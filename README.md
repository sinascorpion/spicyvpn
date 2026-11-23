# SpicyVPN

An open-source, multi-engine connection client.

[Get SpicyVPN on Google Play](https://play.google.com/store/apps/details?id=com.vpn.spicy)
 | [Download Android APKs](https://github.com/sinascorpion/spicyvpn/releases)

## Available Platform

Android is available today. Desktop clients for Windows, Linux and macOS are not
released yet. This repository does not claim desktop feature parity or ship
renamed third-party applications.

## Android Features

- Auto, Manual and Custom connection modes.
- Configuration imports, subscription groups, folders and sharing.
- Xray, VLESS, VMess, Trojan, Shadowsocks and Hysteria2.
- WireGuard, AmneziaWG, OpenVPN, OpenConnect and Android IKEv2.
- SSH with optional TLS and upstream proxy support.
- Aether, WARP, Psiphon and Tor transports.
- DNSTT, MasterDNS, StormDNS and DNS-only profiles.
- Per-app split tunneling and supported country-routing modes.
- Themes, translations, traffic statistics and local proxy sharing.

Capabilities depend on the selected engine and operating system. A listed engine
does not imply every advanced option works with every other engine.

## Source and Service Boundary

This repository contains client source, engine adaptations and build helpers.
The hosted discovery service, server inventory, administrator dashboard and its
credentials are not distributed here. The client retains its public discovery
API so Auto and Manual keep their existing behavior. A client API address is not
a secret and cannot be made inaccessible to someone inspecting an installed app.

## Building

Use JDK 17, the Android SDK/NDK versions declared in the Gradle project, Python
3.11+, Go and Rust. Native engines must be built before compiling the app; their
source and platform build helpers are included under `android/native` and
`scripts`. Prebuilt engine binaries are deliberately not stored in source Git.
See the source snapshot and About metadata for the exact engine versions in
an official release. Local release signing requires your own
keystore. The project's signing credentials are never supplied to forks.

Android releases contain architecture-specific and universal APKs. Google Play
App Bundles are not published in this repository.

## License

SpicyVPN client changes are distributed under AGPL-3.0. Third-party files retain
their original notices and licenses. See [third-party notices](THIRD_PARTY_NOTICES.md).
