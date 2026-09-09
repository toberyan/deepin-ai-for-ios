# UOS AI 本地网络配对设计

## 决策

UOS AI Companion 的首个可用传输模式采用 **局域网直连**。它可以通过当前 Wi-Fi，或在设备已有 Tailscale 网络路径时通过 Tailscale IP 连接；Tailscale 只提供路由可达性，不参与身份验证、账号登录或 HTTPS 证书签发。

当前迭代只实现并验收 Android 客户端。iOS 客户端改造、兼容和实机验证不在本轮范围；它将在 Android 配对端到端稳定后，以同一 v2 协议单独规划。

这项设计取材于 Orca 的配对页：用户选择 `局域网`，选择本机可达网络地址，扫描一次性二维码完成配对。`Orca Relay` 入口会保留为“试验阶段”，但在 UOS AI 建立独立中继和账号授权服务之前不可选择。

本设计取代旧的 `tailscale cert` / `*.ts.net` 配对前置条件。用户不需要进入 Tailscale 管理后台，也不需要启用 HTTPS Certificates。

## 目标与非目标

### 目标

- 在同一 Wi-Fi 上安全配对 Android 手机或平板。
- 若手机与主机已可通过 Tailscale IP 互通，也可以选择该地址配对；UOS AI 不发起 Tailscale 登录，也不要求配置 Tailnet 管理后台。
- 保留已实现的一次性、五分钟有效的邀请码、每设备令牌、工作区 allowlist 与撤销能力。
- 通过二维码绑定 TLS 服务器身份，避免把本地网络上的任何证书都当作可信证书。
- 让宿主配对页的主流程接近 Orca：连接方式、网络选择、二维码、重新生成和复制恢复码。

### 非目标

- 不实现可用的 Relay、中继服务、Orca 账号、云端账号登录或公网穿透。
- 不修改或验收 iOS 客户端；iOS 不作为当前协议迁移的发布目标。
- 不使用 Tailscale Funnel、管理后台 HTTPS Certificates、`tailscale cert` 或 `*.ts.net` 域名。
- 不监听 `QHostAddress::Any`、不暴露公开端口、不回退到明文 WebSocket。
- 不对已有已配对设备做无感迁移；它们需在新协议下重新扫描配对。

## 用户流程

1. 用户在 UOS AI 的 Mobile Companion 页面选择共享工作区。
2. 页面默认选中 `局域网`；`Orca Relay` 显示其需要未来的账号与中继服务，保持不可用。
3. 页面列出已启用的非回环 IPv4 地址，包括 Wi-Fi/以太网地址以及存在时的 Tailscale 地址。用户选择一个地址和端口（默认 45980）。
4. 用户点击 `生成配对二维码`。主机创建或读取本地 TLS 身份、启动仅绑定到所选地址的安全 WebSocket 监听器，并创建现有的一次性邀请。
5. 二维码包含主机地址、端口、TLS 公钥 SHA-256 指纹、一次性配对秘密、有效期和显示名。页面显示二维码、到期时间、`重新生成代码` 与 `复制配对码`。
6. Android 扫描二维码，校验证书公钥指纹后通过 WSS 兑换一次性秘密；主机签发每设备令牌并按选择的工作区授权。
7. 二维码过期、地址不可达或证书指纹不符时，Android 明确失败，不保存设备令牌。

选择某个地址意味着手机必须能路由到该地址：Wi-Fi 地址需要同一局域网；Tailscale 地址需要手机已有可用的 Tailscale 网络路径。选择行为不会改变系统网络设置、发起 Tailscale 登录或要求管理后台操作。

## 安全模型

### 本地 TLS 身份

宿主在用户首次生成二维码时，在 UOS AI 的 per-user AppData `mobile-companion/tls` 目录生成 ECDSA P-256 私钥和自签名 X.509 证书。私钥文件权限为仅当前用户可读写；证书和私钥均不上传。

证书的 subject/SAN 不承担身份验证职责，因为局域网地址可以变动且可能是 IP 地址。Android 为这一个配对/重连目标创建专用的 OkHttp TLS client：其 TrustManager 只接受叶证书 SubjectPublicKeyInfo SHA-256 与二维码完全一致的证书，且其受限 HostnameVerifier 只允许二维码的目标地址。系统信任库不会被修改，指纹不会被全局信任。这样可防止本地网络中的中间人用另一张自签名证书窃取一次性秘密或设备令牌。

主机仍只在用户选择的单一地址上监听，仍要求一次性、五分钟有效的邀请码。邀请码换取的设备令牌继续只在 Android Keystore 中保存；主机继续只存令牌哈希。二维码、错误日志和 UI 不显示长期设备令牌或私钥。

### 配对 URI v2

二维码使用 `uos-ai://pair`，但通过 `v=2` 与现有 Tailnet-only v1 分开。v2 必须只含下列 URL 编码参数，重复、未知或缺失参数一律拒绝：

| 参数 | 说明 |
| --- | --- |
| `v` | 固定为 `2`。 |
| `transport` | 固定为 `local`。 |
| `host` | 选中的 IPv4 字面地址；只允许 RFC1918 局域网段或 Tailscale CGNAT 段（100.64.0.0/10），不得是通配、回环、链路本地或公网地址。 |
| `port` | 1–65535。 |
| `tlsSpkiSha256` | 主机 TLS 叶证书 SubjectPublicKeyInfo 的 32 字节 SHA-256，base64url 且无 padding。 |
| `pairingSecret` | 现有 32 字节随机、一次性秘密。 |
| `expiresAtMs` | 现有五分钟邀请过期时间。 |
| `hostDisplayName` | 仅供用户展示。 |

Android 为 v1 保留当前 `.ts.net` + 平台信任 WSS 行为，以便已发布客户端能给出明确的“需要更新主机/客户端”提示；新桌面端只发 v2。Android 对 v2 绝不接受任意证书、域名信任回退或 `ws://`。

已保存设备授权需同时保存连接地址、端口、传输类型和 v2 指纹。重连继续使用这些值及同一 pin。用户切换网络地址、重置本地 TLS 身份或更换主机，需重新配对，不能静默更换指纹。

## 组件边界

| 组件 | 责任 |
| --- | --- |
| `LocalNetworkDiscovery`（宿主） | 枚举启用且运行中的非回环网络接口；返回可显示地址与接口名；标识 Tailscale 地址但不调用 Tailscale CLI。 |
| `LocalTlsIdentity`（宿主） | 在 AppData 生成、读取、验证本地自签名 ECDSA 身份；返回证书路径、私钥路径和 SPKI SHA-256 指纹。 |
| `RemoteCompanionServer`（宿主） | 保持现有安全 WebSocket、配对、认证、工作区授权和撤销；接受任意合法的本地监听地址和本地 TLS 身份，而非只接受 Tailnet 主机名。 |
| `Application` / `RemoteCompanionChannel`（宿主） | 验证工作区与地址，协调 TLS 身份、监听器和邀请码，并将结构化非敏感状态提供给 UI。 |
| `RemoteCompanionPage`（宿主） | 呈现 Orca 风格连接方式、网络选择、二维码、刷新与错误。不得包含证书路径、私钥路径或 Tailscale 管理配置。 |
| `PairingUri` 与 `CompanionWebSocket`（Android） | 严格解析 v2 URI；仅在该连接上执行 SPKI pinning；保存设备授权并进行后续 WSS 重连。 |

旧的 `RemoteCompanionBootstrap` 和 `tailscale cert` 路径必须删除或停止由任何 UI/API 调用。相关设置键应迁移为通用 `listenerAddress`、`tlsCertificatePath`、`tlsPrivateKeyPath` 与 `tlsSpkiSha256`，不继续命名为 `tailscaleAddress` 或 `tlsServerName`。

## 状态与错误

| 情况 | 宿主提示 | Android 行为 |
| --- | --- | --- |
| 无可用网络地址 | `没有可用于本地配对的网络` | 不创建二维码。 |
| 未选工作区 | `至少选择一个共享工作区` | 不创建二维码。 |
| 本地 TLS 身份创建/读取失败 | `无法准备本地安全连接` | 保持未配对。 |
| 端口已占用或监听失败 | 显示 Qt 的非敏感监听错误 | 不创建二维码。 |
| 二维码过期/已用 | 显示生成新代码 | 丢弃秘密，不保存令牌。 |
| 证书 pin 不符 | 不交换设备令牌 | 显示“无法验证此电脑”，要求重新扫码。 |
| 网络不可达 | 不改变主机授权 | 显示网络不可达，可稍后使用保存的授权重连。 |

## 测试与验收

### 宿主 C++

1. 网络发现测试：过滤回环、通配、关闭接口、链路本地及公网地址；接受 RFC1918 局域网 IPv4 和 Tailscale CGNAT 地址。
2. TLS 身份测试：首次生成、重启后复用、私钥权限、损坏文件恢复失败，以及稳定 SPKI 指纹。
3. 监听器测试：可在指定 LAN 地址启动 WSS；拒绝通配/回环/明文；接收自签名 TLS 配置而不要求 `.ts.net` hostname。
4. 邀请测试：v2 URI 由已启动的本地监听器生成，包含正确 pin；一秒后/第二次兑换失败；不保留邀请秘密。
5. 现有工作区 allowlist、令牌哈希和撤销测试保持通过。

### Android

1. `PairingUri` 测试 v2 的合法 RFC1918/CGNAT IPv4、过期、未知/重复参数、无效地址、非法 pin 和错误编码。
2. socket 测试：正确 pin 能 WSS 配对；不同证书、错误 pin、明文 URL 均失败且不写入 DeviceGrant。
3. 已保存 v2 grant 重连使用同一 pin；证书更换后明确要求重新配对。
4. Android 16 实机：同 Wi-Fi 成功扫描、工作区浏览、发起一次 Agent 操作、撤销设备后立即不能连接；在可用 Tailscale 路径下重复该验证，无需 Tailscale 管理员操作。

### UI

1. 默认选择局域网，Relay 显示为不可用试验功能。
2. 地址切换会使旧二维码失效并要求重新生成。
3. 一次生成完成后页面显示二维码、五分钟有效期、重新生成和复制恢复码。
4. 主流程不显示 Tailnet hostname、证书文件路径或 Tailscale 管理后台操作。
