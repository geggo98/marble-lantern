#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Driver of the Elo study's match runs (plan sections 3.5, 3.6 and 6, task T4).

usage: run.py [--tier 1|2] [--plan] [--status] [--pilot N] [--max-hours H] [--update-costs] [--detach] [--caffeinate]
              [--threads T] [--data DIR] [--out DIR] [--costs FILE] [--energy | --no-energy] [--baseline-minutes M]
              [--configs FILE] [--design FILE ...] [--progress-seconds S]

Reads scripts/elo/configs.tsv and design-tier1.tsv / design-tier2.tsv. For every edge of the chosen tier that is not
complete it runs the match tool: scripts/mctsmatch.sh (MctsMatch) for two players, scripts/threeseatmatch.sh
(ThreeSeatMatch, `--candidate ... --vs ...` for kind one-vs-two, `--table A,B,C` for kind table) for three players.
Results go to DIR/<tierN>/<scale>/<edge-id>/ (DIR default: <data>/elo, <data> default $ELO_DATA or <repo>/data):
pairs-<hash>.jsonl, openings-<hash>.jsonl or tables-<hash>.jsonl, as the tools write them, plus DIR/<tierN>/edges.jsonl and
configs.tsv in the common format of scripts/elo/FORMAT.md, which scripts/elo/fit.py reads as it stands.

STATE comes from the work products only: an edge is SKIP when its result file already holds the units it needs, else the
tool runs and resumes by itself (it skips the pair or opening indices that are in its file). The units of an edge are the
indices 0..N-1, so a pilot with N=10 pairs is the prefix of the real run: the later run continues at pair 10. A top-up row
(source topup, column `of`) extends the file of the Tier 1 edge it names, in the Tier 1 directory, to of.units + units.
A row of source existing has nothing to run (ingest.py brings its data) and counts as SKIP.

COUNTERS, never mixed: ok = edges completed by this run, skip = edges that were complete before, error = edges whose tool
failed (or whose config could not be resolved: missing network file, wrong SHA-256), cleanup = leftover *.tmp.* files of an
aborted run, removed at the start. A summary line prints all four, the edges left and what another run would do.
EXIT CODES: 0 done or nothing to do (also when --max-hours stopped it), 1 at least one error, 2 wrong call, 75 runs already,
130 / 143 stopped by SIGINT / SIGTERM (the running tool is signalled and finishes its running units first).

--plan       print the estimated hours per edge from the cost model and stop (nothing is run).
--status     tell whether a run is going (exit 75 and its pid, start, current edge, elapsed time) or not (exit 0).
--pilot N    N units per edge (pairs, openings) instead of the design's; at the end costs.json is updated from the
             measured CPU time per move (the same as --update-costs). Energy measurement is off unless --energy is given.
--max-hours  stop before the next edge whose estimate would take this run past H hours of estimated work (the unit of
             --plan). Edges run in priority order (1 = backbone first), so a capped run leaves a connected graph.
--update-costs  write DIR/costs.json from the pair, opening and table files found (CPU ms per move per config and scale,
             plies per game and scale) and stop.
--detach     fork, setsid, stderr to DIR/run-tierN.log (path printed); for runs of hours that the agent harness would kill.
--caffeinate keep the Mac awake while this process lives (`caffeinate -ims -w PID`); not needed under `caffeinate -ims ...`
             in the foreground. A detached run with --caffeinate is the form for an overnight run on mains.

COST MODEL. CPU time of a unit = game plies x the moves' ms of the seats: pair plies*(a+b), one-vs-two plies*(c+2*o), table
2*plies*(a+b+c) ms; wall hours = CPU / 11.6 (14 threads x 0.83, plan 3.5). ms per move: classical nodes x ms-per-node of the
scale, network sims x ms-per-sim of the scale (LARGE = 5.6 x SMALL; 5 rows measured 97.4 and 541.7 ms at 200 simulations).
Sources: plan section 3.5, table "Inputs". costs.json (DIR/costs.json) overrides any of them:
{"schema": 1, "efficiency": 11.6, "plies": {"<scale>": n}, "ms_per_move": {"<scale>": {"<config id>": ms}}}.

LONG RUNS (owner's rule): resumable, abortable, observable. Lock: flock on DIR/.run.lock (the kernel frees it on kill -9);
status in DIR/.run.status (display only). Progress: one line per edge on stderr with n/N, the estimated hours done and
left, ETA (from the ratio of wall time to estimated time of the edges played in THIS run) and elapsed time; the tools print
their own progress lines into the same stream. Run it in the devenv shell. Foreground with the errlog wrapper:
    +errlog -- scripts/elo/run.py --tier 1
or detached: scripts/elo/run.py --tier 1 --detach --caffeinate ; then scripts/elo/run.py --status and tail the log.

ENERGY (scripts/energy/README.md): unless --no-energy, the driver starts scripts/energy/sampler.py --detach once (exit 75 =
already running is fine), runs a baseline phase of --baseline-minutes (default 10, once: DIR/.baseline-done) and marks
phase start/end per scale with scripts/energy/mark.py (name $ENERGY_NAME, default elo-tierN; MARK_SOFT=1: a failing marker
never stops the run). Afterwards: ENERGY_NAME=elo-tier1 scripts/energy/report.py. Stop the sampler yourself.

Test hooks (env): ELO_MCTSMATCH, ELO_THREESEAT (command of the match tools, shell-split), ELO_ENERGY_SAMPLER,
ELO_ENERGY_MARK (command of the energy tools), ELO_DATA (the data directory).
Standard library only.
"""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
import re
import shlex
import signal
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
SCALES = ["2p-adj-r4", "2p-adj-r5", "2p-long-r4", "3p-adj-r4", "3p-long-r4"]
CFG_COLS = ["config_id", "scale", "engine", "spec_name", "spec", "nodes", "sims", "net_id", "net_source", "net_archive",
            "sha256_raw", "sha256_stored", "basis", "level", "anchor", "bridge", "tier"]
DESIGN_COLS = ["edge_id", "scale", "block", "kind", "players", "units", "existing_units", "seed", "priority", "source", "of"]
OUT_CONFIG_COLS = ["id", "scale", "engine", "spec", "net", "sha256Prefix", "budget", "basis", "level", "bridge"]
# kind -> (result file prefix, key of the unit index, default opening plies of the tool)
FILES = {"pair": ("pairs-", "pair", 4), "one-vs-two": ("openings-", "opening", 6), "table": ("tables-", "opening", 6)}

# ---- cost model (plan section 3.5, "Inputs"); every number can be overridden by costs.json
EFFICIENCY = 11.6
PLIES = {"2p-adj-r5": 125, "2p-adj-r4": 94, "2p-long-r4": 62, "3p-adj-r4": 140, "3p-long-r4": 80}
MS_PER_NODE = {"2p-adj-r4": 18 / 25600, "2p-adj-r5": 0.00195, "2p-long-r4": 18 / 25600,  # 2p-long: no measurement, r4 value
               "3p-adj-r4": 1 / 1800, "3p-long-r4": 1 / 1000}                          # ab3: 1.8 and 1.0 million nodes/s
MS_PER_SIM_SMALL = {"2p-adj-r5": 97.4 / 200, "2p-adj-r4": 65 / 200, "2p-long-r4": 65 / 200,
                    "3p-adj-r4": 72 / 200, "3p-long-r4": 72 / 200}
LARGE_FACTOR = {"2p-adj-r5": 541.7 / 97.4}
LARGE_FACTOR_DEFAULT = 5.6   # 4 rows: an estimate, not measured on this CPU
PLAN_REFERENCE = {1: (12.0, 16.0), 2: (41.0, 55.0)}  # estimated hours, cap (plan TL;DR 7)


class RunError(Exception):
    """A config or file that the driver cannot use; the edge counts as an error."""


# ------------------------------------------------------------------------------------------------ input files
def read_tsv(path, cols):
    rows, header = [], None
    for no, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if header is None:
            header = fields
            if header != cols:
                raise SystemExit(f"run.py: {path}:{no}: header is {header}, expected {cols}")
            continue
        if len(fields) != len(cols):
            raise SystemExit(f"run.py: {path}:{no}: {len(fields)} fields, expected {len(cols)}")
        rows.append(dict(zip(cols, fields)))
    if header is None:
        raise SystemExit(f"run.py: {path}: no header line")
    return rows


class Edge:
    def __init__(self, row, tier):
        self.row, self.tier = row, tier
        self.id, self.scale, self.kind = row["edge_id"], row["scale"], row["kind"]
        self.players = row["players"].split("|")
        self.units, self.existing = int(row["units"]), int(row["existing_units"])
        self.seed = None if row["seed"] == "-" else int(row["seed"])
        self.priority, self.source, self.of = int(row["priority"]), row["source"], row["of"]
        self.dir_id, self.dir_tier, self.target = self.id, tier, self.units

    @property
    def runnable(self):
        return self.source != "existing" and self.units > 0


def load_designs(paths):
    """Edges of all design files, with top-up rows resolved to the directory and the unit target of the edge they extend."""
    edges, by_id = [], {}
    for tier, path in paths:
        for row in read_tsv(path, DESIGN_COLS):
            e = Edge(row, tier)
            if e.id in by_id:
                raise SystemExit(f"run.py: edge {e.id} appears twice")
            by_id[e.id] = e
            edges.append(e)
    for e in edges:
        if e.source == "topup":
            base = by_id.get(e.of)
            if base is None:
                raise SystemExit(f"run.py: top-up edge {e.id}: edge {e.of!r} of column 'of' is not in the designs")
            e.dir_id, e.dir_tier, e.target = base.dir_id, base.dir_tier, base.target + e.units
    return edges


# ------------------------------------------------------------------------------------------------ result files
def unit_indices(path, key):
    """Unit indices in a result file; a torn (unparsable) line is ignored, as the tools do on resume."""
    found = set()
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError:
        return found
    for line in text.splitlines():
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        if isinstance(rec, dict) and isinstance(rec.get(key), int):
            found.add(rec[key])
    return found


def result_file(edge_dir, kind):
    """(path, units present) of the result file of an edge directory; with several files the one with most units."""
    prefix, key, _ = FILES[kind]
    best = (None, 0)
    for p in sorted(Path(edge_dir).glob(prefix + "*.jsonl")):
        n = len(unit_indices(p, key))
        if best[0] is None or n > best[1]:
            best = (p, n)
    return best


# ------------------------------------------------------------------------------------------------ cost model
def load_costs(path):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {"schema": 1, "ms_per_move": {}, "plies": {}}
    except ValueError as exc:
        raise SystemExit(f"run.py: {path}: not JSON: {exc}")
    data.setdefault("ms_per_move", {})
    data.setdefault("plies", {})
    return data


def ms_per_move(cfg, costs):
    """CPU ms per move of one config (a row of configs.tsv): costs.json first, then the model."""
    over = costs["ms_per_move"].get(cfg["scale"], {}).get(cfg["config_id"])
    if over is not None:
        return float(over)
    if cfg["engine"] == "mcts":
        sims = float(cfg["sims"])
        per_sim = MS_PER_SIM_SMALL[cfg["scale"]]
        if "large" in cfg["net_id"]:
            per_sim *= LARGE_FACTOR.get(cfg["scale"], LARGE_FACTOR_DEFAULT)
        return sims * per_sim
    return float(cfg["nodes"]) * MS_PER_NODE[cfg["scale"]]


def cpu_ms_per_unit(edge, cfgs, costs):
    plies = float(costs["plies"].get(edge.scale, PLIES[edge.scale]))
    ms = [ms_per_move(cfgs[(edge.scale, p)], costs) for p in edge.players]
    if edge.kind == "pair":
        return plies * (ms[0] + ms[1])
    if edge.kind == "one-vs-two":
        return plies * (ms[0] + 2 * ms[1])
    return 2 * plies * sum(ms)


def hours(edge, units, cfgs, costs):
    return units * cpu_ms_per_unit(edge, cfgs, costs) / 1000.0 / float(costs.get("efficiency", EFFICIENCY)) / 3600.0


# ------------------------------------------------------------------------------------------------ tool command lines
def split_scale(scale):
    m = re.fullmatch(r"(\d)p-(adj|long)-r(\d)", scale)
    if not m:
        raise RunError(f"scale {scale!r} is not <players>p-<adj|long>-r<rows>")
    return int(m.group(1)), ("long" if m.group(2) == "long" else "adjacent"), int(m.group(3))


def tool_command(env_name, script):
    if os.environ.get(env_name):
        return shlex.split(os.environ[env_name])
    return [str(REPO / "scripts" / script)]


_sha_cache = {}


def net_file(cfg, data_dir, verify=True, check=True):
    """Path of the network file of a config; checks that it exists and has the SHA-256 of the registry."""
    src = cfg["net_source"]
    rel = src[5:] if src.startswith("data/") else src
    path = Path(data_dir) / rel
    if not check:
        return path
    if not path.is_file():
        raise RunError(f"network file of {cfg['config_id']} missing: {path}")
    if verify and cfg["sha256_raw"] not in ("-", ""):
        if path not in _sha_cache:
            _sha_cache[path] = hashlib.sha256(path.read_bytes()).hexdigest()
        if _sha_cache[path] != cfg["sha256_raw"]:
            raise RunError(f"network file {path}: SHA-256 {_sha_cache[path][:16]}..., the registry has "
                           f"{cfg['sha256_raw'][:16]}... ({cfg['config_id']})")
    return path


def player_texts(edge, cfgs, data_dir, verify=True, check=True):
    """(texts handed to the tool, extra tool arguments, net files by config id). One network file goes in --net; with
    several, every network text carries its own net=PATH."""
    cs = [cfgs[(edge.scale, p)] for p in edge.players]
    files = {c["config_id"]: net_file(c, data_dir, verify, check) for c in cs if c["engine"] == "mcts"}
    distinct = sorted(set(map(str, files.values())))
    texts, extra = [], []
    for c in cs:
        text = c["spec"]
        if c["engine"] == "mcts" and len(distinct) > 1:
            text += f",net={files[c['config_id']]}"
        texts.append(text)
    if len(distinct) == 1:
        extra = ["--net", distinct[0]]
    return texts, extra, files


def build_command(edge, target, out_dir, cfgs, data_dir, threads, progress_seconds, verify=True):
    players, jump, rows = split_scale(edge.scale)
    texts, extra, _ = player_texts(edge, cfgs, data_dir, verify)
    common = ["--out", str(out_dir), "--seed", str(edge.seed), "--threads", str(threads),
              "--progress-seconds", str(progress_seconds)]
    if edge.kind == "pair":
        if players != 2 or len(texts) != 2:
            raise RunError(f"edge {edge.id}: kind pair needs two players of a two-player scale")
        cmd = tool_command("ELO_MCTSMATCH", "mctsmatch.sh") + common + ["--subject", texts[0], "--vs", texts[1],
                                                                       "--pairs", str(target)]
        if jump == "long":
            cmd += ["--jump", "long"]
        if rows != 4:
            cmd += ["--rows", str(rows)]
        return cmd + extra
    if players != 3:
        raise RunError(f"edge {edge.id}: kind {edge.kind} needs a three-player scale")
    base = tool_command("ELO_THREESEAT", "threeseatmatch.sh") + common + ["--jump", jump, "--openings", str(target)]
    if edge.kind == "one-vs-two":
        if len(texts) != 2:
            raise RunError(f"edge {edge.id}: kind one-vs-two needs candidate|opponent")
        return base + ["--candidate", texts[0], "--vs", texts[1]] + extra
    if edge.kind == "table":
        if len(texts) != 3:
            raise RunError(f"edge {edge.id}: kind table needs three players")
        return base + ["--table", ",".join(texts)] + extra
    raise RunError(f"edge {edge.id}: unknown kind {edge.kind!r}")


# ------------------------------------------------------------------------------------------------ edges.jsonl, configs.tsv
def atomic_write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)


def totals_of(path, kind):
    """W/D/L of the first player, the semantics fit.py checks against."""
    w = d = l = 0
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        try:
            r = json.loads(line)
        except ValueError:
            continue
        if kind == "pair":
            w, d, l = w + r.get("wins", 0), d + r.get("draws", 0), l + r.get("losses", 0)
        elif kind == "one-vs-two":
            for s in range(3):
                x = r.get(f"r{s}")
                w, d, l = w + (x == "W"), d + (x == "D"), l + (x == "L")
        else:
            k = 0
            while f"w{k}" in r:
                win, perm = int(r[f"w{k}"]), str(r[f"perm{k}"])
                if win < 0:
                    d += 1
                elif int(perm[win]) == 0:
                    w += 1
                else:
                    l += 1
                k += 1
    return {"wins": w, "draws": d, "losses": l}


def git_commit():
    try:
        out = subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10)
        return out.stdout.strip() or None if out.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def edge_record(edge, target, tier_dir, edge_dir, cfgs, data_dir, threads, commit):
    path, present = result_file(edge_dir, edge.kind)
    texts, _, files = player_texts(edge, cfgs, data_dir, verify=False, check=False)
    nets = {}
    for cid, f in files.items():
        cfg = cfgs[(edge.scale, cid)]
        nets[cid] = {"path": cfg["net_source"], "sha256Prefix": cfg["sha256_raw"][:16]}
    return {"id": edge.dir_id, "scale": edge.scale, "kind": edge.kind, "players": edge.players,
            "specs": dict(zip(edge.players, texts)), "nets": nets, "seed": edge.seed,
            "openingPlies": FILES[edge.kind][2], "unitsPlanned": target,
            "totals": totals_of(path, edge.kind) if path else {"wins": 0, "draws": 0, "losses": 0},
            "file": str(path.relative_to(tier_dir)) if path else None, "unitsPresent": present,
            "commit": commit, "threads": threads, "source": {"kind": "new"}}


def write_formats(tier_dir, records, cfg_rows):
    """Merge `records` (by id) into tier_dir/edges.jsonl and write tier_dir/configs.tsv for all edges in it."""
    tier_dir = Path(tier_dir)
    old = {}
    p = tier_dir / "edges.jsonl"
    if p.exists():
        for line in p.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rec = json.loads(line)
                old[rec["id"]] = rec
    for rec in records:
        old[rec["id"]] = rec
    atomic_write(p, "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in old.values()))
    rows = {}
    for rec in old.values():
        for cid in rec["players"]:
            c = cfg_rows[(rec["scale"], cid)]
            budget = c["sims"] if c["engine"] == "mcts" else c["nodes"]
            clean = lambda v: "" if v == "-" else v
            rows[(rec["scale"], cid)] = [cid, rec["scale"], "net" if c["engine"] == "mcts" else "ab", c["spec"],
                                         clean(c["net_id"]), c["sha256_raw"][:16] if c["sha256_raw"] != "-" else "", budget,
                                         clean(c["basis"]), clean(c["level"]), clean(c["bridge"])]
    atomic_write(tier_dir / "configs.tsv", "\n".join(["\t".join(OUT_CONFIG_COLS)] +
                                                      ["\t".join(map(str, r)) for _, r in sorted(rows.items())]) + "\n")


# ------------------------------------------------------------------------------------------------ cost measurement
def measure_costs(out_root):
    """Mean CPU ms per move per (scale, config) and plies per game and scale from the result files below out_root."""
    cpu, plies = {}, {}
    for tier_dir in sorted(Path(out_root).glob("tier*")):
        p = tier_dir / "edges.jsonl"
        if not p.exists():
            continue
        for line in p.read_text(encoding="utf-8").splitlines():
            rec = json.loads(line)
            if not rec.get("file"):
                continue
            kind, scale = rec["kind"], rec["scale"]
            pl = rec["players"]
            for line2 in (tier_dir / rec["file"]).read_text(encoding="utf-8").splitlines():
                try:
                    r = json.loads(line2)
                except ValueError:
                    continue
                def add(cid, ns, moves):
                    a = cpu.setdefault((scale, cid), [0.0, 0.0])
                    a[0] += ns
                    a[1] += moves
                def game_plies(total, games):
                    a = plies.setdefault(scale, [0.0, 0.0])
                    a[0] += total
                    a[1] += games
                if kind == "pair":
                    if "s_cpu_ns" in r and "s_moves" in r:
                        add(pl[0], r["s_cpu_ns"], r["s_moves"])
                    if "o_cpu_ns" in r and "o_moves" in r:
                        add(pl[1], r["o_cpu_ns"], r["o_moves"])
                    if "plies" in r:
                        game_plies(r["plies"], r.get("wins", 0) + r.get("draws", 0) + r.get("losses", 0) or 2)
                elif kind == "one-vs-two":
                    if "c_cpu_ns" in r and "c_moves" in r:
                        add(pl[0], r["c_cpu_ns"], r["c_moves"])
                    for s in range(3):
                        if f"plies{s}" in r:
                            game_plies(r[f"plies{s}"], 1)
                else:
                    for q in range(3):
                        if f"cpu_ns_p{q}" in r and f"moves_p{q}" in r:
                            add(pl[q], r[f"cpu_ns_p{q}"], r[f"moves_p{q}"])
                    k = 0
                    while f"plies{k}" in r:
                        game_plies(r[f"plies{k}"], 1)
                        k += 1
    ms = {}
    for (scale, cid), (ns, moves) in sorted(cpu.items()):
        if moves > 0:
            ms.setdefault(scale, {})[cid] = round(ns / moves / 1e6, 4)
    pl = {s: round(a[0] / a[1], 2) for s, a in sorted(plies.items()) if a[1] > 0}
    return ms, pl


def update_costs(out_root, costs_path):
    costs = load_costs(costs_path)
    ms, pl = measure_costs(out_root)
    if not ms and not pl:
        print("run.py: no CPU counters in the result files; costs.json unchanged", file=sys.stderr)
        return costs, 0
    n = 0
    for scale, d in ms.items():
        costs["ms_per_move"].setdefault(scale, {}).update(d)
        n += len(d)
    costs["plies"].update(pl)
    costs["schema"] = 1
    costs["updated"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    atomic_write(costs_path, json.dumps(costs, indent=1, sort_keys=True) + "\n")
    print(f"run.py: costs.json updated: {n} config(s), plies of {len(pl)} scale(s) -> {costs_path}", file=sys.stderr)
    return costs, n


# ------------------------------------------------------------------------------------------------ energy, caffeinate
class Energy:
    def __init__(self, name, enabled, out_root, baseline_minutes):
        self.name, self.enabled, self.phase = name, enabled, None
        self.out_root, self.baseline_minutes = Path(out_root), baseline_minutes
        self.env = dict(os.environ, ENERGY_NAME=name, MARK_SOFT="1")

    def _tool(self, env_name, script):
        if os.environ.get(env_name):
            return shlex.split(os.environ[env_name])
        return [sys.executable, str(REPO / "scripts" / "energy" / script)]

    def _call(self, cmd):
        try:
            return subprocess.run(cmd, env=self.env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
        except OSError:
            return 127

    def start(self, say, stop_flag):
        if not self.enabled:
            return
        rc = self._call(self._tool("ELO_ENERGY_SAMPLER", "sampler.py") + ["--detach"])   # 75 = already running: fine
        if rc not in (0, 75):
            say(f"WARNING: the energy sampler did not start (exit {rc}); no energy data")
        done = self.out_root / ".baseline-done"
        if self.baseline_minutes > 0 and not done.exists():
            say(f"energy baseline: {self.baseline_minutes:g} idle minutes; leave the machine alone")
            self.mark("start", "baseline")
            end = time.monotonic() + self.baseline_minutes * 60
            while time.monotonic() < end and not stop_flag():
                time.sleep(min(1.0, max(0.0, end - time.monotonic())))
            self.mark("end", "baseline")
            if not stop_flag():
                done.parent.mkdir(parents=True, exist_ok=True)
                done.touch()

    def mark(self, action, phase):
        if self.enabled:
            self._call(self._tool("ELO_ENERGY_MARK", "mark.py") + [action, phase])

    def enter(self, scale):
        if self.enabled and scale != self.phase:
            self.leave()
            self.mark("start", scale)
            self.phase = scale

    def leave(self):
        if self.enabled and self.phase:
            self.mark("end", self.phase)
            self.phase = None


def caffeinate_command(pid):
    return ["caffeinate", "-ims", "-w", str(pid)]


# ------------------------------------------------------------------------------------------------ run
def hms(seconds):
    seconds = int(seconds)
    return f"{seconds // 3600}:{seconds % 3600 // 60:02d}:{seconds % 60:02d}"


def select_edges(edges, tier):
    order = {s: i for i, s in enumerate(SCALES)}
    chosen = [(e.priority, order.get(e.scale, 99), i, e) for i, e in enumerate(edges) if e.tier == tier]
    return [x[3] for x in sorted(chosen, key=lambda t: t[:3])]


def effective_target(edge, pilot):
    return edge.target if pilot is None else min(pilot, edge.target)


def print_plan(edges, cfgs, costs, tier, pilot, out_root):
    lines, total, per_scale, units_total = [], 0.0, {}, 0
    for e in edges:
        target = effective_target(e, pilot)
        have = result_file(Path(out_root) / f"tier{e.dir_tier}" / e.scale / e.dir_id, e.kind)[1] if e.runnable else 0
        todo = max(0, target - have) if e.runnable else 0
        h_all = hours(e, target if e.runnable else 0, cfgs, costs)
        h_todo = hours(e, todo, cfgs, costs)
        total += h_todo
        per_scale[e.scale] = per_scale.get(e.scale, 0.0) + h_todo
        units_total += todo
        lines.append(f"{e.id:<20} {e.scale:<10} p{e.priority} {e.kind:<10} {todo:>4}/{target if e.runnable else 0:<4} units to do  "
                     f"{h_todo:7.3f} h  (all units: {h_all:.3f} h)" + ("" if e.runnable else "  [existing data: nothing to run]"))
    print("\n".join(lines))
    print()
    for s in SCALES:
        if s in per_scale:
            print(f"scale {s:<10} {per_scale[s]:8.2f} h")
    ref = PLAN_REFERENCE.get(tier)
    tail = f"  (plan: {ref[0]:g} h, cap {ref[1]:g} h)" if ref and pilot is None else ""
    print(f"TOTAL tier {tier}: {len(edges)} edges, {units_total} units to do, {total:.2f} h estimated{tail}")
    if ref and pilot is None and total > ref[1]:
        print(f"WARNING: the estimate is over the cap of {ref[1]:g} h")
    return total


def lock_probe(lock_path):
    """(handle, None) when the lock was taken; (None, status text) when somebody holds it."""
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    fh = open(lock_path, "a")
    try:
        fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return fh, None
    except BlockingIOError:
        fh.close()
        return None, describe_running(lock_path.parent / ".run.status")


def describe_running(status_path):
    try:
        st = status_path.read_text(encoding="utf-8").strip()
    except OSError:
        st = "?"
    m = re.search(r"pid=(\d+)", st)
    et = ""
    if m:
        out = subprocess.run(["ps", "-o", "etime=", "-p", m.group(1)], capture_output=True, text=True)
        et = out.stdout.strip()
    return f"{st}, running for {et or '? (process gone)'}"


def parse_args(argv):
    ap = argparse.ArgumentParser(prog="run.py", description=__doc__.split("\n\n")[0])
    ap.add_argument("--tier", type=int, choices=[1, 2], default=1)
    ap.add_argument("--plan", action="store_true")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--pilot", type=int, default=None, metavar="N")
    ap.add_argument("--max-hours", type=float, default=None, metavar="H")
    ap.add_argument("--update-costs", action="store_true")
    ap.add_argument("--detach", action="store_true")
    ap.add_argument("--caffeinate", action="store_true")
    ap.add_argument("--threads", type=int, default=14, help="threads of each match tool (default 14 of 16 cores)")
    ap.add_argument("--data", default=None, help="data directory (default $ELO_DATA or <repo>/data)")
    ap.add_argument("--out", default=None, help="output directory (default <data>/elo)")
    ap.add_argument("--costs", default=None, help="costs.json (default <out>/costs.json)")
    ap.add_argument("--configs", default=str(HERE / "configs.tsv"))
    ap.add_argument("--design", action="append", default=None,
                    help="design file, as TIER=PATH (default: tier 1 and 2 of scripts/elo); repeatable")
    ap.add_argument("--energy", dest="energy", action="store_true", default=None)
    ap.add_argument("--no-energy", dest="energy", action="store_false")
    ap.add_argument("--baseline-minutes", type=float, default=10.0)
    ap.add_argument("--progress-seconds", type=float, default=60.0)
    a = ap.parse_args(argv)
    if a.pilot is not None and a.pilot < 2:
        ap.error("--pilot needs at least 2 units (MctsMatch plays pairs >= 2)")
    if a.max_hours is not None and a.max_hours <= 0:
        ap.error("--max-hours must be positive")
    return a


def main(argv=None):
    a = parse_args(sys.argv[1:] if argv is None else argv)
    data_dir = Path(a.data or os.environ.get("ELO_DATA") or REPO / "data")
    out_root = Path(a.out) if a.out else data_dir / "elo"
    costs_path = Path(a.costs) if a.costs else out_root / "costs.json"
    designs = []
    for spec in (a.design or [f"1={HERE / 'design-tier1.tsv'}", f"2={HERE / 'design-tier2.tsv'}"]):
        t, _, p = spec.partition("=")
        designs.append((int(t), p))
    cfgs = {(r["scale"], r["config_id"]): r for r in read_tsv(a.configs, CFG_COLS)}
    edges_all = load_designs(designs)
    for e in edges_all:
        missing = [p for p in e.players if (e.scale, p) not in cfgs]
        if missing:
            raise SystemExit(f"run.py: edge {e.id}: config(s) {missing} are not in {a.configs} for scale {e.scale}")
    edges = select_edges(edges_all, a.tier)
    lock_path = out_root / ".run.lock"
    status_path = out_root / ".run.status"

    if a.status:
        fh, text = lock_probe(lock_path)
        if fh is None:
            print(f"run.py: running: {text}")
            return 75
        print("run.py: not running")
        return 0
    if a.plan:
        print_plan(edges, cfgs, load_costs(costs_path), a.tier, a.pilot, out_root)
        return 0
    if a.update_costs:
        update_costs(out_root, costs_path)
        return 0

    fh, text = lock_probe(lock_path)
    if fh is None:
        print(f"run.py: running already: {text}", file=sys.stderr)
        return 75
    if a.detach:
        fh.close()
        log = out_root / f"run-tier{a.tier}.log"
        if os.fork():
            print(f"run.py: detached; log {log}; ask with: {sys.argv[0]} --status", file=sys.stderr)
            os._exit(0)
        os.setsid()
        fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
        os.dup2(fd, 1)
        os.dup2(fd, 2)
        os.dup2(os.open(os.devnull, os.O_RDONLY), 0)
        fh, text = lock_probe(lock_path)
        if fh is None:
            print(f"run.py: running already: {text}", file=sys.stderr)
            os._exit(75)
    try:
        return run(a, edges, cfgs, data_dir, out_root, costs_path, status_path, fh)
    finally:
        fh.close()


def run(a, edges, cfgs, data_dir, out_root, costs_path, status_path, lock_handle):
    t0, start_iso = time.monotonic(), datetime.datetime.now().isoformat(timespec="seconds")
    stop = []
    child = []

    def on_signal(signum, _frame):
        stop.append(signum)
        for c in child:
            try:
                c.send_signal(signum)        # the tool stops after its running units and exits 130 or 143
            except OSError:
                pass

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)
    if a.caffeinate:
        try:
            subprocess.Popen(caffeinate_command(os.getpid()), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            print("run.py: WARNING: caffeinate not found; the machine may sleep", file=sys.stderr)

    def say(msg):
        print(f"run.py: {msg}  (elapsed {hms(time.monotonic() - t0)})", file=sys.stderr, flush=True)

    def state(edge_id):
        atomic_write(status_path, f"pid={os.getpid()} start={start_iso} tier={a.tier} edge={edge_id}\n")

    ok = skip = error = cleanup = 0
    for tier_dir in {out_root / f"tier{e.dir_tier}" for e in edges} | {out_root / f"tier{a.tier}"}:
        for stale in tier_dir.glob("**/*.tmp.*"):       # CLEANUP: leftovers of an aborted run
            stale.unlink()
            cleanup += 1
    costs = load_costs(costs_path)
    pilot_mode = a.pilot is not None
    energy_on = a.energy if a.energy is not None else not pilot_mode
    energy = Energy(os.environ.get("ENERGY_NAME") or f"elo-tier{a.tier}", energy_on, out_root, a.baseline_minutes)
    commit = git_commit()
    state("start")
    energy.start(say, lambda: bool(stop))

    spent = 0.0                      # estimated hours of the work this run has done (the unit of --plan and --max-hours)
    wall_played = est_played = 0.0   # seconds of wall time and of estimate over the edges played in this run
    todo_all = {e.id: max(0, effective_target(e, a.pilot) - (result_file(
        out_root / f"tier{e.dir_tier}" / e.scale / e.dir_id, e.kind)[1] if e.runnable else 0)) if e.runnable else 0
        for e in edges}
    est_left = sum(hours(e, todo_all[e.id], cfgs, costs) for e in edges)
    capped = False
    records = {}                     # tier dir -> edge records to merge
    n = len(edges)
    try:
        for i, e in enumerate(edges, 1):
            if stop:
                break
            target = effective_target(e, a.pilot)
            edge_dir = out_root / f"tier{e.dir_tier}" / e.scale / e.dir_id
            if not e.runnable:
                skip += 1
                say(f"[{i}/{n}] {e.id}: SKIP (existing data, brought in by ingest.py)")
                continue
            _, present = result_file(edge_dir, e.kind)
            if present >= target:
                skip += 1
                say(f"[{i}/{n}] {e.id}: SKIP ({present} of {target} units present)")
                records.setdefault(e.dir_tier, {})[e.id] = (e, target, edge_dir)
                est_left -= hours(e, todo_all[e.id], cfgs, costs)
                continue
            est = hours(e, target - present, cfgs, costs)
            if a.max_hours is not None and spent + est > a.max_hours:
                capped = True
                say(f"[{i}/{n}] {e.id}: estimate {est:.2f} h would take this run past --max-hours "
                    f"{a.max_hours:g} h (spent {spent:.2f} h); stopping before it")
                break
            try:
                cmd = build_command(e, target, edge_dir, cfgs, data_dir, a.threads, a.progress_seconds)
            except RunError as exc:
                error += 1
                say(f"[{i}/{n}] {e.id}: ERROR {exc}")
                continue
            ratio = wall_played / est_played if est_played > 0 else 1.0
            say(f"[{i}/{n}] {e.id}: {target - present} of {target} units to play, estimate {est:.2f} h; "
                f"done this run {spent:.2f} h, left {est_left:.2f} h, ETA {hms(est_left * 3600 * ratio)}")
            state(e.id)
            energy.enter(e.scale)
            edge_dir.mkdir(parents=True, exist_ok=True)
            t_edge = time.monotonic()
            try:
                proc = subprocess.Popen(cmd, stdout=sys.stderr)
            except OSError as exc:
                error += 1
                say(f"[{i}/{n}] {e.id}: ERROR cannot start {cmd[0]}: {exc}")
                continue
            child.append(proc)
            if stop:                                  # a signal arrived between the check and the start
                proc.send_signal(stop[0])
            rc = proc.wait()
            child.remove(proc)
            wall = time.monotonic() - t_edge
            _, now = result_file(edge_dir, e.kind)
            done_units = max(0, now - present)
            frac_est = hours(e, done_units, cfgs, costs)
            spent += frac_est
            est_left -= frac_est
            wall_played += wall
            est_played += frac_est * 3600
            records.setdefault(e.dir_tier, {})[e.id] = (e, target, edge_dir)
            if rc in (130, 143):
                say(f"[{i}/{n}] {e.id}: tool stopped by signal (exit {rc}), {now} of {target} units present")
                if not stop:
                    stop.append(signal.SIGINT if rc == 130 else signal.SIGTERM)
                continue
            if rc != 0:
                error += 1
                say(f"[{i}/{n}] {e.id}: ERROR the tool exited with {rc} after {hms(wall)}; {now} of {target} units present")
                continue
            if now < target:
                error += 1
                say(f"[{i}/{n}] {e.id}: ERROR the tool exited 0 but only {now} of {target} units are present")
                continue
            ok += 1
            say(f"[{i}/{n}] {e.id}: OK {now} of {target} units after {hms(wall)} (estimate {frac_est:.2f} h)")
    finally:
        energy.leave()
        for tier, items in records.items():
            recs = [edge_record(e, target, out_root / f"tier{tier}", d, cfgs, data_dir, a.threads, commit)
                    for e, target, d in items.values()]
            recs = [r for r in recs if r["unitsPresent"] > 0]
            if recs:
                write_formats(out_root / f"tier{tier}", recs, cfgs)
    if pilot_mode and not stop:
        update_costs(out_root, costs_path)

    still = [e for e in edges if e.runnable and
             result_file(out_root / f"tier{e.dir_tier}" / e.scale / e.dir_id, e.kind)[1] < effective_target(e, a.pilot)]
    again = sum(hours(e, effective_target(e, a.pilot) - result_file(
        out_root / f"tier{e.dir_tier}" / e.scale / e.dir_id, e.kind)[1], cfgs, load_costs(costs_path)) for e in still)
    tail = " - aborted" if stop else " - stopped by --max-hours" if capped else \
        " - nothing more to do" if not still else ""
    print(f"run.py: {ok} ok, {skip} skip, {error} error, {cleanup} cleanup, {len(still)} edges left{tail}; "
          f"another run would play {len(still)} edges, about {again:.2f} h; spent {spent:.2f} h estimated, "
          f"{hms(time.monotonic() - t0)} elapsed", file=sys.stderr, flush=True)
    try:
        status_path.unlink()
    except OSError:
        pass
    if stop:
        return 128 + stop[0]
    return 1 if error else 0


if __name__ == "__main__":
    sys.exit(main())
