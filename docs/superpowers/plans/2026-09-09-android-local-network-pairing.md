# Android Local Network Pairing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the desktop/Android `tailscale cert` pairing dependency with secure QR-pinned WSS over a selected local IPv4 address, and verify Android pairing end to end.

**Architecture:** The desktop generates and retains a local ECDSA self-signed TLS identity, binds the remote WebSocket server to one RFC1918 or Tailscale CGNAT address, and embeds that identity's SPKI SHA-256 pin in a v2 `uos-ai://pair` QR URI. Android accepts a v2 certificate only in a per-connection TLS client whose trust manager validates that exact pin; v1 Tailscale invitations remain parseable for installed clients but are not emitted by the desktop.

**Tech Stack:** Qt 6/C++17, OpenSSL libcrypto, Qt WebSockets, Vue 3/TSX/Vite, Android Kotlin/Compose, OkHttp 4.12, JUnit 4, MockWebServer and okhttp-tls test fixtures.

## Global Constraints

- Current release scope is Android only; do not modify or validate iOS.
- Do not call `tailscale cert`, require Tailscale admin-console configuration, launch a Tailscale login flow, use Funnel, or implement Relay.
- Listen only on one selected RFC1918 IPv4 address or a `100.64.0.0/10` Tailscale address; reject Any, loopback, link-local and public addresses in production.
- Keep WebSocket transport WSS-only. Android must neither use `ws://` nor install a global trust override.
- QR v2 is single-use and keeps the existing five-minute invitation TTL; it may contain an invitation secret but never a device token or private key.
- Preserve workspace allowlists, token hashes, device revocation and all unrelated user changes.

## Execution precondition

The intended host source is `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host`, but its `.git` points at the missing `/home/toberyan/workspace/uos-ai-0908/uos-ai/.git/worktrees/uos-ai-ios-tailscale-host`. Before Task 1, the repository owner must provide/restore a canonical Git worktree for this source. Do not run `git init`, `git reset`, or overwrite the directory. Set `HOST` below to that restored path. Android work is in `/home/toberyan/workspace/uos-ai-0908/deepin-ai-for-ios`.

## File structure

| Path | Responsibility |
| --- | --- |
| `$HOST/src/remote/localnetworkdiscovery.{h,cpp}` | Pure filtering of eligible local addresses plus OS interface enumeration. |
| `$HOST/src/remote/localtlsidentity.{h,cpp}` | AppData self-signed ECDSA identity, restrictive file permissions, SPKI fingerprint. |
| `$HOST/src/remote/remotecompanionserver.{h,cpp}` | Generic local-address WSS listener; no Tailnet hostname requirement. |
| `$HOST/src/remote/remotecompanionsettings.cpp` | Persist generic listener address and local TLS metadata; disable legacy Tailnet configuration. |
| `$HOST/src/app/application.{h,cpp}` and `$HOST/src/gui/web/remotecompanionchannel.{h,cpp}` | Validate prepare request, start listener, and emit v2 invitation. |
| `$HOST/web/src/views/window/mainwindow/page/settings/remotecompanion/*` and matching CSS | Orca-inspired local-network pairing page. |
| `android/.../core/pairing/PairingUri.kt` | Strict v1/v2 URI parser and transport model. |
| `android/.../core/network/PinnedTlsClientFactory.kt` | Connection-scoped self-signed certificate pinning. |
| `android/.../core/network/CompanionWebSocket.kt` and `core/security/DeviceGrantStore.kt` | Correct client selection and persistence for v2 grants. |

---

### Task 1: Add host-side eligible-address discovery and local TLS identity

**Files:**
- Create: `$HOST/src/remote/localnetworkdiscovery.h`, `$HOST/src/remote/localnetworkdiscovery.cpp`
- Create: `$HOST/src/remote/localtlsidentity.h`, `$HOST/src/remote/localtlsidentity.cpp`
- Create: `$HOST/tests/test_localnetworkdiscovery.cpp`, `$HOST/tests/test_localtlsidentity.cpp`
- Modify: `$HOST/tests/CMakeLists.txt`, `$HOST/src/CMakeLists.txt`

**Interfaces:**

```cpp
struct LocalNetworkAddress { QHostAddress address; QString interfaceName; bool isTailscale = false; };
bool isEligibleCompanionAddress(const QHostAddress &address);
QList<LocalNetworkAddress> discoverLocalNetworkAddresses();

struct LocalTlsIdentity { QString certificatePath; QString privateKeyPath; QString spkiSha256; };
struct LocalTlsIdentityResult { std::optional<LocalTlsIdentity> identity; QString error; };
LocalTlsIdentityResult loadOrCreateLocalTlsIdentity(const QString &appDataDirectory);
```

- [ ] **Step 1: Write the failing discovery and identity tests.** Test `192.168.1.7`, `10.0.0.7`, `172.16.0.7`, and `100.100.178.97` as accepted; `127.0.0.1`, `0.0.0.0`, `169.254.1.1`, `8.8.8.8`, and IPv6 as rejected. In a `QTemporaryDir`, call `loadOrCreateLocalTlsIdentity` twice and assert identical `spkiSha256`, readable PEM files, 32 decoded SHA-256 bytes, and owner-only private-key permissions.

- [ ] **Step 2: Run the new tests and confirm they fail because the interfaces do not exist.**

Run: `cmake --build "$HOST/build-remote-tests" --target test_localnetworkdiscovery test_localtlsidentity && ctest --test-dir "$HOST/build-remote-tests" -R 'test_local(networkdiscovery|tlsidentity)' --output-on-failure`

Expected: compile failure naming the missing headers/targets.

- [ ] **Step 3: Implement discovery without invoking the Tailscale CLI.** Use `QNetworkInterface::allInterfaces()`, require `IsUp | IsRunning`, and return only `isEligibleCompanionAddress(entry.ip())`. Use explicit IPv4 masks: `10/8`, `172.16/12`, `192.168/16`, and `100.64/10`; set `isTailscale` only for the latter.

- [ ] **Step 4: Implement the local identity with OpenSSL and atomic writes.** Link the app and both new test targets to `crypto`. Generate a P-256 `EVP_PKEY`, create a self-signed X509 certificate signed with SHA-256, serialize using `PEM_write_bio_PrivateKey` and `PEM_write_bio_X509`, write via `QSaveFile`, then set the key to `ReadOwner | WriteOwner`. Compute `spkiSha256` from `i2d_PUBKEY` and encode it with `QByteArray::Base64UrlEncoding | OmitTrailingEquals`. Reuse only a pair that parses into a non-null `QSslKey` and `QSslCertificate`; otherwise return `Local TLS identity is unavailable` without deleting user files.

- [ ] **Step 5: Run the targeted tests.**

Run: `cmake --build "$HOST/build-remote-tests" --target test_localnetworkdiscovery test_localtlsidentity && ctest --test-dir "$HOST/build-remote-tests" -R 'test_local(networkdiscovery|tlsidentity)' --output-on-failure`

Expected: both tests pass.

- [ ] **Step 6: Commit only this vertical slice.**

```bash
git -C "$HOST" add src/remote/localnetworkdiscovery.* src/remote/localtlsidentity.* tests/test_localnetworkdiscovery.cpp tests/test_localtlsidentity.cpp tests/CMakeLists.txt src/CMakeLists.txt
git -C "$HOST" commit -m "feat: add local companion TLS identity"
```

### Task 2: Make the host listener and settings transport-neutral

**Files:**
- Modify: `$HOST/src/remote/remotecompanionserver.{h,cpp}`, `$HOST/src/remote/remotecompanionsettings.cpp`
- Modify: `$HOST/tests/test_remote_companion_server.cpp`, `$HOST/tests/CMakeLists.txt`

**Interfaces:** `RemoteListenerConfig` becomes `{ QHostAddress listenerAddress; quint16 port; bool enabled; RemoteTlsConfig tls; }`; `RemoteTlsConfig` contains `certificatePath`, `privateKeyPath`, `spkiSha256` and no hostname.

- [ ] **Step 1: Replace the production-bind test with a failing local-address test.** Assert that `192.168.1.5` and `100.64.0.2` pass address validation when supplied valid TLS files, while loopback, Any, public and link-local addresses fail. Assert missing TLS produces `TLS certificate and private key are required`, not a Tailnet-hostname error.

- [ ] **Step 2: Run `test_remote_companion_server` and confirm the old Tailnet restriction fails the new assertion.**

Run: `cmake --build "$HOST/build-remote-tests" --target test_remote_companion_server && ctest --test-dir "$HOST/build-remote-tests" -R '^test_remote_companion_server$' --output-on-failure`

- [ ] **Step 3: Implement the minimal listener change.** Rename `tailscaleAddress` to `listenerAddress`; replace `canListenOn` with `isEligibleCompanionAddress`; remove `serverName` validation from `configureTls`; create the secure server with the fixed label `UOS AI Local Companion`. Retain the existing test-only insecure loopback escape hatch and never enable it in `Application`.

- [ ] **Step 4: Migrate settings safely.** Persist `remoteCompanion/listenerAddress` and `remoteCompanion/tlsSpkiSha256`. On reading a legacy `tailscaleAddress` configuration, return `enabled=false` until the user generates a v2 QR; do not reuse its certificate or silently expose the old listener.

- [ ] **Step 5: Run the server suite.**

Run: `cmake --build "$HOST/build-remote-tests" --target test_remote_companion_server && ctest --test-dir "$HOST/build-remote-tests" -R '^test_remote_companion_server$' --output-on-failure`

Expected: all existing protocol/pairing assertions plus the local bind assertions pass.

- [ ] **Step 6: Commit.**

```bash
git -C "$HOST" add src/remote/remotecompanionserver.* src/remote/remotecompanionsettings.cpp tests/test_remote_companion_server.cpp tests/CMakeLists.txt
git -C "$HOST" commit -m "feat: allow pinned local companion listeners"
```

### Task 3: Emit validated v2 local invitations from the desktop host

**Files:**
- Modify: `$HOST/src/app/application.{h,cpp}`, `$HOST/src/gui/web/remotecompanionchannel.{h,cpp}`
- Modify: `$HOST/tests/test_remote_pairing.cpp`; create `$HOST/tests/test_local_pairing_application.cpp`

**Interfaces:** `prepare` receives `{"workspaceIds":[...],"address":"192.168.1.7","port":45980}` and delegates to `Application::prepareLocalRemoteCompanion(QStringList, QString, quint16)`. Status returns `eligibleAddresses`, `listenerAddress`, `tlsSpkiSha256`, `listening`, `tlsReady`, and no certificate paths. `createRemoteCompanionInvitation()` emits `uos-ai://pair?v=2&transport=local&host=...&port=...&tlsSpkiSha256=...` plus the existing secret, expiry and display name.

- [ ] **Step 1: Write failing request-validation tests.** Cover malformed JSON, zero/overflow ports, empty workspace selection, address not present in `discoverLocalNetworkAddresses`, and a successful request that receives an identity pin. Verify the emitted URI has exactly the eight v2 keys and never contains `tlsCertificatePath`, `tlsPrivateKeyPath`, `.ts.net`, or a device token.

- [ ] **Step 2: Run the new tests and observe failure against the array-only `prepare` channel contract.**

Run: `cmake --build "$HOST/build-remote-tests" --target test_local_pairing_application && ctest --test-dir "$HOST/build-remote-tests" -R '^test_local_pairing_application$' --output-on-failure`

- [ ] **Step 3: Implement one explicit preparation path.** Validate workspace IDs before creating the TLS identity, validate the selected address against the current discovery list, call `loadOrCreateLocalTlsIdentity(AppDataLocation)`, start/reconfigure the listener, persist the generic config only after `start()` succeeds, and return redacted errors. Remove `RemoteCompanionBootstrap` construction and delete `tailscale cert` preparation calls.

- [ ] **Step 4: Build the URI with `QUrlQuery`.** Add fixed `v=2` and `transport=local`, base64url pin, existing `PairingInvitation` values, and reject invitation creation unless `m_remoteServer->isListening()`, local TLS is ready, and the workspace allowlist is non-empty.

- [ ] **Step 5: Run all host remote tests.**

Run: `cmake --build "$HOST/build-remote-tests" && ctest --test-dir "$HOST/build-remote-tests" --output-on-failure`

Expected: all remote tests pass and no target named `test_tailscale_bootstrap` remains.

- [ ] **Step 6: Commit.**

```bash
git -C "$HOST" add src/app/application.* src/gui/web/remotecompanionchannel.* src/remote tests/CMakeLists.txt tests/test_remote_pairing.cpp tests/test_local_pairing_application.cpp
git -C "$HOST" commit -m "feat: create local network pairing invitations"
```

### Task 4: Replace the desktop configuration UI with the Orca-inspired local flow

**Files:**
- Modify: `$HOST/web/src/views/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.tsx`, `companionPresentation.ts`
- Modify: `$HOST/web/src/assets/styles/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.css`

- [ ] **Step 1: Add failing pure-presentation assertions.** Create `RemoteCompanionPresentation.test.ts` in the same feature folder and assert default transport is `local`, Relay cannot prepare, no selected workspace disables generate, and a changed address invalidates a visible QR.

- [ ] **Step 2: Run the test/type check and confirm the missing local transport state fails.**

Run: `cd "$HOST/web" && npm run type-check`

- [ ] **Step 3: Implement the page state.** Replace the secure-host/advanced TLS inputs with: radio-like `Local network` (selected) and disabled `Orca Relay (experimental)` cards; an eligible-address `<select>`; shared workspace rows; a primary `Generate pairing QR`; a QR pane with `Regenerate code` and `Copy pairing code`. Post only `{ workspaceIds, address, port }` to `prepare`. Clear `invitationUri` whenever the address, port, or selected workspace IDs change.

- [ ] **Step 4: Update CSS using existing page variables.** Add `.remote-companion-page__transport-option`, `--selected`, `--disabled`, and QR action rules; preserve light/dark variables and keyboard focus outlines. Do not render certificate paths, Tailnet hostname, or Tailscale admin instructions.

- [ ] **Step 5: Verify the frontend.**

Run: `cd "$HOST/web" && npm run type-check && npm run build`

Expected: both commands exit 0 and `web/dist` contains the local pairing page bundle.

- [ ] **Step 6: Commit source only; do not add generated `web/dist` unless this host repo explicitly tracks regenerated bundles.**

```bash
git -C "$HOST" add web/src/views/window/mainwindow/page/settings/remotecompanion web/src/assets/styles/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.css
git -C "$HOST" commit -m "feat: add local network pairing UI"
```

### Task 5: Parse v2 invitations and persist Android transport state

**Files:**
- Modify: `android/app/src/main/java/org/deepin/uosai/companion/core/pairing/PairingUri.kt`, `core/security/DeviceGrantStore.kt`, `feature/pairing/PairingInstructions.kt`, `app/CompanionViewModel.kt`
- Modify: `android/app/src/test/java/org/deepin/uosai/companion/core/pairing/PairingUriTest.kt`, `feature/pairing/PairingInstructionsTest.kt`

**Interfaces:**

```kotlin
enum class PairingTransport { TAILNET, LOCAL }
data class PairingUri(..., val transport: PairingTransport, val tlsSpkiSha256: String? = null)
@Serializable data class DeviceGrant(..., val transport: PairingTransport = PairingTransport.TAILNET,
    val tlsSpkiSha256: String? = null)
```

- [ ] **Step 1: Add failing v2 parser tests.** Accept `v=2&transport=local&host=192.168.1.7` with a 32-byte unpadded base64url pin; reject public, loopback, link-local and malformed addresses; reject wrong-size/non-base64 pin, duplicated keys, unexpected keys and expired URIs. Retain the existing passing v1 `.ts.net` test.

- [ ] **Step 2: Run the unit test and confirm v2 is rejected by the current v1-only parser.**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests org.deepin.uosai.companion.core.pairing.PairingUriTest`

- [ ] **Step 3: Implement strict version-specific parameter sets.** Parse v1 exactly as today. For v2 require `transport=local`, apply the same four IPv4 range checks as the host, decode and length-check `tlsSpkiSha256`, and return `wss://host:port/`. Add transport/pin to `DeviceGrant`; existing encrypted grants deserialize as `TAILNET` with no pin.

- [ ] **Step 4: Replace Tailscale-only pairing copy.** Set `PairingInstructions.summary` to `Scan the one-time QR code from UOS AI desktop. Keep this device on the selected Wi-Fi or reachable Tailscale network.` Update the ViewModel pairing failure to `Pairing failed. Confirm this device can reach the selected network and scan a fresh QR code.`

- [ ] **Step 5: Run Android unit tests.**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest`

Expected: all unit tests pass.

- [ ] **Step 6: Commit.**

```bash
git add android/app/src/main/java/org/deepin/uosai/companion/{core/pairing/PairingUri.kt,core/security/DeviceGrantStore.kt,feature/pairing/PairingInstructions.kt,app/CompanionViewModel.kt} android/app/src/test/java/org/deepin/uosai/companion/{core/pairing/PairingUriTest.kt,feature/pairing/PairingInstructionsTest.kt}
git commit -m "feat: accept local network pairing invitations"
```

### Task 6: Pin Android local TLS and run Android-first end-to-end verification

**Files:**
- Create: `android/app/src/main/java/org/deepin/uosai/companion/core/network/PinnedTlsClientFactory.kt`
- Modify: `android/app/src/main/java/org/deepin/uosai/companion/core/network/CompanionWebSocket.kt`, `android/app/build.gradle.kts`, `android/gradle/libs.versions.toml`
- Create: `android/app/src/test/java/org/deepin/uosai/companion/core/network/PinnedTlsClientFactoryTest.kt`

- [ ] **Step 1: Add failing TLS tests with `MockWebServer` and `HeldCertificate`.** Add test dependencies `com.squareup.okhttp3:mockwebserver:4.12.0` and `com.squareup.okhttp3:okhttp-tls:4.12.0`. Start a HTTPS MockWebServer with a generated self-signed certificate; assert the matching SPKI pin completes a WSS/HTTPS handshake and a different generated certificate fails. Assert the factory rejects `TAILNET` and missing pins.

- [ ] **Step 2: Run the test and confirm the factory is missing.**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests org.deepin.uosai.companion.core.network.PinnedTlsClientFactoryTest`

- [ ] **Step 3: Implement connection-scoped pinning.** `PinnedTlsClientFactory.create(host, pin)` must decode the pin, use a dedicated `X509TrustManager` that calls `certificate.checkValidity()` and accepts only a leaf certificate whose `publicKey.encoded` SHA-256 matches, initialize a new `SSLContext`, and return an OkHttp client with a HostnameVerifier limited to the supplied host. Never set a global HTTPS default and never use a permissive verifier.

- [ ] **Step 4: Select the client by grant transport.** In `CompanionWebSocket`, retain the default client for `TAILNET`; for `LOCAL`, build the pinned client from `PairingUri` during pairing and from `DeviceGrant` during reconnect. Keep the existing timeout, protocol frames and grant-clearing behavior. If the TLS open fails, do not call `grantStore.save`.

- [ ] **Step 5: Run the full Android verification set.**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest`

Expected: unit tests, debug APK, and the existing Android 16 instrumentation tests all pass.

- [ ] **Step 6: Perform the Android acceptance test after Tasks 1–4 are installed on the host.** On the Lenovo TB322FC, join the selected Wi-Fi, scan a freshly generated desktop QR, verify workspace list access, open one conversation, start and cancel one Agent turn, revoke the device on desktop, and confirm reconnect/authentication fails. Repeat only the scan/reconnect portion over a pre-existing reachable Tailscale address; do not change the Tailscale admin console.

- [ ] **Step 7: Commit and report only verified results.**

```bash
git add android/app/src/main/java/org/deepin/uosai/companion/core/network android/app/src/test/java/org/deepin/uosai/companion/core/network android/app/build.gradle.kts android/gradle/libs.versions.toml
git commit -m "feat: pin local companion TLS on Android"
```

## Plan self-review

- Spec coverage: Tasks 1–3 remove the Tailscale certificate dependency and construct the v2 QR; Task 4 supplies the Orca-inspired desktop interaction; Tasks 5–6 make Android parse, pin, pair, reconnect and verify it. Relay and iOS are explicitly excluded.
- Security coverage: every host address is filtered, listener scope is singular, TLS is mandatory, the QR contains only a short-lived secret and pin, and Android validates the pin in an isolated client.
- Repository safety: the host Git metadata failure is a precondition rather than an implicit repair; all generated artifacts and pre-existing untracked dumps remain excluded.
