"""Match Android's TLS dependencies to Aether's tested upstream lockfile."""
from pathlib import Path
import re
import tomllib


def sync(manifest: str, lock: str) -> str:
    packages = tomllib.loads(lock)["package"]
    for name in ("boring", "boring-sys", "tokio-boring"):
        versions = {p["version"] for p in packages if p["name"] == name}
        if len(versions) != 1:
            raise ValueError(f"Ambiguous or missing Aether TLS ABI: {name}")
        version = versions.pop()
        pattern = rf'(?m)^{re.escape(name)} = "=[^"]+"$'
        manifest, count = re.subn(pattern, f'{name} = "={version}"', manifest)
        if count != 1:
            raise ValueError(f"Missing Android TLS ABI constraint: {name}")
    return manifest


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    manifest = root / "android/native/android-bridge/Cargo.toml"
    lock = root / "android/native/aether/aether/Cargo.lock"
    manifest.write_text(sync(manifest.read_text(), lock.read_text()))
