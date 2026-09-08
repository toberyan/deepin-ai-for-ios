# UOS AI Android Tailscale Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and install a native Android 16 companion that securely pairs with the UOS AI tailnet listener, resumes shared conversations, controls Agent turns, and handles one-time Agent approvals on a Lenovo Legion Y700 Gen 4.

**Architecture:** A new Kotlin/Compose `android/app` module owns presentation and uses isolated protocol, security, pairing, and WebSocket packages. The UOS AI host is upgraded from a non-secure tailnet WebSocket to a certificate-backed WSS listener and turns the existing one-time pairing URI into a QR code. The existing iOS client uses the same WSS URI after the host upgrade.

**Tech Stack:** Kotlin 2.1, Android Gradle Plugin 8.9, Compose Material 3, OkHttp WebSocket, Kotlin serialization, CameraX, ZXing core, Android Keystore AES-GCM, Qt WebSockets/Network, Vue 3 JSX, `qrcode`.

## Global Constraints

- Compile and target Android 16 / API 36; set `minSdk` to 28.
- Keep the protocol major version at `1` and preserve all existing wire command/event names.
- Bind the UOS listener only to a local Tailscale address and serve only WSS in production.
- The Android and iOS apps must not enable a broad cleartext-traffic exception or public-network fallback.
- Accept pairing hosts only when they are an ASCII `*.ts.net` hostname; the UOS listener itself remains bound to a local Tailscale IP.
- Store only the issued `deviceId` and token, encrypted with device-bound storage. Never log the pairing secret or token.
- The Android wide layout is Y700-first: a persistent navigation pane at `>= 840dp`; compact layouts use one pane at a time.
- Approval controls are one-time and explicit: Approve or Reject. No always-approve state exists.
- Preserve the user-owned untracked translations in `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/plugin-aibar/translations/`.
- Do not depend on Google Play services for QR scanning.

---

## File structure

### Companion repository: `/home/toberyan/orca/workspaces/deepin-ai-for-ios/barramundi`

```text
android/
  build.gradle.kts
  settings.gradle.kts
  gradle/libs.versions.toml
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/org/deepin/uosai/companion/
      MainActivity.kt
      app/CompanionViewModel.kt
      core/pairing/PairingUri.kt
      core/protocol/RemoteFrame.kt
      core/security/DeviceGrantStore.kt
      core/network/CompanionWebSocket.kt
      feature/pairing/PairingScreen.kt
      feature/pairing/QrScanner.kt
      feature/workspaces/WorkspacePane.kt
      feature/conversation/ConversationPane.kt
      ui/CompanionApp.kt
    src/test/java/org/deepin/uosai/companion/core/{pairing,protocol,network}/
    src/androidTest/java/org/deepin/uosai/companion/{security,feature}/
ios/UOSAICompanion/Features/Pairing/PairingURI.swift
ios/UOSAICompanion/Network/CompanionWebSocket.swift
ios/UOSAICompanionTests/PairingURITests.swift
README.md
docs/operations/tailscale-companion.md
```

### UOS AI host repository: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host`

```text
src/remote/remotecompanionserver.{h,cpp}
src/remote/remotecompanionsettings.cpp
src/app/application.{h,cpp}
tests/test_remote_companion_server.cpp
web/package.json
web/src/views/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.tsx
```

## Interfaces produced by this plan

```kotlin
data class PairingUri(
    val host: String,
    val port: Int,
    val pairingSecret: String,
    val expiresAtMs: Long,
    val hostDisplayName: String,
) {
    fun websocketUrl(): HttpUrl
    companion object { fun parse(raw: String, nowMs: Long): PairingUri }
}

interface DeviceGrantStore {
    fun load(): DeviceGrant?
    fun save(grant: DeviceGrant)
    fun clear()
}

interface CompanionSocket {
    suspend fun pair(invitation: PairingUri, deviceName: String): DeviceGrant
    suspend fun connectSavedGrant(): DeviceGrant?
    suspend fun send(command: CommandFrame)
    fun frames(): Flow<InboundFrame>
    fun close()
}

class CompanionViewModel(
    private val socket: CompanionSocket,
    private val grantStore: DeviceGrantStore,
) : ViewModel()
```

```cpp
struct RemoteTlsConfig {
    QString serverName;          // fully-qualified *.ts.net host name
    QString certificatePath;     // PEM certificate path, local to the UOS host
    QString privateKeyPath;      // PEM private key path, local to the UOS host
};

struct RemoteListenerConfig {
    QHostAddress tailscaleAddress;
    quint16 port = 45980;
    bool enabled = false;
    RemoteTlsConfig tls;
};

bool RemoteCompanionServer::start(const RemoteListenerConfig &config);
QString RemoteCompanionServer::lastStartError() const;
```

### Task 1: Make the UOS host WSS-only in production

**Files:**

- Modify: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/remote/remotecompanionserver.h`
- Modify: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/remote/remotecompanionserver.cpp`
- Modify: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/remote/remotecompanionsettings.cpp`
- Modify: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/app/application.{h,cpp}`
- Modify: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/tests/test_remote_companion_server.cpp`

**Consumes:** Existing `RemoteListenerConfig`, `RemoteCompanionServer`, and `RemoteCompanionSettings`.

**Produces:** A certificate-backed listener that returns a stable diagnostic for invalid TLS configuration and only permits an insecure loopback server when the explicit test-only server option is set.

- [ ] **Step 1: Add failing host tests for the secure-listener contract**

Add tests with these assertions before changing the listener:

```cpp
void RemoteCompanionServerTest::rejectsProductionListenerWithoutTls()
{
    RemoteCompanionServer server({&store, &pairing, &ledger, &execution, &dataSource});
    RemoteListenerConfig config {QHostAddress("100.64.0.2"), 45980, true, {}};

    QVERIFY(!server.start(config));
    QCOMPARE(server.lastStartError(), QStringLiteral("TLS certificate, private key, and tailnet hostname are required"));
}

void RemoteCompanionServerTest::allowsPlainLoopbackOnlyWhenTestOptionOptedIn()
{
    RemoteCompanionServerOptions options;
    options.allowLoopbackForTests = true;
    options.allowInsecureLoopbackForTests = true;
    RemoteCompanionServer server({&store, &pairing, &ledger, &execution, &dataSource}, options);

    QVERIFY(server.start({QHostAddress::LocalHost, 0, true, {}}));
    QCOMPARE(server.port() > 0, true);
}
```

- [ ] **Step 2: Run the host test to verify the new test fails for missing symbols/behavior**

Run:

```bash
cmake --build build --target uos-ai-assistant -j2
ctest --test-dir build --output-on-failure -R remote_companion_server
```

Expected: the new test cannot compile until `RemoteTlsConfig`, `lastStartError()`, and `allowInsecureLoopbackForTests` exist.

- [ ] **Step 3: Implement explicit TLS listener configuration**

Replace the inline server member with a factory-created server, populate `QSslConfiguration` only from readable PEM files, and preserve the old non-secure transport solely inside the explicit test mode:

```cpp
bool RemoteCompanionServer::start(const RemoteListenerConfig &config)
{
    m_lastStartError.clear();
    if (isListening()) return false;
    if (!config.enabled || !canListenOn(config.tailscaleAddress)) {
        m_lastStartError = QStringLiteral("select a local Tailscale address");
        return false;
    }

    const bool insecureTestListener = m_options.allowLoopbackForTests
                                      && m_options.allowInsecureLoopbackForTests
                                      && config.tailscaleAddress.isLoopback();
    if (!insecureTestListener && !configureTls(config.tls)) return false;
    return m_server->listen(config.tailscaleAddress, config.port);
}

bool RemoteCompanionServer::configureTls(const RemoteTlsConfig &tls)
{
    if (!tls.serverName.endsWith(QStringLiteral(".ts.net"), Qt::CaseInsensitive)
        || tls.certificatePath.isEmpty() || tls.privateKeyPath.isEmpty()) {
        m_lastStartError = QStringLiteral("TLS certificate, private key, and tailnet hostname are required");
        return false;
    }
    QFile certificateFile(tls.certificatePath);
    QFile keyFile(tls.privateKeyPath);
    if (!certificateFile.open(QIODevice::ReadOnly) || !keyFile.open(QIODevice::ReadOnly)) {
        m_lastStartError = QStringLiteral("TLS certificate or private key cannot be read");
        return false;
    }
    const auto certificates = QSslCertificate::fromData(certificateFile.readAll(), QSsl::Pem);
    const QSslKey key(keyFile.readAll(), QSsl::Rsa, QSsl::Pem, QSsl::PrivateKey);
    if (certificates.isEmpty() || key.isNull()) {
        m_lastStartError = QStringLiteral("TLS certificate or private key is invalid");
        return false;
    }
    QSslConfiguration ssl = QSslConfiguration::defaultConfiguration();
    ssl.setLocalCertificateChain(certificates);
    ssl.setPrivateKey(key);
    m_server->setSslConfiguration(ssl);
    return true;
}
```

Declare the `QFile`, `QSslCertificate`, `QSslConfiguration`, and `QSslKey` includes; create the server with `QWebSocketServer::SecureMode` for production and `NonSecureMode` only for the test loopback factory path. Persist `tls.serverName`, certificate path, and key path in three new `remoteCompanion/tls*` QSettings keys. Expose `lastStartError()` in the public header.

- [ ] **Step 4: Update application configuration and invitation construction**

Extend the channel-facing configuration data to carry TLS values and construct invitations with the validated magic-DNS host rather than a raw `100.x` address:

```cpp
query.addQueryItem(QStringLiteral("v"), QStringLiteral("1"));
query.addQueryItem(QStringLiteral("host"), config.tls.serverName);
query.addQueryItem(QStringLiteral("port"), QString::number(config.port));
query.addQueryItem(QStringLiteral("pairingSecret"), invitation.pairingSecret);
query.addQueryItem(QStringLiteral("expiresAtMs"), QString::number(invitation.expiresAtMs));
query.addQueryItem(QStringLiteral("hostDisplayName"), QHostInfo::localHostName());
```

Return `tlsServerName`, `tlsReady`, and `listenerError` from `remoteCompanionStatus()`. Reject an enabled configuration whose host name is not an ASCII `*.ts.net` suffix, whose PEM paths are blank, or whose selected address is not a local Tailscale address. On failed reconfiguration, restart the previous valid configuration and return the exact `lastStartError()`.

- [ ] **Step 5: Run focused and full host verification**

Run:

```bash
cmake --build build --target uos-ai-assistant -j2
ctest --test-dir build --output-on-failure -R remote
```

Expected: the production missing-TLS test passes, the isolated loopback protocol test stays on its explicit test-only `ws://` listener, and all remote tests pass.

- [ ] **Step 6: Commit the host transport change without user-owned translations**

Run:

```bash
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host add src/remote/remotecompanionserver.h src/remote/remotecompanionserver.cpp src/remote/remotecompanionsettings.cpp src/app/application.h src/app/application.cpp tests/test_remote_companion_server.cpp
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host commit -m "feat: secure remote companion listener"
```

### Task 2: Show the exact secure invitation as a desktop QR code

**Files:**

- Modify: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/src/gui/web/remotecompanionchannel.cpp`
- Modify: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web/package.json`
- Create: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web/src/views/window/mainwindow/page/settings/remotecompanion/InvitationQr.tsx`
- Modify: `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host/web/src/views/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.tsx`

**Consumes:** Task 1's `uri`, `expiresAtMs`, and `listenerError` values.

**Produces:** A local-only QR visual whose payload is byte-for-byte the invitation URI returned by the native channel, plus explicit secure-host configuration controls and errors.

- [ ] **Step 1: Add the QR library and a typed rendering component**

Add `"qrcode": "^1.5.4"` to `web/package.json` dependencies. Implement the component to derive a data URL from its `uri` prop only:

```tsx
import QRCode from "qrcode";
import { defineComponent, ref, watch } from "vue";

export default defineComponent({
    name: "InvitationQr",
    props: { uri: { type: String, required: true } },
    setup(props) {
        const source = ref("");
        watch(() => props.uri, async (uri) => {
            source.value = uri ? await QRCode.toDataURL(uri, {
                errorCorrectionLevel: "M", margin: 1, width: 256,
            }) : "";
        }, { immediate: true });
        return { source };
    },
    render() {
        return this.source ? <img src={this.source} alt="UOS AI pairing invitation QR code" width="256" height="256" /> : null;
    },
});
```

- [ ] **Step 2: Wire TLS configuration and QR rendering into the existing page**

Extend `RemoteStatus` with `tlsServerName`, `tlsReady`, and `listenerError`; retain form state for `tlsServerName`, `certificatePath`, and `privateKeyPath`; include those values in the JSON sent to `configure`. Render the QR component only after `createInvitation` returns its URI:

```tsx
{this.invitationUri ? (
    <div style={{ marginTop: "12px" }}>
        <InvitationQr uri={this.invitationUri} />
        <textarea readOnly value={this.invitationUri} style={{ width: "100%", minHeight: "72px" }} />
        <p>Expires: {this.invitationExpiry}</p>
        <TextButton text="Copy invitation" onClick={this.copyInvitation} />
    </div>
) : null}
```

Change the title and explanatory copy from “iOS Companion” / “Pair iPhone” to “Mobile Companion” / “Pair a mobile device”. Disable invitation creation unless the listener is running with TLS, and display the native `listenerError` unchanged when configuration fails.

- [ ] **Step 3: Type-check and build the desktop web bundle**

Run:

```bash
npm install
npm run type-check
npm run build
```

Expected: the web project type-checks; creating an invitation draws a QR code whose decoded value is the same string displayed in the read-only textarea.

- [ ] **Step 4: Commit desktop pairing UI changes**

Run:

```bash
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host add web/package.json web/package-lock.json web/src/views/window/mainwindow/page/settings/remotecompanion/InvitationQr.tsx web/src/views/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.tsx src/gui/web/remotecompanionchannel.cpp
git -C /home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host commit -m "feat: add mobile companion pairing QR"
```

### Task 3: Bootstrap the Android 16 application and pure protocol layer with tests

**Files:**

- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle/libs.versions.toml`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradlew`, `android/gradlew.bat`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/org/deepin/uosai/companion/core/protocol/RemoteFrame.kt`
- Create: `android/app/src/main/java/org/deepin/uosai/companion/core/pairing/PairingUri.kt`
- Test: `android/app/src/test/java/org/deepin/uosai/companion/core/protocol/RemoteFrameTest.kt`
- Test: `android/app/src/test/java/org/deepin/uosai/companion/core/pairing/PairingUriTest.kt`

**Consumes:** Protocol v1 in `docs/protocol/remote-companion-v1.md`.

**Produces:** An API-36 Android app that can serialize all client frames and reject unsafe or expired pairing URIs before any network connection is attempted.

- [ ] **Step 1: Generate a versioned Gradle wrapper and application skeleton**

Create a Kotlin DSL app with the Android application, Kotlin Android, Kotlin serialization, and Compose compiler plugins. Configure:

```kotlin
android {
    namespace = "org.deepin.uosai.companion"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.deepin.uosai.companion"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
}
```

Declare dependencies for Compose Material 3, lifecycle ViewModel, Kotlin coroutines, Kotlin serialization JSON, OkHttp, CameraX, ZXing core, JUnit 4, and AndroidX test/Compose UI test. In the manifest declare `INTERNET` and `CAMERA`, register `MainActivity` with `exported="true"`, and add an `uos-ai`/`pair` intent filter. Do not set `usesCleartextTraffic="true"` or a permissive network security config.

- [ ] **Step 2: Write failing pairing URI tests**

```kotlin
class PairingUriTest {
    private val nowMs = 1_700_000_000_000L

    @Test fun parsesAValidTailnetHostnameAsWss() {
        val invite = PairingUri.parse(
            "uos-ai://pair?v=1&host=uos-ai.example.ts.net&port=45980&pairingSecret=abc&expiresAtMs=1700000300000&hostDisplayName=UOS-AI",
            nowMs,
        )
        assertEquals("wss://uos-ai.example.ts.net:45980/", invite.websocketUrl().toString())
    }

    @Test fun rejectsExpiredPublicAndUnexpectedInvitations() {
        assertFailsWith<PairingUriError.Expired> {
            PairingUri.parse("uos-ai://pair?v=1&host=uos-ai.example.ts.net&port=45980&pairingSecret=abc&expiresAtMs=1699999999999&hostDisplayName=UOS-AI", nowMs)
        }
        assertFailsWith<PairingUriError.UnsafeHost> {
            PairingUri.parse("uos-ai://pair?v=1&host=example.com&port=45980&pairingSecret=abc&expiresAtMs=1700000300000&hostDisplayName=UOS-AI", nowMs)
        }
    }
}
```

- [ ] **Step 3: Run the URI tests and verify RED**

Run:

```bash
cd android
./gradlew :app:testDebugUnitTest --tests '*PairingUriTest'
```

Expected: compilation fails because `PairingUri` and `PairingUriError` do not exist.

- [ ] **Step 4: Implement strict URI parsing**

Use `URI` plus a percent-decoding query parser that rejects duplicate keys. Require the exact key set `v`, `host`, `port`, `pairingSecret`, `expiresAtMs`, and `hostDisplayName`; require `v == "1"`; require a non-empty secret and display name; require `expiresAtMs > nowMs`; enforce the `*.ts.net` host rule in the global constraints. Build the endpoint with `HttpUrl.Builder().scheme("wss")` and never accept a scheme supplied by the invitation.

- [ ] **Step 5: Write failing protocol frame tests**

```kotlin
class RemoteFrameTest {
    @Test fun startTurnUsesTheV1WireName() {
        val json = RemoteJson.encodeToString(CommandFrame.startTurn(
            requestId = "r1", workspaceId = "w1", conversationId = "c1",
            message = "continue", assistantId = "uos-claw", modelId = "deepseek-chat",
        ))
        assertTrue(json.contains("\"command\":\"start_turn\""))
        assertTrue(json.contains("\"major\":1"))
    }

    @Test fun unknownEventsRemainDecodable() {
        val frame = RemoteFrame.parseInbound("""{"kind":"event","protocol":{"major":1,"minor":0},"conversationId":"c1","sequence":4,"event":"future_event","payload":{}}""")
        assertEquals(RemoteEvent.Unknown("future_event"), (frame as InboundFrame.Event).event.event)
    }
}
```

- [ ] **Step 6: Run protocol tests and verify RED**

Run:

```bash
cd android
./gradlew :app:testDebugUnitTest --tests '*RemoteFrameTest'
```

Expected: compilation fails because the frame model is absent.

- [ ] **Step 7: Implement protocol v1 JSON models and verify GREEN**

Model `PairFrame`, `AuthenticateFrame`, `CommandFrame`, `PairingGrant`, `RemoteError`, and sealed `InboundFrame`. Map command values to `list_workspaces`, `get_conversation`, `subscribe`, `start_turn`, `cancel`, and `approval`; map events exactly as documented and retain unknown event strings. Reject an inbound protocol whose major is not `1`.

Run:

```bash
cd android
./gradlew :app:testDebugUnitTest --tests '*PairingUriTest' --tests '*RemoteFrameTest'
```

Expected: all pure protocol tests pass.

- [ ] **Step 8: Commit Android foundation**

Run:

```bash
git add android
git commit -m "feat: scaffold Android companion protocol"
```

### Task 4: Add device-bound grant storage and resilient authenticated WebSocket transport

**Files:**

- Create: `android/app/src/main/java/org/deepin/uosai/companion/core/security/DeviceGrantStore.kt`
- Create: `android/app/src/main/java/org/deepin/uosai/companion/core/network/CompanionWebSocket.kt`
- Test: `android/app/src/test/java/org/deepin/uosai/companion/core/network/ConnectionReducerTest.kt`
- Test: `android/app/src/androidTest/java/org/deepin/uosai/companion/core/security/KeystoreDeviceGrantStoreTest.kt`

**Consumes:** Task 3's `PairingUri`, protocol frames, and WSS-only endpoint.

**Produces:** Keystore-encrypted grants plus a stateful socket client with replay-safe command identifiers and bounded reconnect behavior.

- [ ] **Step 1: Write failing connection reducer tests**

Keep socket state transitions pure so they can be tested without a device or a live host:

```kotlin
class ConnectionReducerTest {
    @Test fun authenticationFailureClearsGrantAndStopsRetrying() {
        val state = ConnectionState.Authenticating
        val next = reduce(state, SocketSignal.AuthenticationFailed)
        assertEquals(ConnectionState.NeedsPairing, next.state)
        assertTrue(next.clearGrant)
        assertFalse(next.scheduleReconnect)
    }

    @Test fun networkFailureCapsReconnectDelayAtThirtySeconds() {
        val next = reduce(ConnectionState.Reconnecting(attempt = 12), SocketSignal.NetworkFailure)
        assertEquals(ConnectionState.Reconnecting(attempt = 13), next.state)
        assertEquals(30_000L, next.retryAfterMs)
    }
}
```

- [ ] **Step 2: Run reducer tests and verify RED**

Run:

```bash
cd android
./gradlew :app:testDebugUnitTest --tests '*ConnectionReducerTest'
```

Expected: compilation fails because `ConnectionState`, `SocketSignal`, and `reduce` do not exist.

- [ ] **Step 3: Implement Keystore AES-GCM grant persistence and the reducer**

Create an AES-256 GCM key with alias `org.deepin.uosai.companion.devicegrant` in `AndroidKeyStore`; write the random 12-byte IV and ciphertext (Base64) into private `SharedPreferences`; authenticate encryption with the fixed AAD `uos-ai-device-grant-v1`. Store only a serialized `DeviceGrant(deviceId, token, host, port, hostDisplayName)`. On authentication failure, decryption failure, or corrupt data, delete the preference value and return `null`.

Make the reducer produce `NeedsPairing` and `clearGrant=true` for `unauthenticated` or `protocol_incompatible`; use retry delays of 1, 2, 4, 8, 16, then 30 seconds for transient network loss; use no retry for a user close.

- [ ] **Step 4: Add and run a device grant instrumentation test**

```kotlin
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceGrantStoreTest {
    @Test fun roundTripsAndClearsAnEncryptedGrant() {
        val store = KeystoreDeviceGrantStore(ApplicationProvider.getApplicationContext())
        val grant = DeviceGrant("android-1", "secret-token", "uos-ai.example.ts.net", 45980, "UOS AI")
        store.save(grant)
        assertEquals(grant, store.load())
        store.clear()
        assertNull(store.load())
    }
}
```

Run:

```bash
cd android
./gradlew :app:testDebugUnitTest --tests '*ConnectionReducerTest'
```

Expected: reducer tests pass; the instrumentation test compiles and is reserved for Task 7's connected device run.

- [ ] **Step 5: Implement `CompanionWebSocket` using OkHttp**

Open only `PairingUri.websocketUrl()` or an equivalent WSS URL built from a stored grant. Send `pair`, wait for `pairing_granted`, persist the grant, send `authenticate`, and expose parsed inbound frames as a `SharedFlow`. Retain each pending `CommandFrame` by request ID until `command_ack` or an error arrives; after a transient reconnect, authenticate, subscribe with stored conversation cursors, and resend those exact frames with their original request IDs. Close and clear the grant for authentication and protocol-major errors.

- [ ] **Step 6: Commit secure transport code**

Run:

```bash
git add android/app/src/main/java/org/deepin/uosai/companion/core android/app/src/test android/app/src/androidTest
git commit -m "feat: add Android companion secure transport"
```

### Task 5: Implement the Y700-responsive Compose UI, pairing flow, and Agent controls

**Files:**

- Create: `android/app/src/main/java/org/deepin/uosai/companion/MainActivity.kt`
- Create: `android/app/src/main/java/org/deepin/uosai/companion/app/CompanionViewModel.kt`
- Create: `android/app/src/main/java/org/deepin/uosai/companion/ui/CompanionApp.kt`
- Create: `android/app/src/main/java/org/deepin/uosai/companion/feature/pairing/PairingScreen.kt`
- Create: `android/app/src/main/java/org/deepin/uosai/companion/feature/pairing/QrScanner.kt`
- Create: `android/app/src/main/java/org/deepin/uosai/companion/feature/workspaces/WorkspacePane.kt`
- Create: `android/app/src/main/java/org/deepin/uosai/companion/feature/conversation/ConversationPane.kt`
- Test: `android/app/src/test/java/org/deepin/uosai/companion/feature/ResponsiveLayoutTest.kt`
- Test: `android/app/src/androidTest/java/org/deepin/uosai/companion/feature/CompanionFlowTest.kt`

**Consumes:** Tasks 3–4's parser, socket, `DeviceGrantStore`, and state reducer.

**Produces:** A tablet-first app with manual/QR pairing, workspace navigation, conversation continuation, Agent turn controls, and non-dismissible approval decisions.

- [ ] **Step 1: Write failing responsive layout tests**

```kotlin
class ResponsiveLayoutTest {
    @Test fun y700WidthUsesTwoPanes() {
        assertEquals(PaneLayout.Dual, PaneLayout.forWidth(840))
    }

    @Test fun compactWidthUsesSinglePane() {
        assertEquals(PaneLayout.Single, PaneLayout.forWidth(839))
    }
}
```

- [ ] **Step 2: Run the layout test and verify RED**

Run:

```bash
cd android
./gradlew :app:testDebugUnitTest --tests '*ResponsiveLayoutTest'
```

Expected: compilation fails because `PaneLayout` is absent.

- [ ] **Step 3: Implement view-model state and responsive panes**

Use `WindowSizeClass` / a `PaneLayout.forWidth(widthDp)` adapter with the exact `840dp` threshold. `CompanionViewModel` owns `ConnectionState`, workspace blocks, selected workspace/conversation, transcript, sequence cursors, active turn, pending approval, and displayable protocol errors. Its methods are:

```kotlin
fun pair(rawInvitation: String, deviceName: String)
fun reconnect()
fun selectWorkspace(workspaceId: String)
fun openConversation(conversationId: String)
fun startTurn(message: String, assistantId: String, modelId: String)
fun cancelTurn()
fun answerApproval(approvalId: String, approved: Boolean)
```

`openConversation` sends `get_conversation`, loads its render snapshot, then sends `subscribe` with its latest sequence. `startTurn` appends an optimistic user entry and sends the selected workspace/conversation IDs. Disable Send while a turn is active; replace it with Cancel. On `turn_finished` or `turn_failed`, clear `activeTurn`. On `agent_approval_requested`, put its `approvalId`, action type, title, and already-redacted details in pending state.

- [ ] **Step 4: Implement paste and QR pairing**

`PairingScreen` has an editable invitation text field, an editable device name initialized from `Build.MODEL`, a Pair button, and a Scan QR button. `QrScanner` requests camera permission, binds `CameraX` analysis to the lifecycle, passes `ImageProxy` luminance data to ZXing, and invokes this callback only for a successfully parsed pairing URI:

```kotlin
onScanned = { raw ->
    viewModel.pair(rawInvitation = raw, deviceName = deviceName)
}
```

Close the scanner after the first valid payload. Bad QR payloads remain on the scanner and show “This is not a valid UOS AI invitation”; they must not create a network connection.

- [ ] **Step 5: Implement conversation and approval surfaces**

In dual-pane mode render `WorkspacePane` at a fixed 320dp width and `ConversationPane` in the remaining width. In single-pane mode, selection navigates forward and the top app bar returns to workspace navigation. Render user and assistant messages with distinct Material 3 containers. Put assistant/model IDs in the composer, defaulted to `uos-claw` and `deepseek-chat` but editable.

Use a `ModalBottomSheet` with `confirmValueChange = { false }` while an approval is pending. Disable its buttons immediately after one is pressed and invoke exactly one command:

```kotlin
viewModel.answerApproval(approval.id, approved = true)
viewModel.answerApproval(approval.id, approved = false)
```

- [ ] **Step 6: Write and run the instrumented UI flow test**

```kotlin
@RunWith(AndroidJUnit4::class)
class CompanionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun approvalSheetCannotBeDismissedWithoutAChoice() {
        compose.setContent {
            MaterialTheme {
                ApprovalSheet(
                    approval = AgentApproval("approval-1", "command", "Agent approval required", emptyMap()),
                    submitting = false,
                    onDecision = {},
                )
            }
        }
        compose.onNodeWithText("Approve").assertIsDisplayed()
        compose.onNodeWithText("Reject").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeDown() }
        compose.onNodeWithText("Agent approval required").assertIsDisplayed()
    }
}
```

Run:

```bash
cd android
./gradlew :app:testDebugUnitTest
```

Expected: all JVM tests pass; the UI test compiles for Task 7's connected-device execution.

- [ ] **Step 7: Commit the tablet UI**

Run:

```bash
git add android/app/src/main android/app/src/test android/app/src/androidTest
git commit -m "feat: add Android tablet companion UI"
```

### Task 6: Migrate the existing iOS client to the secure host endpoint

**Files:**

- Modify: `ios/UOSAICompanion/Features/Pairing/PairingURI.swift`
- Modify: `ios/UOSAICompanion/Network/CompanionWebSocket.swift`
- Modify: `ios/UOSAICompanionTests/PairingURITests.swift`

**Consumes:** Task 1's WSS listener and pairing URI hostname field.

**Produces:** Existing iOS pairing and reconnect flows use WSS rather than an ATS-blocked `ws://` URL.

- [ ] **Step 1: Update the pairing URI test to demand WSS**

```swift
func testParsesValidInvitationAsSecureWebSocket() throws {
    let url = try XCTUnwrap(URL(string: "uos-ai://pair?v=1&host=uos-ai.example.ts.net&port=45980&pairingSecret=abc&expiresAtMs=1700000300000&hostDisplayName=UOS-AI"))
    let invitation = try PairingURI(url: url, now: now)
    XCTAssertEqual(invitation.webSocketURL?.absoluteString, "wss://uos-ai.example.ts.net:45980")
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run on the Mac with Xcode installed:

```bash
cd ios
xcodebuild -project UOSAICompanion.xcodeproj -scheme UOSAICompanion -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -only-testing:UOSAICompanionTests/PairingURITests test
```

Expected: the test fails because the current client constructs `ws://`.

- [ ] **Step 3: Implement the secure endpoint migration**

Change both `PairingURI.webSocketURL` and `CompanionWebSocket.webSocketURL(for:)` to build `wss://` URLs. Add the same tailnet host validation used by Android before constructing URLs. Keep the existing exact URI parameter allow-list and do not add an ATS exception to `Info.plist`.

- [ ] **Step 4: Run all iOS unit tests and commit**

Run:

```bash
cd ios
xcodebuild -project UOSAICompanion.xcodeproj -scheme UOSAICompanion -destination 'platform=iOS Simulator,name=iPhone 16 Pro' test
```

Then run:

```bash
git add ios/UOSAICompanion/Features/Pairing/PairingURI.swift ios/UOSAICompanion/Network/CompanionWebSocket.swift ios/UOSAICompanionTests/PairingURITests.swift
git commit -m "fix: use secure companion websocket on iOS"
```

### Task 7: Build, install, and verify on the physical Y700 through ADB

**Files:**

- Modify: `README.md`
- Modify: `docs/operations/tailscale-companion.md`

**Consumes:** A host that has a readable Tailscale certificate/private key, a running WSS listener, one selected shared workspace with an existing conversation, and a Y700 connected with USB debugging authorized.

**Produces:** A debug APK installed and launched on the Y700, with captured evidence of the required end-to-end flows.

- [ ] **Step 1: Add documented prerequisites and recovery steps**

Document the complete operational sequence: install and sign in to Tailscale on UOS and Android; configure its `.ts.net` host and local PEM paths in UOS AI; select a local tailnet bind address and at least one shared workspace; create a one-time QR/link invitation; pair; revoke a device; and recover from a lost Android device by revoking it. State that WSS certificate provisioning must be complete before invitations are available and that the listener never opens on LAN/public interfaces.

- [ ] **Step 2: Run Android static, JVM, and assembly verification**

Run:

```bash
cd android
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: lint and all unit tests pass; the debug APK exists at `android/app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Require an authorized physical device before mutation**

Run:

```bash
adb devices -l
```

Expected: exactly one line ending in `device` for the Lenovo Y700. If the list is empty or says `unauthorized`, unlock the Y700, accept the RSA debugging prompt, and rerun this command; do not run install commands against an unknown device.

- [ ] **Step 4: Install and launch the debug APK on the one attached Y700**

Run:

```bash
adb -d install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -d shell am force-stop org.deepin.uosai.companion
adb -d shell am start -W -n org.deepin.uosai.companion/.MainActivity
adb -d shell wm size
```

Expected: install reports `Success`; launch returns `Status: ok`; `wm size` shows the connected tablet's physical display configuration.

- [ ] **Step 5: Run connected Android tests**

Run:

```bash
cd android
./gradlew :app:connectedDebugAndroidTest
```

Expected: Keystore persistence and Compose approval UI tests pass on the Y700.

- [ ] **Step 6: Execute the real tailnet acceptance flow and collect evidence**

1. On UOS AI, enable the secure listener, select the existing workspace, create an invitation, and display its QR code.
2. On Y700, scan that QR and confirm the paired host name and authenticated state.
3. Open the shared workspace and the pre-existing conversation; verify the transcript snapshot appears.
4. Send a continuation message; verify the UOS desktop conversation receives the same turn and that the streamed response appears on Y700.
5. During an active Agent turn, press Cancel on Y700 and verify the UOS conversation reports the same terminated turn.
6. Trigger one Agent approval from UOS AI; verify the Y700 bottom sheet names the action and has only Approve and Reject. Choose one response and verify the UOS Agent receives it exactly once.
7. In UOS AI, revoke the Y700 device; reconnect from the tablet and verify it returns to pairing after the host responds `unauthenticated`.

- [ ] **Step 7: Commit documentation and record the verified commands/results**

Run:

```bash
git add README.md docs/operations/tailscale-companion.md
git commit -m "docs: add Android companion deployment guide"
git status --short
```

Expected: no uncommitted companion repository changes remain. The host repository status may still show its pre-existing untracked translation files, which must remain untouched.

## Plan self-review

- **Spec coverage:** Tasks 1–2 cover WSS-only host transport and QR pairing. Tasks 3–5 cover Android 16, tailnet URI validation, Keystore credentials, reconnection, Y700 layout, conversations, Agent control, and approvals. Task 6 preserves the existing iOS companion after the host security upgrade. Task 7 covers build, ADB installation, and all requested physical acceptance flows.
- **No placeholders:** All creation/modification paths, interfaces, test commands, security bounds, and external prerequisites are named above. The dynamic USB serial is intentionally addressed with `adb -d`, which refuses an ambiguous device selection.
- **Type consistency:** `PairingUri`, `DeviceGrantStore`, `CompanionSocket`, `CompanionViewModel`, `RemoteTlsConfig`, and `RemoteListenerConfig` have single definitions used consistently across their tasks.
