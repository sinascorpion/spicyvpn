const http = require('node:http');
const net = require('node:net');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const assert = require('node:assert/strict');
const { Engine } = require('../lib/engine.cjs');
const { parseInput } = require('../lib/profiles.cjs');

(async () => {
  if (process.env.GITHUB_ACTIONS !== 'true' || !['darwin', 'win32'].includes(process.platform)) throw new Error('This fixture changes routes only on disposable GitHub-hosted runners.');
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'spicy-hosted-tun-'));
  const addresses = new Set((await require('node:dns').promises.lookup('raw.githubusercontent.com', { all: true })).map(a => a.address));
  const sockets = new Set(); let hops = 0;
  const proxy = http.createServer();
  proxy.on('connect', (request, socket, head) => {
    sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket));
    const target = new URL('http://' + request.url);
    if (target.hostname === 'raw.githubusercontent.com' || addresses.has(target.hostname.replace(/^\[|\]$/g, ''))) hops++;
    const outgoing = net.connect(Number(target.port || 443), target.hostname, () => { socket.write('HTTP/1.1 200 Connection Established\r\n\r\n'); if (head.length) outgoing.write(head); socket.pipe(outgoing); outgoing.pipe(socket); });
    sockets.add(outgoing); outgoing.on('close', () => sockets.delete(outgoing)); outgoing.on('error', () => socket.destroy());
  });
  await new Promise(r => proxy.listen(0, '127.0.0.1', r));
  const engine = new Engine(directory, path.resolve(__dirname, '../engines'));
  engine.on('log', line => process.stderr.write(line));
  const curl = process.platform === 'win32' ? 'C:/Windows/System32/curl.exe' : '/usr/bin/curl';
  const url = 'https://raw.githubusercontent.com/github/gitignore/main/Global/VisualStudioCode.gitignore';
  const request = () => promisify(execFile)(curl, ['--noproxy', '*', '--silent', '--show-error', '--max-time', '20', '--output', process.platform === 'win32' ? 'NUL' : '/dev/null', '--write-out', '%{http_code}', url], { timeout: 25000, windowsHide: true });
  try {
    assert.equal((await request()).stdout, '200', 'Fixture destination must be reachable before changing routes');
    const [profile] = parseInput(`http://fixture:fixture@127.0.0.1:${proxy.address().port}#TUN`);
    await engine.connect(profile, { mode: 'tun', systemProxy: false, processes: ['node', 'node.exe', 'Runner.Worker', 'Runner.Listener', 'Runner.Worker.exe', 'Runner.Listener.exe'], processMode: 'bypass' }, { skipSystemProxy: true, probeUrl: url, probeStatus: 200 });
    const before = hops;
    assert.equal((await request()).stdout, '200');
    assert(hops > before, 'Ordinary curl traffic must pass the selected tunnel, not the original route');
    await engine.disconnect();
    assert.equal((await request()).stdout, '200', 'Direct networking must work after TUN cleanup');
    console.log(`Real ${process.platform} TUN and route restoration passed.`);
  } finally { await engine.disconnect(); for (const socket of sockets) socket.destroy(); proxy.close(); await fs.rm(directory, { recursive: true, force: true }); }
})().catch(error => { console.error(error); process.exitCode = 1; });
