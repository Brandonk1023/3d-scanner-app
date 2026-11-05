#!/usr/bin/env python3
"""Lists the copyright / license / provenance notices in every third-party file the C++ core
actually compiles, and compares them with the reviewed baseline in native/third_party_notices.txt.

Third-party code often vendors pieces from other authors (e.g. Manifold's svd.h, PoissonRecon's PLY
code) whose notices must also ship in androidApp/src/main/assets/licenses/. Run after any change to a
facet_fetch() pin:

    python3 native/scripts/scan_notices.py native/build-host            # check (exit 1 on changes)
    python3 native/scripts/scan_notices.py native/build-host --update   # after reviewing + updating assets

The file list comes from Ninja's dependency log (`ninja -t deps`) plus the compile commands, so only
files that are really built count.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
NATIVE = os.path.dirname(HERE)
BASELINE = os.path.join(NATIVE, "third_party_notices.txt")
DEPS_MARK = "/native/.deps/"
NOTICE = re.compile(
    r"(copyright\s*(\(c\)|©|\d{4})|\(c\)\s*\d{4}|spdx-license-identifier|public domain|unlicense"
    r"|derived from|modified from|adapted from|ported from|taken from|originally by|\bfrom https?://)",
    re.IGNORECASE,
)


def find_ninja():
    """$NINJA, else ninja on PATH, else the one in the Android SDK's CMake (like the Makefile)."""
    if os.environ.get("NINJA"):
        return os.environ["NINJA"]
    import shutil
    if shutil.which("ninja"):
        return "ninja"
    root = os.path.dirname(NATIVE)
    try:
        sdk = re.search(r"^sdk\.dir=(.+)$", open(os.path.join(root, "local.properties")).read(), re.M).group(1).strip()
        cmake = re.search(r'^cmake = "(.+)"', open(os.path.join(root, "gradle", "libs.versions.toml")).read(), re.M).group(1)
        return os.path.join(sdk, "cmake", cmake, "bin", "ninja")
    except (OSError, AttributeError):
        return "ninja"


def ninja(build_dir, *args):
    exe = find_ninja()
    return subprocess.run([exe, "-C", build_dir, *args], capture_output=True, text=True, check=True).stdout


def compiled_files(build_dir):
    candidates = []
    # `ninja -t deps`: one header path per indented line (paths may contain spaces).
    for line in ninja(build_dir, "-t", "deps").splitlines():
        if line.startswith(" ") and line.strip():
            candidates.append(line.strip())
    # Compile commands: sources follow -c, possibly quoted.
    for m in re.finditer(r"-c\s+('([^']+)'|\"([^\"]+)\"|(\S+))", ninja(build_dir, "-t", "commands")):
        candidates.append(m.group(2) or m.group(3) or m.group(4))
    files = set()
    for path in candidates:
        full = os.path.normpath(path if os.path.isabs(path) else os.path.join(build_dir, path))
        if DEPS_MARK in full and os.path.isfile(full):
            files.add(full)
    return sorted(files)


def notices(path):
    out = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if NOTICE.search(line):
                cleaned = re.sub(r"\s+", " ", line.strip(" \t\r\n/*#;\"")).strip()
                if cleaned:
                    out.append(cleaned)
    return out


def scan(build_dir):
    rows = set()
    for path in compiled_files(build_dir):
        rel = path.split(DEPS_MARK, 1)[1]
        lib = rel.split("/", 1)[0]
        for n in notices(path):
            rows.add(f"{lib}\t{n}")
    return sorted(rows)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    rows = scan(sys.argv[1])
    if not rows:
        print("scan_notices: no third-party files found; build native/ first", file=sys.stderr)
        return 2
    if "--update" in sys.argv:
        with open(BASELINE, "w", encoding="utf-8") as f:
            f.write("# Reviewed notices in compiled third-party files (native/scripts/scan_notices.py).\n")
            f.write("# Every holder here is credited in androidApp/src/main/assets/licenses/.\n")
            f.write("\n".join(rows) + "\n")
        print(f"scan_notices: wrote {len(rows)} notices to {os.path.relpath(BASELINE)}")
        return 0
    known = set()
    if os.path.exists(BASELINE):
        with open(BASELINE, encoding="utf-8") as f:
            known = {l.rstrip("\n") for l in f if l.strip() and not l.startswith("#")}
    new = [r for r in rows if r not in known]
    if new:
        print("scan_notices: notices not in native/third_party_notices.txt — check whether they need", file=sys.stderr)
        print("crediting in assets/licenses/, then re-run with --update:", file=sys.stderr)
        for r in new:
            print("  + " + r.replace("\t", ": "), file=sys.stderr)
        return 1
    print(f"scan_notices: {len(rows)} notices, all reviewed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
