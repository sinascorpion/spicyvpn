const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const net = require('node:net');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { fetch, ProxyAgent } = require('undici');
const { Engine, freePort, waitPort } = require('../../lib/engine.cjs');
const { parseInput } = require('../../lib/profiles.cjs');
const { EngineHost } = require('../../lib/engine-host.cjs');
const engines = path.resolve(__dirname, '../../engines');

for (const guarded of [false, true]) test(`real Mihomo HTTP proxy data plane and ${guarded ? 'GUI disconnect guardian' : 'disconnect cleanup'}`, { timeout: 40000 }, async t => {
  const sockets = new Set();
  const track = server => server.on('connection', socket => { sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket)); });
  const origin = http.createServer((_req, res) => { res.writeHead(200); res.end('SPICY-DESKTOP-DATA-PLANE'); }); track(origin);
  await new Promise(r => origin.listen(0, '127.0.0.1', r));
  const originPort = origin.address().port; let hops = 0;
  const proxy = http.createServer(); track(proxy);
  proxy.on('connect', (request, socket, head) => {
    if (request.url !== `127.0.0.1:${originPort}`) return socket.destroy();
    hops++;
    const upstream = net.connect(originPort, '127.0.0.1', () => { socket.write('HTTP/1.1 200 Connection Established\r\n\r\n'); if (head.length) upstream.write(head); upstream.pipe(socket); socket.pipe(upstream); });
    sockets.add(upstream); upstream.on('error', () => socket.destroy()); upstream.on('close', () => sockets.delete(upstream));
  });
  await new Promise(r => proxy.listen(0, '127.0.0.1', r));
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'spicy-desktop-test-'));
  const engine = guarded ? new EngineHost(directory, engines) : new Engine(directory, engines); let port;
  engine.on('log', line => t.diagnostic(line));
  t.after(async () => { if (!guarded || engine.child.connected) await engine.disconnect(); engine.close?.(); for (const socket of sockets) socket.destroy(); origin.close(); proxy.close(); await fs.rm(directory, { recursive: true, force: true }); });
  const profile = parseInput(`http://fixture:fixture@127.0.0.1:${proxy.address().port}#Fixture`)[0];
  const url = `http://127.0.0.1:${originPort}/`;
  const state = await engine.connect(profile, { mode: 'proxy', systemProxy: false, dns: ['1.1.1.1'] }, { probeUrl: url, probeStatus: 200, skipSystemProxy: true });
  assert.equal(state.status, 'connected'); port = state.proxyPort;
  const dispatcher = new ProxyAgent(`http://127.0.0.1:${port}`);
  const response = await fetch(url, { dispatcher }); assert.equal(await response.text(), 'SPICY-DESKTOP-DATA-PLANE'); await dispatcher.close();
  assert(hops >= 2, 'requests must traverse the selected upstream proxy');
  if (guarded) {
    const exited = new Promise(resolve => engine.child.once('exit', resolve));
    engine.close(); await exited;
    const alive = await new Promise(resolve => { const socket = net.connect(port, '127.0.0.1'); socket.on('connect', () => { socket.destroy(); resolve(true); }); socket.on('error', () => resolve(false)); });
    assert.equal(alive, false, 'Guardian must stop native listeners after GUI IPC disappears');
  } else { await engine.disconnect(); assert.equal(engine.state.status, 'disconnected'); }
  assert.equal(await fs.stat(path.join(directory, 'proxy-recovery.json')).then(() => true, () => false), false);
});

test('real Xray VMess through shared desktop router', { timeout: 40000 }, async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'spicy-xray-test-'));
  const origin = http.createServer((_req, res) => res.end('SPICY-XRAY-DATA-PLANE'));
  await new Promise(r => origin.listen(0, '127.0.0.1', r));
  const uuid = crypto.randomUUID(), port = await freePort();
  const file = path.join(directory, 'server.json');
  await fs.writeFile(file, JSON.stringify({ log: { loglevel: 'warning' }, inbounds: [{ listen: '127.0.0.1', port, protocol: 'vmess', settings: { clients: [{ id: uuid }] } }], outbounds: [{ protocol: 'freedom', settings: { finalRules: [{ action: 'allow', ip: ['127.0.0.1'], port: String(origin.address().port) }] } }] }));
  const server = spawn(path.join(engines, 'xray' + (process.platform === 'win32' ? '.exe' : '')), ['run', '-c', file], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  server.stdout.on('data', line => t.diagnostic('server: ' + line.toString()));
  server.stderr.on('data', line => t.diagnostic('server: ' + line.toString()));
  const engine = new Engine(path.join(directory, 'client'), engines);
  engine.on('log', line => t.diagnostic(line));
  t.after(async () => { await engine.disconnect(); server.kill(); origin.closeAllConnections(); origin.close(); await fs.rm(directory, { recursive: true, force: true }); });
  await waitPort(port, 10000, () => server.exitCode === null);
  const profile = parseInput('vmess://' + Buffer.from(JSON.stringify({ v: '2', ps: 'Local VMess', add: '127.0.0.1', port, id: uuid, aid: '0', net: 'tcp', tls: '' })).toString('base64'))[0];
  const url = `http://127.0.0.1:${origin.address().port}/`;
  const state = await engine.connect(profile, { mode: 'proxy', systemProxy: false }, { probeUrl: url, probeStatus: 200, skipSystemProxy: true });
  assert.equal(state.status, 'connected');
  const dispatcher = new ProxyAgent(`http://127.0.0.1:${state.proxyPort}`);
  assert.equal(await (await fetch(url, { dispatcher })).text(), 'SPICY-XRAY-DATA-PLANE'); await dispatcher.close();
});
