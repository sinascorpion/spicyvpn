"""Resolve the SSH library's stable release before the Kotlin test gate."""
import json
import re
from update_mihomo import ROOT, fetch


def main():
    release = json.loads(fetch('https://api.github.com/repos/mwiede/jsch/releases/latest'))
    if release['draft'] or release['prerelease']:
        raise RuntimeError('Refusing preview SSH library')
    version = release['tag_name'].removeprefix('jsch-').removeprefix('v')
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise RuntimeError('Unexpected SSH version')
    path = ROOT / 'android/app/build.gradle.kts'
    original = path.read_text()
    current = re.search(r'com\.github\.mwiede:jsch:(\d+\.\d+\.\d+)', original)
    if current and tuple(map(int, version.split('.'))) < tuple(map(int, current.group(1).split('.'))):
        raise RuntimeError('SSH release metadata is older than the installed library')
    content, count = re.subn(r'com\.github\.mwiede:jsch:\d+\.\d+\.\d+', 'com.github.mwiede:jsch:' + version, original)
    if count != 1:
        raise RuntimeError('SSH dependency declaration needs review')
    path.write_text(content)
    print('SSH dependency resolved to ' + version)


if __name__ == '__main__':
    main()
