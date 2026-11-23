const test = require('node:test');
const assert = require('node:assert/strict');
const { parseInput } = require('../lib/profiles.cjs');
const { buildConfig, buildXray } = require('../lib/config.cjs');

test('VLESS preserves XHTTP, SNI, cipher suites and finalMask', () => {
  const url = new URL('vless://00000000-0000-4000-8000-000000000000@example.com:443');
  for (const [k, v] of Object.entries({ type: 'xhttp', security: 'tls', sni: 'vpn.example.com', fp: 'unsafe', cs: 'A:B', fm: JSON.stringify({ tcp: [{ type: 'fragment', settings: { lengths: ['0', '100', '1'] } }] }) })) url.searchParams.set(k, v);
  const p = parseInput(url.toString())[0];
  assert.equal(p.engine, 'xray');
  assert.equal(p.data.streamSettings.tlsSettings.serverName, 'vpn.example.com');
  assert.equal(p.data.streamSettings.tlsSettings.cipherSuites, 'A,B');
  assert.deepEqual(p.data.streamSettings.finalmask.tcp[0].settings.lengths, ['0', '100', '1']);
});
test('subscription ordering is not alphabetized', () => {
  const links = ['socks5://127.0.0.1:10001#Z', 'socks5://127.0.0.1:10002#A'];
  assert.deepEqual(parseInput(Buffer.from(links.join('\n')).toString('base64')).map(p => p.name), ['Z', 'A']);
});
test('WireGuard and AWG fields are retained', () => {
  const [p] = parseInput('[Interface]\nPrivateKey = local-key\nAddress = 10.0.0.2/32\nJc = 4\nH1 = 11\n[Peer]\nPublicKey = remote-key\nEndpoint = 127.0.0.1:2408');
  assert.equal(p.protocol, 'AmneziaWG'); assert.equal(p.data['amnezia-wg-option'].jc, 4); assert.equal(p.data.port, 2408);
});
test('encrypted DNS and bare IP imports are profiles, not subscriptions', () => {
  assert.equal(parseInput('1.1.1.1')[0].engine, 'dns');
  assert.equal(parseInput('tls://dns.google')[0].data.resolver, 'tls://dns.google');
});
test('all runtime control listeners are loopback-only and authenticated', () => {
  const config = buildConfig({ engine: 'mihomo', data: { type: 'socks5', server: '127.0.0.1', port: 1080 } }, { mode: 'proxy' }, { proxy: 18888, api: 18889, dns: 18890, secret: 'random-test' });
  assert.equal(config['allow-lan'], false); assert.equal(config['bind-address'], '127.0.0.1'); assert.equal(config['external-controller'], '127.0.0.1:18889'); assert.equal(config.secret, 'random-test'); assert.equal(config.tun.enable, false);
});
test('country routing is opt-in; TUN and DNS-only do not silently become system proxy', () => {
  const ports = { proxy: 18888, api: 18889, dns: 18890, secret: 'random-test' };
  const p = { engine: 'dns', data: { resolver: '1.1.1.1' } };
  const base = buildConfig(p, { mode: 'proxy' }, ports);
  assert.equal(base.tun.enable, true); assert.deepEqual(base.dns.nameserver, ['1.1.1.1']); assert.equal(base.rules.at(-1), 'MATCH,DIRECT');
  assert(!base.rules.some(r => r.startsWith('GEOIP')));
  const routed = buildConfig(p, { mode: 'tun', countries: ['ir'] }, ports);
  assert(routed.rules.includes('GEOIP,IR,DIRECT')); assert(routed.rules.includes('GEOSITE,ir,DIRECT'));
});
test('imported Xray JSON cannot bind arbitrary listeners', () => {
  const config = buildXray({ engine: 'xray-json', data: { api: {}, inbounds: [{ listen: '0.0.0.0' }], outbounds: [{ protocol: 'freedom' }] } }, 12345);
  assert.equal(config.inbounds.length, 1); assert.equal(config.inbounds[0].listen, '127.0.0.1'); assert.equal(config.api, undefined);
});
