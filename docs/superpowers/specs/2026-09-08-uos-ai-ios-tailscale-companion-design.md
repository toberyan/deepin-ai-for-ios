# UOS AI iOS Tailscale Companion 设计

## 目标

为 UOS AI 增加一个遵循 Orca Mobile 模式的 iOS 原生伴侣端。运行 UOS AI 的 UOS 主机是工作区、对话、运行中的 Agent 和凭据的唯一事实来源；iOS 只是通过 Tailscale 私网查看和控制主机上的同一对话。

手机发送“继续”或普通消息时，必须在已有 `conversationId` 上创建下一轮执行，而不是复制对话或恢复一个已经不存在的旧运行时。

## 本地代码事实

本设计只依据 `/home/toberyan/workspace/uos-ai-0908/uos-ai` 中的 UOS AI 源码。

- `ConversationRecord` 保存稳定的对话 ID、工作区、消息树、当前消息节点、模型和助手；`ConversationManager` 将它持久化为 JSON，并维护工作区分块索引。
- `DirectoryWorkspaceManager` 将工作区定义为目录，`ConversationIndex` 将对话按 `workspace.value` 分块显示。
- `SessionManager` 中的 `BaseSession` 是一次 Agent 执行的内存对象。它完成后发出 `SeFinished`，并在三秒延迟释放；因此它不是跨重启或跨回合的会话身份。
- `UOSClaw` 支持取消及包含 `request_id` 的审批动作，并通过 `SidekickAgent` 推送流式消息、状态和审批卡片。
- 当前桌面前端经 `QWebChannel` 使用 `ConversationChannel`、`SessionChannel` 与 `WorkspaceChannel`。这条通道仅适用于本机嵌入 WebView，不能作为远程接口公开。
- Qt `Network` 与 `WebSockets` 已被主程序链接。现有 `WebSocketForwardServer` 仅在 Debug 构建中监听所有地址、无鉴权、只转发消息，不能复用。
- 当前桌面前端在会话结束时调用 `setConversationRender()` 再保存历史；如果没有前端在线，流式 render、工具步骤和审批卡片不会成为后端持久化事实。

## 范围

第一版支持：

- 浏览 UOS 主机上的目录工作区、工作区内对话和运行状态。
- 打开已有对话，查看消息、流式输出、工具/子 Agent 状态及审批卡片。
- 在同一 `conversationId` 中发送普通消息或“继续”。
- 取消当前 Agent；批准或拒绝当前审批请求。
- 一次性二维码配对、每设备独立令牌和即时撤销。
- 事件序号补发与命令幂等，支持 iOS 断线重连。

第一版不提供完整移动 IDE、不暴露桌面 QWebChannel、不中转任意 Shell 命令、不执行任意坐标 UI 自动化，也不使用 Tailscale Funnel 或公网反向代理。

## 架构

```text
iOS Companion (SwiftUI)
  |  Tailscale encrypted path + device credential
  |  typed WebSocket protocol
  v
RemoteCompanionServer (inside the UOS AI main process)
  |- PairingGrantStore / DeviceGrantStore
  |- RemoteEventLog / CommandLedger
  |- ConversationExecutionService
  |- ConversationProjectionService
  `- existing UOS AI domain services
       |- ConversationManager / ConversationRecord
       |- DirectoryWorkspaceManager / WorkspaceFileService
       |- SessionManager / BaseSession
       `- UOSClaw / SidekickAgent
```

`RemoteCompanionServer` 是 UOS AI 主进程中的一个新模块，而不是独立的 Go、Node 或 UI 自动化 Adapter。它只把经审计的领域动作转换为现有 C++ 服务调用；它不把 `QWebChannel` 的泛用槽位代理给网络客户端。

第一版要求 UOS AI 主进程保持运行。这样运行中的 `SessionManager` 对象、等待中的审批请求和流式事件才持续存在。主进程退出后，已完成的对话可以从持久化记录恢复；运行中的 Agent 必须被标记为因主机重启中断。

## 身份模型

| 标识 | 生命周期 | 用途 |
| --- | --- | --- |
| `workspace.value` | 目录存在期间 | 对话分组及 Agent 的默认工作目录。 |
| `conversationId` | 持久化对话生命周期 | 手机打开历史和发起下一轮的稳定身份。 |
| `sessionId` | 单次运行到结束后三秒 | 实时流、取消和审批请求的目标。 |
| `requestId` | 调用方重试生命周期 | 对一台已配对设备幂等地执行状态变更。 |
| `eventSequence` | 每个对话的事件日志生命周期 | 重连补发与去重游标。 |

“继续”不是恢复已经结束的 `sessionId`。服务端从 `conversationId` 取出 `ConversationRecord` 的当前消息节点、助手与模型设置，创建新的 `sessionId`，追加下一条用户消息并调用 `SessionManager::runSession()`。`UOSClaw` 随后从同一消息树构造上下文并继续执行。

## 领域服务改动

### ConversationExecutionService

新增共享的 `ConversationExecutionService`，成为启动、重试、取消和审批的唯一入口。现有 `SessionChannel` 与 `RemoteCompanionServer` 都调用它。

- 维护 `conversationId -> active sessionId` 映射，保证每个对话至多一个活动 Agent。
- 使用后端生成的消息 ID、会话 ID、当前模型、当前助手及会话权限；iOS 不提交完整的现有 `SessionChannel::sendMessage()` JSON。
- 活动对话再收到启动请求时，返回冲突和已有 `sessionId`，不让桌面与手机同时修改同一消息树。
- 接收取消时调用现有 `SessionManager::cancelSession(sessionId)`。
- 接收审批时将现有的 `request_id`、审批类型、`approved`、`always_approve` 及 `reject_msg` 交给 `UOSClaw::invokeAction()`；只允许会话当前挂起的审批请求。

### ConversationProjectionService

新增 `ConversationProjectionService`，订阅 `SessionManager::sessionEvent` 并成为 `ConversationRecord` render 投影的唯一写入者。

- 将 `SeStarted`、`SeMessage`、`SeStateMessage`、`SeError` 和 `SeFinished` 映射为不可变远程事件。
- 将文本、思考、工具、子 Agent、状态、审批卡片和错误的 render 数据写入当前的助手消息节点，并在终态时调用 `ConversationManager::saveConversation()`。
- 桌面前端改为渲染此后端投影；既有 `ConversationChannel::setConversationRender()` 不再作为唯一持久化路径，避免桌面与 iOS 对同一 render 列表双写。
- 为每个投影事件分配单调递增 `eventSequence` 和不可变事件 ID。

这样 iOS 重连的对话快照来自真正的后端记录，而不是依赖嵌入 WebView 恰好还在运行。

### RemoteEventLog 与 CommandLedger

新增持久事件日志和命令账本。

- 事件按 `conversationId` 与 `eventSequence` 追加；客户端确认已持久化的最大序号。
- 客户端重连提交最后确认序号。服务器若保留该段事件则补发；若已被压缩，返回 `snapshot_required`，客户端重新读取完整 `ConversationRecord` 快照。
- 所有状态变更都含有 `requestId`。账本以 `(deviceId, requestId)` 为键持久化请求摘要与最终结果；重试返回第一次结果，不再调用 Agent。
- 远程事件日志不得保存临时/隐私会话内容。

## 移动协议

连接成功后，iOS 先交换版本、设备令牌和最后确认的事件序号。协议是版本化的 JSON WebSocket 消息，而不是远程 `QWebChannel`。

允许的读取消息：

- `list_workspaces`：返回 `ConversationManager::workspaceBlocks()` 和未分组对话索引。
- `get_conversation`：返回 `ConversationRecord::toJson()`、活动状态、活动 `sessionId`（若存在）和允许的操作。
- `subscribe`：从指定的 `eventSequence` 推送投影事件。

允许的写入消息：

- `start_turn`：`conversationId`、文本、`requestId`。文本为“继续”时仍由服务端按普通用户回合规则追加，且使用同一对话。
- `cancel`：活动 `sessionId` 与 `requestId`。
- `approval`：活动 `sessionId`、审批 `request_id`、批准/拒绝、是否本会话始终允许与 `requestId`。

文件浏览在第一版只读调用现有 `WorkspaceFileService` 的已验证目录/相对路径接口；编辑、另存为和打开本地应用不属于第一版的移动协议。

## 配对与网络安全

1. 桌面 UOS AI 设置页生成一次性、短期有效的配对秘密以及 `uos-ai://` 配对 URL。
2. URL 含主机的已选择 Tailscale 地址、端口、协议版本和配对秘密，并显示为二维码。
3. iOS 扫描二维码，通过 Tailscale 兑换一次性秘密，取得该 iPhone 独有的随机设备令牌。
4. iOS 只在 Keychain 保存令牌；主机只持久化令牌哈希、设备显示名、签发时间和撤销状态。
5. 每次 WebSocket 连接携带设备令牌和协议版本。令牌被撤销时服务器关闭其活动连接，后续请求返回未授权。

服务端只绑定用户选择的 Tailscale 地址，通常是 `100.x.y.z`。Tailscale 提供链路加密；Tailnet ACL 必须只允许被授权 iPhone 设备访问该端口。不得使用现有 Debug `WebSocketForwardServer`，不得监听 `QHostAddress::Any`，不得启用 Funnel 或公网端口。

## 隐私和审批

- 临时/隐私会话保持现有“不持久化历史”的语义：不被列入移动端，也不写入远程事件日志。
- 手机必须展示 UOS AI 推送的原始审批卡片和影响范围。例如，命令审批显示命令，文件修改审批显示每个路径与变更类型。
- 网络中断、手机退出或审批超时都保持 pending，绝不自动批准。
- `always_approve` 只有用户在 iOS 审批页面显式选择后才允许写入当前会话权限；它不改变其他会话或其他设备。

## 异常处理

| 情况 | 主机行为 | iOS 行为 |
| --- | --- | --- |
| Tailscale 断开 | 保持主机 Agent 和事件日志 | 显示最后同步时间，退避重连 |
| iOS 断开 | 保持当前 `SessionManager` 运行 | 重连后读快照并补事件 |
| UOS AI 主进程重启 | 已保存对话可加载；活动 Session 不可恢复 | 显示任务被主机重启中断 |
| 两端同时启动同一对话 | 拒绝第二次启动，返回活动 session | 显示该对话正在运行 |
| 事件历史过期 | 返回 `snapshot_required` | 替换本地缓存为新快照 |
| 重复命令 | 返回 CommandLedger 中首次结果 | 保持单一命令状态 |
| 审批已不再挂起 | 拒绝过期 `request_id` | 刷新审批卡片状态 |
| 设备令牌撤销 | 关闭连接、拒绝后续认证 | 清理 Keychain 令牌并要求重新配对 |
| 协议版本不兼容 | 在握手阶段拒绝 | 提示更新应用或主机 |

## 持久化迁移

当前 `ConversationManager` 使用 `QStandardPaths::CacheLocation/conversations` 保存被当作历史的对话 JSON。缓存目录可能被系统清理，不能作为跨设备接续的可靠承诺。

实施时将对话、索引、远程事件日志、设备令牌哈希和命令账本迁移到 `QStandardPaths::AppDataLocation`。迁移顺序是：若 AppData 中没有目标数据而旧缓存存在，验证 JSON 后原子复制到 AppData；只在成功校验后切换读取路径。旧数据保留到下一个成功启动周期后再由现有迁移框架清理，避免中断已有 UOS AI 用户的历史。

## 测试与验收

新增 C++ 单元/集成测试目标以及 iOS XCTest/XCUITest。必须覆盖：

1. 一个 `conversationId` 同时只能有一个运行 Session。
2. 远程 `start_turn` 追加到原消息树，并由 UOSClaw 使用原对话 ID 构造上下文。
3. 流式 render 与审批卡片在无桌面 WebView 的情况下仍被后端持久化。
4. 重复 `(deviceId, requestId)` 只触发一次执行。
5. 事件断流后按序号补齐；事件已压缩时返回完整快照。
6. 临时/隐私对话不会出现在任何远程快照或事件中。
7. 审批展示影响范围，拒绝不会调用批准动作，且过期审批不能被提交。
8. 撤销单一设备会关闭该设备连接而不影响其他已配对 iPhone。
9. 真实 iPhone 在同一 Tailnet 上可配对、查看工作区、打开既有对话、发送继续、处理审批、断网重连，并在撤销后立即失去访问权限。

## 非目标

- 不通过 CLI 实现移动控制。当前 CLI 路径会设置 `always_approve=true` 并关闭沙箱，不符合移动审批安全模型。
- 不使用桌面自动化、坐标点击或任意命令执行来控制 UOS AI。
- 不将完整桌面文件编辑器、设置界面或窗口控制迁移到 iOS。
- 不在 iOS 上保存模型凭据、工作区原始文件副本或独立 Agent 状态。
