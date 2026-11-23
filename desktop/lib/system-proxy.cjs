const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const fs = require('node:fs/promises');
const path = require('node:path');
const execute = promisify(execFile);

function ps(script) {
  return execute('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], { windowsHide: true, timeout: 15000 });
}
class SystemProxy {
  constructor(directory) { this.journal = path.join(directory, 'proxy-recovery.json'); }
  async recover() {
    try { await this.restore(JSON.parse(await fs.readFile(this.journal, 'utf8'))); await fs.unlink(this.journal); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  async enable(port) {
    if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid proxy port');
    await this.recover();
    const snapshot = await this.snapshot();
    await fs.mkdir(path.dirname(this.journal), { recursive: true });
    await fs.writeFile(this.journal, JSON.stringify(snapshot), { mode: 0o600 });
    try { await this.apply(port); } catch (error) { await this.recover(); throw error; }
  }
  async snapshot() {
    if (process.platform === 'win32') {
      const { stdout } = await ps('$p=Get-ItemProperty "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"; @{ProxyEnable=$p.ProxyEnable;ProxyServer=$p.ProxyServer;ProxyOverride=$p.ProxyOverride;AutoConfigURL=$p.AutoConfigURL;AutoDetect=$p.AutoDetect}|ConvertTo-Json -Compress');
      return { platform: process.platform, values: JSON.parse(stdout) };
    }
    if (process.platform === 'linux') {
      const entries = [['org.gnome.system.proxy', 'mode'], ['org.gnome.system.proxy.http', 'host'], ['org.gnome.system.proxy.http', 'port'], ['org.gnome.system.proxy.https', 'host'], ['org.gnome.system.proxy.https', 'port'], ['org.gnome.system.proxy.socks', 'host'], ['org.gnome.system.proxy.socks', 'port']];
      const values = [];
      for (const [schema, key] of entries) values.push({ schema, key, value: (await execute('gsettings', ['get', schema, key])).stdout.trim() });
      return { platform: process.platform, values };
    }
    if (process.platform === 'darwin') {
      const services = (await execute('/usr/sbin/networksetup', ['-listallnetworkservices'])).stdout.split('\n').slice(1).map(s => s.trim()).filter(s => s && !s.startsWith('*'));
      const values = [];
      for (const service of services) {
        const proxies = {};
        for (const type of ['web', 'secureweb', 'socksfirewall']) {
          const output = (await execute('/usr/sbin/networksetup', [`-get${type}proxy`, service])).stdout;
          const fields = Object.fromEntries(output.split('\n').filter(line => line.includes(':')).map(line => { const index = line.indexOf(':'); return [line.slice(0, index), line.slice(index + 1).trim()]; }));
          if (fields.AuthenticatedProxyEnabled === '1') throw new Error('An authenticated system proxy is configured. Disable it before connecting.');
          proxies[type] = { enabled: fields.Enabled === 'Yes', server: fields.Server || '', port: Number(fields.Port || 0) };
        }
        const pac = (await execute('/usr/sbin/networksetup', ['-getautoproxyurl', service])).stdout;
        const discovery = (await execute('/usr/sbin/networksetup', ['-getproxyautodiscovery', service])).stdout;
        values.push({ service, proxies, pac: /Enabled: Yes/.test(pac), discovery: /On/i.test(discovery) });
      }
      return { platform: process.platform, values };
    }
    throw new Error('Unsupported operating system');
  }
  async apply(port) {
    if (process.platform === 'win32') {
      await ps(`$p='HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings'; Set-ItemProperty $p ProxyEnable 1; Set-ItemProperty $p ProxyServer 'http=127.0.0.1:${port};https=127.0.0.1:${port};socks=127.0.0.1:${port}'; Set-ItemProperty $p ProxyOverride '<local>;localhost;127.*;[::1]'; Remove-ItemProperty $p AutoConfigURL -ErrorAction SilentlyContinue; Set-ItemProperty $p AutoDetect 0`);
      await this.notifyWindows();
    } else if (process.platform === 'darwin') {
      const snapshot = JSON.parse(await fs.readFile(this.journal, 'utf8'));
      for (const { service } of snapshot.values) {
        for (const type of ['web', 'secureweb', 'socksfirewall']) await execute('/usr/sbin/networksetup', [`-set${type}proxy`, service, '127.0.0.1', String(port)]);
        await execute('/usr/sbin/networksetup', ['-setautoproxystate', service, 'off']);
        await execute('/usr/sbin/networksetup', ['-setproxyautodiscovery', service, 'off']);
      }
    } else {
      for (const protocol of ['http', 'https', 'socks']) {
        await execute('gsettings', ['set', `org.gnome.system.proxy.${protocol}`, 'host', '127.0.0.1']);
        await execute('gsettings', ['set', `org.gnome.system.proxy.${protocol}`, 'port', String(port)]);
      }
      await execute('gsettings', ['set', 'org.gnome.system.proxy', 'mode', 'manual']);
    }
  }
  async restore(snapshot) {
    if (snapshot.platform !== process.platform) throw new Error('Proxy recovery belongs to another platform');
    if (process.platform === 'win32') {
      const payload = Buffer.from(JSON.stringify(snapshot.values), 'utf8').toString('base64');
      await ps(`$p='HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings'; $v=([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${payload}'))|ConvertFrom-Json); foreach($k in @('ProxyEnable','ProxyServer','ProxyOverride','AutoConfigURL','AutoDetect')) { if($null -eq $v.$k) {Remove-ItemProperty $p $k -ErrorAction SilentlyContinue} else {Set-ItemProperty $p $k $v.$k} }`);
      await this.notifyWindows();
    } else if (process.platform === 'darwin') {
      for (const { service, proxies, pac, discovery } of snapshot.values) {
        for (const [type, value] of Object.entries(proxies)) {
          await execute('/usr/sbin/networksetup', [`-set${type}proxy`, service, value.server, String(value.port)]);
          await execute('/usr/sbin/networksetup', [`-set${type}proxystate`, service, value.enabled ? 'on' : 'off']);
        }
        await execute('/usr/sbin/networksetup', ['-setautoproxystate', service, pac ? 'on' : 'off']);
        await execute('/usr/sbin/networksetup', ['-setproxyautodiscovery', service, discovery ? 'on' : 'off']);
      }
    } else {
      for (const { schema, key, value } of snapshot.values) await execute('gsettings', ['set', schema, key, value]);
    }
  }
  async notifyWindows() {
    await ps('Add-Type -TypeDefinition \'using System; using System.Runtime.InteropServices; public static class SpicyWinInet { [DllImport("wininet.dll")] public static extern bool InternetSetOption(IntPtr h,int o,IntPtr b,int l); }\'; [SpicyWinInet]::InternetSetOption([IntPtr]::Zero,39,[IntPtr]::Zero,0)|Out-Null; [SpicyWinInet]::InternetSetOption([IntPtr]::Zero,37,[IntPtr]::Zero,0)|Out-Null');
  }
}
module.exports = { SystemProxy };
