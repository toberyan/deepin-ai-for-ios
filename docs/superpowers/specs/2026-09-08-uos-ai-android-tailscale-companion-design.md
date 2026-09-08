# UOS AI Android Tailscale Companion Design

## Goal

Deliver a native Android companion for UOS AI that runs on Android 16 (API 36), is optimized for Lenovo Legion Y700 Gen 4 landscape use, and lets an authorized tailnet device resume shared workspaces, continue conversations, control Agent turns, and answer one-time Agent approvals. The release is complete only after it has been installed and exercised on the connected Y700 through ADB.

## Scope

The Android client is a new `android/` Gradle project beside the existing native iOS companion. It implements Remote Companion Protocol v1 without altering command or event semantics.

The UOS desktop companion settings page gains a QR rendering of its existing one-time `uos-ai://pair?...` invitation. The QR carries the exact URI already available for copying; it does not create a second pairing mechanism or persist an additional credential.

This work does not add public-network access, multi-user account discovery, automatic approval, or a second server protocol.

## Supported platform and layout

- Compile and target Android 16 / API 36. Set `minSdk` to 28.
- Support phones, but make the primary layout responsive for the Y700 Gen 4 in landscape.
- At expanded width, render a persistent left pane for shared workspaces and conversations, with a right pane for the active conversation.
- At compact width or portrait, show one pane at a time with explicit back navigation to the workspace and conversation list.
- Agent approval appears as a blocking bottom sheet. It contains only explicit Approve and Reject actions; it never offers an always-approve choice.

## Android project structure

```text
android/
  app/                         Android application, Compose UI and manifest
  core/protocol/                Protocol v1 JSON frames and validation
  core/network/                 authenticated WebSocket lifecycle and reconnect policy
  core/security/                Android Keystore-backed device grant store
  feature/pairing/              URI validation, camera scanner, manual paste flow
  feature/workspaces/           workspace and conversation navigation state
  feature/conversation/         transcript, composer, turn controls and approvals
  app/src/androidTest/          device-facing flow tests
```

Each layer only depends inward: UI features call an application state holder; that state holder uses the WebSocket client; the WebSocket client uses protocol types and the device grant store. No UI code parses raw JSON or reads encrypted credentials.

## Pairing and credential handling

The pairing screen accepts either a pasted `uos-ai://pair?...` link or a QR scan. CameraX supplies frames to a bundled ZXing decoder, which avoids a dependency on Google Play services.

`PairingUri` accepts the existing v1 schema exactly: `v=1`, `host`, `port`, `pairingSecret`, `expiresAtMs`, and `hostDisplayName`. It rejects malformed or duplicate fields, missing or expired secrets, public hosts, and hosts outside an approved tailnet address range or `*.ts.net` hostname. The WebSocket endpoint is constructed as `wss://<host>:<port>`. The app posts the existing `pair` frame, receives `pairing_granted`, and saves only the returned `deviceId` and token in Android Keystore-protected encrypted storage. The one-time secret is discarded immediately.

The client sends `authenticate` on every subsequent socket connection. Authentication failures clear the grant and return the user to pairing. A device name is derived locally from the Android model and has no account identity attached to it.

## Transport security

The companion connection uses `wss://` to a Tailscale-bound UOS AI listener. The Android app does not enable a global cleartext exception. The host verifies that its bound peer is tailnet-local as it does today; the client verifies that a pairing endpoint is a tailnet IP or tailnet hostname before connecting.

The UOS host exposes the secure listener only after it has a locally configured certificate and private key for its tailnet hostname. If that configuration is missing, the desktop settings page reports that no secure invitation can be created. It must not silently publish `ws://`, bind a public interface, or fall back to a public relay. The existing iOS URL builder is migrated from `ws://` to the same `wss://` endpoint so a secure-host upgrade does not strand iOS clients.

## Conversation and Agent flow

After authentication, the client requests its shared workspaces and subscribes using stored per-conversation sequence cursors. It applies snapshots before later deltas, retains the latest cursor after each accepted event, and requests replay after reconnect.

Selecting a conversation displays the current transcript and Agent state. Sending a message emits `start_turn` with the selected workspace and conversation IDs. Cancel emits `cancel` for that conversation. Request IDs are random and stable for a retry so the host command ledger prevents duplicated turns.

On `agent_approval_requested`, the active conversation presents the blocking approval sheet. Its reply emits one `approval` command with the event's approval ID and the user-selected boolean. The client disables both choices as soon as it submits a reply and shows the host's terminal result or error.

## Error handling and reconnection

The connection state is visible in the app: disconnected, connecting, pairing, authenticated, reconnecting, or failed. Reconnects use bounded exponential backoff up to 30 seconds and stop after an authentication or protocol-incompatibility error. Network and server errors use stable protocol error codes and do not expose secrets in the UI or logs.

`forbidden`, `not_found`, and `conflict_active_turn` stay attached to the relevant workspace or conversation. `approval_expired` dismisses the pending sheet and marks it expired. The app never assumes a command succeeded until it receives the corresponding acknowledgement or terminal event.

## Desktop QR presentation

The existing Remote Companion page retains its address selector, workspace sharing list, invitation text, copy button, device list, and revoke action. When an invitation is created, it renders the exact invitation URI as a QR code and labels its expiry. A tablet may scan it, while users can still transfer the URI by clipboard.

The rendering dependency is local to the desktop web bundle and does not alter the C++ protocol, pairing secret lifetime, or device grant database.

## Testing and acceptance

- JVM unit tests cover protocol frame decoding, pairing URI policy, redacted diagnostics, command request IDs, endpoint validation, and Keystore grant persistence through an injectable store interface.
- Instrumented tests cover pairing from a supplied URI, responsive conversation navigation, a rendered approval sheet, and the no-grant return-to-pairing path.
- UOS desktop tests cover that a generated invitation is exposed as the exact QR payload and that secure-listener prerequisites prevent unsafe invitation creation.
- On the connected Y700, install the debug APK with ADB, launch it, pair over the active tailnet, resume a shared workspace, continue a conversation, run and cancel an Agent turn, and approve or reject one real pending Agent action.

## Non-goals

- No public Internet listener, public relay, or cleartext transport fallback.
- No automatic or persistent approval policy from the tablet.
- No file browser, workspace editing, push notifications, or background Agent scheduling in this release.
- No change to the Remote Companion Protocol v1 command and event names.
