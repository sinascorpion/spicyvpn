const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { Store } = require('../lib/storage.cjs');

test('concurrent saves remain ordered and do not race temporary files', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'spicy-store-test-'));
  const crypto = { isEncryptionAvailable: () => true, encryptString: text => Buffer.from(text), decryptString: bytes => bytes.toString() };
  try {
    const store = new Store(directory, crypto);
    await Promise.all(Array.from({ length: 15 }, (_, i) => store.write({ sequence: i })));
    assert.equal((await store.read()).sequence, 14);
    await assert.rejects(new Store(directory, { ...crypto, isEncryptionAvailable: () => false }).write({ password: 'fixture' }), /keychain/);
  } finally { await fs.rm(directory, { recursive: true, force: true }); }
});
