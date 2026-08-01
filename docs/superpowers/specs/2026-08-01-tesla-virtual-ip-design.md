# 特斯拉虚拟 IP 兼容模式 — 设计文档

日期：2026-08-01
分支：ansen_dev

## 背景与问题

特斯拉车机浏览器主动封锁对私有 IP 网段（RFC1918：`10.0.0.0/8`、`192.168.0.0/16` 等）的访问，连接直接返回 `ERR_CONNECTION_REFUSED`。因此当 Castla 显示手机热点网卡的真实私有 IP（如 `10.166.96.133` 或 `192.168.43.1`）时，车机浏览器无法打开投屏页面；同一热点下的笔记本电脑无此限制，投屏正常。

该限制为特斯拉官方行为，已由同类产品 TeslaMirror 的 FAQ 证实，其绕过方案是使用特斯拉不封锁的保留网段地址（安卓使用 CGNAT 段 `100.99.9.9`，iOS 使用 `240.3.3.3`）。

Castla 代码中已有原作者留下的未实现脚手架：

- `IPrivilegedService.aidl`：`addInterfaceAddress` / `setupTeslaNetworking` / `restartTetheringWithCgnat`
- `PrivilegedService.kt:702-705`：三个方法均为空壳（`return true` / `return ""`）
- `ShizukuSetup.kt:759`：`setupTeslaNetwork(ifName, virtualIp = "100.99.9.9")`，无调用方

## 方案概述

**方案 A：IP 别名**。在 Shizuku/Stellar 特权进程（shell uid 2000）中，给手机热点网卡添加 `100.99.9.9/32` 的 IP 别名。车机访问 `http://100-99-9-9.sslip.io:9090` 时，`100.99.9.9` 不在特斯拉封锁列表内，连接被放行；数据包到达手机（热点网关）后，由于别名是本机地址，直接本地交付给监听 `0.0.0.0:9090` 的 NanoHTTPD 服务器。

选择理由：

- 无 VPN 授权弹窗、无常驻 VPN 图标
- 不占用系统 VPN 槽位，不与用户的代理/VPN 类 app 冲突
- 复用现有脚手架，改动集中
- Stellar 已确认完全兼容 Shizuku API，特权服务可用

否决的备选：

- **方案 B（VpnService）**：独占 VPN 槽位、需授权弹窗，会顶掉用户的代理 app
- **方案 C（重启热点为 CGNAT 网段）**：改动面大、需断开所有热点客户端、机型差异风险高

## 数据流

```
开始投屏
   │
   ▼
检测热点网卡（拥有 currentIp 的接口：swlan0/ap0/softap0…）
   │
   ▼
Shizuku/Stellar 特权服务: setupTeslaNetworking(ifName, "100.99.9.9")
   │  特权进程内级联尝试（记录诊断日志）：
   │  ① INetd binder: interfaceAddAddress(ifName, 100.99.9.9, 32)
   │  ② 命令行: ip addr add 100.99.9.9/32 dev <ifName>（root 启动时有效）
   │
   ├─ 成功 → app 显示 http://100-99-9-9.sslip.io:9090（沿用现有 sslip 格式）
   └─ 失败 → 回退显示原有地址 + FileLogger 记录，不影响投屏
   │
   ▼
停止投屏 → 尽力移除别名（清理）
```

要点：

- **只在热点模式下启用**。手机连接外部 WiFi（如家庭路由器）时，车机发往 `100.99.9.9` 的包会走向路由器而非手机，别名方案无效；该场景维持现有行为（TeslaMirror 也有同样限制，投屏走热点是此类 app 的标准用法）。
- **服务器零改动**：NanoHTTPD 监听 `0.0.0.0:9090`，别名 IP 天然可达。
- URL 显示沿用现有 `sslip.io` 域名格式，避免车机对裸 IP 的 HTTPS-First 升级问题。

## 组件改动

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/castla/mirror/shizuku/PrivilegedService.kt` | 实现 `setupTeslaNetworking` / `addInterfaceAddress` / `removeInterfaceAddress`：netd binder 调用 + 命令行兜底，返回诊断日志；幂等（别名已存在直接成功） |
| `app/src/main/aidl/com/castla/mirror/shizuku/IPrivilegedService.aidl` | 新增 `removeInterfaceAddress`（或复用现有接口，视实现而定）用于清理别名 |
| `app/src/main/java/com/castla/mirror/shizuku/ShizukuSetup.kt` | 已有 `setupTeslaNetwork` 包装；补充清理方法包装 |
| `app/src/main/java/com/castla/mirror/MainActivity.kt` | 投屏启动流程中，热点就绪后触发别名设置；`updateServerUrl()` 在别名激活时优先显示 `100.99.9.9`（sslip 格式）；停止投屏时清理 |
| 新增 `app/src/main/java/com/castla/mirror/network/TeslaVirtualIp.kt` | 纯逻辑类：热点接口选择、虚拟 URL 生成、级联决策。不依赖 Android 组件，可单测 |

## 关键参数

- 虚拟 IP：`100.99.9.9`（与 TeslaMirror 相同，已验证不在特斯拉封锁列表）
- 前缀长度：`/32`（别名，不影响热点子网原有 DHCP 分配）
- 显示 URL：`http://100-99-9-9.sslip.io:9090`

## 错误处理

- 别名设置全部方法失败 → 静默回退原地址显示，诊断日志（FileLogger）记录各级联失败原因，投屏功能不受影响
- 热点重启导致网卡重建、别名丢失 → NetworkCallback 触发时重新应用
- 重复调用幂等：别名已存在时直接返回成功
- 特权服务未连接（Stellar 未运行）→ 跳过，维持原行为

## 测试

`app/src/test/` 新增 JUnit 测试覆盖纯逻辑类 `TeslaVirtualIp`：

1. 从网卡列表中正确选出拥有 currentIp 的热点接口（`swlan0`/`ap0` 存在时命中；无热点接口时返回 null）
2. 别名激活时 URL 为 `http://100-99-9-9.sslip.io:9090`；未激活时保持原 URL
3. 级联决策：netd 失败落到命令行；全部失败返回失败诊断

netd binder 的真实调用无法单测覆盖，依靠诊断日志 + 真机验证。

运行方式：`./gradlew :app:testDebugUnitTest`

## 验证计划（真机，需上车）

1. 手机开热点 → 开始投屏 → app 显示 `http://100-99-9-9.sslip.io:9090`
2. 车机连热点 → 浏览器输入该地址 → 页面打开、画面正常
3. 笔记本同时验证原有地址仍可用（回归）
4. 停止投屏 → 别名被清理（`ip addr show` 确认）
