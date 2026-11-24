"""Archive the complete unpacked application, preserving Unix executable bits."""
import json
import platform
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
version = json.loads((root / 'package.json').read_text())['version']
system = {'Windows': 'win', 'Linux': 'linux', 'Darwin': 'mac'}[platform.system()]
arch = 'arm64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x64'
dist = root / 'dist'
source = dist / {'win': 'win-arm64-unpacked' if arch == 'arm64' else 'win-unpacked', 'linux': 'linux-arm64-unpacked' if arch == 'arm64' else 'linux-unpacked',
                 'mac': 'mac-arm64' if arch == 'arm64' else 'mac'}[system]
if not source.is_dir():
    raise SystemExit(f'Unpacked application not found: {source}')
engines = list(source.rglob('engines/versions.json'))
if not engines:
    raise SystemExit('Bundled engines are missing')
destination = dist / f'SpicyVPN-{version}-{system}-{arch}-portable.zip'
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for item in sorted(source.rglob('*')):
        if item.is_file():
            archive.write(item, Path('SpicyVPN') / item.relative_to(source))
print(destination.name)
