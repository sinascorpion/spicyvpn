"""Fail the release gate if a registered engine is absent on any supported ABI."""
import json
import struct
import zipfile
from update_mihomo import ROOT

ABIS = {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86': 3, 'x86_64': 62}
BINARIES = {'Mihomo': 'libmihomo.so', 'Aether': 'libspicyaether_core.so',
            'OpenConnect': 'libopenconnect.so', 'MasterDNS': 'libspicymasterdns.so',
            'StormDNS': 'libspicystormdns.so', 'Tor': 'libtor.so',
            'Psiphon': 'libspicypsiphon.so', 'Lyrebird': 'liblyrebird.so', 'Conjure': 'libconjure.so'}


def verify_elf(data, machine, label):
    if len(data) < 20 or data[:4] != b'\x7fELF' or int.from_bytes(data[18:20], 'little') != machine:
        raise RuntimeError('Invalid or wrong-architecture engine: ' + label)
    if machine in (183, 62):
        if len(data) < 64 or data[4:6] != b'\x02\x01':
            raise RuntimeError('Invalid 64-bit ELF header: ' + label)
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        if size < 56 or offset + size * count > len(data):
            raise RuntimeError('Invalid ELF program headers: ' + label)
        loads = []
        for index in range(count):
            header = struct.unpack_from('<IIQQQQQQ', data, offset + size * index)
            if header[0] == 1:
                loads.append(header[-1])
        if not loads or min(loads) < 16384:
            raise RuntimeError('Engine does not support Android 16KB pages: ' + label)


def main():
    for name, binary in BINARIES.items():
        for abi, machine in ABIS.items():
            path = ROOT / 'android/app/src/main/jniLibs' / abi / binary
            verify_elf(path.read_bytes(), machine, name + '/' + abi)
    for library in ('libv2ray.aar', 'dnstt.aar'):
        with zipfile.ZipFile(ROOT / 'android/app/libs' / library) as archive:
            if 'classes.jar' not in archive.namelist():
                raise RuntimeError('Missing Java binding: ' + library)
            for abi, machine in ABIS.items():
                members = [name for name in archive.namelist() if name.startswith('jni/' + abi + '/') and name.endswith('.so')]
                if not members:
                    raise RuntimeError('Missing native binding: ' + library + '/' + abi)
                for member in members:
                    verify_elf(archive.read(member), machine, library + '/' + member)
    versions = json.loads((ROOT / 'android/app/src/main/assets/core_versions.json').read_text())
    required = set(BINARIES) - {'Aether', 'Lyrebird'} | {'Xray', 'DNSTT', 'Tor bridges / Lyrebird'}
    for name in required:
        if not versions.get(name, {}).get('version'):
            raise RuntimeError('Missing About version: ' + name)
    print('Verified all registered standalone engines and Android bindings on four ABIs')


if __name__ == '__main__':
    main()
