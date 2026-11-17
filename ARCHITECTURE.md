# 万能代理客户端 安卓原生版（VPN-Android）

## 目标
路线 B：真正能连的安卓代理客户端。

## 技术选型（定死）
| 层 | 选型 | 理由 |
|---|---|---|
| UI | Kotlin + Jetpack Compose | 官方现代栈 |
| 内核 | sing-box Go 库，gomobile bind 成 AAR | 安卓不能 spawn 进程；sing-box 官方安卓端同方案；桌面端能力矩阵里 sing-box 覆盖协议最全（Reality/Hysteria2/TUIC/WireGuard/AnyTLS） |
| 接管 | Android VpnService + tun2singbox（sing-box 内置 tunnel 模式） | 安卓唯一正道；系统代理 API 应用无权改 |
| 解析核心复用 | 桌面端 src/core/hub 用 esbuild 打成单文件 JS，跑在**无头 WebView**（JS 桥传 JSON） | 零新增原生依赖、离线可用、直接复用经 55+ 项测试的全协议解析/订阅/去重代码；不重写第二套解析器 |
| 配置 | hub 的 UnifiedNode → sing-box JSON（新写一份轻量生成器，语义对齐桌面 config-builder 的 sing-box 分支） | 桌面生成器含 Windows 专属逻辑，只取协议映射部分 |

## 模块
```
VPN-Android/
├─ app/                 # Compose UI + ViewModel + Room(节点/订阅持久化)
├─ hubcore/             # 无头 WebView 承载 hub.bundle.js（解析中心）
├─ singboxcore.aar      # gomobile 产物（由 core/ Go 模块生成）
└─ core/                # Go: 封装 sing-box 启动/停止/统计（gomobile 友好 API）
```

## 功能范围（v0.1）
订阅 URL 拉取+解析导入、节点列表+真实延迟测速（经内核）、连接/断开（VpnService）、
规则（全局/绕过国内/自定义 GEOIP 简版）、运行日志、流量统计。
不含：二维码摄像头（v0.2）、TUN 之外的代理模式、桌面端导出。

## 合规
与桌面端一致：仅配置管理工具，遵守所在地网络法规。
