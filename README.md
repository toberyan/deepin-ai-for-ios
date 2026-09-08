# UOS AI Mobile Companion

Native iOS and Android companions continue existing UOS AI conversations over a user's Tailscale tailnet. The UOS AI host owns the secure WebSocket listener, pairing grants, workspace sharing policy, event replay, Agent execution, cancellation, and one-time approvals. iOS stores a device grant only in Keychain; Android stores it with an Android-Keystore-backed encrypted store.

The host implementation is in the dedicated worktree at `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host` on branch `feature/ios-tailscale-companion`. It deliberately does not modify the user's original UOS AI checkout.

## Setup

1. Sign in to Tailscale on the UOS computer and mobile device, using the same tailnet.
2. In UOS AI, open **Mobile Companion** from the title-bar menu and select at least one workspace. Press **Prepare secure pairing**. This explicit action reads local Tailscale state and asks Tailscale to write the host TLS files under UOS AI application data.
3. When Secure host shows **Ready**, press **Show pairing QR**. On Android, choose **Scan UOS AI QR**. Use **Paste invitation instead** only when camera scanning is unavailable. Invitations expire in five minutes and only return a device token once.
4. Open an allowed workspace, select an existing conversation, and continue it. The app sends the original stable `conversationId`; it does not create a replacement conversation.

The production transport is `wss://<tailnet-hostname>:45980`; neither client accepts a public address or silently falls back to cleartext `ws://`. Agent approvals always require a tap. The apps expose only **Reject** and **Allow once**; they never send an `always_approve` grant.

**Advanced connection settings** are a manual recovery path for a custom address, port, or existing certificate. QR creation remains unavailable until a workspace is selected, its access is saved, and the secure WSS listener is ready. Clearing the final selected workspace presents **Stop sharing**, which closes the listener and prevents new invitations. To change an active listener's address, port, hostname, or certificate, first disable it so existing paired devices are not disconnected unexpectedly.

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
