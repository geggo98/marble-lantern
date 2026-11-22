#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Energy report: per-phase energy from the sampler CSV and the phase markers (see scripts/energy/README.md).

usage: report.py [--name NAME] [--dir DIR] [--metric wall|system] [--baseline-minutes M] [--gap-factor F]
                 [--markdown PATH] [--json PATH] [--no-files]

Reads DIR/NAME.csv and DIR/NAME.markers.csv, prints Markdown on stdout and writes DIR/NAME.report.md and DIR/NAME.report.json
(--markdown / --json choose other paths, --no-files writes none). Default NAME: $ENERGY_NAME, else "default".

Method. The battery controller refreshes its energy counters about once a minute. The report therefore does not integrate the
power samples. It takes the cumulative counter (acc_wall_uwh for --metric wall, the default; acc_system_uwh for system), finds the
samples at which the counter changed, and places the energy of each counter step evenly between the previous and the current step
(the step time is the middle between the last unchanged and the first changed sample). Between two steps the mean power is the
energy of the step divided by its duration. A phase gets the share of each step that overlaps it. Resolution: about one minute.
Gaps: a stretch between two samples of more than F times the sampler interval (default F = 3), a sampler restart or a counter
reset ends a segment. No energy is attributed across the end of a segment; the report lists each gap with the counter change over it.
Baseline: the phase "baseline" (mean power over all its segments) if present with at least 2 minutes covered, else the lowest
mean power over M minutes (default 10) inside one segment. Energy above baseline = energy - baseline power * covered time.
Peak W = the highest step mean (one minute), not an instantaneous peak.
Exit codes: 0 report written, 1 no usable data, 2 wrong call.
"""
import argparse
import csv
import datetime
import json
import os
import sys
from pathlib import Path

UWH_TO_J = 3.6e-3          # 1 microwatt hour = 3.6 millijoule
UWH_PER_KWH = 1e9


def iso(epoch: float) -> str:
    return datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def hms(sec: float) -> str:
    sec = int(round(sec))
    return "%d:%02d:%02d" % (sec // 3600, sec % 3600 // 60, sec % 60)


def read_rows(path: Path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def build_segments(rows, metric: str, gap_factor: float):
    """Returns (segments, gaps, errors). A segment: dict(points=[(t, E_abs_uwh)], flagged=[t...], first, last).
    A gap: dict(start, end, seconds, energy_uwh or None, reason)."""
    col = "acc_wall_uwh" if metric == "wall" else "acc_system_uwh"
    segments, gaps, errors = [], [], 0
    interval = 5.0
    cur = None          # current segment under construction
    prev = None         # previous valid sample (t, E)

    def close(reason, at):
        nonlocal cur, prev
        if cur is not None and cur["first"] is not None:
            segments.append(cur)
            cur["reason_end"] = reason
        cur, prev = None, None

    def open_new():
        return {"points": [], "flagged": [], "first": None, "last": None}

    cur = open_new()
    for r in rows:
        kind = r["kind"]
        if kind == "start":
            close("restart", None)
            cur = open_new()
            try:
                interval = float(r["flag"].split("interval=")[1])
            except (IndexError, ValueError):
                interval = 5.0
            continue
        if kind in ("gap", "stop"):
            close(kind, None)
            cur = open_new()
            continue
        if kind == "error":
            errors += 1
            continue
        if kind != "sample" or not r.get(col):
            continue
        t, e = float(r["epoch"]), int(r[col])
        if r.get("flag") == "batt":
            cur["flagged"].append(t)
        if prev is not None:
            if t - prev[0] > gap_factor * interval:
                # the samples before it end a segment; this one starts the next
                flagged_now = r.get("flag") == "batt"
                close("gap", None)
                cur = open_new()
                if flagged_now:
                    cur["flagged"].append(t)
                prev = None
            elif e < prev[1]:
                close("counter reset", None)
                cur = open_new()
                prev = None
        if cur["first"] is None:
            cur["first"] = (t, e)
        cur["last"] = (t, e)
        if prev is not None and e != prev[1]:
            cur["points"].append(((prev[0] + t) / 2.0, e))     # step time: middle between the last unchanged and first changed sample
        prev = (t, e)
    close("end", None)

    for a, b in zip(segments, segments[1:]):
        ea = a["points"][-1][1] if a["points"] else a["last"][1]
        eb = b["points"][0][1] if b["points"] else b["first"][1]
        gaps.append({"start": a["last"][0], "end": b["first"][0], "seconds": b["first"][0] - a["last"][0],
                     "energy_uwh": eb - ea if eb >= ea else None, "reason": a.get("reason_end", "?")})
    return segments, gaps, errors


def pieces_of(segments, interval_guess: float = 5.0):
    """Counter steps as pieces: dict(t0, t1, e_uwh, w, batt)."""
    out = []
    for s in segments:
        pts = s["points"]
        for (t0, e0), (t1, e1) in zip(pts, pts[1:]):
            if t1 <= t0:
                continue
            flagged = any(t0 - interval_guess <= f <= t1 for f in s["flagged"])
            out.append({"t0": t0, "t1": t1, "e_uwh": e1 - e0, "w": (e1 - e0) * UWH_TO_J / (t1 - t0), "batt": flagged})
    out.sort(key=lambda p: p["t0"])
    return out


def window(pieces, a: float, b: float) -> dict:
    """Energy (uWh), covered seconds, peak W and battery-flagged seconds of the pieces inside [a, b]."""
    e = cov = batt = 0.0
    peak = None
    for p in pieces:
        lo, hi = max(a, p["t0"]), min(b, p["t1"])
        if hi <= lo:
            continue
        f = (hi - lo) / (p["t1"] - p["t0"])
        e += p["e_uwh"] * f
        cov += hi - lo
        batt += (hi - lo) if p["batt"] else 0.0
        peak = p["w"] if peak is None else max(peak, p["w"])
    return {"e_uwh": e, "covered_s": cov, "peak_w": peak, "batt_s": batt}


def read_markers(path: Path, rows, warnings):
    """Phase intervals: dict(phase, start, end, note). Rows of the sampler give the cut for an unclosed phase."""
    if not path.exists():
        return []
    events = sorted(((float(r["epoch"]), r["event"], r["phase"]) for r in read_rows(path)), key=lambda x: x[0])
    cuts = sorted(float(r["epoch"]) for r in rows if r["kind"] in ("gap", "stop"))
    samples = [float(r["epoch"]) for r in rows if r["kind"] == "sample"]
    last_t = samples[-1] if samples else 0.0
    open_, out = {}, []
    for t, ev, ph in events:
        if ev == "phase_start":
            if ph in open_:
                s = open_.pop(ph)
                after = [c for c in cuts if c > s]
                end = min([t] + after[:1])
                out.append({"phase": ph, "start": s, "end": end, "note": "unclosed (no phase_end before the next start or a sampler gap)"})
                warnings.append("phase %s at %s was not closed; ended at %s" % (ph, iso(s), iso(end)))
            open_[ph] = t
        elif ev == "phase_end":
            if ph not in open_:
                warnings.append("phase_end %s at %s without a start: ignored" % (ph, iso(t)))
                continue
            out.append({"phase": ph, "start": open_.pop(ph), "end": t, "note": ""})
    for ph, s in open_.items():
        end = max(s, last_t)
        out.append({"phase": ph, "start": s, "end": end, "note": "open (no phase_end yet; ended at the last sample)"})
        warnings.append("phase %s at %s is still open; ended at the last sample" % (ph, iso(s)))
    return sorted(out, key=lambda x: x["start"])


def baseline(pieces, segments, phase_rows, minutes: float):
    """(watts, description) or (None, reason)."""
    base = [p for p in phase_rows if p["phase"] == "baseline"]
    if base:
        e = sum(window(pieces, p["start"], p["end"])["e_uwh"] for p in base)
        c = sum(window(pieces, p["start"], p["end"])["covered_s"] for p in base)
        if c >= 120:
            return e * UWH_TO_J / c, "marker phase 'baseline' (%s covered, from %s)" % (hms(c), iso(base[0]["start"]))
    span = minutes * 60
    best = None
    for s in segments:
        for t0, _ in s["points"]:
            t1 = t0 + span
            if not s["points"] or t1 > s["points"][-1][0]:
                break
            w = window(pieces, t0, t1)
            mean = w["e_uwh"] * UWH_TO_J / w["covered_s"]
            if best is None or mean < best[0]:
                best = (mean, t0)
    if best:
        return best[0], "lowest %g-minute mean power in the file (window from %s)%s" % (
            minutes, iso(best[1]), "; the 'baseline' phase had too little data" if base else "")
    return None, "no baseline: no 'baseline' phase and no %g continuous minutes in the file" % minutes


def analyse(rows, marker_path: Path, metric="wall", minutes=10.0, gap_factor=3.0, name="default"):
    warnings = []
    segments, gaps, errors = build_segments(rows, metric, gap_factor)
    pieces = pieces_of(segments)
    if not pieces:
        raise ValueError("no usable data: fewer than two counter steps in a continuous stretch (a run needs a few minutes)")
    phases_raw = read_markers(marker_path, rows, warnings)
    base_w, base_text = baseline(pieces, segments, phases_raw, minutes)
    if base_w is None:
        warnings.append(base_text)

    def figures(e_uwh, covered):
        kwh = e_uwh / UWH_PER_KWH
        above = kwh - base_w * covered / 3.6e6 if base_w is not None else None
        return kwh, above, (e_uwh * UWH_TO_J / covered if covered else None)

    order, agg = [], {}
    for p in phases_raw:
        w = window(pieces, p["start"], p["end"])
        a = agg.get(p["phase"])
        if a is None:
            a = agg[p["phase"]] = {"phase": p["phase"], "start": p["start"], "segments": 0, "wall_s": 0.0, "covered_s": 0.0,
                                   "e_uwh": 0.0, "peak_w": None, "batt_s": 0.0, "notes": []}
            order.append(a)
        a["segments"] += 1
        a["wall_s"] += p["end"] - p["start"]
        a["covered_s"] += w["covered_s"]
        a["e_uwh"] += w["e_uwh"]
        a["batt_s"] += w["batt_s"]
        if w["peak_w"] is not None:
            a["peak_w"] = w["peak_w"] if a["peak_w"] is None else max(a["peak_w"], w["peak_w"])
        if p["note"]:
            a["notes"].append(p["note"])
    phases = []
    for a in order:
        kwh, above, mean = figures(a["e_uwh"], a["covered_s"])
        cov_share = a["covered_s"] / a["wall_s"] if a["wall_s"] else None
        notes = list(a["notes"])
        if cov_share is not None and cov_share < 0.9:
            notes.append("only %.0f%% of the phase has data (gap or less than a minute)" % (100 * cov_share))
        if a["batt_s"] > 0:
            notes.append("battery not idle for %.0f%% of the covered time" % (100 * a["batt_s"] / a["covered_s"]))
        phases.append({"phase": a["phase"], "start": iso(a["start"]), "start_epoch": a["start"], "segments": a["segments"],
                       "duration_s": a["wall_s"], "covered_s": a["covered_s"], "mean_w": mean, "peak_w": a["peak_w"],
                       "kwh": kwh, "kwh_above_baseline": above, "notes": notes})

    total = {"e_uwh": sum(p["e_uwh"] for p in pieces), "covered_s": sum(p["t1"] - p["t0"] for p in pieces),
             "batt_s": sum(p["t1"] - p["t0"] for p in pieces if p["batt"])}
    kwh, above, mean = figures(total["e_uwh"], total["covered_s"])
    nb = [a for a in order if a["phase"] != "baseline"]
    pk, pa, _ = figures(sum(a["e_uwh"] for a in nb), sum(a["covered_s"] for a in nb))
    if total["batt_s"] > 0:
        warnings.append("battery not idle for %.0f%% of the covered time: system power then differs from wall power (README)"
                        % (100 * total["batt_s"] / total["covered_s"]))
    if errors:
        warnings.append("%d samples could not be read (rows of kind error)" % errors)
    return {
        "name": name, "metric": metric, "baseline_w": base_w, "baseline_source": base_text,
        "file_start": iso(pieces[0]["t0"]), "file_end": iso(pieces[-1]["t1"]),
        "phases": phases,
        "phases_total": {"phases": [a["phase"] for a in nb], "kwh": pk, "kwh_above_baseline": pa,
                         "covered_s": sum(a["covered_s"] for a in nb)},
        "run_total": {"kwh": kwh, "kwh_above_baseline": above, "covered_s": total["covered_s"], "mean_w": mean,
                      "peak_w": max(p["w"] for p in pieces)},
        "gaps": [{"start": iso(g["start"]), "end": iso(g["end"]), "seconds": g["seconds"],
                  "kwh_counter_change": None if g["energy_uwh"] is None else g["energy_uwh"] / UWH_PER_KWH,
                  "reason": g["reason"]} for g in gaps],
        "warnings": warnings,
    }


def fnum(x, fmt, dash="-"):
    return dash if x is None else fmt % x


def markdown(r) -> str:
    L = ["# Energy report: %s" % r["name"], "",
         "Metric: %s (README: what it contains). Data from %s to %s." % (r["metric"], r["file_start"], r["file_end"]), ""]
    if r["baseline_w"] is None:
        L.append("Baseline: none (%s). Energy above baseline is not available." % r["baseline_source"])
    else:
        L.append("Baseline: %.2f W, from %s." % (r["baseline_w"], r["baseline_source"]))
    L += ["", "| phase | start (UTC) | duration | covered | mean W | peak W | kWh | kWh above baseline | notes |",
          "|---|---|---|---|---|---|---|---|---|"]
    for p in r["phases"]:
        L.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            p["phase"], p["start"], hms(p["duration_s"]), hms(p["covered_s"]), fnum(p["mean_w"], "%.1f"), fnum(p["peak_w"], "%.1f"),
            "%.4f" % p["kwh"], fnum(p["kwh_above_baseline"], "%.4f"), "; ".join(p["notes"]) or ""))
    t, pt = r["run_total"], r["phases_total"]
    L.append("| **sum of phases** (without baseline) | | | %s | | | **%.4f** | **%s** | |" % (
        hms(pt["covered_s"]), pt["kwh"], fnum(pt["kwh_above_baseline"], "%.4f")))
    L.append("| **whole file** (run %s) | %s | | %s | %.1f | %.1f | **%.4f** | **%s** | |" % (
        r["name"], r["file_start"], hms(t["covered_s"]), t["mean_w"], t["peak_w"], t["kwh"], fnum(t["kwh_above_baseline"], "%.4f")))
    L += ["", "Peak W is the highest one-minute mean. Duration is from the markers; covered is the time with data.", ""]
    if r["gaps"]:
        L += ["Gaps (no energy attributed to a phase across them; the counter change is the energy the counter saw meanwhile):", "",
              "| from (UTC) | to (UTC) | length | counter change kWh | segment ended by |", "|---|---|---|---|---|"]
        for g in r["gaps"]:
            L.append("| %s | %s | %s | %s | %s |" % (g["start"], g["end"], hms(g["seconds"]),
                                                   fnum(g["kwh_counter_change"], "%.4f", "counter reset"), g["reason"]))
        L.append("")
    for w in r["warnings"]:
        L.append("Warning: " + w)
    return "\n".join(L).rstrip() + "\n"


def main(argv) -> int:
    p = argparse.ArgumentParser(prog="report.py")
    p.add_argument("--name", default=os.environ.get("ENERGY_NAME", "default"))
    p.add_argument("--dir", type=Path, default=None)
    p.add_argument("--metric", choices=["wall", "system"], default="wall")
    p.add_argument("--baseline-minutes", type=float, default=10.0)
    p.add_argument("--gap-factor", type=float, default=3.0)
    p.add_argument("--markdown", type=Path)
    p.add_argument("--json", type=Path)
    p.add_argument("--no-files", action="store_true")
    a = p.parse_args(argv)
    d = a.dir or Path(os.environ.get("ENERGY_DIR") or Path(__file__).resolve().parents[2] / "data" / "energy")
    src = d / (a.name + ".csv")
    if not src.exists():
        print("report: no file %s" % src, file=sys.stderr)
        return 1
    try:
        res = analyse(read_rows(src), d / (a.name + ".markers.csv"), a.metric, a.baseline_minutes, a.gap_factor, a.name)
    except ValueError as e:
        print("report: %s" % e, file=sys.stderr)
        return 1
    md = markdown(res)
    sys.stdout.write(md)
    if not a.no_files:
        for path, text in ((a.markdown or d / (a.name + ".report.md"), md),
                           (a.json or d / (a.name + ".report.json"), json.dumps(res, indent=2) + "\n")):
            tmp = path.with_name(path.name + ".tmp")
            tmp.write_text(text, encoding="utf-8")
            os.replace(tmp, path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
