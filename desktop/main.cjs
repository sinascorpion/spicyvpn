const { app, BrowserWindow, ipcMain, dialog, clipboard, safeStorage, shell, Notification } = require('electron');
const path = require('node:path');
const fs = require('node:fs/promises');
const crypto = require('node:crypto');
const QRCode = require('qrcode');
const { fetch } = require('undici');
const { Store } = require('./lib/storage.cjs');
const { EngineHost } = require('./lib/engine-host.cjs');
const { parseInput } = require('./lib/profiles.cjs');

let window, engine, store, state, allowQuit = false;
if (!app.isPackaged && process.env.SPICY_TEST_DIRECTORY) app.setPath('userData', process.env.SPICY_TEST_DIRECTORY);
if (!app.isPackaged && process.env.SPICY_SMOKE === '1') app.disableHardwareAcceleration();
if (!app.requestSingleInstanceLock()) app.exit(0);
app.on('second-instance', () => { if (window) { if (window.isMinimized()) window.restore(); window.show(); window.focus(); } });
const API = 'https://admin-panel-ten-iota-82.vercel.app/api/healthy-proxies';
const engines = app.isPackaged ? path.join(process.resourcesPath, 'engines') : path.join(__dirname, 'engines');
let manual = [], autoCandidates = [], logs = [], autoCancelled = false;
const testers = new Set();
let quitting = false;
function send(channel, value) { if (window && !window.isDestroyed()) window.webContents.send(channel, value); }
async function persist() { await store.write(state); send('data', { ...state, manual }); }
async function getText(url) {
  const address = new URL(url);
  if (!['https:', 'http:'].includes(address.protocol) || address.username || address.password) throw new Error('Only HTTP(S) subscription URLs are supported.');
  const response = await fetch(address, { signal: AbortSignal.timeout(20000), headers: { 'User-Agent': 'SpicyVPN-Desktop' } });
  if (!response.ok) throw new Error(`Download failed (${response.status})`);
  const reader = response.body.getReader(); let length = 0; const parts = [];
  try { while (true) { const { done, value } = await reader.read(); if (done) break; length += value.length; if (length > 4 * 1024 * 1024) throw new Error('Subscription is too large.'); parts.push(Buffer.from(value)); } }
  finally { await reader.cancel().catch(() => {}); }
  return Buffer.concat(parts).toString('utf8');
}
async function refreshSubscription(sub) {
  const imported = parseInput(await getText(sub.url));
  if (imported.some(p => p.subscriptionUrl)) throw new Error('Nested subscription URLs are not accepted.');
  const previous = new Map(state.profiles.filter(p => p.subscriptionId === sub.id).map(p => [p.original, p]));
  const next = imported.map(p => ({ ...p, id: previous.get(p.original)?.id || p.id, favorite: previous.get(p.original)?.favorite || false, folderId: previous.get(p.original)?.folderId, subscriptionId: sub.id }));
  state.profiles = [...state.profiles.filter(p => p.subscriptionId !== sub.id), ...next];
  sub.updatedAt = new Date().toISOString();
  await persist();
}
async function discover() {
  const payload = JSON.parse(await getText(API));
  autoCandidates = (payload.auto_connect || []).flatMap(text => { try { return parseInput(text); } catch { return []; } });
  const discovered = Array.isArray(payload.manual) ? payload.manual : Object.entries(payload.manual || {}).flatMap(([countryCode, rows]) => (Array.isArray(rows) ? rows : []).map(row => typeof row === 'string' ? { url: row, country_code: countryCode } : { ...row, country_code: countryCode }));
  const rows = [...(payload.pinned_servers || []).map(p => ({ ...p, pinned: true })), ...discovered];
  const seen = new Set();
  manual = rows.flatMap((row, index) => {
    const original = typeof row === 'string' ? row : row.url || row.link || row.config;
    if (!original || seen.has(original)) return [];
    seen.add(original);
    try {
      const p = parseInput(original)[0];
      const metadata = payload.exit_metadata?.[original] || {};
      return [{ ...p, id: crypto.createHash('sha256').update(original).digest('hex'), name: row.pinned ? `SpicyVPN${index + 1}` : `Server${index + 1}`, countryCode: metadata.country || row.country_code, country: row.country, pinned: Boolean(row.pinned) }];
    } catch { return []; }
  }).slice(0, 50);
  send('data', { ...state, manual }); return manual;
}
function register(name, handler) {
  ipcMain.handle(name, async (event, ...args) => {
    if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Untrusted IPC sender');
    try { return { ok: true, value: await handler(...args) }; }
    catch (error) { return { ok: false, error: error.message }; }
  });
}
app.whenReady().then(async () => {
  const directory = app.getPath('userData');
  store = new Store(directory, safeStorage); state = await store.read();
  engine = new EngineHost(path.join(directory, 'runtime'), engines);
  await engine.system.recover();
  engine.on('state', value => { send('connection', value); if (value.status === 'connected' && Notification.isSupported()) new Notification({ title: 'SpicyVPN', body: 'Connected', silent: true }).show(); });
  engine.on('log', line => { logs.push({ time: Date.now(), line }); logs = logs.slice(-300); send('log', logs.at(-1)); });
  window = new BrowserWindow({ width: 1100, height: 820, minWidth: 760, minHeight: 640, show: process.env.SPICY_SMOKE !== '1', title: 'SpicyVPN', backgroundColor: '#111111', icon: path.join(__dirname, 'assets/logo.png'), webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true } });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.session.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
  register('initial', async () => ({ ...state, manual, connection: engine.state, logs, versions: JSON.parse(await fs.readFile(path.join(engines, 'versions.json'), 'utf8')), platform: process.platform }));
  register('settings', async value => {
    if (!value || typeof value !== 'object') throw new Error('Invalid settings');
    for (const key of ['theme', 'mode', 'countries', 'dns', 'processes', 'processMode', 'systemProxy']) if (Object.hasOwn(value, key)) state.settings[key] = value[key];
    await persist();
  });
  register('import', async (text, name) => {
    if (typeof text !== 'string') throw new Error('Invalid configuration');
    const profiles = parseInput(text, name);
    if (profiles[0]?.subscriptionUrl) {
      const url = profiles[0].subscriptionUrl;
      const sub = state.subscriptions.find(s => s.url === url) || { id: crypto.randomUUID(), url, name: name || new URL(url).hostname, expanded: true };
      const fresh = !state.subscriptions.some(s => s.id === sub.id);
      if (fresh) state.subscriptions.push(sub);
      try { await refreshSubscription(sub); } catch (e) { if (fresh) state.subscriptions = state.subscriptions.filter(s => s.id !== sub.id); throw e; }
    } else { state.profiles.push(...profiles); await persist(); }
  });
  register('clipboard', () => clipboard.readText());
  register('file', async () => {
    const result = await dialog.showOpenDialog(window, { properties: ['openFile'], filters: [{ name: 'Configurations', extensions: ['json', 'yaml', 'yml', 'conf', 'txt'] }] });
    return result.canceled ? null : { name: path.basename(result.filePaths[0]), text: await fs.readFile(result.filePaths[0], 'utf8') };
  });
  register('edit', async (id, name, text) => {
    const index = state.profiles.findIndex(p => p.id === id);
    if (index < 0) throw new Error('Profile not found');
    const next = parseInput(text, name);
    if (next.length !== 1 || next[0].subscriptionUrl) throw new Error('Edit one profile at a time.');
    state.profiles[index] = { ...state.profiles[index], ...next[0], id, name: name || next[0].name }; await persist();
  });
  register('delete', async (kind, id) => {
    if (kind === 'profile') state.profiles = state.profiles.filter(p => p.id !== id);
    else if (kind === 'subscription') { state.subscriptions = state.subscriptions.filter(s => s.id !== id); state.profiles = state.profiles.filter(p => p.subscriptionId !== id); }
    else if (kind === 'folder') { state.folders = state.folders.filter(f => f.id !== id); state.profiles.forEach(p => { if (p.folderId === id) delete p.folderId; }); }
    else throw new Error('Invalid deletion target');
    await persist();
  });
  register('folder', async name => { if (typeof name !== 'string' || !name.trim()) throw new Error('Folder name is required'); state.folders.push({ id: crypto.randomUUID(), name: name.slice(0, 100), expanded: true }); await persist(); });
  register('assign', async (id, folderId) => { const p = state.profiles.find(p => p.id === id); if (!p || (folderId && !state.folders.some(f => f.id === folderId))) throw new Error('Folder or profile not found'); p.folderId = folderId || undefined; await persist(); });
  register('favorite', async id => { const p = state.profiles.find(p => p.id === id) || manual.find(p => p.id === id); if (!p) return; p.favorite = !p.favorite; if (p.favorite && !state.profiles.some(q => q.id === p.id)) state.profiles.push({ ...p, manualFavorite: true }); else if (!p.favorite && p.manualFavorite) state.profiles = state.profiles.filter(q => q.id !== id); await persist(); });
  register('order', async ids => { if (!Array.isArray(ids) || ids.length !== state.profiles.length || new Set(ids).size !== ids.length || ids.some(id => !state.profiles.some(p => p.id === id))) throw new Error('Invalid order'); const map = new Map(state.profiles.map(p => [p.id, p])); state.profiles = ids.map(id => map.get(id)); await persist(); });
  register('expand', async (id, expanded) => { const group = [...state.folders, ...state.subscriptions].find(g => g.id === id); if (group) { group.expanded = Boolean(expanded); await persist(); } });
  register('refresh', async id => { const sub = state.subscriptions.find(s => s.id === id); if (!sub) throw new Error('Subscription not found'); await refreshSubscription(sub); });
  register('discover', discover);
  register('test', async tab => {
    const profiles = tab === 'manual' ? manual : state.profiles;
    const pending = [...profiles];
    const worker = async () => {
      while (pending.length && !quitting) {
        const p = pending.shift();
        const tester = new EngineHost(path.join(directory, 'tests', crypto.randomUUID()), engines);
        testers.add(tester);
        try { const result = await tester.connect(p, { ...state.settings, mode: 'proxy', systemProxy: false, countries: [], processes: [] }, { skipSystemProxy: true }); p.latency = result.latency; }
        catch { p.latency = -1; }
        finally { await tester.disconnect(); tester.close(); testers.delete(tester); }
        send('data', { ...state, manual });
      }
    };
    await Promise.all([worker(), worker()]);
    await persist();
  });
  register('connect', async (id, tab) => {
    if (tab === 'auto') {
      autoCancelled = false;
      if (!manual.length) await discover();
      const candidates = [...autoCandidates, ...manual].slice(0, 12);
      candidates.push(...parseInput('aether://?protocol=gool&scan=balanced&ip=v4#Aether'), ...parseInput('aether://?protocol=wg&scan=balanced&ip=v4#WARP'));
      for (const p of candidates) { if (autoCancelled || quitting) break; try { return await engine.connect(p, state.settings); } catch {} }
      throw new Error('No tested server connected on this network.');
    }
    const p = (tab === 'manual' ? [...state.profiles.filter(p => p.manualFavorite), ...manual] : state.profiles).find(p => p.id === id);
    if (!p) throw new Error('Select a profile first.');
    return engine.connect(p, state.settings);
  });
  register('disconnect', () => { autoCancelled = true; return engine.disconnect(); });
  register('share', async (kind, id) => {
    const profiles = kind === 'folder' ? state.profiles.filter(p => p.folderId === id) : state.profiles.filter(p => p.id === id);
    if (!profiles.length) throw new Error('Nothing to share');
    const text = profiles.map(p => p.original).join('\n');
    return { text, qr: text.length <= 1800 ? await QRCode.toDataURL(text, { margin: 2, width: 360 }) : null };
  });
  register('copy', text => { if (typeof text === 'string' && text.length < 4 * 1024 * 1024) clipboard.writeText(text); });
  register('logs', () => logs);
  register('store', () => shell.openExternal('https://play.google.com/store/apps/details?id=com.vpn.spicy'));
  await window.loadFile(path.join(__dirname, 'ui/index.html'));
  window.on('closed', () => { window = null; app.quit(); });
}).catch(error => { dialog.showErrorBox('SpicyVPN', error.message); app.exit(1); });
app.on('before-quit', event => { if (!allowQuit && engine) { event.preventDefault(); quitting = true; Promise.allSettled([engine.disconnect(), ...[...testers].map(t => t.disconnect())]).finally(() => { engine.close(); allowQuit = true; app.quit(); }); } });
app.on('window-all-closed', () => app.quit());
