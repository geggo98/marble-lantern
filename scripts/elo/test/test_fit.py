"""Tests of scripts/elo/fit.py (offline; run: python3 scripts/elo/test/test_fit.py).

Expected values come from independent sources: Elo.fromScore of search/src/main/java/sternhalma/search/Elo.java, run
in jshell on 2026-11-26 (0.75 -> 190.84850188786498, 0.3 -> -147.19071411783776, 0.62 -> 85.04323715257749,
1.0 -> 1199.8261952903927); the closed form 400 log10(2p/(1-p)) of plan 2.2 by hand (p = 0.6: 400 log10 3 = 190.8485);
chi-square table values (6 df at 12.5916 and 1 df at 3.8415: upper tail 0.05); and synthetic data drawn from known
ratings with fixed seeds. FIT_PY=<path> runs the tests against another copy of fit.py (used for mutation checks).
"""
import importlib.util
import json
import math
import os
import random
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = Path(os.environ.get("FIT_PY", HERE.parent / "fit.py"))


def load():
    spec = importlib.util.spec_from_file_location("fit", SRC)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


fit = load()
ELO = 400 / math.log(10)


def expected(dr):
    return 1 / (1 + 10 ** (-dr / 400))


class Data:
    """Writes an edges.jsonl and its result files into a temporary directory."""

    def __init__(self, root):
        self.root = Path(root)
        self.edges = []

    def edge(self, eid, scale, kind, players, lines, seed=None, planned=None, source="new", raw=None):
        name = f"{eid}.jsonl"
        text = raw if raw is not None else "".join(json.dumps(x) + "\n" for x in lines)
        (self.root / name).write_text(text)
        d = {"id": eid, "scale": scale, "kind": kind, "players": players, "file": name,
             "unitsPlanned": planned if planned is not None else len(lines), "source": source}
        if seed is not None:
            d["seed"] = seed
            d["openingPlies"] = 4
        self.edges.append(d)

    def write(self, name="edges.jsonl"):
        p = self.root / name
        p.write_text("".join(json.dumps(e) + "\n" for e in self.edges))
        return p


def pair_lines(scores, cpu=(0, 0, 0, 0)):
    return [{"pair": i, "score": s, "wins": 0, "draws": 0, "losses": 0, "plies": 100, "s_cpu_ns": cpu[0],
             "s_moves": cpu[1], "o_cpu_ns": cpu[2], "o_moves": cpu[3]} for i, s in enumerate(scores)]


def sim_pairs(rng, ra, rb, n):
    p = expected(ra - rb)
    return [((rng.random() < p) + (rng.random() < p)) / 2 for _ in range(n)]


def ovt_lines(results):
    return [{"opening": i, "open_hash": "0", "r0": r[0], "r1": r[1], "r2": r[2]} for i, r in enumerate(results)]


def sim_ovt(rng, rc, ro, n, sigma=(1.0, 1.0, 1.0)):
    gc, go = 10 ** (rc / 400), 10 ** (ro / 400)
    out = []
    for _ in range(n):
        r = []
        for s in range(3):
            p = gc * sigma[s] / (gc * sigma[s] + go * sum(sigma[t] for t in range(3) if t != s))
            r.append("W" if rng.random() < p else "L")
        out.append(r)
    return out


PERMS = [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]]


def sim_table(rng, ratings, n, sigma=(1.0, 1.0, 1.0)):
    g = [10 ** (r / 400) for r in ratings]
    out = []
    for i in range(n):
        games = []
        for perm in PERMS:
            th = [g[perm[s]] * sigma[s] for s in range(3)]
            u, acc, win = rng.random() * sum(th), 0.0, 2
            for s in range(3):
                acc += th[s]
                if u < acc:
                    win = s
                    break
            games.append((perm, win))
        line = {"opening": i, "open_hash": "0"}
        for k, (perm, win) in enumerate(games):         # the TableMatch line of task T2
            line[f"perm{k}"] = "".join(map(str, perm))
            line[f"w{k}"] = win
            line[f"wp{k}"] = perm[win] if win >= 0 else -1
        out.append(line)
    return out


def args_for(edges, out, extra=()):
    return [str(edges), "--out", str(out), "--work", str(Path(out) / "work"), "--date", "2026-11-26",
            "--commit", "test", "--quiet", *extra]


def run_fit(edges, out, extra=()):
    """Run main() in-process; returns (exit code, ratings.json or None)."""
    code = fit.main(args_for(edges, out, extra))
    p = Path(out) / "ratings.json"
    return code, (json.loads(p.read_text()) if p.exists() else None)


def by_id(js, scale):
    return {p["id"]: p for p in js["scales"][scale]["players"]}


class Tmp(unittest.TestCase):
    def setUp(self):
        self._t = tempfile.TemporaryDirectory()
        self.dir = Path(self._t.name)
        self.d = Data(self.dir)
        self.out = self.dir / "out"

    def tearDown(self):
        self._t.cleanup()


# ------------------------------------------------------------------------------------------------------- one edge

class OneEdge(Tmp):
    JAVA = {0.75: 190.84850188786498, 0.3: -147.19071411783776, 0.62: 85.04323715257749}

    def test_single_edge_without_prior_equals_java_fromScore(self):
        for score, java in self.JAVA.items():
            with self.subTest(score=score):
                d = Data(self.dir / f"s{score}")
                d.root.mkdir()
                # 100 pairs whose mean is exactly `score`: k pairs at 1.0, the rest at x
                n = 100
                scores = [score] * n
                d.edge("e", "2p-adj-r4", "pair", ["ab:x@n20000", "net:y@s200"], pair_lines(scores))
                code, js = run_fit(d.write(), d.root / "out",
                                   ["--anchor", "2p-adj-r4=net:y@s200", "--prior-games", "0", "--replicates", "20",
                                    "--gof-replicates", "0"])
                self.assertEqual(code, 0)
                got = by_id(js, "2p-adj-r4")["ab:x@n20000"]["elo"] - 1000
                self.assertAlmostEqual(got, java, delta=0.01, msg=f"score {score}: fit {got} vs Elo.fromScore {java}")

    def test_edge_only_column_clamps_like_java(self):
        self.assertAlmostEqual(fit.from_score(1.0), 1199.8261952903927, places=9)
        self.assertAlmostEqual(fit.from_score(0.0), -1199.826195290393, places=9)
        for s, j in self.JAVA.items():
            self.assertAlmostEqual(fit.from_score(s), j, places=9)

    def test_prior_keeps_a_perfect_edge_finite_and_the_report_flags_separation(self):
        self.d.edge("e", "2p-adj-r4", "pair", ["a", "b"], pair_lines([1.0] * 50))
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "2p-adj-r4=b", "--replicates", "20",
                                                      "--gof-replicates", "0"])
        self.assertEqual(code, 0)
        # prior: 100 + 0.5 wins against 0.5 -> 400 log10(201)
        self.assertAlmostEqual(by_id(js, "2p-adj-r4")["a"]["elo"] - 1000, 400 * math.log10(201), delta=0.01)
        rep = (self.out / "fit-report.md").read_text()
        self.assertIn("without the prior (did not converge: separation", rep)


# --------------------------------------------------------------------------------------------- one against two

class OneAgainstTwo(Tmp):
    def exact(self, share_num, n):
        """n openings; in every seat the candidate wins exactly share_num of n."""
        res = [["W" if i < share_num else "L"] * 3 for i in range(n)]
        self.d.edge("e", "3p-adj-r4", "one-vs-two", ["c", "o"], ovt_lines(res))
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "3p-adj-r4=o", "--prior-games", "0",
                                                      "--replicates", "20", "--gof-replicates", "0"])
        self.assertEqual(code, 0)
        return by_id(js, "3p-adj-r4")["c"]["elo"] - 1000

    def test_one_third_gives_zero(self):
        self.assertAlmostEqual(self.exact(100, 300), 0.0, delta=0.01)

    def test_share_06_gives_400_log10_3(self):
        self.assertAlmostEqual(self.exact(180, 300), 400 * math.log10(3), delta=0.01)

    def test_closed_form_helper(self):
        self.assertAlmostEqual(fit.one_vs_two_elo(1 / 3), 0.0, places=9)
        self.assertAlmostEqual(fit.one_vs_two_elo(0.6), 400 * math.log10(3), places=9)

    def test_draw_counts_one_third_for_each_player(self):
        # all draws: the candidate gets 1/3 of every game, as at p = 1/3, so the difference is 0
        self.d.edge("e", "3p-adj-r4", "one-vs-two", ["c", "o"], ovt_lines([["D", "D", "D"]] * 60))
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "3p-adj-r4=o", "--prior-games", "0",
                                                      "--replicates", "20", "--gof-replicates", "0"])
        self.assertEqual(code, 0)
        self.assertAlmostEqual(by_id(js, "3p-adj-r4")["c"]["elo"], 1000.0, delta=0.01)


# --------------------------------------------------------------------------------------------- recovery

class Recovery(Tmp):
    def test_two_player_ratings_recovered_within_bootstrap_interval(self):
        rng = random.Random(20261126)
        true = {"a1": 600, "a2": 800, "L3": 1000, "a4": 1150, "n1": 1300, "n2": 1450}
        edges = [("a1", "a2"), ("a2", "L3"), ("L3", "a4"), ("a4", "n1"), ("n1", "n2"), ("a2", "a4"), ("L3", "n1"),
                 ("a4", "n2")]
        for i, (x, y) in enumerate(edges):
            self.d.edge(f"e{i}", "2p-adj-r5", "pair", [x, y], pair_lines(sim_pairs(rng, true[x], true[y], 200)),
                        seed=8101)
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "2p-adj-r5=L3", "--replicates", "300",
                                                      "--gof-replicates", "20"])
        self.assertEqual(code, 0)
        got = by_id(js, "2p-adj-r5")
        for p, r in true.items():
            lo, hi = got[p]["ci95"]
            self.assertTrue(lo <= r <= hi, f"{p}: true {r} outside [{lo:.0f}, {hi:.0f}], fit {got[p]['elo']:.0f}")
            if p != "L3":
                self.assertLess(hi - lo, 150, f"{p}: interval implausibly wide")

    def test_three_player_ratings_and_seat_factors_recovered(self):
        rng = random.Random(8103)
        sigma = (1.0, 0.85, 0.7)
        true = {"x": 1000, "y": 1200, "z": 900}
        k = 0
        for c, o in (("x", "y"), ("y", "x"), ("x", "z"), ("z", "x"), ("y", "z")):
            self.d.edge(f"e{k}", "3p-adj-r4", "one-vs-two", [c, o],
                        ovt_lines(sim_ovt(rng, true[c], true[o], 300, sigma)), seed=8104)
            k += 1
        self.d.edge("t", "3p-adj-r4", "table", ["x", "y", "z"], sim_table(rng, [1000, 1200, 900], 100, sigma),
                    seed=8104)
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "3p-adj-r4=x", "--replicates", "300",
                                                      "--gof-replicates", "20"])
        self.assertEqual(code, 0)
        got = by_id(js, "3p-adj-r4")
        for p, r in true.items():
            lo, hi = got[p]["ci95"]
            self.assertTrue(lo <= r <= hi, f"{p}: true {r} outside [{lo:.0f}, {hi:.0f}]")
        self.assertEqual(js["scales"]["3p-adj-r4"]["fit"]["threePlayer"], "one-dimensional")
        rep = (self.out / "fit-report.md").read_text()
        import re
        m = re.search(r"seat 1 (?P<s1>[\d.]+) \[(?P<l1>[\d.]+), (?P<h1>[\d.]+)\], seat 2 (?P<s2>[\d.]+) "
                      r"\[(?P<l2>[\d.]+), (?P<h2>[\d.]+)\]", rep)
        self.assertIsNotNone(m, "seat factors missing in the report")
        self.assertTrue(float(m["l1"]) <= sigma[1] <= float(m["h1"]), m.group(0))
        self.assertTrue(float(m["l2"]) <= sigma[2] <= float(m["h2"]), m.group(0))

    def test_seat_factors_from_mixed_tables_alone(self):
        # only tables, strong seat effect: the seat factors can come from the seats of the table games only
        rng = random.Random(8105)
        sigma = (1.0, 0.5, 0.3)
        self.d.edge("t1", "3p-long-r4", "table", ["x", "y", "z"], sim_table(rng, [1000, 1100, 900], 150, sigma),
                    seed=1)
        self.d.edge("t2", "3p-long-r4", "table", ["x", "y", "w"], sim_table(rng, [1000, 1100, 800], 150, sigma),
                    seed=2)
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "3p-long-r4=x", "--replicates", "200",
                                                      "--gof-replicates", "0"])
        self.assertEqual(code, 0)
        rep = (self.out / "fit-report.md").read_text()
        import re
        m = re.search(r"seat 1 (?P<s1>[\d.]+) \[(?P<l1>[\d.]+), (?P<h1>[\d.]+)\], seat 2 (?P<s2>[\d.]+) "
                      r"\[(?P<l2>[\d.]+), (?P<h2>[\d.]+)\]", rep)
        self.assertTrue(float(m["l1"]) <= 0.5 <= float(m["h1"]), m.group(0))
        self.assertTrue(float(m["l2"]) <= 0.3 <= float(m["h2"]), m.group(0))
        got = by_id(js, "3p-long-r4")
        for p, r in {"y": 1100, "z": 900, "w": 800}.items():
            lo, hi = got[p]["ci95"]
            self.assertTrue(lo <= r <= hi, f"{p}: true {r} outside [{lo:.0f}, {hi:.0f}]")


# --------------------------------------------------------------------------------------------- graph and input

class Input(Tmp):
    def test_disconnected_graph_is_an_error(self):
        self.d.edge("e1", "2p-adj-r4", "pair", ["a", "b"], pair_lines([0.5] * 10))
        self.d.edge("e2", "2p-adj-r4", "pair", ["c", "d"], pair_lines([0.5] * 10))
        edges = self.d.write()
        with self.assertRaises(fit.FitError) as cm:
            fit.fit_all([edges], fit.parse_args(args_for(edges, self.out, ["--anchor", "2p-adj-r4=a"])), fit.Runner(
                None, True), [])
        self.assertIn("not connected, 2 components: a, b | c, d", str(cm.exception))
        code, js = run_fit(edges, self.out, ["--anchor", "2p-adj-r4=a"])
        self.assertEqual(code, 1)
        self.assertIsNone(js)

    def err(self, **kw):
        lines = kw.pop("lines", pair_lines([0.5] * 4))
        self.d.edge("e", "2p-adj-r4", kw.pop("kind", "pair"), kw.pop("players", ["a", "b"]), lines, **kw)
        with self.assertRaises(fit.FitError) as cm:
            fit.load_edges([self.d.write()])
        return str(cm.exception)

    def test_fewer_units_than_planned_is_an_error(self):
        self.assertIn("4 of 5 planned units", self.err(planned=5))

    def test_file_without_lines_is_an_error(self):
        self.assertIn("no result lines", self.err(lines=[], planned=3))

    def test_repeated_unit_is_an_error(self):
        lines = pair_lines([0.5, 0.5])
        lines[1]["pair"] = 0
        self.assertIn("unit 0 appears twice", self.err(lines=lines, planned=1))

    def test_broken_middle_line_names_file_and_line(self):
        raw = json.dumps(pair_lines([0.5])[0]) + "\n{broken\n" + json.dumps(pair_lines([0.5, 0.5])[1]) + "\n"
        msg = self.err(raw=raw, planned=2)
        self.assertIn("e.jsonl:2: not a JSON line", msg)

    def test_torn_last_line_is_ignored(self):
        raw = "".join(json.dumps(x) + "\n" for x in pair_lines([0.5, 1.0])) + '{"pair": 2, "sco'
        self.d.edge("e", "2p-adj-r4", "pair", ["a", "b"], [], raw=raw, planned=2)
        notes = []
        edges = fit.load_edges([self.d.write()], notes=notes)
        self.assertEqual(sorted(edges[0].units), [0, 1])
        self.assertTrue(any("torn last line" in n for n in notes), notes)

    def test_score_out_of_range_is_an_error(self):
        lines = pair_lines([0.5, 1.5])
        self.assertIn("e.jsonl:2: field 'score' = 1.5 out of range", self.err(lines=lines))

    def test_bad_three_player_result_code(self):
        msg = self.err(lines=ovt_lines([["W", "X", "L"]]), kind="one-vs-two", players=["a", "b"])
        self.assertIn("scale 2p-adj-r4", msg)       # kind and scale disagree first
        self.d = Data(self.dir)
        self.d.edge("e", "3p-adj-r4", "one-vs-two", ["a", "b"], ovt_lines([["W", "X", "L"]]))
        with self.assertRaises(fit.FitError) as cm:
            fit.load_edges([self.d.write()])
        self.assertIn("r1 = 'X'", str(cm.exception))

    def test_config_against_itself_is_refused(self):
        self.assertIn("plays itself", self.err(players=["a", "a"]))

    def test_totals_that_disagree_with_the_lines_are_an_error(self):
        lines = [{"pair": 0, "score": 1.0, "wins": 2, "draws": 0, "losses": 0},
                 {"pair": 1, "score": 0.25, "wins": 0, "draws": 1, "losses": 1}]
        self.d.edge("e", "2p-adj-r4", "pair", ["a", "b"], lines)
        self.d.edges[-1]["totals"] = {"wins": 2, "draws": 1, "losses": 1}
        fit.load_edges([self.d.write()])                       # agrees: 2-1-1
        self.d.edges[-1]["totals"] = {"wins": 3, "draws": 0, "losses": 1}
        with self.assertRaises(fit.FitError) as cm:
            fit.load_edges([self.d.write()])
        self.assertIn("totals W-D-L 3-0-1, the lines of", str(cm.exception))
        self.assertIn("sum to 2-1-1", str(cm.exception))

    def test_units_present_must_match(self):
        self.d.edge("e", "2p-adj-r4", "pair", ["a", "b"], pair_lines([0.5] * 3))
        self.d.edges[-1]["unitsPresent"] = 4
        with self.assertRaises(fit.FitError) as cm:
            fit.load_edges([self.d.write()])
        self.assertIn("unitsPresent = 4, the file holds 3 units", str(cm.exception))

    def test_table_line_winner_identity_is_checked(self):
        line = sim_table(random.Random(1), [1000, 1000, 1000], 1)[0]
        line["wp3"] = (line["wp3"] + 1) % 3 if line["w3"] >= 0 else 0
        self.d.edge("t", "3p-adj-r4", "table", ["x", "y", "z"], [line])
        with self.assertRaises(fit.FitError) as cm:
            fit.load_edges([self.d.write()])
        self.assertIn("wp3", str(cm.exception))

    def test_table_totals_from_the_first_players_view(self):
        line = {"opening": 0, "open_hash": "0"}
        for k, (perm, w) in enumerate(zip(["012", "021", "102", "120", "201", "210"], [0, 1, 1, -1, 1, 0])):
            line[f"perm{k}"], line[f"w{k}"] = perm, w
        # player 0 wins in seating 0 (seat 0 = player 0), 2 (seat 1 = player 0), 4 (seat 1 = player 0): 3 wins;
        # seating 3 has no winner; seatings 1 and 5 are won by player 2 (seat 1 and seat 0): 3-1-2
        self.d.edge("t", "3p-adj-r4", "table", ["x", "y", "z"], [line])
        self.d.edges[-1]["totals"] = {"wins": 3, "draws": 1, "losses": 2}
        fit.load_edges([self.d.write()])

    def test_edge_with_totals_only(self):
        self.d.edge("e1", "2p-adj-r4", "pair", ["a", "b"], pair_lines([1.0, 0.5, 0.5, 0.5]))
        self.d.edges.append({"id": "e2", "scale": "2p-adj-r4", "kind": "pair", "players": ["b", "c"], "file": None,
                             "unitsPlanned": 5, "totals": {"wins": 6, "draws": 2, "losses": 2},
                             "source": {"kind": "existing", "origin": "x.txt:3"}})
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "2p-adj-r4=b", "--prior-games", "0",
                                                      "--replicates", "50", "--gof-replicates", "0"])
        self.assertEqual(code, 0)
        # b against c scores 7/10 -> c = 1000 - 400 log10(7/3)
        self.assertAlmostEqual(by_id(js, "2p-adj-r4")["c"]["elo"], 1000 - 400 * math.log10(7 / 3), delta=0.01)
        rep = (self.out / "fit-report.md").read_text()
        self.assertIn("edge e2: no result file, 10 games from totals", rep)
        self.d.edges[-1]["totals"] = {"wins": 6, "draws": 2, "losses": 1}
        with self.assertRaises(fit.FitError) as cm:
            fit.load_edges([self.d.write()])
        self.assertIn("expected 2 * unitsPlanned = 10", str(cm.exception))

    def test_registry_style_configs(self):
        # the layout of scripts/elo/configs.tsv of task T3: comments first, config_id, '-' for empty, anchor column
        p = self.dir / "configs.tsv"
        p.write_text("# comment\n# '-' means: not applicable.\n"
                     "config_id\tscale\tengine\tnodes\tsims\tnet_id\tbasis\tlevel\tanchor\tbridge\n"
                     "ab:x@n5000\t2p-adj-r4\tab\t5000\t-\t-\t-\t2\t0\tab5000\n"
                     "ab:x@n20000\t2p-adj-r4\tab\t20000\t-\t-\t-\t3\t1\tab20000\n"
                     "net:n@s200\t2p-adj-r4\tnet\t-\t200\tr4-n\tsims200\t-\t0\t-\n")
        c = fit.load_configs(p)
        self.assertEqual(c["net:n@s200"]["net"], "r4-n")
        self.assertEqual(c["ab:x@n5000"]["sims"], "")
        self.d.edge("e1", "2p-adj-r4", "pair", ["ab:x@n5000", "ab:x@n20000"], pair_lines([0.5] * 4))
        self.d.edge("e2", "2p-adj-r4", "pair", ["net:n@s200", "ab:x@n20000"], pair_lines([0.5] * 4))
        edges = fit.load_edges([self.d.write()])
        self.assertEqual(fit.resolve_anchors(edges, c, {}), {"2p-adj-r4": "ab:x@n20000"})
        c["ab:x@n5000"]["anchor"] = "1"
        with self.assertRaises(fit.FitError):
            fit.resolve_anchors(edges, c, {})

    def test_same_config_id_in_two_scales_is_scoped_per_scale(self):
        # bridge players have the same engine setting, hence the same id, in a 2p and a 3p scale (Tier 1 data)
        p = self.dir / "configs.tsv"
        p.write_text("id\tscale\tlevel\tanchor\tbridge\n"
                     "ab:x@n5000\t2p-adj-r4\t2\t0\tab5000\n"
                     "ab:x@n20000\t2p-adj-r4\t3\t1\tab20000\n"
                     "ab:x@n5000\t3p-adj-r4\t3\t1\tab5000\n"
                     "ab:x@n20000\t3p-adj-r4\t2\t0\tab20000\n")
        c = fit.load_configs(p)
        self.d.edge("e1", "2p-adj-r4", "pair", ["ab:x@n5000", "ab:x@n20000"], pair_lines([0.5] * 4))
        self.d.edge("e2", "3p-adj-r4", "one-vs-two", ["ab:x@n5000", "ab:x@n20000"], ovt_lines(sim_ovt(random.Random(1), 1000, 1000, 4)))
        edges = fit.load_edges([self.d.write()])
        # the rows differ per scale: the anchor is the level 3 config of the scale, not of the other one
        self.assertEqual(fit.resolve_anchors(edges, c, {}), {"2p-adj-r4": "ab:x@n20000", "3p-adj-r4": "ab:x@n5000"})

    def test_duplicate_config_id_inside_one_scale_is_still_an_error(self):
        p = self.dir / "configs.tsv"
        p.write_text("id\tscale\tlevel\n"
                     "ab:x@n5000\t2p-adj-r4\t2\n"
                     "ab:x@n5000\t2p-adj-r4\t3\n")
        with self.assertRaises(fit.FitError) as cm:
            fit.load_configs(p)
        self.assertIn("configs.tsv:3: config ab:x@n5000 listed twice in scale 2p-adj-r4", str(cm.exception))


# --------------------------------------------------------------------------------------------- three-player checks

class RoleSwap(Tmp):
    def build(self, share_xy, share_yx, n=300):
        rng = random.Random(5)

        def fixed(share):
            k = round(share * n)
            res = []
            for s in range(3):
                col = ["W"] * k + ["L"] * (n - k)
                rng.shuffle(col)
                res.append(col)
            return [[res[0][i], res[1][i], res[2][i]] for i in range(n)]
        self.d.edge("xy", "3p-adj-r4", "one-vs-two", ["x", "y"], ovt_lines(fixed(share_xy)), seed=1)
        self.d.edge("yx", "3p-adj-r4", "one-vs-two", ["y", "x"], ovt_lines(fixed(share_yx)), seed=2)
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "3p-adj-r4=y", "--replicates", "200",
                                                      "--gof-replicates", "0"])
        self.assertEqual(code, 0)
        return js["scales"]["3p-adj-r4"]["fit"]["threePlayer"], (self.out / "fit-report.md").read_text()

    def test_consistent_roles_pass(self):
        # x - y = +200: x as candidate wins 2g/(2g+2) ... p = 2*10^(0.5)/(2*10^0.5 + 2)?  Luce: p_x = gx/(gx + 2 gy)
        gx = 10 ** 0.5
        p_xy = gx / (gx + 2)
        p_yx = 1 / (1 + 2 * gx)
        verdict, _rep = self.build(p_xy, p_yx)
        self.assertEqual(verdict, "one-dimensional")

    def test_role_swap_violation_is_detected(self):
        # x as candidate looks +200 better, but y as candidate wins as if equal: one-dimensional strength fails
        gx = 10 ** 0.5
        verdict, rep = self.build(gx / (gx + 2), 1 / 3)
        self.assertEqual(verdict, "per-table-type-only")
        self.assertIn("**!**", rep.split("Role swap")[1].split("Verdict")[0])


# --------------------------------------------------------------------------------------------- cluster unit

class Cluster(Tmp):
    def shared(self, seed_b, seed_c, n=200):
        """A against B and A against C; in opening i both pairs end the same way (A wins both games or loses both)."""
        rng = random.Random(77)
        res = [1.0 if rng.random() < 0.5 else 0.0 for _ in range(n)]
        self.d.edge("ab", "2p-adj-r4", "pair", ["A", "B"], pair_lines(res), seed=seed_b)
        self.d.edge("ac", "2p-adj-r4", "pair", ["A", "C"], pair_lines(res), seed=seed_c)
        (self.dir / "pairs.tsv").write_text("B\tC\n")
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "2p-adj-r4=A", "--replicates", "300",
                                                      "--gof-replicates", "0", "--pairs", str(self.dir / "pairs.tsv")])
        self.assertEqual(code, 0)
        pair = [x for x in js["scales"]["2p-adj-r4"]["pairs"] if x["a"] == "B" and x["b"] == "C"][0]
        return pair

    def test_edges_with_one_seed_are_resampled_with_the_same_openings(self):
        pair = self.shared(8101, 8101)
        self.assertAlmostEqual(pair["elo"], 0.0, delta=0.01)
        half = (pair["ci95"][1] - pair["ci95"][0]) / 2
        self.assertLess(half, 1.0, f"B - C interval {pair['ci95']}: shared openings were not resampled together")

    def test_edges_with_different_seeds_are_resampled_independently(self):
        pair = self.shared(8101, 8102)
        half = (pair["ci95"][1] - pair["ci95"][0]) / 2
        self.assertGreater(half, 50.0, f"B - C interval {pair['ci95']}")

    def test_three_games_of_an_opening_are_one_unit(self):
        # each opening: the candidate wins all three games or none, p = 1/2, 300 openings. Delta method on the
        # opening level: se(p) = sqrt(0.25/300), dR/dp = (400/ln 10) (1/p + 1/(1-p)) -> half-width 39.3 Elo.
        # Resampling single games would give 39.3 / sqrt(3) = 22.7.
        rng = random.Random(3)
        res = [["W"] * 3 if rng.random() < 0.5 else ["L"] * 3 for _ in range(300)]
        k = sum(r[0] == "W" for r in res)
        res = [["W"] * 3] * 150 + [["L"] * 3] * 150 if k != 150 else res
        self.d.edge("e", "3p-adj-r4", "one-vs-two", ["c", "o"], ovt_lines(res), seed=1)
        code, js = run_fit(self.d.write(), self.out, ["--anchor", "3p-adj-r4=o", "--replicates", "400",
                                                      "--gof-replicates", "0"])
        self.assertEqual(code, 0)
        c = by_id(js, "3p-adj-r4")["c"]
        half = (c["ci95"][1] - c["ci95"][0]) / 2
        analytic = 1.96 * ELO * 4 * math.sqrt(0.25 / 300)
        self.assertGreater(half, 0.8 * analytic, f"half-width {half:.1f}, expected about {analytic:.1f}")
        self.assertLess(half, 1.25 * analytic, f"half-width {half:.1f}, expected about {analytic:.1f}")


# --------------------------------------------------------------------------------------------- bridge

class Bridge(unittest.TestCase):
    def test_chi2_tail_against_table_values(self):
        self.assertAlmostEqual(fit.chi2_sf(12.5916, 6), 0.05, delta=2e-5)
        self.assertAlmostEqual(fit.chi2_sf(3.8415, 1), 0.05, delta=2e-5)
        self.assertAlmostEqual(fit.chi2_sf(0.0, 3), 1.0, places=12)
        self.assertAlmostEqual(fit.chi2_sf(30.0, 4), 4.89e-6, delta=1e-7)

    def reps(self, rng, mean, sdv, n=2000):
        return [rng.gauss(mean, sdv) for _ in range(n)]

    def make(self, offsets, sdv=12.0, scales=("2p-adj-r4", "3p-adj-r4")):
        rng = random.Random(9)
        names = [f"b{i}" for i in range(len(offsets))]
        base = {b: 700 + 120 * i for i, b in enumerate(names)}
        rg = dict(base)
        rh = {b: base[b] - offsets[i] for i, b in enumerate(names)}
        repg = {b: self.reps(rng, rg[b], sdv) for b in names}
        reph = {b: self.reps(rng, rh[b], sdv) for b in names}
        return fit.bridge_test(scales[0], scales[1], names, rg, rh, repg, reph)

    def test_one_offset_passes_and_is_estimated(self):
        t = self.make([30] * 7)
        self.assertEqual(t["verdict"], "mixed", t["reasons"])
        self.assertEqual(t["df"], 6)
        self.assertAlmostEqual(t["offset"], 30, delta=10)
        self.assertAlmostEqual(t["slope"], 1.0, delta=0.05)
        self.assertGreater(t["p"], 0.05)
        self.assertIn("pooled", t)

    def test_networks_400_apart_are_separate(self):
        t = self.make([0, 0, 0, 0, -400, -400, -400])
        self.assertEqual(t["verdict"], "separate")
        self.assertLess(t["p"], 1e-6)
        self.assertGreater(t["tau"], 150)

    def test_small_but_certain_differences_reject_on_p_alone(self):
        # offsets 0 and 30 alternate, SD 4: tau about 16 (< 50), d intervals about +-11 (< 60), but p is tiny
        t = self.make([0, 30, 0, 30, 0, 30, 0], sdv=4.0)
        self.assertLess(t["tau"], 50)
        self.assertLess(max(t["dHalf"]), 60)
        self.assertEqual(t["verdict"], "separate")
        self.assertTrue(any("one offset rejected" in r for r in t["reasons"]), t["reasons"])

    def test_tau_alone_rejects_when_the_differences_are_negatively_correlated(self):
        # d = (0, 105), SD 29 each, correlation -0.95: Wald chi2 = 105^2 / (841 * 3.9) = 3.36 (p = 0.067), but the
        # DerSimonian-Laird tau = sqrt((2 * 52.5^2 / 841 - 1) * 841) = 68 > 50, and both d intervals are +-57 < 60
        rng = random.Random(4)
        rho, s = -0.95, 29.0
        z = [(rng.gauss(0, 1), rng.gauss(0, 1)) for _ in range(40000)]
        repg = {"p": [1000 + s * a for a, _b in z], "q": [1105 + s * (rho * a + math.sqrt(1 - rho ** 2) * b)
                                                          for a, b in z]}
        reph = {"p": [1000.0] * len(z), "q": [1000.0] * len(z)}
        t = fit.bridge_test("2p-adj-r4", "3p-adj-r4", ["p", "q"], {"p": 1000, "q": 1105}, {"p": 1000, "q": 1000},
                            repg, reph)
        self.assertGreater(t["p"], 0.05)
        self.assertLess(max(t["dHalf"]), 60)
        self.assertAlmostEqual(t["tau"], 68, delta=4)
        self.assertEqual(t["verdict"], "separate")
        self.assertEqual(t["reasons"], ["tau = 68 > 50"])

    def test_no_power_is_not_mixed(self):
        t = self.make([0] * 7, sdv=40.0)          # d half-width about 1.96 * 40 * sqrt 2 = 111 > 60
        self.assertEqual(t["verdict"], "separate")
        self.assertTrue(any("no power" in r for r in t["reasons"]), t["reasons"])

    def test_four_against_five_rows_never_mixed(self):
        t = self.make([30] * 4, scales=("2p-adj-r4", "2p-adj-r5"))
        self.assertEqual(t["verdict"], "separate")
        self.assertTrue(any("5 rows" in r for r in t["reasons"]))

    def test_wald_statistic_by_hand(self):
        # two bridge players, d = (0, 60), independent errors of known SD -> chi2 = 60^2 / var(d1 - d0)
        rng = random.Random(1)
        names = ["p", "q"]
        rg, rh = {"p": 1000, "q": 1060}, {"p": 1000, "q": 1000}
        repg = {b: self.reps(rng, rg[b], 15, 20000) for b in names}
        reph = {b: self.reps(rng, rh[b], 15, 20000) for b in names}
        t = fit.bridge_test("2p-adj-r4", "3p-adj-r4", names, rg, rh, repg, reph)
        self.assertAlmostEqual(t["chi2"], 3600 / (4 * 225), delta=0.15)


class EndToEnd(Tmp):
    def test_two_scales_with_configs_levels_bridge_and_schema(self):
        rng = random.Random(11)
        cfg = ["id\tscale\tlevel\tnodes\tsims\tnet\tbasis\tbridge"]
        true = {"ab:b@n1000": 700, "ab:b@n5000": 880, "ab:b@n20000": 1000, "net:shared@s200": 1080}
        bridge = {"ab:b@n1000": "ab1000", "ab:b@n5000": "ab5000", "ab:b@n20000": "ab20000",
                  "net:shared@s200": "shared200"}
        level = {"ab:b@n1000": "1", "ab:b@n5000": "2", "ab:b@n20000": "3"}
        k = 0
        for scale, kind in (("2p-adj-r4", "pair"), ("3p-adj-r4", "one-vs-two")):
            ids = {p: f"{p}#{scale[:2]}" for p in true}
            for p in true:
                net, sims = (("shared", "200") if p.startswith("net") else ("", ""))
                cfg.append(f"{ids[p]}\t{scale}\t{level.get(p, '')}\t{p.split('@n')[1] if '@n' in p else ''}\t{sims}\t"
                           f"{net}\t{'sims200' if net else ''}\t{bridge[p]}")
            names = list(true)
            for i in range(len(names)):
                for j in range(i + 1, len(names)):
                    a, b = names[i], names[j]
                    if kind == "pair":
                        lines = pair_lines(sim_pairs(rng, true[a], true[b], 150), cpu=(1e9, 50, 2e9, 50))
                        self.d.edge(f"e{k}", scale, kind, [ids[a], ids[b]], lines, seed=8102)
                    else:
                        lines = ovt_lines(sim_ovt(rng, true[a], true[b], 150))
                        self.d.edge(f"e{k}", scale, kind, [ids[a], ids[b]], lines, seed=8104)
                    k += 1
        (self.dir / "configs.tsv").write_text("\n".join(cfg) + "\n")
        code, js = run_fit(self.d.write(), self.out, ["--configs", str(self.dir / "configs.tsv"),
                                                      "--replicates", "200", "--gof-replicates", "20"])
        self.assertEqual(code, 0)
        self.assertEqual(set(js), {"schema", "date", "commit", "anchorRule", "scales", "bridge", "levels"})
        self.assertEqual(js["schema"], 1)
        self.assertEqual(js["anchorRule"], "classical browser engine at 20000 nodes = 1000, per scale")
        s = js["scales"]["2p-adj-r4"]
        self.assertEqual(s["anchor"], "ab:b@n20000#2p")
        self.assertEqual(set(s), {"anchor", "players", "pairs", "fit"})
        self.assertEqual(set(s["players"][0]), {"id", "net", "sims", "basis", "elo", "ci95", "games", "edges"})
        self.assertEqual(set(s["fit"]), {"deviance", "df", "p", "maxAbsResidual", "priorGames"})
        self.assertEqual(set(js["scales"]["3p-adj-r4"]["fit"]) - set(s["fit"]), {"threePlayer"})
        net = by_id(js, "2p-adj-r4")["net:shared@s200#2p"]
        self.assertEqual((net["net"], net["sims"], net["basis"], net["edges"], net["games"]),
                         ("shared", 200, ["sims200"], 3, 900))
        self.assertEqual(set(s["pairs"][0]), {"a", "b", "elo", "ci95"})
        self.assertEqual([(x["a"], x["b"]) for x in s["pairs"]], [("net:shared@s200#2p", "ab:b@n20000#2p")])
        self.assertEqual([x["level"] for x in js["levels"]["2p-adj-r4"]], [1, 2, 3])
        self.assertEqual(js["levels"]["2p-adj-r4"][2], {"level": 3, "nodes": 20000, "elo": 1000.0,
                                                         "ci95": [1000.0, 1000.0]})
        self.assertEqual([b["id"] for b in js["bridge"]["players"]], ["ab1000", "ab20000", "ab5000", "shared200"])
        t = js["bridge"]["tests"]
        self.assertEqual(len(t), 1)
        self.assertEqual(t[0]["scales"], ["2p-adj-r4", "3p-adj-r4"])
        self.assertEqual(t[0]["df"], 3)
        self.assertTrue({"chi2", "df", "p", "offset", "slope", "tau", "verdict"} <= set(t[0]))
        rep = (self.out / "fit-report.md").read_text()
        for head in ("### 1. Edges", "### 2. Goodness of fit", "### 3. Cycles", "### 4. Leave one edge out",
                     "### 5. Sensitivity", "### 6. Old against new", "### 7. Three players", "## 8. Bridge",
                     "### 9. Cost"):
            self.assertIn(head, rep)
        self.assertIn("alternative anchor `ab:b@n1000#2p` (level 1)", rep)
        # n1000 is always the subject (1e9 ns / 50 moves = 20 ms), the network always the opponent (2e9 / 50 = 40 ms)
        self.assertIn("| `ab:b@n1000#2p` | 20.0 |", rep)
        self.assertIn("| `net:shared@s200#2p` | 40.0 |", rep)

    def test_bridge_with_shared_config_ids_in_two_and_three_player_scale(self):
        rng = random.Random(12)
        true = {"ab:b@n1000": 700, "ab:b@n5000": 880, "ab:b@n20000": 1000, "net:shared@s200": 1080}
        bridge = {"ab:b@n1000": "ab1000", "ab:b@n5000": "ab5000", "ab:b@n20000": "ab20000",
                  "net:shared@s200": "shared200"}
        level = {"ab:b@n1000": "1", "ab:b@n5000": "2", "ab:b@n20000": "3"}
        cfg = ["id\tscale\tlevel\tnodes\tsims\tnet\tbasis\tbridge"]
        k = 0
        for scale, kind in (("2p-adj-r4", "pair"), ("3p-adj-r4", "one-vs-two")):
            for p in true:
                # the 3p rows of the same ids carry other metadata: no level 1, and ab:b@n5000 is no bridge player
                lv = "" if (scale[0] == "3" and p == "ab:b@n1000") else level.get(p, "")
                br = "" if (scale[0] == "3" and p == "ab:b@n5000") else bridge[p]
                cfg.append(f"{p}\t{scale}\t{lv}\t{p.split('@n')[1] if '@n' in p else ''}\t"
                           f"{'200' if p.startswith('net') else ''}\t{'shared' if p.startswith('net') else ''}\t\t{br}")
            names = list(true)
            for i in range(len(names)):
                for j in range(i + 1, len(names)):
                    a, b = names[i], names[j]
                    if kind == "pair":
                        lines = pair_lines(sim_pairs(rng, true[a], true[b], 150))
                        self.d.edge(f"e{k}", scale, kind, [a, b], lines, seed=8102)
                    else:
                        lines = ovt_lines(sim_ovt(rng, true[a], true[b], 150))
                        self.d.edge(f"e{k}", scale, kind, [a, b], lines, seed=8104)
                    k += 1
        (self.dir / "configs.tsv").write_text("\n".join(cfg) + "\n")
        code, js = run_fit(self.d.write(), self.out, ["--configs", str(self.dir / "configs.tsv"),
                                                      "--replicates", "200", "--gof-replicates", "20"])
        self.assertEqual(code, 0)
        for s in ("2p-adj-r4", "3p-adj-r4"):
            self.assertEqual(js["scales"][s]["anchor"], "ab:b@n20000")
            self.assertEqual(sorted(by_id(js, s)), sorted(true))
            self.assertAlmostEqual(by_id(js, s)["ab:b@n20000"]["elo"], 1000.0)
            self.assertEqual(by_id(js, s)["net:shared@s200"]["net"], "shared")
        self.assertEqual([x["level"] for x in js["levels"]["2p-adj-r4"]], [1, 2, 3])
        self.assertEqual([x["level"] for x in js["levels"]["3p-adj-r4"]], [2, 3])
        rep = (self.out / "fit-report.md").read_text()
        self.assertEqual(rep.count("alternative anchor `ab:b@n1000` (level 1)"), 1)     # the 2p scale only
        self.assertEqual([b["id"] for b in js["bridge"]["players"]], ["ab1000", "ab20000", "shared200"])
        for b in js["bridge"]["players"]:
            self.assertEqual(sorted(b["ratings"]), ["2p-adj-r4", "3p-adj-r4"])
            self.assertEqual(b["ratings"]["2p-adj-r4"]["config"], b["ratings"]["3p-adj-r4"]["config"])
        t = js["bridge"]["tests"]
        self.assertEqual(len(t), 1)
        self.assertEqual((t[0]["scales"], t[0]["df"]), (["2p-adj-r4", "3p-adj-r4"], 2))


# --------------------------------------------------------------------------------------------- long-run behaviour

class LongRun(Tmp):
    def data(self, n=100):
        rng = random.Random(1)
        self.d.edge("e1", "2p-adj-r4", "pair", ["a", "b"], pair_lines(sim_pairs(rng, 1000, 1100, n)), seed=1)
        self.d.edge("e2", "2p-adj-r4", "pair", ["b", "c"], pair_lines(sim_pairs(rng, 1100, 1200, n)), seed=1)
        return self.d.write()

    def cli(self, edges, extra, **kw):
        return subprocess.Popen([sys.executable, str(SRC), str(edges), "--out", str(self.out), "--work",
                                 str(self.dir / "work"), "--anchor", "2p-adj-r4=a", "--date", "2026-11-26",
                                 "--commit", "t", *extra], stderr=subprocess.PIPE, text=True, **kw)

    def test_second_run_uses_the_cache_and_gives_the_same_output(self):
        e = self.data()
        p = self.cli(e, ["--replicates", "40", "--gof-replicates", "5"])
        _o, err1 = p.communicate(timeout=120)
        self.assertEqual(p.returncode, 0, err1)
        first = (self.out / "ratings.json").read_text()
        p = self.cli(e, ["--replicates", "40", "--gof-replicates", "5"])
        _o, err2 = p.communicate(timeout=120)
        self.assertEqual(p.returncode, 0, err2)
        self.assertIn("40/40 cached, nothing to compute", err2)
        self.assertIn("0 replicates computed in this run", err2)
        self.assertEqual(first, (self.out / "ratings.json").read_text())

    def test_lock_held_exits_75(self):
        import fcntl
        e = self.data()
        (self.dir / "work").mkdir()
        fh = open(self.dir / "work" / ".fit.lock", "a")
        fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
        (self.dir / "work" / ".fit.status").write_text(f"pid={os.getpid()} start=x\n")
        try:
            p = self.cli(e, ["--replicates", "20"])
            _o, err = p.communicate(timeout=60)
            self.assertEqual(p.returncode, 75, err)
            self.assertIn("already running: pid=", err)
            p = self.cli(e, ["--status"])
            _o, err = p.communicate(timeout=60)
            self.assertEqual(p.returncode, 75)
        finally:
            fh.close()
        p = self.cli(e, ["--status"], stdout=subprocess.PIPE)
        out, _err = p.communicate(timeout=60)
        self.assertEqual((p.returncode, out.strip()), (0, "not running"))

    def test_sigint_stops_without_output_and_a_rerun_continues(self):
        e = self.data(400)
        p = self.cli(e, ["--replicates", "100000", "--gof-replicates", "0"])
        deadline = time.monotonic() + 60
        line = ""
        while time.monotonic() < deadline:
            line = p.stderr.readline()
            if "bootstrap:" in line:
                break
        self.assertIn("bootstrap:", line)
        p.send_signal(signal.SIGINT)
        _o, err = p.communicate(timeout=60)
        self.assertEqual(p.returncode, 130, err)
        self.assertIn("no output written", err)
        self.assertFalse((self.out / "ratings.json").exists())
        cache = list((self.dir / "work" / "cache").glob("*-boot-*.jsonl"))
        self.assertEqual(len(cache), 1)
        n = len(cache[0].read_text().splitlines())
        self.assertGreater(n, 0)
        p = self.cli(e, ["--replicates", str(n + 5), "--gof-replicates", "0"])
        _o, err = p.communicate(timeout=120)
        self.assertEqual(p.returncode, 0, err)
        self.assertIn(f"({n} cached)", err)


if __name__ == "__main__":
    unittest.main(verbosity=1)
