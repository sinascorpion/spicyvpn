const net = require('node:net');
const tls = require('node:tls');
const { SocksClient } = require('socks');

async function dial(host, port, upstream) {
  if (!upstream) return new Promise((resolve, reject) => { const socket = net.connect(port, host); socket.once('connect', () => resolve(socket)); socket.once('error', reject); socket.setTimeout(10000, () => socket.destroy(new Error('SSH transport timed out'))); });
  const proxy = new URL(upstream);
  if (proxy.protocol === 'socks5:') {
    return (await SocksClient.createConnection({ proxy: { host: proxy.hostname, port: Number(proxy.port || 1080), type: 5, userId: decodeURIComponent(proxy.username), password: decodeURIComponent(proxy.password) }, command: 'connect', destination: { host, port }, timeout: 10000 })).socket;
  }
  if (!['http:', 'https:'].includes(proxy.protocol)) throw new Error('Unsupported SSH upstream proxy');
  return new Promise((resolve, reject) => {
    const socket = proxy.protocol === 'https:' ? tls.connect({ host: proxy.hostname, port: Number(proxy.port || 443), servername: proxy.hostname }) : net.connect(Number(proxy.port || 8080), proxy.hostname);
    const event = proxy.protocol === 'https:' ? 'secureConnect' : 'connect';
    socket.setTimeout(10000, () => socket.destroy(new Error('Upstream proxy timed out')));
    socket.once('error', reject);
    socket.once(event, () => {
      const authority = net.isIPv6(host) ? `[${host}]:${port}` : `${host}:${port}`;
      let headers = `CONNECT ${authority} HTTP/1.1\r\nHost: ${authority}\r\n`;
      if (proxy.username) headers += 'Proxy-Authorization: Basic ' + Buffer.from(decodeURIComponent(proxy.username) + ':' + decodeURIComponent(proxy.password)).toString('base64') + '\r\n';
      socket.write(headers + '\r\n');
    });
    let buffer = Buffer.alloc(0);
    const receive = chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length > 16384) return socket.destroy(new Error('Proxy response is too large'));
      const end = buffer.indexOf('\r\n\r\n'); if (end < 0) return;
      socket.off('data', receive);
      if (!/^HTTP\/1\.[01] 200\b/.test(buffer.toString('latin1', 0, end))) return socket.destroy(new Error('Upstream proxy rejected CONNECT'));
      if (end + 4 < buffer.length) socket.unshift(buffer.subarray(end + 4));
      socket.setTimeout(0); resolve(socket);
    };
    socket.on('data', receive);
  });
}
async function createTlsRelay(profile) {
  const sockets = new Set();
  const track = socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); socket.on('error', () => {}); return socket; };
  const server = net.createServer(async incoming => {
    track(incoming);
    try {
      let outgoing = track(await dial(profile.data.server, profile.data.port, profile.transport?.upstream));
      if (profile.transport?.tls) {
        outgoing = track(tls.connect({ socket: outgoing, servername: profile.transport.servername || profile.data.server, rejectUnauthorized: true, ca: profile.transport.ca }));
        await new Promise((resolve, reject) => { outgoing.once('secureConnect', resolve); outgoing.once('error', reject); });
      }
      incoming.pipe(outgoing); outgoing.pipe(incoming);
      incoming.once('close', () => outgoing.destroy()); outgoing.once('close', () => incoming.destroy());
    } catch { incoming.destroy(); }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return { port: server.address().port, close: async () => { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); } };
}
module.exports = { createTlsRelay };
