const fs = require('node:fs/promises');
const path = require('node:path');

class Store {
  constructor(directory, crypto) { this.file = path.join(directory, 'profiles.enc'); this.crypto = crypto; this.pending = Promise.resolve(); }
  async read() {
    try { return JSON.parse(this.crypto.decryptString(await fs.readFile(this.file))); }
    catch (e) { if (e.code === 'ENOENT') return { profiles: [], subscriptions: [], folders: [], settings: { theme: 'dark', mode: 'proxy', countries: [], dns: ['https://dns.google/dns-query'] } }; throw new Error('Unable to unlock saved profiles.'); }
  }
  write(value) {
    const snapshot = JSON.stringify(value);
    this.pending = this.pending.catch(() => {}).then(() => this.writeSnapshot(snapshot));
    return this.pending;
  }
  async writeSnapshot(snapshot) {
    if (!this.crypto.isEncryptionAvailable() || this.crypto.getSelectedStorageBackend?.() === 'basic_text') throw new Error('An operating-system keychain is required before saving credentials.');
    await fs.mkdir(path.dirname(this.file), { recursive: true });
    const temporary = this.file + '.tmp';
    await fs.writeFile(temporary, this.crypto.encryptString(snapshot), { mode: 0o600 });
    await fs.rename(temporary, this.file);
  }
}
module.exports = { Store };
