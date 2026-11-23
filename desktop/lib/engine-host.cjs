const { fork } = require('node:child_process');
const { EventEmitter } = require('node:events');
const path = require('node:path');

class EngineHost extends EventEmitter {
  constructor(directory, engines) {
    super(); this.pending = new Map(); this.sequence = 0;
    this.state = { status: 'disconnected', profileId: null, download: 0, upload: 0, ip: null };
    this.child = fork(path.join(__dirname, 'engine-worker.cjs'), [directory, engines], { execPath: process.execPath, env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' }, windowsHide: true, stdio: ['ignore', 'ignore', 'pipe', 'ipc'] });
    this.child.on('message', message => {
      if (message.event) { if (message.event === 'state') this.state = message.value; this.emit(message.event, message.value); return; }
      const handler = this.pending.get(message.id); if (!handler) return; this.pending.delete(message.id);
      message.error ? handler.reject(new Error(message.error)) : handler.resolve(message.value);
    });
    this.child.on('error', error => this.fail(error));
    this.child.on('exit', () => this.fail(new Error('Connection worker stopped.')));
    this.system = { recover: () => this.command('recover') };
  }
  fail(error) { for (const p of this.pending.values()) p.reject(error); this.pending.clear(); }
  command(method, ...args) {
    return new Promise((resolve, reject) => {
      if (!this.child.connected) return reject(new Error('Connection worker is unavailable.'));
      const id = ++this.sequence; this.pending.set(id, { resolve, reject });
      this.child.send({ id, method, args }, error => { if (error) { this.pending.delete(id); reject(error); } });
    });
  }
  connect(...args) { return this.command('connect', ...args); }
  disconnect() { return this.command('disconnect'); }
  close() { if (this.child.connected) this.child.disconnect(); }
}
module.exports = { EngineHost };
