#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Retry runner for the AWS run of the C++ comparison (docs/cpp-comparison-plan.md, WP14 item 6).

usage: spot-retry.py [--status] [--detach] --regions r1,r2 --attempts N --wait S --budget-usd B --suite cpp
                     [--on-demand-after K] [--on-demand-price P] [--aws-spot PATH] -- <aws-spot.sh run arguments>

What it does, in this order:
  1. Pre-flight per region (aws-spot.sh preflight): a region that ends with exit 3 (no spot quota, no credentials) is dropped and
     named in the log. No region left: exit 3, nothing launched. Any other failure of a pre-flight (a usage error) stops: exit 1.
  2. Attempts: aws-spot.sh run --region <r> --budget-usd <remaining>, regions in turn, S seconds between attempts, at most N
     attempts. It retries ONLY on exit 4 (no capacity or no quota, nothing was launched). Any other exit code ends the loop and
     is returned. After K spot attempts (--on-demand-after) the attempts are on-demand, with --on-demand-price (default 0.53 USD/h) in
     place of --max-price. That is allowed by the owner and stays inside the cumulative cap below.
  3. Cumulative cap: before every attempt it adds up estimate_usd of every cloud-results/*/cost.tsv of the suite (a run directory of
     the suite without cost.tsv counts with the worst case of the next attempt), adds the worst case of the next attempt, and
     refuses the attempt if the sum is above --budget-usd (exit 5). Failed launches cost nothing and write no cost.tsv.

Long runs: --detach forks (os.fork, os.setsid) and writes everything to cloud-results/spot-retry-<time>.log; it prints the log
path and the PID and returns at once (the harness kills background jobs after about 10 minutes). The lock is
cloud-results/.spot-retry.lock (fcntl.flock, held for the whole run; the kernel frees it when the process dies, also on kill -9).
A second start prints "already running: pid=... since ..." and exits 75. --status: lock probe, then the status file
(cloud-results/.spot-retry.status, display only) and ps -o etime=; exit 75 if a run holds the lock, else "not running" and exit 0.
SIGINT and SIGTERM set a flag and forward SIGTERM to the running aws-spot.sh (its cleanup and verification run); between attempts the
flag stops the loop at once; then the summary is printed and the exit code is 130 or 143.

Exit codes: 0 a run succeeded; 1 a run failed (or the exit code of aws-spot.sh, if it is not 0 or 4); 2 usage; 3 no usable region;
4 no capacity after all attempts; 5 budget reached; 75 already running; 130/143 aborted.
The AWS profile and region come from the environment (AWS_PROFILE, AWS_REGION); nothing is read from or written to anything else.
Output of aws-spot.sh is passed through line by line.
"""
from __future__ import annotations

import fcntl
import os
import re
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
DEFAULT_ON_DEMAND_PRICE = 0.53   # USD per hour for g4dn.xlarge on demand (an assumption for the budget check only)

stop_signal = []          # signals received (the handler only records them)
child = {"proc": None}    # the running aws-spot.sh


def log(msg: str) -> None:
    print(f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}] {msg}", flush=True)


def usage(msg: str = "") -> None:
    if msg:
        print(f"spot-retry.py: {msg}", file=sys.stderr)
    print((__doc__ or "").split("Long runs:")[0], file=sys.stderr)
    sys.exit(2)


def results_dir() -> Path:
    return Path(os.environ.get("RESULTS_DIR") or ROOT / "cloud-results")


def parse_args(argv: list[str]) -> dict:
    o = {"status": False, "detach": False, "regions": [], "attempts": None, "wait": None, "budget": None, "suite": None,
         "on_demand_after": None, "on_demand_price": DEFAULT_ON_DEMAND_PRICE, "aws_spot": str(HERE / "aws-spot.sh"), "run_args": []}
    if "--" in argv:
        i = argv.index("--")
        argv, o["run_args"] = argv[:i], argv[i + 1:]
    it = iter(argv)
    for a in it:
        try:
            if a == "--status":
                o["status"] = True
            elif a == "--detach":
                o["detach"] = True
            elif a == "--regions":
                o["regions"] = [r for r in next(it).split(",") if r]
            elif a == "--attempts":
                o["attempts"] = int(next(it))
            elif a == "--wait":
                o["wait"] = float(next(it))
            elif a == "--budget-usd":
                o["budget"] = float(next(it))
            elif a == "--suite":
                o["suite"] = next(it)
            elif a == "--on-demand-after":
                o["on_demand_after"] = int(next(it))
            elif a == "--on-demand-price":
                o["on_demand_price"] = float(next(it))
            elif a == "--aws-spot":
                o["aws_spot"] = next(it)
            else:
                usage(f"unknown option {a}")
        except (StopIteration, ValueError):
            usage(f"missing or bad value for {a}")
    if o["status"]:
        return o
    for k in ("attempts", "wait", "budget", "suite"):
        if o[k] is None:
            usage(f"--{k.replace('budget', 'budget-usd')} is required")
    if not o["regions"]:
        usage("--regions is required")
    if not all(re.fullmatch(r"[a-z]{2}(-[a-z]+)+-\d", r) for r in o["regions"]):
        usage("--regions must be region names like eu-central-1, comma separated")
    if o["attempts"] < 1 or o["wait"] < 0 or o["budget"] <= 0:
        usage("--attempts >= 1, --wait >= 0 and --budget-usd > 0 are required")
    if o["suite"] != "cpp":
        usage("--suite must be cpp")
    if not o["run_args"] or o["run_args"][0] != "run":
        o["run_args"] = ["run"] + o["run_args"]
    for forbidden in ("--region", "--budget-usd", "--on-demand", "--suite"):
        if forbidden in o["run_args"]:
            usage(f"{forbidden} is set by spot-retry.py, not in the arguments after --")
    for need in ("--instance-type", "--max-price", "--max-minutes"):
        if need not in o["run_args"]:
            usage(f"the arguments after -- need {need}")
    return o


def arg_value(args: list[str], name: str) -> str:
    return args[args.index(name) + 1]


# --- status and lock ---------------------------------------------------------------------------------------------------
def etime(pid: str) -> str:
    out = subprocess.run(["ps", "-o", "etime=", "-p", pid], capture_output=True, text=True).stdout.strip()
    return out or "? (process gone)"


def status_info(status_file: Path) -> str:
    fields = {}
    try:
        for part in status_file.read_text().split():
            k, _, v = part.partition("=")
            fields[k] = v
    except OSError:
        return "pid=? (no status file)"
    pid = fields.get("pid", "?")
    return (f"pid={pid} since {fields.get('start', '?')} (running for {etime(pid)}), attempt {fields.get('attempt', '?')}, "
            f"region {fields.get('region', '?')}, run {fields.get('run', '-')}")


def write_status(status_file: Path, start: str, attempt: str, region: str, run_id: str) -> None:
    tmp = status_file.with_name(status_file.name + ".tmp")
    tmp.write_text(f"pid={os.getpid()} start={start} attempt={attempt} region={region} run={run_id or '-'}\n")
    os.replace(tmp, status_file)


# --- cost -------------------------------------------------------------------------------------------------------------------
def read_cost(path: Path):
    """(suite, estimate_usd) of a cost.tsv, or None if it is missing or malformed."""
    try:
        parts = path.read_text().strip().split("\t")
        return parts[0], float(parts[4])
    except (OSError, ValueError, IndexError):
        return None


def cumulative_cost(res: Path, suite: str, worst_next: float):
    """Sum of the estimates of every run of the suite; a run directory of the suite without cost.tsv counts with worst_next."""
    total, runs = 0.0, 0
    for d in sorted(res.glob("sh-*")):
        if not d.is_dir():
            continue
        c = read_cost(d / "cost.tsv")
        if c is not None:
            if c[0] == suite:
                total += c[1]
                runs += 1
            continue
        marker = d / "suite.txt"
        summary = d / "results" / "summary.txt"
        in_suite = (marker.is_file() and marker.read_text().strip() == suite) or \
                   (summary.is_file() and f"suite: {suite}" in summary.read_text())
        if in_suite:
            total += worst_next
            runs += 1
    return total, runs


def worst_case(price: float, minutes: float) -> float:
    return price * minutes / 60.0


# --- signals ------------------------------------------------------------------------------------------------------------
def on_signal(signum, _frame) -> None:
    stop_signal.append(signum)
    proc = child["proc"]
    if proc is not None and proc.poll() is None:
        try:
            proc.send_signal(signal.SIGTERM)    # its cleanup and verification run; we wait for it
        except OSError:
            pass


def interruptible_sleep(seconds: float) -> None:
    end = time.monotonic() + seconds
    while not stop_signal and time.monotonic() < end:
        time.sleep(min(0.2, max(0.0, end - time.monotonic())))


# --- the run ----------------------------------------------------------------------------------------------------------------
def run_child(cmd: list[str], on_run_id=None):
    """Runs aws-spot.sh, passes its output through, returns (exit code, run id or ''); on_run_id(id) is called when the id appears."""
    run_id = ""
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace", bufsize=1)
    child["proc"] = proc
    assert proc.stdout is not None
    for line in proc.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
        m = re.search(r"\brun (sh-\d{8}-\d{6}-\d{1,5})\b", line)
        if m and m.group(1) != run_id:
            run_id = m.group(1)
            if on_run_id is not None:
                on_run_id(run_id)
    rc = proc.wait()
    child["proc"] = None
    return rc, run_id


def main_loop(o: dict, res: Path, status_file: Path, start: str) -> int:
    aws = o["aws_spot"]
    run_args = list(o["run_args"])
    instance_type = arg_value(run_args, "--instance-type")
    max_price = float(arg_value(run_args, "--max-price"))
    minutes = float(arg_value(run_args, "--max-minutes"))
    rows = []   # (attempt, region, mode, exit code, run id, cost)

    def summary(final_code: int) -> int:
        spent, runs = cumulative_cost(res, o["suite"], worst_case(max_price, minutes))
        log("summary:")
        per_region = {}
        for _n, region, _mode, rc, _rid, _c in rows:
            per_region.setdefault(region, []).append(str(rc))
        for region, codes in per_region.items():
            log(f"  region {region}: {len(codes)} attempt(s), exit codes {', '.join(codes)}")
        for n, region, mode, rc, rid, c in rows:
            log(f"  attempt {n}: {region} {mode} exit {rc} run {rid or '-'} cost {('%.4f USD' % c) if c is not None else 'none (nothing launched)'}")
        remaining = o["budget"] - spent
        log(f"  cumulative cost of the {o['suite']} suite: {spent:.4f} USD over {runs} run(s) of the cap {o['budget']:.2f} USD "
            f"({remaining:.4f} USD left)")
        nxt = worst_case(max_price, minutes)
        if remaining < nxt:
            log(f"  another run would be refused (worst case {nxt:.2f} USD is above what is left): exit 5")
        else:
            log(f"  another run would start (worst case {nxt:.2f} USD, {remaining:.2f} USD left)")
        log(f"  exit code {final_code}")
        return final_code

    # 1. pre-flight per region
    usable = []
    for region in o["regions"]:
        pre = [aws, "preflight", "--instance-type", instance_type, "--region", region, "--max-price", str(max_price)]
        if "--backend" in run_args:
            pre += ["--backend", arg_value(run_args, "--backend")]
        log(f"pre-flight {region}")
        write_status(status_file, start, "preflight", region, "")
        rc, _ = run_child(pre)
        if stop_signal:
            return summary(128 + stop_signal[0])
        if rc == 0:
            usable.append(region)
        elif rc == 3:
            log(f"region {region} dropped: the pre-flight found a problem (exit 3, for example no spot quota)")
        else:
            log(f"the pre-flight of {region} failed with exit {rc} (not a finding): stopping")
            return summary(1)
    if not usable:
        log("no usable region: nothing was launched")
        return summary(3)
    log(f"regions in use: {', '.join(usable)}")

    # 2. attempts
    for n in range(1, o["attempts"] + 1):
        if stop_signal:
            return summary(128 + stop_signal[0])
        region = usable[(n - 1) % len(usable)]
        on_demand = o["on_demand_after"] is not None and n > o["on_demand_after"]
        price = o["on_demand_price"] if on_demand else max_price
        worst = worst_case(price, minutes)
        spent, _runs = cumulative_cost(res, o["suite"], worst)
        if spent + worst > o["budget"] + 1e-9:
            log(f"budget: {spent:.4f} USD spent + {worst:.4f} USD worst case of the next attempt is above {o['budget']:.2f} USD: refused")
            return summary(5)
        remaining = o["budget"] - spent
        budget_arg = "%.2f" % (int(remaining * 100) / 100.0)     # rounded down: never above what is left
        args = list(run_args)
        args[args.index("--max-price") + 1] = ("%g" % price)
        cmd = [aws] + args + ["--region", region, "--budget-usd", budget_arg, "--suite", o["suite"]]
        if on_demand:
            cmd.append("--on-demand")
        mode = "on-demand" if on_demand else "spot"
        log(f"attempt {n}/{o['attempts']} in {region} ({mode}), {spent:.4f} of {o['budget']:.2f} USD spent")
        write_status(status_file, start, f"{n}/{o['attempts']}", region, "")
        rc, run_id = run_child(cmd, lambda rid, n=n, region=region: write_status(status_file, start, f"{n}/{o['attempts']}", region, rid))
        cost = read_cost(res / run_id / "cost.tsv") if run_id else None
        rows.append((n, region, mode, rc, run_id, cost[1] if cost else None))
        log(f"attempt {n} ended with exit {rc}" + (f", run {run_id}" if run_id else ""))
        if stop_signal:
            return summary(128 + stop_signal[0])
        if rc == 0:
            return summary(0)
        if rc != 4:
            return summary(rc if rc > 0 else 1)
        if n == o["attempts"]:
            log("no capacity after all attempts: stop and ask the owner")
            return summary(4)
        log(f"no capacity or no quota (exit 4): waiting {o['wait']:g} s")
        interruptible_sleep(o["wait"])
    return summary(4)


def detach(log_path: Path) -> None:
    """Forks; the parent prints the log path and the PID and returns. The child keeps the lock fd and runs on in its own session."""
    sys.stdout.flush()
    pid = os.fork()
    if pid > 0:
        print(f"detached: log {log_path} pid {pid}", flush=True)
        os._exit(0)
    os.setsid()
    fd = os.open(str(log_path), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    devnull = os.open(os.devnull, os.O_RDONLY)
    os.dup2(devnull, 0)
    os.dup2(fd, 1)
    os.dup2(fd, 2)


def main(argv: list[str]) -> int:
    o = parse_args(argv)
    res = results_dir()
    res.mkdir(parents=True, exist_ok=True)
    lock_path, status_file = res / ".spot-retry.lock", res / ".spot-retry.status"
    lock = open(lock_path, "a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)      # the kernel frees it when the process dies
    except BlockingIOError:
        print("already running: " + status_info(status_file), file=sys.stderr)
        return 75
    start = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if o["detach"]:
        log_path = res / f"spot-retry-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}.log"
        detach(log_path)
    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)
    write_status(status_file, start, "starting", "-", "")
    log(f"started: pid {os.getpid()}, suite {o['suite']}, cap {o['budget']:.2f} USD, regions {','.join(o['regions'])}, "
        f"{o['attempts']} attempt(s), wait {o['wait']:g} s")
    return main_loop(o, res, status_file, start)


def status_main(argv: list[str]) -> int:
    """--status must report a running instance without taking the lock for good: probe, then read."""
    res = results_dir()
    res.mkdir(parents=True, exist_ok=True)
    lock = open(res / ".spot-retry.lock", "a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print("running: " + status_info(res / ".spot-retry.status"))
        return 75
    print("not running")
    return 0


if __name__ == "__main__":
    if "--status" in sys.argv[1:]:
        sys.exit(status_main(sys.argv[1:]))
    sys.exit(main(sys.argv[1:]))
