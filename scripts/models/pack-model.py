#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Packs a network (F32 safetensors) for the browser: F16 weights, gzip -9, and an entry in web/models/models.json.

usage:
  pack-model.py pack <net.safetensors> --id ID --variant jump:players [--variant ...] --created-from LABEL
                [--strength-json JSON-or-FILE] [--out DIR]
  pack-model.py verify [--out DIR] [--source ID=<net.safetensors> ...]
  pack-model.py widen <ID> <out.safetensors> [--out DIR]

pack:    reads the F32 file, converts every tensor to F16 (struct 'e', round to nearest even; a value beyond 65504 or a
         NaN/Inf is an error, exit 1), keeps the metadata and adds weights_dtype=F16 and source_sha256, writes
         DIR/<ID>.safetensors.gz (gzip -9, mtime 0, no file name: same input, same bytes) and adds or replaces the entry
         <ID> in DIR/models.json. Default DIR: <repo>/web/models. Variants are jump:players, jump adjacent|long, players 2|3.
verify:  every entry: file exists, packedBytes and sha256Packed match, gunzip gives rawBytesF16 and sha256Raw, the header is
         F16 with the metadata the entry claims (formatVersion, planes, players, params). With --source ID=file it also checks
         that the packed net is the F16 rounding of that F32 file (sourceSha256 and every weight). Exit 0 = ok, 1 = a difference.
widen:   writes the F32 safetensors the browser loader produces from the packed file (for the agreement check in Java).
Manifest key order: lengths and hashes come before the descriptive fields (a cut-short manifest is detectable).
Files are written under a temporary name and renamed. Exit codes: 0 ok, 1 failure, 2 wrong call.
"""
import argparse
import gzip
import hashlib
import json
import math
import os
import re
import struct
import sys
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_OUT = ROOT / "web/models"
MANIFEST = "models.json"
F16_MAX = 65504.0
ID_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")


def fail(msg: str, code: int = 1):
    print(f"pack-model: {msg}", file=sys.stderr)
    sys.exit(code)


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def atomic_write(path: Path, data: bytes) -> None:
    tmp = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def parse(raw: bytes):
    """-> (metadata dict, [(name, dtype, shape, begin, end)] in file order, data bytes)."""
    if len(raw) < 8:
        fail("safetensors: shorter than 8 bytes")
    (n,) = struct.unpack("<Q", raw[:8])
    if 8 + n > len(raw):
        fail("safetensors: header longer than the file")
    header = json.loads(raw[8:8 + n].decode("utf-8"))
    meta = header.pop("__metadata__", {})
    data = raw[8 + n:]
    tensors = [(k, v["dtype"], v["shape"], v["data_offsets"][0], v["data_offsets"][1]) for k, v in header.items()]
    pos = 0
    for k, _dtype, _shape, b, e in sorted(tensors, key=lambda t: t[3]):
        if b != pos:
            fail(f"safetensors: tensor {k} starts at {b}, expected {pos} (gap or overlap)")
        pos = e
    if pos != len(data):
        fail(f"safetensors: data section has {len(data)} bytes, tensors cover {pos}")
    return meta, tensors, data


def build(meta, specs, blobs) -> bytes:
    header = {"__metadata__": meta}
    pos = 0
    for (name, dtype, shape), blob in zip(specs, blobs):
        header[name] = {"dtype": dtype, "shape": shape, "data_offsets": [pos, pos + len(blob)]}
        pos += len(blob)
    text = json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    text += b" " * (-len(text) % 8)
    return struct.pack("<Q", len(text)) + text + b"".join(blobs)


def to_f16(raw: bytes):
    meta, tensors, data = parse(raw)
    if meta.get("format") != "sternhalma-nn":
        fail("metadata format is not sternhalma-nn")
    if meta.get("weights_dtype", "F32") != "F32":
        fail("the source is not an F32 net")
    blobs, specs, count, small = [], [], 0, 0
    for name, dtype, shape, b, e in tensors:
        if dtype != "F32":
            fail(f"tensor {name} has dtype {dtype}, expected F32")
        n = math.prod(shape)
        if e - b != 4 * n:
            fail(f"tensor {name}: {e - b} bytes for {n} values")
        vals = struct.unpack(f"<{n}f", data[b:e])
        for v in vals:
            if not math.isfinite(v):
                fail(f"tensor {name} holds a non-finite value")
            if abs(v) > F16_MAX:
                fail(f"tensor {name} holds {v}, beyond the F16 range of {F16_MAX}")
            if v != 0 and abs(v) < 6.103515625e-5:
                small += 1
        count += n
        blobs.append(struct.pack(f"<{n}e", *vals))
        specs.append((name, "F16", shape))
    out = dict(meta)
    out["weights_dtype"] = "F16"
    out["source_sha256"] = sha(raw)
    return build(out, specs, blobs), meta, count, small


def widen(raw16: bytes) -> bytes:
    meta, tensors, data = parse(raw16)
    if meta.get("weights_dtype") != "F16":
        fail("metadata weights_dtype is not F16")
    blobs, specs = [], []
    for name, dtype, shape, b, e in tensors:
        if dtype != "F16":
            fail(f"tensor {name} has dtype {dtype}, expected F16")
        n = math.prod(shape)
        blobs.append(struct.pack(f"<{n}f", *struct.unpack(f"<{n}e", data[b:e])))
        specs.append((name, "F32", shape))
    out = {k: v for k, v in meta.items() if k not in ("weights_dtype", "source_sha256")}
    return build(out, specs, blobs)


def read_manifest(out: Path) -> dict:
    p = out / MANIFEST
    if not p.exists():
        return {"v": 1, "models": []}
    m = json.loads(p.read_text(encoding="utf-8"))
    if m.get("v") != 1 or not isinstance(m.get("models"), list):
        fail(f"{p}: unexpected manifest shape")
    return m


def write_manifest(out: Path, m: dict) -> None:
    m["models"].sort(key=lambda e: e["id"])
    atomic_write(out / MANIFEST, (json.dumps(m, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def parse_variant(s: str) -> dict:
    jump, _, players = s.partition(":")
    if jump not in ("adjacent", "long") or players not in ("2", "3"):
        fail(f"--variant {s!r}: expected adjacent|long:2|3", 2)
    return {"jump": jump, "players": int(players)}


def cmd_pack(a) -> int:
    if not ID_RE.match(a.id):
        fail("--id must be lower-case words joined by '-'", 2)
    variants = [parse_variant(v) for v in a.variant]
    if not variants:
        fail("at least one --variant is required", 2)
    strength = {}
    if a.strength_json:
        s = a.strength_json
        strength = json.loads(Path(s).read_text() if os.path.isfile(s) else s)
        if not isinstance(strength, dict):
            fail("--strength-json must be a JSON object", 2)
    src = Path(a.net).read_bytes()
    f16, meta, count, small = to_f16(src)
    gz = gzip.compress(f16, compresslevel=9, mtime=0)  # compress() writes no file name into the header
    gz32 = gzip.compress(src, compresslevel=9, mtime=0)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    file = f"{a.id}.safetensors.gz"
    for path in out.glob("*.tmp.*"):
        path.unlink()  # CLEANUP: leftovers of an interrupted run
    atomic_write(out / file, gz)
    entry = {
        "id": a.id,
        "rawBytesF16": len(f16),
        "packedBytes": len(gz),
        "sha256Raw": sha(f16),
        "sha256Packed": sha(gz),
        "file": file,
        "formatVersion": int(meta["format_version"]),
        "planeSet": meta["planes"],
        "planes": int(meta["input_planes"]),
        "players": int(meta["players"]),
        "params": count,
        "variants": variants,
        "sourceSha256": sha(src),
        "createdFrom": a.created_from,
        "strength": strength,
        "labelKey": f"model.{a.id}.label",
        "descriptionKey": f"model.{a.id}.description",
    }
    m = read_manifest(out)
    m["models"] = [e for e in m["models"] if e["id"] != a.id] + [entry]
    write_manifest(out, m)
    print(f"pack-model: {a.id}: F32 {len(src)} B (gzip {len(gz32)} B) -> F16 {len(f16)} B -> packed {len(gz)} B; "
          f"{count} weights, {small} below the smallest normal F16 (precision loss only)", file=sys.stderr)
    return 0


def check_entry(out: Path, e: dict, source: Optional[Path]) -> list:
    errs = []
    p = out / e["file"]
    if not p.exists():
        return [f"{e['id']}: {p} is missing"]
    gz = p.read_bytes()
    if len(gz) != e["packedBytes"]:
        errs.append(f"{e['id']}: packedBytes {e['packedBytes']}, file has {len(gz)}")
    if sha(gz) != e["sha256Packed"]:
        errs.append(f"{e['id']}: sha256Packed differs")
    try:
        raw = gzip.decompress(gz)
    except Exception as ex:  # noqa: BLE001
        return errs + [f"{e['id']}: gunzip failed: {ex}"]
    if len(raw) != e["rawBytesF16"]:
        errs.append(f"{e['id']}: rawBytesF16 {e['rawBytesF16']}, gunzip gives {len(raw)}")
    if sha(raw) != e["sha256Raw"]:
        errs.append(f"{e['id']}: sha256Raw differs")
    meta, tensors, _ = parse(raw)
    if meta.get("weights_dtype") != "F16" or any(t[1] != "F16" for t in tensors):
        errs.append(f"{e['id']}: not an F16 net")
    for key, mk in (("formatVersion", "format_version"), ("planes", "input_planes"), ("players", "players")):
        if str(e[key]) != meta.get(mk):
            errs.append(f"{e['id']}: {key} {e[key]} differs from header {mk}={meta.get(mk)}")
    if e["planeSet"] != meta.get("planes"):
        errs.append(f"{e['id']}: planeSet differs from the header")
    if e["params"] != sum(math.prod(t[2]) for t in tensors):
        errs.append(f"{e['id']}: params differ from the tensor shapes")
    if meta.get("source_sha256") != e["sourceSha256"]:
        errs.append(f"{e['id']}: sourceSha256 differs from the header")
    if source is not None:
        src = source.read_bytes()
        if sha(src) != e["sourceSha256"]:
            errs.append(f"{e['id']}: {source} is not the source net (sha256 differs)")
        else:
            again = to_f16(src)[0]
            if again != raw:
                errs.append(f"{e['id']}: packed data is not the F16 rounding of the source")
    return errs


def cmd_verify(a) -> int:
    out = Path(a.out)
    m = read_manifest(out)
    sources = dict(s.split("=", 1) for s in a.source)
    errs, ids = [], set()
    for e in m["models"]:
        if e["id"] in ids:
            errs.append(f"duplicate id {e['id']}")
        ids.add(e["id"])
        errs += check_entry(out, e, Path(sources[e["id"]]) if e["id"] in sources else None)
    files = {e["file"] for e in m["models"]}
    for p in out.glob("*.safetensors.gz"):
        if p.name not in files:
            errs.append(f"{p.name} is in the directory but not in the manifest")
    for s in sources:
        if s not in ids:
            errs.append(f"--source {s}: no such id")
    for x in errs:
        print(f"pack-model: {x}", file=sys.stderr)
    print(f"pack-model: verify: {len(m['models'])} models, {len(errs)} differences", file=sys.stderr)
    return 1 if errs else 0


def cmd_widen(a) -> int:
    m = read_manifest(Path(a.out))
    e = next((x for x in m["models"] if x["id"] == a.id), None)
    if e is None:
        fail(f"no model {a.id}")
    atomic_write(Path(a.dest), widen(gzip.decompress((Path(a.out) / e["file"]).read_bytes())))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pack")
    p.add_argument("net")
    p.add_argument("--id", required=True)
    p.add_argument("--variant", action="append", default=[])
    p.add_argument("--created-from", required=True)
    p.add_argument("--strength-json")
    p.add_argument("--out", default=str(DEFAULT_OUT))
    v = sub.add_parser("verify")
    v.add_argument("--out", default=str(DEFAULT_OUT))
    v.add_argument("--source", action="append", default=[])
    w = sub.add_parser("widen")
    w.add_argument("id")
    w.add_argument("dest")
    w.add_argument("--out", default=str(DEFAULT_OUT))
    a = ap.parse_args()
    return {"pack": cmd_pack, "verify": cmd_verify, "widen": cmd_widen}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
