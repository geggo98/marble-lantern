#!/usr/bin/env python3
"""Tests of the --no-gate rules of bench-nn-mac.py. Run: python3 -I cpp/scripts/test_bench_nn_mac.py

An unchecked session must never look like a measurement: it needs an explicit --out outside cpp/results/ (exit 2, nothing started)
and both result files start with the INVALID SESSION line.
"""
import importlib.util
import os
import subprocess
import sys
import unittest

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("bench_nn_mac", os.path.join(here, "bench-nn-mac.py"))
bm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bm)


class NoGate(unittest.TestCase):
    def test_default_prefix_is_refused(self):
        self.assertIn("--out", bm.no_gate_out_error(None))

    def test_prefix_under_results_is_refused(self):
        self.assertIsNotNone(bm.no_gate_out_error(str(bm.CPP / "results" / "mac-nn-2026-11-15")))
        self.assertIsNotNone(bm.no_gate_out_error(str(bm.CPP / "results" / ".." / "results" / "x")))

    def test_prefix_elsewhere_is_allowed(self):
        self.assertIsNone(bm.no_gate_out_error("/tmp/mac-nn-test"))

    def test_marker_line(self):
        self.assertTrue(bm.marker_line(False, True).startswith("# INVALID SESSION"))
        self.assertTrue(bm.marker_line(True, False).startswith("# QUICK TEST"))
        self.assertEqual(bm.marker_line(False, False), "#")

    def test_the_command_line_exits_2_before_anything_starts(self):
        r = subprocess.run([sys.executable, "-I", os.path.join(here, "bench-nn-mac.py"), "--no-gate"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)
        self.assertIn("--no-gate needs --out", r.stderr)


if __name__ == "__main__":
    unittest.main()
