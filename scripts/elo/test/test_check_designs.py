"""Tests of scripts/elo/check_designs.py (offline; run: python3 -m unittest discover -s scripts/elo/test).

Three groups. (1) Small units on hand-made input: graph functions, the Java constant reader, the TSV reader. Their expected values are
hand calculations. (2) The real registry and designs of the repository pass, and the plan's totals are recomputed here without the
checker's code. (3) Mutations of the real files, one fault each: the checker must name that fault. Each negative test therefore also
proves that the unmutated input is clean (group 2), so a failure of the mutation test comes from the mutation.
"""
import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ELO = HERE.parent
REPO = ELO.parents[1]


def load():
    spec = importlib.util.spec_from_file_location("check_designs", ELO / "check_designs.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


cd = load()


def run_with(configs=None, tier1=None, tier2=None):
    """Run the checker on the real repository with the given file contents (text) in place of the real files."""
    with tempfile.TemporaryDirectory() as tmp:
        paths = {}
        for name, text, real in (("configs", configs, "configs.tsv"), ("tier1", tier1, "design-tier1.tsv"),
                                 ("tier2", tier2, "design-tier2.tsv")):
            if text is not None:
                p = Path(tmp) / real
                p.write_text(text, encoding="utf-8")
                paths[name] = p
        return cd.run_checks(REPO, **paths)


def real(name):
    return (ELO / name).read_text(encoding="utf-8")


def edit_line(text, pick, change):
    """Applies change to the one line for which pick(line) is true; fails the test if the number of such lines is not 1."""
    lines = text.split("\n")
    hit = [i for i, l in enumerate(lines) if pick(l)]
    assert len(hit) == 1, f"expected one matching line, found {len(hit)}"
    lines[hit[0]] = change(lines[hit[0]])
    return "\n".join(l for l in lines if l is not None)


def drop_line(text, pick, expect=1):
    lines = text.split("\n")
    keep = [l for l in lines if not pick(l)]
    assert len(lines) - len(keep) == expect, f"expected to drop {expect} lines, dropped {len(lines) - len(keep)}"
    return "\n".join(keep)


def has(rep, needle):
    return any(needle in e for e in rep.errors)


class GraphTest(unittest.TestCase):
    def test_bridges_of_a_path_and_a_cycle(self):
        self.assertEqual(cd.bridges("abc", [("a", "b"), ("b", "c")]), [("a", "b"), ("b", "c")])
        self.assertEqual(cd.bridges("abc", [("a", "b"), ("b", "c"), ("c", "a")]), [])

    def test_a_triangle_with_a_pendant_has_one_bridge(self):
        edges = [("a", "b"), ("b", "c"), ("c", "a"), ("c", "d")]
        self.assertEqual(cd.bridges("abcd", edges), [("c", "d")])

    def test_two_triangles_joined_by_one_edge(self):
        edges = [("a", "b"), ("b", "c"), ("c", "a"), ("d", "e"), ("e", "f"), ("f", "d"), ("c", "d")]
        self.assertEqual(cd.bridges("abcdef", edges), [("c", "d")])

    def test_parallel_edges_are_one_edge(self):
        # the two roles of a 3-player comparison must not hide a bridge
        self.assertEqual(cd.bridges("ab", [("a", "b"), ("b", "a")]), [("a", "b")])
        self.assertEqual(cd.collapse([("a", "b"), ("b", "a"), ("a", "a")]), [("a", "b")])

    def test_components(self):
        comps = cd.components("abcde", [("a", "b"), ("c", "d")])
        self.assertEqual(sorted(sorted(c) for c in comps), [["a", "b"], ["c", "d"], ["e"]])


class JavaConstantsTest(unittest.TestCase):
    def test_concatenation_and_references(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "A.java"
            p.write_text('class A {\n  static final String OPTIONS = "x=1,y=2";\n'
                         '  public static final String BASE =\n      "ab:" + OPTIONS + ",z=3";\n'
                         '  public static final String LONG = BASE + ",jump=long";\n}\n')
            c = cd.java_constants([p])
        self.assertEqual(c["BASE"], "ab:x=1,y=2,z=3")
        self.assertEqual(c["LONG"], "ab:x=1,y=2,z=3,jump=long")

    def test_unknown_reference_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "A.java"
            p.write_text('class A {\n  static final String BAD = MISSING + "x";\n}\n')
            with self.assertRaises(ValueError):
                cd.java_constants([p])

    def test_real_constants(self):
        c = cd.java_constants([REPO / "search/src/main/java/sternhalma/search/Strategies.java",
                               REPO / "search/src/main/java/sternhalma/search/ReferencePositions.java"])
        # literal text taken by hand from Strategies.java (OPTIONS and BROWSER_SPEC) and ReferencePositions.java (R2_SPEC)
        self.assertEqual(c["BROWSER_SPEC"], "ab:eval=racelin-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,"
                                            "fut=1,futk=20,fut2=1,race=1,racerows=2")
        self.assertEqual(c["BROWSER_3P_LONG_SPEC"], "ab3:eval=linear-r2,nodes=20000,tt=16,jump=long")
        self.assertTrue(c["BROWSER_ROWS5_SPEC"].endswith(",fut2=1,rows=5"))
        self.assertTrue(c["R2_SPEC"].endswith(",nodes=20000"))

    def test_spec_text(self):
        self.assertEqual(cd.spec_text("ab:a=1", 500), "ab:a=1,nodes=500,tt=16")       # StrengthEstimate.spec: base + nodes + tt
        self.assertEqual(cd.spec_text("ab3:e=1,nodes=20000,tt=16", 1000), "ab3:e=1,nodes=1000,tt=16")  # a base with nodes
        self.assertEqual(cd.spec_text("ab:a=1,nodes=20000", 25600), "ab:a=1,nodes=25600")


class TsvTest(unittest.TestCase):
    def test_wrong_field_count_and_header(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "t.tsv"
            p.write_text("# c\na\tb\n1\t2\n3\n")
            rep = cd.Report()
            rows, _ = cd.read_tsv(p, ["a", "b"], rep)
            self.assertEqual([r["a"] for r in rows], ["1"])
            self.assertTrue(has(rep, "1 fields, expected 2"))
            rep = cd.Report()
            cd.read_tsv(p, ["a", "c"], rep)
            self.assertTrue(has(rep, "header is"))

    def test_empty_file_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "t.tsv"
            p.write_text("# only a comment\n")
            rep = cd.Report()
            cd.read_tsv(p, ["a"], rep)
            self.assertTrue(has(rep, "no header"))


class CheckNetTest(unittest.TestCase):
    def _repo_with_archive(self, tmp, payload):
        """A tiny repo with one gzip archive; returns (repo, index, row) whose hashes are right for payload."""
        import gzip
        import hashlib
        repo = Path(tmp)
        (repo / "models/nets").mkdir(parents=True)
        arc = repo / "models/nets/x.gz"
        arc.write_bytes(gzip.compress(payload, mtime=0))
        stored = hashlib.sha256(arc.read_bytes()).hexdigest()
        raw = hashlib.sha256(payload).hexdigest()
        index = {"data/x/candidate.safetensors": {"file": "nets/x.gz", "sha256Raw": raw, "sha256Stored": stored,
                                                  "variants": ["adjacent:2"], "rows": "4"}}
        row = {"scale": "2p-adj-r4", "net_source": "data/x/candidate.safetensors", "net_archive": "models/nets/x.gz",
               "sha256_raw": raw, "sha256_stored": stored}
        return repo, index, row

    def test_consistent_archive_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo, index, row = self._repo_with_archive(tmp, b"weights")
            rep = cd.Report()
            cd.check_net(repo, row, index, rep, None, "t")
            self.assertEqual(rep.errors, [])

    def test_file_that_is_not_the_registered_one(self):
        # registry and index agree with each other, the file on disk differs: only the file hash can tell
        with tempfile.TemporaryDirectory() as tmp:
            repo, index, row = self._repo_with_archive(tmp, b"weights")
            import gzip
            (repo / "models/nets/x.gz").write_bytes(gzip.compress(b"other weights", mtime=0))
            rep = cd.Report()
            cd.check_net(repo, row, index, rep, None, "t")
            self.assertTrue(has(rep, "SHA-256 of the file models/nets/x.gz is not sha256_stored"), rep.errors)
            self.assertTrue(has(rep, "unpacked SHA-256 of models/nets/x.gz is not sha256_raw"), rep.errors)

    def test_only_the_unpacked_hash_is_wrong(self):
        # stored hash right, unpacked hash wrong (registry and index agree on the wrong one): only the unpacking can tell
        with tempfile.TemporaryDirectory() as tmp:
            repo, index, row = self._repo_with_archive(tmp, b"weights")
            row["sha256_raw"] = index["data/x/candidate.safetensors"]["sha256Raw"] = "0" * 64
            rep = cd.Report()
            cd.check_net(repo, row, index, rep, None, "t")
            self.assertTrue(has(rep, "unpacked SHA-256"), rep.errors)
            self.assertFalse(has(rep, "is not sha256_stored"), rep.errors)

    def test_lfs_pointer_is_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            (repo / "models/nets").mkdir(parents=True)
            (repo / "models/nets/x.gz").write_bytes(b"version https://git-lfs.github.com/spec/v1\noid sha256:00\nsize 1\n")
            index = {"data/x/candidate.safetensors": {"file": "nets/x.gz", "sha256Raw": "a", "sha256Stored": "b",
                                                      "variants": ["adjacent:2"], "rows": "4"}}
            r = {"scale": "2p-adj-r4", "net_source": "data/x/candidate.safetensors", "net_archive": "models/nets/x.gz",
                 "sha256_raw": "a", "sha256_stored": "b"}
            rep = cd.Report()
            cd.check_net(repo, r, index, rep, None, "t")
            self.assertEqual(len(rep.errors), 1, rep.errors)
            self.assertIn("LFS pointer", rep.errors[0])


class RealFilesTest(unittest.TestCase):
    def test_the_repository_files_pass(self):
        rep = cd.run_checks(REPO)
        self.assertEqual(rep.errors, [])

    def test_plan_totals_recomputed_without_the_checker(self):
        rows = [l.split("\t") for l in real("design-tier1.tsv").split("\n") if l and not l.startswith("#")][1:]
        new = [r for r in rows if r[9] == "new"]
        gpu = {"pair": 2, "one-vs-two": 3}
        self.assertEqual(sum(int(r[5]) * gpu[r[3]] for r in new), 32800)            # plan 3.5: "about 32,800" games, exactly 32,800
        self.assertEqual(sum(1 for r in new if r[1] == "2p-adj-r5"), 34)
        self.assertEqual(sum(1 for r in new if r[1] == "2p-adj-r4"), 16)
        self.assertEqual(sum(1 for r in new if r[1] == "3p-adj-r4"), 32)          # 16 comparisons in both roles
        self.assertEqual(sum(1 for r in rows if r[9] == "existing"), 4)
        rows2 = [l.split("\t") for l in real("design-tier2.tsv").split("\n") if l and not l.startswith("#")][1:]
        r4 = [r for r in rows2 if r[1] == "2p-adj-r4"]
        self.assertEqual(len(r4), 35)                                                # plan 3.5: "about 35 edges"
        r5top = [r for r in rows2 if r[2] == "r5-topup"]
        self.assertEqual((len(r5top), {r[5] for r in r5top}), (34, {"200"}))         # "+200 pairs x 34 edges"
        self.assertEqual(sum(1 for r in rows2 if r[3] == "table"), 12)               # 6 mixed tables in each of two 3-player scales

    def test_seeds_are_the_unused_ones(self):
        used = {r.split("\t")[7] for r in (real("design-tier1.tsv") + real("design-tier2.tsv")).split("\n")
                if r and not r.startswith("#") and not r.startswith("edge_id")} - {"-"}
        self.assertEqual(used, {"8101", "8102", "8103", "8104", "8105"})

    def test_hashes_equal_the_index(self):
        index = {n["source"]: n for n in json.loads((REPO / "models/index.json").read_text())["networks"]}
        nets = [l.split("\t") for l in real("configs.tsv").split("\n") if l.startswith("net:")]
        self.assertGreater(len(nets), 30)
        for r in nets:
            self.assertEqual(r[10], index[r[8]]["sha256Raw"])
            self.assertEqual(r[11], index[r[8]]["sha256Stored"])


class MutationTest(unittest.TestCase):
    """One fault in a copy of the real files; the checker must report that fault."""

    def test_wrong_raw_hash(self):
        t = edit_line(real("configs.tsv"), lambda l: l.startswith("net:r5-small-gen9@s200\t"),
                      lambda l: re.sub(r"\t[0-9a-f]{64}\t", "\t" + "0" * 64 + "\t", l, count=1))
        self.assertTrue(has(run_with(configs=t), "sha256_raw differs from models/index.json"))

    def test_spec_that_is_not_the_java_constant(self):
        t = edit_line(real("configs.tsv"), lambda l: l.startswith("ab:browser-r5@n44721\t"),
                      lambda l: l.replace("fut=1,", "fut=0,"))
        self.assertTrue(has(run_with(configs=t), "the Java constant BROWSER_ROWS5_SPEC gives"))

    def test_net_spec_with_race_play(self):
        t = edit_line(real("configs.tsv"), lambda l: l.startswith("net:r5-small-gen9@s200\t"),
                      lambda l: l.replace("race=0", "race=1"))
        self.assertTrue(has(run_with(configs=t), "the rated form is mcts"))

    def test_missing_archive(self):
        t = edit_line(real("configs.tsv"), lambda l: l.startswith("net:r5-small-gen9@s200\t"),
                      lambda l: l.replace("models/nets/net5/loop-small-gen-0009", "models/nets/net5/nowhere"))
        rep = run_with(configs=t)
        self.assertTrue(has(rep, "does not exist"))
        self.assertTrue(has(rep, "net_archive"))

    def test_network_of_another_board(self):
        # a 4-row network in the 5-row scale
        t = edit_line(real("configs.tsv"), lambda l: l.startswith("net:r5-small-gen9@s200\t"),
                      lambda l: l.replace("data/net5/loop-small/gen-0009", "data/loop3/gen-0013"))
        self.assertTrue(has(run_with(configs=t), "plays variants"))

    def test_no_anchor(self):
        t = edit_line(real("configs.tsv"), lambda l: l.startswith("ab:browser-r5@n20000\t"),
                      lambda l: l.replace("\t1\t-\t1", "\t0\t-\t1"))
        rep = run_with(configs=t)
        self.assertTrue(has(rep, "expected exactly one anchor"))

    def test_wrong_level(self):
        t = edit_line(real("configs.tsv"), lambda l: l.startswith("ab:browser-r5@n100000\t"),
                      lambda l: l.replace("\t4\t0\t-\t", "\t5\t0\t-\t"))
        self.assertTrue(has(run_with(configs=t), "level of ab:browser-r5@n100000 is 5, expected 4"))

    def test_bridge_player_missing(self):
        t = edit_line(real("configs.tsv"), lambda l: l.startswith("ab:browser-3p-long@n5000\t"),
                      lambda l: l.replace("\tab5000\t", "\t-\t"))
        self.assertTrue(has(run_with(configs=t), "bridge players are"))

    def test_unused_config(self):
        row = [l for l in real("configs.tsv").split("\n") if l.startswith("net:r5-small-gen2@s200\t")][0]
        t = real("configs.tsv") + row.replace("gen2", "gen3").replace("gen-0002", "gen-0003") + "\n"
        rep = run_with(configs=t)
        self.assertTrue(has(rep, "no edge uses it"), rep.errors)

    def test_unregistered_config_in_a_design(self):
        t = edit_line(real("design-tier1.tsv"), lambda l: l.startswith("t1.2p-adj-r5.001\t"),
                      lambda l: l.replace("ab:browser-r5@n1000", "ab:browser-r5@n999"))
        self.assertTrue(has(run_with(tier1=t), "not registered for scale 2p-adj-r5"))

    def test_wrong_seed(self):
        t = edit_line(real("design-tier1.tsv"), lambda l: l.startswith("t1.2p-adj-r4.001\t"), lambda l: l.replace("\t8101\t", "\t3001\t"))
        self.assertTrue(has(run_with(tier1=t), "seed 3001 but scale 2p-adj-r4 uses 8101"))

    def test_missing_role_swap(self):
        t = drop_line(real("design-tier1.tsv"), lambda l: l.startswith("t1.3p-adj-r4.002\t"))
        self.assertTrue(has(run_with(tier1=t), "no role-swap row"))

    def test_undeclared_bridge(self):
        t = drop_line(real("design-tier1.tsv"), lambda l: l.startswith("#bridge-allowed\t2p-adj-r5\tab:browser-r5@n1000\t"))
        rep = run_with(tier1=t)
        self.assertTrue(has(rep, "ab:browser-r5@n1000 - ab:browser-r5@n2236 is a bridge and not declared"), rep.errors)

    def test_stale_bridge_declaration(self):
        t = real("design-tier1.tsv").replace("edge_id\tscale", "#bridge-allowed\t2p-adj-r4\tab:browser-r4@n1000\tab:browser-r4@n2236\n"
                                                                 "edge_id\tscale", 1)
        self.assertTrue(has(run_with(tier1=t), "is not a bridge any more"))

    def test_bridge_in_the_union(self):
        # without the skip edge 5000-20000 the chain 5000-10000-20000 stays a path in the graph of both tiers
        t = drop_line(real("design-tier2.tsv"),
                      lambda l: "r5-skip" in l and "ab:browser-r5@n5000|ab:browser-r5@n20000" in l)
        rep = run_with(tier2=t)
        self.assertTrue(has(rep, "is a bridge in the graph of both tiers"), rep.errors)

    def test_isolated_config(self):
        # drop every edge of rung 500000 in the 2p-long scale: the config has no neighbour
        t = drop_line(real("design-tier2.tsv"), lambda l: "2p-long-r4" in l and "ab:browser-long-r4@n500000" in l, expect=2)
        rep = run_with(tier2=t)
        self.assertTrue(has(rep, "ab:browser-long-r4@n500000 has 0 neighbour(s)"), rep.errors)
        self.assertTrue(has(rep, "graph of both tiers has 2 components"), rep.errors)

    def test_two_components(self):
        # drop every edge between a network and a classical config in the 2p-long scale: the networks form a second component
        t = drop_line(real("design-tier2.tsv"), lambda l: "2p-long-r4" in l and "net:" in l and "ab:" in l, expect=9)
        rep = run_with(tier2=t)
        self.assertTrue(has(rep, "scale 2p-long-r4: graph of both tiers has 2 components"), rep.errors)

    def test_tier2_backbone_priorities(self):
        # all rows of the 2p-long scale at priority 2: Tier 2 alone must connect this scale with its priority-1 rows
        lines = real("design-tier2.tsv").split("\n")
        t = "\n".join(l.replace("\t1\tnew", "\t2\tnew") if "2p-long-r4" in l else l for l in lines)
        self.assertTrue(has(run_with(tier2=t), "Tier 1 plus the Tier 2 edges of priority 1 do not connect"))

    def test_too_few_and_too_many_units(self):
        base = real("design-tier2.tsv")
        for units, wrong in (("300", "300 units after Tier 2"), ("500", "500 units after Tier 2")):
            t = edit_line(base, lambda l: "r4-new" in l and "ab:browser-r4@n100000|ab:browser-r4@n223607" in l,
                          lambda l, u=units: l.replace("\t400\t0\t", f"\t{u}\t0\t"))
            self.assertTrue(has(run_with(tier2=t), wrong), units)

    def test_second_anchor(self):
        t = edit_line(real("configs.tsv"), lambda l: l.startswith("ab:browser-r5@n44721\t"), lambda l: l.replace("\t-\t0\t-\t", "\t-\t1\t-\t"))
        self.assertTrue(has(run_with(configs=t), "scale 2p-adj-r5: expected exactly one anchor, found 2"))

    def test_swapped_bridge_ids_of_the_classical_engine(self):
        c = real("configs.tsv").split("\n")
        i1 = next(i for i, l in enumerate(c) if l.startswith("ab:browser-r4@n1000\t"))
        i5 = next(i for i, l in enumerate(c) if l.startswith("ab:browser-r4@n5000\t"))
        c[i1] = c[i1].replace("\tab1000\t", "\tab5000\t")
        c[i5] = c[i5].replace("\tab5000\t", "\tab1000\t")
        self.assertTrue(has(run_with(configs="\n".join(c)), "bridge of ab:browser-r4@n1000 is ab5000, expected ab1000"))

    def test_swapped_bridge_ids_of_the_shared_network(self):
        c = real("configs.tsv").split("\n")
        i50 = next(i for i, l in enumerate(c) if l.startswith("net:shared-gen0@s50\t2p-adj-r4\t"))
        i200 = next(i for i, l in enumerate(c) if l.startswith("net:shared-gen0@s200\t2p-adj-r4\t"))
        c[i50] = c[i50].replace("\tshared50\t", "\tshared200\t")
        c[i200] = c[i200].replace("\tshared200\t", "\tshared50\t")
        self.assertTrue(has(run_with(configs="\n".join(c)), "bridge of net:shared-gen0@s50 is shared200, expected shared50"))

    def test_backbone_does_not_connect_when_priorities_are_raised(self):
        t = real("design-tier1.tsv")
        for needle in ("net:r5-small-gen9@s50|ab:browser-r5@n223607", "net:r5-small-gen9@s100|ab:browser-r5@n500000",
                       "net:r5-small-gen0@s200|ab:browser-r5@n20000", "net:r5-large-gen0@s200|ab:browser-r5@n100000"):
            t = edit_line(t, lambda l, n=needle: n in l, lambda l: l.replace("\t1\tnew", "\t2\tnew"))
        self.assertTrue(has(run_with(tier1=t), "of priority 0 and 1 do not connect"))

    def test_changed_units_break_the_plan_total(self):
        t = edit_line(real("design-tier1.tsv"), lambda l: l.startswith("t1.2p-adj-r5.001\t"), lambda l: l.replace("\t200\t0\t", "\t100\t0\t"))
        rep = run_with(tier1=t)
        self.assertTrue(has(rep, "Tier 1 block r5-classical-chain"), rep.errors)
        self.assertTrue(has(rep, "Tier 1 total is 32600 games"), rep.errors)

    def test_topup_of_the_wrong_edge(self):
        t = edit_line(real("design-tier2.tsv"), lambda l: "r5-topup" in l and l.endswith("\tt1.2p-adj-r5.001"),
                      lambda l: l.replace("t1.2p-adj-r5.001", "t1.2p-adj-r5.002"))
        self.assertTrue(has(run_with(tier2=t), "topup names"))

    def test_mixed_table_of_three_networks(self):
        t = edit_line(real("design-tier2.tsv"), lambda l: "3p-adj-r4" in l and "\ttable\t" in l and "net:shared-gen0@s800|net:shared-gen0@s200|ab:" in l,
                      lambda l: re.sub(r"\|ab:browser-3p@n\d+", "|net:spec-3p-adjacent@s200", l))
        self.assertTrue(has(run_with(tier2=t), "a mixed table holds"))

    def test_existing_edge_without_its_summary(self):
        t = edit_line(real("design-tier1.tsv"), lambda l: "models/runs/net5-large/h2h.txt" in l,
                      lambda l: l.replace("net5-large/h2h.txt", "net5-large/missing.txt"))
        self.assertTrue(has(run_with(tier1=t), "which does not exist"))

    def test_duplicate_edge(self):
        row = [l for l in real("design-tier1.tsv").split("\n") if l.startswith("t1.2p-adj-r4.002\t")][0]
        a, b = row.split("\t")[4].split("|")
        t = real("design-tier1.tsv") + row.replace("t1.2p-adj-r4.002", "t1.2p-adj-r4.999").replace(f"{a}|{b}", f"{b}|{a}") + "\n"
        self.assertTrue(has(run_with(tier1=t), "is listed twice"))


if __name__ == "__main__":
    unittest.main()
