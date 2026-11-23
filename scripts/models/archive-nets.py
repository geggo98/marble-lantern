#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Copies the trained networks of the documented runs from data/ (git-ignored) into models/nets/ (Git LFS) and writes the
index models/index.json and models/index.md.

usage:
  archive-nets.py [--data DIR] [--out DIR] [--energy FILE] [--energy-large FILE] [--check]

Every distinct network file (by SHA-256) is stored once, as F32 safetensors with gzip -9, mtime 0 and no file name in the
header, so the same input gives the same bytes. A second source path with the same bytes (for example generation 0 of a
loop, which is the network the loop started from) becomes an index row that points to the stored file. A run directory
that is missing on this machine is reported and skipped; the index then lacks its rows (exit 1).

The index lists per network: the stored file, the source path under data/, run and documentation section, the gate
decision of a loop generation (data/<run>/gen-NNNN/decision.json), preset, plane set, players, board sizes (metadata key
triangle_rows, missing = 4), the variants the plane set can play, sizes, SHA-256 of the raw and of the stored file,
strength (measured figures for the networks the documentation reports, else the gate score) and kWh (only the two 5-row
runs measured energy: data/energy/net5.report.json for SMALL and data/energy/net5-large.report.json for LARGE; every
other row says "not measured").

--check: build everything in memory and compare with models/; exit 1 on any difference, 0 when all matches. Files are
written under a temporary name and renamed. A stored file whose bytes are already right is kept (SKIP). Exit codes:
0 ok, 1 a difference or a missing run, 2 wrong call.
"""
import argparse
import gzip
import hashlib
import json
import os
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
TRAINING = "docs/training.md"

# run directory under data/ -> (description, section of docs/training.md). Only runs that the documentation reports are
# archived; smoke and signal-test runs (net5-smoke, rev-g2, loopgpu1) are left out on purpose.
RUNS = {
    "gen0run1": ("Gen0 run 1: SMALL classic1 on teacher data", "generation-0-run-1-measured-2026-11-08"),
    "gen0run2": ("Gen0 run 2: SMALL classic1 on teacher data", "generation-0-run-2-measured-2026-11-08"),
    "gen0run3": ("Gen0 run 3 (E11): SMALL classic1 on 20,000 teacher games", "the-ab-measurement"),
    "loop1": ("Loop run 1: self-play from Gen0 run 3, 100 simulations", "loop-run-1-measured-2026-11-10"),
    "loop2": ("Loop run 2: constant learning rate, stalled", "loop-run-2-and-hyperparameter-sweep-1-measured"),
    "sweep1": ("Hyperparameter sweep 1 on loop run 2", "loop-run-2-and-hyperparameter-sweep-1-measured"),
    "loop3": ("Loop run 3: SMALL, the shipped 4-row expert", "loop-run-3-measured-2026-11-14"),
    "D1": ("Capacity diagnostics 1: offline capacity test", "capacity-diagnostics-1-measured"),
    "D2": ("Capacity diagnostics 2: 128,000-step offline training", "capacity-diagnostics-2-measured"),
    "loop4": ("Loop run 4: LARGE 64x6", "loop-run-4-measured-2026-11-14"),
    "loop5": ("Loop run 5", "loop-runs-5-to-7-and-the-end-of-training-measured-2026-11-15"),
    "loop6": ("Loop run 6", "loop-runs-5-to-7-and-the-end-of-training-measured-2026-11-15"),
    "loop7": ("Loop run 7", "loop-runs-5-to-7-and-the-end-of-training-measured-2026-11-15"),
    "sweep-loop7": ("Sweep on loop run 7", "loop-runs-5-to-7-and-the-end-of-training-measured-2026-11-15"),
    "shared": ("Shared net for four variants and its specialists (Gen0)", "shared-net-for-all-four-variants-measured-2026-11-19"),
    "net5": ("Five-row expert net: Gen0 and loop", "five-row-expert-net"),
}

# Parts of a run directory that the documentation reports in a section of their own (path prefix below data/ -> the same
# pair as in RUNS). The LARGE 5-row run shares data/net5 with the SMALL one: its teacher data, Gen0 and checks.
PARTS = {
    "net5/gen0-large/": ("Five-row LARGE net: Gen0 LARGE", "five-row-large-net"),
    "net5/loop-large/": ("Five-row LARGE net: loop", "five-row-large-net"),
}

# Measured figures that the documentation reports for single networks (source path -> text). Everything else gets the
# gate score of its loop generation, if it has one.
STRENGTH = {
    "loop3/gen-0013/candidate.safetensors":
        "shipped as small-2p-adjacent-expert; 4 rows, MCTS 200 sims, race on: +564 ± 96 / +490 ± 75 / +298 ± 46 Elo "
        "vs ab:6400 / 25600 / 102400 (BEST_SPEC)",
    "shared/gen0-shared/gen-0000/candidate.safetensors":
        "shipped as small-shared-all; 4 rows, 2 players adjacent: +225 / +69 / -123 Elo vs ab:1600 / 6400 / 25600; "
        "long hop: +191 / +102 vs ab:1600 / 6400; 3 players: wins 0.913 (adjacent) and 0.707 (long) vs 2 x ab3 at 5,000",
    "net5/gen0-small/gen-0000/candidate.safetensors":
        "5 rows, MCTS 200 sims vs the 5-row classical player: +402 ± 60 / +260 ± 42 / +131 ± 33 Elo vs ab:1600 / 6400 / "
        "25600 (E1, 200 pairs)",
    "net5/loop-small/gen-0009/candidate.safetensors":
        "shipped as small-2p-adjacent-r5-expert; 5 rows, MCTS 200 sims vs the 5-row classical player: +970 ± 193 / "
        "+798 ± 261 / +611 ± 112 / +512 ± 81 Elo vs ab:1600 / 6400 / 25600 / 102400 (E3, 200 pairs per rung); "
        "vs its Gen0: score 0.991, +822 ± 155 Elo (E2, 400 pairs)",
    "loop4/gen-0011/candidate.safetensors":
        "best network of loop run 4 (LARGE 64x6); 4 rows, 200 sims: +407 Elo vs ab:102400 (BEST_SPEC), ladder saturated; "
        "vs its generation 0: +78 ± 19 Elo (400 pairs)",
    "net5/gen0-large/gen-0000/candidate.safetensors":
        "5 rows, MCTS 200 sims vs the 5-row classical player: +503 ± 79 / +361 ± 52 / +242 ± 39 Elo vs ab:1600 / 6400 / "
        "25600 (E1, 200 pairs)",
    "net5/loop-large/gen-0010/candidate.safetensors":
        "best LARGE 5-row network, not shipped; 5 rows, MCTS 200 sims vs the 5-row classical player: +1162 ± 113 / "
        "+713 ± 168 / +798 ± 261 / +636 ± 125 Elo vs ab:1600 / 6400 / 25600 / 102400 (E3, 200 pairs per rung, ladder "
        "saturated); vs its Gen0: score 0.963, +565 ± 48 Elo (E2, 400 pairs); vs the shipped SMALL generation 9 at 200 "
        "sims each: score 0.680, +131 ± 24 Elo (400 pairs); at equal CPU time (SMALL at 1,000 sims): score 0.270, "
        "-173 ± 36 Elo (200 pairs)",
}


def fail(msg: str, code: int = 1):
    print(f"archive-nets: {msg}", file=sys.stderr)
    sys.exit(code)


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def metadata(raw: bytes) -> dict:
    (n,) = struct.unpack("<Q", raw[:8])
    header = json.loads(raw[8:8 + n].decode("utf-8"))
    params = sum(_count(v["shape"]) for k, v in header.items() if k != "__metadata__")
    meta = header.get("__metadata__", {})
    meta["_params"] = params
    return meta


def _count(shape) -> int:
    n = 1
    for d in shape:
        n *= d
    return n


def variants(meta: dict) -> list:
    """The variants the plane set can play, on the board sizes of the file (docs/network.md)."""
    rows = meta.get("triangle_rows", "4").split(",")
    jumps_players = [("adjacent", 2), ("long", 2), ("adjacent", 3), ("long", 3)] if meta.get("planes") == "classic1v" \
        else [("adjacent", int(meta.get("players", "2")))]
    return [f"{j}:{p}" + ("" if r == "4" else f":{r}") for r in rows for j, p in jumps_players]


def phase_kwh(report: Path) -> dict:
    return {p["phase"]: p["kwh"] for p in json.loads(report.read_text())["phases"]} if report.exists() else {}


def energy(report: Path, report_large: Path) -> dict:
    """kWh per 5-row network (wall, whole machine) from the energy reports of the two 5-row runs: the training phases that
    made it. The checks (pilot, ladder check, E1, E2, H2H, E3) are not counted."""
    phases = phase_kwh(report)
    out = energy_large(phase_kwh(report_large), phases.get("teacher"))
    if not phases:
        return out
    gen0 = phases.get("teacher", 0) + phases.get("gen0", 0)
    out["net5/gen0-small/gen-0000/candidate.safetensors"] = (gen0, "teacher data + Gen0")
    total = gen0
    for g in range(1, 11):
        phase = phases.get(f"loop-{g}")
        if phase is None:
            continue
        total += phase
        out[f"net5/loop-small/gen-{g:04d}/candidate.safetensors"] = (
            phase, f"loop generation {g}: self-play, training, gate; {total:.3f} kWh with teacher data, Gen0 and "
                   + (f"generations 1 to {g}" if g > 1 else "generation 1"))
    return out


def energy_large(phases: dict, teacher) -> dict:
    """The LARGE run: Gen0 LARGE is its phase gen0-large alone; it trained on the teacher data of the SMALL run, which the
    cumulative figure of a loop generation adds (teacher data + Gen0 LARGE + loop generations 1 to g), so that it is
    comparable with the SMALL rows."""
    if "gen0-large" not in phases:
        return {}
    gen0 = phases["gen0-large"]
    out = {"net5/gen0-large/gen-0000/candidate.safetensors": (gen0, "Gen0 LARGE only; the teacher data came from the "
                                                                    "SMALL run")}
    total = gen0 + (teacher or 0)
    for g in range(1, 11):
        phase = phases.get(f"loop-large-{g}")
        if phase is None:
            continue
        total += phase
        out[f"net5/loop-large/gen-{g:04d}/candidate.safetensors"] = (
            phase, f"loop generation {g}: self-play, training, gate; {total:.3f} kWh with the teacher data, Gen0 LARGE "
                   f"and generations 1 to {g}" if g > 1 else
            f"loop generation 1: self-play, training, gate; {total:.3f} kWh with the teacher data, Gen0 LARGE and "
            f"generation 1")
    return out


def decision(src: Path) -> str:
    d = src.parent / "decision.json"
    if not d.exists():
        return ""
    j = json.loads(d.read_text())
    if j.get("init"):
        return "initial network of the loop"
    if "score" not in j:
        return str(j.get("decision") or "")
    return f"{j['decision']}, gate {j['score']:.3f} vs gen {j.get('best_before')}"


def stored_name(run: str, rel: Path) -> str:
    """data/<run>/a/b/candidate.safetensors -> <run>/a-b.safetensors.gz"""
    parts = [p for p in rel.parent.parts]
    return f"{run}/{'-'.join(parts)}.safetensors.gz"


def build(data: Path, energy_file: Path, energy_large: Path):
    rows, files, missing = [], {}, []
    kwh = energy(energy_file, energy_large)
    for run, (what, section) in RUNS.items():
        base = data / run
        if not base.is_dir():
            missing.append(run)
            continue
        for src in sorted(base.rglob("candidate.safetensors")):
            rel = src.relative_to(base)
            key = f"{run}/{rel}"
            raw = src.read_bytes()
            h = sha(raw)
            if h not in files:
                files[h] = (stored_name(run, rel), gzip.compress(raw, compresslevel=9, mtime=0))
            name, gz = files[h]
            meta = metadata(raw)
            strength = STRENGTH.get(key, "")
            gate = decision(src)
            e = kwh.get(key)
            what_part, section_part = next((v for k, v in PARTS.items() if key.startswith(k)), (what, section))
            rows.append({
                "file": f"nets/{name}",
                "source": f"data/{key}",
                "run": run,
                "runDescription": what_part,
                "doc": f"{TRAINING}#{section_part}",
                "gate": gate,
                "preset": f"{meta.get('channels')}x{meta.get('blocks')}",
                "planeSet": meta.get("planes", "plain"),  # format version 1 files have no key: the plain plane set
                "players": int(meta.get("players", "2")),
                "rows": meta.get("triangle_rows", "4"),
                "variants": variants(meta),
                "params": meta["_params"],
                "rawBytes": len(raw),
                "storedBytes": len(gz),
                "sha256Raw": h,
                "sha256Stored": sha(gz),
                "strength": strength or ("not measured beyond the gate" if gate and "gate" in gate else "not measured"),
                "kWh": round(e[0], 4) if e else "not measured",
                "kWhNote": e[1] if e else "",
            })
    return rows, files, missing


def markdown(rows: list) -> str:
    out = [
        "<!-- generated by scripts/models/archive-nets.py; do not edit -->",
        "# Index of the archived networks",
        "",
        f"{len(rows)} source files, {len({r['sha256Raw'] for r in rows})} distinct networks. "
        "Columns and how the file is made: [README.md](README.md).",
        "",
        "| file | source | run | preset, planes | rows | variants | stored / raw bytes | SHA-256 (raw) | gate | strength | kWh |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        k = r["kWh"] if isinstance(r["kWh"], str) else f"{r['kWh']} ({r['kWhNote']})"
        out.append(
            f"| [{Path(r['file']).name}]({r['file']}) | `{r['source']}` | [{r['run']}](../{r['doc']}) | {r['preset']} {r['planeSet']} "
            f"| {r['rows']} | {', '.join(r['variants'])} | {r['storedBytes']:,} / {r['rawBytes']:,} | `{r['sha256Raw'][:16]}…` "
            f"| {r['gate'] or '–'} | {r['strength']} | {k} |")
    return "\n".join(out) + "\n"


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", default=str(ROOT / "data"))
    ap.add_argument("--out", default=str(ROOT / "models"))
    ap.add_argument("--energy", default=None, help="energy report JSON of the 5-row run (default: <data>/energy/net5.report.json)")
    ap.add_argument("--energy-large", default=None,
                    help="energy report JSON of the LARGE 5-row run (default: <data>/energy/net5-large.report.json)")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    data, out = Path(a.data), Path(a.out)
    rows, files, missing = build(data, Path(a.energy) if a.energy else data / "energy/net5.report.json",
                                 Path(a.energy_large) if a.energy_large else data / "energy/net5-large.report.json")
    index = {"v": 1, "networks": rows}
    texts = {out / "index.json": (json.dumps(index, indent=1, ensure_ascii=False) + "\n").encode(),
             out / "index.md": markdown(rows).encode()}
    ok = skip = diff = 0
    for name, gz in files.values():
        path = out / "nets" / name
        if path.exists() and path.read_bytes() == gz:
            skip += 1
        elif a.check:
            diff += 1
            print(f"archive-nets: {path} differs or is missing", file=sys.stderr)
        else:
            atomic_write(path, gz)
            ok += 1
    for path, text in texts.items():
        if path.exists() and path.read_bytes() == text:
            skip += 1
        elif a.check:
            diff += 1
            print(f"archive-nets: {path} differs or is missing", file=sys.stderr)
        else:
            atomic_write(path, text)
            ok += 1
    for run in missing:
        print(f"archive-nets: data/{run} is missing on this machine; its networks are not in the index", file=sys.stderr)
    stored = sum(len(g) for _, g in files.values())
    print(f"archive-nets: {len(rows)} source files, {len(files)} distinct networks, {stored:,} bytes stored; "
          f"{ok} written, {skip} unchanged, {diff} differences, {len(missing)} runs missing", file=sys.stderr)
    return 1 if diff or missing else 0


if __name__ == "__main__":
    sys.exit(main())
