package org.deepin.uosai.companion.core.network

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.deepin.uosai.companion.core.pairing.PairingUri
import org.deepin.uosai.companion.core.protocol.AuthenticateFrame
import org.deepin.uosai.companion.core.protocol.CommandFrame
import org.deepin.uosai.companion.core.protocol.InboundFrame
import org.deepin.uosai.companion.core.protocol.PairFrame
import org.deepin.uosai.companion.core.protocol.PairPayload
import org.deepin.uosai.companion.core.protocol.RemoteEvent
import org.deepin.uosai.companion.core.protocol.RemoteFrame
import org.deepin.uosai.companion.core.protocol.RemoteJson
import org.deepin.uosai.companion.core.security.DeviceGrant
import org.deepin.uosai.companion.core.security.DeviceGrantStore

interface CompanionSocket {
    val connectionState: StateFlow<ConnectionState>

    suspend fun pair(invitation: PairingUri, deviceName: String): DeviceGrant
    suspend fun connectSavedGrant(): DeviceGrant?
    suspend fun send(command: CommandFrame)
    suspend fun request(command: CommandFrame): InboundFrame
    fun frames(): Flow<InboundFrame>
    fun close()
}

class CompanionWebSocket(
    private val grantStore: DeviceGrantStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : CompanionSocket {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val incoming = Channel<InboundFrame>(Channel.BUFFERED)
    private val emittedFrames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
    private val mutableConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private var socket: WebSocket? = null
    private var manuallyClosed = false
    private var reconnecting = false

    override val connectionState: StateFlow<ConnectionState> = mutableConnectionState.asStateFlow()

    override suspend fun pair(invitation: PairingUri, deviceName: String): DeviceGrant = operationMutex.withLock {
        open(invitation.webSocketUrl())
        sendRaw(RemoteJson.encode(PairFrame(payload = PairPayload(invitation.pairingSecret, deviceName))))
        val pairingGrant = awaitFrame { it is InboundFrame.PairingGranted }
            .let { it as InboundFrame.PairingGranted }.grant
        val grant = invitation.toDeviceGrant(pairingGrant)
        grantStore.save(grant)
        try {
            authenticate(grant)
        } catch (error: Throwable) {
            grantStore.clear()
            throw error
        }
        grant
    }

    override suspend fun connectSavedGrant(): DeviceGrant? = operationMutex.withLock {
        val grant = grantStore.load() ?: return null
        open("wss://${grant.host}:${grant.port}/")
        authenticate(grant)
        grant
    }

    override suspend fun send(command: CommandFrame) {
        sendRaw(RemoteJson.encode(command))
    }

    override suspend fun request(command: CommandFrame): InboundFrame {
        send(command)
        return awaitFrame { frame ->
            frame is InboundFrame.Failure && frame.requestId == command.requestId ||
                frame is InboundFrame.Event &&
                frame.event.event == RemoteEvent.CommandAck &&
                frame.event.payload["requestId"]?.let { value ->
                    (value as? kotlinx.serialization.json.JsonPrimitive)?.content == command.requestId
                } == true
        }
    }

    override fun frames(): Flow<InboundFrame> = emittedFrames.asSharedFlow()

    override fun close() {
        manuallyClosed = true
        socket?.close(1000, "Client closed")
        socket = null
        mutableConnectionState.value = ConnectionState.Disconnected
    }

    private suspend fun authenticate(grant: DeviceGrant) {
        mutableConnectionState.value = ConnectionState.Connecting
        sendRaw(RemoteJson.encode(AuthenticateFrame(deviceId = grant.pairingGrant.deviceId, token = grant.pairingGrant.token)))
        when (val response = awaitFrame { it is InboundFrame.Authenticated || it is InboundFrame.Failure }) {
            is InboundFrame.Authenticated -> mutableConnectionState.value = ConnectionState.Connected
            is InboundFrame.Failure -> {
                mutableConnectionState.value = ConnectionState.AuthenticationRequired
                throw CompanionSocketException(response.error.code, response.error.message)
            }
            else -> error("The remote companion sent an invalid authentication frame")
        }
    }

    private suspend fun open(url: String) {
        close()
        manuallyClosed = false
        mutableConnectionState.value = ConnectionState.Connecting
        val opened = CompletableDeferred<Unit>()
        val nextSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (socket != null && webSocket !== socket) return
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (socket != null && webSocket !== socket) return
                    receiveFrame(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (socket != null && webSocket !== socket) return
                    receiveFrame(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== socket) return
                    if (!opened.isCompleted) opened.completeExceptionally(t)
                    if (!manuallyClosed) scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== socket) return
                    if (!manuallyClosed) scheduleReconnect()
                }
            },
        )
        socket = nextSocket
        withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }
    }

    private fun receiveFrame(text: String) {
        val frame = try {
            RemoteFrame.parseInbound(text)
        } catch (_: Exception) {
            return
        }
        incoming.trySend(frame)
        emittedFrames.tryEmit(frame)
    }

    private suspend fun sendRaw(text: String) {
        check(socket?.send(text) == true) { "Remote companion WebSocket is not connected" }
    }

    private suspend fun awaitFrame(matches: (InboundFrame) -> Boolean): InboundFrame =
        withTimeout(FRAME_TIMEOUT_MS) {
            while (true) {
                val frame = incoming.receive()
                if (matches(frame)) return@withTimeout frame
            }
            error("unreachable")
        }

    private fun scheduleReconnect() {
        if (reconnecting) return
        val grant = grantStore.load() ?: run {
            mutableConnectionState.value = ConnectionState.Disconnected
            return
        }
        reconnecting = true
        scope.launchSafe {
            try {
                for (attempt in 1..MAX_RETRIES) {
                    val retryAfterMs = BASE_RETRY_MS * (1L shl (attempt - 1))
                    mutableConnectionState.value = ConnectionState.Reconnecting(attempt, retryAfterMs)
                    delay(retryAfterMs)
                    try {
                        operationMutex.withLock {
                            open("wss://${grant.host}:${grant.port}/")
                            authenticate(grant)
                        }
                        return@launchSafe
                    } catch (error: CompanionSocketException) {
                        mutableConnectionState.value = ConnectionState.AuthenticationRequired
                        return@launchSafe
                    } catch (_: Throwable) {
                        // Try the next bounded interval. No secrets are logged here.
                    }
                }
                mutableConnectionState.value = ConnectionState.Disconnected
            } finally {
                reconnecting = false
            }
        }
    }

    private fun CoroutineScope.launchSafe(block: suspend () -> Unit) = launch { block() }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val FRAME_TIMEOUT_MS = 15_000L
        const val MAX_RETRIES = 4
        const val BASE_RETRY_MS = 1_000L
    }
}

internal fun PairingUri.toDeviceGrant(pairingGrant: org.deepin.uosai.companion.core.protocol.PairingGrant) = DeviceGrant(
    pairingGrant = pairingGrant,
    host = host,
    port = port,
    hostDisplayName = hostDisplayName,
    transport = transport,
    tlsSpkiSha256 = tlsSpkiSha256,
)

class CompanionSocketException(val code: String, override val message: String) : IllegalStateException(message)
