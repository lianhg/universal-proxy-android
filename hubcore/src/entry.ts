// hubcore entry — 复用桌面端 src/core/hub 解析中心（同一份代码，非重写）
import { importText, importQrPayload, protocolCoverage } from './hub/index.js';
import { generateKernelConfig } from './hub/kernel.js';
import type { UnifiedNode } from './hub/model.js';

const PRIVATE_CIDR = ['0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8', '169.254.0.0/16', '172.16.0.0/12', '192.0.0.0/24', '192.168.0.0/16', '224.0.0.0/4', '240.0.0.0/4', 'fc00::/7', 'fe80::/10'];

/**
 * UnifiedNode[] → 完整 sing-box 运行配置（安卓 tun 形态）。
 * 协议字段映射 100% 走桌面端同一份 buildSingBoxOutbound（经 55+ 测试）。
 * mode: 'global' 全部走代理；'bypass-private' 仅局域网直连。
 */
function buildSingBoxConfig(nodes: UnifiedNode[], selectedId: string, mode: string): string {
  const selected = nodes.find(n => n.id === selectedId) ?? nodes[0];
  if (!selected) return JSON.stringify({ error: '没有可用节点' });
  const gen = generateKernelConfig([selected], 'sing-box');
  const hardIssues = (gen.issues ?? []).filter((i: any) => i.level === 'error');
  if (hardIssues.length) return JSON.stringify({ error: hardIssues.map((i: any) => i.message).join('；') });
  const outs = (gen.config as any)?.outbounds ?? [];
  const proxyOut = outs.find((o: any) => o.tag === selected.name);
  if (!proxyOut) return JSON.stringify({ error: 'outbound 生成失败' });
  const rules: any[] = [];
  if (mode !== 'global') {
    rules.push({ action: 'route', outbound: 'direct', ip_cidr: PRIVATE_CIDR });
  }
  const config = {
    log: { level: 'info', timestamp: true },
    dns: {
      servers: [
        { tag: 'dns-local', type: 'udp', server: '223.5.5.5', detour: 'direct' },
        { tag: 'dns-remote', type: 'https', server: 'dns.google', detour: proxyOut.tag },
      ],
      final: 'dns-remote',
    },
    inbounds: [
      {
        type: 'tun', tag: 'tun-in',
        address: ['172.19.0.1/30', 'fdfe:dcba:9876::1/126'],
        mtu: 9000, strict_route: false, stack: 'mixed',
        platform: { http_proxy: { enabled: false } },
      },
    ],
    outbounds: [proxyOut, { type: 'direct', tag: 'direct' }, { type: 'block', tag: 'block' }],
    route: { rules: [{ action: 'sniff' }, { protocol: ['dns'], action: 'hijack-dns' }, ...rules], final: proxyOut.tag, auto_detect_interface: true, default_domain_resolver: 'dns-remote' },
    experimental: {
      clash_api: { external_controller: '127.0.0.1:21811', external_ui: '', secret: '' },
      cache_file: { enabled: true },
    },
  };
  return JSON.stringify({ config: JSON.stringify(config), nodeName: selected.name, protocol: selected.protocol });
}


/* ───────────────────── 卡片数据层：复用桌面 telemetry.ts / regions.ts 同一份代码 ───────────────────── */

import {
  emptyTelemetry, applySample, parseClashConnections, aggregateConnections, outboundRatio,
  formatBytes, formatSpeed, describeMonitorStatus, formatSampledAgo,
  type TelemetrySnapshot, type ConnectionsPayload,
} from './telemetry.js';
import { regionOf } from './regions.js';

/** 上一次采样快照（模块内状态；每次成功/失败都重建，不沿用旧帧冒充实时） */
let snapshot: TelemetrySnapshot = emptyTelemetry('sing-box', '内核未连接，等待用户点击「开始连接」');

/** Kotlin 每秒轮询 clash_api 后把 /connections 原始 JSON 喂进来；返回归一化快照 */
function ingestConnections(rawJson: string, versionStr: string): string {
  try {
    const payload: ConnectionsPayload = parseClashConnections(JSON.parse(rawJson));
    snapshot = applySample(snapshot, payload, Date.now(), versionStr);
    return JSON.stringify({ ok: true, snapshot });
  } catch (e: any) {
    // 失败：速率归零 + 具体原因，绝不返回上一帧
    snapshot = { ...emptyTelemetry('sing-box', '采样失败：' + String(e?.message ?? e)), state: 'error', error: String(e?.message ?? e) } as TelemetrySnapshot;
    return JSON.stringify({ ok: false, error: String(e?.message ?? e), snapshot });
  }
}

/** Kotlin 轮询层自身失败（连不上控制端口等）时也把原因喂进来，界面不沿用旧帧 */
function ingestPollFailure(reason: string): string {
  snapshot = { ...emptyTelemetry('sing-box', '轮询失败：' + reason), state: 'error', error: reason } as TelemetrySnapshot;
  return JSON.stringify({ ok: false, error: reason, snapshot });
}

/** 重置（断开时调用） */
function resetTelemetry(): string {
  snapshot = emptyTelemetry('sing-box', '内核未连接，等待用户点击「开始连接」');
  return JSON.stringify({ ok: true });
}

/** 首页卡片聚合：一次返回全部渲染所需数据（纯展示计算，全部走桌面同一份函数） */
function buildDashboardCards(inputJson: string): string {
  const inp = JSON.parse(inputJson) as {
    connected: boolean; nodeName: string; listenPort: number | null;
    runningSeconds: number; nodesJson: string; selectedId: string;
    diagnosis?: any;
  };
  const t = snapshot;
  const status = describeMonitorStatus({
    connected: inp.connected, runtimeState: inp.connected ? 'running' : 'idle',
    runtimeMessage: '', kernel: 'sing-box', nodeName: inp.nodeName,
    listenPort: inp.listenPort, telemetry: t, diagnosis: inp.diagnosis ?? null, now: Date.now(),
  });
  const ratio = outboundRatio(t.connections);
  const byProcess = aggregateConnections(t.connections, 'chain', 6);
  // 节点地区分组（桌面 regions.ts 同一规则：只认节点名真实关键词，不猜）
  let groups: Record<string, number> = {};
  let nodeRows: any[] = [];
  try {
    const nodes = JSON.parse(inp.nodesJson || '[]');
    for (const n of nodes) {
      const g = regionOf(n); // 桌面同一规则：只认节点名真实关键词，认不出归「其他地区」
      groups[g] = (groups[g] || 0) + 1;
      nodeRows.push({ id: n.id, name: n.name, protocol: n.protocol, server: n.server, port: n.port, region: g, selected: n.id === inp.selectedId });
    }
  } catch { /* 节点列表解析失败不影响其余卡片 */ }
  const h = Math.floor(inp.runningSeconds / 3600), m = Math.floor((inp.runningSeconds % 3600) / 60);
  return JSON.stringify({
    heroTop: {
      connected: inp.connected,
      connectedText: inp.connected ? '已连接' : '未连接',
      downSpeed: formatSpeed(t.downSpeed), upSpeed: formatSpeed(t.upSpeed),
      runtimeText: inp.connected ? h + '小时' + m + '分钟' : '—',
    },
    power: { running: inp.connected, nodeName: inp.nodeName || '未选择节点' },
    monitor: {
      connection: status.connection, sampling: status.sampling,
      source: status.source, nodeCheck: status.nodeCheck,
      sampledAgo: status.sampledAgo, connectionList: status.connectionList,
      emptyTitle: status.emptyTitle, emptyDetail: status.emptyDetail,
      // 桌面 telemetry.ts 的 advice 面向桌面 UI；安卓按本端可执行动作改写（保持"说人话+给操作"语义）
      advice: status.advice.map((a: string) =>
        a.includes('刷新数据') ? '下拉本页或等下一秒自动重采样'
        : a.includes('运行日志') ? '下拉本页稍候重试，仍失败请断开重连'
        : a),
    },
    traffic: {
      downTotal: formatBytes(t.downloadTotal), upTotal: formatBytes(t.uploadTotal),
      active: t.activeConnections, sampledAgo: formatSampledAgo(t.ts, Date.now()),
      ratio, history: t.history.map(p => ({ up: p.up, down: p.down })),
    },
    ranking: byProcess,                   // 出口链路排行（chain 维度）
    nodeGroups: groups, nodeRows,
    emptyReason: inp.connected ? '' : (t.reason || '内核未运行'),
  });
}

;(globalThis as any).__HUB__ = {
  importText: (text: string, opts?: any) => importText(text, opts ?? {}),
  importQrPayload: (text: string, source: string, opts?: any) => importQrPayload(text, source, opts ?? {}),
  protocolCoverage: () => protocolCoverage(),
  buildSingBoxConfig,
  ingestConnections, ingestPollFailure, resetTelemetry, buildDashboardCards,
};
export {};
