# Remote companion protocol v1

Frames are UTF-8 JSON over the UOS AI host's Tailscale-bound, TLS-protected WebSocket (`wss://<tailnet-hostname>:<port>`). Protocol major version is `1`; unknown fields are ignored.

## Bootstrap

The first unauthenticated frame may be a one-time pairing exchange:

```json
{
  "kind": "pair",
  "protocol": { "major": 1, "minor": 0 },
  "payload": { "pairingSecret": "…", "deviceName": "iPhone" }
}
```

On success the host returns `pairing_granted` with a newly created `deviceId` and token. The client stores that token only in platform-protected credential storage and begins later connections with:

```json
{ "kind": "authenticate", "deviceId": "ios-…", "token": "…" }
```

## Commands

Authenticated commands have `kind: "command"`, a unique `requestId`, the protocol version, and an object `payload`.

- `list_workspaces`
- `get_conversation` with `conversationId`
- `subscribe` with `cursors: [{ "conversationId": "…", "sequence": 42 }]`
- `start_turn` with `workspaceId`, `conversationId`, `message`, `assistantId`, and `modelId`
- `cancel` with `conversationId`
- `approval` with `conversationId`, `approvalId`, and boolean `approved`

The server persists the acknowledgement or deterministic error for every valid command request ID. Retrying the same request returns the original response without starting a second Agent action.

## Events

Events include `conversationId`, monotonic `sequence`, an event name, and `payload`. The host replays events after the supplied cursor before live broadcast.

- `snapshot`
- `message_delta`
- `state_delta`
- `agent_approval_requested`
- `turn_finished`
- `turn_failed`
- `command_ack`

An approval event includes an `approvalId`, `actionType`, short `title`, and redacted `details`. The mobile client must present it as a blocking decision and send only a one-time boolean response. It must not create an always-approve control.

Stable errors include `unauthenticated`, `forbidden`, `not_found`, `conflict_active_turn`, `invalid_command`, `approval_expired`, `protocol_incompatible`, and `internal_error`.
