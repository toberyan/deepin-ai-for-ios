# UOS AI Mobile Pairing UX Design

## Goal

Make the UOS AI desktop companion page legible in both light and dark UOS themes, and make Android pairing a scan-first flow. A normal user must not type a Tailscale address, tailnet hostname, certificate path, private-key path, or `uos-ai://` URI on the tablet.

The design keeps the existing security boundary: the listener remains bound to one local Tailscale address, clients connect only through `wss://<ASCII *.ts.net hostname>`, pairing invitations are single-use and five minutes long, and every device receives only the host-selected workspace allowlist.

## Evidence and constraints

- The current `RemoteCompanionPage.tsx` is a 760-pixel column of raw `input`, `select`, and `textarea` elements styled inline. It does not use the application's themed settings-page classes or color tokens. This is the direct cause of the weak visual hierarchy and poor dark-theme contrast.
- The current desktop page already emits the exact one-time QR payload consumed by Android. The Android app already scans the QR and registers `uos-ai://pair` as a deep link. The unnecessary friction is exposing the raw URI and network configuration as the main pairing experience.
- The local Orca CLI separates transport configuration from mobile enrollment: `orca serve --mobile-pairing` produces a mobile-scoped QR/link and the receiving client consumes a one-time pairing code. UOS AI will use the same boundary: prepare the secure host once, then make mobile enrollment QR-first.
- The local Tailscale CLI supports `tailscale status --json` and `tailscale cert --cert-file <path> --key-file <path> <domain>`. UOS AI must invoke it without a shell and only after an explicit user action.
- Existing host worktree changes in `translations/uos-ai-assistant_zh_CN.ts`, `web/dist/index.html`, and `plugin-aibar/translations/` are user-owned and out of scope.

## UX and interaction model

### 1. Themed desktop page

Replace the inline page with the same page shell used by the MCP and Skills settings pages: a title header, bounded scrollable content, responsive cards, app font tokens, and explicit `html` / `html.dark` color variables. The page title becomes **Mobile Companion** consistently in the title bar and content.

The normal view has three ordered cards:

1. **Secure host** — one compact readiness state: `Not prepared`, `Ready`, or a concise actionable failure. It shows the selected tailnet hostname and port only when ready.
2. **Shared workspaces** — selectable workspace rows with a clear statement that selected workspaces are visible to newly paired devices. This stays visible because it is the authorization decision, not transport plumbing.
3. **Pair Android device** — a single prominent `Show pairing QR` action. When ready, the QR is large enough to scan from a tablet, includes its expiration time, and has `Copy link` as a secondary recovery action. The raw one-time URI is hidden by default and may only be revealed/copied deliberately.

An **Advanced connection settings** disclosure contains manual address, hostname, port, certificate path, and private-key path overrides. It is never the first thing a user sees. Existing saved values populate it; users who already manage their own certificates retain that path unchanged.

All visible labels, status messages, and button states must be localizable through the existing translation pathway rather than introduced as isolated English-only strings.

### 2. First-use secure preparation

The `Secure host` card offers `Prepare secure pairing` when the listener is not ready. Pressing it is the explicit consent for UOS AI to:

1. Run `tailscale status --json` through `QProcess` with a fixed executable and argument list, never a shell. Extract the local tailnet IP and `Self.DNSName`, trim a terminal dot, and reject a missing/non-ASCII/non-`*.ts.net` hostname.
2. Use the discovered values to prefill the current address and hostname. The user selects at least one shared workspace before the listener can be enabled.
3. Run `tailscale cert` only on this same explicit action, with certificate and key outputs under the UOS AI per-user application-data directory (in a private mobile-companion TLS subdirectory). The command receives the discovered hostname, `--cert-file`, and `--key-file` as separate arguments.
4. Validate that both output files are readable and pass those exact paths to the existing secure listener configuration. The listener starts only if the existing WSS and tailnet checks pass.

`Prepare secure pairing` is idempotent: reusing it refreshes a certificate when Tailscale permits it, but never relaxes the listener to cleartext or a LAN/public binding. If the Tailscale CLI is absent, signed out, lacks a MagicDNS hostname, or refuses certificate issuance, the page reports a short error and expands Advanced settings; it does not expose raw command output or secrets.

No certificate is generated merely by opening the page, and the page never uploads a key or pairs a device automatically.

### 3. Scan-first Android pairing

Make `Scan UOS AI QR` the primary Android pairing action. Pasting an invitation remains behind a secondary `Paste invitation` affordance for accessibility and recovery. The existing custom-scheme deep link remains supported, so an Android system QR scanner can open the companion directly.

The QR contains the existing v1 pairing invitation, including the host, port, one-time secret, expiry, and display name. Scanning immediately invokes the current pair exchange and stores only the issued device grant in Android Keystore-backed storage. It does not add a second protocol or a long-lived QR.

The explicit desktop authorization happens when the user chooses the shared workspaces and presses `Show pairing QR`; this preserves the current, tested pre-authorized one-time invitation contract. A post-scan desktop approval queue is deliberately out of scope: it would require a new pending-pair protocol state, hold an unauthenticated socket open, and provide no security benefit over the existing host-side QR authorization.

## Components and boundaries

| Component | Responsibility | Does not do |
| --- | --- | --- |
| `RemoteCompanionPage` and new page CSS | Render themed readiness, workspace authorization, QR enrollment, advanced disclosure, and non-secret errors. | Parse Tailscale JSON or construct certificate commands. |
| `RemoteCompanionChannel` | Expose `detect` / `prepare` request methods with JSON validation, alongside existing status/configure/invitation/device methods. | Start a listener from arbitrary executable input. |
| `RemoteCompanionBootstrap` (new host service) | Safely run fixed `tailscale` subcommands, parse discovery data, issue files into app data, and return structured redacted results. | Pair a device or bypass server TLS validation. |
| `Application` | Validates discovered values, persists accepted configuration, starts the existing secure server, and reports readiness. | Trust a discovery result without tailnet hostname and file checks. |
| Android pairing UI | Prioritizes camera scan and retains manual/deep-link recovery. | Store pairing secrets or accept non-tailnet / cleartext endpoints. |

## Errors and recovery

- **Tailscale unavailable or signed out:** show `Tailscale is not ready on this computer`; keep the primary action disabled until retry and expose manual configuration in Advanced settings.
- **No eligible local tailnet address / hostname:** explain that this UOS host must be connected to a tailnet with a `*.ts.net` name; do not select a LAN address.
- **Certificate command fails or files cannot be read:** report that secure certificate preparation failed, preserve no partial configuration, and allow manual certificate paths in Advanced settings.
- **No workspace selected:** block QR creation with an inline explanation; never create an invitation with a broad or implicit allowlist.
- **Invitation expiry, public hostname, malformed QR:** retain the existing Android validation and show a clear local failure before a network connection.

## Validation

1. Add host unit tests for Tailscale status parsing: valid tailnet IP/name, terminal-dot normalization, missing/self DNS name, public hostname, and no eligible IP. Use an injected command runner so no test calls the live Tailscale CLI.
2. Add host tests ensuring a prepare failure does not persist a new listener configuration or start an insecure listener; retain the existing production-WSS rejection tests.
3. Add page-level tests where the current frontend harness permits them; otherwise make the UI state calculation pure and verify it through TypeScript type-checking plus the existing frontend production build.
4. Add Android UI/unit coverage for scan-first default and manual-paste recovery; run all existing pairing/protocol/reconnect tests.
5. On the connected Android 16 Y700, verify the card hierarchy in light and dark mode, prepare/QR flow, system deep link, camera scan, workspace continuation, Agent start/cancel, one-time approval, and device revocation.

## Non-goals

- No public relay, cleartext WebSocket, wildcard/LAN binding, or certificate-trust bypass.
- No Tailscale account login, ACL administration, or background certificate issuance by UOS AI.
- No post-scan pending-pair protocol in this iteration.
- No modification of the user's unrelated host worktree changes.
