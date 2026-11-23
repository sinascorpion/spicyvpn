"""Resolve stable OpenVPN dependencies; stay on the supported Mbed TLS 3.6 LTS API."""
import json
import re
from urllib.parse import quote
from update_mihomo import fetch, ROOT


def api(repository, path):
    return json.loads(fetch(f"https://api.github.com/repos/{repository}/{path}"))


def main():
    path = ROOT / "android/native/openvpn-android/dependencies.json"
    dependencies = json.loads(path.read_text())
    for name in ("ovpn", "fmt", "lz4", "mbedtls"):
        repository = dependencies[name]["repository"]
        if name in ("ovpn", "mbedtls"):
            tags = api(repository, "tags?per_page=100")
            pattern = r"release/\d+\.\d+(?:\.\d+)?" if name == "ovpn" else r"v3\.6\.\d+"
            stable = [tag["name"] for tag in tags if re.fullmatch(pattern, tag["name"])]
            stable.append(dependencies[name]["tag"])
            dependencies[name]["tag"] = max(stable, key=lambda tag: tuple(map(int, tag.removeprefix("release/").removeprefix("v").split("."))))
            continue
        releases = api(repository, "releases?per_page=100")
        candidates = [release for release in releases if not release["draft"] and not release["prerelease"]]
        if name == "mbedtls":
            candidates = [release for release in candidates if re.fullmatch(r"v3\.6\.\d+", release["tag_name"])]
        if not candidates:
            raise RuntimeError(f"No compatible stable release for {name}")
        dependencies[name]["tag"] = candidates[0]["tag_name"]
    tags = api(dependencies["asio"]["repository"], "tags?per_page=100")
    stable = [tag["name"] for tag in tags if re.fullmatch(r"asio-\d+-\d+-\d+", tag["name"])]
    dependencies["asio"]["tag"] = max(stable, key=lambda tag: tuple(map(int, tag.split("-")[1:])))
    framework = api("Mbed-TLS/mbedtls", "contents/framework?ref=" + quote(dependencies["mbedtls"]["tag"], safe=""))
    if not re.fullmatch(r"[0-9a-f]{40}", framework["sha"]):
        raise RuntimeError("Invalid Mbed TLS framework submodule revision")
    dependencies["mbedframework"]["tag"] = framework["sha"]
    path.write_text(json.dumps(dependencies, indent=2) + "\n")
    print(json.dumps(dependencies, indent=2))


if __name__ == "__main__":
    main()
