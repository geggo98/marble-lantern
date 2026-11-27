#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Driver of the net-hints study: runs the games of a job list with N parallel workers (README.md in this directory).

usage: run.py JOBS --out DIR [--workers N] [--max-plies P] [--runner CMD] [--detach] [--progress-seconds S]
       run.py --status --out DIR

JOBS holds one `<variant> <mode> <game>` per line (jobs.py writes it). Per job the driver starts web/tooling/nethints-sim.test.ts
through vitest, which writes DIR/<variant>-<mode>-<game>.jsonl atomically (temp name, then rename).

STATE comes from the work products only: a job is SKIP when its file exists and its last line has end:true. A file without that
line (truncated or foreign) is not done: the job runs again and replaces it. COUNTERS, never mixed: ok = jobs finished by this run,
skip = jobs done before, error = jobs whose process failed or left no verified file, cleanup = leftover *.tmp.* files of an aborted
run, removed at the start. The summary line prints all four, the jobs left and what another run would do.
EXIT CODES: 0 done or nothing to do, 1 at least one error, 2 wrong call, 75 runs already, 130 / 143 stopped by SIGINT / SIGTERM
(running games are signalled and discarded; a game that was not finished leaves no file).

--status     tell whether a run is going (exit 75 with pid, start, elapsed time) or not (exit 0).
--max-plies  stop every game after P plies (smoke tests only; such a file is complete but short).
--runner     command that plays one game, default `<repo>/web/node_modules/.bin/vitest run tooling/nethints-sim.test.ts` run in
             <repo>/web. The job arrives in the environment: NETHINTS_SIM=1, NETHINTS_VARIANT, NETHINTS_MODE, NETHINTS_GAME, NETHINTS_OUT.
--detach     fork, setsid, stderr to DIR/run.log (path printed), for runs of hours that the agent harness would kill.

LONG RUN rules (owner's rule long-running-scripts): lock = flock on DIR/.run.lock (the kernel frees it on kill -9), status file
DIR/.run.status only for display, progress lines with ETA on stderr, one line per second at most per --progress-seconds.
"""
import argparse
import fcntl
import glob
import os
import shlex
import signal
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(os.path.dirname(os.path.dirname(HERE)), "web")
DEFAULT_RUNNER = f"{os.path.join(WEB, 'node_modules', '.bin', 'vitest')} run tooling/nethints-sim.test.ts"


def parse_jobs(path):
    jobs = []
    with open(path, encoding="utf-8") as f:
        for n, raw in enumerate(f, 1):
            raw = raw.strip()
            if not raw or raw.startswith("#"):
                continue
            parts = raw.split()
            if len(parts) != 3 or not parts[2].isdigit() or "-" in parts[0] + parts[1]:
                raise ValueError(f"{path}:{n}: expected `<variant> <mode> <game>`, got {raw!r}")
            jobs.append((parts[0], parts[1], int(parts[2])))
    return jobs


def job_file(out, job):
    return os.path.join(out, f"{job[0]}-{job[1]}-{job[2]}.jsonl")


def verified(path):
    """True when the last non-empty line parses as JSON with end:true. Reads only the tail, files get large."""
    import json
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - 4096))
            tail = f.read().decode("utf-8", "replace").rstrip("\n")
    except OSError:
        return False
    last = tail.rsplit("\n", 1)[-1]
    try:
        return json.loads(last).get("end") is True
    except ValueError:
        return False


def cleanup(out):
    """CLEANUP: *.tmp.* leftovers of an aborted run. Returns their number."""
    n = 0
    for p in glob.glob(os.path.join(out, "*.tmp.*")):
        try:
            os.unlink(p)
            n += 1
        except OSError:
            pass
    return n


def etime(pid):
    r = subprocess.run(["ps", "-o", "etime=", "-p", str(pid)], capture_output=True, text=True)
    return r.stdout.strip() or "? (process gone)"


def status_text(out):
    try:
        return open(os.path.join(out, ".run.status"), encoding="utf-8").read().strip()
    except OSError:
        return "?"


def acquire(out):
    fh = open(os.path.join(out, ".run.lock"), "a")
    try:
        fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        fh.close()
        return None
    return fh


def fmt_duration(s):
    s = int(s)
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("jobs", nargs="?")
    ap.add_argument("--out", required=True)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--max-plies", type=int)
    ap.add_argument("--runner", default=DEFAULT_RUNNER)
    ap.add_argument("--detach", action="store_true")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--progress-seconds", type=float, default=10.0)
    a = ap.parse_args(argv)
    out = os.path.abspath(a.out)
    os.makedirs(out, exist_ok=True)

    if a.status:
        lock = acquire(out)
        if lock is None:
            print(f"läuft: {status_text(out)}; läuft seit {etime(status_text(out).split()[0].removeprefix('pid='))}")
            return 75
        print("läuft nicht")
        return 0
    if not a.jobs or a.workers < 1:
        ap.print_usage(sys.stderr)
        return 2
    try:
        jobs = parse_jobs(a.jobs)
    except (OSError, ValueError) as e:
        print(f"run.py: {e}", file=sys.stderr)
        return 2

    if a.detach:
        log = os.path.join(out, "run.log")
        if os.fork():
            print(f"run.py: detached; log {log}; ask with: {sys.argv[0]} --status --out {out}", file=sys.stderr)
            return 0
        os.setsid()
        fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
        os.dup2(fd, 1)
        os.dup2(fd, 2)

    lock = acquire(out)
    if lock is None:
        print(f"läuft bereits: {status_text(out)}", file=sys.stderr)
        return 75
    t0 = time.monotonic()
    with open(os.path.join(out, ".run.status"), "w", encoding="utf-8") as f:
        f.write(f"pid={os.getpid()} start={time.ctime()}\n")

    def say(msg):
        print(msg, file=sys.stderr, flush=True)

    cleaned = cleanup(out)
    todo = [j for j in jobs if not verified(job_file(out, j))]
    skip = len(jobs) - len(todo)
    n = len(jobs)
    ok = err = 0
    stop = []
    running = {}      # pid -> (Popen, job, start time)

    def on_signal(signum, _frame):
        stop.append(signum)
        for p, _j, _t in running.values():
            try:
                p.send_signal(signum)
            except OSError:
                pass

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)
    say(f"run.py: {n} jobs, {skip} done already, {len(todo)} to run, {a.workers} workers, {cleaned} leftover temp files removed")

    env_base = dict(os.environ, NETHINTS_SIM="1", NETHINTS_OUT=out)
    if a.max_plies:
        env_base["NETHINTS_MAX_PLIES"] = str(a.max_plies)
    logdir = os.path.join(out, "logs")
    os.makedirs(logdir, exist_ok=True)
    argv_runner = shlex.split(a.runner)
    # cwd of the default runner is web/; a custom runner gets the same, its own business
    queue = list(todo)
    last_progress = 0.0
    busy = 0.0          # summed duration of finished jobs: the rate counts real work only, SKIPs never

    def progress(force=False):
        nonlocal last_progress
        now = time.monotonic()
        if not force and now - last_progress < a.progress_seconds:
            return
        last_progress = now
        done = ok + err
        el = now - t0
        eta = "?"
        if ok:
            eta = fmt_duration((len(todo) - done) * (busy / ok) / a.workers)
        say(f"{skip + done}/{n}  ok {ok} skip {skip} error {err}  running {len(running)}  ETA {eta}  elapsed {fmt_duration(el)}")

    while (queue or running) and not (stop and not running):
        while queue and len(running) < a.workers and not stop:
            job = queue.pop(0)
            env = dict(env_base, NETHINTS_VARIANT=job[0], NETHINTS_MODE=job[1], NETHINTS_GAME=str(job[2]))
            log = open(os.path.join(logdir, f"{job[0]}-{job[1]}-{job[2]}.log"), "w")
            p = subprocess.Popen(argv_runner, cwd=WEB, env=env, stdout=log, stderr=subprocess.STDOUT)
            log.close()
            running[p.pid] = (p, job, time.monotonic())
            say(f"start {job[0]} {job[1]} {job[2]} (pid {p.pid})")
        for pid, (p, job, ts) in list(running.items()):
            rc = p.poll()
            if rc is None:
                continue
            del running[pid]
            dur = time.monotonic() - ts
            if stop:
                say(f"stopped {job[0]} {job[1]} {job[2]} (exit {rc}), discarded")
                cleanup(out)
            elif rc == 0 and verified(job_file(out, job)):
                ok += 1
                busy += dur
                say(f"ok {job[0]} {job[1]} {job[2]} in {fmt_duration(dur)}")
            else:
                err += 1
                say(f"ERROR {job[0]} {job[1]} {job[2]}: exit {rc}, see {os.path.join(logdir, f'{job[0]}-{job[1]}-{job[2]}.log')}")
        progress()
        time.sleep(0.2)

    left = len(todo) - ok - err
    say(f"{ok} ok, {skip} skip, {err} error, {cleaned} cleanup, {left} left"
        + (" - stopped" if stop else " - nothing more to do" if left == 0 and err == 0 else ""))
    if stop:
        return 128 + stop[0]
    return 1 if err else 0


if __name__ == "__main__":
    sys.exit(main())
