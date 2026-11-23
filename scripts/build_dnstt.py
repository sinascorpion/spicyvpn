"""Build the DNSTT AAR in isolation with namespaced Go JNI runtime bindings."""
import json
import hashlib
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/native/dnstt"
OUTPUT = ROOT / "android/app/libs/dnstt.aar"
MOBILE_VERSION = "v0.0.0-20260112195712-5b9ecdfb8721"


def record_version():
    manifest = ROOT / "android/app/src/main/assets/core_versions.json"
    versions = json.loads(manifest.read_text()) if manifest.exists() else {}
    lock = ROOT / "android/native/core-source-lock.json"
    source = json.loads(lock.read_text()).get("DNSTT", {}) if lock.exists() else {}
    version = source.get("version", "vendored")
    versions["DNSTT"] = {"version": version + " / build " + hashlib.sha256(OUTPUT.read_bytes()).hexdigest()[:12],
                          "revision": source.get("revision"),
                          "source": "https://www.bamsoftware.com/software/dnstt/"}
    manifest.write_text(json.dumps(versions, indent=2) + "\n")


def namespace_aar(source, destination):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w") as rebuilt:
        libraries = 0
        for entry in original.infolist():
            data = original.read(entry.filename)
            if entry.filename.startswith("jni/") and entry.filename.endswith("/libgojni.so"):
                entry.filename = entry.filename.replace("/libgojni.so", "/libspicydnstt.so")
                libraries += 1
            rebuilt.writestr(entry, data)
        if libraries != 4:
            raise RuntimeError(f"Expected four DNSTT native ABIs, found {libraries}")


def main():
    metadata = json.loads(subprocess.check_output(
        ["go", "mod", "download", "-json", f"golang.org/x/mobile@{MOBILE_VERSION}"],
        cwd=SOURCE, text=True))
    with tempfile.TemporaryDirectory(prefix="spicyvpn-dnstt-") as directory:
        temp = Path(directory)
        module = temp / "dnstt"
        runtime = temp / "mobile-runtime"
        shutil.copytree(SOURCE, module, ignore=shutil.ignore_patterns(".git", ".build"))
        shutil.copytree(metadata["Dir"], runtime)
        generator = runtime / "bind/genjava.go"
        generator.chmod(stat.S_IWRITE | stat.S_IREAD)
        java_source = generator.read_text(encoding="utf-8")
        old_package = 'if pkg == nil {\n\t\treturn "go"\n\t}'
        if old_package not in java_source or "import go.Seq;" not in java_source:
            raise RuntimeError("Pinned gomobile generator changed; review JNI namespace patch")
        java_source = java_source.replace(old_package, 'if pkg == nil {\n\t\treturn pkgPrefix + ".go"\n\t}', 1)
        java_source = java_source.replace("import go.Seq;", "import com.spicyvpn.dnstt.go.Seq;")
        generator.write_text(java_source, encoding="utf-8")
        for file in (SOURCE / "binding-overlay").iterdir():
            if file.name != "LICENSE":
                (runtime / "bind/java" / file.name).chmod(stat.S_IWRITE | stat.S_IREAD)
                shutil.copyfile(file, runtime / "bind/java" / file.name)
        subprocess.run(["go", "mod", "edit", f"-replace=golang.org/x/mobile={runtime.as_posix()}"],
                       cwd=module, check=True)
        env = dict(os.environ, GOBIN=str(temp / "bin"))
        env["PATH"] = env["GOBIN"] + os.pathsep + env.get("PATH", "")
        subprocess.run(["go", "install", "golang.org/x/mobile/cmd/gobind"], cwd=module, env=env, check=True)
        staged = temp / "dnstt.aar"
        subprocess.run(["go", "run", "golang.org/x/mobile/cmd/gomobile", "bind",
                        "-target=android", "-androidapi=26", "-javapkg=com.spicyvpn.dnstt",
                        "-o", str(staged), "./mobile"], cwd=module, env=env, check=True)
        if staged.stat().st_size < 1_000_000:
            raise RuntimeError("DNSTT build output is unexpectedly small")
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        namespace_aar(staged, OUTPUT.with_suffix(".aar.download"))
        os.replace(OUTPUT.with_suffix(".aar.download"), OUTPUT)
        record_version()
        print(f"Built {OUTPUT.name}: {OUTPUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
