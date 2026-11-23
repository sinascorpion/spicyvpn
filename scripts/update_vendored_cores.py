"""Three-way upstream updates: retain Android adaptations, reject merge conflicts."""
import json
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".tmp/rust-merge-parser"))
from update_mihomo import fetch, ROOT

REGISTRY = {
    "Aether": {"repository": "https://github.com/CluvexStudio/Aether.git", "api": "CluvexStudio/Aether",
               "revision": "0e6f6a52", "directory": "android/native/aether", "paths": ["aether"]},
    "DNSTT": {"repository": "https://www.bamsoftware.com/git/dnstt.git", "revision": "v1.20240513.0",
              "directory": "android/native/dnstt", "paths": ["dns", "noise", "turbotunnel"]},
}


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def merge_file(local, base, remote, scratch):
    # Git checkout may use CRLF on Windows, while git-show returns LF.
    local, base, remote = tuple(
        value.replace(b"\r\n", b"\n") if value is not None and b"\0" not in value else value
        for value in (local, base, remote)
    )
    if local == base:
        return remote
    if remote == base or local == remote:
        return local
    if local is None or base is None or remote is None or any(b"\0" in data for data in (local, base, remote)):
        raise RuntimeError("Upstream update conflicts with Android adaptations")
    files = [scratch / name for name in ("local", "base", "remote")]
    for file, data in zip(files, (local, base, remote)):
        file.write_bytes(data)
    merged = subprocess.run(["git", "merge-file", "-p", *map(str, files)], capture_output=True)
    if merged.returncode != 0:
        raise RuntimeError("Upstream source conflict; refusing to publish an unreviewed core")
    return merged.stdout


def merge_cargo_manifest(local, base, remote):
    missing = object()
    def merge(a, b, c):
        if a == b:
            return c
        if c == b or a == c:
            return a
        if all(isinstance(value, dict) for value in (a, b, c)):
            result = {}
            for key in a.keys() | b.keys() | c.keys():
                value = merge(a.get(key, missing), b.get(key, missing), c.get(key, missing))
                if value is not missing:
                    result[key] = value
            return result
        raise RuntimeError("Cargo manifest field changed both locally and upstream")
    def encode(value):
        if isinstance(value, dict):
            return "{ " + ", ".join(json.dumps(k) + " = " + encode(v) for k, v in sorted(value.items())) + " }"
        if isinstance(value, list):
            return "[" + ", ".join(map(encode, value)) + "]"
        return json.dumps(value)
    result = merge(*(tomllib.loads(data.decode()) for data in (local, base, remote)))
    output = "\n".join(json.dumps(k) + " = " + encode(v) for k, v in sorted(result.items())) + "\n"
    assert tomllib.loads(output) == result
    return output.encode()


def stage_review(review, updates, registry, conflicts):
    review = review.resolve()
    review.mkdir(parents=True, exist_ok=True)
    deleted = []
    for destination, content in updates:
        relative = destination.relative_to(ROOT)
        if content is None:
            deleted.append(relative.as_posix())
            continue
        staged = review / relative
        staged.parent.mkdir(parents=True, exist_ok=True)
        staged.write_bytes(content)
    (review / "core-source-lock.json").write_text(json.dumps(registry, indent=2) + "\n")
    (review / "conflicts.json").write_text(json.dumps(conflicts, indent=2) + "\n")
    (review / "deleted-files.json").write_text(json.dumps(deleted, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", choices=list(REGISTRY))
    parser.add_argument("--review-directory", type=Path,
                        help="Stage merged sources and conflict diagnostics without changing active cores")
    options = parser.parse_args()
    lock = ROOT / "android/native/core-source-lock.json"
    registry = json.loads(lock.read_text()) if lock.exists() else REGISTRY
    updates = []
    conflicts = []
    with tempfile.TemporaryDirectory(prefix="spicyvpn-core-update-") as temporary:
        work = Path(temporary)
        for name, source in registry.items():
            if options.core and name != options.core:
                continue
            repo = work / name
            # Every source blob is inspected below; a partial clone turns that
            # into a separate network fetch per file on Windows.
            subprocess.run(["git", "clone", "--quiet", "--no-checkout", source["repository"], str(repo)], check=True)
            if source.get("api"):
                release = json.loads(fetch("https://api.github.com/repos/" + source["api"] + "/releases/latest"))
                if release["draft"] or release["prerelease"]:
                    raise RuntimeError(name + " has no eligible stable release")
                latest = release["tag_name"]
            else:
                tags = git(repo, "tag", "--sort=-version:refname").splitlines()
                latest = next(tag for tag in tags if tag.startswith("v1.") and "-" not in tag)
            revision = git(repo, "rev-parse", latest + "^{commit}")
            base = git(repo, "rev-parse", source["revision"] + "^{commit}")
            if revision == base:
                continue
            for subtree in source["paths"]:
                old = set(git(repo, "ls-tree", "-r", "--name-only", base, "--", subtree).splitlines())
                new = set(git(repo, "ls-tree", "-r", "--name-only", revision, "--", subtree).splitlines())
                for relative in sorted((old | new) - {""}):
                    destination = ROOT / source["directory"] / relative
                    local = destination.read_bytes() if destination.exists() else None
                    original = subprocess.check_output(["git", "-C", str(repo), "show", base + ":" + relative]) if relative in old else None
                    remote = subprocess.check_output(["git", "-C", str(repo), "show", revision + ":" + relative]) if relative in new else None
                    try:
                        if relative.endswith("/Cargo.lock"):
                            merged = remote
                        elif relative.endswith("/Cargo.toml") and all(value is not None for value in (local, original, remote)):
                            merged = merge_cargo_manifest(local, original, remote)
                        elif relative.endswith(".rs") and all(value is not None for value in (local, original, remote)):
                            try:
                                merged = merge_file(local, original, remote, work)
                            except RuntimeError:
                                from merge_rust import merge
                                merged = merge(local, original, remote, merge_file, work,
                                    ROOT / ".tmp/rust-item-conflicts" / name / relative)
                        else:
                            merged = merge_file(local, original, remote, work)
                    except RuntimeError as error:
                        diagnostics = ROOT / ".tmp/upstream-conflicts" / name / relative
                        diagnostics.parent.mkdir(parents=True, exist_ok=True)
                        for suffix, data in ((".local", local), (".base", original), (".upstream", remote)):
                            diagnostics.with_name(diagnostics.name + suffix).write_bytes(data or b"")
                        conflicts.append(f"{name}/{relative}: {error}")
                        continue
                    if merged != local:
                        updates.append((destination, merged))
            source["revision"] = revision
            source["version"] = latest
        # Only commit file changes after every upstream merge has passed.
        if options.review_directory:
            stage_review(options.review_directory, updates, registry, conflicts)
            print(f"Staged {len(updates)} changes and {len(conflicts)} conflicts for review; active cores unchanged")
            return
        if conflicts:
            raise RuntimeError("Unresolved upstream conflicts:\n" + "\n".join(conflicts))
        for destination, content in updates:
            if content is None:
                destination.unlink()
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(content)
        lock.write_text(json.dumps(registry, indent=2) + "\n")
    print(f"Updated {len(updates)} native source files without dropping local adaptations")


if __name__ == "__main__":
    main()
