"""Build official stable Xray plus PattNG's legacy cipher-name mapping."""
import argparse
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import zipfile
from update_mihomo import fetch, ROOT
from patch_finalmask import patch as patch_finalmask


def run(args, cwd, env):
    subprocess.run(args, cwd=cwd, env=env, check=True)


def patch_cipher_mapping(file):
    code = file.read_text()
    anchor = "\t\tfor _, s := range tls.CipherSuites() {\n\t\t\tid[s.Name] = s.ID\n\t\t}"
    legacy = "\t\tfor _, s := range tls.InsecureCipherSuites() {\n\t\t\tid[s.Name] = s.ID\n\t\t}"
    if code.count(anchor) != 1:
        raise RuntimeError("Upstream TLS mapping changed; review compatibility patch")
    if legacy in code:
        print("Using stable Xray's native legacy cipher-name mapping")
        return
    file.chmod(stat.S_IREAD | stat.S_IWRITE)
    file.write_text(code.replace(anchor, anchor + "\n" + legacy, 1))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag")
    args = parser.parse_args()
    release = json.loads(fetch("https://api.github.com/repos/XTLS/Xray-core/releases/latest"))
    tag = args.tag or release["tag_name"]
    if release["draft"] or release["prerelease"]:
        raise RuntimeError("Stable Xray release required")
    with tempfile.TemporaryDirectory(prefix="spicyvpn-xray-") as temporary:
        temp = Path(temporary)
        archive = temp / "binding.zip"
        archive.write_bytes(fetch("https://api.github.com/repos/2dust/AndroidLibXrayLite/zipball/" + tag))
        with zipfile.ZipFile(archive) as source:
            source.extractall(temp / "binding")
        binding = next((temp / "binding").iterdir())
        metadata = json.loads(subprocess.check_output(
            ["go", "mod", "download", "-json", "github.com/xtls/xray-core"], cwd=binding, text=True))
        core = temp / "xray-core"
        shutil.copytree(metadata["Dir"], core)
        # Go's module cache is read-only; only the temporary copy is patched.
        for path in [core, *core.rglob("*")]:
            path.chmod(path.stat().st_mode | stat.S_IWUSR)
        file = core / "transport/internet/tls/config.go"
        patch_cipher_mapping(file)
        patch_finalmask(core)
        shutil.copy2(Path(__file__).parent / "patches/cipher_mapping_test.go",
                     core / "transport/internet/tls/spicy_cipher_mapping_test.go")
        run(["go", "test", "./transport/internet/tls", "-run", "TestSpicyCipher"], core, os.environ)
        run(["go", "test", "./transport/internet/finalmask/fragment"], core, os.environ)
        run(["go", "test", "./infra/conf", "-run", "TestSpicyFinalMask"], core, os.environ)
        run(["go", "mod", "edit", "-replace=github.com/xtls/xray-core=" + core.as_posix()], binding, os.environ)
        env = dict(os.environ, GOBIN=str(temp / "bin"))
        env["PATH"] = env["GOBIN"] + os.pathsep + env["PATH"]
        # The upstream binding can omit sums for transitive mobile-tool modules.
        # Resolve its pinned graph before installing gobind or invoking gomobile.
        run(["go", "mod", "download", "all"], binding, env)
        run(["go", "install", "golang.org/x/mobile/cmd/gobind"], binding, env)
        output = temp / "libv2ray.aar"
        run(["go", "run", "golang.org/x/mobile/cmd/gomobile", "bind", "-target=android",
             "-androidapi=26", "-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384", "-o", str(output), "."], binding, env)
        with zipfile.ZipFile(output) as aar:
            for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
                if not any(name.startswith("jni/" + abi + "/") for name in aar.namelist()):
                    raise RuntimeError("Missing Xray ABI " + abi)
        shutil.copy2(output, ROOT / "android/app/libs/libv2ray.aar")
    manifest = ROOT / "android/app/src/main/assets/core_versions.json"
    versions = json.loads(manifest.read_text())
    versions["Xray"] = {"version": tag + " + cipher mapping + finalMask sequences", "source": release["html_url"],
                        "compatibilityPatch": "Legacy TLS cipher mapping and sequence finalMask fragmentation"}
    manifest.write_text(json.dumps(versions, indent=2) + "\n")


if __name__ == "__main__":
    main()
