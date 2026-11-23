"""Install official Tor Android binaries without trusting stale Maven metadata."""
import io
import json
import re
import zipfile
import hashlib
from update_mihomo import ROOT, fetch

ABIS = {"arm64-v8a": 183, "armeabi-v7a": 40, "x86": 3, "x86_64": 62}


def version_key(version):
    if not re.fullmatch(r"\d+(?:\.\d+)+", version):
        raise ValueError("Not a stable numeric Tor version")
    return tuple(map(int, version.split('.')))


def main():
    path = "info/guardianproject/tor-android"
    entries = json.loads(fetch("https://api.github.com/repos/guardianproject/gpmaven/contents/" + path))
    candidates = [item['name'] for item in entries if item['type'] == 'dir' and re.fullmatch(r"\d+(?:\.\d+)+", item['name'])]
    latest = max(candidates, key=version_key)
    manifest = ROOT / "android/app/src/main/assets/core_versions.json"
    versions = json.loads(manifest.read_text())
    if version_key(latest) < version_key(versions['Tor']['version']):
        raise RuntimeError("Official Tor repository is older than installed Tor; refusing downgrade")
    url = f"https://raw.githubusercontent.com/guardianproject/gpmaven/master/{path}/{latest}/tor-android-{latest}.aar"
    content = fetch(url)
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        staged = {}
        for abi, machine in ABIS.items():
            binary = archive.read(f"jni/{abi}/libtor.so")
            if binary[:4] != b'\x7fELF' or int.from_bytes(binary[18:20], 'little') != machine:
                raise RuntimeError("Invalid Tor ABI: " + abi)
            staged[abi] = binary
    for abi, binary in staged.items():
        destination = ROOT / "android/app/src/main/jniLibs" / abi / "libtor.so"
        destination.write_bytes(binary)
    versions['Tor'] = {'version': latest, 'source': url, 'sha256': hashlib.sha256(content).hexdigest()}
    manifest.write_text(json.dumps(versions, indent=2) + '\n')
    print("Installed official Tor " + latest)


if __name__ == '__main__':
    main()
