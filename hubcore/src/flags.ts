// 安卓精简版：只保留国家名解析（Intl），去掉桌面端 Vite 专用的 import.meta.glob 图源映射
const ALIAS: Record<string, string> = { UK: 'GB', TW: 'CN' };

export function normalizeCountryCode(code: unknown): string {
  if (typeof code !== 'string') return '';
  const c = code.trim().toUpperCase();
  return /^[A-Z]{2}$/.test(c) ? ALIAS[c] ?? c : '';
}

let dn: Intl.DisplayNames | null = null;
try { dn = new Intl.DisplayNames(['zh-CN'], { type: 'region' }); } catch { /* 老 WebView 无 Intl 时退化为原码 */ }

export function countryName(code: string): string {
  const c = normalizeCountryCode(code);
  if (!c) return '';
  const special: Record<string, string> = { CN: '中国', HK: '中国香港', MO: '中国澳门', TW: '中国台湾' };
  if (special[c]) return special[c];
  try { return dn?.of(c) ?? c; } catch { return c; }
}
