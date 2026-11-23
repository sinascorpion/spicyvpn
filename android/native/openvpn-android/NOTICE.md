# OpenVPN Native Core

SpicyVPN builds the official OpenVPN 3 core, release/3.11.7,
from https://github.com/OpenVPN/openvpn3 under Mozilla Public License 2.0.
The corresponding upstream source is available at
https://github.com/OpenVPN/openvpn3/tree/release/3.11.7.
The local Android JNI integration is in bridge.cpp.
The CMake integration applies one compatibility change: explicitly call
mbedtls_ssl_set_hostname with NULL when OpenVPN has no TLS DNS hostname.
OpenVPN's CA, certificate role and configured verify-x509-name checks remain
enabled. This adapts to Mbed TLS 3.6.7's explicit hostname API requirement.

Build dependencies:
- Mbed TLS 3.6.7: Apache-2.0 OR GPL-2.0-or-later
- Asio 1.38.2: Boost Software License 1.0
- fmt 12.2.0: MIT
- LZ4 1.10.0: BSD-2-Clause

Pinned source archives are declared in CMakeLists.txt. Android release
libraries are built with function-level stripping and 16 KiB load alignment.
