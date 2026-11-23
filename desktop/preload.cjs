const { contextBridge, ipcRenderer } = require('electron');
const commands = new Set(['initial', 'settings', 'import', 'clipboard', 'file', 'edit', 'delete', 'folder', 'assign', 'favorite', 'order', 'expand', 'refresh', 'discover', 'test', 'connect', 'disconnect', 'share', 'copy', 'logs', 'store']);
contextBridge.exposeInMainWorld('spicy', {
  call: async (name, ...args) => { if (!commands.has(name)) throw new Error('Unknown command'); const result = await ipcRenderer.invoke(name, ...args); if (!result.ok) throw new Error(result.error); return result.value; },
  on: (channel, callback) => { if (!['data', 'connection', 'log'].includes(channel)) return; const listener = (_event, data) => callback(data); ipcRenderer.on(channel, listener); return () => ipcRenderer.removeListener(channel, listener); }
});
