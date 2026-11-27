"""Tests of scripts/guides/run.py and jobs.py (offline; run: python3 -m unittest discover -s scripts/guides/test).

The vitest game is replaced by a stub runner (STUB_SOURCE) that reads the job from the environment as the real runner does. It
writes <variant>-<mode>-<game>.jsonl atomically with an end line, fails for the game in $STUB_FAIL, sleeps for the game in
$STUB_SLEEP, and logs every call to $STUB_LOG. The driver runs as a subprocess. RUN_PY=<path> tests another copy of run.py.
"""
import fcntl
import importlib.util
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
GUIDES = HERE.parent
RUN = Path(os.environ.get("RUN_PY", GUIDES / "run.py"))
JOBS = GUIDES / "jobs.py"

spec = importlib.util.spec_from_file_location("run_mod", RUN)
rm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rm)

STUB_SOURCE = r'''
import json, os, sys, time
v, m, g = os.environ["NETHINTS_VARIANT"], os.environ["NETHINTS_MODE"], os.environ["NETHINTS_GAME"]
out = os.environ["NETHINTS_OUT"]
open(os.environ["STUB_LOG"], "a").write(f"{v} {m} {g}\n")
if os.environ.get("STUB_SLEEP") == g:
    time.sleep(60)
if os.environ.get("STUB_FAIL") == g:
    sys.exit(1)
dest = f"{out}/{v}-{m}-{g}.jsonl"
tmp = f"{dest}.tmp.{os.getpid()}"
open(tmp, "w").write(json.dumps({"header": True}) + "\n" + json.dumps({"end": True}) + "\n")
os.rename(tmp, dest)
'''


class Tmp(unittest.TestCase):
    def setUp(self):
        self._t = tempfile.TemporaryDirectory()
        self.dir = Path(self._t.name)
        self.out = self.dir / "out"
        self.out.mkdir()
        self.stub = self.dir / "stub.py"
        self.stub.write_text(STUB_SOURCE)
        self.log = self.dir / "calls.log"
        self.jobs = self.dir / "jobs.txt"
        self.jobs.write_text("r5 A 0\nr5 A 1\nr4 B 2\n")

    def tearDown(self):
        self._t.cleanup()

    def env(self, **extra):
        return {**os.environ, "STUB_LOG": str(self.log), **extra}

    def start(self, *extra, env=None):
        return subprocess.Popen([sys.executable, str(RUN), str(self.jobs), "--out", str(self.out), "--workers", "2",
                                 "--runner", f"{sys.executable} {self.stub}", "--progress-seconds", "0.1", *extra],
                                env=env or self.env(), stderr=subprocess.PIPE, text=True)

    def run_driver(self, *extra, env=None):
        p = self.start(*extra, env=env)
        _, err = p.communicate(timeout=60)
        return p.returncode, err

    def calls(self):
        return sorted(self.log.read_text().split("\n")[:-1]) if self.log.exists() else []


class Run(Tmp):
    def test_runs_all_jobs_then_nothing_to_do(self):
        rc, err = self.run_driver()
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.calls(), ["r4 B 2", "r5 A 0", "r5 A 1"])
        self.assertIn("3 ok, 0 skip, 0 error, 0 cleanup, 0 left - nothing more to do", err)
        rc, err = self.run_driver()
        self.assertEqual(rc, 0, err)
        self.assertEqual(len(self.calls()), 3)                     # no further call
        self.assertIn("0 ok, 3 skip, 0 error, 0 cleanup, 0 left", err)

    def test_finished_file_is_skipped_truncated_file_runs_again(self):
        (self.out / "r5-A-0.jsonl").write_text('{"header": true}\n{"end": true}\n')
        (self.out / "r5-A-1.jsonl").write_text('{"header": true}\n{"ply": 4, "net":')      # killed halfway
        (self.out / "r4-B-2.jsonl").write_text('{"header": true}\n')                       # no end line
        rc, err = self.run_driver()
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.calls(), ["r4 B 2", "r5 A 1"])
        self.assertIn("2 ok, 1 skip, 0 error", err)
        self.assertTrue(rm.verified(str(self.out / "r5-A-1.jsonl")))

    def test_leftover_temp_files_are_cleaned_and_counted(self):
        (self.out / "r5-A-0.jsonl.tmp.99999").write_text("x")
        rc, err = self.run_driver()
        self.assertEqual(rc, 0, err)
        self.assertIn("3 ok, 0 skip, 0 error, 1 cleanup", err)
        self.assertEqual(list(self.out.glob("*.tmp.*")), [])

    def test_failing_job_is_an_error_not_a_skip(self):
        rc, err = self.run_driver(env=self.env(STUB_FAIL="1"))
        self.assertEqual(rc, 1)
        self.assertIn("2 ok, 0 skip, 1 error", err)
        self.assertFalse((self.out / "r5-A-1.jsonl").exists())
        rc, err = self.run_driver()                                # a retry picks up exactly the failed job
        self.assertEqual(rc, 0, err)
        self.assertIn("1 ok, 2 skip, 0 error", err)

    def test_max_plies_reaches_the_runner_environment(self):
        stub = self.dir / "env.py"
        stub.write_text("import os; open(os.environ['STUB_LOG'], 'a').write(os.environ.get('NETHINTS_MAX_PLIES', 'unset') + '\\n'); sys.exit(1)"
                        .replace("import os;", "import os, sys;"))
        p = subprocess.run([sys.executable, str(RUN), str(self.jobs), "--out", str(self.out), "--workers", "1", "--max-plies", "7",
                            "--runner", f"{sys.executable} {stub}"], env=self.env(), capture_output=True, text=True)
        self.assertEqual(p.returncode, 1)                          # the stub fails on purpose; only the environment matters
        self.assertEqual(set(self.calls()), {"7"})

    def test_wrong_call_exits_2(self):
        self.jobs.write_text("r5 A\n")
        rc, _ = self.run_driver()
        self.assertEqual(rc, 2)


class Control(Tmp):
    def test_second_start_exits_75(self):
        lock = open(self.out / ".run.lock", "a")
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        rc, err = self.run_driver()
        self.assertEqual(rc, 75)
        self.assertIn("läuft bereits", err)
        self.assertEqual(self.calls(), [])

    def test_status_reports_running_and_not_running(self):
        r = subprocess.run([sys.executable, str(RUN), "--status", "--out", str(self.out)], capture_output=True, text=True)
        self.assertEqual((r.returncode, r.stdout.strip()), (0, "läuft nicht"))
        p = self.start(env=self.env(STUB_SLEEP="0"))
        try:
            self.wait_for_call()
            r = subprocess.run([sys.executable, str(RUN), "--status", "--out", str(self.out)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 75)
            self.assertIn(f"pid={p.pid}", r.stdout)
        finally:
            p.kill()
            p.communicate()

    def wait_for_call(self):
        for _ in range(100):
            if self.log.exists() and self.log.read_text():
                return
            time.sleep(0.1)
        self.fail("the stub runner never started")

    def test_sigterm_stops_with_143_and_leaves_no_partial_file(self):
        p = self.start(env=self.env(STUB_SLEEP="0"))
        self.wait_for_call()
        p.send_signal(signal.SIGTERM)
        _, err = p.communicate(timeout=30)
        self.assertEqual(p.returncode, 143, err)
        self.assertIn("stopped", err)
        self.assertFalse((self.out / "r5-A-0.jsonl").exists())
        r = subprocess.run([sys.executable, str(RUN), "--status", "--out", str(self.out)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0)                          # the lock is gone with the process

    def test_sigint_stops_with_130(self):
        p = self.start(env=self.env(STUB_SLEEP="0"))
        self.wait_for_call()
        p.send_signal(signal.SIGINT)
        _, err = p.communicate(timeout=30)
        self.assertEqual(p.returncode, 130, err)


class Jobs(unittest.TestCase):
    def test_order_is_game_by_game(self):
        spec2 = importlib.util.spec_from_file_location("jobs_mod", JOBS)
        jm = importlib.util.module_from_spec(spec2)
        spec2.loader.exec_module(jm)
        self.assertEqual(jm.make_jobs(["r5", "r4"], ["A", "B"], 2),
                         [("r5", "A", 0), ("r5", "B", 0), ("r4", "A", 0), ("r4", "B", 0),
                          ("r5", "A", 1), ("r5", "B", 1), ("r4", "A", 1), ("r4", "B", 1)])
        self.assertEqual(jm.make_jobs(["r5"], ["A"], 5, first=3), [("r5", "A", 3), ("r5", "A", 4)])

    def test_cli_output_round_trips_through_parse_jobs(self):
        r = subprocess.run([sys.executable, str(JOBS), "--variants", "r5", "--modes", "A,B", "--games", "2"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0)
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write(r.stdout)
        try:
            self.assertEqual(rm.parse_jobs(f.name), [("r5", "A", 0), ("r5", "B", 0), ("r5", "A", 1), ("r5", "B", 1)])
        finally:
            os.unlink(f.name)

    def test_wrong_calls_exit_2(self):
        for args in (["--games", "0"], ["--games", "2", "--variants", "r9"], ["--games", "2", "--modes", "A-B"], ["--games", "2", "--first", "2"]):
            r = subprocess.run([sys.executable, str(JOBS), *args], capture_output=True, text=True)
            self.assertEqual(r.returncode, 2, args)


if __name__ == "__main__":
    unittest.main()
