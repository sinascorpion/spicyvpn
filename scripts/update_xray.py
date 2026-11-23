"""Resolve the stable Xray release first, then install its matching Android binding."""
import json
import zipfile
import subprocess
import sys
from update_mihomo import fetch, ROOT


def main():
    upstream = json.loads(fetch("https://api.github.com/repos/XTLS/Xray-core/releases/latest"))
    if upstream["draft"] or upstream["prerelease"]:
        raise RuntimeError("Xray release is not stable")
    tag = upstream["tag_name"]
    manifest = ROOT / "android/app/src/main/assets/core_versions.json"
    current = json.loads(manifest.read_text()) if manifest.exists() else {}
    target = ROOT / "android/app/libs/libv2ray.aar"
    if current.get("Xray", {}).get("version") != tag + " + cipher mapping + finalMask sequences" or not target.exists():
        subprocess.run([sys.executable, str(ROOT / "scripts/build_xray_compat.py"), "--tag", tag], check=True)
    with zipfile.ZipFile(target) as archive:
        if "classes.jar" not in archive.namelist():
            raise RuntimeError("Android binding has no Java classes")
        for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
            if not any(name.startswith(f"jni/{abi}/") for name in archive.namelist()):
                raise RuntimeError(f"Missing Xray architecture: {abi}")
    print(f"Verified stable Xray {tag} with cipher compatibility")


if __name__ == "__main__":
    main()
