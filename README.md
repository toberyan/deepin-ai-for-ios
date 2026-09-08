# UOS AI Mobile Companion

Native iOS and Android companions continue existing UOS AI conversations over a user's Tailscale tailnet. The UOS AI host owns the secure WebSocket listener, pairing grants, workspace sharing policy, event replay, Agent execution, cancellation, and one-time approvals. iOS stores a device grant only in Keychain; Android stores it with an Android-Keystore-backed encrypted store.

The host implementation is in the dedicated worktree at `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host` on branch `feature/ios-tailscale-companion`. It deliberately does not modify the user's original UOS AI checkout.

## Setup

1. Sign in to Tailscale on the UOS computer and mobile device, using the same tailnet.
2. In UOS AI, open the title-bar **iOS Companion** item (the page is headed **Mobile Companion**). Choose the computer's local Tailscale address, its ASCII `*.ts.net` MagicDNS hostname, and the matching TLS certificate and private-key files. Select only the workspaces to share, then enable the listener on port `45980`.
3. Create a one-time invitation and scan its QR code or open/paste its `uos-ai://pair?...` value in either app. Invitations expire in five minutes and only return a device token once.
4. Open an allowed workspace, select an existing conversation, and continue it. The app sends the original stable `conversationId`; it does not create a replacement conversation.

The production transport is `wss://<tailnet-hostname>:45980`; neither client accepts a public address or silently falls back to cleartext `ws://`. Agent approvals always require a tap. The apps expose only **Reject** and **Allow once**; they never send an `always_approve` grant.

## Build

On a macOS development host with Xcode and XcodeGen:

```sh
cd ios
xcodegen generate
xcodebuild -project UOSAICompanion.xcodeproj -scheme UOSAICompanion -destination 'platform=iOS Simulator,name=iPhone 16 Pro' test
```

For Android 16 (API 36), use JDK 17 and an Android SDK containing platform and build-tools 36:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

The Android debug APK has been built, installed, and launched on the connected Lenovo Legion Y700 Gen 4 running Android 16. Its Keystore instrumentation test also passed on that device. iOS needs the Xcode installation on the macOS host to complete its native test run.

See [operations guide](docs/operations/tailscale-companion.md) and [protocol v1](docs/protocol/remote-companion-v1.md).
