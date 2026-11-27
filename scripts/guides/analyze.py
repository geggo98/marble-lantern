#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Frequency and validity tables of the net-hints study (format: scripts/guides/README.md).

usage: analyze.py DIR [--json PATH] [--deltas 0.15,0.20,...] [--resamples N] [--seed S] [--cluster position|game]

Reads DIR/<variant>-<mode>-<game>.jsonl. A file counts only when its last line has end:true; others are listed and ignored.

(a) FREQUENCY, per variant and mode, for all positions and for balanced ones (|net value| < 0.5):
    tip  = the net favourite (most root visits) is classically fair, weak or poor;
    trap = the classically best move is not the favourite and its net value is at least delta below the favourite's.
    The net value of a move is its root value when it has at least `minVisits` visits, else its probe value (a search of its own).
(b) VALIDITY, from the counter-move lines (both alternatives played out to the end by classical players):
    tip : mean score of the favourite minus mean score of the classically best move, over the tip positions;
    trap: mean score of the classically best move (the trap) minus mean score of the favourite, over the trap positions of delta.
    A score is the mean of the playouts of all budgets for one alternative, from the view of the player who made the move.
    The interval is a percentile bootstrap (95 %, seeded) over positions, or over games with --cluster game.
    The mark is right when the tip difference is above 0 and the trap difference is below 0.
"""
import argparse
import glob
import json
import math
import os
import random
import sys
from collections import defaultdict

STEP = 30                       # one step of progress, as web/src/guides/bands.ts
MATE = 1_000_000
BALANCED = 0.5
DEFAULT_DELTAS = (0.15, 0.20, 0.25, 0.30, 0.40)


def norm(score):
    """A forced win or loss is exact: collapse the mate distance (bands.ts normaliseScore)."""
    return math.copysign(MATE, score) if abs(score) >= 999_000 else score


def band(loss):
    if loss <= STEP:
        return "top"
    if loss <= 3 * STEP:
        return "good"
    if loss <= 6 * STEP:
        return "fair"
    if loss <= 10 * STEP:
        return "weak"
    return "poor"


def read_lines(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for raw in f:
            raw = raw.strip()
            if not raw:
                continue
            try:
                out.append(json.loads(raw))
            except ValueError:
                return None            # a truncated line: the file is not verified
    return out


def is_verified(path):
    """The only state that counts as done: the last line parses and has end:true."""
    try:
        lines = read_lines(path)
    except OSError:
        return False
    return bool(lines) and lines[-1].get("end") is True


def parse_name(path):
    """'/x/r5-A-12.jsonl' -> ('r5', 'A', 12)"""
    stem = os.path.basename(path)[: -len(".jsonl")]
    variant, mode, game = stem.split("-")
    return variant, mode, int(game)


def net_value(pos, move):
    """Root value with at least minVisits visits, else the probe value; None when neither exists."""
    net = pos["net"]
    if move in net["moves"]:
        i = net["moves"].index(move)
        if net["visits"][i] >= pos["minVisits"]:
            return net["values"][i]
    for m, q in pos.get("probe", []):
        if m == move:
            return q
    return None


def digest(pos):
    """The facts of one position that the tables need: favourite, classical best, its band, both net values."""
    net, cls = pos["net"], pos["cls"]
    fav = net["moves"][net["visits"].index(max(net["visits"]))]
    scores = {m: norm(s) for m, s in zip(cls["moves"], cls["scores"])}
    cbest = max(scores, key=scores.get)
    best = scores[cbest]
    return {
        "ply": pos["ply"],
        "fav": fav,
        "cbest": cbest,
        "fav_band": band(max(0, best - scores[fav])) if fav in scores else None,
        "qfav": net_value(pos, fav),
        "qbest": net_value(pos, cbest),
        "balanced": abs(net["value"]) < BALANCED,
    }


def load(directory):
    """-> (groups, skipped): groups[(variant, mode)] = list of games; a game = {'name', 'positions', 'counters'}."""
    groups, skipped = defaultdict(list), []
    for path in sorted(glob.glob(os.path.join(directory, "*.jsonl"))):
        if not is_verified(path):
            skipped.append(path)
            continue
        variant, mode, game = parse_name(path)
        lines = read_lines(path)
        header = next((d for d in lines if d.get("header")), {})
        min_visits = header.get("minVisits", 16)
        positions, counters = [], {}
        for d in lines:
            if "end" in d or "header" in d:
                continue
            if d.get("counter"):
                counters[d["ply"]] = d
            else:
                d["minVisits"] = min_visits
                positions.append(digest(d))
        groups[(variant, mode)].append({"name": os.path.basename(path), "positions": positions, "counters": counters})
    return groups, skipped


def is_tip(p):
    return p["fav_band"] in ("fair", "weak", "poor")


def is_trap(p, delta):
    # the epsilon keeps a difference of exactly delta in the mark despite float error (0.40 - 0.25 is not exactly 0.15)
    return (p["cbest"] != p["fav"] and p["qbest"] is not None and p["qfav"] is not None
            and p["qbest"] <= p["qfav"] - delta + 1e-9)


def frequency(games, deltas):
    """Counts and rates for all positions and for balanced ones."""
    rows = {}
    for label, keep in (("all", lambda p: True), ("balanced", lambda p: p["balanced"])):
        ps = [p for g in games for p in g["positions"] if keep(p)]
        n = len(ps)
        tip = sum(is_tip(p) for p in ps)
        row = {"positions": n, "tip": tip, "tip_rate": tip / n if n else None, "trap": {}}
        for d in deltas:
            c = sum(is_trap(p, d) for p in ps)
            row["trap"][f"{d:.2f}"] = {"count": c, "rate": c / n if n else None}
        rows[label] = row
    return rows


def percentile(sorted_values, q):
    """Linear interpolation between the nearest ranks (q in [0, 1])."""
    if not sorted_values:
        return None
    k = q * (len(sorted_values) - 1)
    lo, hi = math.floor(k), math.ceil(k)
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (k - lo)


def cluster_bootstrap(clusters, resamples, seed_key):
    """clusters: list of lists of per-position differences. Mean over all positions, percentile 95 % interval of the mean
    when whole clusters are resampled with replacement. Deterministic for a given seed_key."""
    n = sum(len(c) for c in clusters)
    if n == 0:
        return {"n": 0, "mean": None, "lo": None, "hi": None}
    mean = sum(sum(c) for c in clusters) / n
    rng = random.Random(seed_key)
    sums = [(sum(c), len(c)) for c in clusters]
    k = len(sums)
    means = []
    for _ in range(resamples):
        s = m = 0
        for _ in range(k):
            a, b = sums[rng.randrange(k)]
            s += a
            m += b
        means.append(s / m if m else 0.0)
    means.sort()
    return {"n": n, "mean": mean, "lo": percentile(means, 0.025), "hi": percentile(means, 0.975)}


def alt_score(counter, move):
    """Mean over the budgets of the playout results of one alternative (a position's score for that move)."""
    row = counter["results"].get(str(move))
    if not row:
        return None
    return sum(row.values()) / len(row)


def validity(games, deltas, resamples, seed, cluster):
    """Tip and trap differences with bootstrap intervals, plus per-budget means for the point estimate."""
    def units(select, sign_move_a, sign_move_b):
        clusters, missing = [], 0
        for gi, g in enumerate(games):
            diffs = []
            for p in g["positions"]:
                if not select(p):
                    continue
                c = g["counters"].get(p["ply"])
                if c is None:
                    missing += 1
                    continue
                a = alt_score(c, p[sign_move_a])
                b = alt_score(c, p[sign_move_b])
                if a is None or b is None:
                    missing += 1
                    continue
                diffs.append(a - b)
            clusters.append((gi, diffs))
        if cluster == "game":
            return [d for _, d in clusters if d], missing
        return [[x] for _, d in clusters for x in d], missing

    out = {}
    cl, missing = units(is_tip, "fav", "cbest")
    out["tip"] = {**cluster_bootstrap(cl, resamples, f"boot|{seed}|tip"), "missing_counter": missing}
    out["trap"] = {}
    for d in deltas:
        cl, missing = units(lambda p, d=d: is_trap(p, d), "cbest", "fav")
        out["trap"][f"{d:.2f}"] = {**cluster_bootstrap(cl, resamples, f"boot|{seed}|trap|{d:.2f}"), "missing_counter": missing}
    return out


def fmt_rate(count, total):
    return f"{count:>4} ({count / total:6.1%})" if total else f"{count:>4} (   n/a)"


def fmt_ci(r):
    if r["n"] == 0:
        return "n=0"
    return f"n={r['n']:<4} diff {r['mean']:+.3f}  95% [{r['lo']:+.3f}, {r['hi']:+.3f}]"


def report(groups, skipped, deltas, resamples, seed, cluster):
    result = {"deltas": list(deltas), "resamples": resamples, "seed": seed, "cluster": cluster,
              "skipped_files": [os.path.basename(s) for s in skipped], "groups": {}}
    pooled = defaultdict(list)
    for (v, m), games in groups.items():
        pooled[v].extend(games)
    pooled_groups = {f"{v}-all": g for v, g in pooled.items()}
    pooled_groups["all-all"] = [g for games in groups.values() for g in games]
    for key, games in sorted({f"{v}-{m}": g for (v, m), g in groups.items()}.items()) + sorted(pooled_groups.items()):
        result["groups"][key] = {"games": len(games), "frequency": frequency(games, deltas),
                                 "validity": validity(games, deltas, resamples, seed, cluster)}
    return result


def print_report(result):
    deltas = result["deltas"]
    if result["skipped_files"]:
        print(f"ignored (no end line, i.e. not finished): {', '.join(result['skipped_files'])}")
    print("\n(a) FREQUENCY  tip = net favourite is classically fair/weak/poor; trap = classically best move at least delta below the favourite")
    head = "group".ljust(10) + "games positions  " + "tip".ljust(14) + "".join(f"trap {d:.2f}".ljust(16) for d in deltas)
    for label in ("all", "balanced"):
        print(f"\n[{label} positions{' (|net value| < 0.5)' if label == 'balanced' else ''}]")
        print(head)
        for key, g in result["groups"].items():
            f = g["frequency"][label]
            n = f["positions"]
            print(f"{key:<10}{g['games']:>5}{n:>10}  {fmt_rate(f['tip'], n):<14}" +
                  "".join(f"{fmt_rate(f['trap'][f'{d:.2f}']['count'], n):<16}" for d in deltas))
    print(f"\n(b) VALIDITY  cluster = {result['cluster']}, {result['resamples']} resamples, seed {result['seed']}")
    print("tip: score(favourite) - score(classical best), right when > 0.   trap: score(classical best) - score(favourite), right when < 0.")
    for key, g in result["groups"].items():
        v = g["validity"]
        print(f"\n[{key}]")
        print(f"  tip            {fmt_ci(v['tip'])}  (without counter line: {v['tip']['missing_counter']})")
        for d in deltas:
            r = v["trap"][f"{d:.2f}"]
            print(f"  trap d={d:.2f}   {fmt_ci(r)}  (without counter line: {r['missing_counter']})")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--json")
    ap.add_argument("--deltas", default=",".join(f"{d:.2f}" for d in DEFAULT_DELTAS))
    ap.add_argument("--resamples", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=20261127)
    ap.add_argument("--cluster", choices=("position", "game"), default="position")
    a = ap.parse_args(argv)
    deltas = tuple(float(x) for x in a.deltas.split(","))
    groups, skipped = load(a.dir)
    if not groups:
        print(f"analyze.py: no finished game file in {a.dir}", file=sys.stderr)
        return 1
    result = report(groups, skipped, deltas, a.resamples, a.seed, a.cluster)
    print_report(result)
    if a.json:
        tmp = f"{a.json}.tmp.{os.getpid()}"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=1, sort_keys=True)
        os.replace(tmp, a.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
