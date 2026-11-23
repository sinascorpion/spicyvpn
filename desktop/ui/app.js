const $ = selector => document.querySelector(selector);
let data, connection = {}, tab = 'auto', selected = { manual: null, custom: null }, editing = null, testing = false, sortLatency = false;
const api = window.spicy;
function el(tag, text, className) { const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (className) node.className = className; return node; }
function iconButton(icon, title, action) { const button = el('button', undefined, 'icon'); button.title = title; button.setAttribute('aria-label', title); const i = el('i'); i.dataset.lucide = icon; button.append(i); button.onclick = event => { event.stopPropagation(); Promise.resolve(action()).catch(error); }; return button; }
function icons() { lucide.createIcons(); }
function error(e) { $('#toast').textContent = e.message || String(e); $('#toast').hidden = false; clearTimeout(error.timer); error.timer = setTimeout(() => $('#toast').hidden = true, 7000); }
function run(handler) { return (...args) => Promise.resolve().then(() => handler(...args)).catch(error); }
function flag(code) { return /^[A-Za-z]{2}$/.test(code || '') ? String.fromCodePoint(...code.toUpperCase().split('').map(v => 127397 + v.charCodeAt(0))) : ''; }
function rate(n) { if (n < 1024) return `${Math.round(n || 0)} B/s`; if (n < 1048576) return `${(n / 1024).toFixed(1)} KB/s`; return `${(n / 1048576).toFixed(1)} MB/s`; }
function paintConnection(value) {
  connection = value;
  const connected = value.status === 'connected';
  document.body.classList.toggle('connected', connected);
  $('#connect-label').textContent = connected ? 'Disconnect' : value.status === 'connecting' ? 'Connecting...' : 'Connect';
  $('#status').textContent = value.error || value.status[0].toUpperCase() + value.status.slice(1);
  $('#engine-status').textContent = $('#status').textContent;
  $('#telemetry').hidden = !connected;
  $('#download').textContent = rate(value.download); $('#upload').textContent = rate(value.upload);
  $('#exit-ip').textContent = value.ip || 'Fetching IP...';
  $('#exit-country').textContent = [flag(value.countryCode), value.country].filter(Boolean).join(' ');
  $('#ping').textContent = value.latency ? `${value.latency} ms` : '';
  $('#proxy-port').textContent = connected && value.proxyPort ? `127.0.0.1:${value.proxyPort}` : '';
  $('#auto-connect').disabled = value.status === 'disconnecting';
}
async function connect() {
  if (connection.status === 'connected' || connection.status === 'connecting') return api.call('disconnect');
  return api.call('connect', selected[tab], tab);
}
function render() {
  if (!data) return;
  document.body.classList.toggle('light', data.settings.theme === 'light');
  $('#system-proxy').checked = data.settings.systemProxy !== false;
  $('#system-proxy').parentElement.hidden = data.settings.mode === 'tun';
  document.querySelectorAll('[data-mode]').forEach(b => b.classList.toggle('active', b.dataset.mode === data.settings.mode));
  $('#auto').hidden = tab !== 'auto'; $('#list-view').hidden = tab === 'auto';
  $('#add').hidden = tab !== 'custom'; $('#new-folder').hidden = tab !== 'custom';
  $('#get-config').style.display = tab === 'manual' ? 'inline-flex' : 'none';
  if (tab === 'auto') return;
  let profiles = tab === 'manual' ? [...data.profiles.filter(p => p.manualFavorite), ...data.manual.filter(p => !data.profiles.some(q => q.manualFavorite && q.id === p.id))] : data.profiles.filter(p => !p.manualFavorite);
  const query = $('#search').value.toLowerCase();
  profiles = profiles.filter(p => `${p.name} ${p.protocol} ${p.country || ''}`.toLowerCase().includes(query));
  if (sortLatency) profiles.sort((a, b) => (a.latency > 0 ? a.latency : Infinity) - (b.latency > 0 ? b.latency : Infinity));
  const root = $('#profiles'); root.replaceChildren();
  function row(profile) {
    const node = el('article', undefined, `profile-row${selected[tab] === profile.id ? ' selected' : ''}`); node.dataset.id = profile.id; node.tabIndex = 0;
    const body = el('div'); body.append(el('h3', (tab === 'manual' ? flag(profile.countryCode) + ' ' : '') + profile.name));
    const meta = el('div', undefined, 'meta'); meta.append(el('span', profile.protocol, 'protocol'));
    const latency = el('span', profile.latency === null || profile.latency === undefined ? 'Not tested' : profile.latency < 0 ? 'Timeout' : `${profile.latency} ms`);
    if (profile.latency > 0) latency.className = 'healthy'; meta.append(latency); body.append(meta); node.append(body);
    const actions = el('div', undefined, 'row-actions');
    const star = iconButton('star', 'Favorite', () => api.call('favorite', profile.id)); if (profile.favorite) star.classList.add('favorite'); actions.append(star);
    if (tab === 'custom') {
      actions.append(iconButton('folder-input', 'Move to folder', () => folderPicker(profile)));
      actions.append(iconButton('pencil', 'Edit configuration', () => editor(profile)));
      actions.append(iconButton('share-2', 'Share configuration', () => share('profile', profile.id)));
      actions.append(iconButton('trash-2', 'Delete configuration', async () => { if (confirm(`Delete ${profile.name}?`)) await api.call('delete', 'profile', profile.id); }));
    }
    node.append(actions);
    const choose = () => { selected[tab] = profile.id; render(); };
    node.onclick = choose; node.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(); } };
    return node;
  }
  if (tab === 'manual' || sortLatency || query) profiles.forEach(p => root.append(row(p)));
  else {
    profiles.filter(p => !p.folderId && !p.subscriptionId).forEach(p => root.append(row(p)));
    for (const group of [...data.folders.map(g => ({ ...g, kind: 'folder' })), ...data.subscriptions.map(g => ({ ...g, kind: 'subscription' }))]) {
      const groupRows = profiles.filter(p => group.kind === 'folder' ? p.folderId === group.id : !p.folderId && p.subscriptionId === group.id);
      const section = el('section', undefined, 'profile-group');
      const header = el('div', undefined, 'group-header'); const toggle = el('button', `${group.expanded !== false ? '▾' : '▸'} ${group.name} (${groupRows.length})`);
      toggle.onclick = run(() => api.call('expand', group.id, group.expanded === false)); header.append(toggle);
      if (group.kind === 'subscription') header.append(iconButton('refresh-cw', 'Update subscription', () => api.call('refresh', group.id)));
      if (group.kind === 'folder') header.append(iconButton('share-2', 'Share folder', () => share('folder', group.id)));
      header.append(iconButton('trash-2', 'Delete group', async () => { if (confirm(`Delete ${group.name}?`)) await api.call('delete', group.kind, group.id); }));
      section.append(header);
      if (group.expanded !== false) { const items = el('div', undefined, 'group-items'); groupRows.forEach(p => items.append(row(p))); section.append(items); }
      root.append(section);
    }
  }
  $('#empty').hidden = profiles.length > 0 || (tab === 'custom' && data.folders.length);
  icons();
  if (tab === 'custom' && !sortLatency && !query) new Sortable(root, { animation: 150, draggable: '.profile-row', delay: 180, onEnd: run(async () => {
    const ids = [...root.querySelectorAll('.profile-row')].map(n => n.dataset.id);
    const rest = data.profiles.filter(p => !ids.includes(p.id)).map(p => p.id); await api.call('order', [...ids, ...rest]);
  }) });
}
function editor(profile = null) {
  editing = profile?.id || null; $('#editor-title').textContent = profile ? 'Edit Configuration' : 'Add Configuration';
  $('#profile-name').value = profile?.name || ''; $('#profile-text').value = profile?.original || ''; $('#editor').showModal();
}
function panel(title, contents) { $('#panel-title').textContent = title; $('#panel-content').replaceChildren(contents); if (!$('#panel').open) $('#panel').showModal(); icons(); }
async function share(kind, id) {
  const value = await api.call('share', kind, id); const content = el('div');
  if (value.qr) { const img = el('img'); img.src = value.qr; img.alt = 'Configuration QR code'; img.width = 260; img.height = 260; content.append(img); }
  const area = el('textarea'); area.value = value.text; area.readOnly = true; area.rows = 6; content.append(area);
  const copy = el('button', 'Copy'); copy.onclick = run(() => api.call('copy', value.text)); content.append(copy); panel('Share', content);
}
function folderPicker(profile) {
  const content = el('div');
  for (const folder of [{ id: '', name: 'No folder' }, ...data.folders]) { const b = el('button', folder.name); b.onclick = run(async () => { await api.call('assign', profile.id, folder.id); $('#panel').close(); }); content.append(b); }
  panel('Move to folder', content);
}
async function openPanel(view) {
  const content = el('div');
  if (view === 'about') for (const [name, version] of Object.entries(data.versions)) { const row = el('div', undefined, 'setting-row'); row.append(el('strong', name), el('span', version)); content.append(row); }
  if (view === 'logs') { content.className = 'log-lines'; content.textContent = (await api.call('logs')).map(l => `${new Date(l.time).toLocaleTimeString()} ${l.line}`).join('\n'); }
  if (view === 'subscriptions') for (const sub of data.subscriptions) { const row = el('div', undefined, 'setting-row'); row.append(el('strong', sub.name), iconButton('refresh-cw', 'Update', () => api.call('refresh', sub.id)), iconButton('trash-2', 'Delete', () => api.call('delete', 'subscription', sub.id))); content.append(row); }
  if (view === 'routing') {
    content.className = 'country-grid'; const names = new Intl.DisplayNames(['en'], { type: 'region' });
    const codes = 'ir ru cn af az de tr us gb fr ca ae pk iq am ge in nl se ch au br jp kr'.split(' ');
    codes.sort((a, b) => a === 'ir' ? -1 : b === 'ir' ? 1 : names.of(a.toUpperCase()).localeCompare(names.of(b.toUpperCase())));
    for (const code of codes) { const label = el('label', `${flag(code)} ${names.of(code.toUpperCase())}`); const input = el('input'); input.type = 'checkbox'; input.checked = (data.settings.countries || []).includes(code); input.onchange = run(() => { const countries = new Set(data.settings.countries || []); input.checked ? countries.add(code) : countries.delete(code); return api.call('settings', { countries: [...countries] }); }); label.prepend(input); content.append(label); }
  }
  if (['dns', 'processes'].includes(view)) {
    const area = el('textarea'); area.rows = 7; area.value = (view === 'dns' ? data.settings.dns || [] : data.settings.processes || []).join('\n');
    const label = el('label', view === 'dns' ? 'DNS resolvers' : 'Process names'); label.append(area); content.append(label);
    if (view === 'processes') { const select = el('select'); for (const [value, name] of [['bypass', 'Bypass selected processes'], ['include', 'Only selected processes']]) { const option = el('option', name); option.value = value; select.append(option); } select.value = data.settings.processMode || 'bypass'; select.onchange = run(() => api.call('settings', { processMode: select.value })); content.append(select); }
    const save = el('button', 'Save', 'primary'); save.onclick = run(async () => { await api.call('settings', { [view === 'dns' ? 'dns' : 'processes']: area.value.split('\n').map(s => s.trim()).filter(Boolean) }); $('#panel').close(); }); content.append(save);
  }
  panel(({ about: 'About SpicyVPN', logs: 'Connection Logs', subscriptions: 'Subscriptions', routing: 'Country Routing', dns: 'DNS Settings', processes: 'Split Tunneling' })[view], content);
}
document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => { tab = b.dataset.tab; document.querySelectorAll('[data-tab]').forEach(n => { n.classList.toggle('active', n === b); n.setAttribute('aria-selected', n === b ? 'true' : 'false'); }); render(); });
document.querySelectorAll('[data-mode]').forEach(b => b.onclick = run(() => api.call('settings', { mode: b.dataset.mode })));
document.querySelectorAll('[data-view]').forEach(b => b.onclick = run(() => openPanel(b.dataset.view)));
document.querySelectorAll('[data-close]').forEach(b => b.onclick = () => b.closest('dialog').close());
$('#theme').onclick = run(() => api.call('settings', { theme: data.settings.theme === 'light' ? 'dark' : 'light' }));
$('#system-proxy').onchange = run(() => api.call('settings', { systemProxy: $('#system-proxy').checked }));
$('#auto-connect').onclick = run(connect); $('#list-connect').onclick = run(connect);
$('#add').onclick = () => editor();
$('#paste').onclick = run(async () => $('#profile-text').value = await api.call('clipboard'));
$('#import-file').onclick = run(async () => { const file = await api.call('file'); if (file) { $('#profile-name').value = file.name; $('#profile-text').value = file.text; } });
$('#editor-form').onsubmit = run(async event => { event.preventDefault(); if (editing) await api.call('edit', editing, $('#profile-name').value, $('#profile-text').value); else await api.call('import', $('#profile-text').value, $('#profile-name').value); $('#editor').close(); });
$('#new-folder').onclick = () => { const content = el('form'); const input = el('input'); input.placeholder = 'Folder name'; input.required = true; const submit = el('button', 'Create', 'primary'); content.append(input, submit); content.onsubmit = run(async e => { e.preventDefault(); await api.call('folder', input.value); $('#panel').close(); }); panel('New Folder', content); };
$('#get-config').onclick = run(() => api.call('discover'));
$('#search').oninput = render;
$('#sort').onclick = () => { sortLatency = !sortLatency; render(); };
$('#test').onclick = run(async () => { if (testing) return; testing = true; sortLatency = true; $('#test').disabled = true; try { await api.call('test', tab); } finally { testing = false; $('#test').disabled = false; } });
$('#google-play').onclick = run(() => api.call('store'));
$('#menu').onclick = () => { $('#sidebar').hidden = !$('#sidebar').hidden; $('.shell').style.gridTemplateColumns = $('#sidebar').hidden ? 'minmax(0,1fr)' : ''; };
$('#advanced').onclick = run(() => { const content = el('div'); for (const [name, value] of [['Xray', data.versions.Xray], ['Mihomo', data.versions.Mihomo], ['Aether', data.versions.Aether]]) { const row = el('div', undefined, 'setting-row'); row.append(el('strong', name), el('span', value)); content.append(row); } panel('Engine Settings', content); });
$('#more').onclick = () => { const content = el('div'); const b = el('button', 'Import from clipboard'); b.onclick = run(async () => { $('#panel').close(); editor(); $('#profile-text').value = await api.call('clipboard'); }); content.append(b); panel('Configurations', content); };
api.on('data', value => { data = { ...data, ...value }; render(); });
api.on('connection', paintConnection);
api.call('initial').then(value => { data = value; render(); paintConnection(value.connection); icons(); }).catch(error);
