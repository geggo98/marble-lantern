#!/usr/bin/env python3
"""Per-phase power drift (minute means from the wall counter) and step-time statistics from the Gen0 logs.
usage: cpupower-analyze.py ENERGY_NAME EXP_DIR"""
import csv, re, statistics as st, sys
from pathlib import Path

name, exp = sys.argv[1], Path(sys.argv[2])
edir = Path("data/energy")   # run from the repository root
rows = [r for r in csv.DictReader(open(edir / f"{name}.csv")) if r["kind"] == "sample" and r["acc_wall_uwh"]]
marks = list(csv.DictReader(open(edir / f"{name}.markers.csv")))
# counter steps: (time, cumulative uWh); time = middle between last unchanged and first changed sample
steps = []
for a, b in zip(rows, rows[1:]):
    if b["acc_wall_uwh"] != a["acc_wall_uwh"]:
        steps.append(((float(a["epoch"]) + float(b["epoch"])) / 2, float(b["acc_wall_uwh"])))
minutes = [(t0, t1, (e1 - e0) * 3.6e-3 / (t1 - t0)) for (t0, e0), (t1, e1) in zip(steps, steps[1:])]
phases = {}
for m in marks:
    phases.setdefault(m["phase"], {})[m["event"]] = float(m["epoch"])

def parse_elapsed(s):
    sec = 0
    for v, u in re.findall(r"(\d+)([hms])", s):
        sec += int(v) * {"h": 3600, "m": 60, "s": 1}[u]
    return sec

for ph, ev in phases.items():
    if "phase_end" not in ev:
        continue
    a, b = ev["phase_start"], ev["phase_end"]
    ins = [w for t0, t1, w in minutes if t0 >= a and t1 <= b]       # whole minutes inside the phase
    if not ins:
        continue
    h = len(ins) // 2
    first, second = ins[:h] or ins, ins[h:]
    line = (f"{ph:15s} minutes {len(ins):2d}  mean {st.mean(ins):6.2f} W  sd(min) {st.pstdev(ins):5.2f}  "
            f"first half {st.mean(first):6.2f}  second half {st.mean(second):6.2f}  drift {st.mean(second) - st.mean(first):+6.2f} W")
    log = exp / ph / "gen0.log"
    if log.exists():
        pts = []
        for l in open(log, errors="replace"):
            m = re.search(r"gen 0 train: (\d+)/\d+ steps .*elapsed (\S+)", l)
            if m:
                pts.append((parse_elapsed(m.group(2)), int(m.group(1))))
        pts = [p for p in pts if p[0] >= 30]                          # skip start-up (JVM, data load, first proxies)
        if len(pts) >= 3:
            (e0, s0), (e1, s1) = pts[0], pts[-1]
            sps = (e1 - e0) / (s1 - s0) if s1 > s0 else float("nan")
            iv = [(b2[0] - a2[0]) / (b2[1] - a2[1]) for a2, b2 in zip(pts, pts[1:]) if b2[1] > a2[1]]
            hh = len(pts) // 2
            r1 = (pts[hh][0] - e0) / max(1, pts[hh][1] - s0); r2 = (e1 - pts[hh][0]) / max(1, s1 - pts[hh][1])
            line += (f"\n{'':15s} steps {s0}..{s1} in {e1 - e0} s: {sps * 1000:8.2f} ms/step ({1 / sps:6.2f} steps/s); "
                     f"interval sd {st.pstdev(iv) * 1000 if len(iv) > 1 else float('nan'):7.2f} ms over {len(iv)} intervals; "
                     f"halves {r1 * 1000:.2f} / {r2 * 1000:.2f} ms/step")
        else:
            line += f"\n{'':15s} {len(pts)} progress points after 30 s: " + str(pts)
    print(line)
