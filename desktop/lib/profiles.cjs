const crypto = require('node:crypto');
const yaml = require('js-yaml');

const id = () => crypto.randomUUID();
const decode64 = value => Buffer.from(value.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
const unescape = value => { try { return decodeURIComponent(value); } catch { return value; } };
function profile(name, engine, data, original, protocol) {
  return { id: id(), name: name || protocol, engine, data, original, protocol, favorite: false, latency: null };
}
function xrayStream(url) {
  const q = url.searchParams;
  const network = q.get('type') || 'tcp';
  const security = q.get('security') || 'none';
  const stream = { network, security };
  if (security === 'tls' || security === 'reality') {
    const tls = { serverName: q.get('sni') || url.hostname, fingerprint: q.get('fp') || 'chrome' };
    if (q.get('alpn')) tls.alpn = q.get('alpn').split(',');
    if (q.get('cs')) tls.cipherSuites = q.get('cs').replaceAll(':', ',');
    if (security === 'reality') Object.assign(tls, { publicKey: q.get('pbk') || '', shortId: q.get('sid') || '', spiderX: q.get('spx') || '' });
    stream[security === 'tls' ? 'tlsSettings' : 'realitySettings'] = tls;
  }
  if (network === 'ws') stream.wsSettings = { path: q.get('path') || '/', headers: { Host: q.get('host') || url.hostname } };
  if (network === 'grpc') stream.grpcSettings = { serviceName: q.get('serviceName') || q.get('path') || '' };
  if (network === 'xhttp' || network === 'splithttp') {
    stream.xhttpSettings = { host: q.get('host') || url.hostname, path: q.get('path') || '/', mode: q.get('mode') || 'auto' };
    if (q.get('extra')) stream.xhttpSettings.extra = JSON.parse(q.get('extra'));
  }
  if (q.get('fm')) stream.finalmask = JSON.parse(q.get('fm'));
  return stream;
}
function parseLink(text) {
  if (text.startsWith('vmess://')) {
    const c = JSON.parse(decode64(text.slice(8)));
    const u = new URL(`vless://${c.id}@${c.add}:${c.port}`);
    for (const [key, value] of Object.entries({ type: c.net, security: c.tls || 'none', sni: c.sni, host: c.host, path: c.path, alpn: c.alpn, fp: c.fp })) if (value) u.searchParams.set(key, value);
    return profile(c.ps, 'xray', { protocol: 'vmess', settings: { vnext: [{ address: c.add, port: Number(c.port), users: [{ id: c.id, alterId: Number(c.aid || 0), security: c.scy || 'auto' }] }] }, streamSettings: xrayStream(u) }, text, 'VMess');
  }
  if (text.startsWith('ss://')) {
    const raw = text.slice(5).split('#')[0];
    const expanded = raw.includes('@') ? raw : decode64(raw);
    const [auth, address] = expanded.split('@');
    const credentials = auth.includes(':') ? unescape(auth) : decode64(auth);
    const colon = credentials.indexOf(':');
    const u = new URL('socks://' + address.split('?')[0]);
    return profile(unescape(text.split('#')[1] || 'Shadowsocks'), 'mihomo', { type: 'ss', server: u.hostname.replace(/^\[|\]$/g, ''), port: Number(u.port), cipher: credentials.slice(0, colon), password: credentials.slice(colon + 1), udp: true }, text, 'Shadowsocks');
  }
  const u = new URL(text);
  const protocol = u.protocol.slice(0, -1).toLowerCase();
  const name = unescape(u.hash.slice(1)) || `${protocol.toUpperCase()} ${u.hostname}`;
  const host = u.hostname.replace(/^\[|\]$/g, '');
  const port = Number(u.port || 443);
  if (['vless', 'trojan'].includes(protocol)) {
    const settings = protocol === 'vless'
      ? { vnext: [{ address: host, port, users: [{ id: unescape(u.username), encryption: u.searchParams.get('encryption') || 'none', flow: u.searchParams.get('flow') || '' }] }] }
      : { servers: [{ address: host, port, password: unescape(u.username) }] };
    return profile(name, 'xray', { protocol, settings, streamSettings: xrayStream(u) }, text, protocol.toUpperCase());
  }
  if (['hysteria2', 'hy2'].includes(protocol)) return profile(name, 'mihomo', { type: 'hysteria2', server: host, port, password: unescape(u.username), sni: u.searchParams.get('sni') || host, obfs: u.searchParams.get('obfs') || undefined, 'obfs-password': u.searchParams.get('obfs-password') || undefined }, text, 'Hysteria2');
  if (['ssh', 'ssh+tls'].includes(protocol)) {
    const value = profile(name, 'mihomo', { type: 'ssh', server: host, port: Number(u.port || 22), username: unescape(u.username), password: unescape(u.password) }, text, protocol === 'ssh+tls' ? 'SSH+TLS' : 'SSH');
    if (protocol === 'ssh+tls' || u.searchParams.get('tls') === '1') value.transport = { tls: true, servername: u.searchParams.get('sni') || host };
    return value;
  }
  if (['socks', 'socks5', 'http', 'https'].includes(protocol)) return profile(name, 'mihomo', { type: protocol.startsWith('socks') ? 'socks5' : 'http', server: host, port: Number(u.port || (protocol.startsWith('socks') ? 1080 : 8080)), username: unescape(u.username), password: unescape(u.password), tls: protocol === 'https', udp: protocol.startsWith('socks') }, text, protocol.toUpperCase());
  if (['dns', 'udp', 'tcp', 'tls', 'doh', 'dot'].includes(protocol)) return profile(name, 'dns', { resolver: text.split('#')[0].replace(/^doh:/, 'https:').replace(/^dot:/, 'tls:') }, text, 'DNS');
  if (protocol === 'aether') return profile(name, 'aether', Object.fromEntries(u.searchParams), text, 'Aether');
  throw new Error(`Unsupported configuration scheme: ${protocol}`);
}
function parseWireguard(text, name) {
  const groups = text.split(/^\[Peer\]\s*$/m);
  if (groups.length !== 2) throw new Error('WireGuard imports require exactly one peer.');
  const values = source => Object.fromEntries(source.split(/\r?\n/).map(line => line.match(/^\s*([A-Za-z][A-Za-z0-9]*)\s*=\s*(.*?)\s*$/)).filter(Boolean).map(m => [m[1].toLowerCase(), m[2]]));
  const local = values(groups[0]), peer = values(groups[1]);
  const endpoint = new URL('udp://' + peer.endpoint);
  const ips = (local.address || '').split(',').map(v => v.trim()).filter(Boolean);
  const data = { type: 'wireguard', server: endpoint.hostname.replace(/^\[|\]$/g, ''), port: Number(endpoint.port), ip: ips.find(v => !v.includes(':'))?.split('/')[0], ipv6: ips.find(v => v.includes(':'))?.split('/')[0], 'private-key': local.privatekey, 'public-key': peer.publickey, 'pre-shared-key': peer.presharedkey, udp: true, mtu: Number(local.mtu || 1420) };
  const awg = {};
  for (const [key, value] of Object.entries(local)) if (/^(jc|jmin|jmax|s[1-4]|h[1-4]|i[1-5])$/.test(key)) awg[key] = /^\d+$/.test(value) ? Number(value) : value;
  if (Object.keys(awg).length) data['amnezia-wg-option'] = awg;
  if (!data['private-key'] || !data['public-key'] || !data.port) throw new Error('Incomplete WireGuard configuration.');
  return profile(name || 'WireGuard', 'mihomo', data, text, Object.keys(awg).length ? 'AmneziaWG' : 'WireGuard');
}
function parseInput(input, name = '') {
  const text = input.trim();
  if (!text || text.length > 4 * 1024 * 1024) throw new Error('Configuration is empty or too large.');
  if (/^\[Interface\]/m.test(text)) return [parseWireguard(text, name)];
  if (/^\d+\.\d+\.\d+\.\d+$/.test(text)) return [profile(name || text, 'dns', { resolver: text }, text, 'DNS')];
  if (text.startsWith('{') || text.startsWith('[') || /^proxies\s*:/m.test(text)) {
    const value = yaml.load(text, { schema: yaml.JSON_SCHEMA });
    if (value?.outbounds) return [profile(name || 'Xray JSON', 'xray-json', value, text, 'Xray')];
    if (Array.isArray(value?.proxies)) return value.proxies.map(p => profile(p.name, 'mihomo', p, yaml.dump({ proxies: [p] }), p.type || 'Mihomo'));
    if (value?.type && value?.server) return [profile(value.name || name, 'mihomo', value, text, value.type)];
    throw new Error('Expected an Xray configuration or Mihomo proxy list.');
  }
  if (/^https?:\/\//.test(text) && !text.includes('\n')) {
    const url = new URL(text);
    if (!url.username && (!url.port || url.pathname !== '/')) return [{ subscriptionUrl: text, name }];
  }
  if (!text.includes('://') && /^[A-Za-z0-9+/_=\s-]+$/.test(text)) return parseInput(decode64(text), name);
  return text.split(/\r?\n/).map(v => v.trim().replace(/\\$/, '').trim()).filter(Boolean).map(parseLink);
}
module.exports = { parseInput, parseLink, parseWireguard, profile };
