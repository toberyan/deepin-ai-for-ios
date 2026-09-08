package org.deepin.uosai.companion.core.network

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object AuthenticationRequired : ConnectionState
    data class Reconnecting(val attempt: Int, val retryAfterMs: Long) : ConnectionState
}

enum class ConnectionEvent {
    BeginConnection,
    Connected,
    ConnectionLost,
    AuthenticationFailed,
    Disconnect,
}

/** Pure state machine: reconnects at 1, 2, 4 and 8 seconds, then stops. */
object ConnectionReducer {
    fun reduce(state: ConnectionState, event: ConnectionEvent): ConnectionState = when (event) {
        ConnectionEvent.BeginConnection -> ConnectionState.Connecting
        ConnectionEvent.Connected -> ConnectionState.Connected
        ConnectionEvent.AuthenticationFailed -> ConnectionState.AuthenticationRequired
        ConnectionEvent.Disconnect -> ConnectionState.Disconnected
        ConnectionEvent.ConnectionLost -> when (state) {
            ConnectionState.Connected, ConnectionState.Connecting -> reconnect(1)
            is ConnectionState.Reconnecting -> if (state.attempt >= MAX_RETRIES) {
                ConnectionState.Disconnected
            } else {
                reconnect(state.attempt + 1)
            }
            else -> ConnectionState.Disconnected
        }
    }

    private fun reconnect(attempt: Int) = ConnectionState.Reconnecting(
        attempt = attempt,
        retryAfterMs = BASE_RETRY_MS * (1L shl (attempt - 1)),
    )

    private const val MAX_RETRIES = 4
    private const val BASE_RETRY_MS = 1_000L
}
