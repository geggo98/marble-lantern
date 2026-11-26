"""Tests of scripts/elo/ingest.py. Run: python3 -m unittest discover -s scripts/elo/test -p 'test_ingest.py' -v

Expected values come from the cited summary lines as printed in models/runs/**/*.txt and in docs/training.md
(lines 2835 to 2838), typed in by hand below, and from the file listing of data/net5/eval (the pair hashes).
They were not taken from the output of ingest.py.
"""
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import ingest  # noqa: E402

REPO = HERE.parents[2]
R5 = ("ab:eval=linear-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,"
      "rows=5")
S200 = "net:r5-small-gen0@s200"
S9 = "net:r5-small-gen9@s200"
L0 = "net:r5-large-gen0@s200"
L10 = "net:r5-large-gen10@s200"


def ab(n):
    return f"ab:browser-r5@n{n}"


# (file, line number in that file, players, wins, draws, losses, pairs)
EXPECTED = [
    ("models/runs/net5/e1.txt", 1, [S200, ab(1600)], 364, 0, 36, 200),
    ("models/runs/net5/e1.txt", 2, [S200, ab(6400)], 326, 2, 72, 200),
    ("models/runs/net5/e1.txt", 3, [S200, ab(25600)], 270, 4, 126, 200),
    ("models/runs/net5/e2.txt", 1, [S9, S200], 792, 2, 6, 400),
    ("models/runs/net5/e3.txt", 1, [S9, ab(1600)], 398, 1, 1, 200),
    ("models/runs/net5/e3.txt", 2, [S9, ab(6400)], 396, 0, 4, 200),
    ("models/runs/net5/e3.txt", 3, [S9, ab(25600)], 388, 1, 11, 200),
    ("models/runs/net5/e3.txt", 4, [S9, ab(102400)], 380, 0, 20, 200),
    ("models/runs/net5/ladder-check.txt", 3, [ab(6400), ab(1600)], 162, 0, 38, 100),
    ("models/runs/net5-large/e1.txt", 1, [L0, ab(1600)], 379, 0, 21, 200),
    ("models/runs/net5-large/e1.txt", 2, [L0, ab(6400)], 355, 1, 44, 200),
    ("models/runs/net5-large/e1.txt", 3, [L0, ab(25600)], 319, 3, 78, 200),
    ("models/runs/net5-large/e2.txt", 1, [L10, L0], 743, 54, 3, 400),
    ("models/runs/net5-large/h2h.txt", 1, [L10, S9], 544, 0, 256, 400),
    ("models/runs/net5-large/equal-time.txt", 1, [L10, "net:r5-small-gen9@s1000"], 108, 0, 292, 200),
    ("models/runs/net5-large/e3.txt", 1, [L10, ab(1600)], 399, 1, 0, 200),
    ("models/runs/net5-large/e3.txt", 2, [L10, ab(6400)], 393, 1, 6, 200),
    ("models/runs/net5-large/e3.txt", 3, [L10, ab(25600)], 396, 0, 4, 200),
    ("models/runs/net5-large/e3.txt", 4, [L10, ab(102400)], 390, 0, 10, 200),
]

SUBJECT = "mcts:sims=200,batch=1,cpuct=1.0,noise=0,temp=0,race=0"
SMALL0_PATH = "data/net5/gen0-small/gen-0000/candidate.safetensors"


def mcts_line(opp="ab:1600", w=364, d=0, l=36, pairs=200, score=None, subject=SUBJECT):
    score = (w + d / 2) / (2 * pairs) if score is None else score
    return (f"{subject} vs {opp}: Elo +402 +- 60 (95%), score {score:.3f} +- 0.014, {w}-{d}-{l} games, "
            f"{pairs} pairs, 131 plies; subject 119.2 CPU-ms/move, 200.0 sims/searched move, 1678 sims/CPU-s, "
            f"0% moves by the race module; opponent 4.7 CPU-ms/move, 1572 nodes/move\n")


def src(path, **kw):
    base = dict(path=path, kind="mcts", scale="2p-adj-r5", seed=3001, subject_net="r5-small-gen0", threads=14,
                pairs_dir="net5/eval/e1")
    base.update(kw)
    return ingest.Source(**base)


def edges_of(out):
    return [json.loads(x) for x in (Path(out) / "edges.jsonl").read_text().splitlines()]


class RealSummaryLines(unittest.TestCase):
    """The ingested edges carry exactly the W-D-L of the cited summary lines."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = Path(cls.tmp.name) / "out"
        # data=None: only the summary lines count here, so the test does not depend on the git-ignored data/ directory
        cls.result = ingest.run(REPO, ingest.SOURCES, None, cls.out)
        cls.edges = edges_of(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_edge_count(self):
        self.assertEqual(len(self.edges), len(EXPECTED))

    def test_every_cited_line_has_its_counts(self):
        by_origin = {e["source"]["origin"]: e for e in self.edges}
        for path, line, players, w, d, l, pairs in EXPECTED:
            with self.subTest(origin=f"{path}:{line}"):
                e = by_origin[f"{path}:{line}"]
                self.assertEqual(e["players"], players)
                self.assertEqual(e["totals"], {"wins": w, "draws": d, "losses": l})
                self.assertEqual(e["unitsPlanned"], pairs)
                self.assertEqual(e["scale"], "2p-adj-r5")
                self.assertEqual(e["kind"], "pair")

    def test_the_docs_cited_counts(self):
        # docs/training.md lines 2835 to 2838 cite these three results in words; the reader sees them there
        by_players = {tuple(e["players"]): e["totals"] for e in self.edges}
        self.assertEqual(by_players[(L10, L0)], {"wins": 743, "draws": 54, "losses": 3})
        self.assertEqual(by_players[(L10, S9)], {"wins": 544, "draws": 0, "losses": 256})
        self.assertEqual(by_players[(L10, "net:r5-small-gen9@s1000")], {"wins": 108, "draws": 0, "losses": 292})

    def test_games_equal_twice_the_pairs(self):
        for e in self.edges:
            self.assertEqual(sum(e["totals"].values()), 2 * e["unitsPlanned"], e["id"])

    def test_ids_are_unique_and_safe(self):
        ids = [e["id"] for e in self.edges]
        self.assertEqual(len(ids), len(set(ids)))
        for i in ids:
            self.assertRegex(i, r"^[A-Za-z0-9._-]+$")

    def test_without_data_there_are_no_result_files(self):
        for e in self.edges:
            self.assertIsNone(e["file"])
            self.assertEqual(e["unitsPresent"], 0)

    def test_counters(self):
        self.assertEqual(self.result.ok, len(EXPECTED))
        self.assertEqual(self.result.error, 0)

    def test_configs(self):
        rows = [r.split("\t") for r in (self.out / "configs.tsv").read_text().splitlines()]
        head, body = rows[0], rows[1:]
        self.assertEqual(head, ["id", "scale", "engine", "spec", "net", "sha256Prefix", "budget", "basis", "level",
                                "bridge"])
        cfg = {r[0]: dict(zip(head, r)) for r in body}
        self.assertEqual(cfg[ab(25600)]["spec"], R5 + ",nodes=25600")
        self.assertEqual(cfg[S9]["sha256Prefix"], "c236ae0eac7a6a51")
        self.assertEqual(cfg[S9]["basis"], "sims200")
        self.assertEqual(cfg["net:r5-small-gen9@s1000"]["basis"], "time")
        self.assertEqual(cfg[L10]["basis"], "sims200,time")
        self.assertEqual(cfg[ab(25600)]["basis"], "")
        self.assertEqual(len(cfg), len(body))


@unittest.skipUnless(os.environ.get("ELO_DATA") and Path(os.environ["ELO_DATA"]).is_dir(),
                     "set ELO_DATA to the git-ignored data/ directory of the training machine")
class RealPairFiles(unittest.TestCase):
    def test_all_present_pair_files_agree_with_their_summary_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            res = ingest.run(REPO, ingest.SOURCES, Path(os.environ["ELO_DATA"]), Path(tmp))
            with_file = [e for e in res.edges if e["file"]]
            self.assertEqual(len(res.edges), len(EXPECTED))
            self.assertGreater(len(with_file), 0)  # a run that found no file at all proves nothing
            for e in with_file:
                self.assertEqual(e["unitsPresent"], e["unitsPlanned"], e["id"])


class Refusals(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def write(self, rel, text):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        return rel

    def test_gate_paths_are_refused(self):
        for rel in ["data/loop3/gen-0005/gate.txt", "data/loop4/gen-0003/pairs-aaaaaaaaaaaa.jsonl",
                    "x/gate/e1.txt", "models/runs/loop1/decisions.jsonl", "data/net5/loop-small/metrics.jsonl",
                    "data/net5/loop-small/gen-0009/decision.json"]:
            with self.subTest(rel=rel):
                self.write(rel, mcts_line())
                with self.assertRaises(ingest.Refused) as cm:
                    ingest.run(self.root, [src(rel)], None, self.root / "out")
                self.assertIn(rel, str(cm.exception))
                self.assertFalse((self.root / "out" / "edges.jsonl").exists())

    def test_ordinary_names_are_not_taken_for_gates(self):
        # origin: a plain substring test on "gate" would also refuse "aggregate"
        self.write("models/runs/x/aggregate.txt", mcts_line())
        res = ingest.run(self.root, [src("models/runs/x/aggregate.txt")], None, self.root / "out")
        self.assertEqual(res.ok, 1)

    def test_file_without_summary_line_is_an_error(self):
        self.write("a.txt", "mctsmatch: 0 ok, 200 skip, 0 error, 0 left - nothing more to do\n")
        with self.assertRaises(ingest.Inconsistent) as cm:
            ingest.run(self.root, [src("a.txt")], None, self.root / "out")
        self.assertIn("a.txt", str(cm.exception))

    def test_batch_other_than_one_is_refused(self):
        self.write("a.txt", mcts_line(subject=SUBJECT.replace("batch=1", "batch=8")))
        with self.assertRaises(ingest.Refused):
            ingest.run(self.root, [src("a.txt")], None, self.root / "out")

    def test_games_not_twice_the_pairs(self):
        self.write("a.txt", mcts_line(w=364, d=0, l=35))
        with self.assertRaises(ingest.Inconsistent):
            ingest.run(self.root, [src("a.txt")], None, self.root / "out")

    def test_printed_score_must_match_counts(self):
        self.write("a.txt", mcts_line(score=0.5))
        with self.assertRaises(ingest.Inconsistent):
            ingest.run(self.root, [src("a.txt")], None, self.root / "out")

    def test_unregistered_file_is_refused_by_main(self):
        self.write("a.txt", mcts_line())
        rc = ingest.main(["--root", str(self.root), "--out", str(self.root / "out"), str(self.root / "a.txt")])
        self.assertEqual(rc, 2)

    def test_main_exit_code_for_gate_is_2(self):
        rc = ingest.main(["--root", str(REPO), "--out", str(self.root / "out"), "data/loop3/gen-0005/gate.txt"])
        self.assertEqual(rc, 2)


class PairFiles(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        (self.root / "e1.txt").write_text(mcts_line(opp="ab:1600", w=4, d=0, l=2, pairs=3))
        self.source = src("e1.txt")
        self.hash = ingest.pair_hash(SUBJECT, "ab:1600", 3001, 4, R5, True, 5)
        self.dir = self.root / "data" / "net5" / "eval" / "e1"
        self.dir.mkdir(parents=True)

    def pairs(self, wins_losses):
        lines = [json.dumps({"pair": i, "score": (w + 0) / 2, "wins": w, "draws": 0, "losses": 2 - w, "plies": 100})
                 for i, w in enumerate(wins_losses)]
        (self.dir / f"pairs-{self.hash}.jsonl").write_text("\n".join(lines) + "\n")

    def test_hash_is_the_one_of_the_real_files(self):
        # data/net5/eval/e1/pairs-ca35ba7de6ae.jsonl, written by MctsMatch for this subject, rung and seed
        self.assertEqual(ingest.pair_hash(SUBJECT, "ab:1600", 3001, 4, R5, True, 5), "ca35ba7de6ae")
        self.assertEqual(ingest.pair_hash(SUBJECT, "ab:6400", 3001, 4, R5, True, 5), "95cbed36f57d")
        self.assertEqual(ingest.pair_hash(SUBJECT, "ab:25600", 3001, 4, R5, True, 5), "baf7e50d8b3e")
        self.assertEqual(ingest.pair_hash(SUBJECT, SUBJECT + "," + "net=" + SMALL0_PATH, 3002, 4, R5, True, 5),
                         "ae18442216ea")

    def test_file_is_copied_and_checked(self):
        self.pairs([2, 2, 0])  # 4 wins, 0 draws, 2 losses over 3 pairs
        out = self.root / "out"
        res = ingest.run(self.root, [self.source], self.root / "data", out)
        self.assertEqual(res.error, 0)
        (e,) = edges_of(out)
        self.assertEqual(e["file"], f"2p-adj-r5/{e['id']}/pairs-{self.hash}.jsonl")
        self.assertEqual(e["unitsPresent"], 3)
        copied = (out / e["file"]).read_text()
        self.assertEqual(copied, (self.dir / f"pairs-{self.hash}.jsonl").read_text())

    def test_counts_that_disagree_with_the_summary_are_an_error(self):
        self.pairs([2, 1, 0])  # 3-0-3, the summary says 4-0-2
        with self.assertRaises(ingest.Inconsistent):
            ingest.run(self.root, [self.source], self.root / "data", self.root / "out")

    def test_missing_pair_in_file_is_an_error(self):
        self.pairs([2, 2])  # two lines, three planned
        with self.assertRaises(ingest.Inconsistent):
            ingest.run(self.root, [self.source], self.root / "data", self.root / "out")

    def test_duplicate_pair_index_is_an_error(self):
        lines = [json.dumps({"pair": 0, "score": 1.0, "wins": 2, "draws": 0, "losses": 0}),
                 json.dumps({"pair": 0, "score": 1.0, "wins": 2, "draws": 0, "losses": 0}),
                 json.dumps({"pair": 2, "score": 0.0, "wins": 0, "draws": 0, "losses": 2})]
        (self.dir / f"pairs-{self.hash}.jsonl").write_text("\n".join(lines) + "\n")
        with self.assertRaisesRegex(ingest.Inconsistent, "twice"):
            ingest.run(self.root, [self.source], self.root / "data", self.root / "out")

    def test_wrong_pair_index_is_an_error_even_when_the_sums_agree(self):
        wl = {0: (2, 0), 1: (2, 0), 5: (0, 2)}  # sums 4-0-2 as in the summary line, but pair 2 is called 5
        lines = [json.dumps({"pair": i, "score": w / 2, "wins": w, "draws": 0, "losses": l}) for i, (w, l) in wl.items()]
        (self.dir / f"pairs-{self.hash}.jsonl").write_text("\n".join(lines) + "\n")
        with self.assertRaisesRegex(ingest.Inconsistent, "indices 0 to 2"):
            ingest.run(self.root, [self.source], self.root / "data", self.root / "out")

    def test_absent_file_gives_totals_only(self):
        res = ingest.run(self.root, [self.source], self.root / "data", self.root / "out")
        (e,) = edges_of(self.root / "out")
        self.assertIsNone(e["file"])
        self.assertEqual(e["totals"], {"wins": 4, "draws": 0, "losses": 2})
        self.assertEqual(res.ok, 1)

    def test_require_files_fails_on_absent_file(self):
        with self.assertRaises(ingest.Inconsistent):
            ingest.run(self.root, [self.source], self.root / "data", self.root / "out", require_files=True)

    def test_rerun_skips_and_gives_identical_output(self):
        self.pairs([2, 2, 0])
        out = self.root / "out"
        ingest.run(self.root, [self.source], self.root / "data", out)
        first = (out / "edges.jsonl").read_bytes()
        res = ingest.run(self.root, [self.source], self.root / "data", out)
        self.assertEqual((out / "edges.jsonl").read_bytes(), first)
        self.assertEqual((res.ok, res.skip, res.error), (0, 1, 0))

    def test_same_hash_in_another_directory_is_not_used(self):
        # Found on the real data: the hash does not contain the network (--net is not part of the key), so
        # data/net5/eval/e1 (SMALL Gen0) and data/net5/eval/e1-large (LARGE Gen0) hold files of the same name.
        other = self.root / "data" / "net5" / "eval" / "e1-large"
        other.mkdir(parents=True)
        (other / f"pairs-{self.hash}.jsonl").write_text(
            "\n".join(json.dumps({"pair": i, "score": 1.0, "wins": 2, "draws": 0, "losses": 0}) for i in range(3)))
        self.pairs([2, 2, 0])
        ingest.run(self.root, [self.source], self.root / "data", self.root / "out")
        (e,) = edges_of(self.root / "out")
        self.assertEqual(e["totals"], {"wins": 4, "draws": 0, "losses": 2})
        self.assertEqual(e["unitsPresent"], 3)
        self.assertEqual((self.root / "out" / e["file"]).read_text(),
                         (self.dir / f"pairs-{self.hash}.jsonl").read_text())

    def test_pair_file_from_a_gate_directory_is_refused(self):
        gdir = self.root / "data" / "loop3" / "gen-0005"
        gdir.mkdir(parents=True)
        (gdir / f"pairs-{self.hash}.jsonl").write_text(
            "\n".join(json.dumps({"pair": i, "score": 1.0, "wins": 2, "draws": 0, "losses": 0}) for i in range(3)))
        with self.assertRaises(ingest.Refused):
            ingest.run(self.root, [src("e1.txt", pairs_dir="loop3/gen-0005")], self.root / "data",
                       self.root / "out")


class ArenaLine(unittest.TestCase):
    def test_arena_totals_and_orientation(self):
        text = ("A=" + R5 + ",nodes=6400\nB=" + R5 + ",nodes=1600\n"
                "pairs=100 games=200  A wins=162 draws=0 B wins=38  avg plies=127.9\n"
                "score(A)=0.8100 +- 0.0254  Elo(A-B)=+251.9 +- 57.3 (95%)\n")
        p = ingest.parse_arena(text)
        self.assertEqual((p.nodes_a, p.nodes_b, p.pairs, p.wins, p.draws, p.losses), (6400, 1600, 100, 162, 0, 38))


if __name__ == "__main__":
    unittest.main()
