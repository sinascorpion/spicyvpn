"""Build independently licensed MasterDNS/StormDNS clients with Android NDK."""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile
import urllib.error
from update_mihomo import fetch, ROOT

CORES = {"masterdns": ("masterking32/MasterDnsVPN", "MasterDNS"),
         "stormdns": ("nullroute1970/StormDNS", "StormDNS")}
ARCHES = {"arm64-v8a": ("arm64", "aarch64-linux-android"),
          "armeabi-v7a": ("arm", "armv7a-linux-androideabi"),
          "x86_64": ("amd64", "x86_64-linux-android"), "x86": ("386", "i686-linux-android")}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path)
    options = parser.parse_args()
    ndk = Path(os.environ["ANDROID_NDK_HOME"])
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    toolchain = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    go = os.environ.get("SPICYVPN_GO", "go")
    manifest = ROOT / "android/app/src/main/assets/core_versions.json"
    versions = json.loads(manifest.read_text()) if manifest.exists() else {}
    with tempfile.TemporaryDirectory(prefix="dns-core-build-", dir=ROOT / ".tmp") as temporary:
        for core, (repository, display) in CORES.items():
            if options.source_root:
                source = options.source_root / (core + "-reference")
                commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
            else:
                try:
                    release = json.loads(fetch(f"https://api.github.com/repos/{repository}/releases/latest"))
                    if release["draft"] or release["prerelease"]:
                        raise RuntimeError("Refusing preview DNS core")
                    revision = release["tag_name"]
                except urllib.error.HTTPError as error:
                    if error.code != 404:
                        raise
                    revision = json.loads(fetch(f"https://api.github.com/repos/{repository}/commits?per_page=1"))[0]["sha"]
                source = Path(temporary) / core
                subprocess.run(["git", "clone", "https://github.com/" + repository + ".git", str(source)], check=True)
                subprocess.run(["git", "checkout", revision], cwd=source, check=True)
                commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
            subprocess.run([go, "test", "./..."], cwd=source, check=True)
            license_text = (source / "LICENSE").read_text()
            if "MIT" not in license_text:
                raise RuntimeError("DNS core license changed; review required")
            (ROOT / "android/app/src/main/assets" / (core + "_LICENSE.txt")).write_text(license_text)
            for abi, (arch, target) in ARCHES.items():
                output = ROOT / "android/app/src/main/jniLibs" / abi / ("libspicy" + core + ".so")
                output.parent.mkdir(parents=True, exist_ok=True)
                env = os.environ.copy()
                env.update(GOOS="android", GOARCH=arch, CGO_ENABLED="1")
                if arch == "arm":
                    env["GOARM"] = "7"
                if os.name == "nt":
                    env["CC"] = f'"{toolchain / "clang.exe"}" --target={target}26'
                else:
                    env["CC"] = str(toolchain / (target + "26-clang"))
                staged = output.with_suffix(".download")
                subprocess.run([go, "build", "-trimpath", "-buildmode=pie", "-ldflags=-s -w", "-o", str(staged), "./cmd/client"], cwd=source, env=env, check=True)
                if staged.read_bytes()[:4] != b"\x7fELF":
                    raise RuntimeError("Not an Android ELF executable")
                staged.replace(output)
            info = json.loads((source / "cmd/client/versioninfo.json").read_text())
            versions[display] = {"version": info["StringFileInfo"]["ProductVersion"] + " (" + commit[:8] + ")",
                                 "source": "https://github.com/" + repository + "/tree/" + commit}
            manifest.write_text(json.dumps(versions, indent=2) + "\n")
            print("Built", display, "for all Android architectures", flush=True)


if __name__ == "__main__":
    main()
