"""Rebuild Psiphon and Tor transports; preserve reviewed Conjure fixes."""
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path
from update_mihomo import ROOT

SOURCES = {
    'Psiphon': ('https://github.com/CluvexStudio/psiphon-tunnel-core.git', './ConsoleClient', 'libspicypsiphon.so'),
    'Tor bridges / Lyrebird': ('https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git', './cmd/lyrebird', 'liblyrebird.so'),
    'Conjure': ('https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/conjure.git', './client', 'libconjure.so'),
}
ARCHES = {'arm64-v8a': ('arm64', 'aarch64-linux-android', 183),
          'armeabi-v7a': ('arm', 'armv7a-linux-androideabi', 40),
          'x86': ('386', 'i686-linux-android', 3), 'x86_64': ('amd64', 'x86_64-linux-android', 62)}


def replace_once(path, old, new):
    text = path.read_text()
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError("Upstream Conjure adaptation needs review: " + str(path))
    path.write_text(text.replace(old, new, 1))


def patch_conjure(source):
    replace_once(source / 'client/conjure.go',
                 '\t\tgetSOCKSArgs(conn, config)',
                 '\t\tconnectionConfig := *config\n\t\tconnectionConfig.Fronts = append([]string(nil), config.Fronts...)\n\t\tgetSOCKSArgs(conn, &connectionConfig)')
    replace_once(source / 'client/conjure.go', 'err := handler(conn, config)', 'err := handler(conn, &connectionConfig)')
    replace_once(source / 'client/conjure/registration.go',
                 'phantomConn, err := dialer.DialContext(context.Background(), "tcp", config.BridgeAddress)',
                 'ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)\n\tdefer cancel()\n\tphantomConn, err := dialer.DialContext(ctx, "tcp", config.BridgeAddress)')


def main():
    manifest = ROOT / 'android/app/src/main/assets/core_versions.json'
    versions = json.loads(manifest.read_text())
    ndk = Path(os.environ['ANDROID_NDK_HOME'])
    tools = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    staged = []
    with tempfile.TemporaryDirectory(prefix='spicy-transports-') as directory:
        for index, (name, (url, target, filename)) in enumerate(SOURCES.items()):
            source = Path(directory) / str(index)
            subprocess.run(['git', 'clone', '--filter=blob:none', url, str(source)], check=True)
            tags = subprocess.check_output(['git', 'tag', '--sort=-version:refname'], cwd=source, text=True).splitlines()
            # This fork's HEAD contains Android-required transports; inherited
            # official tags are not releases of the fork.
            stable = None if name == 'Psiphon' else next((tag for tag in tags if re.fullmatch(r'v?\d+(?:\.\d+)+', tag)), None)
            if stable:
                subprocess.run(['git', 'checkout', stable], cwd=source, check=True)
            revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
            if name == 'Conjure':
                patch_conjure(source)
            subprocess.run(['go', 'test', '-timeout=2m', target], cwd=source, check=True)
            hashes = {}
            for abi, (arch, compiler, machine) in ARCHES.items():
                print(f'Building {name} for {abi} at {revision[:12]}', flush=True)
                output = Path(directory) / f'{index}-{abi}.so'
                env = dict(os.environ, GOOS='android', GOARCH=arch, CGO_ENABLED='1',
                           CC=str(tools / (compiler + '26-clang')))
                if arch == 'arm':
                    env['GOARM'] = '7'
                # Android transports depend on anet's Go runtime linknames.
                flags = '-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384'
                subprocess.run(['go', 'build', '-trimpath', '-buildmode=pie', '-ldflags=' + flags, '-o', str(output), target], cwd=source, env=env, check=True)
                binary = output.read_bytes()
                if binary[:4] != b'\x7fELF' or int.from_bytes(binary[18:20], 'little') != machine:
                    raise RuntimeError('Invalid transport ABI: ' + abi)
                import hashlib
                hashes[abi] = hashlib.sha256(binary).hexdigest()
                staged.append((ROOT / 'android/app/src/main/jniLibs' / abi / filename, binary))
            versions[name] = {'version': stable or ('snapshot ' + revision[:12]), 'revision': revision,
                              'source': url, 'sha256': hashes}
        for destination, binary in staged:
            destination.write_bytes(binary)
        manifest.write_text(json.dumps(versions, indent=2) + '\n')


if __name__ == '__main__':
    main()
