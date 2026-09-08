# Tailscale companion operation

## Security model

- The UOS AI listener is disabled by default.
- It binds only to an administrator-selected, locally present Tailscale IPv4 `100.64.0.0/10` or Tailscale IPv6 `fd7a:115c:a1e0::/48` address. It rejects wildcard, LAN, and public addresses.
- Production connections are TLS-only: the listener needs an ASCII `*.ts.net` tailnet hostname and the matching readable PEM certificate and private key. Mobile clients build `wss://<hostname>:<port>` and retain normal certificate and hostname validation; they do not fall back to `ws://`.
- Pairing invitations are memory-resident, single-use, and valid for five minutes. A device token is 32 random bytes, delivered once, and persisted on the host only as a versioned SHA-256 digest.
- Each device grant has an explicit workspace allowlist. Private or temporary conversations are denied even if a client guesses an ID.
- Revocation is checked on the next command and closes the connection. Command responses are idempotent for 24 hours per `{deviceId, requestId}`.
- Approvals are one-time. The host accepts only an ID that the active session emitted; it synthesizes UOS's required response with `always_approve: false`.

## Enablement

1. Install and sign in to Tailscale on the UOS host and mobile device with the same tailnet.
2. In UOS AI select **Mobile Companion** from the title-bar menu. Select one or more existing workspaces. Do not share workspaces that contain conversations you do not intend to expose to the selected device.
3. Press **Prepare secure pairing**. Only this user-initiated action reads local Tailscale state and asks Tailscale to create a matching TLS certificate and private key under UOS AI application data. No command output or key data is shown in the UI.
4. When Secure host shows **Ready**, press **Show pairing QR**. On Android choose **Scan UOS AI QR**. Use **Paste invitation instead** only if camera scanning is unavailable.
5. The QR is an invitation valid for five minutes. The desktop page intentionally returns its secret once. Do not put it in chat logs, issue trackers, or a shared clipboard. QR creation requires both a selected workspace and a ready WSS listener.
6. Verify the connected device label, then revoke it immediately if the phone is lost or replaced.

The exact QR payload is the copyable `uos-ai://pair?...` URI. Android also registers that scheme as a system deep link, so scanning it with the system camera can hand it directly to the companion. A malformed, public-host, or expired invitation is rejected before any connection attempt.

Use **Advanced connection settings** only as the manual recovery path for a custom Tailnet address, port, hostname, or existing PEM files. Save any changed workspace selection before showing a QR. Clearing the final workspace exposes **Stop sharing**, which disables the listener and prevents new invitations. To change an active listener's connection settings, disable it first; this keeps existing paired-device connections from being dropped by a failed reconfiguration. The key remains stored under UOS AI application data. Production has no `ws://` fallback.

## Recovery and backup

- UOS AI conversation history was migrated from cache to its application-data directory. Back up that durable application-data directory together with `remote-companion.sqlite` if history/replay continuity matters.
- A host restart ends in-memory Agent sessions. Do not treat a reconnect as proof that a running turn continued; reopen the conversation and inspect its terminal state.
- Revoke stale devices rather than attempting to reuse their token. Pair again after host migration or a restored host database.

## Tailscale ACL starting point

Replace the tag and group with values from the actual tailnet:

```json
{
  "acls": [
    {
      "action": "accept",
      "src": ["group:uos-ai-owners"],
      "dst": ["tag:uos-ai-host:45980"]
    }
  ]
}
```

## Required real-device validation

Before enabling this broadly, verify from each supported mobile platform on the same tailnet that an unpaired client cannot read data, an allowed device can continue a durable conversation, reconnect cursor replay has no duplicate bubbles, cancel works, approval requires an explicit tap, and revocation blocks the next command.
