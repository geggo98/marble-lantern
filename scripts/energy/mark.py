#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Phase marker: appends a phase_start or phase_end row to DIR/NAME.markers.csv (see scripts/energy/README.md).

usage: mark.py [--name NAME] [--dir DIR] [--epoch T] start|end PHASE

  --name NAME   run name, default $ENERGY_NAME, else "default": the same name as the sampler's --name
  --dir DIR     default <repo>/data/energy, or $ENERGY_DIR
  --epoch T     Unix time of the event, default now (back-filling, tests)
  PHASE         [A-Za-z0-9_.-]+ ; the phase "baseline" is the idle baseline of the report

Columns: ts_utc, epoch, event (phase_start or phase_end), phase. One row is one write in append mode, so several drivers can
mark at once. The tool never fails a driver on the measurement side: with MARK_SOFT=1 a failure to write is a warning, exit 0.
Exit codes: 0 written, 1 the row could not be written, 2 wrong call.
"""
import argparse
import datetime
import fcntl
import os
import re
import sys
import time
from pathlib import Path


def main(argv) -> int:
    p = argparse.ArgumentParser(prog="mark.py")
    p.add_argument("--name", default=os.environ.get("ENERGY_NAME", "default"))
    p.add_argument("--dir", type=Path, default=None)
    p.add_argument("--epoch", type=float, default=None)
    p.add_argument("action", choices=["start", "end"])
    p.add_argument("phase")
    a = p.parse_args(argv)
    if not re.fullmatch(r"[\w.-]+", a.phase) or not re.fullmatch(r"[\w.-]+", a.name):
        p.error("name and phase: only [A-Za-z0-9_.-]")
    d = a.dir or Path(os.environ.get("ENERGY_DIR") or Path(__file__).resolve().parents[2] / "data" / "energy")
    t = a.epoch if a.epoch is not None else time.time()
    ts = datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    line = "%s,%.1f,phase_%s,%s\n" % (ts, t, a.action, a.phase)
    try:
        d.mkdir(parents=True, exist_ok=True)
        path = d / (a.name + ".markers.csv")
        with open(path, "a", encoding="utf-8") as f:
            fcntl.flock(f, fcntl.LOCK_EX)          # header and first row stay together when two markers start at once
            if f.tell() == 0:
                f.write("ts_utc,epoch,event,phase\n")
            f.write(line)
            f.flush()
    except OSError as e:
        print("mark: cannot write: %s" % e, file=sys.stderr)
        return 0 if os.environ.get("MARK_SOFT") == "1" else 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
