#!/usr/bin/env python3
"""Steps, time and wall energy of the training steps of the LARGE 5-row run, for the check of the CPU-against-GPU
estimate in docs/training.md ("Five-row LARGE net", "Check of the GPU estimate").

usage (from the repository root on the training machine):
  python3 models/runs/net5-large/net5large-analyze.py [RECORDS] [ENERGY_CSV]

RECORDS is the directory with gen0-metrics.jsonl and loop-metrics.jsonl (default: models/runs/net5-large).
ENERGY_CSV is the sampler file of the run (default: data/energy/net5-large.csv; git-ignored, on the training machine
only). Without it the script prints steps and times only.

Method: every "phase" event of the loop metrics carries its end time t (ms since the epoch) and its wall time; the window
[t - wall, t] of each phase is integrated over the counter steps of the sampler file with the functions of
scripts/energy/report.py. Gen0 has no phase events; its training time is the "train" cost event, and the proxy
evaluations are the "strength"/"proxy" cost events.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts" / "energy"))
import report  # noqa: E402  (scripts/energy/report.py)


def events(path: Path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def main() -> int:
    rec = Path(sys.argv[1] if len(sys.argv) > 1 else "models/runs/net5-large")
    csv = Path(sys.argv[2] if len(sys.argv) > 2 else "data/energy/net5-large.csv")
    pieces = None
    if csv.exists():
        segments, _, _ = report.build_segments(report.read_rows(csv), "wall", 3.0)
        pieces = report.pieces_of(segments)

    def kwh(a, b):
        if pieces is None:
            return None
        w = report.window(pieces, a, b)
        return w["e_uwh"] / 1e9, w["covered_s"]

    g0 = events(rec / "gen0-metrics.jsonl")
    train = next(e for e in g0 if e["event"] == "cost" and e["category"] == "train")
    proxies = [e for e in g0 if e["event"] == "cost" and e["category"] == "strength"]
    run = next(e for e in g0 if e["event"] == "run")
    print(f"gen0: {train['count']} steps, training {train['wall_ns'] / 1e9:.0f} s "
          f"({train['count'] / (train['wall_ns'] / 1e9):.1f} steps/s, {train['wall_ns'] / 1e6 / train['count']:.2f} ms/step), "
          f"{len(proxies)} proxy evaluations {sum(p['wall_ns'] for p in proxies) / 1e9:.0f} s, "
          f"whole Gen0 process {run['wall_ns'] / 1e9:.0f} s")

    loop = events(rec / "loop-metrics.jsonl")
    total_steps = total_s = 0
    total_kwh = 0.0
    per_phase = {}
    print("gen  train steps  train s  steps/s  train kWh  covered s")
    for e in loop:
        if e["event"] != "phase":
            continue
        end = e["t"] / 1000.0
        sec = e["wall_ns"] / 1e9
        k = kwh(end - sec, end)
        if k is not None:
            per_phase[e["phase"]] = per_phase.get(e["phase"], 0.0) + k[0]
        if e["phase"] != "train":
            continue
        total_steps += e["units"]
        total_s += sec
        if k is not None:
            total_kwh += k[0]
        print(f"{e['gen']:>3}  {e['units']:>11,}  {sec:>7.0f}  {e['units'] / sec:>7.1f}  "
              + (f"{k[0]:>9.4f}  {k[1]:>9.0f}" if k else "        -          -"))
    print(f"loop: {total_steps:,} training steps in {total_s:.0f} s ({total_steps / total_s:.1f} steps/s)"
          + (f", {total_kwh:.4f} kWh" if pieces is not None else ""))
    if per_phase:
        print("loop kWh per kind of phase: " + ", ".join(f"{p} {v:.4f}" for p, v in per_phase.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
