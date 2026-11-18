#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Packs the rows>=2 race table for the offline browser build.

usage: pack-tables.py <rows2.bin> [--out DIR]          pack (default DIR: <repo>/build/offline)
       pack-tables.py --verify [<rows2.bin>] [--out DIR]  pack nothing; round-trip DIR/rows2.bin.gz against the manifest

Pack: reads the table, checks its SHA-256 against the constant SHA256 in RaceTable.java (a different file is an error,
exit 1), writes DIR/rows2.bin.gz (gzip -9, mtime 0, so the same input gives the same bytes) and DIR/rows2.manifest.json.
The manifest keys come first and the compressed data is a separate file, so a truncated manifest is detectable:
  {"v":1,"alg":"gzip","rawBytes":N,"sha256":"<of the raw file>","gzBytes":M,"file":"rows2.bin.gz"}
Verify: gunzips the file, checks gzBytes, rawBytes and the SHA-256 of the result against the manifest, and the
manifest SHA-256 against RaceTable.java again. With <rows2.bin> it also checks that this file has the SHA-256 of the packed
data, so a packed file of another table is a difference. Exit 0 = all equal, 1 = a difference, 2 = wrong call (usage is printed).
Files are written under a temporary name and renamed, so a name that exists is complete.
"""
import gzip
import hashlib
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
RACE_TABLE_JAVA = ROOT / "tablebase/src/main/java/sternhalma/tablebase/RaceTable.java"
GZ_NAME = "rows2.bin.gz"
MANIFEST_NAME = "rows2.manifest.json"


def expected_sha() -> str:
    # matches:     public static final String SHA256 = "d62e...78e3";
    text = RACE_TABLE_JAVA.read_text(encoding="utf-8")
    m = re.search(r'public static final String SHA256\s*=\s*"(?P<sha>[0-9a-f]{64})"', text)
    if not m:
        sys.exit(f"pack-tables: no SHA256 constant found in {RACE_TABLE_JAVA}")
    return m.group("sha")


def atomic_write(path: Path, data: bytes) -> None:
    tmp = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def pack(src: Path, out: Path) -> int:
    raw = src.read_bytes()
    sha = hashlib.sha256(raw).hexdigest()
    want = expected_sha()
    if sha != want:
        print(f"pack-tables: {src} has sha256 {sha}, RaceTable.java says {want}", file=sys.stderr)
        return 1
    gz = gzip.compress(raw, compresslevel=9, mtime=0)
    out.mkdir(parents=True, exist_ok=True)
    atomic_write(out / GZ_NAME, gz)
    manifest = {"v": 1, "alg": "gzip", "rawBytes": len(raw), "sha256": sha, "gzBytes": len(gz), "file": GZ_NAME}
    atomic_write(out / MANIFEST_NAME, (json.dumps(manifest, separators=(",", ":")) + "\n").encode("ascii"))
    print(f"packed: raw {len(raw)} B, gzip {len(gz)} B ({len(gz) / len(raw):.3f}), sha256 {sha}")
    return 0


def verify(out: Path, src: "Path | None" = None) -> int:
    manifest = json.loads((out / MANIFEST_NAME).read_text(encoding="ascii"))
    keys = list(manifest)
    if keys != ["v", "alg", "rawBytes", "sha256", "gzBytes", "file"] or manifest["v"] != 1 or manifest["alg"] != "gzip":
        print(f"verify: unexpected manifest keys or values: {keys}", file=sys.stderr)
        return 1
    gz = (out / manifest["file"]).read_bytes()
    problems = []
    if len(gz) != manifest["gzBytes"]:
        problems.append(f"gzBytes {len(gz)} != {manifest['gzBytes']}")
    try:
        raw = gzip.decompress(gz)  # checks the CRC32 and the length in the gzip trailer
    except (OSError, EOFError) as e:
        print(f"verify: gunzip failed: {e}", file=sys.stderr)
        return 1
    if len(raw) != manifest["rawBytes"]:
        problems.append(f"rawBytes {len(raw)} != {manifest['rawBytes']}")
    sha = hashlib.sha256(raw).hexdigest()
    if sha != manifest["sha256"]:
        problems.append(f"sha256 {sha} != manifest {manifest['sha256']}")
    if manifest["sha256"] != expected_sha():
        problems.append("manifest sha256 differs from RaceTable.java")
    if src is not None:
        src_sha = hashlib.sha256(src.read_bytes()).hexdigest()
        if src_sha != sha:
            problems.append(f"{src} has sha256 {src_sha}, the packed data {sha}")
    for p in problems:
        print("verify: " + p, file=sys.stderr)
    if problems:
        return 1
    print(f"verified: {len(gz)} B gzip -> {len(raw)} B, sha256 {sha}")
    return 0


def main(argv) -> int:
    args = list(argv)
    out = ROOT / "build/offline"
    if "--out" in args:
        i = args.index("--out")
        if i + 1 >= len(args):
            print(__doc__, file=sys.stderr)
            return 2
        out = Path(args[i + 1])
        del args[i:i + 2]
    if args and args[0] == "--verify":
        if len(args) not in (1, 2):
            print(__doc__, file=sys.stderr)
            return 2
        return verify(out, Path(args[1]) if len(args) == 2 else None)
    if len(args) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    return pack(Path(args[0]), out)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
