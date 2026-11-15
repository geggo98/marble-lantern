#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Network measurement session on the Mac (docs/cpp-comparison-plan.md, WP13, "Network measurement" and "Hygiene").

usage: bench-nn-mac.py [--detach] [--status] [--out PREFIX] [--max-load L] [--pairs N] [--no-gate] [--work DIR] [--keep-work]

One session: hygiene checks, anchor rows (SMALL and LARGE at batch 256, both programs), then N pairs of processes J, C, J, C, ... (Java
CrossBench against C++ nn_bench, the full table, the gen11 rows, and the cost-of-the-checks rows C hardened against C release), the
kernel table (Java KernelsBenchmark against nn_bench --kernels), the cold-start runs, and the anchor rows again. After the session:
anchors at the end against the start (the two medians of every program must agree within 2 %), the foreign-process check, the load
and the thermal state. A session that fails a check is invalid: its files stay in the work directory, no result is written, and the
exit code is 4 (repeat it; after three invalid sessions in a row measuring stops and the owner is asked).

The result is <PREFIX>.tsv (the raw output of every run, one section per run) and <PREFIX>-summary.txt (merge-bench.pl over it).
PREFIX defaults to cpp/results/mac-nn-<date>. Inputs: cpp/build/release-hardened/nn_bench, cpp/build/release/nn_bench,
cpp/build/java-nn.cmd (the Java command line that :nn-tornado:printRunCommand wrote; its class path is never copied into a result),
cpp/golden/. STERNHALMA_DATA_DIR (the gen11 weights) is read from the environment.

Load limit: --max-load, default 4.0. The Mac of the owner has a base load of 3.0 to 3.7 at rest (window server, audio routing,
terminal; measured with no benchmark running), so the plan's 2.0 could never be met. The limit applies to the base load only: the
gate refuses a start above it and any process of the plan's list (java, gradle, clang, cmake, ninja, g++, docker) that is not part
of the session. After the session the 1-minute average contains the session's own busy thread (about 1.0); the check waits up to
5 minutes for it to decay below the limit and records both values.

Long runs (README, "long-running scripts"): --detach forks (os.fork, os.setsid) and writes everything to <work>/session.log and prints
its path and the PID. The lock is an fcntl.flock on a file in the temporary directory; a second start prints "already running: pid=...
since ..." and exits 75. --status: lock probe, then the status file (display only) and ps -o etime=. SIGINT and SIGTERM set a flag; the
running child is terminated, nothing is written, exit 130 or 143. One progress line per step on stderr: n/N, elapsed, estimated rest.
Exit codes: 0 result written, 1 error, 3 not started (load, foreign processes, power), 4 invalid session, 75 already running,
130 and 143 aborted.
"""
import argparse
import datetime
import fcntl
import os
import re
import shlex
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

CPP = Path(__file__).resolve().parent.parent
REPO = CPP.parent
HARD = CPP / "build" / "release-hardened" / "nn_bench"
REL = CPP / "build" / "release" / "nn_bench"
JAVA_CMD_FILE = CPP / "build" / "java-nn.cmd"
GOLDEN = "cpp/golden"
PROC_PATTERN = r"java|gradle|clang|cmake|ninja|g\+\+|docker"
LOCK = Path(tempfile.gettempdir()) / "sternhalma-bench-nn-mac.lock"
STATUS = Path(tempfile.gettempdir()) / "sternhalma-bench-nn-mac.status"
BATCHES = "1,16,64,256,512,1024"
QUICK = False
FOREIGN_CPU_LIMIT = 350  # percent of one core, all processes except the benchmark; the base load at rest is about 120 to 150

stop_signal = []


def on_signal(sig, _frame):
    stop_signal.append(sig)


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def run_text(cmd):
    return subprocess.run(cmd, capture_output=True, text=True).stdout


def load1():
    return os.getloadavg()[0]


def foreign_procs():
    """Processes of the plan's list (by process name, not by command line)."""
    out = run_text(["pgrep", "-l", PROC_PATTERN])
    me = {os.getpid(), os.getppid()}
    keep = []
    for line in out.splitlines():
        parts = line.split(None, 1)
        if len(parts) == 2 and int(parts[0]) not in me:
            keep.append(parts[1])
    return keep


def top3():
    """The three most CPU-hungry programs now (ps, decaying average), as 'name pcpu'."""
    out = run_text(["ps", "-Ao", "pcpu=,comm=", "-r"])
    rows = []
    for line in out.splitlines()[:3]:
        pcpu, _, comm = line.strip().partition(" ")
        rows.append("%s %s" % (os.path.basename(comm.strip()), pcpu))
    return rows


def therm():
    out = run_text(["pmset", "-g", "therm"])
    m = re.search(r"CPU_Speed_Limit\s*=\s*(\d+)", out)
    return "CPU_Speed_Limit=%s" % m.group(1) if m else "no thermal or performance warning recorded"


def on_ac():
    return "AC Power" in run_text(["pmset", "-g", "batt"]).splitlines()[0]


def containers():
    try:
        out = run_text(["docker", "ps", "-q"])
    except FileNotFoundError:
        return 0
    return len([l for l in out.splitlines() if l.strip()])


def java_base():
    return shlex.split(JAVA_CMD_FILE.read_text())


class Step:
    def __init__(self, name, kind, lang, cmd, pair=0, est=60, extra_env=None):
        self.foreign_max = 0.0
        self.name, self.kind, self.lang, self.cmd, self.pair, self.est = name, kind, lang, cmd, pair, est
        self.extra_env = extra_env or {}
        self.rc = None
        self.wall = 0.0
        self.load_before = 0.0
        self.load_after = 0.0
        self.top = []


def plan(pairs):
    batches = "1,64" if QUICK else BATCHES
    presets = "small" if QUICK else "small,large"
    j = java_base()
    j_kernels = j[:-1] + ["sternhalma.tornado.KernelsBenchmark"]
    g = ["--golden", GOLDEN]
    steps = []

    def cpp(binary, *args):
        return [str(binary)] + g + list(args)

    def anchors(tag):
        steps.append(Step("anchors-%s-cpp" % tag, "anchors", "cpp", cpp(HARD, "--anchors", "--impls", "simd,metal-copy"), est=45))
        steps.append(Step("anchors-%s-java" % tag, "anchors", "java", j + g + ["--anchors", "--impls", "vector,tornado", "--no-profiled"], est=50))

    anchors("start")
    for p in range(1, pairs + 1):
        steps.append(Step("main-p%d-java" % p, "main", "java", j + g + ["--presets", presets, "--batches", batches, "--impls", "scalar,vector,tornado"], p, 330))
        steps.append(Step("main-p%d-cpp" % p, "main", "cpp",
                          cpp(HARD, "--presets", presets, "--batches", batches, "--impls", "reference,simd,simd-nofma,metal-copy,metal-shared"), p, 420))
        steps.append(Step("gen11-p%d-java" % p, "gen11", "java", j + g + ["--presets", "gen11", "--batches", batches, "--impls", "vector,tornado", "--no-profiled"], p, 100))
        steps.append(Step("gen11-p%d-cpp" % p, "gen11", "cpp",
                          cpp(HARD, "--presets", "gen11", "--batches", batches, "--impls", "simd,metal-copy,metal-shared", "--no-profiled"), p, 100))
        for tag, binary in ((("hard", HARD), ("rel", REL)) if p % 2 else (("rel", REL), ("hard", HARD))):
            steps.append(Step("checks-p%d-%s" % (p, tag), "checks", "cpp-" + tag,
                              cpp(binary, "--presets", "small,large", "--batches", "256", "--impls", "simd,simd-nofma,metal-copy,metal-shared", "--no-profiled"), p, 70))
        if p == (pairs + 1) // 2:
            anchors("mid")
    for r in (1, 2):
        steps.append(Step("kernels-r%d-java" % r, "kernels", "java", j_kernels, r, 60))
        steps.append(Step("kernels-r%d-cpp" % r, "kernels", "cpp", cpp(HARD, "--presets", "small,large", "--batches", "1,64,512", "--kernels"), r, 45))
    for r in (1, 2, 3):
        for batch in ("1", "256"):
            steps.append(Step("cold-r%d-b%s-java-tornado" % (r, batch), "cold", "java", j + g + ["--presets", "large", "--batches", batch, "--impls", "tornado", "--cold"], r, 6))
            steps.append(Step("cold-r%d-b%s-cpp-metal" % (r, batch), "cold", "cpp", cpp(HARD, "--presets", "large", "--batches", batch, "--impls", "metal-copy", "--cold"), r, 3))
            steps.append(Step("cold-r%d-b%s-java-vector" % (r, batch), "cold", "java", j + g + ["--presets", "large", "--batches", batch, "--impls", "vector", "--cold"], r, 6))
            steps.append(Step("cold-r%d-b%s-cpp-simd" % (r, batch), "cold", "cpp", cpp(HARD, "--presets", "large", "--batches", batch, "--impls", "simd", "--cold"), r, 3))
    anchors("end")
    return steps


def info(work, status_line):
    STATUS.write_text(status_line + "\n")


def foreign_cpu(child_pid):
    """Sum of the CPU percentages (ps, decaying average) of every process except the child: the load that is not the benchmark."""
    total = 0.0
    for line in run_text(["ps", "-Ao", "pcpu=,pid="]).splitlines():
        parts = line.split()
        if len(parts) == 2 and int(parts[1]) != child_pid:
            total += float(parts[0])
    return total


def run_step(step, work, env):
    out = open(work / (step.name + ".tsv"), "wb")
    err = open(work / (step.name + ".err"), "wb")
    t0 = time.monotonic()
    child = subprocess.Popen(step.cmd, stdout=out, stderr=err, cwd=str(REPO), env=env)
    next_sample = time.monotonic() + 3
    while child.poll() is None:
        if time.monotonic() >= next_sample:
            step.foreign_max = max(step.foreign_max, foreign_cpu(child.pid))
            next_sample = time.monotonic() + 10
        if stop_signal:
            child.terminate()
            try:
                child.wait(10)
            except subprocess.TimeoutExpired:
                child.kill()
            break
        time.sleep(0.25)
    step.wall = time.monotonic() - t0
    step.rc = child.returncode
    out.close()
    err.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--detach", action="store_true")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--out")
    ap.add_argument("--max-load", type=float, default=4.0)
    ap.add_argument("--pairs", type=int, default=3)
    ap.add_argument("--no-gate", action="store_true")
    ap.add_argument("--work")
    ap.add_argument("--keep-work", action="store_true")
    ap.add_argument("--quick", action="store_true", help="test the script itself: a tiny table, not a measurement")
    a = ap.parse_args()

    global QUICK
    QUICK = a.quick
    lock = open(LOCK, "a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        pid = STATUS.read_text().split()[0].removeprefix("pid=") if STATUS.exists() else "?"
        et = run_text(["ps", "-o", "etime=", "-p", pid]).strip()
        print("already running: pid=%s since %s" % (pid, et or "? (process gone)"), file=sys.stderr)
        return 75
    if a.status:
        print("not running")
        return 0

    for f in (HARD, REL, JAVA_CMD_FILE, CPP / "golden" / "MANIFEST.tsv"):
        if not f.exists():
            log("bench-nn-mac.py: missing %s (build the presets, run the golden exporter and printRunCommand)" % f.name)
            return 1
    work = Path(a.work) if a.work else Path(tempfile.mkdtemp(prefix="bench-nn-mac."))
    work.mkdir(parents=True, exist_ok=True)
    date = datetime.date.today().isoformat()
    prefix = Path(a.out) if a.out else CPP / "results" / ("mac-nn-" + date)

    if a.detach:
        logfile = work / "session.log"
        pid = os.fork()
        if pid:
            print("log: %s\npid: %d" % (logfile, pid))
            return 0
        os.setsid()
        fd = os.open(str(logfile), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
        os.dup2(fd, 1)
        os.dup2(fd, 2)
        os.close(fd)
        # the forked child keeps the lock descriptor of the parent: flock belongs to the open file description

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)
    started = time.time()
    STATUS.write_text("pid=%d start=%s work=%s\n" % (os.getpid(), time.ctime(started), work.name))

    env = dict(os.environ)
    env.setdefault("STERNHALMA_GOLDEN_REQUIRED", "1")
    gate = not a.no_gate

    # ---- hygiene before ----
    load_before = load1()
    procs_before = foreign_procs()
    therm_before = therm()
    top_before = top3()
    containers_before = containers()
    power_ok = on_ac()
    log("load before %.2f (limit %.1f), foreign processes: %s, top: %s, containers: %d, thermal: %s" %
        (load_before, a.max_load, procs_before or "none", "; ".join(top_before), containers_before, therm_before))
    if gate and load_before > a.max_load:
        log("bench-nn-mac.py: load before is %.2f, above %.1f; not started" % (load_before, a.max_load))
        return 3
    if gate and procs_before:
        log("bench-nn-mac.py: foreign processes are running, not started: %s" % procs_before)
        return 3
    if gate and not power_ok:
        log("bench-nn-mac.py: the power adapter is not connected; not started")
        return 3

    steps = plan(a.pairs)
    total_est = sum(s.est for s in steps)
    done_est = 0.0
    t_start = time.monotonic()
    invalid = []
    for n, step in enumerate(steps, 1):
        if stop_signal:
            break
        if gate:
            fp = foreign_procs()
            if fp:
                invalid.append("foreign process before step %s: %s" % (step.name, fp))
        step.load_before = load1()
        step.top = top3()
        elapsed = time.monotonic() - t_start
        rest = (elapsed / done_est * (total_est - done_est)) if done_est > 30 else (total_est - done_est)
        log("%d/%d  %s  elapsed %ds  ETA %ds  load %.2f" % (n, len(steps), step.name, elapsed, rest, step.load_before))
        run_step(step, work, env)
        step.load_after = load1()
        done_est += step.est
        STATUS.write_text("pid=%d start=%s step=%d/%d %s\n" % (os.getpid(), time.ctime(started), n, len(steps), step.name))
        if step.rc not in (0, None) and not stop_signal:
            err_tail = (work / (step.name + ".err")).read_text(errors="replace").strip().splitlines()[-3:]
            invalid.append("step %s ended with exit code %s: %s" % (step.name, step.rc, " | ".join(err_tail)))
            break
    if stop_signal:
        log("bench-nn-mac.py: aborted (files in %s)" % work)
        return 128 + stop_signal[0]

    # ---- hygiene after ----
    fp_after = foreign_procs()
    load_after = load1()
    load_decayed = load_after
    waited = 0
    while gate and load_decayed > a.max_load and waited < 300 and not stop_signal:
        time.sleep(15)
        waited += 15
        load_decayed = load1()
    therm_after = therm()
    top_after = top3()
    containers_after = containers()
    for s in steps:
        if gate and s.foreign_max > FOREIGN_CPU_LIMIT:
            invalid.append("foreign CPU load %.0f %% (limit %d %%) during step %s" % (s.foreign_max, FOREIGN_CPU_LIMIT, s.name))
    if gate and fp_after:
        invalid.append("foreign processes after the session: %s" % fp_after)
    if gate and load_decayed > a.max_load:
        invalid.append("load stays at %.2f, above %.1f, 5 minutes after the session" % (load_decayed, a.max_load))

    # ---- write the steps table for merge-bench.pl and the raw result ----
    with open(work / "steps.tsv", "w") as f:
        f.write("name\tkind\tlang\tpair\trc\twall_s\tload_before\tload_after\tforeign_cpu_max\ttop3_before\n")
        for s in steps:
            f.write("%s\t%s\t%s\t%d\t%s\t%.2f\t%.2f\t%.2f\t%.0f\t%s\n" % (s.name, s.kind, s.lang, s.pair, s.rc, s.wall, s.load_before, s.load_after, s.foreign_max, "; ".join(s.top)))
    meta = [
        "# QUICK TEST of the session script, not a measurement" if QUICK else "#",
        "# Network measurement on the Mac (docs/cpp-comparison-plan.md, WP13)",
        "# date %s" % date,
        "# machine %s, %s cores, macOS %s (build %s)" % (run_text(["sysctl", "-n", "machdep.cpu.brand_string"]).strip(), run_text(["sysctl", "-n", "hw.ncpu"]).strip(),
                                                         run_text(["sw_vers", "-productVersion"]).strip(), run_text(["sw_vers", "-buildVersion"]).strip()),
        "# git commit %s%s" % (run_text(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"]).strip(),
                                " (working tree has uncommitted changes)" if run_text(["git", "-C", str(REPO), "status", "--porcelain", "--untracked-files=no"]).strip() else ""),
        "# C++ compiler %s" % run_text(["/usr/bin/clang++", "--version"]).splitlines()[0],
    ]
    for p in ("release-hardened", "release"):
        cache = (CPP / "build" / p / "CMakeCache.txt").read_text()
        flags = " ".join("%s=%s" % (k, v) for k, v in re.findall(r"^(CMAKE_CXX_FLAGS|CMAKE_CXX_FLAGS_RELEASE|SH_HARDENING|SH_NATIVE):\w+=(.*)$", cache, re.M))
        meta.append("# C++ %s: %s -std=c++23, no LTO, no PGO, -mcpu=native (SH_NATIVE); simd_net.cpp alone with -ffp-contract=fast" % (p, flags))
    meta += [
        "# method: processes alternate J, C (hardened), %d pairs; per process the schedule of the plan (warm-up of at least 2 s and 10 calls, 3 rounds of at least 1 s);" % a.pairs,
        "#         the reported figure of a program is the median over the pairs of the per-process median round; one host thread; Java started with java directly",
        "# equality: every case ends with the check of the last call against the Java reference outputs (exit 2 and no line on a violation)",
        "# hygiene: load limit %.1f (base load of this Mac at rest 3.0 to 3.7); load before %.2f, after %.2f, %.2f after %ds of cool-down" % (a.max_load, load_before, load_after, load_decayed, waited),
        "# hygiene: foreign processes (pgrep -l, by name: %s) before: %s; after: %s" % (PROC_PATTERN.replace("\\", ""), ", ".join(procs_before) or "none", ", ".join(fp_after) or "none"),
        "# hygiene: top 3 programs by CPU before: %s; after: %s" % ("; ".join(top_before), "; ".join(top_after)),
        "# hygiene: docker containers running before: %d, after: %d (OrbStack Kubernetes system pods, idle)" % (containers_before, containers_after),
        "# hygiene: thermal before: %s; after: %s; power: %s" % (therm_before, therm_after, "AC power" if power_ok else "NOT on AC"),
        "# session wall time %d s" % (time.monotonic() - t_start),
    ]
    raw = work / "raw.tsv"
    with open(raw, "w") as f:
        f.write("\n".join(meta) + "\n")
        for s in steps:
            body = (work / (s.name + ".tsv")).read_text(errors="replace")
            f.write("## run %s kind=%s lang=%s pair=%d wall_s=%.1f load_before=%.2f load_after=%.2f\n" % (s.name, s.kind, s.lang, s.pair, s.wall, s.load_before, s.load_after))
            f.write(body if body.endswith("\n") or not body else body + "\n")

    summary = work / "summary.txt"
    pl = subprocess.run(["perl", "-CSDA", "-Mutf8", str(CPP / "scripts" / "merge-bench.pl"), str(work)], capture_output=True, text=True)
    summary.write_text(pl.stdout)
    if pl.returncode != 0:
        invalid.append("merge-bench.pl failed: " + " | ".join(pl.stderr.strip().splitlines()[-3:]))
    # the checks that merge-bench.pl computed ("CHECK FAILED ..." lines)
    for line in pl.stdout.splitlines():
        if line.startswith("CHECK FAILED"):
            invalid.append(line)

    if invalid:
        log("bench-nn-mac.py: SESSION INVALID, nothing written (files in %s):" % work)
        for i in invalid:
            log("  - %s" % i)
        return 4
    prefix.parent.mkdir(parents=True, exist_ok=True)
    Path(str(prefix) + ".tsv").write_text(raw.read_text())
    Path(str(prefix) + "-summary.txt").write_text(summary.read_text())
    log("result written: %s.tsv and %s-summary.txt" % (prefix.relative_to(REPO) if str(prefix).startswith(str(REPO)) else prefix, prefix.name))
    if not a.keep_work and not a.work:
        pass  # the work directory is small; kept for the log
    return 0


if __name__ == "__main__":
    sys.exit(main())
