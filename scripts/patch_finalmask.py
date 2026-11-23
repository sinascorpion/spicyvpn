"""Backport sequence fragmentation without replacing the stable Xray core."""
import re
import shutil
import stat
from pathlib import Path
from update_mihomo import fetch

REFERENCE = "655804bc54f4"


def patch(core: Path):
    base = f"https://raw.githubusercontent.com/patterniha/xray-core/{REFERENCE}/"
    candidates = [core / "infra/conf" / name for name in
                  ("transport_finalmask.go", "transport_internet.go")]
    pattern = r"type FragmentMask struct \{.*?\n}\n\nfunc \(c \*FragmentMask\) Build\(\) \(proto.Message, error\) \{.*?\n}"
    parsers = [path for path in candidates if path.exists()
               and len(re.findall(pattern, path.read_text(), re.S)) == 1]
    if len(parsers) != 1:
        raise RuntimeError("Fragment parser changed; compatibility backport requires review")
    parser = parsers[0]
    source = parser.read_text()
    fragment = core / "transport/internet/finalmask/fragment"
    native_sequences = (
        re.search(r"Lengths\s+\[\]Int32Range", source) is not None
        and "LengthsMin" in (fragment / "config.pb.go").read_text()
        and "LengthsMin" in (fragment / "conn.go").read_text()
    )
    if native_sequences:
        print("Using stable Xray's native finalMask sequences; no backport needed")
    else:
        reference = fetch(base + "infra/conf/transport_finalmask.go").decode()
        replacement = re.search(pattern, reference, re.S)
        if replacement is None:
            raise RuntimeError("Reference fragment parser changed")
        parser.chmod(stat.S_IREAD | stat.S_IWRITE)
        parser.write_text(re.sub(pattern, lambda _: replacement.group(), source, count=1, flags=re.S))
        for name in ("config.proto", "config.pb.go", "conn.go"):
            target = fragment / name
            target.chmod(stat.S_IREAD | stat.S_IWRITE)
            target.write_bytes(fetch(base + "transport/internet/finalmask/fragment/" + name))
    shutil.copy2(Path(__file__).parent / "patches/finalmask_sequence_test.go", fragment / "sequence_test.go")
    shutil.copy2(Path(__file__).parent / "patches/finalmask_parser_test.go", core / "infra/conf/spicy_finalmask_test.go")


if __name__ == "__main__":
    import sys
    patch(Path(sys.argv[1]))
