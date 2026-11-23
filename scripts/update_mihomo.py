"""Install official stable Android Mihomo executables and record shipped versions."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def fetch(url):
    headers = {"User-Agent": "SpicyVPN-core-updater"}
    if url.startswith("https://api.github.com/") and os.environ.get("GH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as response:
        return response.read()


def main():
    release = json.loads(fetch("https://api.github.com/repos/MetaCubeX/mihomo/releases/latest"))
    if release["prerelease"] or release["draft"]:
        raise RuntimeError("Refusing a non-stable release")
    tag = release["tag_name"]
    artifacts = {asset["name"]: asset for asset in release["assets"]}
    arches = {"arm64-v8a": ("arm64-v8", 183), "armeabi-v7a": ("armv7", 40),
              "x86": ("386", 3), "x86_64": ("amd64", 62)}
    staged = []
    for abi, (name, machine) in arches.items():
        asset = artifacts[f"mihomo-android-{name}-{tag}.gz"]
        compressed = fetch(asset["browser_download_url"])
        digest = asset.get("digest")
        if digest and digest != "sha256:" + hashlib.sha256(compressed).hexdigest():
            raise RuntimeError("Official asset digest mismatch")
        binary = gzip.decompress(compressed)
        if binary[:4] != b"\x7fELF" or int.from_bytes(binary[18:20], "little") != machine:
            raise RuntimeError("Invalid Android ELF architecture")
        destination = ROOT / "android/app/src/main/jniLibs" / abi / "libmihomo.so"
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(".download")
        temporary.write_bytes(binary)
        staged.append((temporary, destination, hashlib.sha256(binary).hexdigest()))
    for temporary, destination, _ in staged:
        temporary.replace(destination)
    manifest = ROOT / "android/app/src/main/assets/core_versions.json"
    versions = json.loads(manifest.read_text()) if manifest.exists() else {}
    versions["Mihomo"] = {"version": tag, "source": release["html_url"],
                          "sha256": {dest.parent.name: digest for _, dest, digest in staged}}
    versions["AmneziaWG"] = {"version": "1-3.1 (Mihomo " + tag + ")",
                            "source": "https://wiki.metacubex.one/en/config/proxies/wg/"}
    manifest.write_text(json.dumps(versions, indent=2) + "\n")
    license_file = ROOT / "android/app/src/main/assets/mihomo_LICENSE.txt"
    license_file.write_bytes(fetch(f"https://raw.githubusercontent.com/MetaCubeX/mihomo/{tag}/LICENSE"))
    print(f"Installed official Mihomo {tag} for {', '.join(arches)}")


if __name__ == "__main__":
    main()
