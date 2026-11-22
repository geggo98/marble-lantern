#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Energy sampler: writes the power telemetry of the Mac's battery controller to an append-only CSV (see scripts/energy/README.md).

usage: sampler.py [--status] [--detach] [--name NAME] [--interval S] [--duration S] [--dir DIR]

  --name NAME     run name, default $ENERGY_NAME, else "default"; the file is DIR/NAME.csv (DIR default: <repo>/data/energy, or $ENERGY_DIR)
  --interval S    seconds between samples, default 5 (the controller refreshes its counters about once a minute; see README)
  --duration S    stop by itself after S seconds (tests, checks); default: run until SIGINT or SIGTERM
  --detach        fork, setsid, write stdout and stderr to DIR/NAME.log, print the log path and return at once
  --status        print whether a sampler runs for NAME: exit 75 = running (pid, elapsed), 0 = not running
  --stop          send SIGTERM to the running sampler of NAME and wait up to 15 s for it to end: exit 0 stopped or not running, 1 still running

One row per sample: kind=sample. Other kinds: start (a sampler started, flag interval=S), gap (written at a restart, when the
file already has rows: flag previous_epoch=...), stop (clean end), error (a sample that could not be read; no values, the reason
in flag; the report treats it as missing data).
Columns: ts_utc, epoch, kind, system_power_mw (SystemPowerIn), battery_power_mw, external (1 = adapter connected), charging (1 = charging),
battery_pct, acc_wall_uwh (AccumulatedWallEnergyEstimate, microwatt hours at the wall socket), acc_system_uwh
(AccumulatedSystemEnergyConsumed, microwatt hours after the adapter), acc_count (SystemPowerInAccumulatorCount, seconds),
loadavg1, flag. The flag "batt" marks a sample in which the battery was not idle on mains power (see README).

Job conventions (docs/training.md, "Job conventions"): lock by flock on DIR/.NAME.lock (the kernel frees it when the process dies,
also on kill -9), 75 when it is held, SIGINT and SIGTERM end the loop between samples (a clean stop row, exit 130 or 143), a restart
appends and writes a gap row, progress line on stderr once a minute. There is no ETA: the sampler has no planned end.
Exit codes: 0 done (--duration) or not running (--status), 1 the telemetry cannot be read (the first 5 samples failed),
2 wrong call, 75 running already, 130/143 stopped.
"""
import argparse
import datetime
import fcntl
import os
import re
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

COLUMNS = ["ts_utc", "epoch", "kind", "system_power_mw", "battery_power_mw", "external", "charging", "battery_pct",
           "acc_wall_uwh", "acc_system_uwh", "acc_count", "loadavg1", "flag"]
IOREG = os.environ.get("ENERGY_IOREG", "ioreg -rn AppleSmartBattery").split()   # tests replace it by a stub
PROGRESS_EVERY = 60.0
stop = threading.Event()
stop_signal = []


def default_dir() -> Path:
    return Path(os.environ.get("ENERGY_DIR") or Path(__file__).resolve().parents[2] / "data" / "energy")


def iso(epoch: float) -> str:
    return datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def signed64(v: int) -> int:
    return v - (1 << 64) if v >= 1 << 63 else v


# matches:   "PowerTelemetryData" = {"SystemPowerIn"=12370,"WallEnergyEstimate"=3607,...}
TELEMETRY = re.compile(r'"PowerTelemetryData"\s*=\s*\{(?P<body>[^}]*)\}')


def top_level(text: str, key: str):
    # matches:   "ExternalConnected" = Yes   (at line start: "AppleRawExternalConnected" must not match)
    m = re.search(r'^\s*"%s"\s*=\s*(?P<v>\S+)' % key, text, re.M)
    return m["v"] if m else None


def parse_ioreg(text: str) -> dict:
    """The fields of one reading; raises ValueError with the reason when the text has no usable telemetry."""
    m = TELEMETRY.search(text)
    if not m:
        raise ValueError("no PowerTelemetryData in the ioreg output")
    t = {k: int(v) for k, v in re.findall(r'"(\w+)"=(-?\d+)', m["body"])}
    for k in ("SystemPowerIn", "AccumulatedWallEnergyEstimate", "AccumulatedSystemEnergyConsumed", "SystemPowerInAccumulatorCount"):
        if k not in t:
            raise ValueError("PowerTelemetryData has no " + k)
    ext, chg, pct = top_level(text, "ExternalConnected"), top_level(text, "IsCharging"), top_level(text, "CurrentCapacity")
    return {"system_power_mw": t["SystemPowerIn"], "battery_power_mw": signed64(t.get("BatteryPower", 0)),
            "external": 1 if ext == "Yes" else 0, "charging": 1 if chg == "Yes" else 0,
            "battery_pct": int(pct) if pct and pct.isdigit() else "",
            "acc_wall_uwh": t["AccumulatedWallEnergyEstimate"], "acc_system_uwh": t["AccumulatedSystemEnergyConsumed"],
            "acc_count": t["SystemPowerInAccumulatorCount"]}


def read_sample() -> dict:
    try:
        out = subprocess.run(IOREG, capture_output=True, text=True, timeout=20)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise ValueError("ioreg failed: %s" % e)
    if out.returncode != 0:
        raise ValueError("ioreg exit %d" % out.returncode)
    return parse_ioreg(out.stdout)


def batt_flag(s: dict) -> str:
    # not on mains, charging, or the battery moves more than 1 W: the system power then differs from the wall power
    return "batt" if (not s["external"] or s["charging"] or abs(s["battery_power_mw"]) > 1000) else ""


class Writer:
    def __init__(self, path: Path):
        new = not path.exists() or path.stat().st_size == 0
        self.f = open(path, "a", encoding="utf-8")
        if new:
            self.f.write(",".join(COLUMNS) + "\n")
            self.f.flush()

    def row(self, kind: str, epoch: float, flag: str = "", **v) -> None:
        d = {"ts_utc": iso(epoch), "epoch": "%.1f" % epoch, "kind": kind, "flag": flag, **v}
        self.f.write(",".join(str(d.get(c, "")) for c in COLUMNS) + "\n")
        self.f.flush()


def last_epoch(path: Path):
    """Epoch of the last data row of an existing file, or None (no file, header only, unparsable last row)."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
        return float(lines[-1].split(",")[1]) if len(lines) > 1 else None
    except (OSError, ValueError, IndexError):
        return None


def etime(pid: str) -> str:
    r = subprocess.run(["ps", "-o", "etime=", "-p", pid], capture_output=True, text=True)
    return r.stdout.strip() or "? (process gone)"


def status_text(statusf: Path) -> str:
    try:
        pid = statusf.read_text().split()[0].removeprefix("pid=")
    except (OSError, IndexError):
        return "pid=? elapsed ?"
    return "pid=%s running for %s" % (pid, etime(pid))


def stop_running(name: str, statusf: Path, lock_path: Path) -> int:
    try:
        pid = int(statusf.read_text().split()[0].removeprefix("pid="))
    except (OSError, IndexError, ValueError):
        print("energy %s: running, but the status file has no pid; stop it by hand" % name, file=sys.stderr)
        return 1
    os.kill(pid, signal.SIGTERM)
    deadline = time.monotonic() + 15
    with open(lock_path, "a") as lk:
        while time.monotonic() < deadline:
            try:
                fcntl.flock(lk, fcntl.LOCK_EX | fcntl.LOCK_NB)
                print("energy %s: stopped (pid %d)" % (name, pid))
                return 0
            except BlockingIOError:
                time.sleep(0.2)
    print("energy %s: pid %d did not end within 15 s" % (name, pid), file=sys.stderr)
    return 1


def on_signal(signum, _frame) -> None:
    stop_signal.append(signum)
    stop.set()


def detach(log_path: Path, keep_fd: int) -> None:
    sys.stdout.flush()
    if os.fork():
        print("detached: log %s" % log_path, flush=True)
        os._exit(0)      # the child keeps the lock: both share one open file description
    os.setsid()
    # a parent (the training driver) may have handed us its own lock descriptor; a long-lived sampler must not keep that lock held
    for inherited in range(3, 64):
        if inherited != keep_fd:
            try:
                os.close(inherited)
            except OSError:
                pass
    fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
    os.dup2(fd, 1)
    os.dup2(fd, 2)
    os.close(0)


def run(a, csv: Path, statusf: Path) -> int:
    existing = last_epoch(csv)
    w = Writer(csv)
    t_start = time.time()
    if existing is not None:
        w.row("gap", t_start, "previous_epoch=%.1f" % existing)
    w.row("start", t_start, "interval=%g" % a.interval)
    statusf.write_text("pid=%d start=%s name=%s\n" % (os.getpid(), iso(t_start), a.name))
    ok = err = bad_streak = 0
    first = last = None
    last_progress = time.monotonic()
    nxt = time.monotonic()
    code = 0
    while not stop.is_set():
        now = time.time()
        try:
            s = read_sample()
            bad_streak = 0
            ok += 1
            first = first or s
            last = s
            w.row("sample", now, batt_flag(s), **s, loadavg1="%.2f" % os.getloadavg()[0])
        except ValueError as e:
            err += 1
            bad_streak += 1
            w.row("error", now, str(e).replace(",", ";"))
            if ok == 0 and bad_streak >= 5:
                print("energy %s: cannot read the telemetry: %s" % (a.name, e), file=sys.stderr)
                code = 1
                break
        if time.monotonic() - last_progress >= PROGRESS_EVERY and first:
            last_progress = time.monotonic()
            d_wh = (last["acc_wall_uwh"] - first["acc_wall_uwh"]) / 1e6
            el = now - t_start
            print("energy %s: %d samples, %d errors, system %.1f W now, wall energy since start %.4f Wh (mean %.1f W), elapsed %d s"
                  % (a.name, ok, err, last["system_power_mw"] / 1000, d_wh, d_wh * 3600 / el if el else 0, el),
                  file=sys.stderr, flush=True)
        if a.duration and time.time() - t_start >= a.duration:
            break
        nxt += a.interval
        stop.wait(max(0.0, nxt - time.monotonic()))
    w.row("stop", time.time(), "signal=%d" % stop_signal[0] if stop_signal else "")
    print("energy %s: %d ok, 0 skip, %d error, 0 left%s" % (a.name, ok, err, " - stopped" if stop_signal else " - done"),
          file=sys.stderr, flush=True)
    statusf.unlink(missing_ok=True)
    return 128 + stop_signal[0] if stop_signal else code


def main(argv) -> int:
    p = argparse.ArgumentParser(prog="sampler.py")
    p.add_argument("--name", default=os.environ.get("ENERGY_NAME", "default"))
    p.add_argument("--interval", type=float, default=5.0)
    p.add_argument("--duration", type=float, default=0.0)
    p.add_argument("--dir", type=Path, default=None)
    p.add_argument("--detach", action="store_true")
    p.add_argument("--status", action="store_true")
    p.add_argument("--stop", action="store_true")
    a = p.parse_args(argv)
    if a.interval <= 0 or not re.fullmatch(r"[\w.-]+", a.name):
        p.error("interval must be positive and name only [A-Za-z0-9_.-]")
    d = a.dir or default_dir()
    d.mkdir(parents=True, exist_ok=True)
    csv, statusf = d / (a.name + ".csv"), d / (".%s.status" % a.name)
    lock_path = d / (".%s.lock" % a.name)
    lock = open(lock_path, "a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        if a.stop:
            return stop_running(a.name, statusf, lock_path)
        print("energy %s: running already: %s" % (a.name, status_text(statusf)), file=sys.stderr)
        return 75
    if a.status or a.stop:
        print("energy %s: not running" % a.name)
        return 0
    if a.detach:
        detach(d / (a.name + ".log"), lock.fileno())
    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)
    return run(a, csv, statusf)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
