"""Tests of the energy tools (offline; run: python3 scripts/energy/test/test_energy.py).

The report is checked on synthetic CSV files whose expected values come from a hand calculation: a counter that grows by
W * 16666.67 microwatt hours per minute (1 W for 60 s = 60 J = 16666.67 uWh), so 18 W is 300000 uWh per minute, 36 W is 600000
and 90 W is 1500000. The sampler is checked against a stub that replaces ioreg.
"""
import csv
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
SRC = HERE.parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, SRC / (name + ".py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


report, sampler = load("report"), load("sampler")
T = 1_800_000_000          # a multiple of 60: the counter steps fall on minute boundaries
STEP = {18: 300_000, 36: 600_000, 90: 1_500_000}
BASE = 5_000_000_000


class Series:
    """The counter as a function of time: power per minute index (from T), the counter grows at each minute boundary."""

    def __init__(self, watts_of_minute):
        self.w = watts_of_minute

    def counter(self, t):
        k = int((t - T) // 60)
        return BASE + sum(STEP[self.w(i)] for i in range(k))


def sample_row(t, e, flag="", system=18000, interval=5):
    return {"ts_utc": report.iso(t), "epoch": "%.1f" % t, "kind": "sample", "system_power_mw": system, "battery_power_mw": 0,
            "external": 1, "charging": 0, "battery_pct": 80, "acc_wall_uwh": e, "acc_system_uwh": e - 1000, "acc_count": 0,
            "loadavg1": "1.00", "flag": flag}


def ctl_row(kind, t, flag=""):
    return {"ts_utc": report.iso(t), "epoch": "%.1f" % t, "kind": kind, "flag": flag}


def run_rows(series, a, b, interval=5, flag_if=lambda t: ""):
    return [sample_row(a + i * interval, series.counter(a + i * interval), flag_if(a + i * interval))
            for i in range(int((b - a) // interval) + 1)]


def write_csv(path, rows):
    cols = sampler.COLUMNS
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, restval="")
        w.writeheader()
        w.writerows(rows)


def write_markers(path, events):
    with open(path, "w") as f:
        f.write("ts_utc,epoch,event,phase\n")
        for t, ev, ph in events:
            f.write("%s,%.1f,phase_%s,%s\n" % (report.iso(t), t, ev, ph))


def kwh(uwh):
    return uwh / 1e9


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.d = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def analyse(self, rows, events=(), **kw):
        write_csv(self.d / "r.csv", rows)
        write_markers(self.d / "r.markers.csv", events)
        return report.analyse(report.read_rows(self.d / "r.csv"), self.d / "r.markers.csv", name="r", **kw)

    def test_constant_power_total_equals_counter_delta(self):
        s = Series(lambda m: 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 1800)
        r = self.analyse(rows)
        # first step at minute 1, last at minute 30: 29 minutes of 300000 uWh, exactly
        self.assertAlmostEqual(r["run_total"]["kwh"], kwh(29 * 300_000), places=9)
        self.assertAlmostEqual(r["run_total"]["mean_w"], 18.0, delta=0.01)
        self.assertEqual(r["gaps"], [])

    def test_phases_mean_peak_and_baseline_from_marker(self):
        # minutes 0-9 idle 18 W, 10-19 load 90 W, 20-29 idle 18 W
        s = Series(lambda m: 90 if 10 <= m < 20 else 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 1800)
        ev = [(T + 120, "start", "baseline"), (T + 540, "end", "baseline"),
              (T + 600 + 5, "start", "load"), (T + 1200 + 5, "end", "load")]
        r = self.analyse(rows, ev)
        self.assertIn("marker phase 'baseline'", r["baseline_source"])
        self.assertAlmostEqual(r["baseline_w"], 18.0, delta=0.05)
        load = next(p for p in r["phases"] if p["phase"] == "load")
        self.assertAlmostEqual(load["mean_w"], 90.0, delta=90 * 0.06)         # step times are 2.5 s early: a few percent
        self.assertAlmostEqual(load["peak_w"], 90.0, delta=0.01)
        self.assertAlmostEqual(load["kwh"], 90 * 600 / 3.6e6, delta=90 * 600 / 3.6e6 * 0.06)
        # above baseline = energy - 18 W * covered time, by construction
        self.assertAlmostEqual(load["kwh_above_baseline"], load["kwh"] - 18.0 * load["covered_s"] / 3.6e6, places=9)
        self.assertAlmostEqual(load["kwh_above_baseline"], 72 * 600 / 3.6e6, delta=72 * 600 / 3.6e6 * 0.08)
        base = next(p for p in r["phases"] if p["phase"] == "baseline")
        self.assertAlmostEqual(base["kwh_above_baseline"], 0.0, delta=1e-6)

    def test_baseline_fallback_is_lowest_ten_minute_window(self):
        s = Series(lambda m: 90 if 10 <= m < 20 else 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 1800)
        r = self.analyse(rows)
        self.assertAlmostEqual(r["baseline_w"], 18.0, delta=0.05)
        self.assertIn("lowest 10-minute mean", r["baseline_source"])
        # a window length that fits only the loaded stretch gives the loaded power: the minimum is taken over windows
        r2 = self.analyse(rows, minutes=25)
        self.assertGreater(r2["baseline_w"], 30.0)

    def test_no_baseline_possible_is_said(self):
        s = Series(lambda m: 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 400)
        r = self.analyse(rows)
        self.assertIsNone(r["baseline_w"])
        self.assertIn("no baseline", r["baseline_source"])
        self.assertIsNone(r["run_total"]["kwh_above_baseline"])

    def test_gap_and_restart_are_not_integrated(self):
        s = Series(lambda m: 18)
        first = run_rows(s, T, T + 600)                          # sampler killed at T+600: no stop row
        second = run_rows(s, T + 1800, T + 2400)                 # restart 20 minutes later
        rows = ([ctl_row("start", T, "interval=5")] + first +
                [ctl_row("gap", T + 1800, "previous_epoch=%.1f" % (T + 600)), ctl_row("start", T + 1800, "interval=5")] + second)
        ev = [(T + 300, "start", "across"), (T + 2100, "end", "across")]
        r = self.analyse(rows, ev)
        self.assertEqual(len(r["gaps"]), 1)
        self.assertAlmostEqual(r["gaps"][0]["seconds"], 1200, delta=0.1)
        # the counter went on over the gap: 20 minutes at 18 W (one step each side is inside the segments)
        self.assertAlmostEqual(r["gaps"][0]["kwh_counter_change"], kwh(21 * 300_000), delta=kwh(2 * 300_000))
        # the run total is the two segments only: 9 + 9 steps of 300000
        self.assertAlmostEqual(r["run_total"]["kwh"], kwh(18 * 300_000), places=9)
        across = r["phases"][0]
        self.assertAlmostEqual(across["duration_s"], 1800)
        self.assertLess(across["covered_s"], 1800 * 0.5)
        self.assertTrue(any("only" in n and "has data" in n for n in across["notes"]))
        self.assertAlmostEqual(across["mean_w"], 18.0, delta=0.1)       # mean over the covered time, not over the gap

    def test_gap_without_restart_rows_is_found_by_sample_spacing(self):
        s = Series(lambda m: 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 600) + run_rows(s, T + 1500, T + 2100)
        r = self.analyse(rows)
        self.assertEqual(len(r["gaps"]), 1)
        self.assertEqual(r["gaps"][0]["reason"], "gap")

    def test_spacing_exactly_three_intervals_is_no_gap(self):
        s = Series(lambda m: 18)
        # polls 15 s apart with interval 5: not more than 3 intervals, so one segment
        rows = [ctl_row("start", T, "interval=5")] + [sample_row(T + 15 * i, s.counter(T + 15 * i)) for i in range(0, 121)]
        self.assertEqual(self.analyse(rows)["gaps"], [])
        ts = [15 * i for i in range(0, 61)] + [900 + 16 + 15 * i for i in range(0, 60)]      # one spacing of 16 s in the middle
        rows = [ctl_row("start", T, "interval=5")] + [sample_row(T + x, s.counter(T + x)) for x in ts]
        self.assertEqual(len(self.analyse(rows)["gaps"]), 1)

    def test_counter_reset_ends_a_segment(self):
        s = Series(lambda m: 18)
        a = run_rows(s, T, T + 600)
        b = [dict(x, acc_wall_uwh=int(x["acc_wall_uwh"]) - BASE) for x in run_rows(s, T + 600 + 5, T + 1200)]
        r = self.analyse([ctl_row("start", T, "interval=5")] + a + b)
        self.assertEqual(len(r["gaps"]), 1)
        self.assertEqual(r["gaps"][0]["reason"], "counter reset")
        self.assertIsNone(r["gaps"][0]["kwh_counter_change"])

    def test_unclosed_orphan_and_open_phases_warn(self):
        s = Series(lambda m: 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 900)
        ev = [(T + 100, "start", "a"), (T + 400, "start", "a"), (T + 500, "end", "a"),
              (T + 600, "end", "orphan"), (T + 700, "start", "open")]
        r = self.analyse(rows, ev)
        text = "\n".join(r["warnings"])
        self.assertIn("phase a at", text)
        self.assertIn("without a start", text)
        self.assertIn("still open", text)
        a = next(p for p in r["phases"] if p["phase"] == "a")
        self.assertEqual(a["segments"], 2)
        self.assertAlmostEqual(a["duration_s"], 300 + 100)       # first segment ends at the second start, second runs 400..500

    def test_step_time_is_between_last_unchanged_and_first_changed_sample(self):
        rows = [ctl_row("start", T, "interval=5")] + [sample_row(T + 5 * i, 100 if i < 2 else 200 if i < 14 else 300) for i in range(0, 30)]
        segs, _, _ = report.build_segments(rows, "wall", 3.0)
        self.assertEqual(segs[0]["points"], [(T + 7.5, 200), (T + 67.5, 300)])

    def test_short_baseline_marker_falls_back(self):
        s = Series(lambda m: 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 1800)
        r = self.analyse(rows, [(T + 100, "start", "baseline"), (T + 160, "end", "baseline")])
        self.assertIn("lowest 10-minute mean", r["baseline_source"])
        self.assertIn("too little data", r["baseline_source"])

    def test_unclosed_phase_ends_at_the_next_sampler_gap(self):
        s = Series(lambda m: 18)
        rows = ([ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 600) + [ctl_row("gap", T + 900, "previous_epoch=1"), ctl_row("start", T + 900, "interval=5")]
                + run_rows(s, T + 900, T + 1500))
        r = self.analyse(rows, [(T + 100, "start", "p"), (T + 1200, "start", "p"), (T + 1400, "end", "p")])
        self.assertEqual(r["phases"][0]["duration_s"], 800 + 200)

    def test_repeated_phase_is_summed(self):
        s = Series(lambda m: 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 1200)
        ev = [(T + 120, "start", "p"), (T + 300, "end", "p"), (T + 600, "start", "p"), (T + 900, "end", "p")]
        r = self.analyse(rows, ev)
        p = r["phases"][0]
        self.assertEqual((p["segments"], p["duration_s"]), (2, 480))
        self.assertAlmostEqual(p["mean_w"], 18.0, delta=0.1)

    def test_battery_flag_is_reported(self):
        s = Series(lambda m: 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 1200, flag_if=lambda t: "batt" if t >= T + 600 else "")
        r = self.analyse(rows, [(T + 60, "start", "p"), (T + 1100, "end", "p")])
        self.assertTrue(any("battery not idle" in n for n in r["phases"][0]["notes"]))
        self.assertTrue(any("battery not idle" in w for w in r["warnings"]))
        clean = self.analyse([ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 1200), [(T + 60, "start", "p"), (T + 1100, "end", "p")])
        self.assertFalse(any("battery" in w for w in clean["warnings"]))

    def test_error_rows_are_counted(self):
        s = Series(lambda m: 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 900) + [ctl_row("error", T + 902, "ioreg exit 1")]
        self.assertTrue(any("1 samples could not be read" in w for w in self.analyse(rows)["warnings"]))

    def test_no_usable_data_raises(self):
        with self.assertRaises(ValueError):
            self.analyse([ctl_row("start", T, "interval=5")] + run_rows(Series(lambda m: 18), T, T + 50))

    def test_system_metric_uses_the_system_counter(self):
        s = Series(lambda m: 18)
        rows = [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 1800)
        r = self.analyse(rows, metric="system")
        self.assertEqual(r["metric"], "system")
        self.assertAlmostEqual(r["run_total"]["kwh"], kwh(29 * 300_000), places=9)      # same steps, offset by a constant

    def test_markdown_and_main_write_files(self):
        s = Series(lambda m: 18)
        write_csv(self.d / "r.csv", [ctl_row("start", T, "interval=5")] + run_rows(s, T, T + 1800))
        write_markers(self.d / "r.markers.csv", [(T + 60, "start", "p"), (T + 900, "end", "p")])
        out = subprocess.run([sys.executable, "-I", str(SRC / "report.py"), "--name", "r", "--dir", str(self.d)],
                             capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("| p |", out.stdout)
        self.assertIn("whole file", out.stdout)
        self.assertTrue((self.d / "r.report.md").exists() and (self.d / "r.report.json").exists())
        missing = subprocess.run([sys.executable, "-I", str(SRC / "report.py"), "--name", "nope", "--dir", str(self.d)],
                                 capture_output=True, text=True)
        self.assertEqual(missing.returncode, 1)


IOREG_TEXT = '''
    "ExternalConnected" = Yes
    "AppleRawExternalConnected" = No
    "CurrentCapacity" = 80
    "IsCharging" = Yes
      "PowerTelemetryData" = {"AccumulatedWallEnergyEstimate"=4598716821,"SystemPowerIn"=12370,"AccumulatedSystemEnergyConsumed"=4254796362,"SystemPowerInAccumulatorCount"=692511,"BatteryPower"=18446744073709551116,"WallEnergyEstimate"=3607}
'''


class ParseTest(unittest.TestCase):
    def test_parse_fields(self):
        s = sampler.parse_ioreg(IOREG_TEXT)
        self.assertEqual((s["system_power_mw"], s["acc_wall_uwh"], s["acc_system_uwh"], s["acc_count"]),
                         (12370, 4598716821, 4254796362, 692511))
        self.assertEqual((s["external"], s["charging"], s["battery_pct"]), (1, 1, 80))   # AppleRawExternalConnected=No must not win
        self.assertEqual(s["battery_power_mw"], -500)         # uint64 two's complement
        self.assertEqual(sampler.batt_flag(s), "batt")

    def test_batt_flag_cases(self):
        base = {"external": 1, "charging": 0, "battery_power_mw": 0}
        self.assertEqual(sampler.batt_flag(base), "")
        self.assertEqual(sampler.batt_flag(dict(base, external=0)), "batt")
        self.assertEqual(sampler.batt_flag(dict(base, charging=1)), "batt")
        self.assertEqual(sampler.batt_flag(dict(base, battery_power_mw=1001)), "batt")
        self.assertEqual(sampler.batt_flag(dict(base, battery_power_mw=-1001)), "batt")
        self.assertEqual(sampler.batt_flag(dict(base, battery_power_mw=1000)), "")

    def test_unusable_text_raises(self):
        with self.assertRaises(ValueError):
            sampler.parse_ioreg("nothing here")
        with self.assertRaises(ValueError):
            sampler.parse_ioreg('"PowerTelemetryData" = {"SystemPowerIn"=1}')


STUB = '''#!/usr/bin/env python3
import os, sys
p = os.environ["STUB_STATE"]
n = int(open(p).read()) if os.path.exists(p) else 0
open(p, "w").write(str(n + 1))
if os.environ.get("STUB_FAIL") == "1":
    sys.exit(1)
print('"ExternalConnected" = Yes\\n"IsCharging" = No\\n"CurrentCapacity" = 80')
print('"PowerTelemetryData" = {"AccumulatedWallEnergyEstimate"=%d,"SystemPowerIn"=10000,"AccumulatedSystemEnergyConsumed"=%d,'
      '"SystemPowerInAccumulatorCount"=%d,"BatteryPower"=0}' % (1000000 + n * 10, 900000 + n * 10, n))
'''


class SamplerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.d = Path(self.tmp.name)
        (self.d / "stub.py").write_text(STUB)
        self.env = dict(os.environ, ENERGY_IOREG="%s %s" % (sys.executable, self.d / "stub.py"), STUB_STATE=str(self.d / "n"))

    def tearDown(self):
        self.tmp.cleanup()

    def cmd(self, *a):
        return [sys.executable, "-I", str(SRC / "sampler.py"), "--name", "t", "--dir", str(self.d / "out"), *a]

    def kinds(self):
        with open(self.d / "out" / "t.csv", newline="") as f:
            return [r for r in csv.DictReader(f)]

    def test_restart_appends_and_writes_gap_row(self):
        for _ in range(2):
            r = subprocess.run(self.cmd("--interval", "0.2", "--duration", "0.5"), env=self.env, capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertRegex(r.stderr, r"\d+ ok, 0 skip, 0 error, 0 left - done")
        rows = self.kinds()
        kinds = [x["kind"] for x in rows]
        self.assertEqual(kinds.count("start"), 2)
        self.assertEqual(kinds.count("gap"), 1)
        self.assertEqual(kinds.count("stop"), 2)
        # order: start, samples, stop, gap, start, samples, stop; header written once
        self.assertEqual(kinds[0], "start")
        self.assertEqual(kinds[kinds.index("gap") - 1], "stop")
        self.assertTrue(rows[kinds.index("gap")]["flag"].startswith("previous_epoch="))
        samples = [x for x in rows if x["kind"] == "sample"]
        self.assertGreaterEqual(len(samples), 4)
        self.assertEqual(samples[0]["system_power_mw"], "10000")
        self.assertEqual((self.d / "out" / "t.csv").read_text().count("ts_utc,epoch"), 1)

    def test_lock_status_and_sigterm(self):
        p = subprocess.Popen(self.cmd("--interval", "0.2"), env=self.env, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.time() + 10
            while time.time() < deadline and not (self.d / "out" / "t.csv").exists():
                time.sleep(0.05)
            time.sleep(0.5)
            second = subprocess.run(self.cmd(), env=self.env, capture_output=True, text=True)
            self.assertEqual(second.returncode, 75)
            self.assertIn("running already: pid=%d" % p.pid, second.stderr)
            st = subprocess.run(self.cmd("--status"), env=self.env, capture_output=True, text=True)
            self.assertEqual(st.returncode, 75)
            p.send_signal(signal.SIGTERM)
            p.wait(timeout=10)
            self.assertEqual(p.returncode, 143)
        finally:
            if p.poll() is None:
                p.kill()
            p.stderr.close()
        rows = self.kinds()
        self.assertEqual(rows[-1]["kind"], "stop")
        self.assertEqual(rows[-1]["flag"], "signal=15")
        st = subprocess.run(self.cmd("--status"), env=self.env, capture_output=True, text=True)
        self.assertEqual((st.returncode, st.stdout.strip()), (0, "energy t: not running"))

    def test_stop_option_ends_the_running_sampler(self):
        p = subprocess.Popen(self.cmd("--interval", "0.2"), env=self.env, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.time() + 10
            while time.time() < deadline and not (self.d / "out" / "t.csv").exists():
                time.sleep(0.05)
            time.sleep(0.5)
            st = subprocess.run(self.cmd("--stop"), env=self.env, capture_output=True, text=True, timeout=30)
            self.assertEqual((st.returncode, st.stdout.strip()), (0, "energy t: stopped (pid %d)" % p.pid))
            self.assertEqual(p.wait(timeout=10), 143)
            again = subprocess.run(self.cmd("--stop"), env=self.env, capture_output=True, text=True)
            self.assertEqual((again.returncode, again.stdout.strip()), (0, "energy t: not running"))
        finally:
            if p.poll() is None:
                p.kill()
            p.stderr.close()

    def test_detached_sampler_does_not_keep_a_foreign_lock(self):
        import fcntl
        foreign = self.d / "foreign.lock"
        fd = os.open(foreign, os.O_RDWR | os.O_CREAT)
        fcntl.flock(fd, fcntl.LOCK_EX)
        out = subprocess.run(self.cmd("--detach", "--interval", "0.2"), env=self.env, capture_output=True, text=True, pass_fds=[fd])
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("detached: log", out.stdout)
        os.close(fd)                       # the "driver" ends; only an inherited copy could keep the lock
        try:
            fd2 = os.open(foreign, os.O_RDWR)
            fcntl.flock(fd2, fcntl.LOCK_EX | fcntl.LOCK_NB)      # raises BlockingIOError if the sampler kept it
            os.close(fd2)
        finally:
            st = subprocess.run(self.cmd("--stop"), env=self.env, capture_output=True, text=True, timeout=30)
        self.assertEqual(st.returncode, 0, st.stderr)

    def test_sigint_exit_130(self):
        p = subprocess.Popen(self.cmd("--interval", "0.2"), env=self.env, stderr=subprocess.PIPE, text=True)
        try:
            time.sleep(1.0)
            p.send_signal(signal.SIGINT)
            p.wait(timeout=10)
        finally:
            if p.poll() is None:
                p.kill()
            p.stderr.close()
        self.assertEqual(p.returncode, 130)

    def test_unreadable_telemetry_exits_1_and_logs_errors(self):
        r = subprocess.run(self.cmd("--interval", "0.05"), env=dict(self.env, STUB_FAIL="1"), capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 1)
        self.assertIn("cannot read the telemetry", r.stderr)
        self.assertEqual([x["kind"] for x in self.kinds()].count("error"), 5)

    def test_bad_arguments_exit_2(self):
        r = subprocess.run(self.cmd("--interval", "0"), env=self.env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)

    def test_report_on_sampler_output_end_to_end(self):
        # the stub counter grows 10 uWh per sample: the report must find steps and total them
        r = subprocess.run(self.cmd("--interval", "0.1", "--duration", "1.5"), env=self.env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        rows = report.read_rows(self.d / "out" / "t.csv")
        segs, gaps, errs = report.build_segments(rows, "wall", 3.0)
        self.assertEqual((len(segs), errs), (1, 0))
        pts = segs[0]["points"]
        self.assertGreater(len(pts), 5)
        self.assertEqual(pts[-1][1] - pts[0][1], 10 * (len(pts) - 1))


class MarkTest(unittest.TestCase):
    def test_marker_rows_and_soft_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            mark = [sys.executable, "-I", str(SRC / "mark.py"), "--name", "m", "--dir", tmp]
            self.assertEqual(subprocess.run(mark + ["--epoch", "1800000000", "start", "gen0-small"]).returncode, 0)
            self.assertEqual(subprocess.run(mark + ["--epoch", "1800000060", "end", "gen0-small"]).returncode, 0)
            self.assertEqual((Path(tmp) / "m.markers.csv").read_text().splitlines(), [
                "ts_utc,epoch,event,phase",
                "2027-01-15T08:00:00Z,1800000000.0,phase_start,gen0-small",
                "2027-01-15T08:01:00Z,1800000060.0,phase_end,gen0-small"])
            self.assertEqual(subprocess.run(mark + ["start", "bad phase"], capture_output=True).returncode, 2)
            blocker = Path(tmp) / "file"
            blocker.write_text("x")
            bad = [sys.executable, "-I", str(SRC / "mark.py"), "--name", "m", "--dir", str(blocker / "sub"), "start", "p"]
            self.assertEqual(subprocess.run(bad, capture_output=True).returncode, 1)
            self.assertEqual(subprocess.run(bad, capture_output=True, env=dict(os.environ, MARK_SOFT="1")).returncode, 0)

    def test_name_from_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([sys.executable, "-I", str(SRC / "mark.py"), "--dir", tmp, "start", "p"],
                           env=dict(os.environ, ENERGY_NAME="fromenv"), check=True)
            self.assertTrue((Path(tmp) / "fromenv.markers.csv").exists())


if __name__ == "__main__":
    unittest.main()
