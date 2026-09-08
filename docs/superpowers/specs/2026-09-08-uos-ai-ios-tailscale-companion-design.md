# UOS AI iOS Tailscale Companion 设计

## 目标

构建一个遵循 Orca Mobile 模式的 iOS 原生伴侣端，使用户通过
Tailscale 私有网络查看并操控运行在 UOS 主机上的 UOS AI。UOS 主机始终
拥有工作空间、会话和 Agent 进程；iOS 恢复并控制该主机已有的会话，绝不
创建一份独立的云端或手机端会话副本。

## 范围

第一版支持：

- 浏览 UOS 主机上的工作空间及其 Agent 状态：`working`、`waiting`、
  `done`、`failed`。
- 打开已有工作空间，查看原有对话、流式回复和工具执行日志。
- 在原会话中发送普通消息或“继续”，恢复 UOS AI 中的同一 Agent 会话。
- 启动、停止或中断 Agent，并批准或拒绝其显式请求。
- 通过一次性二维码将一台 iPhone 与一台 UOS 主机配对，并在手机端撤销后
  拒绝后续连接。

第一版不把 iPhone 做成完整 IDE，不在手机上执行 Agent，也不暴露 UOS 主机
到公网。

## 架构

```text
iOS Companion (SwiftUI)
  |  Tailscale-only WebSocket
  v
UOS Host Gateway
  |- Session / Workspace state store
  |- Event log and replay service
  |- Pairing and device-grant service
  `- UOS AI Adapter
       |- supported local UOS AI API integration
       `- capability-scoped UOS AI UI automation fallback
```

### UOS Host Gateway

Gateway 是部署在 UOS 主机上的常驻进程，也是唯一的会话事实来源。它保存
UOS AI 返回的稳定工作空间 ID、对话 ID、Agent 运行 ID 和当前状态；并将
UOS AI 的状态变化规范化为事件流。所有 Agent 进程和 UOS AI 的本地界面继续
在这台主机上运行，客户端休眠、退出或断网不会终止它们。

Gateway 暴露工作空间快照、会话快照、事件订阅和命令入口。它不会保存另一份
可写对话历史；应始终从 UOS AI 会话源或持久化事件日志恢复原会话。

### UOS AI Adapter

Adapter 隔离 Gateway 与具体 UOS AI 版本的耦合。它提供以下能力：

- `listWorkspaces()`：列出工作空间和 Agent 摘要。
- `getSession(workspaceID)`：取得原会话及其状态。
- `subscribe(workspaceID)`：将 UOS AI 更新转换为 Gateway 事件。
- `sendMessage(sessionID, text, attachments)`：向原会话写入消息。
- `invokeAction(sessionID, action)`：执行 `continue`、`start`、`stop`、
  `approve` 或 `deny`。

优先使用 UOS AI 提供的稳定本地 API、IPC 或会话存储接口。某项功能没有受支持
接口时，Adapter 可使用有能力声明的 UI 自动化兜底；该兜底仅允许已命名的操作，
不可接受任意坐标点击或任意 Shell 指令。无法可靠实现的能力必须报告为不可用。

### iOS Companion

iOS 应用使用 SwiftUI，并分为主机、工作空间列表、会话详情和操作审批界面。
它只缓存最近快照和事件用于断线阅读，不拥有 UOS AI 的会话状态。会话详情提供
消息编辑器、继续、停止、审批/拒绝与日志视图；未获得 Gateway 能力声明的操作
不得显示为可用。

设备令牌只保存在 iOS Keychain。所有连接均使用 Tailscale 可达地址上的安全
WebSocket；应用不需要也不应将 Gateway 暴露到公共互联网。

## 配对和连接

1. UOS 主机生成包含一次性配对秘密、Gateway 的 Tailscale 地址和过期时间的二维码。
2. iOS 扫码后，通过 Tailscale 向 Gateway 兑换一次性秘密。
3. Gateway 为该设备签发独立、可撤销的设备令牌，并记录设备标识、签发时间和授权范围。
4. iOS 将令牌写入 Keychain，并用令牌建立 WebSocket。
5. 每次连接携带设备标识和最后已确认的事件序号。Gateway 校验令牌、撤销状态及
   Tailnet 来源后才接受连接。

配对 URL 等同于短期密码，不能写入日志、截图或提交到仓库。Gateway 必须允许
主机管理员撤销单一设备的令牌；撤销后应断开该设备的活动连接。

## 状态同步与命令流

### 初始同步

连接建立后，iOS 请求主机与工作空间摘要。打开一个工作空间时，Gateway 返回该
工作空间、原会话、Agent 状态和最新事件序号的完整快照，随后订阅该序号之后的事件。

### 事件

每个工作空间事件具有单调递增的 `sequence`，以及不可变的事件 ID、类型和产生时间。
事件类型至少包括会话消息、Agent 状态变化、工具日志、审批请求、命令状态和能力变更。
客户端仅在持久化事件后确认序号；渲染时以事件 ID 去重。

重连时客户端提交最后确认序号。Gateway 从该序号补发事件；若事件保留期已过，
Gateway 返回“需要快照”，客户端清空本地事件游标并重新获取完整快照。

### 命令

iOS 发出的每一个会改变状态的命令必须含有一个客户端生成的幂等 `requestID`。
Gateway 以 `(deviceID, requestID)` 去重，并按以下状态报告执行过程：

`accepted` → `running` → `succeeded | failed | rejected`

网络重试使用同一 `requestID`，因此双击“继续”、断线重发和前后台切换都不能
多次执行 UOS AI 操作。Gateway 在执行前检查目标会话、能力和 Agent 当前状态；
不满足条件时返回可展示的拒绝原因。

## 安全模型

- Gateway 只监听 Tailscale 地址或仅允许 Tailscale 接口访问；不得使用 Funnel、端口映射或公网反向代理。
- Tailnet ACL 必须限制仅授权的 iPhone 用户或设备能访问 Gateway 端口。
- 每台设备使用独立令牌；令牌不共享、不在应用日志中打印，并可单独撤销。
- 手机上的高风险 Agent 请求必须展示 UOS AI 提供的原始请求和影响范围，用户明确批准后才转发。
- Adapter 的 UI 自动化兜底必须使用能力白名单，禁止将 iOS 文本解释为任意本地命令。

## 异常处理

| 情况 | Gateway 行为 | iOS 行为 |
| --- | --- | --- |
| Tailscale 断开 | 保持 Agent 和事件日志运行 | 显示最后同步时间与离线状态，指数退避重连 |
| UOS AI 重启 | 重新发现会话和 Agent；无法恢复时发出状态事件 | 显示真实的 `failed` 或 `unavailable`，不伪装为运行中 |
| Gateway 重启 | 从持久状态和 UOS AI 重建快照 | 重新鉴权、从事件序号补齐或请求快照 |
| 令牌被撤销 | 拒绝新连接并关闭现有连接 | 清除本地令牌，显示需要重新配对 |
| 功能不可支持 | 返回能力缺失错误与可用能力集 | 隐藏/禁用对应操作并说明原因 |
| 命令重复或迟到 | 以 `requestID` 返回原命令结果 | 保持单一命令状态，不再次触发动作 |

## 验收与测试

1. iPhone 可通过同一 Tailnet 扫码配对，随后可看到 UOS 主机的工作空间。
2. 打开已有工作空间时，iOS 展示的对话 ID 与 UOS AI 原会话一致，并能读取其历史和新日志。
3. 从 iOS 发送“继续”和普通消息后，UOS AI 在同一会话中接收；手机可收到对应流式事件。
4. 启动、停止、批准和拒绝操作会在主机执行并把终态反馈到 iOS。
5. 对“继续”命令模拟双击和断线重发，只发生一次 Adapter 调用。
6. 在事件订阅中断后，客户端可从最后事件序号补齐；事件过期时可恢复完整快照且无重复消息。
7. 撤销设备令牌后，现有连接被关闭、后续认证失败，且其他已配对设备不受影响。
8. 非 Tailnet 路径、公网请求和未配对设备无法访问 Gateway。

## 参考模式

该设计参考 Orca Remote Server / Mobile Companion 的职责划分：主机保存项目、
工作空间、终端与 Agent 会话；移动端作为远程控制器恢复同一服务端会话，而不是
运行独立 Agent。相关行为见：

- https://www.onorca.dev/docs/remote-servers
- https://www.onorca.dev/docs/mobile
