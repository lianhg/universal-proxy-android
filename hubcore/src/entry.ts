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
  };
  return JSON.stringify({ config: JSON.stringify(config), nodeName: selected.name, protocol: selected.protocol });
}

;(globalThis as any).__HUB__ = {
  importText: (text: string, opts?: any) => importText(text, opts ?? {}),
  importQrPayload: (text: string, source: string, opts?: any) => importQrPayload(text, source, opts ?? {}),
  protocolCoverage: () => protocolCoverage(),
  buildSingBoxConfig,
};
export {};
