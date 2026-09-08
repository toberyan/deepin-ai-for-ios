# UOS AI iOS Companion

This companion continues existing UOS AI conversations over a user's Tailscale tailnet. The UOS AI host owns the WebSocket listener, pairing grants, workspace sharing policy, event replay, Agent execution, cancellation, and one-time approvals. The iPhone stores its device grant only in Keychain.

The host implementation is in the dedicated worktree at `/home/toberyan/orca/workspaces/uos-ai-0908/uos-ai-ios-tailscale-host` on branch `feature/ios-tailscale-companion`. It deliberately does not modify the user's original UOS AI checkout.

## Setup

1. Sign in to Tailscale on the UOS computer and iPhone.
2. In UOS AI, open the title-bar menu, select **iOS Companion**, choose the computer's `100.x.y.z` Tailscale address, select only the workspaces to share, then enable the listener.
3. Create a one-time invitation and paste its `uos-ai://pair?...` value in the iOS app. Invitations expire in five minutes and only return a device token once.
4. Open an allowed workspace on iPhone, select an existing conversation, and continue it. The app sends the original stable `conversationId`; it does not create a replacement conversation.

Agent approvals always require a tap on iPhone. The app exposes only **Reject** and **Allow once**; it never sends an `always_approve` grant.

## Build

On a macOS development host with Xcode and XcodeGen:

```sh
cd ios
xcodegen generate
xcodebuild -project UOSAICompanion.xcodeproj -scheme UOSAICompanion -destination 'platform=iOS Simulator,name=iPhone 16 Pro' test
```

The current Linux workspace has neither Xcode nor XcodeGen, so the generated project and simulator tests have not been run here. It also has no `web/node_modules`, so the UOS AI WebView type check/build needs to run in a provisioned frontend environment.

See [operations guide](docs/operations/tailscale-companion.md) and [protocol v1](docs/protocol/remote-companion-v1.md).
