#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Writes the job list of the net-hints study: one line `<variant> <mode> <game>` per game.

usage: jobs.py --variants r5,r4 --modes A,B --games N [--first K] [--out FILE]

The games are 0..N-1 (or K..N-1 with --first K, to extend a study). The order is game by game, then variant, then mode, so that a
run stopped halfway leaves every group with about the same number of games. Without --out the list goes to stdout.
"""
import argparse
import os
import sys

KNOWN_VARIANTS = ("r5", "r4")


def make_jobs(variants, modes, games, first=0):
    return [(v, m, g) for g in range(first, games) for v in variants for m in modes]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--variants", default="r5,r4")
    ap.add_argument("--modes", default="A,B")
    ap.add_argument("--games", type=int, required=True, help="games per variant and mode")
    ap.add_argument("--first", type=int, default=0)
    ap.add_argument("--out")
    a = ap.parse_args(argv)
    variants = [x for x in a.variants.split(",") if x]
    modes = [x for x in a.modes.split(",") if x]
    bad = [v for v in variants if v not in KNOWN_VARIANTS]
    if bad or not variants or not modes or a.games <= a.first or a.first < 0 or any("-" in m or " " in m for m in modes):
        print(f"jobs.py: wrong call (variants {variants}, modes {modes}, games {a.games}, first {a.first}; known variants {KNOWN_VARIANTS})",
              file=sys.stderr)
        return 2
    text = "".join(f"{v} {m} {g}\n" for v, m, g in make_jobs(variants, modes, a.games, a.first))
    if a.out:
        tmp = f"{a.out}.tmp.{os.getpid()}"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(text)
        os.replace(tmp, a.out)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
