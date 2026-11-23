# SpicyVPN Desktop

Development preview for Windows, Linux and macOS. This is not yet a feature-parity
replacement for the Android client and is not promoted to stable public releases.

## Implemented

- Native Xray, Mihomo and Aether sidecars with verified release digests.
- Auto discovery, Manual selection and Custom profiles.
- Clipboard/file imports, subscriptions, folders, favorites, QR/text sharing.
- Shared proxy/TUN router, opt-in country rules and process routing configuration.
- DNS-only profile configuration and DNS resolver settings.
- HTTP, SOCKS, VLESS, VMess, Trojan, Shadowsocks, Hysteria2 and WireGuard/AWG imports.
- SSH imports and optional verified-TLS/upstream transport.
- Encrypted profile storage using the operating-system keychain.
- Live counters and exit-IP requests sent through the active local proxy.

## Verification Boundary

Automated fixtures exercise actual traffic through Mihomo HTTP, Xray VMess,
SSH and certificate-verified SSH+TLS. GUI-disconnect cleanup, encrypted storage
updates and UI behavior are also tested. Real TUN and route restoration passed
on disposable Windows and macOS runners and in an isolated Linux network
namespace. None of these tests change the developer server's global routes or
open Internet-facing listeners. Parser tests do not prove live service access.
System TUN requires operating-system privileges; production privilege handling,
complete crash-recovery validation and independent end-user testing remain.

OpenVPN, OpenConnect, IKEv2, DNSTT, MasterDNS, StormDNS, Tor bridges, Psiphon,
all advanced S-button combinations and protected folder bundles have not been
fully ported. AdMob is Android-specific and is not embedded in desktop builds.
Desktop installers need platform-specific signing, not the Android keystore.

## Development

Use Node.js 24 and Python 3.11+:

```sh
npm ci
npm run engines
npm test
npm run test:integration
npm start
```

`npm run dist` creates a local package. Credentials are not committed. Proxies
and control APIs bind to loopback; TUN is never enabled by the automated local
tests. Existing system proxy settings are saved before changing them and restored
on a normal disconnect, with a recovery journal checked on the next startup.
