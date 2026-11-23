const { execFileSync, spawn } = require('node:child_process');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');
const fs = require('node:fs/promises');
const os = require('node:os');
const assert = require('node:assert/strict');
const { Engine } = require('../lib/engine.cjs');
const { parseInput } = require('../lib/profiles.cjs');

async function worker(port, originPort) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'spicy-tun-test-'));
  const engine = new Engine(directory, path.resolve(__dirname, '../engines'));
  engine.on('log', line => process.stderr.write(line));
  try {
    const [profile] = parseInput(`http://fixture:fixture@10.203.77.1:${port}#TUN`);
    const url = `http://10.203.78.1:${originPort}/`;
    await engine.connect(profile, { mode: 'tun', systemProxy: false }, { skipSystemProxy: true, probeUrl: url, probeStatus: 200 });
    // This request has no explicit proxy; only the namespace's TUN can carry it.
    const response = await require('undici').fetch(url, { signal: AbortSignal.timeout(10000) });
    assert.equal(await response.text(), 'SPICY-REAL-TUN');
  } finally { await engine.disconnect(); await fs.rm(directory, { recursive: true, force: true }); }
}

async function parent() {
  if (process.platform !== 'linux' || process.getuid() !== 0 || process.env.GITHUB_ACTIONS !== 'true') throw new Error('TUN integration fixture runs only as root on an isolated GitHub Linux runner.');
  const suffix = process.pid.toString(36), namespace = 'spicy-' + suffix, host = 'spv' + suffix, peer = 'spn' + suffix;
  const ip = (...args) => execFileSync('ip', args, { stdio: 'pipe' });
  const sockets = new Set(); let origin, proxy, child, hops = 0, namespaceCreated = false, linkCreated = false, resolverCreated = false;
  const resolverDirectory = '/etc/netns/' + namespace;
  try {
    ip('netns', 'add', namespace); namespaceCreated = true;
    await fs.mkdir('/etc/netns', { recursive: true });
    await fs.mkdir(resolverDirectory); resolverCreated = true;
    await fs.copyFile('/etc/resolv.conf', path.join(resolverDirectory, 'resolv.conf'));
    ip('link', 'add', host, 'type', 'veth', 'peer', 'name', peer); linkCreated = true;
    ip('link', 'set', peer, 'netns', namespace);
    ip('addr', 'add', '10.203.77.1/24', 'dev', host); ip('addr', 'add', '10.203.78.1/32', 'dev', host); ip('link', 'set', host, 'up');
    ip('-n', namespace, 'addr', 'add', '10.203.77.2/24', 'dev', peer); ip('-n', namespace, 'link', 'set', peer, 'up'); ip('-n', namespace, 'link', 'set', 'lo', 'up'); ip('-n', namespace, 'route', 'add', 'default', 'via', '10.203.77.1');
    origin = http.createServer((_request, response) => response.end('SPICY-REAL-TUN'));
    origin.on('connection', socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
    await new Promise(r => origin.listen(0, '10.203.78.1', r));
    proxy = http.createServer();
    proxy.on('connection', socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
    proxy.on('connect', (request, socket, head) => {
      if (request.url !== `10.203.78.1:${origin.address().port}`) return socket.destroy();
      hops++;
      const outgoing = net.connect(origin.address().port, '10.203.78.1', () => { socket.write('HTTP/1.1 200 Connection Established\r\n\r\n'); if (head.length) outgoing.write(head); socket.pipe(outgoing); outgoing.pipe(socket); });
      sockets.add(outgoing); outgoing.once('close', () => sockets.delete(outgoing)); outgoing.on('error', () => socket.destroy());
    });
    await new Promise(r => proxy.listen(0, '10.203.77.1', r));
    const env = { ...process.env, HTTP_PROXY: '', HTTPS_PROXY: '', ALL_PROXY: '', NODE_USE_ENV_PROXY: '0' };
    child = spawn('ip', ['netns', 'exec', namespace, process.execPath, __filename, '--worker', String(proxy.address().port), String(origin.address().port)], { env, stdio: 'inherit' });
    const timeout = setTimeout(() => child.kill('SIGTERM'), 60000);
    const code = await new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject); }); clearTimeout(timeout);
    assert.equal(code, 0); assert(hops >= 2, 'Both explicit probe and ordinary system traffic must traverse the selected proxy');
    console.log('Real Linux system TUN passed in isolated network namespace.');
  } finally {
    child?.kill(); for (const socket of sockets) socket.destroy(); origin?.close(); proxy?.close();
    if (namespaceCreated) { try { ip('netns', 'del', namespace); } catch {} }
    if (linkCreated) { try { ip('link', 'del', host); } catch {} }
    if (resolverCreated) await fs.rm(resolverDirectory, { recursive: true, force: true });
  }
}
(process.argv[2] === '--worker' ? worker(Number(process.argv[3]), Number(process.argv[4])) : parent()).catch(error => { console.error(error); process.exitCode = 1; });
