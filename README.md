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

## 合规

与桌面版一致：仅配置管理与代理工具，使用需遵守所在地网络法规。
