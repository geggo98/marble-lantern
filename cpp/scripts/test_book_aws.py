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
            ba.parse_summary("exit status: 0\nbuild: FAIL (1)\napt-packages: OK\n")

    def test_summary_with_a_skipped_step_other_than_the_sanitizer_fails(self):
        with self.assertRaises(ba.BookingError):
            ba.parse_summary("exit status: 0\nbuild: SKIP (no toolchain)\napt-packages: OK\n")

    def test_summary_with_the_real_skip_format_is_read_and_names_the_reason(self):
        steps, _ = ba.parse_summary("exit status: 0\ncpp-sanitizer: SKIP (compute-sanitizer not found on this machine)\nbuild: OK\n")
        self.assertEqual(steps, {"cpp-sanitizer": "SKIP (compute-sanitizer not found on this machine)", "build": "OK"})
        self.assertIn("compute-sanitizer not found", ba.steps_ok_text(steps))
        self.assertNotIn("All", ba.steps_ok_text(steps))

    def test_summary_with_the_real_fail_format_and_a_zero_exit_status_fails(self):
        with self.assertRaises(ba.BookingError) as cm:
            ba.parse_summary("exit status: 0\ncpp-sanitizer: SKIP (compute-sanitizer not found)\ngolden-hash: FAIL (1)\nbuild: OK\n")
        self.assertIn("golden-hash: FAIL (1)", str(cm.exception))

    def test_summary_that_is_all_ok_says_all(self):
        steps, _ = ba.parse_summary("exit status: 0\nbuild: OK\napt-packages: OK\n")
        self.assertEqual(ba.steps_ok_text(steps), "All 2 steps of the run ended OK")

    def test_summary_with_exit_status_not_zero_fails(self):
        with self.assertRaises(ba.BookingError):
            ba.parse_summary("exit status: 4\nbuild: OK\n")


class Cost(unittest.TestCase):
    def test_the_file_as_written_by_aws_spot_sh(self):
        c = ba.parse_cost("cpp\t2026-11-15T17:17:06Z\t1828\t0.214400\t0.1089\t0.26\n")
        self.assertEqual((c["seconds"], c["price"], c["estimate"], c["worst"]), (1828, 0.2144, 0.1089, 0.26))

    def test_an_assumed_on_demand_price_is_read(self):
        c = ba.parse_cost("cpp\t2026-11-15T17:17:06Z\t1828\t0.66-assumed\t0.3351\t0.5\n")
        self.assertEqual(c["price"], 0.66)

    def test_the_old_two_line_layout_is_refused(self):
        with self.assertRaises(ba.BookingError):
            ba.parse_cost("cpp\t2026-11-15T17:17:06Z\t1828\t0.214400\nNone\t0.1089\t0.26\n")


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


CROSS_RE = r"^# (vector and tornado|cold start( round \d+: .*)?)$"


def cold_row(lang, impl, preset, batch, first):
    c = [lang, impl, "cpu" if impl in ("simd", "vector") else "cuda", preset, "u5", str(batch), "NA", "16", "NA", "NA", "NA", "NA", "NA",
         "NA", "NA", "NA", "NA", "NA", "NA", str(first), "1.0", "ok"]
    return "\t".join(c)


def cold_block(lang, impl, preset, batch, rnd, construct, first, uptime, label_batch=None, extra_row=False):
    head = "# hardened, " if lang == "cpp" else "# "
    out = ["%scold start round %d: %s %s batch %s" % (head, rnd, impl, preset, label_batch or batch),
           "# golden manifest checked; presets %s, batches %d, impls %s, variant auto, cold" % (preset, batch, impl),
           cold_row(lang, impl, preset, batch, first)]
    if extra_row:
        out.append(cold_row(lang, impl, preset, batch, first))
    out.append("# cold: construct %.1f ms, first call %.1f ms, %s uptime%s %.1f ms" % (
        construct, first, "process" if lang == "cpp" else "JVM", " (from main)" if lang == "cpp" else "", uptime))
    return "\n".join(out) + "\n"


def booking_with_cold(nn_cold, java_cold):
    c_main = lambda lanes: ("# hardened, l%d: S\n# golden manifest checked; presets small, batches 256, impls cuda-pinned, variant auto\n" % lanes +
                            row("cpp", "cuda-pinned", "small", 256, 100.0) + "\n")
    nn = ba.parse_blocks(c_main(8) + c_main(16) + nn_cold, r"^# (hardened|unchecked), ")
    j = ba.parse_blocks("# vector and tornado\n# golden manifest checked; presets [small], batches [256], impls [tornado]\n" + row("java", "tornado", "small", 256, 100.0) + "\n" + java_cold, CROSS_RE)
    ab = ba.parse_blocks(anchor_text({(w, r, l): ({("cuda-pinned", "small"): 100.0, ("simd", "small"): 1.0} if l == "C++" else
                                                  {("tornado", "small"): 100.0, ("vector", "small"): 1.0})
                                      for w, r in (("start", 1), ("start", 2), ("end", 1), ("end", 2)) for l in ("C++", "Java")}),
                         r"^# anchors-(start|end) round \d: ")
    return ba.Booking(nn, j, ab), nn, j


class ColdStart(unittest.TestCase):
    def three(self, lang, impl, batch, vals):
        return "".join(cold_block(lang, impl, "small", batch, i + 1, *v) for i, v in enumerate(vals))

    def test_round_blocks_are_blocks_of_their_own_and_do_not_fall_into_the_main_block(self):
        bk, nn, j = booking_with_cold("", self.three("java", "vector", 1, [(4.5, 142.7, 686.0), (6.4, 147.0, 703.0), (6.3, 144.2, 708.0)]))
        self.assertEqual(len(j), 4)
        self.assertEqual(len(j[0]["rows"]), 1)           # the main block keeps its one row
        self.assertEqual(len(bk.jcold), 3)
        self.assertEqual(ba.check_blocks("c", j), 4)

    def test_three_rounds_give_median_lowest_highest_and_are_unanchored(self):
        bk, nn, j = booking_with_cold(self.three("cpp", "simd", 1, [(0.1, 0.2, 111.7), (0.1, 0.3, 113.6), (0.1, 0.2, 109.9)]),
                                      self.three("java", "vector", 1, [(4.5, 142.7, 686.0), (6.4, 147.0, 703.0), (6.3, 144.2, 708.0)]))
        bk.cold()
        rows = {(r[1], r[4]): r for r in bk.rows if r[0] == "cold_start"}
        r = rows[("cpp:simd", "process_to_first_result_ms")]
        self.assertEqual((r[5], r[6], r[7], r[8]), ("111.7", "109.9", "113.6", "unanchored"))
        r = rows[("java:vector", "first_call_ms")]
        self.assertEqual((r[5], r[6], r[7]), ("144.2", "142.7", "147"))
        self.assertEqual(len(bk.cold_runs[("cpp:simd", "small", 1)]), 3)
        self.assertFalse([r for r in bk.rows if r[0] == "not_measured"])

    def test_a_row_that_does_not_match_the_label_is_refused(self):
        bk, nn, j = booking_with_cold(cold_block("cpp", "simd", "small", 1, 1, 0.1, 0.2, 111.0, label_batch=256), "")
        with self.assertRaises(ba.BookingError):
            bk.cold()

    def test_a_cold_block_with_two_rows_is_refused(self):
        bk, nn, j = booking_with_cold(cold_block("cpp", "simd", "small", 1, 1, 0.1, 0.2, 111.0, extra_row=True), "")
        with self.assertRaises(ba.BookingError):
            bk.cold()

    def test_cases_stay_apart_by_batch(self):
        bk, nn, j = booking_with_cold(self.three("cpp", "simd", 1, [(0.1, 0.2, 111.0)] * 3) + self.three("cpp", "simd", 256, [(0.1, 50.0, 160.0)] * 3), "")
        bk.cold()
        self.assertEqual(sorted(k[2] for k in bk.cold_runs), [1, 256])

    def test_the_old_single_cold_block_of_run_1_is_still_booked_as_before(self):
        old_j = ("# cold start\n# golden manifest checked; presets [small], batches [1], impls [vector], cold\n" + cold_row("java", "vector", "small", 1, 142.7) +
                 "\n# cold: construct 4.5 ms, first call 142.7 ms, JVM uptime 686 ms\n")
        bk, nn, j = booking_with_cold("", old_j)
        bk.cold()
        self.assertTrue([r for r in bk.rows if r[0] == "not_measured" and r[4] == "cold_start_process"])
        self.assertTrue([r for r in bk.rows if r[0] == "cold_start" and r[1] == "java:vector"])


if __name__ == "__main__":
    unittest.main(verbosity=1)
