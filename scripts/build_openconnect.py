"""Build the official OpenConnect JNI engine in Linux, without public listeners."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from update_mihomo import fetch

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = {"OpenConnect": "9.21", "OpenSSL": "3.5.9", "libxml2": "2.15.4"}
ARCHES = {
    "arm64-v8a": ("aarch64-linux-android", "android-arm64"),
    "armeabi-v7a": ("armv7a-linux-androideabi", "android-arm"),
    "x86_64": ("x86_64-linux-android", "android-x86_64"),
    "x86": ("i686-linux-android", "android-x86"),
}


def download(url, target):
    if target.exists():
        return
    request = urllib.request.Request(url, headers={"User-Agent": "SpicyVPN-build"})
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read()
    temporary = target.with_suffix(".download")
    temporary.write_bytes(data)
    temporary.replace(target)


def run(args, cwd, env):
    print("Running", args[0], "in", cwd, flush=True)
    log = cwd / "spicyvpn-build.log"
    with log.open("ab") as output:
        result = subprocess.run(args, cwd=cwd, env=env, stdout=output, stderr=output)
    if result.returncode:
        print(log.read_text(errors="replace")[-12000:], flush=True)
        raise subprocess.CalledProcessError(result.returncode, args)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", choices=ARCHES, action="append")
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args()
    ndk = Path(os.environ["ANDROID_NDK_HOME"]).resolve()
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    cache = ROOT / ".tmp/openconnect-downloads"
    cache.mkdir(parents=True, exist_ok=True)
    if args.update:
        with urllib.request.urlopen("https://www.infradead.org/openconnect/download.html", timeout=60) as response:
            releases = re.findall(r"openconnect-([0-9]+\.[0-9]+)\.tar", response.read().decode())
        VERSIONS["OpenConnect"] = max([VERSIONS["OpenConnect"], *releases], key=lambda value: tuple(map(int, value.split('.'))))
        releases = json.loads(fetch("https://api.github.com/repos/openssl/openssl/releases?per_page=100"))
        stable = [r["tag_name"].removeprefix("openssl-") for r in releases if not r["prerelease"] and not r["draft"]
                  and re.fullmatch(r"openssl-3\.5\.[0-9]+", r["tag_name"])]
        VERSIONS["OpenSSL"] = max([VERSIONS["OpenSSL"], *stable], key=lambda value: tuple(map(int, value.split('.'))))
        with urllib.request.urlopen("https://download.gnome.org/sources/libxml2/2.15/", timeout=60) as response:
            releases = re.findall(r"libxml2-(2\.15\.[0-9]+)\.tar\.xz", response.read().decode())
        VERSIONS["libxml2"] = max([VERSIONS["libxml2"], *releases], key=lambda value: tuple(map(int, value.split('.'))))
    oc_version, ssl_version, xml_version = (VERSIONS[name] for name in ("OpenConnect", "OpenSSL", "libxml2"))
    sources = [
        ("openconnect-" + oc_version, f"https://www.infradead.org/openconnect/download/openconnect-{oc_version}.tar.gz"),
        ("openssl-" + ssl_version, f"https://github.com/openssl/openssl/releases/download/openssl-{ssl_version}/openssl-{ssl_version}.tar.gz"),
        ("libxml2-" + xml_version, f"https://download.gnome.org/sources/libxml2/2.15/libxml2-{xml_version}.tar.xz"),
    ]
    archives = {}
    for name, url in sources:
        archive = cache / url.rsplit("/", 1)[-1]
        download(url, archive)
        archives[name] = archive
    hashes = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in archives.items()}
    # Linux filesystem is required: the NDK contains case-distinct header names.
    with tempfile.TemporaryDirectory(prefix="spicyvpn-openconnect-") as temporary:
        work = Path(temporary)
        for abi in args.abi or ARCHES:
            triplet, openssl_target = ARCHES[abi]
            arch = work / abi
            arch.mkdir()
            for archive in archives.values():
                with tarfile.open(archive) as source:
                    source.extractall(arch, filter="data")
            prefix = arch / "install"
            env = dict(os.environ)
            env.update(ANDROID_NDK_ROOT=str(ndk),
                       PATH=str(toolchain / "bin") + os.pathsep + env["PATH"],
                       CC=str(toolchain / "bin" / (triplet + "26-clang")),
                       AR=str(toolchain / "bin/llvm-ar"),
                       RANLIB=str(toolchain / "bin/llvm-ranlib"),
                       STRIP=str(toolchain / "bin/llvm-strip"),
                       CFLAGS="-O2 -fPIC", LDFLAGS="-Wl,-z,max-page-size=16384")
            ssl = arch / ("openssl-" + ssl_version)
            run(["perl", "Configure", openssl_target, "-D__ANDROID_API__=26", "no-shared",
                 "no-tests", "no-module", "--prefix=" + str(prefix)], ssl, env)
            run(["make", "-j4", "build_libs"], ssl, env)
            xml = arch / ("libxml2-" + xml_version)
            xmlbuild = arch / "xml-build"
            run(["cmake", "-S", str(xml), "-B", str(xmlbuild),
                 "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
                 "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=26", "-DBUILD_SHARED_LIBS=OFF",
                 "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", "-DCMAKE_INSTALL_PREFIX=" + str(prefix),
                 "-DLIBXML2_WITH_PROGRAMS=OFF", "-DLIBXML2_WITH_TESTS=OFF",
                 "-DLIBXML2_WITH_PYTHON=OFF", "-DLIBXML2_WITH_ZLIB=OFF",
                 "-DLIBXML2_WITH_LZMA=OFF", "-DLIBXML2_WITH_ICONV=OFF"], arch, env)
            run(["cmake", "--build", str(xmlbuild), "-j4"], arch, env)
            run(["cmake", "--install", str(xmlbuild)], arch, env)
            oc = arch / ("openconnect-" + oc_version)
            env.update(LIBXML2_CFLAGS="-I" + str(prefix / "include/libxml2"),
                       LIBXML2_LIBS=str(prefix / "lib/libxml2.a"),
                       OPENSSL_CFLAGS="-I" + str(ssl / "include"),
                       OPENSSL_LIBS=str(ssl / "libssl.a") + " " + str(ssl / "libcrypto.a") + " -ldl -lz -pthread",
                       PKG_CONFIG_LIBDIR=str(prefix / "lib/pkgconfig"))
            run(["./configure", "--host=" + triplet, "--with-openssl", "--enable-shared",
                 "--without-gnutls", "--disable-nls", "--without-libproxy",
                 "--without-libpcsclite", "--without-libstoken", "--without-lz4",
                 "--with-java=" + str(toolchain / "sysroot/usr/include"),
                 "--enable-jni-standalone", "--disable-symvers", "--with-vpnc-script=/dev/null"], oc, env)
            run(["make", "-j4", "libopenconnect.la"], oc, env)
            library = oc / ".libs/libopenconnect.so"
            destination = ROOT / "android/app/src/main/jniLibs" / abi
            destination.mkdir(parents=True, exist_ok=True)
            shutil.copy2(library, destination / "libopenconnect.so")
            java = ROOT / "android/app/src/main/java/org/infradead/libopenconnect"
            java.mkdir(parents=True, exist_ok=True)
            shutil.copy2(oc / "java/src/org/infradead/libopenconnect/LibOpenConnect.java", java)
            shutil.copy2(oc / "COPYING.LGPL", ROOT / "android/app/src/main/assets/openconnect_LICENSE.txt")
    manifest = ROOT / "android/app/src/main/assets/core_versions.json"
    versions = json.loads(manifest.read_text())
    versions["OpenConnect"] = {"version": VERSIONS["OpenConnect"], "dependencies": VERSIONS,
                               "sourceSHA256": hashes, "source": "https://www.infradead.org/openconnect/"}
    manifest.write_text(json.dumps(versions, indent=2) + "\n")


if __name__ == "__main__":
    main()
