"""Tests of scripts/elo/run.py (offline; run: python3 -m unittest discover -s scripts/elo/test).

The match tools are replaced by a stub (STUB_SOURCE) that has the interface of MctsMatch and ThreeSeatMatch: it appends one JSON
line per unit to pairs-/openings-/tables-<hash>.jsonl, skips units already in the file, holds a lock (exit 75), stops after the
running unit on SIGINT/SIGTERM (exit 130/143), and logs every call to $STUB_LOG. The driver runs as a subprocess, as the owner
runs it. Expected values come from hand calculation: plan section 3.5 (CPU = plies x ms of the seats, 11.6 effective cores).
RUN_PY=<path> runs the tests against another copy of run.py (used for mutation checks).
ELO_DATA=<data dir> adds the end-to-end test on the real Tier 0 data (ingest.py, then fit.py); without it that test is skipped.
"""
import importlib.util
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ELO = HERE.parent
RUN = Path(os.environ.get("RUN_PY", ELO / "run.py"))

spec = importlib.util.spec_from_file_location("run_mod", RUN)
run_mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run_mod)

STUB_SOURCE = r'''
import fcntl, hashlib, json, math, os, random, re, signal, sys, time
args = sys.argv[1:]
opts, vs = {}, []
for i in range(0, len(args), 2):
    if args[i] == "--vs":
        vs.append(args[i + 1])
    else:
        opts[args[i][2:]] = args[i + 1]
with open(os.environ["STUB_LOG"], "a") as f:
    f.write(json.dumps(args) + "\n")
if os.environ.get("STUB_FAIL") and os.environ["STUB_FAIL"] in opts["out"]:
    sys.stderr.write("stub: injected failure\n"); sys.exit(1)
out = opts["out"]; os.makedirs(out, exist_ok=True)
mode = "table" if "table" in opts else "openings" if "candidate" in opts else "pairs"
n = int(opts.get("pairs" if mode == "pairs" else "openings")); seed = int(opts["seed"])
lock = open(os.path.join(out, ".match.lock" if mode == "pairs" else ".threeseat.lock"), "a")
try:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    sys.stderr.write("stub: running already\n"); sys.exit(75)
stop = []
for s in (signal.SIGINT, signal.SIGTERM):
    signal.signal(s, lambda sig, _f: stop.append(sig))
def gamma(text):
    m = re.search(r"(?:nodes|sims)=(\d+)", text)
    return 10 ** (math.log10(int(m.group(1))) if m else 3.0)
if mode == "table":
    players = re.split(r",(?=ab3|ab:|mcts|raw|random)", opts["table"])
else:
    players = [opts.get("subject") or opts["candidate"], vs[0]]
h = hashlib.sha1(("|".join(players) + str(seed)).encode()).hexdigest()[:12]
path = os.path.join(out, {"pairs": "pairs-", "openings": "openings-", "table": "tables-"}[mode] + h + ".jsonl")
done = set()
if os.path.exists(path):
    for ln in open(path).read().splitlines():
        try:
            done.add(json.loads(ln).get("pair", json.loads(ln).get("opening")))
        except ValueError:
            pass
g = [gamma(p) for p in players]
delay = float(os.environ.get("STUB_SLEEP", "0"))
for i in range(n):
    if i in done:
        continue
    if stop:
        break
    time.sleep(delay)
    rng = random.Random(f"{seed}-{i}")
    if mode == "pairs":
        p = g[0] / (g[0] + g[1])
        wins = sum(rng.random() < p for _ in range(2))
        line = {"pair": i, "score": wins / 2, "wins": wins, "draws": 0, "losses": 2 - wins, "plies": 250,
                "s_cpu_ns": 11600000000, "s_moves": 116, "s_sims": 0, "o_cpu_ns": 5800000000, "o_moves": 116, "o_nodes": 1}
    elif mode == "openings":
        pw = g[0] / (g[0] + 2 * g[1])
        line = {"opening": i, "open_hash": "%x" % (seed * 1000 + i)}
        for s in range(3):
            line[f"r{s}"] = "W" if rng.random() < pw else "L"
        for s in range(3):
            line[f"plies{s}"] = 140
        line.update(c_cpu_ns=3 * 47 * 1000000 * 10, c_moves=3 * 47)
    else:
        perms = ["012", "021", "102", "120", "201", "210"]
        line = {"opening": i, "open_hash": "%x" % (seed * 1000 + i)}
        for k, perm in enumerate(perms):
            w = [g[int(c)] for c in perm]
            seat = rng.choices(range(3), weights=w)[0]
            line.update({f"perm{k}": perm, f"w{k}": seat, f"wp{k}": int(perm[seat]), f"plies{k}": 140, f"ml{k}": False})
        for q in range(3):
            line.update({f"cpu_ns_p{q}": 2000000000, f"moves_p{q}": 100, f"sims_p{q}": 0})
    with open(path, "a") as f:
        f.write(json.dumps(line) + "\n")
sys.exit(128 + stop[0] if stop else 0)
'''

CFG_COLS = run_mod.CFG_COLS
DESIGN_COLS = run_mod.DESIGN_COLS
NET_BYTES = b"not a real network, only a file with a known SHA-256"


def sha(data):
    import hashlib
    return hashlib.sha256(data).hexdigest()


def cfg_row(cid, scale, engine, level="-", anchor="0", nodes="-", sims="-", net_id="-", net_source="-", raw="-", bridge="-"):
    spec = {"ab": f"ab:eval=x,rows=5,nodes={nodes}", "ab3": f"ab3:eval=x,nodes={nodes},tt=16",
            "mcts": f"mcts:sims={sims},batch=1,cpuct=1.0,noise=0,temp=0,race=0"}[engine]
    return [cid, scale, engine, "-", spec, nodes, sims, net_id, net_source, "-", raw, "-", "-", level, anchor, bridge, "1"]


def design_row(eid, scale, kind, players, units, seed="8102", priority="1", source="new", of="-", existing="0", block="b"):
    return [eid, scale, block, kind, "|".join(players), str(units), existing, seed, str(priority), source, of]


def write_tsv(path, cols, rows):
    Path(path).write_text("\t".join(cols) + "\n" + "".join("\t".join(r) + "\n" for r in rows), encoding="utf-8")


class Fixture:
    """A temporary world: data dir with one network file, configs.tsv, designs, stub tool."""

    def __init__(self, root, edges1=None, edges2=None):
        self.root = Path(root)
        self.data, self.out = self.root / "data", self.root / "out"
        (self.data / "net5").mkdir(parents=True)
        (self.data / "net5" / "x.safetensors").write_bytes(NET_BYTES)
        raw = sha(NET_BYTES)
        net = dict(net_id="r5-small-gen9", net_source="data/net5/x.safetensors", raw=raw)
        cfgs = [cfg_row("ab:browser-r5@n5000", "2p-adj-r5", "ab", level="2", nodes="5000"),
                cfg_row("ab:browser-r5@n20000", "2p-adj-r5", "ab", level="3", anchor="1", nodes="20000"),
                cfg_row("ab:browser-r5@n100000", "2p-adj-r5", "ab", level="4", nodes="100000"),
                cfg_row("net:r5-small-gen9@s200", "2p-adj-r5", "mcts", sims="200", **net),
                cfg_row("net:r5-small-gen9@s800", "2p-adj-r5", "mcts", sims="800", **net),
                cfg_row("ab:browser-3p@n5000", "3p-adj-r4", "ab3", level="2", nodes="5000"),
                cfg_row("ab:browser-3p@n20000", "3p-adj-r4", "ab3", level="3", anchor="1", nodes="20000"),
                cfg_row("net:shared-gen0@s200", "3p-adj-r4", "mcts", sims="200", net_id="shared-gen0",
                        net_source="data/net5/x.safetensors", raw=raw)]
        write_tsv(self.root / "configs.tsv", CFG_COLS, cfgs)
        a, b, c, n = "ab:browser-r5@n5000", "ab:browser-r5@n20000", "ab:browser-r5@n100000", "net:r5-small-gen9@s200"
        self.edges1 = edges1 if edges1 is not None else [
            design_row("t1.001", "2p-adj-r5", "pair", [a, b], 10),
            design_row("t1.002", "2p-adj-r5", "pair", [b, c], 10),
            design_row("t1.003", "2p-adj-r5", "pair", [n, b], 10, priority=2),
        ]
        write_tsv(self.root / "d1.tsv", DESIGN_COLS, self.edges1)
        write_tsv(self.root / "d2.tsv", DESIGN_COLS, edges2 or [])
        (self.root / "stub.py").write_text(STUB_SOURCE, encoding="utf-8")
        self.log = self.root / "stub.log"
        self.energy_log = self.root / "energy.log"
        (self.root / "energy.py").write_text("import os, sys\nopen(os.environ['ENERGY_LOG'], 'a').write(' '.join(sys.argv[1:]) + "
                                             "' name=' + os.environ.get('ENERGY_NAME', '') + ' soft=' + "
                                             "os.environ.get('MARK_SOFT', '') + '\\n')\n", encoding="utf-8")

    def env(self, **extra):
        e = dict(os.environ)
        e.pop("ELO_DATA", None)
        stub = f"{sys.executable} {self.root / 'stub.py'}"
        e.update(ELO_MCTSMATCH=stub, ELO_THREESEAT=stub, STUB_LOG=str(self.log), ENERGY_LOG=str(self.energy_log),
                 ELO_ENERGY_SAMPLER=f"{sys.executable} {self.root / 'energy.py'} sampler",
                 ELO_ENERGY_MARK=f"{sys.executable} {self.root / 'energy.py'} mark")
        e.update(extra)
        return e

    def argv(self, *more, energy=False):
        base = [sys.executable, str(RUN), "--data", str(self.data), "--out", str(self.out), "--configs",
                str(self.root / "configs.tsv"), "--design", f"1={self.root / 'd1.tsv'}", "--design", f"2={self.root / 'd2.tsv'}",
                "--progress-seconds", "1"]
        return base + ([] if energy else ["--no-energy"]) + list(more)

    def run(self, *more, env=None, energy=False, timeout=60):
        return subprocess.run(self.argv(*more, energy=energy), env=env or self.env(), capture_output=True, text=True,
                              timeout=timeout)

    def popen(self, *more, env=None):
        return subprocess.Popen(self.argv(*more), env=env or self.env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def calls(self):
        return [json.loads(x) for x in self.log.read_text().splitlines()] if self.log.exists() else []

    def units(self, tier, scale, edge, prefix="pairs-", key="pair"):
        files = list((self.out / f"tier{tier}" / scale / edge).glob(prefix + "*.jsonl"))
        self.assertions_one_file = len(files)
        out = []
        for f in files:
            out += [json.loads(x)[key] for x in f.read_text().splitlines()]
        return out


def wait_for(cond, seconds=20):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if cond():
            return True
        time.sleep(0.05)
    return False


class TestPlan(unittest.TestCase):
    def test_estimate_of_one_edge_by_hand(self):
        # edge ab@n5000 vs ab@n20000 on 5 rows: ms per move = 0.00195 ms/node x nodes (plan 3.5: 48.5 ms at 25,600 nodes)
        # CPU of a pair = plies x (a + b) = 125 x (9.75 + 39.0) = 6093.75 ms; 10 pairs = 60.9375 s; / 11.6 cores = 5.2532 s
        with tempfile.TemporaryDirectory() as d:
            fx = Fixture(d)
            r = fx.run("--plan")
            self.assertEqual(r.returncode, 0, r.stderr)
            line = next(x for x in r.stdout.splitlines() if x.startswith("t1.001"))
            self.assertIn("0.001 h", line)                    # 5.2532 s = 0.001459 h
            self.assertIn("TOTAL tier 1: 3 edges, 30 units to do", r.stdout)
            self.assertEqual(fx.calls(), [])                  # --plan runs nothing

    def test_hours_function_values(self):
        with tempfile.TemporaryDirectory() as d:
            fx = Fixture(d)
            cfgs = {(r["scale"], r["config_id"]): r for r in run_mod.read_tsv(fx.root / "configs.tsv", CFG_COLS)}
            edges = run_mod.load_designs([(1, fx.root / "d1.tsv")])
            costs = {"ms_per_move": {}, "plies": {}}
            self.assertAlmostEqual(run_mod.hours(edges[0], 10, cfgs, costs), 6093.75 * 10 / 1000 / 11.6 / 3600, places=9)
            # network, 5 rows: SMALL 97.4 ms at 200 simulations (plan 3.5); pair net@s200 vs ab@n20000 = 125 x (97.4 + 39)
            self.assertAlmostEqual(run_mod.cpu_ms_per_unit(edges[2], cfgs, costs), 125 * (97.4 + 39.0), places=6)
            costs = {"ms_per_move": {"2p-adj-r5": {"ab:browser-r5@n5000": 100.0}}, "plies": {"2p-adj-r5": 200}, "efficiency": 10}
            self.assertAlmostEqual(run_mod.cpu_ms_per_unit(edges[0], cfgs, costs), 200 * (100.0 + 39.0), places=6)
            self.assertAlmostEqual(run_mod.hours(edges[0], 1, cfgs, costs), 200 * 139 / 1000 / 10 / 3600, places=9)

    def test_three_player_cost_formulas(self):
        # one-vs-two: plies x (c + 2o); table: 2 x plies x (a + b + c); ab3 costs 1/1800 ms per node (1.8 M nodes/s)
        with tempfile.TemporaryDirectory() as d:
            a, b = "ab:browser-3p@n5000", "ab:browser-3p@n20000"
            fx = Fixture(d, edges1=[design_row("o", "3p-adj-r4", "one-vs-two", [a, b], 4, seed="8104"),
                                    design_row("t", "3p-adj-r4", "table", [a, b, "net:shared-gen0@s200"], 4, seed="8104")])
            cfgs = {(r["scale"], r["config_id"]): r for r in run_mod.read_tsv(fx.root / "configs.tsv", CFG_COLS)}
            e = run_mod.load_designs([(1, fx.root / "d1.tsv")])
            ms5, ms20, msn = 5000 / 1800, 20000 / 1800, 200 * 72 / 200
            costs = {"ms_per_move": {}, "plies": {}}
            self.assertAlmostEqual(run_mod.cpu_ms_per_unit(e[0], cfgs, costs), 140 * (ms5 + 2 * ms20), places=6)
            self.assertAlmostEqual(run_mod.cpu_ms_per_unit(e[1], cfgs, costs), 2 * 140 * (ms5 + ms20 + msn), places=6)

    def test_plan_of_the_real_tier1_design_is_near_the_plan_and_below_the_cap(self):
        r = subprocess.run([sys.executable, str(RUN), "--plan", "--tier", "1", "--out", tempfile.mkdtemp()], capture_output=True,
                           text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        total = float(r.stdout.split("TOTAL tier 1:")[1].split("units to do,")[1].split("h estimated")[0])
        self.assertTrue(8.0 < total < 16.0, total)             # plan 3.5: about 12 h, cap 16 h, +-30 %
        self.assertIn("2p-adj-r5", r.stdout)


class TestCommands(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.fx = Fixture(self.tmp.name)
        self.cfgs = {(r["scale"], r["config_id"]): r for r in run_mod.read_tsv(self.fx.root / "configs.tsv", CFG_COLS)}

    def tearDown(self):
        self.tmp.cleanup()

    def cmd(self, scale, kind, players, units=10, seed="8102"):
        e = run_mod.load_designs([(1, self._write([design_row("e", scale, kind, players, units, seed=seed)]))])[0]
        return run_mod.build_command(e, 7, Path("/o/e"), self.cfgs, self.fx.data, 3, 60)

    def _write(self, rows):
        p = self.fx.root / "x.tsv"
        write_tsv(p, DESIGN_COLS, rows)
        return p

    def test_pair_command_5_rows_with_one_net(self):
        c = self.cmd("2p-adj-r5", "pair", ["net:r5-small-gen9@s200", "ab:browser-r5@n20000"])
        s = " ".join(c)
        self.assertIn("--subject mcts:sims=200,batch=1,cpuct=1.0,noise=0,temp=0,race=0", s)
        self.assertIn("--vs ab:eval=x,rows=5,nodes=20000", s)
        self.assertIn("--pairs 7", s)
        self.assertIn("--rows 5", s)
        self.assertNotIn("--jump", s)
        self.assertIn(f"--net {self.fx.data / 'net5' / 'x.safetensors'}", s)
        self.assertIn("--seed 8102", s)
        self.assertIn("--threads 3", s)

    def test_two_distinct_net_files_carry_their_own_net_key(self):
        other = self.fx.data / "net5" / "y.safetensors"
        other.write_bytes(b"other")
        rows = [r for r in run_mod.read_tsv(self.fx.root / "configs.tsv", CFG_COLS)]
        for r in rows:
            if r["config_id"] == "net:r5-small-gen9@s800":
                r.update(net_source="data/net5/y.safetensors", sha256_raw=sha(b"other"))
        write_tsv(self.fx.root / "configs.tsv", CFG_COLS, [[r[c] for c in CFG_COLS] for r in rows])
        self.cfgs = {(r["scale"], r["config_id"]): r for r in run_mod.read_tsv(self.fx.root / "configs.tsv", CFG_COLS)}
        c = self.cmd("2p-adj-r5", "pair", ["net:r5-small-gen9@s200", "net:r5-small-gen9@s800"])
        self.assertNotIn("--net", c)
        self.assertTrue(c[c.index("--subject") + 1].endswith(f",net={self.fx.data / 'net5' / 'x.safetensors'}"))
        self.assertTrue(c[c.index("--vs") + 1].endswith(f",net={other}"))

    def test_classical_pair_has_no_net_and_long_hop_flag(self):
        c = self.cmd("2p-adj-r5", "pair", ["ab:browser-r5@n5000", "ab:browser-r5@n20000"])
        self.assertNotIn("--net", c)
        self.assertEqual(c[c.index("--subject") + 1], "ab:eval=x,rows=5,nodes=5000")

    def test_one_vs_two_and_table_commands(self):
        c = self.cmd("3p-adj-r4", "one-vs-two", ["net:shared-gen0@s200", "ab:browser-3p@n20000"], seed="8104")
        self.assertEqual(c[c.index("--candidate") + 1], "mcts:sims=200,batch=1,cpuct=1.0,noise=0,temp=0,race=0")
        self.assertEqual(c[c.index("--vs") + 1], "ab3:eval=x,nodes=20000,tt=16")
        self.assertEqual(c[c.index("--openings") + 1], "7")
        self.assertEqual(c[c.index("--jump") + 1], "adjacent")
        c = self.cmd("3p-adj-r4", "table", ["ab:browser-3p@n5000", "net:shared-gen0@s200", "ab:browser-3p@n20000"], seed="8104")
        self.assertEqual(c[c.index("--table") + 1], "ab3:eval=x,nodes=5000,tt=16,"
                                                   "mcts:sims=200,batch=1,cpuct=1.0,noise=0,temp=0,race=0,ab3:eval=x,nodes=20000,tt=16")
        self.assertNotIn("--candidate", c)
        self.assertNotIn("--vs", c)

    def test_wrong_kind_for_scale_is_refused(self):
        with self.assertRaises(run_mod.RunError):
            self.cmd("2p-adj-r5", "one-vs-two", ["ab:browser-r5@n5000", "ab:browser-r5@n20000"])
        with self.assertRaises(run_mod.RunError):
            self.cmd("3p-adj-r4", "pair", ["ab:browser-3p@n5000", "ab:browser-3p@n20000"], seed="8104")

    def test_net_file_missing_and_wrong_hash_are_errors(self):
        (self.fx.data / "net5" / "x.safetensors").write_bytes(b"tampered")
        run_mod._sha_cache.clear()
        with self.assertRaisesRegex(run_mod.RunError, "SHA-256"):
            self.cmd("2p-adj-r5", "pair", ["net:r5-small-gen9@s200", "ab:browser-r5@n20000"])
        (self.fx.data / "net5" / "x.safetensors").unlink()
        with self.assertRaisesRegex(run_mod.RunError, "missing"):
            self.cmd("2p-adj-r5", "pair", ["net:r5-small-gen9@s200", "ab:browser-r5@n20000"])

    def test_caffeinate_argv(self):
        self.assertEqual(run_mod.caffeinate_command(4242), ["caffeinate", "-ims", "-w", "4242"])


class TestRun(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.fx = Fixture(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def counters(self, r):
        line = [x for x in r.stderr.splitlines() if " ok, " in x and " skip, " in x][-1]
        import re
        m = re.search(r"(\d+) ok, (\d+) skip, (\d+) error, (\d+) cleanup, (\d+) edges left", line)
        return tuple(int(x) for x in m.groups()), line

    def test_full_run_then_second_run_skips_everything(self):
        r = self.fx.run()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.counters(r)[0], (3, 0, 0, 0, 0))
        self.assertIn("nothing more to do", r.stderr)
        for e in ("t1.001", "t1.002", "t1.003"):
            self.assertEqual(sorted(self.fx.units(1, "2p-adj-r5", e)), list(range(10)))
        self.assertEqual(len(self.fx.calls()), 3)
        r2 = self.fx.run()
        self.assertEqual(r2.returncode, 0, r2.stderr)
        self.assertEqual(self.counters(r2)[0], (0, 3, 0, 0, 0))   # SKIP is not an error
        self.assertEqual(len(self.fx.calls()), 3)                 # the tool did not run again

    def test_edges_run_in_priority_order(self):
        self.fx.run()
        outs = [c[c.index("--out") + 1].rsplit("/", 1)[1] for c in self.fx.calls()]
        self.assertEqual(outs, ["t1.001", "t1.002", "t1.003"])    # t1.003 has priority 2

    def test_resume_after_pilot_keeps_the_prefix(self):
        r = self.fx.run("--pilot", "3")
        self.assertEqual(r.returncode, 0, r.stderr)
        d = self.fx.out / "tier1" / "2p-adj-r5" / "t1.001"
        f = next(d.glob("pairs-*.jsonl"))
        prefix = f.read_text()
        self.assertEqual(len(prefix.splitlines()), 3)
        r = self.fx.run()
        self.assertEqual(self.counters(r)[0], (3, 0, 0, 0, 0))
        self.assertTrue(f.read_text().startswith(prefix))         # nothing was played twice
        self.assertEqual(sorted(self.fx.units(1, "2p-adj-r5", "t1.001")), list(range(10)))
        self.assertEqual(self.fx.calls()[-1][self.fx.calls()[-1].index("--pairs") + 1], "10")

    def test_pilot_run_is_skipped_when_enough_units_are_there(self):
        self.fx.run("--pilot", "4")
        r = self.fx.run("--pilot", "3")
        self.assertEqual(self.counters(r)[0], (0, 3, 0, 0, 0))

    def test_pilot_updates_costs_json_from_measured_cpu(self):
        r = self.fx.run("--pilot", "3")
        costs = json.loads((self.fx.out / "costs.json").read_text())
        # stub: 11.6e9 ns over 116 moves = 100 ms per move for the subject, 5.8e9 / 116 = 50 ms for the opponent
        self.assertAlmostEqual(costs["ms_per_move"]["2p-adj-r5"]["ab:browser-r5@n5000"], 100.0, places=3)
        self.assertAlmostEqual(costs["ms_per_move"]["2p-adj-r5"]["net:r5-small-gen9@s200"], 100.0, places=3)
        self.assertAlmostEqual(costs["plies"]["2p-adj-r5"], 125.0, places=3)        # 250 plies per pair of two games
        # a mean over subject and opponent seats: ab@n20000 is the opponent of t1.001 (50 ms) and the subject of t1.002 (100 ms)
        self.assertAlmostEqual(costs["ms_per_move"]["2p-adj-r5"]["ab:browser-r5@n20000"], 75.0 * 1.0, delta=26.0)
        plan = self.fx.run("--plan")
        self.assertEqual(plan.returncode, 0, plan.stderr)
        c = json.loads((self.fx.out / "costs.json").read_text())
        e = run_mod.load_designs([(1, self.fx.root / "d1.tsv")])[0]
        cfgs = {(x["scale"], x["config_id"]): x for x in run_mod.read_tsv(self.fx.root / "configs.tsv", CFG_COLS)}
        self.assertAlmostEqual(run_mod.cpu_ms_per_unit(e, cfgs, c), 125 * (100.0 + c["ms_per_move"]["2p-adj-r5"]["ab:browser-r5@n20000"]),
                               places=3)

    def test_second_start_exits_75_status_reports_it_and_sigint_exits_130(self):
        env = self.fx.env(STUB_SLEEP="0.4")
        p = self.fx.popen(env=env)
        try:
            self.assertTrue(wait_for(lambda: self.fx.log.exists() and (self.fx.out / ".run.status").exists()), "driver did not start")
            second = self.fx.run(env=env)
            self.assertEqual(second.returncode, 75, second.stderr)
            self.assertIn("running already", second.stderr)
            self.assertIn(f"pid={p.pid}", second.stderr)
            st = self.fx.run("--status", env=env)
            self.assertEqual(st.returncode, 75)
            self.assertIn("running", st.stdout)
            self.assertIn("edge=t1.001", st.stdout)
            time.sleep(0.5)
            p.send_signal(signal.SIGINT)
            out, err = p.communicate(timeout=30)
        finally:
            if p.poll() is None:
                p.kill()
        self.assertEqual(p.returncode, 130, err)
        self.assertIn("aborted", err)
        n = len(self.fx.units(1, "2p-adj-r5", "t1.001"))
        self.assertTrue(0 < n < 10, n)                              # the running unit finished, the rest is left
        self.assertEqual(self.fx.run("--status").returncode, 0)     # the lock is free again
        self.assertFalse((self.fx.out / ".run.status").exists())
        r = self.fx.run()                                           # restart without thought
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.counters(r)[0][2], 0)
        for e in ("t1.001", "t1.002", "t1.003"):
            self.assertEqual(sorted(self.fx.units(1, "2p-adj-r5", e)), list(range(10)))

    def test_sigterm_exits_143(self):
        p = self.fx.popen(env=self.fx.env(STUB_SLEEP="0.4"))
        try:
            self.assertTrue(wait_for(lambda: self.fx.log.exists()))
            time.sleep(0.5)
            p.send_signal(signal.SIGTERM)
            _, err = p.communicate(timeout=30)
        finally:
            if p.poll() is None:
                p.kill()
        self.assertEqual(p.returncode, 143, err)
        self.assertIn("aborted", err)

    def test_max_hours_stops_before_the_edge_that_would_pass_the_cap(self):
        # costs.json: 6500 ms per move on both seats; 10 pairs x 125 plies x 13000 ms = 16.25e6 ms CPU = 0.389 h per edge
        costs = {"ms_per_move": {"2p-adj-r5": {c: 6500.0 for c in ("ab:browser-r5@n5000", "ab:browser-r5@n20000",
                                                                    "ab:browser-r5@n100000", "net:r5-small-gen9@s200")}}}
        self.fx.out.mkdir(parents=True)
        (self.fx.out / "costs.json").write_text(json.dumps(costs))
        r = self.fx.run("--max-hours", "0.8")
        self.assertEqual(r.returncode, 0, r.stderr)               # a cap is not an error
        self.assertEqual(self.counters(r)[0], (2, 0, 0, 0, 1))    # 0.389 + 0.389 = 0.78 <= 0.8, a third would be 1.17
        self.assertIn("stopped by --max-hours", r.stderr)
        self.assertEqual(len(self.fx.calls()), 2)
        r = self.fx.run("--max-hours", "0.8")                     # the next run does the rest
        self.assertEqual(self.counters(r)[0], (1, 2, 0, 0, 0))

    def test_cap_smaller_than_the_first_edge_runs_nothing(self):
        r = self.fx.run("--max-hours", "0.0000001")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(self.counters(r)[0], (0, 0, 0, 0, 3))
        self.assertEqual(self.fx.calls(), [])

    def test_failing_tool_is_an_error_and_does_not_stop_the_others(self):
        r = self.fx.run(env=self.fx.env(STUB_FAIL="t1.002"))
        self.assertEqual(r.returncode, 1)
        self.assertEqual(self.counters(r)[0], (2, 0, 1, 0, 1))
        self.assertIn("ERROR the tool exited with 1", r.stderr)
        r = self.fx.run()                                          # fixed: only the failed edge runs
        self.assertEqual(r.returncode, 0)
        self.assertEqual(self.counters(r)[0], (1, 2, 0, 0, 0))

    def test_tool_that_exits_0_without_the_units_is_an_error(self):
        # a stub that does nothing: the driver must not believe the exit code
        (self.fx.root / "noop.py").write_text("import os, sys\nopen(os.environ['STUB_LOG'], 'a').write('[]\\n')\n")
        env = self.fx.env(ELO_MCTSMATCH=f"{sys.executable} {self.fx.root / 'noop.py'}")
        r = self.fx.run(env=env)
        self.assertEqual(r.returncode, 1)
        self.assertEqual(self.counters(r)[0], (0, 0, 3, 0, 3))
        self.assertIn("only 0 of 10 units are present", r.stderr)

    def test_missing_network_file_is_an_error_and_the_tool_is_not_called(self):
        (self.fx.data / "net5" / "x.safetensors").unlink()
        r = self.fx.run()
        self.assertEqual(self.counters(r)[0], (2, 0, 1, 0, 1))
        self.assertEqual(len(self.fx.calls()), 2)
        self.assertIn("missing", r.stderr)

    def test_cleanup_of_leftover_temp_files_is_counted(self):
        d = self.fx.out / "tier1" / "2p-adj-r5" / "t1.001"
        d.mkdir(parents=True)
        (d / "pairs-abc.jsonl.tmp.99").write_text("x")
        (self.fx.out / "tier1" / "edges.jsonl.tmp.7").write_text("x")
        r = self.fx.run()
        self.assertEqual(self.counters(r)[0], (3, 0, 0, 2, 0))
        self.assertFalse(list(self.fx.out.glob("**/*.tmp.*")))

    def test_existing_data_edge_is_a_skip_not_an_error(self):
        rows = self.fx.edges1 + [design_row("t1.ex", "2p-adj-r5", "pair", ["ab:browser-r5@n5000", "ab:browser-r5@n100000"], 0,
                                            seed="-", priority=0, source="existing", of="x", existing="400")]
        write_tsv(self.fx.root / "d1.tsv", DESIGN_COLS, rows)
        r = self.fx.run()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.counters(r)[0], (3, 1, 0, 0, 0))

    def test_topup_extends_the_file_of_the_tier1_edge_in_the_tier1_directory(self):
        write_tsv(self.fx.root / "d2.tsv", DESIGN_COLS,
                  [design_row("t2.001", "2p-adj-r5", "pair", ["ab:browser-r5@n5000", "ab:browser-r5@n20000"], 6, priority=3,
                              source="topup", of="t1.001")])
        self.fx.run()
        r = self.fx.run("--tier", "2")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.counters(r)[0], (1, 0, 0, 0, 0))
        self.assertEqual(sorted(self.fx.units(1, "2p-adj-r5", "t1.001")), list(range(16)))   # 10 + 6, same openings continue
        self.assertEqual(self.fx.calls()[-1][self.fx.calls()[-1].index("--pairs") + 1], "16")
        self.assertFalse((self.fx.out / "tier2" / "2p-adj-r5" / "t2.001").exists())
        rec = [json.loads(x) for x in (self.fx.out / "tier1" / "edges.jsonl").read_text().splitlines()]
        self.assertEqual({x["id"]: x["unitsPlanned"] for x in rec}["t1.001"], 16)

    def test_tier_option_selects_the_design_file(self):
        write_tsv(self.fx.root / "d2.tsv", DESIGN_COLS,
                  [design_row("t2.new", "2p-adj-r5", "pair", ["ab:browser-r5@n5000", "ab:browser-r5@n100000"], 4, source="new")])
        r = self.fx.run("--tier", "2")
        self.assertEqual(self.counters(r)[0], (1, 0, 0, 0, 0))
        self.assertTrue((self.fx.out / "tier2" / "2p-adj-r5" / "t2.new").is_dir())
        self.assertFalse((self.fx.out / "tier1").exists())

    def test_three_player_edges_run_and_write_their_result_files(self):
        a, b, n = "ab:browser-3p@n5000", "ab:browser-3p@n20000", "net:shared-gen0@s200"
        self.fx.edges1 = [design_row("o1", "3p-adj-r4", "one-vs-two", [a, b], 6, seed="8104"),
                          design_row("t1", "3p-adj-r4", "table", [a, b, n], 5, seed="8104")]
        write_tsv(self.fx.root / "d1.tsv", DESIGN_COLS, self.fx.edges1)
        r = self.fx.run()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(sorted(self.fx.units(1, "3p-adj-r4", "o1", "openings-", "opening")), list(range(6)))
        self.assertEqual(sorted(self.fx.units(1, "3p-adj-r4", "t1", "tables-", "opening")), list(range(5)))

    def test_energy_hooks_start_sampler_once_and_mark_each_scale(self):
        a, b, n = "ab:browser-3p@n5000", "ab:browser-3p@n20000", "net:shared-gen0@s200"
        rows = self.fx.edges1 + [design_row("o1", "3p-adj-r4", "one-vs-two", [a, b], 4, seed="8104", priority=2)]
        write_tsv(self.fx.root / "d1.tsv", DESIGN_COLS, rows)
        r = self.fx.run("--baseline-minutes", "0", energy=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        lines = self.fx.energy_log.read_text().splitlines()
        self.assertEqual(sum(1 for x in lines if x.startswith("sampler")), 1)
        self.assertIn("sampler --detach name=elo-tier1 soft=1", lines)
        marks = [x.split(" name=")[0] for x in lines if x.startswith("mark")]
        # priority 1 edges first (2p-adj-r5), then priority 2: edge t1.003 (2p-adj-r5), then o1 (3p-adj-r4): consecutive edges of a
        # scale share one phase
        self.assertEqual(marks, ["mark start 2p-adj-r5", "mark end 2p-adj-r5", "mark start 3p-adj-r4", "mark end 3p-adj-r4"])

    def test_energy_baseline_is_marked_once(self):
        r = self.fx.run("--baseline-minutes", "0.001", energy=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        marks = [x.split(" name=")[0] for x in self.fx.energy_log.read_text().splitlines() if x.startswith("mark")]
        self.assertEqual(marks[:2], ["mark start baseline", "mark end baseline"])
        self.fx.energy_log.unlink()
        self.fx.run("--baseline-minutes", "0.001", "--tier", "2", energy=True)
        self.assertNotIn("baseline", self.fx.energy_log.read_text())

    def test_pilot_has_no_energy_measurement_by_default(self):
        subprocess.run(self.fx.argv("--pilot", "2", energy=True), env=self.fx.env(), capture_output=True, text=True)
        self.assertFalse(self.fx.energy_log.exists())

    def test_detach_returns_at_once_and_the_log_gets_the_summary(self):
        r = self.fx.run("--detach")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("run-tier1.log", r.stderr)
        log = self.fx.out / "run-tier1.log"
        self.assertTrue(wait_for(lambda: log.exists() and "nothing more to do" in log.read_text()), "no summary in the log")
        self.assertEqual(sorted(self.fx.units(1, "2p-adj-r5", "t1.003")), list(range(10)))


class TestFormatAndFit(unittest.TestCase):
    """The edge format the driver writes is exactly what fit.py reads (plan 5.1, FORMAT.md)."""

    def test_driver_output_goes_through_fit(self):
        with tempfile.TemporaryDirectory() as d:
            a, b, c, n = "ab:browser-r5@n5000", "ab:browser-r5@n20000", "ab:browser-r5@n100000", "net:r5-small-gen9@s200"
            pa, pb, pn = "ab:browser-3p@n5000", "ab:browser-3p@n20000", "net:shared-gen0@s200"
            fx = Fixture(d, edges1=[
                design_row("e1", "2p-adj-r5", "pair", [a, b], 30), design_row("e2", "2p-adj-r5", "pair", [b, c], 30),
                design_row("e3", "2p-adj-r5", "pair", [n, a], 30), design_row("e4", "2p-adj-r5", "pair", [n, c], 30),
                design_row("o1", "3p-adj-r4", "one-vs-two", [pa, pb], 30, seed="8104"),
                design_row("o2", "3p-adj-r4", "one-vs-two", [pb, pa], 30, seed="8104"),
                design_row("o3", "3p-adj-r4", "one-vs-two", [pn, pb], 30, seed="8104"),
                design_row("o4", "3p-adj-r4", "one-vs-two", [pb, pn], 30, seed="8104"),
                design_row("tb", "3p-adj-r4", "table", [pa, pb, pn], 30, seed="8104")])
            self.assertEqual(fx.run().returncode, 0)
            tier = fx.out / "tier1"
            recs = [json.loads(x) for x in (tier / "edges.jsonl").read_text().splitlines()]
            self.assertEqual({r["id"] for r in recs}, {"e1", "e2", "e3", "e4", "o1", "o2", "o3", "o4", "tb"})
            e1 = next(r for r in recs if r["id"] == "e1")
            self.assertEqual({"id", "scale", "kind", "players", "specs", "nets", "seed", "openingPlies", "unitsPlanned", "totals",
                              "file", "unitsPresent", "commit", "threads", "source"}, set(e1))
            self.assertEqual((e1["unitsPlanned"], e1["unitsPresent"], e1["openingPlies"], e1["source"]), (30, 30, 4, {"kind": "new"}))
            self.assertEqual(sum(e1["totals"].values()), 60)                      # two games per pair
            self.assertTrue(e1["file"].startswith("2p-adj-r5/e1/pairs-"))
            e3 = next(r for r in recs if r["id"] == "e3")
            self.assertEqual(e3["nets"][n]["path"], "data/net5/x.safetensors")
            self.assertEqual(e3["nets"][n]["sha256Prefix"], sha(NET_BYTES)[:16])
            self.assertEqual(next(r for r in recs if r["id"] == "tb")["openingPlies"], 6)
            header = (tier / "configs.tsv").read_text().splitlines()[0].split("\t")
            self.assertEqual(header, run_mod.OUT_CONFIG_COLS)
            out = fx.root / "fit"
            r = subprocess.run([sys.executable, str(ELO / "fit.py"), "--configs", str(tier / "configs.tsv"), "--out", str(out),
                                "--work", str(fx.root / "fitwork"), "--replicates", "30", "--gof-replicates", "10", str(tier / "edges.jsonl")],
                               capture_output=True, text=True, timeout=300)
            self.assertEqual(r.returncode, 0, r.stderr)
            ratings = json.loads((out / "ratings.json").read_text())
            r5 = {p["id"]: p["elo"] for p in ratings["scales"]["2p-adj-r5"]["players"]}
            self.assertAlmostEqual(r5[b], 1000.0, places=3)                        # the level-3 config is the anchor
            self.assertTrue(r5[c] > r5[b] > r5[a], r5)                              # the stub plays with rating 400 log10(nodes)
            p3 = {p["id"]: p["elo"] for p in ratings["scales"]["3p-adj-r4"]["players"]}
            self.assertAlmostEqual(p3[pb], 1000.0, places=3)
            self.assertTrue(p3[pb] > p3[pa], p3)

    def test_unfinished_edge_is_written_as_partial_and_fit_refuses_it(self):
        with tempfile.TemporaryDirectory() as d:
            fx = Fixture(d, edges1=[design_row("e1", "2p-adj-r5", "pair", ["ab:browser-r5@n5000", "ab:browser-r5@n20000"], 10)])
            fx.run("--pilot", "4")
            rec = json.loads((fx.out / "tier1" / "edges.jsonl").read_text().splitlines()[0])
            self.assertEqual((rec["unitsPlanned"], rec["unitsPresent"]), (4, 4))      # the pilot's own target: complete
            p = fx.popen(env=fx.env(STUB_SLEEP="0.3"))
            self.assertTrue(wait_for(lambda: fx.log.exists()))
            time.sleep(0.4)
            p.send_signal(signal.SIGINT)
            p.communicate(timeout=30)
            rec = json.loads((fx.out / "tier1" / "edges.jsonl").read_text().splitlines()[0])
            self.assertEqual(rec["unitsPlanned"], 10)
            self.assertTrue(4 <= rec["unitsPresent"] < 10, rec)
            r = subprocess.run([sys.executable, str(ELO / "fit.py"), "--out", str(fx.root / "fit"), "--anchor",
                                "2p-adj-r5=ab:browser-r5@n20000", str(fx.out / "tier1" / "edges.jsonl")], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1, r.stderr)                               # fewer units than planned is an error
            self.assertIn("planned units", r.stderr)


@unittest.skipUnless(os.environ.get("ELO_DATA") and Path(os.environ.get("ELO_DATA", "/nonexistent")).is_dir(),
                     "set ELO_DATA to the data directory of the owner's machine for the Tier 0 end-to-end test")
class TestTier0EndToEnd(unittest.TestCase):
    def test_ingest_of_the_real_data_then_fit_gives_ratings_for_2p_adj_r5(self):
        with tempfile.TemporaryDirectory() as d:
            ing = Path(d) / "ingest"
            r = subprocess.run([sys.executable, str(ELO / "ingest.py"), "--data", os.environ["ELO_DATA"], "--out", str(ing)],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("19 ok, 0 skip, 0 error", r.stderr)
            edges = [json.loads(x) for x in (ing / "edges.jsonl").read_text().splitlines()]
            self.assertEqual(len(edges), 19)
            self.assertEqual({e["scale"] for e in edges}, {"2p-adj-r5"})
            # Tier 0 has no 20,000-node rung, so the anchor is the 25,600-node rung of the old ladder (plan 2.4: alternative anchor)
            out = Path(d) / "fit"
            r = subprocess.run([sys.executable, str(ELO / "fit.py"), "--configs", str(ing / "configs.tsv"), "--anchor",
                                "2p-adj-r5=ab:browser-r5@n25600", "--out", str(out), "--work", str(Path(d) / "work"),
                                "--replicates", "200", "--gof-replicates", "50", str(ing / "edges.jsonl")],
                               capture_output=True, text=True, timeout=600)
            self.assertEqual(r.returncode, 0, r.stderr)
            ratings = json.loads((out / "ratings.json").read_text())
            players = {p["id"]: p for p in ratings["scales"]["2p-adj-r5"]["players"]}
            cfg_ids = {line.split("\t")[0] for line in (ing / "configs.tsv").read_text().splitlines()[1:]}
            self.assertEqual(set(players), cfg_ids)
            self.assertAlmostEqual(players["ab:browser-r5@n25600"]["elo"], 1000.0, places=3)
            for p in players.values():
                lo, hi = p["ci95"]
                self.assertTrue(lo <= p["elo"] <= hi, p)
            # the sign of two independent facts of the summary lines: gen0 beats ab:1600 with 364-0-36 (e1.txt), and
            # n6400 beats n1600 only if the ladder is ordered
            self.assertTrue(players["net:r5-small-gen0@s200"]["elo"] > players["ab:browser-r5@n1600"]["elo"])
            self.assertTrue(players["ab:browser-r5@n102400"]["elo"] > players["ab:browser-r5@n6400"]["elo"] >
                            players["ab:browser-r5@n1600"]["elo"])
            self.assertTrue((out / "fit-report.md").read_text().strip())


if __name__ == "__main__":
    unittest.main()
