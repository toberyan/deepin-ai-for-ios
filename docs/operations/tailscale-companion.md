# Tailscale companion operation

## Security model

- The UOS AI listener is disabled by default.
- It binds only to an administrator-selected, locally present Tailscale IPv4 `100.64.0.0/10` or Tailscale IPv6 `fd7a:115c:a1e0::/48` address. It rejects wildcard, LAN, and public addresses.
- Pairing invitations are memory-resident, single-use, and valid for five minutes. A device token is 32 random bytes, delivered once, and persisted on the host only as a versioned SHA-256 digest.
- Each device grant has an explicit workspace allowlist. Private or temporary conversations are denied even if a client guesses an ID.
- Revocation is checked on the next command and closes the connection. Command responses are idempotent for 24 hours per `{deviceId, requestId}`.
- Approvals are one-time. The host accepts only an ID that the active session emitted; it synthesizes UOS's required response with `always_approve: false`.

## Enablement

1. Install and sign in to Tailscale on both devices.
2. In UOS AI select **iOS Companion** from the title-bar menu.
3. Select a displayed Tailscale address and one or more existing workspaces. Do not share workspaces that contain conversations you do not intend to expose to the selected device.
4. Enable the listener. The default port is `45980`.
5. Create an invitation and paste it into the iOS app. The desktop page intentionally returns the secret once. Do not put it in chat logs, issue trackers, or a shared clipboard.
6. Verify the connected device label, then revoke it immediately if the phone is lost or replaced.

The current desktop page provides a copyable URI. It does not yet render a QR image; the iOS pairing screen supports a pasted URI. Add camera/QR operation only after a macOS/iPhone validation pass.

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

Before enabling this broadly, verify from an iPhone on the same tailnet that an unpaired client cannot read data, an allowed device can continue a durable conversation, reconnect cursor replay has no duplicate bubbles, cancel works, approval requires an explicit tap, and revocation blocks the next command.
