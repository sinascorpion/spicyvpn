const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const http = require('node:http');
const net = require('node:net');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { Server } = require('ssh2');
const { Engine } = require('../../lib/engine.cjs');
const { parseInput } = require('../../lib/profiles.cjs');
const tls = require('node:tls');
const { execFileSync } = require('node:child_process');

for (const encrypted of [false, true]) test(`${encrypted ? 'SSH+TLS' : 'SSH'} carries real HTTP via shared router`, { timeout: 40000 }, async t => {
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048, privateKeyEncoding: { type: 'pkcs1', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
  const sockets = new Set();
  const origin = http.createServer((_req, response) => response.end('SPICY-SSH-DATA-PLANE'));
  await new Promise(r => origin.listen(0, '127.0.0.1', r));
  let forwards = 0;
  const server = new Server({ hostKeys: [privateKey] }, client => {
    sockets.add(client); client.on('close', () => sockets.delete(client)); client.on('error', () => {});
    client.on('authentication', ctx => ctx.method === 'password' && ctx.username === 'fixture' && ctx.password === 'fixture' ? ctx.accept() : ctx.reject(['password']));
    client.on('ready', () => client.on('tcpip', (accept, reject, info) => {
      if (info.destIP !== '127.0.0.1' || info.destPort !== origin.address().port) return reject();
      forwards++; const stream = accept(); const socket = net.connect(origin.address().port, '127.0.0.1', () => { stream.pipe(socket); socket.pipe(stream); }); sockets.add(socket); socket.on('close', () => sockets.delete(socket)); stream.on('close', () => socket.destroy());
    }));
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'spicy-ssh-test-'));
  let tlsServer, certificate;
  if (encrypted) {
    const openssl = process.platform === 'win32' ? 'C:/Program Files/Git/usr/bin/openssl.exe' : 'openssl';
    execFileSync(openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', path.join(directory, 'tls-key.pem'), '-out', path.join(directory, 'tls-cert.pem'), '-subj', '/CN=localhost', '-days', '1', '-addext', 'subjectAltName=DNS:localhost'], { stdio: 'ignore', windowsHide: true });
    certificate = await fs.readFile(path.join(directory, 'tls-cert.pem'), 'utf8');
    tlsServer = tls.createServer({ key: await fs.readFile(path.join(directory, 'tls-key.pem')), cert: certificate }, socket => {
      sockets.add(socket); socket.once('close', () => sockets.delete(socket)); socket.on('error', () => {});
      const forward = net.connect(server.address().port, '127.0.0.1', () => { socket.pipe(forward); forward.pipe(socket); });
      sockets.add(forward); forward.once('close', () => sockets.delete(forward)); forward.on('error', () => socket.destroy()); socket.once('close', () => forward.destroy());
    });
    tlsServer.on('tlsClientError', () => {});
    await new Promise(r => tlsServer.listen(0, '127.0.0.1', r));
  }
  const engine = new Engine(directory, path.resolve(__dirname, '../../engines'));
  engine.on('log', line => t.diagnostic(line));
  t.after(async () => { await engine.disconnect(); for (const socket of sockets) socket.end?.(); tlsServer?.close(); server.close(); origin.closeAllConnections(); origin.close(); await fs.rm(directory, { recursive: true, force: true }); });
  const [profile] = parseInput(`${encrypted ? 'ssh+tls' : 'ssh'}://fixture:fixture@127.0.0.1:${encrypted ? tlsServer.address().port : server.address().port}?sni=localhost#SSH`);
  if (encrypted) profile.transport.ca = certificate;
  const state = await engine.connect(profile, { mode: 'proxy', systemProxy: false }, { skipSystemProxy: true, probeUrl: `http://127.0.0.1:${origin.address().port}/`, probeStatus: 200 });
  assert.equal(state.status, 'connected'); assert(forwards > 0);
});
