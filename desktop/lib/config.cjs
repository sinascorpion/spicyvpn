function buildConfig(profile, settings, ports, upstream) {
  if (!['proxy', 'tun'].includes(settings.mode)) throw new Error('Invalid connection mode.');
  const dnsOnly = profile.engine === 'dns';
  const countries = (settings.countries || []).filter(code => /^[a-z]{2}$/.test(code));
  const processRules = (settings.processes || []).filter(p => p && !/[\r\n,]/.test(p));
  const routes = [];
  if (settings.mode === 'tun' || dnsOnly) for (const name of ['xray', 'xray.exe', 'aether', 'aether.exe']) routes.push(`PROCESS-NAME,${name},DIRECT`);
  for (const p of processRules) routes.push(`PROCESS-NAME,${p},${settings.processMode === 'include' ? 'SPICY' : 'DIRECT'}`);
  for (const code of countries) routes.push(`GEOSITE,${code},DIRECT`, `GEOIP,${code.toUpperCase()},DIRECT`);
  routes.push(`MATCH,${dnsOnly || (processRules.length && settings.processMode === 'include') ? 'DIRECT' : 'SPICY'}`);
  const proxy = upstream || { ...profile.data, name: 'selected' };
  return {
    'mixed-port': ports.proxy,
    'bind-address': '127.0.0.1', 'allow-lan': false,
    'external-controller': `127.0.0.1:${ports.api}`, secret: ports.secret,
    mode: 'rule', 'log-level': 'warning', ipv6: true,
    'find-process-mode': processRules.length || settings.mode === 'tun' || dnsOnly ? 'always' : 'off',
    'geodata-mode': true,
    'geox-url': { geoip: 'https://raw.githubusercontent.com/Chocolate4U/Iran-v2ray-rules/release/geoip.dat', geosite: 'https://raw.githubusercontent.com/Chocolate4U/Iran-v2ray-rules/release/geosite.dat' },
    dns: { enable: true, listen: `127.0.0.1:${ports.dns}`, ipv6: true, 'enhanced-mode': 'fake-ip', 'fake-ip-range': '198.18.0.1/16', 'default-nameserver': ['1.1.1.1', '8.8.8.8'], nameserver: dnsOnly ? [profile.data.resolver] : settings.dns || ['https://dns.google/dns-query'], 'proxy-server-nameserver': ['https://dns.google/dns-query'] },
    tun: { enable: settings.mode === 'tun' || dnsOnly, device: process.platform === 'darwin' ? 'utun150' : 'SpicyVPN', stack: 'gvisor', 'auto-route': true, 'auto-detect-interface': true, 'dns-hijack': ['any:53', 'tcp://any:53'], 'strict-route': true },
    proxies: dnsOnly ? [] : [{ ...proxy, name: 'selected' }],
    'proxy-groups': dnsOnly ? [] : [{ name: 'SPICY', type: 'select', proxies: ['selected'] }],
    rules: routes
  };
}
function buildXray(profile, port) {
  const value = profile.engine === 'xray-json' ? structuredClone(profile.data) : { outbounds: [{ ...profile.data, tag: 'proxy' }] };
  value.inbounds = [{ tag: 'desktop', listen: '127.0.0.1', port, protocol: 'socks', settings: { auth: 'noauth', udp: true }, sniffing: { enabled: true, destOverride: ['http', 'tls', 'quic'] } }];
  // Native TUN and API entries from imported configurations never own the host.
  delete value.api;
  value.log = { loglevel: 'warning' };
  if (!Array.isArray(value.outbounds) || !value.outbounds.length) throw new Error('Xray configuration has no outbound.');
  return value;
}
module.exports = { buildConfig, buildXray };
