#!/usr/bin/env python3
"""Tests of book-nn.py on a synthetic session. Run: python3 -I cpp/scripts/test_book_nn.py

Each test plants one situation (anchor inside or outside 2 %, a GPU row, a GPU pair that agrees or disagrees, a failed check) and
asserts the booking that the amendment of 2026-11-15 requires. A test is only trusted because its negative twin exists.
"""
import atexit
import importlib.util
import os
import shutil
import tempfile
import unittest

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("book_nn", os.path.join(here, "book-nn.py"))
bn = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bn)

STEPS_HEAD = "name\tkind\tlang\tpair\trc\twall_s\tload_before\tload_after\tforeign_cpu_max\tgpu_util_before\ttop3_before\n"


def row(lang, impl, batch, eps, verified="ok", preset="small"):
    dev = "cpu" if impl in ("scalar", "vector", "reference", "simd", "simd-nofma") else "metal"
    c = [lang, impl, dev, preset, "u5", str(batch), "NA", "NA", str(eps), "1.0", "1.1", str(eps * 0.99), str(eps * 1.01),
         "NA", "NA", "NA", "NA", "NA", "NA", "1.0", "2.0", verified]
    return "\t".join(c)


def java_header(impls, batches):
    return "# golden cpp/golden manifest checked; presets [small], batches [%s], impls [%s]\n" % (", ".join(map(str, batches)), ", ".join(impls))


def cpp_header(impls, batches):
    return "# golden manifest checked; presets small, batches %s, impls %s, variant auto\n" % (" ".join(map(str, batches)), " ".join(impls))


class Work:
    def __init__(self, anchors, pairs, rc="0", verified="ok", drop_row=False):
        """anchors: {(lang, impl): (start, mid, end)}; pairs: list of (java_eps, cpp_eps) for java vector/tornado and cpp simd/metal-copy"""
        self.dir = tempfile.mkdtemp()
        atexit.register(shutil.rmtree, self.dir, True)
        steps = []
        for phase_i, phase in enumerate(("start", "mid", "end")):
            for lang, impls in (("cpp", ("simd", "metal-copy")), ("java", ("vector", "tornado"))):
                name = "anchors-%s-%s" % (phase, lang)
                text = "lang\timpl\n"
                for impl in impls:
                    text += row(lang, impl, 256, anchors[(lang, impl)][phase_i]) + "\n"
                self.put(name, text)
                steps.append("%s\tanchors\t%s\t0\t0\t20.0\t2.0\t2.0\t100\t10\tx" % (name, lang))
        for i, (jv, jt, cs, cm) in enumerate(pairs, start=1):
            jt_rows = [row("java", "vector", 1, jv), row("java", "tornado", 1, jt)]
            ct_rows = [row("cpp", "simd", 1, cs), row("cpp", "metal-copy", 1, cm)]
            if drop_row:
                ct_rows = ct_rows[:1]
            self.put("main-p%d-java" % i, java_header(["vector", "tornado"], [1]) + "lang\timpl\n" + "\n".join(jt_rows) + "\n"
                     + "# skipped: vector small u5 batch 4 (one call would take about 4.0 s)\n"[:0])
            self.put("main-p%d-cpp" % i, cpp_header(["simd", "metal-copy"], [1]) + "lang\timpl\n" + "\n".join(ct_rows) + "\n"
                     + "# gpu: metal-copy small u5 batch 1: GPU time of the command buffer 0.5 ms per call\n")
            steps.append("main-p%d-java\tmain\tjava\t%d\t%s\t10.0\t2.0\t2.0\t100\t10\tx" % (i, i, rc))
            steps.append("main-p%d-cpp\tmain\tcpp\t%d\t0\t10.0\t2.0\t2.0\t100\t10\tx" % (i, i))
        if verified != "ok":
            self.put("main-p1-java", java_header(["vector", "tornado"], [1]) + "lang\timpl\n" + row("java", "vector", 1, 100, verified) + "\n"
                     + row("java", "tornado", 1, 100) + "\n")
        with open(os.path.join(self.dir, "steps.tsv"), "w") as f:
            f.write(STEPS_HEAD + "\n".join(steps) + "\n")

    def put(self, name, text):
        with open(os.path.join(self.dir, name + ".tsv"), "w") as f:
            f.write(text)

    def book(self):
        s = bn.Session(self.dir)
        report = bn.verify(s)
        b = bn.Book(s)
        b.anchor_table()
        b.throughput()
        b.ratios()
        b.phases()
        return b, report


STABLE = {("cpp", "simd"): (100, 100, 100), ("java", "vector"): (90, 90, 90), ("cpp", "metal-copy"): (1000, 800, 900), ("java", "tornado"): (1000, 750, 950)}


def find(b, table, program, metric=None):
    return [r for r in b.rows if r["table"] == table and r["program"] == program and (metric is None or r["metric"] == metric)]


class BookingTest(unittest.TestCase):
    def test_cpu_row_with_stable_anchor_is_valid(self):
        w = Work(STABLE, [(90, 1000, 100, 1100)] * 2)
        b, _ = w.book()
        r = find(b, "throughput", "cpp:simd")[0]
        self.assertEqual(r["status"], "valid")
        self.assertEqual((r["anchor_start"], r["anchor_end"]), (100, 100))

    def test_cpu_row_with_drifting_anchor_is_unsettled_not_dropped(self):
        a = dict(STABLE)
        a[("java", "vector")] = (90, 90, 93)      # end/start 1.033
        w = Work(a, [(90, 1000, 100, 1100)] * 2)
        b, _ = w.book()
        r = find(b, "throughput", "java:vector")[0]
        self.assertEqual(r["status"], "unsettled")
        self.assertIn("CPU anchor outside 2 %", r["note"])
        self.assertEqual(r["median"], 90)           # the number is kept as measured

    def test_gpu_row_is_always_unsettled_and_carries_the_drift(self):
        a = dict(STABLE)
        a[("cpp", "metal-copy")] = (1000, 1000, 1000)    # even a perfectly stable GPU anchor does not make a GPU row valid
        w = Work(a, [(90, 1000, 100, 1100)] * 2)
        b, _ = w.book()
        r = find(b, "throughput", "cpp:metal-copy")[0]
        self.assertEqual(r["status"], "unsettled")
        self.assertIn("GPU clock drift", r["note"])
        self.assertIn("start/mid/end", r["note"])
        r = find(b, "throughput", "java:tornado")[0]
        self.assertEqual((r["anchor_start"], r["anchor_mid"], r["anchor_end"]), (1000, 750, 950))
        self.assertIn("lowest/start 0.7500", r["note"])

    def test_gpu_ratio_below_5_percent_makes_no_statement(self):
        w = Work(STABLE, [(90, 1000, 100, 1030)] * 2)
        b, _ = w.book()
        r = find(b, "ratio", "cpp:metal-copy / java:tornado")[0]
        self.assertEqual(r["status"], "unsettled")
        self.assertIn("no statement (difference below 5 %)", r["note"])
        self.assertIn("unsettled (GPU clock drift)", r["note"])

    def test_gpu_ratio_above_5_percent_is_indicative_only(self):
        w = Work(STABLE, [(90, 1000, 100, 1200)] * 2)
        b, _ = w.book()
        r = find(b, "ratio", "cpp:metal-copy / java:tornado")[0]
        self.assertIn("indicative only", r["note"])
        self.assertIn("unsettled (GPU clock drift)", r["note"])
        self.assertNotIn("real", r["note"])

    def test_gpu_ratio_with_pairs_on_both_sides_of_one_makes_no_statement(self):
        w = Work(STABLE, [(90, 1000, 100, 1200), (90, 1000, 100, 900)])
        b, _ = w.book()
        r = find(b, "ratio", "cpp:metal-copy / java:tornado")[0]
        self.assertIn("disagree on the direction", r["note"])

    def test_cpu_ratio_is_real_only_for_valid_rows(self):
        w = Work(STABLE, [(90, 1000, 100, 1000), (90, 1000, 100, 1000)])
        b, _ = w.book()
        r = find(b, "ratio", "cpp:simd / java:vector")[0]
        self.assertEqual(r["status"], "valid")
        self.assertIn("real: C++ faster", r["note"])
        a = dict(STABLE)
        a[("java", "vector")] = (90, 90, 80)
        b, _ = Work(a, [(90, 1000, 100, 1000)] * 2).book()
        r = find(b, "ratio", "cpp:simd / java:vector")[0]
        self.assertEqual(r["status"], "unsettled")
        self.assertIn("indicative only", r["note"])

    def test_columns_are_complete(self):
        b, _ = Work(STABLE, [(90, 1000, 100, 1100)] * 2).book()
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.tsv")
            bn.write_tsv(p, b.rows)
            lines = open(p).read().rstrip("\n").split("\n")
        self.assertEqual(lines[0].split("\t"), bn.COLUMNS)
        self.assertTrue(all(len(l.split("\t")) == len(bn.COLUMNS) for l in lines))
        self.assertGreater(len(lines), 10)


class VerificationTest(unittest.TestCase):
    def test_clean_session_passes_and_counts_rows(self):
        _, report = Work(STABLE, [(90, 1000, 100, 1100)] * 2).book()
        main = dict(report)["main"]
        self.assertEqual((main["rows"], main["ok"]), (8, 8))

    def test_a_row_that_is_not_verified_fails_the_booking(self):
        with self.assertRaises(bn.BookingError) as e:
            Work(STABLE, [(90, 1000, 100, 1100)] * 2, verified="FAIL").book()
        self.assertIn("not verified", str(e.exception))

    def test_a_failed_step_fails_the_booking(self):
        with self.assertRaises(bn.BookingError) as e:
            Work(STABLE, [(90, 1000, 100, 1100)] * 2, rc="2").book()
        self.assertIn("rc 2", str(e.exception))

    def test_a_missing_row_fails_the_booking(self):
        with self.assertRaises(bn.BookingError) as e:
            Work(STABLE, [(90, 1000, 100, 1100)] * 2, drop_row=True).book()
        self.assertIn("scheduled", str(e.exception))

    def test_schedule_line_parsing(self):
        self.assertEqual(bn.parse_schedule("# golden cpp/golden manifest checked; presets [small, large], batches [1, 16, 64], impls [vector, tornado]"), 12)
        self.assertEqual(bn.parse_schedule("# golden manifest checked; presets small large, batches 256, impls simd simd-nofma metal-copy metal-shared, variant auto"), 8)


if __name__ == "__main__":
    unittest.main()
