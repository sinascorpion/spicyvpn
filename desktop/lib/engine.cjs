const { EventEmitter } = require('node:events');
const { spawn } = require('node:child_process');
const net = require('node:net');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { ProxyAgent, fetch } = require('undici');
const { buildConfig, buildXray } = require('./config.cjs');
const { SystemProxy } = require('./system-proxy.cjs');
const { createTlsRelay } = require('./tls-relay.cjs');

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
  });
}
async function waitPort(port, timeout, alive) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (!alive()) throw new Error('Tunnel engine stopped before it became ready.');
    const ready = await new Promise(resolve => {
      const socket = net.connect({ host: '127.0.0.1', port });
      socket.setTimeout(300);
      socket.once('connect', () => { socket.destroy(); resolve(true); });
      socket.once('error', () => { socket.destroy(); resolve(false); });
      socket.once('timeout', () => { socket.destroy(); resolve(false); });
    });
    if (ready) return;
    await new Promise(r => setTimeout(r, 150));
  }
  throw new Error('Tunnel engine startup timed out.');
}
class Engine extends EventEmitter {
  constructor(directory, engines) {
    super(); this.directory = directory; this.engines = engines;
    this.system = new SystemProxy(directory); this.children = []; this.queue = Promise.resolve();
    this.state = { status: 'disconnected', profileId: null, download: 0, upload: 0, ip: null };
  }
  serial(action) { const next = this.queue.then(action, action); this.queue = next.catch(() => {}); return next; }
  update(patch) { this.state = { ...this.state, ...patch }; this.emit('state', this.state); }
  executable(name) { return path.join(this.engines, name + (process.platform === 'win32' ? '.exe' : '')); }
  launch(name, args, cwd) {
    const child = spawn(this.executable(name), args, { cwd, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, XRAY_LOCATION_ASSET: this.engines } });
    this.children.push(child);
    const log = bytes => this.emit('log', bytes.toString().replace(/(password|token|private.?key)\s*[:=]\s*[^\s,]+/gi, '$1=[redacted]').slice(0, 1200));
    child.stdout.on('data', log); child.stderr.on('data', log);
    child.on('error', error => { this.emit('log', error.message); child.failed = true; });
    child.on('exit', () => {
      if (this.state.status === 'connected') this.serial(async () => { await this.stop(); this.update({ error: `${name} stopped unexpectedly.` }); });
    });
    return child;
  }
  connect(profile, settings, options = {}) { return this.serial(async () => {
    await this.stop();
    this.cancelled = false; this.abort = new AbortController();
    this.update({ status: 'connecting', profileId: profile.id, error: null, ip: null });
    try {
      await fs.mkdir(this.directory, { recursive: true, mode: 0o700 });
      const ports = { proxy: await freePort(), api: await freePort(), dns: await freePort(), upstream: await freePort(), secret: crypto.randomBytes(24).toString('hex') };
      this.ports = ports;
      this.runDir = await fs.mkdtemp(path.join(this.directory, 'session-'));
      let upstream;
      if (profile.engine === 'mihomo' && profile.data.type === 'ssh' && profile.transport) {
        this.relay = await createTlsRelay(profile);
        profile = { ...profile, data: { ...profile.data, server: '127.0.0.1', port: this.relay.port } };
      }
      if (profile.engine.startsWith('xray')) {
        const file = path.join(this.runDir, 'xray.json');
        await fs.writeFile(file, JSON.stringify(buildXray(profile, ports.upstream)), { mode: 0o600 });
        const child = this.launch('xray', ['run', '-c', file], this.engines);
        await waitPort(ports.upstream, 12000, () => !this.cancelled && child.exitCode === null && !child.failed);
        upstream = { type: 'socks5', server: '127.0.0.1', port: ports.upstream, udp: true };
      } else if (profile.engine === 'aether') {
        const config = profile.data;
        const protocol = config.protocol || 'gool';
        if (!['gool', 'h2', 'h3', 'wg', 'wiw', 'mim', 'masque'].includes(protocol)) throw new Error('Unsupported Aether protocol');
        const args = ['--bind', `127.0.0.1:${ports.upstream}`, '--protocol', ['h2', 'h3'].includes(protocol) ? 'masque' : protocol === 'wiw' ? 'gool' : protocol, '--scan', config.scan || 'balanced', '--ip', config.ip || 'v4', '--tls-verify'];
        if (['gool', 'h2', 'mim'].includes(protocol)) args.push('--h2');
        for (const [key, flag] of [['outer', '--wiw-outer'], ['inner', '--wiw-inner'], ['peer', '--peer']]) if (config[key]) {
          if (!/^[\da-fA-F.:\[\]]+$/.test(config[key])) throw new Error('Invalid Aether endpoint');
          args.push(flag, config[key]);
        }
        const identity = path.join(this.directory, 'aether'); await fs.mkdir(identity, { recursive: true, mode: 0o700 });
        const child = this.launch('aether', args, identity);
        await waitPort(ports.upstream, 120000, () => !this.cancelled && child.exitCode === null && !child.failed);
        upstream = { type: 'socks5', server: '127.0.0.1', port: ports.upstream, udp: true };
      } else if (!['mihomo', 'dns'].includes(profile.engine)) throw new Error('This desktop engine is not integrated yet.');
      const config = buildConfig(profile, settings, ports, upstream);
      const file = path.join(this.runDir, 'mihomo.json');
      await fs.writeFile(file, JSON.stringify(config), { mode: 0o600 });
      const cache = path.join(this.directory, 'engine-data');
      await fs.mkdir(cache, { recursive: true, mode: 0o700 });
      const child = this.launch('mihomo', ['-d', cache, '-f', file], this.engines);
      await waitPort(ports.api, 20000, () => !this.cancelled && child.exitCode === null && !child.failed);
      await waitPort(ports.proxy, 3000, () => child.exitCode === null && !child.failed);
      this.dispatcher = new ProxyAgent(`http://127.0.0.1:${ports.proxy}`);
      // Never claim Connected merely because a process or a local listener exists.
      const started = Date.now();
      const response = await fetch(options.probeUrl || 'https://www.gstatic.com/generate_204', { dispatcher: this.dispatcher, signal: AbortSignal.any([AbortSignal.timeout(20000), this.abort.signal]), redirect: 'manual' });
      await response.arrayBuffer();
      if (response.status !== (options.probeStatus || 204)) throw new Error('The tunnel did not pass the connectivity check.');
      if (settings.mode === 'proxy' && settings.systemProxy !== false && !options.skipSystemProxy) await this.system.enable(ports.proxy);
      this.update({ status: 'connected', latency: Date.now() - started, proxyPort: ports.proxy });
      this.timer = setInterval(() => this.stats().catch(() => {}), 1000);
      if (!options.probeUrl && !options.skipSystemProxy) this.exitIp().catch(() => {});
      return this.state;
    } catch (error) {
      await this.stop(); this.update({ error: error.message }); throw error;
    }
  }); }
  async requestApi(endpoint) {
    const response = await fetch(`http://127.0.0.1:${this.ports.api}/${endpoint}`, { headers: { Authorization: `Bearer ${this.ports.secret}` }, signal: AbortSignal.timeout(3000) });
    if (!response.ok) throw new Error('Engine API unavailable');
    return response.json();
  }
  async stats() {
    const value = await this.requestApi('connections');
    const previous = this.totals || { downloadTotal: 0, uploadTotal: 0 };
    this.totals = value;
    this.update({ download: Math.max(0, value.downloadTotal - previous.downloadTotal), upload: Math.max(0, value.uploadTotal - previous.uploadTotal) });
  }
  async exitIp() {
    const dispatcher = this.dispatcher;
    const response = await fetch('https://api.ip.sb/geoip', { dispatcher, signal: AbortSignal.timeout(10000) });
    const value = await response.json();
    if (this.dispatcher === dispatcher) this.update({ ip: value.ip, country: value.country, countryCode: value.country_code });
  }
  disconnect() { this.cancelled = true; this.abort?.abort(); return this.serial(() => this.stop()); }
  async stop() {
    clearInterval(this.timer); this.timer = null;
    this.update({ status: 'disconnecting' });
    let recoveryError;
    try { await this.system.recover(); } catch (e) { recoveryError = e; }
    for (const child of this.children.splice(0).reverse()) {
      child.kill();
      await Promise.race([new Promise(r => child.once('exit', r)), new Promise(r => setTimeout(r, 1500))]);
      if (child.exitCode === null) child.kill('SIGKILL');
    }
    await this.dispatcher?.destroy().catch(() => {}); this.dispatcher = null;
    await this.relay?.close(); this.relay = null;
    if (this.runDir) await fs.rm(this.runDir, { recursive: true, force: true });
    this.runDir = null; this.totals = null;
    this.update({ status: 'disconnected', download: 0, upload: 0, ip: null, profileId: null });
    if (recoveryError) throw recoveryError;
  }
}
module.exports = { Engine, freePort, waitPort };
