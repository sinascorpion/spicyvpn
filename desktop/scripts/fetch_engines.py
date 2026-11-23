"""Fetch official stable desktop engines and verify GitHub asset digests."""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def get(url):
    headers = {'User-Agent': 'SpicyVPN-Desktop'}
    if url.startswith('https://api.github.com/') and os.getenv('GH_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=180) as response:
        return response.read()


def release(repo):
    value = json.loads(get(f'https://api.github.com/repos/{repo}/releases/latest'))
    if value['draft'] or value['prerelease']:
        raise ValueError('Only stable engines are permitted')
    return value


def payload(value, name):
    asset = next(item for item in value['assets'] if item['name'] == name)
    data = get(asset['browser_download_url'])
    digest = asset.get('digest')
    if not digest or digest != 'sha256:' + hashlib.sha256(data).hexdigest():
        raise ValueError('Engine asset checksum missing or mismatched: ' + name)
    return data


def unpack(data, destination, accepted):
    if data[:2] == b'PK':
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = [(p.filename, archive.read(p)) for p in archive.infolist() if not p.is_dir() and Path(p.filename).name in accepted]
    else:
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            entries = [(p.name, archive.extractfile(p).read()) for p in archive if p.isfile() and Path(p.name).name in accepted]
    for name, content in entries:
        path = destination / Path(name).name
        path.write_bytes(content)
        path.chmod(0o755)
    return [Path(name).name for name, _ in entries]


def main():
    target = ROOT / 'engines'
    target.mkdir(exist_ok=True)
    system = platform.system()
    arm = platform.machine().lower() in ('aarch64', 'arm64')
    if system == 'Windows' and arm:
        raise ValueError('Windows ARM64 is not packaged yet')
    versions = {}
    mihomo = release('MetaCubeX/mihomo')
    suffix = {'Windows': 'windows-amd64-compatible', 'Linux': 'linux-arm64' if arm else 'linux-amd64-compatible', 'Darwin': 'darwin-arm64' if arm else 'darwin-amd64'}[system]
    name = f'mihomo-{suffix}-{mihomo["tag_name"]}.' + ('zip' if system == 'Windows' else 'gz')
    data = payload(mihomo, name)
    if system == 'Windows':
        extracted = unpack(data, target, {f'mihomo-{suffix}.exe', f'mihomo-{suffix}-{mihomo["tag_name"]}.exe', 'mihomo.exe'})
        if len(extracted) != 1:
            raise ValueError('Unexpected Mihomo archive layout')
        (target / extracted[0]).rename(target / 'mihomo.exe') if extracted[0] != 'mihomo.exe' else None
    else:
        (target / 'mihomo').write_bytes(gzip.decompress(data))
        (target / 'mihomo').chmod(0o755)
    versions['Mihomo'] = mihomo['tag_name']
    (target / 'Mihomo_LICENSE.txt').write_bytes(get(f'https://raw.githubusercontent.com/MetaCubeX/mihomo/{mihomo["tag_name"]}/LICENSE'))
    xray = release('XTLS/Xray-core')
    suffix = {'Windows': 'windows-64', 'Linux': 'linux-arm64-v8a' if arm else 'linux-64', 'Darwin': 'macos-arm64-v8a' if arm else 'macos-64'}[system]
    unpack(payload(xray, f'Xray-{suffix}.zip'), target, {'xray', 'xray.exe', 'wintun.dll', 'geoip.dat', 'geosite.dat', 'LICENSE'})
    if (target / 'LICENSE').exists():
        (target / 'Xray_LICENSE.txt').write_bytes((target / 'LICENSE').read_bytes())
    versions['Xray'] = xray['tag_name']
    aether = release('CluvexStudio/Aether')
    suffix = {'Windows': 'windows-x86_64.zip', 'Linux': 'linux-aarch64-musl.tar.gz' if arm else 'linux-x86_64-musl.tar.gz', 'Darwin': 'macos-arm64.tar.gz' if arm else 'macos-x86_64.tar.gz'}[system]
    unpack(payload(aether, 'aether-' + suffix), target, {'aether', 'aether.exe', 'LICENSE'})
    (target / 'Aether_LICENSE.txt').write_bytes(get(f'https://raw.githubusercontent.com/CluvexStudio/Aether/{aether["tag_name"]}/LICENSE'))
    versions['Aether'] = aether['tag_name']
    for name in ('mihomo', 'xray', 'aether'):
        if not (target / (name + ('.exe' if system == 'Windows' else ''))).is_file():
            raise ValueError('Required engine missing: ' + name)
    (target / 'versions.json').write_text(json.dumps(versions, indent=2) + '\n')
    print(json.dumps(versions))


if __name__ == '__main__':
    main()
