"""Tests of scripts/guides/analyze.py (offline; run: python3 -m unittest discover -s scripts/guides/test).

The fixture is a synthetic game file with three positions, built so that every expected number follows from a hand calculation
(see FIXTURE below). ANALYZE_PY=<path> runs the tests against another copy of analyze.py (used for mutation checks).
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
GUIDES = HERE.parent
ANALYZE = Path(os.environ.get("ANALYZE_PY", GUIDES / "analyze.py"))

spec = importlib.util.spec_from_file_location("analyze_mod", ANALYZE)
am = importlib.util.module_from_spec(spec)
spec.loader.exec_module(am)

HEADER = {"header": True, "version": 1, "variant": "r5", "mode": "A", "game": 0, "minVisits": 16}


def pos(ply, moves, scores, visits, values, value, probe=()):
    return {"ply": ply, "mover": ply % 2, "n": len(moves), "h": [], "cls": {"moves": moves, "scores": scores},
            "net": {"moves": moves, "visits": visits, "values": values, "value": value}, "probe": [list(p) for p in probe]}


def counter(ply, fav, cbest, res_fav, res_cbest):
    return {"counter": True, "ply": ply, "h": [], "fav": fav, "cbest": cbest, "tip": True, "trap": True,
            "results": {str(fav): dict(zip(("3000", "4000", "5000"), res_fav)), str(cbest): dict(zip(("3000", "4000", "5000"), res_cbest))}}


# FIXTURE
# ply 4 (P1): classical best move 1 (score 100); favourite 2 (100 visits, score 60 = loss 40 = band good: no tip).
#   Net value of the favourite 0.5 (root value, 100 visits). Move 1 has 5 visits (< 16), so its probe value 0.2 counts.
#   Difference 0.5 - 0.2 = 0.30: trap for delta 0.15, 0.20, 0.25 and 0.30 (boundary), not for 0.40. Not balanced (value 0.9).
# ply 6 (P2): classical best 1 (100), favourite 2 (score -20 = loss 120 = band fair): tip. Net value of 2: 0.4 (50 visits), of 1:
#   probe 0.35; difference 0.05, no trap at any delta. Balanced (value 0.1).
# ply 8 (P3): favourite = classical best: neither mark. Balanced (value 0.0).
P1 = pos(4, [1, 2, 3], [100, 60, -100], [5, 100, 10], [0.1, 0.5, -0.3], 0.9, probe=[(1, 0.2)])
P2 = pos(6, [1, 2], [100, -20], [3, 50], [0.0, 0.4], 0.1, probe=[(1, 0.35)])
P3 = pos(8, [1, 2], [100, 90], [80, 20], [0.2, 0.1], 0.0)
# Counter-move lines. P1: the trap (move 1) scores mean(0, 0, 1) = 1/3, the favourite (move 2) 1: trap difference -2/3.
# P2: the favourite (move 2) scores mean(1, 1, 0.5) = 5/6, the classical best (move 1) mean(0, 0.5, 0) = 1/6: tip difference 2/3.
C1 = counter(4, 2, 1, (1, 1, 1), (0, 0, 1))
C2 = counter(6, 2, 1, (1, 1, 0.5), (0, 0.5, 0))
END = {"end": True, "plies": 40, "outcome": {"kind": "win", "winner": 0}, "netSeat": 0, "mode": "A"}


def write(directory, name, lines, truncate=0):
    text = "\n".join(json.dumps(x) for x in lines) + "\n"
    if truncate:
        text = text[:-truncate]
    p = Path(directory) / name
    p.write_text(text)
    return p


class Tmp(unittest.TestCase):
    def setUp(self):
        self._t = tempfile.TemporaryDirectory()
        self.dir = self._t.name

    def tearDown(self):
        self._t.cleanup()


class Frequency(Tmp):
    def load_games(self):
        write(self.dir, "r5-A-0.jsonl", [HEADER, P1, C1, P2, C2, P3, END])
        groups, skipped = am.load(self.dir)
        self.assertEqual(skipped, [])
        return groups[("r5", "A")]

    def test_counts_all_positions(self):
        f = am.frequency(self.load_games(), (0.15, 0.20, 0.25, 0.30, 0.40))["all"]
        self.assertEqual(f["positions"], 3)
        self.assertEqual(f["tip"], 1)
        self.assertEqual([f["trap"][k]["count"] for k in ("0.15", "0.20", "0.25", "0.30", "0.40")], [1, 1, 1, 1, 0])
        self.assertAlmostEqual(f["trap"]["0.25"]["rate"], 1 / 3)

    def test_counts_balanced_positions_only(self):
        f = am.frequency(self.load_games(), (0.15, 0.30))["balanced"]
        self.assertEqual(f["positions"], 2)               # P2 and P3; P1 has |value| 0.9
        self.assertEqual(f["tip"], 1)
        self.assertEqual(f["trap"]["0.15"]["count"], 0)   # the only trap position is not balanced

    def test_probe_value_is_used_below_min_visits(self):
        d = am.digest({**P1, "minVisits": 16})
        self.assertEqual((d["qfav"], d["qbest"]), (0.5, 0.2))     # root value of 2, probe of 1 (root value would be 0.1)
        d = am.digest({**P1, "minVisits": 3})                     # 5 visits are enough: the root value 0.1 counts
        self.assertEqual(d["qbest"], 0.1)

    def test_visit_threshold_is_inclusive(self):
        # exactly minVisits visits: the root value counts, the probe does not
        p = pos(4, [1, 2], [100, 60], [16, 50], [0.1, 0.5], 0.0, probe=[(1, 0.9)])
        self.assertEqual(am.digest({**p, "minVisits": 16})["qbest"], 0.1)
        self.assertEqual(am.digest({**p, "minVisits": 17})["qbest"], 0.9)

    def test_balanced_boundary_is_exclusive(self):
        self.assertFalse(am.digest({**pos(4, [1, 2], [100, 60], [16, 50], [0.1, 0.5], 0.5), "minVisits": 16})["balanced"])
        self.assertTrue(am.digest({**pos(4, [1, 2], [100, 60], [16, 50], [0.1, 0.5], 0.49), "minVisits": 16})["balanced"])

    def test_favourite_is_best_move_means_no_mark(self):
        d = am.digest({**P3, "minVisits": 16})
        self.assertEqual(d["fav"], d["cbest"])
        self.assertFalse(am.is_tip(d))
        self.assertFalse(am.is_trap(d, 0.0))

    def test_bands_use_loss_to_best(self):
        self.assertEqual([am.band(x) for x in (0, 30, 31, 90, 91, 180, 181, 300, 301)],
                         ["top", "top", "good", "good", "fair", "fair", "weak", "weak", "poor"])

    def test_mate_scores_are_collapsed(self):
        # a win in 3 and a win in 9 plies are the same: loss 0, band top, so no tip
        pos_m = pos(4, [1, 2], [999_990, 999_970], [3, 50], [0.0, 0.4], 0.1)
        d = am.digest({**pos_m, "minVisits": 16})
        self.assertEqual(d["fav_band"], "top")


class Validity(Tmp):
    def games(self):
        write(self.dir, "r5-A-0.jsonl", [HEADER, P1, C1, P2, C2, P3, END])
        return am.load(self.dir)[0][("r5", "A")]

    def test_tip_and_trap_differences(self):
        v = am.validity(self.games(), (0.15, 0.40), 200, 1, "position")
        self.assertEqual(v["tip"]["n"], 1)
        self.assertAlmostEqual(v["tip"]["mean"], 2 / 3)
        self.assertAlmostEqual(v["trap"]["0.15"]["mean"], -2 / 3)
        self.assertEqual(v["trap"]["0.40"]["n"], 0)          # no trap position at 0.40
        self.assertIsNone(v["trap"]["0.40"]["mean"])

    def test_candidate_without_counter_line_is_counted_not_dropped_silently(self):
        write(self.dir, "r5-A-0.jsonl", [HEADER, P1, P2, C2, P3, END])      # C1 missing
        games = am.load(self.dir)[0][("r5", "A")]
        v = am.validity(games, (0.15,), 50, 1, "position")
        self.assertEqual((v["trap"]["0.15"]["n"], v["trap"]["0.15"]["missing_counter"]), (0, 1))

    def test_bootstrap_is_deterministic_and_seeded(self):
        clusters = [[x] for x in (-1, -1, 0, 0.5, 1, 0, -0.5, 1, 1, -1)]
        a = am.cluster_bootstrap(clusters, 500, "boot|1|x")
        b = am.cluster_bootstrap(clusters, 500, "boot|1|x")
        c = am.cluster_bootstrap(clusters, 500, "boot|2|x")
        self.assertEqual(a, b)
        self.assertNotEqual((a["lo"], a["hi"]), (c["lo"], c["hi"]))
        self.assertAlmostEqual(a["mean"], 0.0)               # hand sum: -1-1+0+0.5+1+0-0.5+1+1-1 = 0
        self.assertLess(a["lo"], 0.0)
        self.assertGreater(a["hi"], 0.0)

    def test_bootstrap_of_equal_values_has_no_width(self):
        r = am.cluster_bootstrap([[0.25], [0.25], [0.25]], 100, "k")
        self.assertEqual((r["lo"], r["mean"], r["hi"]), (0.25, 0.25, 0.25))

    def test_bootstrap_interval_width_grows_with_spread(self):
        narrow = am.cluster_bootstrap([[0.0], [0.1]] * 20, 300, "k")
        wide = am.cluster_bootstrap([[-1.0], [1.0]] * 20, 300, "k")
        self.assertGreater(wide["hi"] - wide["lo"], narrow["hi"] - narrow["lo"])

    def test_percentile_by_hand(self):
        s = [1, 2, 3, 4, 5]
        self.assertEqual(am.percentile(s, 0.5), 3)
        self.assertEqual(am.percentile(s, 0.25), 2)
        self.assertAlmostEqual(am.percentile(s, 0.125), 1.5)
        self.assertIsNone(am.percentile([], 0.5))

    def test_cluster_by_game_resamples_whole_games(self):
        write(self.dir, "r5-A-1.jsonl", [{**HEADER, "game": 1}, P2, C2, END])
        games = am.load(self.dir)[0][("r5", "A")]
        write(self.dir, "r5-A-0.jsonl", [HEADER, P1, C1, P2, C2, END])
        games = am.load(self.dir)[0][("r5", "A")]
        v = am.validity(games, (0.15,), 100, 1, "game")
        self.assertEqual(v["tip"]["n"], 2)                   # one tip position in each of the two games


class Loading(Tmp):
    def test_truncated_and_unfinished_files_are_ignored(self):
        write(self.dir, "r5-A-0.jsonl", [HEADER, P1, END])
        write(self.dir, "r5-A-1.jsonl", [HEADER, P1, END], truncate=10)   # last line cut inside the JSON
        write(self.dir, "r5-A-2.jsonl", [HEADER, P1, P2])                 # no end line
        groups, skipped = am.load(self.dir)
        self.assertEqual(len(groups[("r5", "A")]), 1)
        self.assertEqual(sorted(os.path.basename(s) for s in skipped), ["r5-A-1.jsonl", "r5-A-2.jsonl"])

    def test_groups_by_variant_and_mode(self):
        write(self.dir, "r5-A-0.jsonl", [HEADER, P1, END])
        write(self.dir, "r4-B-3.jsonl", [HEADER, P1, END])
        groups, _ = am.load(self.dir)
        self.assertEqual(sorted(groups), [("r4", "B"), ("r5", "A")])

    def test_parse_name(self):
        self.assertEqual(am.parse_name("/x/r5-A-12.jsonl"), ("r5", "A", 12))


class Cli(Tmp):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(ANALYZE), *args], capture_output=True, text=True)

    def test_prints_tables_and_writes_json(self):
        write(self.dir, "r5-A-0.jsonl", [HEADER, P1, C1, P2, C2, P3, END])
        out = os.path.join(self.dir, "res.json")
        r = self.run_cli(self.dir, "--json", out, "--resamples", "50")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("(a) FREQUENCY", r.stdout)
        self.assertIn("(b) VALIDITY", r.stdout)
        data = json.load(open(out))
        self.assertEqual(data["groups"]["r5-A"]["frequency"]["all"]["trap"]["0.30"]["count"], 1)
        self.assertEqual(sorted(data["groups"]), ["all-all", "r5-A", "r5-all"])

    def test_same_seed_same_json(self):
        write(self.dir, "r5-A-0.jsonl", [HEADER, P1, C1, P2, C2, P3, END])
        a, b = os.path.join(self.dir, "a.json"), os.path.join(self.dir, "b.json")
        self.run_cli(self.dir, "--json", a, "--resamples", "50")
        self.run_cli(self.dir, "--json", b, "--resamples", "50")
        self.assertEqual(open(a).read(), open(b).read())

    def test_empty_directory_fails(self):
        self.assertEqual(self.run_cli(self.dir).returncode, 1)


if __name__ == "__main__":
    unittest.main()
