#!/usr/bin/env python3
"""Tests of book-aws.py on synthetic data. Run: python3 -I cpp/scripts/test_book_aws.py

Each test plants one situation (anchors inside or outside 2 %, a row that failed its check, a step that is not OK, a cut schedule,
a pair that agrees or disagrees, the malformed cost file) and asserts the booking that the plan and its amendment of 2026-11-15
require. A test is only trusted because its negative twin exists.
"""
import importlib.util
import os
import unittest

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("book_aws", os.path.join(here, "book-aws.py"))
ba = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ba)


def row(lang, impl, preset, batch, eps, verified="ok", lanes="NA"):
    dev = "cuda" if impl.startswith(("cuda", "tornado")) else "cpu"
    c = [lang, impl, dev, preset, "u5", str(batch), "NA", lanes, str(eps), "1.0", "1.1", str(eps * 0.99), str(eps * 1.01),
         "NA", "NA", "NA", "NA", "NA", "NA", "1.0", "1.0", verified]
    return "\t".join(c)


def anchor_text(vals):
    """vals: {(when, round, lang): {(impl, preset): eps}} -> the text of anchors.tsv"""
    out = []
    for (when, rnd, lang), rows in vals.items():
        out.append("# anchors-%s round %d: %s" % (when, rnd, lang))
        for (impl, preset), eps in rows.items():
            out.append(row("cpp" if lang == "C++" else "java", impl, preset, 256, eps))
    return "\n".join(out) + "\n"


def anchors_from(c_pinned, j_tornado, c_simd=(1000.0,) * 4, j_vec=(4000.0,) * 4):
    order = [("start", 1), ("start", 2), ("end", 1), ("end", 2)]
    vals = {}
    for i, (w, r) in enumerate(order):
        vals[(w, r, "C++")] = {("cuda-pinned", "small"): c_pinned[i], ("simd", "small"): c_simd[i]}
        vals[(w, r, "Java")] = {("tornado", "small"): j_tornado[i], ("vector", "small"): j_vec[i]}
    blocks = ba.parse_blocks(anchor_text(vals), r"^# anchors-(start|end) round \d: ")
    return ba.read_anchors(blocks)


class AnchorRule(unittest.TestCase):
    def test_within_two_percent_is_valid(self):
        self.assertEqual(ba.anchor_verdict([100.0, 101.0, 100.5, 101.9])[1], "valid")

    def test_outside_two_percent_is_unsettled(self):
        self.assertEqual(ba.anchor_verdict([100.0, 100.0, 100.0, 102.5])[1], "unsettled")

    def test_a_step_between_the_two_runs_of_a_block_counts(self):
        # start and end agree (1000 / 1000), but the second run of each block is 34 % lower: the rule looks at all four values
        spread, st = ba.anchor_verdict([1153.6, 760.7, 1151.9, 760.0])
        self.assertEqual(st, "unsettled")
        self.assertAlmostEqual(spread, 1153.6 / 760.0)

    def test_judge_uses_the_anchor_of_the_named_program(self):
        anch, order = anchors_from([100, 100, 100, 100], [100, 100, 100, 100], c_simd=[1000, 1000, 1000, 1000])
        a = ba.Anchors(anch)
        self.assertEqual(a.judge("cpp:simd-nofma-l8", "small")[4], "cpp:simd")
        self.assertEqual(a.judge("cpp:cuda-pageable", "small")[4], "cpp:cuda-pinned")
        self.assertEqual(a.judge("java:tornado", "small")[4], "java:tornado")
        self.assertEqual(a.judge("cpp:simd-l16", "small")[0], "valid")

    def test_negative_twin_judge_follows_the_outlier(self):
        anch, order = anchors_from([100, 100, 100, 100], [100, 100, 100, 100], c_simd=[1000, 600, 1000, 600])
        a = ba.Anchors(anch)
        self.assertEqual(a.judge("cpp:simd-nofma-l8", "small")[0], "unsettled")
        self.assertEqual(a.judge("cpp:cuda-pinned", "small")[0], "valid")

    def test_an_anchor_with_one_value_missing_is_refused(self):
        anch, order = anchors_from([100, 100, 100, 100], [100, 100, 100, 100])
        anch[("cpp:simd", "small")]["end"].pop()
        with self.assertRaises(ba.BookingError):
            ba.Anchors(anch)


class Verdicts(unittest.TestCase):
    def test_gpu_below_five_percent_is_no_statement(self):
        self.assertIn("not resolved", ba.ratio_verdict([1.02, 1.03, 1.01, 1.02], "valid", True))

    def test_valid_above_five_percent_on_every_pair_is_real(self):
        self.assertTrue(ba.ratio_verdict([1.10, 1.12, 1.11, 1.10], "valid", True).startswith("real: C++ faster"))

    def test_pairs_that_disagree_on_the_direction_give_no_real_difference(self):
        self.assertNotIn("real", ba.ratio_verdict([1.10, 0.90, 1.10, 1.10], "valid", True))

    def test_unsettled_is_never_real(self):
        v = ba.ratio_verdict([0.2, 0.21, 0.3, 0.2], "unsettled", False)
        self.assertTrue(v.startswith("indicative only (unsettled)"))
        self.assertNotIn("real", v)

    def test_unsettled_below_five_percent_is_no_statement(self):
        self.assertTrue(ba.ratio_verdict([1.01, 1.02, 0.99, 1.0], "unsettled", True).startswith("no statement"))

    def test_block_division_is_always_labelled_as_blocks(self):
        self.assertIn("blocks not interleaved", ba.block_verdict(0.5))
        self.assertIn("blocks not interleaved", ba.block_verdict(1.01))


class Verification(unittest.TestCase):
    def blocks(self, text):
        return ba.parse_blocks(text, r"^# (hardened|unchecked), ")

    HEAD = "# hardened, l8: SMALL\n# golden manifest checked; presets small, batches 1 16, impls simd cuda-pinned, variant auto\n"

    def test_good_block_counts_its_rows(self):
        rows = "\n".join(row("cpp", i, "small", b, 10.0) for i in ("simd", "cuda-pinned") for b in (1, 16))
        self.assertEqual(ba.check_blocks("t", self.blocks(self.HEAD + rows + "\n")), 4)

    def test_a_row_that_is_not_ok_fails(self):
        rows = "\n".join(row("cpp", i, "small", b, 10.0, verified="FAIL" if (i, b) == ("simd", 16) else "ok") for i in ("simd", "cuda-pinned") for b in (1, 16))
        with self.assertRaises(ba.BookingError):
            ba.check_blocks("t", self.blocks(self.HEAD + rows + "\n"))

    def test_a_missing_case_fails(self):
        rows = "\n".join(row("cpp", i, "small", b, 10.0) for i in ("simd", "cuda-pinned") for b in (1, 16))
        rows = "\n".join(rows.split("\n")[:-1])
        with self.assertRaises(ba.BookingError):
            ba.check_blocks("t", self.blocks(self.HEAD + rows + "\n"))

    def test_a_skipped_case_makes_up_the_schedule(self):
        rows = "\n".join(row("cpp", i, "small", b, 10.0) for i in ("simd", "cuda-pinned") for b in (1, 16))
        rows = "\n".join(rows.split("\n")[:-1]) + "\n# skipped: cuda-pinned small u5 batch 16 (one call would take about 3.5 s)"
        blocks = self.blocks(self.HEAD + rows + "\n")
        self.assertEqual(ba.check_blocks("t", blocks), 3)
        self.assertEqual(len(blocks[0]["skipped"]), 1)

    def test_summary_all_ok(self):
        steps, rc = ba.parse_summary("backend: cuda\nsuite: cpp\nexit status: 0\nseconds: 10\napt-packages: OK\ngate: passed\nbuild: OK\n")
        self.assertEqual(sorted(steps), ["apt-packages", "build"])

    def test_summary_with_a_failed_step_fails(self):
        with self.assertRaises(ba.BookingError):
            ba.parse_summary("exit status: 0\nbuild: FAIL\napt-packages: OK\n")

    def test_summary_with_a_skipped_step_fails(self):
        with self.assertRaises(ba.BookingError):
            ba.parse_summary("exit status: 0\ncpp-sanitizer: SKIP\nbuild: OK\n")

    def test_summary_with_exit_status_not_zero_fails(self):
        with self.assertRaises(ba.BookingError):
            ba.parse_summary("exit status: 4\nbuild: OK\n")


class Cost(unittest.TestCase):
    def test_the_file_as_written_by_aws_spot_sh(self):
        c = ba.parse_cost("cpp\t2026-11-15T17:17:06Z\t1828\t0.214400\nNone\t0.1089\t0.26\n")
        self.assertEqual((c["seconds"], c["price"], c["estimate"], c["worst"]), (1828, 0.2144, 0.1089, 0.26))

    def test_another_layout_is_refused(self):
        with self.assertRaises(ba.BookingError):
            ba.parse_cost("cpp\t2026-11-15T17:17:06Z\t1828\t0.2144\t0.1089\t0.26\n")


class Aggregation(unittest.TestCase):
    def test_two_blocks_of_the_cuda_rows_give_median_and_full_range(self):
        text = ("# hardened, l8: S\n# golden manifest checked; presets small, batches 256, impls cuda-pinned, variant auto\n" + row("cpp", "cuda-pinned", "small", 256, 100.0) +
                "\n# hardened, l16: S\n# golden manifest checked; presets small, batches 256, impls cuda-pinned, variant auto\n" + row("cpp", "cuda-pinned", "small", 256, 110.0) + "\n")
        blocks = ba.parse_blocks(text, r"^# (hardened|unchecked), ")
        anch, order = anchors_from([100] * 4, [100] * 4)
        j = ba.parse_blocks("# vector and tornado\n# golden cpu [small], batches [256], impls [tornado]\n" + row("java", "tornado", "small", 256, 100.0) + "\n",
                            r"^# (vector and tornado|cold start)$")
        ablocks = ba.parse_blocks(anchor_text({(w, r, l): ({("cuda-pinned", "small"): 100.0, ("simd", "small"): 1.0} if l == "C++" else
                                                           {("tornado", "small"): 100.0, ("vector", "small"): 1.0})
                                               for w, r in (("start", 1), ("start", 2), ("end", 1), ("end", 2)) for l in ("C++", "Java")}),
                                  r"^# anchors-(start|end) round \d: ")
        bk = ba.Booking(blocks, j, ablocks)
        med, lo, hi = bk.stat(("cpp:cuda-pinned", "small", 256))
        self.assertEqual(med, 105.0)
        self.assertAlmostEqual(lo, 100.0 * 0.99)
        self.assertAlmostEqual(hi, 110.0 * 1.01)

    def test_only_one_main_block_is_refused(self):
        text = "# hardened, l8: S\n# golden manifest checked; presets small, batches 256, impls cuda-pinned, variant auto\n" + row("cpp", "cuda-pinned", "small", 256, 100.0) + "\n"
        blocks = ba.parse_blocks(text, r"^# (hardened|unchecked), ")
        ablocks = ba.parse_blocks(anchor_text({("start", 1, "C++"): {("cuda-pinned", "small"): 1.0}}), r"^# anchors-(start|end) round \d: ")
        with self.assertRaises(ba.BookingError):
            ba.Booking(blocks, [], ablocks)

    def test_the_four_pairs_are_the_adjacent_runs_of_a_round(self):
        # C++ values per run 100, 100, 100, 100; Java 50, 50, 100, 200: the ratio per pair is 2, 2, 1, 0.5 in the order of the runs
        anch, order = anchors_from([100, 100, 100, 100], [50, 50, 100, 200])
        c = anch[("cpp:cuda-pinned", "small")]
        j = anch[("java:tornado", "small")]
        ratios = [a / b for a, b in zip(c["start"] + c["end"], j["start"] + j["end"])]
        self.assertEqual(ratios, [2.0, 2.0, 1.0, 0.5])


if __name__ == "__main__":
    unittest.main(verbosity=1)
