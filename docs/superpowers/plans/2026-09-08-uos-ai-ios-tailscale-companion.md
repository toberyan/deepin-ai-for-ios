# UOS AI iOS Tailscale Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an iOS companion that reaches a UOS AI desktop instance only across the user's Tailscale tailnet, lists permitted workspaces and conversations, continues an existing conversation, and safely drives the same Agent turn, cancellation, and approval capabilities as the desktop UI.

**Architecture:** Keep the host-side gateway in the UOS AI Qt process.  A versioned, authenticated WebSocket protocol calls a shared execution service rather than the current WebChannel or D-Bus bridges.  A backend projection service persists render/event state as it arrives from `SessionManager`; both the desktop WebView and iOS client consume that canonical stream.  The iOS SwiftUI app stores a pairing grant in Keychain and reconnects with event cursors for idempotent recovery.

**Tech Stack:** UOS AI: C++17, Qt Core/Network/WebSockets/Sql/Test, SQLite, Qt WebChannel bridge compatibility.  Desktop UI: existing Vue 3 + TypeScript application in `web/`.  iOS: Swift 5.10, SwiftUI, URLSessionWebSocketTask, Keychain Services, XCTest/XCUITest, XcodeGen.  Network: Tailscale tailnet + application device grants; no public listener, Funnel, or D-Bus proxy.

## Global constraints and repository boundaries

- The UOS AI host repository is `/home/toberyan/workspace/uos-ai-0908/uos-ai`.  It has user-owned changes in translation and `web/dist` files.  Preserve those changes; before each host edit, inspect `git status --short` and edit only the files named below or newly added files.
- The iOS companion repository is the current workspace, `/home/toberyan/orca/workspaces/deepin-ai-for-ios/barramundi`.  Put the XcodeGen project under `ios/`; do not put an iOS runtime inside the UOS AI repository.
- Treat `workspace.value` as the workspace identity, `conversationId` as durable history identity, `sessionId` as one execution lifetime, `requestId` as a client idempotency key, and `eventSequence` as a monotonically increasing cursor within a conversation.
- The host must bind the remote socket only to an administrator-selected Tailscale address.  `QHostAddress::Any`, LAN interfaces, a public reverse proxy, Tailscale Funnel, and the existing debug `WebSocketForwardServer` are out of scope and must not be used.
- Do not route the mobile client through `CliRunner`: its `always approve` behavior is incompatible with mobile approval safety.  Do not route it through `ChatDbusInterface`: it does not expose a durable conversational contract.
- Remote clients may read only explicitly shareable workspaces and non-private conversations.  They may write only `start_turn`, `cancel`, and `approval` commands.  Approval always defaults to deny until the user chooses a response.

## Protocol contract

All WebSocket frames are UTF-8 JSON objects.  Incoming command frames are named `command`; outgoing frames are named `event`.  Unknown fields are ignored so a newer client can safely talk to an older host only when the major protocol versions match.

```json
{
  "kind": "command",
  "protocol": { "major": 1, "minor": 0 },
  "requestId": "01J8K35K1Y7G1Q4D2M9P8Q6R5S",
  "command": "start_turn",
  "payload": {
    "workspaceId": "/home/uos/workspaces/default",
    "conversationId": "b7c8cf95-9898-4f12-8f9c-70c4d0c88df2",
    "message": "继续分析这个项目",
    "assistantId": "deepseek",
    "modelId": "deepseek-chat"
  }
}
```

```json
{
  "kind": "event",
  "protocol": { "major": 1, "minor": 0 },
  "conversationId": "b7c8cf95-9898-4f12-8f9c-70c4d0c88df2",
  "sequence": 42,
  "event": "agent_approval_requested",
  "payload": {
    "approvalId": "req_7e25892a120a4e88",
    "actionType": "command_execution",
    "title": "运行命令",
    "details": { "command": "git status --short" }
  }
}
```

The initial authenticated command is `subscribe`, whose payload contains `conversationIds` and the last received cursor for each conversation.  The server replays events with a greater sequence before broadcasting live events.  `start_turn`, `cancel`, and `approval` receive a durable acknowledgement containing the original `requestId`, command status, and the affected `conversationId`; repeating the same `requestId` returns that acknowledgement without another Agent action.

Pairing is a bootstrap frame, not an authenticated command: `{"kind":"pair","protocol":{"major":1,"minor":0},"payload":{"pairingSecret":"pAb4Fg8Hj2Lm6Nq9Rs1Tu3Vw5Xy7Za0B","deviceName":"iPhone"}}`.  It is accepted only on the Tailscale-bound listener while the invitation is unexpired and unconsumed, and returns one `pairing_granted` frame with a newly created `deviceId` and device token.  Every later connection starts with `{"kind":"authenticate","deviceId":"ios-8a564438-4149-42d8-9354-2761bbf01e0e","token":"CY25bmF5uwruW4gZNe3AEasf1p4sO6Z7X9dH2kL8qRc"}` before the client sends a command.

## File map

| Repository | Create | Modify |
| --- | --- | --- |
| UOS AI | `src/remote/remoteprotocol.*`, `remotecompanionstore.*`, `conversationprojectionservice.*`, `conversationexecutionservice.*`, `remotecompanionserver.*`, `tests/test_remote_*.cpp`, `tests/CMakeLists.txt` | root `CMakeLists.txt`, `src/conversation/conversationmanager.*`, `src/session/sessionmanager.*`, `src/gui/web/sessionchannel.*`, relevant WebView event consumer and settings registration |
| iOS companion | `ios/project.yml`, `ios/UOSAICompanion/**`, `ios/UOSAICompanionTests/**`, `ios/UOSAICompanionUITests/**` | `README.md`, generated `ios/UOSAICompanion.xcodeproj` only after `xcodegen generate` |

---

### Task 1: Establish host-side test target and the protocol value types

**Files:**
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/CMakeLists.txt`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/tests/CMakeLists.txt`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/tests/test_remote_protocol.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remoteprotocol.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remoteprotocol.cpp`

- [ ] Inspect the host worktree first; record its existing dirty paths and do not stage them.

  Run: `git -C /home/toberyan/workspace/uos-ai-0908/uos-ai status --short`

- [ ] Add an opt-in CTest entry point after the existing application subdirectories.  This keeps production packaging unchanged when tests are disabled.

  ```cmake
  include(CTest)
  if(BUILD_TESTING)
      add_subdirectory(tests)
  endif()
  ```

- [ ] Make `tests/CMakeLists.txt` compile the remote-core sources into focused QTest binaries.  Add each new test with the same helper so tests do not link the full desktop executable:

  ```cmake
  find_package(Qt${QT_VERSION_MAJOR} REQUIRED COMPONENTS Test)

  function(add_remote_qtest target)
      add_executable(${target} ${ARGN})
      target_link_libraries(${target} PRIVATE Qt${QT_VERSION_MAJOR}::Core Qt${QT_VERSION_MAJOR}::Test)
      add_test(NAME ${target} COMMAND ${target})
  endfunction()

  add_remote_qtest(test_remote_protocol
      test_remote_protocol.cpp
      ${CMAKE_SOURCE_DIR}/src/remote/remoteprotocol.cpp)
  ```

- [ ] Write the failing protocol tests before the implementation.  Cover protocol-major rejection, a valid `start_turn`, a malformed `approval`, and cursor parsing with more than one conversation.  Use table-driven `QTest` rows; assert structured errors rather than string matching.

  ```cpp
  void RemoteProtocolTest::rejectsIncompatibleMajor()
  {
      const auto parsed = RemoteProtocol::parseCommand(
          QJsonDocument::fromJson(R"({"kind":"command","protocol":{"major":2,"minor":0},"requestId":"r1","command":"subscribe","payload":{}})").object());
      QVERIFY(!parsed.ok());
      QCOMPARE(parsed.error().code, QStringLiteral("protocol_incompatible"));
  }
  ```

- [ ] Define the protocol as pure Qt Core types, without dependencies on GUI, WebChannel, or sessions:

  ```cpp
  namespace RemoteCompanion {
  inline constexpr int kProtocolMajor = 1;
  inline constexpr int kProtocolMinor = 0;

  enum class CommandName { Subscribe, ListWorkspaces, GetConversation, StartTurn, Cancel, Approval };
  enum class EventName { Snapshot, MessageDelta, StateDelta, ApprovalRequested, TurnFinished, TurnFailed, CommandAck };

  struct ProtocolError { QString code; QString message; QJsonObject details; };
  struct Cursor { QString conversationId; quint64 sequence = 0; };
  struct Command { QString requestId; CommandName name; QJsonObject payload; };
  struct ParsedCommand { std::optional<Command> command; std::optional<ProtocolError> error; bool ok() const { return command.has_value(); } };

  class RemoteProtocol {
  public:
      static ParsedCommand parseCommand(const QJsonObject &frame);
      static QJsonObject commandAck(const QString &requestId, const QString &conversationId, const QJsonObject &result);
      static QJsonObject errorFrame(const QString &requestId, const ProtocolError &error);
      static QJsonObject eventFrame(const QString &conversationId, quint64 sequence, EventName name, const QJsonObject &payload);
      static QString commandWireName(CommandName name);
      static QString eventWireName(EventName name);
  };
  }
  ```

  Include `<optional>` and `<variant>` in the header and include `<QJsonObject>` directly rather than relying on transitive Qt includes.

- [ ] Implement strict validation: a command requires `kind == "command"`, matching major version, nonempty `requestId`, a known command string, and object `payload`; `subscribe` requires an array of `{conversationId, sequence}` objects; `start_turn` requires a nonempty message and conversation ID; `approval` requires `approvalId` and boolean `approved`.
- [ ] Build only the pure test binary, then run it.

  Run: `cmake -S /home/toberyan/workspace/uos-ai-0908/uos-ai -B /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests -DBUILD_TESTING=ON`

  Run: `cmake --build /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests --target test_remote_protocol -j2 && ctest --test-dir /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests -R remote_protocol --output-on-failure`

- [ ] Commit only these host paths.

  Run: `git -C /home/toberyan/workspace/uos-ai-0908/uos-ai add CMakeLists.txt tests src/remote/remoteprotocol.h src/remote/remoteprotocol.cpp && git -C /home/toberyan/workspace/uos-ai-0908/uos-ai commit -m "feat: define remote companion protocol"`

### Task 2: Make conversation storage durable and migration-safe

**Files:**
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/conversation/conversationstorage.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/conversation/conversationstorage.cpp`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/conversation/conversationmanager.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/tests/test_conversation_storage.cpp`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/tests/CMakeLists.txt`

- [ ] Add failing tests using injected temporary cache and application-data directories.  Verify: valid legacy conversations move exactly once; a pre-existing destination wins; malformed legacy JSON is retained in place and reported; index files migrate with their conversation set.
- [ ] Introduce `ConversationStorage` with injected paths so the migration is testable and no test touches a real user profile.

  ```cpp
  struct ConversationStoragePaths { QString legacyCacheDirectory; QString durableDirectory; };
  struct ConversationStorageMigrationResult { int moved = 0; int skipped = 0; QStringList warnings; };

  class ConversationStorage {
  public:
      explicit ConversationStorage(ConversationStoragePaths paths);
      ConversationStorageMigrationResult migrateLegacyFiles();
      QString durableDirectory() const;
  private:
      ConversationStoragePaths m_paths;
  };
  ```

- [ ] In `ConversationManager`, derive the durable root from `QStandardPaths::AppDataLocation + "/conversations"`; invoke migration before `loadIndex()`.  Migration must use `QSaveFile` plus rename semantics and a marker written only after every eligible file was processed.  Retain the old cache files if an operation fails, log the warning, and continue from the durable directory.
- [ ] Keep `conversationId` filenames, JSON payload format, index IDs, search entries, and workspace block values unchanged.  The remote service depends on that identity remaining stable.
- [ ] Run the protocol and storage tests together, then commit.

  Run: `cmake --build /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests --target test_conversation_storage -j2 && ctest --test-dir /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests -R "remote_protocol|conversation_storage" --output-on-failure`

  Run: `git -C /home/toberyan/workspace/uos-ai-0908/uos-ai add src/conversation/conversationstorage.* src/conversation/conversationmanager.cpp tests && git -C /home/toberyan/workspace/uos-ai-0908/uos-ai commit -m "fix: persist conversations outside cache"`

### Task 3: Persist canonical Agent projection and replayable events

**Files:**
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotecompanionstore.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotecompanionstore.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/conversationprojectionservice.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/conversationprojectionservice.cpp`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/session/sessionmanager.h`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/session/sessionmanager.cpp`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/session/basesession.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/tests/test_conversation_projection.cpp`

- [ ] Start with a test that feeds a deterministic `SeStarted`, text message, state message, approval request, and `SeFinished` sequence.  Assert that it creates strictly increasing event rows, updates the projected render JSON, writes the conversation once at a terminal state, and can replay events after a supplied cursor.
- [ ] Implement a dedicated SQLite database at `AppDataLocation/remote-companion.sqlite`.  Set WAL mode and foreign keys on every connection.  Create tables in one transaction:

  ```sql
  CREATE TABLE IF NOT EXISTS remote_event (
      conversation_id TEXT NOT NULL,
      sequence INTEGER NOT NULL,
      event_name TEXT NOT NULL,
      payload_json TEXT NOT NULL,
      created_at_ms INTEGER NOT NULL,
      PRIMARY KEY (conversation_id, sequence)
  );
  CREATE TABLE IF NOT EXISTS conversation_projection (
      conversation_id TEXT PRIMARY KEY,
      render_json TEXT NOT NULL,
      last_sequence INTEGER NOT NULL,
      updated_at_ms INTEGER NOT NULL
  );
  ```

- [ ] Add `conversationId` to the session lifecycle.  `SessionManager::createSession` accepts it, stores it in a per-session context, and emits a typed `sessionEvent(const QString &sessionId, const QString &conversationId, const SessionEvent &event)` signal.  Preserve the existing `sessionEvent(QString, QVariant)` signal temporarily as a desktop compatibility adapter; remove it only after the WebView migration in Task 5.
- [ ] `ConversationProjectionService` subscribes once to the typed signal.  It turns each session event into a normalized event, assigns the next transactionally persisted sequence, modifies the matching `ConversationRecord` render tree, and emits `projectedEvent(const RemoteEvent &)`.  It owns persistence at turn finish/failure, so `web/src/stores/conversationrecord.ts` no longer has to be the only place that calls `setConversationRender` and `saveConversation`.
- [ ] Preserve full action payloads for approval events but redact token-like fields before persistence.  Do not persist raw device grants, authorization headers, or file contents not already represented by the conversation render.
- [ ] Retain at most 10,000 events or 30 days per conversation, whichever limit is reached first.  When an iOS cursor predates retained history, return a fresh snapshot with its current cursor instead of an incomplete replay.
- [ ] Run the projection test under AddressSanitizer when the host build supports it, plus the normal CTest run.  Commit the implementation and tests.

  Run: `cmake --build /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests --target test_conversation_projection -j2 && ctest --test-dir /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests -R conversation_projection --output-on-failure`

  Run: `git -C /home/toberyan/workspace/uos-ai-0908/uos-ai add src/remote src/session/sessionmanager.h src/session/sessionmanager.cpp src/session/basesession.cpp tests && git -C /home/toberyan/workspace/uos-ai-0908/uos-ai commit -m "feat: persist canonical conversation projection"`

### Task 4: Centralize desktop and remote turn execution

**Files:**
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/conversationexecutionservice.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/conversationexecutionservice.cpp`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/gui/web/sessionchannel.h`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/gui/web/sessionchannel.cpp`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/gui/web/conversationchannel.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/tests/test_conversation_execution.cpp`

- [ ] Write unit tests with a fake `SessionManager` façade and fake `DirectoryWorkspaceManager` façade.  Test continuing an existing conversation, rejecting an unknown workspace/conversation pair, rejecting a second active turn for the same conversation, cancellation routing, and a repeated approval response.
- [ ] Define a service API that accepts only host-validated values and returns typed results:

  ```cpp
  struct StartTurnRequest {
      QString workspaceId;
      QString conversationId;
      QString message;
      QString assistantId;
      QString modelId;
      QJsonObject attachments;
  };
  struct ExecutionError { QString code; QString message; QJsonObject details; };
  template<class T> using ExecutionResult = std::variant<T, ExecutionError>;

  class ConversationExecutionService : public QObject {
      Q_OBJECT
  public:
      ExecutionResult<QString> startTurn(const StartTurnRequest &request);
      ExecutionResult<std::monostate> cancelTurn(const QString &conversationId);
      ExecutionResult<std::monostate> answerApproval(const QString &conversationId, const QJsonObject &answer);
  };
  ```

- [ ] Resolve `workspaceId` through `DirectoryWorkspaceManager::resolveWorkspaceDirectory`, load the conversation through `ConversationManager`, and verify the conversation belongs to that workspace before creating a session.  Create a new user node in the conversation, retain its persistent conversation ID, then call `SessionManager` with both `sessionId` and `conversationId`.
- [ ] Keep a `conversationId -> sessionId` active map in the service.  Clear it only when a terminal projection event is emitted.  On host restart, mark all previously active projections as `interrupted_by_host_restart`; do not claim a turn continues after an in-memory session vanished.
- [ ] Translate mobile approvals to UOS Claw's existing `invokeAction` payload shape, normalizing the legacy `type`/`ic_type` difference at this boundary.  Never set `always_approve` from a remote client.  Reject expired or already-completed `approvalId` values.
- [ ] Refactor `SessionChannel::sendMessage`, `retry`, `cancel`, and `invokeAction` into thin adapters over this service.  Existing desktop calls retain their current WebChannel signatures; the service becomes the only code path that starts or controls a session.
- [ ] Update the desktop TypeScript stream consumer to render backend-projected events and remove its terminal-only save responsibility.  Retain display-specific state locally, but never replace canonical render data with stale WebView data.
- [ ] Run unit tests and manually perform one desktop new turn, continuation, cancellation, and approval.  Commit host paths only.

  Run: `cmake --build /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests --target test_conversation_execution -j2 && ctest --test-dir /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests -R conversation_execution --output-on-failure`

  Run: `git -C /home/toberyan/workspace/uos-ai-0908/uos-ai add src/remote/conversationexecutionservice.* src/gui/web/sessionchannel.* src/gui/web/conversationchannel.cpp src/session web/src/stores && git -C /home/toberyan/workspace/uos-ai-0908/uos-ai commit -m "refactor: centralize conversation execution"`

### Task 5: Add durable device grants, command idempotency, and sharing policy

**Files:**
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotecompanionstore.*`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotepairingservice.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotepairingservice.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotesharingpolicy.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotesharingpolicy.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/tests/test_remote_pairing.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/tests/test_remote_command_ledger.cpp`

- [ ] First add failing tests that prove a raw token is never stored, an expired one-time pairing secret cannot create a grant, successful pairing returns a token only once, revocation takes effect on the next command, and duplicate `requestId` returns the original result without invoking the execution fake twice.
- [ ] Extend the SQLite migration with these tables:

  ```sql
  CREATE TABLE IF NOT EXISTS remote_device_grant (
      device_id TEXT PRIMARY KEY,
      display_name TEXT NOT NULL,
      token_hash BLOB NOT NULL,
      allowed_workspace_ids_json TEXT NOT NULL,
      created_at_ms INTEGER NOT NULL,
      last_seen_at_ms INTEGER NOT NULL,
      revoked_at_ms INTEGER
  );
  CREATE TABLE IF NOT EXISTS remote_command_ledger (
      device_id TEXT NOT NULL,
      request_id TEXT NOT NULL,
      response_json TEXT NOT NULL,
      completed_at_ms INTEGER NOT NULL,
      PRIMARY KEY (device_id, request_id)
  );
  ```

- [ ] Generate 32 cryptographically random bytes for the device token and one-time pairing secret using `QRandomGenerator::system()`.  Encode them base64url without padding; persist only `SHA-256(token)` plus a constant-format version prefix.  Compare hashes with a constant-time byte loop.  A pairing secret expires after five minutes and is consumed exactly once.
- [ ] Expose `RemoteSharingPolicy::isWorkspaceAllowed`, `isConversationShareable`, and `filterWorkspaceBlocks`.  It must deny private/temporary conversations and directory IDs not in the device grant even if the client guesses the ID.
- [ ] Add ledger expiry cleanup for responses older than 24 hours.  The ledger records deterministic errors too, preventing rapid reconnect loops from duplicating unsafe commands.
- [ ] Commit after the new tests pass.

  Run: `cmake --build /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests --target test_remote_pairing test_remote_command_ledger -j2 && ctest --test-dir /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests -R "remote_pairing|remote_command_ledger" --output-on-failure`

  Run: `git -C /home/toberyan/workspace/uos-ai-0908/uos-ai add src/remote tests && git -C /home/toberyan/workspace/uos-ai-0908/uos-ai commit -m "feat: secure remote companion pairing"`

### Task 6: Implement the Tailscale-bound WebSocket gateway

**Files:**
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotecompanionserver.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotecompanionserver.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotecompanionsettings.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/remote/remotecompanionsettings.cpp`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/app/application.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/tests/test_remote_companion_server.cpp`

- [ ] Write gateway tests using `QWebSocket` and a loopback-only test configuration.  Exercise an unauthenticated close, grant authentication, rejected non-shareable workspace read, subscribe replay followed by a live event, duplicate `start_turn`, cancellation, and denial of a malformed approval.
- [ ] Keep the production bind precondition explicit:

  ```cpp
  struct RemoteListenerConfig {
      QHostAddress tailscaleAddress;
      quint16 port = 45980;
      bool enabled = false;
  };

  bool RemoteCompanionServer::start(const RemoteListenerConfig &config)
  {
      if (!config.enabled || config.tailscaleAddress.isNull() || config.tailscaleAddress == QHostAddress::Any)
          return false;
      return m_server.listen(config.tailscaleAddress, config.port);
  }
  ```

- [ ] Handle the uncredentialed `pair` bootstrap frame before authentication by calling `RemotePairingService::exchangeInvitation`.  All other command frames require an `authenticate` frame containing `deviceId` and `token`; rate-limit failed attempts per socket and close after five failures.  Clear the server-owned token buffer immediately after hash verification, avoid logging it, and never echo it in errors.
- [ ] Dispatch `list_workspaces`, `get_conversation`, `subscribe`, `start_turn`, `cancel`, and `approval` through the policy, projection store, command ledger, and execution service in that order.  Send error frames with stable codes: `unauthenticated`, `forbidden`, `not_found`, `conflict_active_turn`, `invalid_command`, `approval_expired`, `protocol_incompatible`, and `internal_error`.
- [ ] Register `RemoteCompanionServer` during application startup, but start listening only after settings enable it and a selected host address passes local interface validation.  Stop and destroy active sockets on application shutdown.  Do not modify or reuse `src/network/websocketforwardserver.*`.
- [ ] Test the gateway on a real tailnet from a non-host device before enabling it by default.  Set the shipped default to disabled.
- [ ] Run all remote CTests and commit.

  Run: `ctest --test-dir /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests -R remote --output-on-failure`

  Run: `git -C /home/toberyan/workspace/uos-ai-0908/uos-ai add src/remote src/app/application.cpp tests && git -C /home/toberyan/workspace/uos-ai-0908/uos-ai commit -m "feat: serve companion API on tailnet"`

### Task 7: Make desktop pairing and remote sharing understandable

**Files:**
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/gui/web/remotecompanionchannel.h`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/gui/web/remotecompanionchannel.cpp`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/gui/web/webcontext.h`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/gui/web/webcontext.cpp`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/src/gui/window/windowmanager.cpp`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/src/views/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.tsx`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/src/views/window/mainwindow/page/settings/remotecompanion/page.ts`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/src/views/window/mainwindow/page/builtinPages.ts`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/src/types/mainwindow.ts`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/src/stores/mainwindow.ts`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/src/views/window/mainwindow/titlebar/WindowTitleBar.tsx`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/src/stores/backend.ts`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/src/views/root/prodmode.ts`
- Modify: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/package.json`
- Create: `/home/toberyan/workspace/uos-ai-0908/uos-ai/web/src/views/window/mainwindow/page/settings/remotecompanion/RemoteCompanionPage.spec.tsx`

- [ ] Add a native channel that exposes only: listener status, enumerated eligible Tailscale addresses, selected bind address/port, per-workspace sharing toggles, create pairing invitation, list devices, and revoke device.  It must return pairing data once and must never return an existing raw device token.  Add `remoteCompanionCh` to `WebContext`, register `remoteCompanionObj` in both `WindowManager::createContext` and `WindowManager::recoveryPage`, and expose it from `backend.ts` and `prodmode.ts`.
- [ ] Implement `REMOTE_COMPANION` in `MAIN_WINDOW_WORKSPACE_PAGES`, register `remoteCompanionWorkspacePageDefinition` from `builtinPages.ts`, add `openRemoteCompanionPage` to `mainwindow.ts`, and add a title-bar menu action named `ios-companion`.  This follows the existing MCP/Skills workspace-page pattern rather than adding an unused Vue Router route.
- [ ] Implement the page with an explicit disabled-by-default switch, selected `100.x.y.z` Tailscale address, workspace checklist, QR code and copyable complete pairing URI such as `uos-ai://pair?v=1&host=100.85.21.7&port=45980&pairingSecret=pAb4Fg8Hj2Lm6Nq9Rs1Tu3Vw5Xy7Za0B&expiresAtMs=1788839700000&hostDisplayName=UOS-AI`, connected-device list, and revoke control.  Add `qrcode` as a runtime dependency, plus `vitest`, `@vue/test-utils`, and `jsdom` as development dependencies; render the QR locally and do not send invitation data to a hosted QR service.
- [ ] Pairing URI fields are `v`, `host`, `port`, `pairingSecret`, `expiresAtMs`, and `hostDisplayName`.  Reject an invitation whose version or future expiry cannot be parsed before the iOS app sends a network request.
- [ ] Add `test:unit` script as `vitest run`, then add a component test for disabled state, a generated invitation expiry label, workspace selection, and revoke confirmation.  Type-check and build the web bundle without modifying the user-owned `web/dist` artifacts; packaging decides when generated output is refreshed.

  Run: `cd /home/toberyan/workspace/uos-ai-0908/uos-ai/web && npm run type-check && npm run test:unit && npm run build`

  Run: `git -C /home/toberyan/workspace/uos-ai-0908/uos-ai add src/gui/web/remotecompanionchannel.* src/gui/web/webcontext.* src/gui/window/windowmanager.cpp web/src web/package.json web/package-lock.json && git -C /home/toberyan/workspace/uos-ai-0908/uos-ai commit -m "feat: add companion pairing settings"`

### Task 8: Scaffold the iOS app around a testable protocol client

**Files:**
- Create: `ios/project.yml`
- Create: `ios/UOSAICompanion/App/UOSAICompanionApp.swift`
- Create: `ios/UOSAICompanion/App/AppModel.swift`
- Create: `ios/UOSAICompanion/Protocol/RemoteFrame.swift`
- Create: `ios/UOSAICompanion/Network/CompanionWebSocket.swift`
- Create: `ios/UOSAICompanion/Security/KeychainDeviceGrantStore.swift`
- Create: `ios/UOSAICompanion/Features/Pairing/PairingURI.swift`
- Create: `ios/UOSAICompanionTests/RemoteFrameTests.swift`
- Create: `ios/UOSAICompanionTests/PairingURITests.swift`
- Create: `ios/UOSAICompanionTests/KeychainDeviceGrantStoreTests.swift`

- [ ] On a macOS/Xcode development host, create a minimum iOS 17 project with a single application target, unit-test target, UI-test target, and no third-party runtime dependency.  Generate the project from XcodeGen so source-controlled configuration remains portable.

  ```yaml
  name: UOSAICompanion
  options:
    minimumXcodeGenVersion: 2.38.0
  targets:
    UOSAICompanion:
      type: application
      platform: iOS
      deploymentTarget: "17.0"
      sources: [UOSAICompanion]
      settings:
        base:
          PRODUCT_BUNDLE_IDENTIFIER: org.deepin.uos-ai-companion
          SWIFT_VERSION: 5.10
          INFOPLIST_KEY_NSLocalNetworkUsageDescription: "用于通过你的 Tailscale 私有网络连接 UOS AI。"
  ```

- [ ] Write `RemoteFrame` tests before implementing Codable models.  Test exact wire names for every command/event, unknown event preservation, invalid major-version rejection, and a snapshot fixture decoded from the UOS AI protocol test.
- [ ] Implement an actor-backed `CompanionWebSocket` around `URLSessionWebSocketTask`.  It first exchanges a scanned invitation for `{deviceId, token}`, then its state sequence is `disconnected -> connecting -> authenticating -> subscribed -> reconnecting`; exponential retry caps at 30 seconds, and a reconnect always sends saved cursors before accepting live UI commands.  Use only the Tailscale encrypted path specified by the invitation; do not add a broad App Transport Security exception or a public fallback transport.
- [ ] Store `{deviceId, token, host, port}` in a Keychain generic-password item with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.  Test the store through a protocol-backed fake; use the real Security API only in the production implementation.  Never place a token in `UserDefaults`, logs, crash reports, clipboard, screenshots, or notification text.
- [ ] Implement `PairingURI` as a `URLComponents` parser with exact allowlisted keys.  It requires `uos-ai` scheme, `pair` host, version 1, a numeric port in `1...65535`, a nonempty secret, and a numeric future expiry derived from the invitation payload returned by the host.
- [ ] Generate and test on macOS.

  Run: `cd /home/toberyan/orca/workspaces/deepin-ai-for-ios/barramundi/ios && xcodegen generate && xcodebuild -project UOSAICompanion.xcodeproj -scheme UOSAICompanion -destination 'platform=iOS Simulator,name=iPhone 16 Pro' test`

- [ ] Commit only the companion scaffold and generated project.

  Run: `git add ios README.md && git commit -m "feat: scaffold UOS AI iOS companion"`

### Task 9: Implement the iOS workspace, conversation, and Agent experience

**Files:**
- Create: `ios/UOSAICompanion/Features/Workspaces/WorkspaceListView.swift`
- Create: `ios/UOSAICompanion/Features/Conversations/ConversationListView.swift`
- Create: `ios/UOSAICompanion/Features/Conversations/ConversationView.swift`
- Create: `ios/UOSAICompanion/Features/Conversations/ConversationViewModel.swift`
- Create: `ios/UOSAICompanion/Features/Approvals/ApprovalSheet.swift`
- Create: `ios/UOSAICompanion/Features/Pairing/PairingView.swift`
- Create: `ios/UOSAICompanionUITests/CompanionFlowUITests.swift`
- Modify: `ios/UOSAICompanion/App/AppModel.swift`

- [ ] Start with reducer/view-model tests fed by saved protocol fixtures.  Cover workspace filtering, opening an existing `conversationId`, rendering streamed text in sequence order, reconnect event replay without duplicate bubbles, a visible disconnected state, cancelling a running turn, and reject/approve behavior.
- [ ] Show workspace blocks exactly as delivered by the host, but never use their label as an identifier.  Selecting a conversation fetches its snapshot plus cursor, starts a subscription, and displays the backend-projected render nodes.
- [ ] The composer always calls `start_turn` with the selected stable `workspaceId` and `conversationId`; it does not invent a replacement conversation when the user chose “continue.”  Disable Send while there is an active request; enable Cancel while that request is active.
- [ ] Render `agent_approval_requested` as a blocking sheet including action type, a concise title, structured details, explicit “拒绝” and “允许一次” controls, and a safe default when dismissed.  The app never offers an “always approve” switch and never auto-submits an approval after reconnect.
- [ ] Pairing view accepts QR scanning and pasted invitation URI.  Request camera permission only when the user opens scanning; support paste so a camera is not required.
- [ ] Add XCUITest with an injected `MockCompanionWebSocket` launch mode for pair → select workspace → open existing conversation → continue → approval → completion.  Run it in a simulator.

  Run: `cd /home/toberyan/orca/workspaces/deepin-ai-for-ios/barramundi/ios && xcodebuild -project UOSAICompanion.xcodeproj -scheme UOSAICompanion -destination 'platform=iOS Simulator,name=iPhone 16 Pro' test`

- [ ] Commit the iOS user experience separately from the protocol foundation.

  Run: `git add ios && git commit -m "feat: control UOS AI conversations from iOS"`

### Task 10: Verify the real-tailnet integration and document safe operation

**Files:**
- Modify: `README.md`
- Create: `docs/operations/tailscale-companion.md`
- Create: `docs/protocol/remote-companion-v1.md`
- Create: `ios/UOSAICompanionTests/Fixtures/remote-companion-v1.json`

- [ ] Create shared JSON fixtures from the host protocol tests and copy them verbatim into the companion tests.  The fixture set includes successful pairing, workspace list, snapshot, message delta, approval request, terminal state, duplicate command acknowledgement, revoked token, and protocol mismatch.
- [ ] Run the UOS AI unit suite, desktop WebView build, iOS unit/UI suite, and a manual two-device tailnet test.  The manual matrix must prove: socket rejects a non-Tailscale bind configuration; an unpaired client cannot list data; an allowed client can continue a persisted conversation; stream reconnect uses cursor replay; cancel works; an approval requires an explicit tap; revoking a device blocks its next command; and a host restart reports interruption rather than a false completion.

  Run: `ctest --test-dir /home/toberyan/workspace/uos-ai-0908/uos-ai/build-remote-tests --output-on-failure`

  Run: `cd /home/toberyan/workspace/uos-ai-0908/uos-ai/web && npm run type-check && npm run test:unit && npm run build`

  Run: `cd /home/toberyan/orca/workspaces/deepin-ai-for-ios/barramundi/ios && xcodebuild -project UOSAICompanion.xcodeproj -scheme UOSAICompanion -destination 'platform=iOS Simulator,name=iPhone 16 Pro' test`

- [ ] Document the exact host enablement sequence: install and sign in to Tailscale on UOS and iPhone, select the host's Tailscale IP in UOS AI settings, share only required workspaces, scan the time-limited QR, and verify the device label.  Document revocation, host migration, backup implications for the AppData conversation store, and that Tailscale ACLs should limit the UOS host/port to intended user devices.
- [ ] Include this example ACL as an administrator starting point, with the host and tags replaced for the actual tailnet:

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

- [ ] Perform final repository hygiene checks.  Confirm the UOS AI staging area contains no pre-existing translation or `web/dist` modifications.  Commit documentation in the companion repository and any fixture-only host documentation in the repository where it lives.

  Run: `git -C /home/toberyan/workspace/uos-ai-0908/uos-ai status --short && git -C /home/toberyan/orca/workspaces/deepin-ai-for-ios/barramundi status --short`

  Run: `git add README.md docs ios/UOSAICompanionTests/Fixtures && git commit -m "docs: document tailscale companion operation"`

## Definition of done

- UOS AI owns a default-disabled, exact-Tailscale-address WebSocket listener with authenticated per-device grants, workspace allowlists, command idempotency, cursor replay, revocation, and no public or D-Bus route.
- A desktop WebView and iOS client share the same execution/projection lifecycle; a continued conversation retains its original `conversationId` and backend-durable render state.
- iOS has no raw token outside Keychain, does not auto-approve Agent actions, renders clear approval details, and recovers reconnects from cursors without duplicated content.
- Both repositories have passing relevant tests; host and iOS integration passed on two real devices in the same tailnet; documentation covers pairing, ACLs, revocation, and recovery.
