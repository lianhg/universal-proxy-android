# 万能代理客户端 · 安卓原生版

Windows 桌面版（https://github.com/lianhg/universal-proxy-client）的安卓移植，路线：原生 Kotlin + VpnService + 内嵌 sing-box Go 内核。

## 与桌面版的关系

| 层 | 复用方式 |
|---|---|
| 协议/订阅解析 | **同一份代码**：桌面端 `src/core/hub`（16 类协议分享链接、Clash/sing-box/Xray 配置、去重、能力矩阵）经 esbuild 打成 `hub.bundle.js`，跑在 App 内无头 WebView（`HubCore.kt`），零重写 |
| 运行内核 | sing-box v1.14.4 以 **Go 库**形态编译进 APK（gomobile bind，`core/` 桥接层），VpnService 的 tun fd 经 `PlatformInterface.OpenInterface` 注入 sing-tun。安卓不允许 spawn 进程 / 改全局代理，这是唯一合法路径 |
| 配置生成 | `hubcore/src/entry.ts` 里 buildSingBoxConfig：协议字段映射 100% 走桌面端 `kernel.ts` 的 buildSingBoxOutbound（同一份、经测试），外层套安卓 tun 形态运行配置 |
| 系统代理 | 不适用（安卓无应用可写的全局代理）；分流由 sing-box route 规则完成 |

## 已验证（构建机可做的最强验证）

- 8 类协议（VMess/WS/TLS、VLESS/Reality、VLESS/XTLS-Vision、Trojan/WS、SS-AEAD、Hysteria2、TUIC、SOCKS5）生成的配置全部通过 **sing-box 官方 `check`（带 with_utls/with_quic/with_gvisor 标签构建）**，exit 0 无警告
- Reality 用合法格式公钥复验 PASS；SSv2022 的失败是测试样例密钥长度写错（需 16 字节），非生成器问题
- APK：`app/build/outputs/apk/debug/app-debug.apk`（含 libgojni.so ≈50MB + hub.bundle.js）

## 构建

```
# 1) Go 内核 AAR（需 NDK + gomobile，见 ARCHITECTURE.md 工具链）
cd core && gomobile bind -target=android/arm64 -androidapi=24 -tags=with_utls,with_quic,with_gvisor -o out/core-singbox.aar .
# 2) APK
gradle assembleDebug
```

## 功能状态（v0.1）

已实现：订阅/链接导入（预览→确认语义与桌面一致）、节点持久化与去重、连接/断开（VpnService）、局域网直连分流、运行通知。
未实现（路线图）：真实延迟测速、流量统计页、规则编辑 UI、二维码、x86_64 设备、正式签名。

## 虚拟测试（Android 模拟器）发现的真实 BUG（均已修复）

在 x86_64 模拟器（API 34，WHPX 加速）上跑通「导入 → 连接 → 卡片实时刷新」全链路，抓到 6 个静态分析发现不了的真实问题：

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| 1 | 点导入无反应（协程永久挂起） | `w.post{evaluateJavascript}` 在页面加载完成前执行被丢弃，`ready` 永不为真 | 改用 `WebViewClient.onPageFinished` 注入 + `__HUB__` 自检 + 15s 超时兜底 |
| 2 | 连接报 `JSONException: Value … cannot be converted to JSONObject` | 桥对已返回字符串的入口又套一层 `JSON.stringify` → 双重编码 | 桥按类型处理：字符串原样传，对象才 stringify |
| 3 | `SecurityException: does not require BIND_VPN_SERVICE` | Manifest 的 service 缺 `android:permission="android.permission.BIND_VPN_SERVICE"`（`establish()` 硬性要求） | 补上该属性 |
| 4 | `missing DNS transport options registry in context` | 配置解码用了 `context.Background()`，没有协议注册表 | 先 `include.Context()` 建注册表再解码（解码与启动共用同一 ctx） |
| 5 | `netlink socket in Android is banned by Google` | `auto_detect_interface: true` 触发 netlink 接口监视器，安卓应用无权用 | 改 `false`（VpnService 已给出固定路由，无需内核探测） |
| 6 | `detour to an empty direct outbound makes no sense` | `dns-local` 服务器 detour 到空 direct outbound，sing-box 1.14 拒绝 | 移除 `dns-local`（局域网目标都是 IP 字面量，不需本地 DNS） |
| 7 | 轮询全失败 `Cleartext HTTP traffic to 127.0.0.1 not permitted` | Android 9+ 默认禁明文 HTTP，clash_api 是 `http://127.0.0.1` | `network_security_config.xml` 仅对 127.0.0.1/localhost 放行明文（公网仍禁） |
| 8 | `buildDashboardCards` 报 `"[object Object]" is not valid JSON` | 桥传的是对象字面量，函数又 `JSON.parse` 一次 | 兼容 string / object 两种入参 |

验证结果（模拟器实测）：`已连接` · `节点：10.0.2.2:1080` · `活跃连接：0 · 采样 刚刚` · 监控徽章 `实时采样中`/`clash_api 全量` · 地区分组 `其他地区 1`；宿主机 SOCKS5 测试代理确认收到模拟器发出的 CONNECT 请求，证明「VPN 接管 → tun → 内嵌 sing-box → 代理出站」整条链路真实工作。

## 合规

与桌面版一致：仅配置管理与代理工具，使用需遵守所在地网络法规。
