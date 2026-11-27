"""Tests of scripts/elo/fit_re.py (offline; run: python3 scripts/elo/test/test_fit_re.py).

Expected values come from independent sources: closed forms worked by hand from the model in the docstring of
fit_re.py (noted at each test); the Paule-Mandel equation solved by hand for equal standard errors; Student t and
chi-square table values (t 0.975: 2.0452 at 29 df, 2.3646 at 7 df; chi-square 29 df: 16.047 and 45.722, 7 df: 1.690
and 16.013); synthetic data drawn from known ratings and a known between-edge tau with fixed seeds; and the published
numbers of models/ratings/ratings.json, which the evaluation of the Elo study computed with its own scripts before this
tool existed. FIT_RE_PY=<path> runs the tests against another copy of fit_re.py (used for mutation checks).
"""
import gzip
import importlib.util
import json
import math
import os
import random
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = Path(os.environ.get("FIT_RE_PY", HERE.parent / "fit_re.py"))
REPO = HERE.parents[2]
RECORDS = REPO / "models" / "runs" / "elo-study"
PUBLISHED = REPO / "models" / "ratings" / "ratings.json"


def load():
    sys.path.insert(0, str(HERE.parent))           # fit_re imports its sibling fit.py
    spec = importlib.util.spec_from_file_location("fit_re_under_test", SRC)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


fit_re = load()
K = 400 / math.log(10)


def expected(d):
    return 1 / (1 + 10 ** (-d / 400))


def stat(eid, a, b, y, se):
    return fit_re.EdgeStat(eid, [a, b], y, se)


class Files:
    """Writes an edges.jsonl and its pairs files into a directory."""

    def __init__(self, root):
        self.root = Path(root)
        self.edges = []

    def pair_edge(self, eid, a, b, scores, scale="2p-adj-r5", totals=None):
        d = {"id": eid, "scale": scale, "kind": "pair", "players": [a, b], "unitsPlanned": len(scores) or 200}
        if totals is not None:
            d.update(file=None, totals=totals, unitsPlanned=sum(totals.values()) // 2)
        else:
            name = f"{eid}.jsonl"
            (self.root / name).write_text("".join(json.dumps({"pair": i, "score": s}) + "\n"
                                                  for i, s in enumerate(scores)))
            d["file"] = name
        self.edges.append(d)

    def ovt_edge(self, eid, c, o, results, scale="3p-adj-r4"):
        name = f"{eid}.jsonl"
        (self.root / name).write_text("".join(json.dumps({"opening": i, "r0": r[0], "r1": r[1], "r2": r[2]}) + "\n"
                                              for i, r in enumerate(results)))
        self.edges.append({"id": eid, "scale": scale, "kind": "one-vs-two", "players": [c, o], "file": name,
                           "unitsPlanned": len(results)})

    def write(self):
        p = self.root / "edges.jsonl"
        p.write_text("".join(json.dumps(e) + "\n" for e in self.edges))
        return p


class Tmp(unittest.TestCase):
    def setUp(self):
        self._t = tempfile.TemporaryDirectory()
        self.dir = Path(self._t.name)
        self.f = Files(self.dir)

    def tearDown(self):
        self._t.cleanup()

    def stats(self):
        return [fit_re.edge_stat(e) for e in fit_re.fit.load_edges([self.f.write()])]


# ------------------------------------------------------------------------------------------------- per-edge statistic

class EdgeStatistic(Tmp):
    def test_pair_edge_by_hand(self):
        # 4 pairs with scores 1, 0.5, 0.5, 1: 6 points of 8 games; p = 6.5 / 9 = 13/18, so y = 400 log10(6.5 / 2.5).
        # Unit points 2, 1, 1, 2 around the mean 1.5: sum of squares 1, var = 1/3 * 4 / 64 = 1/48 (above the floor
        # p(1-p)/8/4 = 0.0063). se = K sqrt(1/48) / (65/324).
        self.f.pair_edge("e", "a", "b", [1.0, 0.5, 0.5, 1.0])
        (s,) = self.stats()
        self.assertAlmostEqual(s.y, 400 * math.log10(2.6), places=9)
        self.assertAlmostEqual(s.se, K * math.sqrt(1 / 48) / (65 / 324), places=9)
        self.assertEqual((s.n, s.pts), (8, 6.0))

    def test_variance_floor_applies_when_every_pair_is_split(self):
        # every pair 1-1: the unit variance is 0, so the floor p(1-p)/n/4 holds; p = 20.5/41 = 1/2
        self.f.pair_edge("e", "a", "b", [0.5] * 20)
        (s,) = self.stats()
        self.assertAlmostEqual(s.y, 0.0, places=9)
        self.assertAlmostEqual(s.se, K * math.sqrt(0.25 / 40 / 4) / 0.25, places=9)

    def test_totals_only_edge_is_binomial(self):
        # 300-0-100 in 400 games: p = 300.5 / 401, se = K sqrt(p(1-p)/400) / (p(1-p))
        self.f.pair_edge("e", "a", "b", [], totals={"wins": 300, "draws": 0, "losses": 100})
        (s,) = self.stats()
        p = 300.5 / 401
        self.assertAlmostEqual(s.y, 400 * math.log10(p / (1 - p)), places=9)
        self.assertAlmostEqual(s.se, K * math.sqrt(p * (1 - p) / 400) / (p * (1 - p)), places=9)

    def test_one_against_two_by_hand(self):
        # openings WWW, LLL, WDL: points 3, 0, 4/3 of 9 games; p = (13/3 + 1/3) / 10 = 7/15, y = 400 log10(2p / (1-p))
        # = 400 log10(14/8). Mean per opening 13/9; squares (14/9)^2 + (13/9)^2 + (1/9)^2 = 366/81; var = 366/81 / 2
        # * 3 / 81 = 0.08368.
        self.f.ovt_edge("e", "c", "o", ["WWW", "LLL", "WDL"])
        (s,) = self.stats()
        self.assertAlmostEqual(s.y, 400 * math.log10(14 / 8), places=9)
        p = 7 / 15
        self.assertAlmostEqual(s.se, K * math.sqrt(366 / 81 / 2 * 3 / 81) / (p * (1 - p)), places=9)

    def test_table_edge_is_refused(self):
        name = "t.jsonl"
        (self.dir / name).write_text(json.dumps({"opening": 0, "perm0": "012", "w0": 0}) + "\n")
        self.f.edges.append({"id": "t", "scale": "3p-adj-r4", "kind": "table", "players": ["a", "b", "c"],
                             "file": name, "unitsPlanned": 1})
        with self.assertRaises(fit_re.fit.FitError) as cm:
            self.stats()
        self.assertIn("kind 'table'", str(cm.exception))


# ------------------------------------------------------------------------------------------------------- statistics

class Quantiles(unittest.TestCase):
    def test_t_quantiles_against_table(self):
        for df, want in ((29, 2.0452), (7, 2.3646), (23, 2.0687), (1, 12.7062)):
            self.assertAlmostEqual(fit_re.t_quantile(0.975, df), want, delta=1e-4, msg=f"t({df})")
            self.assertAlmostEqual(fit_re.t_quantile(0.025, df), -want, delta=1e-4, msg=f"lower t({df})")
            self.assertAlmostEqual(fit_re.t_cdf(-want, df), 0.025, delta=1e-5, msg=f"cdf(-t) at {df} df")

    def test_chi2_quantiles_against_table(self):
        for df, lo, hi in ((29, 16.047, 45.722), (7, 1.690, 16.013), (1, 0.000982, 5.0239)):
            self.assertAlmostEqual(fit_re.chi2_quantile(0.025, df), lo, delta=1e-3 * max(1, lo), msg=f"lo {df}")
            self.assertAlmostEqual(fit_re.chi2_quantile(0.975, df), hi, delta=1e-3 * hi, msg=f"hi {df}")


# ------------------------------------------------------------------------------------------------------- Paule-Mandel

class PauleMandel(unittest.TestCase):
    def test_two_edges_by_hand(self):
        # Two edges a - b with y = 0 and 100, both se = 50. The fit is their weighted mean 50; Q = 2 * 50^2 / (50^2 +
        # tau^2) and df = 1, so Q = df gives tau^2 = 5000 - 2500 = 2500: tau = 50.
        stats = [stat("e1", "a", "b", 0.0, 50.0), stat("e2", "a", "b", 100.0, 50.0)]
        t2 = fit_re.paule_mandel(stats, "b")
        self.assertAlmostEqual(math.sqrt(t2), 50.0, delta=1e-6)
        f = fit_re.Fit(stats, "b", t2)
        self.assertAlmostEqual(f.rating("a"), 1050.0, delta=1e-6)
        self.assertAlmostEqual(f.Q, 1.0, delta=1e-6)

    def test_three_edges_by_hand(self):
        # y = 0, 60, 120 with se = 30: mean 60, Q = 7200 / (900 + tau^2) = 2 at tau^2 = 2700
        stats = [stat(f"e{i}", "a", "b", y, 30.0) for i, y in enumerate((0.0, 60.0, 120.0))]
        self.assertAlmostEqual(fit_re.paule_mandel(stats, "b"), 2700.0, delta=1e-4)

    def test_consistent_edges_give_tau_zero(self):
        # Q at tau = 0 is 2 * 10^2 / 50^2 = 0.08 < df = 1
        stats = [stat("e1", "a", "b", 90.0, 50.0), stat("e2", "a", "b", 110.0, 50.0)]
        self.assertEqual(fit_re.paule_mandel(stats, "b"), 0.0)

    def test_weighted_least_squares_on_a_triangle_by_hand(self):
        # a - b = 100, b - c = 100, a - c = 230, all se = 10, anchor c. Normal equations at tau = 0 (times 100):
        # [[2, -1], [-1, 2]] (a, b) = (100 + 230, -100 + 100) -> a = 220, b = 110; residuals of 10 on all three
        # edges, Q = 3 * 10^2 / 100 = 3 on 1 df.
        stats = [stat("e1", "a", "b", 100.0, 10.0), stat("e2", "b", "c", 100.0, 10.0),
                 stat("e3", "a", "c", 230.0, 10.0)]
        f = fit_re.Fit(stats, "c")
        self.assertAlmostEqual(f.rating("a") - 1000, 220.0, delta=1e-9)
        self.assertAlmostEqual(f.rating("b") - 1000, 110.0, delta=1e-9)
        self.assertAlmostEqual(f.Q, 3.0, delta=1e-9)
        self.assertEqual(f.df, 1)
        # var(a) = first diagonal element of the inverse of [[2, -1], [-1, 2]] / 100, that is 100 * 2/3
        self.assertAlmostEqual(f.diff("a", "c")[1], math.sqrt(200 / 3), delta=1e-9)


# ------------------------------------------------------------------------------------------- synthetic recovery

class Recovery(Tmp):
    TRUE = {"L3": 1000, "a1": 600, "a2": 800, "a4": 1200, "n1": 1350, "n2": 1500, "n3": 1700, "n4": 1100}
    TAU = 80.0

    def build(self, seed, tau=TAU, pairs=300, rounds=3):
        rng = random.Random(seed)
        names = sorted(self.TRUE)
        k = 0
        for _ in range(rounds):
            for i, a in enumerate(names):
                for b in names[i + 1:]:
                    if abs(self.TRUE[a] - self.TRUE[b]) > 600:     # keep scores away from saturation
                        continue
                    d = self.TRUE[a] - self.TRUE[b] + rng.gauss(0.0, tau)
                    p = expected(d)
                    scores = [((rng.random() < p) + (rng.random() < p)) / 2 for _ in range(pairs)]
                    self.f.pair_edge(f"e{k:03d}", a, b, scores)
                    k += 1
        return self.f.write()

    def test_ratings_and_tau_recovered(self):
        out, fits = fit_re.run([self.build(20261127)], anchors=["2p-adj-r5=L3"])
        sf = fits["2p-adj-r5"]
        tau = math.sqrt(sf.tau2)
        lo, hi = sf.tau_ci
        self.assertTrue(lo <= self.TAU <= hi, f"true tau {self.TAU} outside [{lo:.0f}, {hi:.0f}] (estimate {tau:.0f})")
        self.assertAlmostEqual(tau, self.TAU, delta=25, msg="tau estimate")
        for p, r in self.TRUE.items():
            elo, hw = sf.rating(p)
            self.assertLessEqual(abs(elo - r), hw, f"{p}: true {r}, fit {elo:.0f} +- {hw:.0f}")
        # the median edge standard error is about 2 * 400 / ln10 / sqrt(600) * ... = 20 to 30 Elo, well below tau
        self.assertLess(out["scales"]["2p-adj-r5"]["medianEdgeSe"], 40)

    def test_random_effects_intervals_cover_and_fixed_effect_intervals_do_not(self):
        rng_seeds = (11, 12, 13)
        cover = cover0 = total = 0
        for seed in rng_seeds:
            self.f = Files(self.dir / f"s{seed}")
            self.f.root.mkdir()
            stats = self.stats_of(self.build(seed))
            t2 = fit_re.paule_mandel(stats, "L3")
            c, c0, n = fit_re.loo_coverage(stats, "L3", t2)
            cover, cover0, total = cover + c, cover0 + c0, total + n
        self.assertGreaterEqual(cover / total, 0.88, f"RE coverage {cover}/{total}")
        self.assertLess(cover0 / total, 0.75, f"FE coverage {cover0}/{total} should be far below 95 % when tau >> se")

    def test_without_heterogeneity_tau_is_small_and_both_cover(self):
        stats = self.stats_of(self.build(99, tau=0.0, rounds=2))
        t2 = fit_re.paule_mandel(stats, "L3")
        self.assertLess(math.sqrt(t2), 25)
        c, c0, n = fit_re.loo_coverage(stats, "L3", t2)
        self.assertGreaterEqual(c0 / n, 0.85, f"FE coverage {c0}/{n}")

    def stats_of(self, edges_path):
        return [fit_re.edge_stat(e) for e in fit_re.fit.load_edges([edges_path])]


class LeaveOneOut(unittest.TestCase):
    def test_leaf_edges_are_not_counted_as_redundant(self):
        # triangle a, b, c plus leaf d (only edge d - a) and a chain end e - d: 3 redundant edges of 5
        stats = [stat("ab", "a", "b", 100, 20), stat("bc", "b", "c", 100, 20), stat("ac", "a", "c", 200, 20),
                 stat("da", "d", "a", -300, 20), stat("ed", "e", "d", -100, 20)]
        cov, cov0, tot = fit_re.loo_coverage(stats, "c", 0.0)
        self.assertEqual(tot, 3)
        self.assertEqual((cov, cov0), (3, 3))     # a perfectly consistent triangle is predicted exactly

    def test_an_outlying_edge_is_not_covered(self):
        stats = [stat("ab", "a", "b", 100, 10), stat("bc", "b", "c", 100, 10), stat("ac", "a", "c", 200, 10),
                 stat("ab2", "a", "b", 100, 10), stat("bc2", "b", "c", 100, 10), stat("ac2", "a", "c", 500, 10)]
        cov, cov0, tot = fit_re.loo_coverage(stats, "c", 0.0)
        self.assertEqual(tot, 6)
        # Without ac2 the five consistent edges predict a - c = 200 with a standard error of about 6, so the observed
        # 500 lies far outside 1.96 sqrt(6^2 + 10^2) = 23. At a tau of 300 the same edge is covered.
        self.assertLess(cov0, 6)
        cov_wide, _c0, _t = fit_re.loo_coverage(stats, "c", 300.0 ** 2)
        self.assertEqual(cov_wide, 6)


# -------------------------------------------------------------------------------------------------- CLI and output

class Cli(Tmp):
    def data(self):
        rng = random.Random(5)
        true = {"ab:x@n20000": 1000, "ab:x@n5000": 800, "net:r5-small-gen0@s200": 1200, "n2": 1100}
        for i, (a, b) in enumerate([("ab:x@n5000", "ab:x@n20000"), ("net:r5-small-gen0@s200", "ab:x@n20000"),
                                    ("net:r5-small-gen0@s200", "n2"), ("n2", "ab:x@n20000"), ("n2", "ab:x@n5000")]):
            p = expected(true[a] - true[b])
            self.f.pair_edge(f"e{i}", a, b, [((rng.random() < p) + (rng.random() < p)) / 2 for _ in range(100)])
        (self.dir / "configs.tsv").write_text("id\tscale\tlevel\nab:x@n20000\t2p-adj-r5\t3\nab:x@n5000\t2p-adj-r5\t2\n")
        return self.f.write()

    def test_writes_json_with_levels_energy_rows_and_pairs(self):
        edges = self.data()
        (self.dir / "pairs.tsv").write_text("net:r5-small-gen0@s200\tn2\nnet:r5-small-gen0@s200\tab:x@n5000\n")
        out = self.dir / "out.json"
        code = fit_re.main(["--configs", str(self.dir / "configs.tsv"), "--pairs", str(self.dir / "pairs.tsv"),
                            "--out", str(out), str(edges)])
        self.assertEqual(code, 0)
        js = json.loads(out.read_text())
        sc = js["scales"]["2p-adj-r5"]
        self.assertEqual(sc["anchor"], "ab:x@n20000")
        self.assertEqual(sc["edges"], 5)
        self.assertEqual(sc["df"], 2)
        ids = [p["id"] for p in sc["players"]]
        self.assertEqual(len(ids), 4)
        anchor = next(p for p in sc["players"] if p["id"] == "ab:x@n20000")
        self.assertEqual((anchor["elo"], anchor["hw95"], anchor["method"]), (1000, 0, "anchor"))
        levels = js["levels"]["2p-adj-r5"]
        self.assertEqual([x["level"] for x in levels], [1, 2, 3, 4, 5])
        self.assertEqual(levels[2]["elo"], 1000)
        self.assertEqual(levels[1]["config"], "ab:x@n5000")
        self.assertIsNone(levels[0]["elo"])
        rows = js["energyTable"]["rows"]
        self.assertEqual(rows["small-gen0"]["sims"]["config"], "net:r5-small-gen0@s200")
        self.assertIsNone(rows["small-gen9"]["sims"]["elo"])
        pr = sc["pairs"]
        self.assertEqual(pr[0]["directGames"], 200)
        self.assertNotIn("direct", pr[1])

    def test_display_rounding(self):
        self.assertEqual([fit_re.display_elo(x) for x in (1197.0, 1839.4, 1905.0, 1904.4)], [1200, 1840, 1910, 1900])
        self.assertEqual([fit_re.display_hw(x) for x in (100.2, 124.73, 163.76, 130.4)], [100, 130, 170, 130])

    def test_disconnected_graph_exits_1_and_writes_nothing(self):
        self.f.pair_edge("e1", "a", "b", [0.5] * 10)
        self.f.pair_edge("e2", "c", "d", [0.5] * 10)
        out = self.dir / "out.json"
        edges = self.f.write()
        code = fit_re.main(["--anchor", "2p-adj-r5=a", "--out", str(out), str(edges)])
        self.assertEqual(code, 1)
        self.assertFalse(out.exists())
        with self.assertRaises(fit_re.fit.FitError) as cm:
            fit_re.run([edges], anchors=["2p-adj-r5=a"])
        self.assertIn("not connected, 2 components: a, b | c, d", str(cm.exception))

    def test_wrong_call_exits_2(self):
        self.assertEqual(fit_re.main(["--anchor", "noequals", "x.jsonl"]), 2)
        self.assertEqual(fit_re.main([]), 2)


# --------------------------------------------------------------------------------- the committed study records

@unittest.skipUnless((RECORDS / "tier1" / "edges.jsonl").is_file() and PUBLISHED.is_file(), "records not present")
class StudyRecords(unittest.TestCase):
    """Reproduce the published scales from the committed raw results (models/runs/elo-study)."""

    @classmethod
    def setUpClass(cls):
        cls.out, cls.fits = fit_re.run([RECORDS / "tier1" / "edges.jsonl", RECORDS / "tier0" / "edges.jsonl"],
                                       configs_path=RECORDS / "tier1" / "configs.tsv",
                                       pairs_path=RECORDS / "pairs-r5.tsv")
        cls.pub = json.loads(PUBLISHED.read_text())

    def test_every_rating_and_half_width_within_rounding(self):
        n = 0
        for scale, sf in self.fits.items():
            for p in self.pub["scales"][scale]["players"]:
                elo, hw = sf.rating(p["id"])
                self.assertLessEqual(abs(elo - p["elo"]), 0.5 + 1e-6, f"{scale} {p['id']}: {elo:.2f} vs {p['elo']}")
                self.assertLessEqual(abs(hw - p["hw95"]), 0.5 + 1e-6, f"{scale} {p['id']}: +-{hw:.2f} vs {p['hw95']}")
                n += 1
        self.assertEqual(n, 25 + 10 + 10)

    def test_tau_interval_df_and_coverage(self):
        want = {"2p-adj-r5": (72, [54, 102], 29, (49, 47, 20)), "2p-adj-r4": (38, [20, 86], 7, (16, 16, 10)),
                "3p-adj-r4": (48, [31, 78], 23, (32, 31, 22))}
        for scale, (tau, ci, df, loo) in want.items():
            sc = self.out["scales"][scale]
            self.assertEqual((sc["tauElo"], sc["tauCi95"], sc["df"]), (tau, ci, df), scale)
            c = sc["looCoverage"]
            self.assertEqual((c["redundantEdges"], c["randomEffects"], c["fixedEffect"]), loo, scale)

    def test_energy_rows_levels_and_pairs(self):
        for row, bases in self.pub["energyTable"]["rows"].items():
            for basis, want in bases.items():
                got = self.out["energyTable"]["rows"][row][basis]
                for k in ("config", "elo", "hw95", "displayElo", "displayHw95"):
                    if k in want:
                        self.assertEqual(got[k], want[k], f"{row}.{basis}.{k}")
        for scale, levels in self.pub["levels"].items():
            got = self.out["levels"][scale]
            self.assertEqual([(x["elo"], x.get("hw95")) for x in got], [(x["elo"], x.get("hw95")) for x in levels])
        got = {(p["a"], p["b"]): p for p in self.out["scales"]["2p-adj-r5"]["pairs"]}
        for p in self.pub["scales"]["2p-adj-r5"]["pairs"]:
            g = got[(p["a"], p["b"])]
            self.assertEqual((g["pooled"], g["pooledHw95"]), (p["pooled"], p["pooledHw95"]), p["a"] + " - " + p["b"])
            self.assertEqual(g.get("direct"), p.get("direct"))


if __name__ == "__main__":
    unittest.main()
