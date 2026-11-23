# DNSTT Android Integration

The DNSTT protocol source is derived from the CC0-licensed project at
https://www.bamsoftware.com/software/dnstt/ and the mobile integration
from dnstt_xyz_app. The Android wrapper supports UDP DNS resolvers,
Noise server-key authentication, KCP/smux streams, IPv4 and IPv6,
and VpnService socket protection.

DNSTT forwards raw TCP streams. The configured server must forward to
a SOCKS service, or to an SSH service when SSH credentials are supplied.
It does not turn an arbitrary DNS server into a VPN server.

scripts/build_dnstt.py builds a namespaced AAR without modifying this
source module or relying on machine-specific paths. The Go mobile runtime
overlay retains the Go project's BSD license in binding-overlay/LICENSE.
