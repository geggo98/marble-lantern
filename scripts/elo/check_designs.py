#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Checker of the Elo study's config registry and designs (plan section 6, task T3).

usage: check_designs.py [--repo DIR] [--data-root DIR]

Reads scripts/elo/configs.tsv, design-tier1.tsv and design-tier2.tsv of the repository and checks:

  configs    every config resolves: an alpha-beta spec is the Java constant of Strategies.java / ReferencePositions.java with the node
             budget set; a network config has the canonical MCTS spec, an archive under models/ whose gunzipped SHA-256 and whose own
             SHA-256 equal models/index.json, and a source and variant that fit the scale; one anchor per scale (level 3 of the app);
             the seven bridge players in every bridge scale.
  designs    every edge names configs of its scale; the seed of the scale; role-swap partners of 3-player rows; top-up rows point at a
             Tier 1 edge; every registered config is used.
  graph      per scale, connected; Tier 1 may have only the bridges it declares ('#bridge-allowed'), the union of both tiers none
             (2-edge-connected); the edges of priority 0 and 1 alone keep the graph connected, so a capped run leaves a rateable graph.
  totals     edge and game counts of the plan's tables (section 3.5).

Exit code: 0 all checks pass, 1 at least one check failed, 2 wrong usage. Standard library only.
"""
import argparse
import gzip
import hashlib
import json
import re
import sys
from pathlib import Path

SCALES = ["2p-adj-r4", "2p-adj-r5", "2p-long-r4", "3p-adj-r4", "3p-long-r4"]
SEEDS = {"2p-adj-r4": "8101", "2p-adj-r5": "8102", "2p-long-r4": "8103", "3p-adj-r4": "8104", "3p-long-r4": "8105"}
VARIANT = {"2p-adj-r4": ("adjacent:2", "4"), "2p-adj-r5": ("adjacent:2:5", "5"), "2p-long-r4": ("long:2", "4"),
           "3p-adj-r4": ("adjacent:3", "4"), "3p-long-r4": ("long:3", "4")}
PLAYERS = {s: int(s[0]) for s in SCALES}
BRIDGE_SCALES = ["2p-adj-r4", "2p-long-r4", "3p-adj-r4", "3p-long-r4"]
BRIDGE_IDS = ["ab1000", "ab5000", "ab20000", "ab100000", "shared50", "shared200", "shared800"]
BRIDGE_NODES = {1000: "ab1000", 5000: "ab5000", 20000: "ab20000", 100000: "ab100000"}
BRIDGE_SIMS = {50: "shared50", 200: "shared200", 800: "shared800"}
# short name in a config id -> Java constant; the engine of the constant
SPEC_NAMES = {"browser-r4": ("BROWSER_SPEC", "ab"), "best-r4": ("BEST_SPEC", "ab"), "r2-r4": ("R2_SPEC", "ab"),
              "browser-r5": ("BROWSER_ROWS5_SPEC", "ab"), "browser-long-r4": ("BROWSER_LONG_SPEC", "ab"),
              "browser-3p": ("BROWSER_3P_SPEC", "ab3"), "browser-3p-long": ("BROWSER_3P_LONG_SPEC", "ab3")}
BROWSER_NAME = {"2p-adj-r4": "browser-r4", "2p-adj-r5": "browser-r5", "2p-long-r4": "browser-long-r4",
                "3p-adj-r4": "browser-3p", "3p-long-r4": "browser-3p-long"}
SPEC_MARK = {"2p-adj-r4": [], "2p-adj-r5": ["rows=5"], "2p-long-r4": ["jump=long"], "3p-adj-r4": [], "3p-long-r4": ["jump=long"]}
GAMES_PER_UNIT = {"pair": 2, "one-vs-two": 3, "table": 6}
KIND_OF_SCALE = {2: "pair", 3: "one-vs-two"}

CFG_COLS = ["config_id", "scale", "engine", "spec_name", "spec", "nodes", "sims", "net_id", "net_source", "net_archive", "sha256_raw",
            "sha256_stored", "basis", "level", "anchor", "bridge", "tier"]
DESIGN_COLS = ["edge_id", "scale", "block", "kind", "players", "units", "existing_units", "seed", "priority", "source", "of"]

# Plan section 3.5, Tier 1: block -> (edges, games). Existing edges are listed apart.
PLAN_TIER1 = {"r5-classical-chain": (8, 3200), "r5-small-gens": (8, 3200), "r5-large-gen0": (2, 800), "r5-sims": (5, 2000),
              "r5-net-classical": (11, 4400), "r4-pilot": (16, 4800), "p3-pilot": (32, 14400)}
PLAN_TIER1_TOTAL_GAMES = 32800
PLAN_TIER1_EXISTING = 4
# Plan section 3.5, Tier 2: block -> edges. r5-skip is an addition (plan 3.4 item 1: "Tier 2 adds the skip edges between app levels").
PLAN_TIER2_EDGES = {"r5-topup": 34, "r5-large-chain": 5, "r5-skip": 4, "r4-topup": 16, "r4-new": 19}
PLAN_R4_TOTAL_EDGES = 35  # "about 35 edges", plan section 3.5
FINAL_UNITS = {"pair": 400, "one-vs-two": 300}  # units per edge after Tier 2 (plan 3.5)
LOW_UNIT_BLOCKS = {"r5-large-chain", "r5-skip"}  # plan 3.5: 200 pairs, not topped up
TABLES_PER_3P_SCALE, TABLE_UNITS = 6, 150


class Report:
    def __init__(self):
        self.errors, self.info = [], []

    def err(self, msg):
        self.errors.append(msg)

    def note(self, msg):
        self.info.append(msg)


# ------------------------------------------------------------------ small readers
def read_tsv(path, cols, rep):
    """Rows of a TSV file as dicts, plus the '#bridge-allowed' directives. Other '#' lines are comments."""
    rows, directives, header = [], [], None
    for no, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        if line.startswith("#bridge-allowed\t"):
            directives.append(tuple(line.split("\t")[1:]))
            continue
        if line.startswith("#"):
            continue
        fields = line.split("\t")
        if header is None:
            header = fields
            if header != cols:
                rep.err(f"{path}:{no}: header is {header}, expected {cols}")
                return [], directives
            continue
        if len(fields) != len(cols):
            rep.err(f"{path}:{no}: {len(fields)} fields, expected {len(cols)}")
            continue
        row = dict(zip(cols, fields))
        row["_line"] = f"{Path(path).name}:{no}"
        rows.append(row)
    if header is None:
        rep.err(f"{path}: no header line found")
    return rows, directives


def java_constants(paths):
    """The String constants of Java files, with '+' concatenation of literals and other constants resolved."""
    raw = {}
    decl = re.compile(r"^[ \t]*(?:public[ \t]+|private[ \t]+)?static[ \t]+final[ \t]+String[ \t]+(\w+)[ \t]*=(.*?);[ \t]*$",
                      re.S | re.M)
    for p in paths:
        text = Path(p).read_text(encoding="utf-8")
        for m in decl.finditer(text):
            raw[m.group(1)] = m.group(2)
    cache = {}

    def value(name, seen=()):
        if name in cache:
            return cache[name]
        if name in seen:
            raise ValueError(f"circular constant {name}")
        out = []
        for tok in re.finditer(r'"((?:[^"\\]|\\.)*)"|(\w+)', raw[name]):
            if tok.group(1) is not None:
                out.append(tok.group(1))
            else:
                if tok.group(2) not in raw:
                    raise ValueError(f"constant {name} uses {tok.group(2)}, which is not a String constant of the files read")
                out.append(value(tok.group(2), seen + (name,)))
        cache[name] = "".join(out)
        return cache[name]

    return {n: value(n) for n in raw}


def spec_text(base, nodes):
    """Spec of a rung: the base spec with its node budget set (StrengthEstimate.spec adds ',nodes=N,tt=16' to a base without one)."""
    nodes = int(nodes)
    if re.search(r"nodes=\d+", base):
        return re.sub(r"nodes=\d+", f"nodes={nodes}", base)
    return f"{base},nodes={nodes},tt=16"


def sha256_file(path, gz=False):
    h = hashlib.sha256()
    opener = gzip.open if gz else open
    with opener(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ------------------------------------------------------------------ graphs
def collapse(edges):
    """Undirected simple graph: parallel edges (the two roles of a 3-player comparison) count once."""
    return sorted({tuple(sorted(e)) for e in edges if e[0] != e[1]})


def components(nodes, edges):
    adj = {n: set() for n in nodes}
    for a, b in edges:
        adj.setdefault(a, set()).add(b)
        adj.setdefault(b, set()).add(a)
    seen, comps = set(), []
    for n in adj:
        if n in seen:
            continue
        stack, comp = [n], set()
        while stack:
            x = stack.pop()
            if x in comp:
                continue
            comp.add(x)
            stack.extend(adj[x] - comp)
        seen |= comp
        comps.append(comp)
    return comps


def bridges(nodes, edges):
    """Edges whose removal disconnects the graph (lowlink DFS on the collapsed graph)."""
    edges = collapse(edges)
    adj = {n: [] for n in nodes}
    for a, b in edges:
        adj[a].append(b)
        adj[b].append(a)
    disc, low, found, clock = {}, {}, [], [0]

    def dfs(u, parent):
        disc[u] = low[u] = clock[0]
        clock[0] += 1
        for v in adj[u]:
            if v == parent:
                continue
            if v in disc:
                low[u] = min(low[u], disc[v])
            else:
                dfs(v, u)
                low[u] = min(low[u], low[v])
                if low[v] > disc[u]:
                    found.append(tuple(sorted((u, v))))

    for n in adj:
        if n not in disc:
            dfs(n, None)
    return sorted(found)


# ------------------------------------------------------------------ configs
def check_configs(repo, rows, rep, data_root):
    index = {n["source"]: n for n in json.loads((repo / "models/index.json").read_text())["networks"]}
    consts = java_constants([repo / "search/src/main/java/sternhalma/search/Strategies.java",
                             repo / "search/src/main/java/sternhalma/search/ReferencePositions.java"])
    panels = (repo / "web/src/ui/Panels.tsx").read_text()
    m = re.search(r"NODE_LEVELS\s*=\s*\[([\d,\s]+)\]", panels)
    d = re.search(r"DEFAULT_LEVEL\s*=\s*(\d+)", panels)
    if not (m and d):
        rep.err("web/src/ui/Panels.tsx: NODE_LEVELS or DEFAULT_LEVEL not found")
        return {}
    levels = [int(x) for x in m.group(1).replace(" ", "").split(",") if x]
    default_level = int(d.group(1))

    by_key, data_seen, data_missing, archives = {}, 0, 0, 0
    for r in rows:
        where, cid, scale = r["_line"], r["config_id"], r["scale"]
        if scale not in SCALES:
            rep.err(f"{where}: unknown scale {scale!r}")
            continue
        if (scale, cid) in by_key:
            rep.err(f"{where}: config {cid} of {scale} is listed twice")
            continue
        by_key[(scale, cid)] = r
        if r["tier"] not in ("1", "2"):
            rep.err(f"{where}: tier must be 1 or 2, got {r['tier']!r}")
        if r["engine"] in ("ab", "ab3"):
            mm = re.fullmatch(r"ab:([\w-]+)@n(\d+)", cid)
            if not mm or mm.group(1) not in SPEC_NAMES:
                rep.err(f"{where}: {cid} is not of the form ab:<spec-name>@n<nodes> with a known spec name")
                continue
            const, eng = SPEC_NAMES[mm.group(1)]
            nodes = mm.group(2)
            if r["spec_name"] != const or r["engine"] != eng:
                rep.err(f"{where}: {cid} must use constant {const} and engine {eng}, got {r['spec_name']} and {r['engine']}")
            if r["nodes"] != nodes:
                rep.err(f"{where}: nodes column {r['nodes']} differs from the id {cid}")
            if const not in consts:
                rep.err(f"{where}: constant {const} not found in the Java sources")
                continue
            want = spec_text(consts[const], nodes)
            if r["spec"] != want:
                rep.err(f"{where}: spec of {cid} is {r['spec']!r}, the Java constant {const} gives {want!r}")
            if (eng == "ab3") != (PLAYERS[scale] == 3):
                rep.err(f"{where}: engine {eng} does not fit scale {scale}")
            if mm.group(1) in ("best-r4", "r2-r4") and scale != "2p-adj-r4":
                rep.err(f"{where}: {cid} exists in 2p-adj-r4 only")
            if mm.group(1).startswith("browser") and mm.group(1) != BROWSER_NAME[scale]:
                rep.err(f"{where}: {cid} is not the browser engine of {scale} ({BROWSER_NAME[scale]})")
            for mark in SPEC_MARK[scale]:
                if mark not in r["spec"]:
                    rep.err(f"{where}: spec of {cid} lacks {mark}, which scale {scale} needs")
            if r["net_id"] != "-" or r["sims"] != "-":
                rep.err(f"{where}: classical config {cid} must have '-' in the network and simulation columns")
            is_browser = mm.group(1) == BROWSER_NAME[scale]
            nodes_i = int(nodes)
            want_level = str(levels.index(nodes_i) + 1) if is_browser and nodes_i in levels else "-"
            if r["level"] != want_level:
                rep.err(f"{where}: level of {cid} is {r['level']}, expected {want_level} (NODE_LEVELS in Panels.tsx)")
            want_anchor = "1" if is_browser and nodes_i == levels[default_level] else "0"
            if r["anchor"] != want_anchor:
                rep.err(f"{where}: anchor of {cid} is {r['anchor']}, expected {want_anchor}")
            want_bridge = BRIDGE_NODES.get(nodes_i, "-") if is_browser and scale in BRIDGE_SCALES else "-"
            if r["bridge"] != want_bridge:
                rep.err(f"{where}: bridge of {cid} is {r['bridge']}, expected {want_bridge}")
        elif r["engine"] == "mcts":
            mm = re.fullmatch(r"net:([\w-]+)@s(\d+)", cid)
            if not mm:
                rep.err(f"{where}: {cid} is not of the form net:<net-id>@s<sims>")
                continue
            sims = mm.group(2)
            if r["net_id"] != mm.group(1) or r["sims"] != sims:
                rep.err(f"{where}: net_id/sims columns differ from the id {cid}")
            if r["spec"] != f"mcts:sims={sims},batch=1,cpuct=1.0,noise=0,temp=0,race=0":
                rep.err(f"{where}: spec of {cid} is {r['spec']!r}; the rated form is mcts:sims=N,batch=1,cpuct=1.0,noise=0,temp=0,race=0")
            if r["spec_name"] != "-" or r["nodes"] != "-" or r["level"] != "-" or r["anchor"] != "0":
                rep.err(f"{where}: network config {cid} must have '-' for spec_name, nodes and level and anchor 0")
            want_bridge = BRIDGE_SIMS.get(int(sims), "-") if mm.group(1) == "shared-gen0" and scale in BRIDGE_SCALES else "-"
            if r["bridge"] != want_bridge:
                rep.err(f"{where}: bridge of {cid} is {r['bridge']}, expected {want_bridge}")
            check_net(repo, r, index, rep, data_root, where)
            archives += 1
            if data_root is not None:
                if (data_root / r["net_source"]).is_file():
                    data_seen += 1
                else:
                    data_missing += 1
        else:
            rep.err(f"{where}: engine must be ab, ab3 or mcts, got {r['engine']!r}")

    for s in SCALES:
        anchors = [k for k, v in by_key.items() if k[0] == s and v["anchor"] == "1"]
        if len(anchors) != 1:
            rep.err(f"scale {s}: expected exactly one anchor, found {len(anchors)}")
    for s in BRIDGE_SCALES:
        have = sorted(v["bridge"] for k, v in by_key.items() if k[0] == s and v["bridge"] != "-")
        if have != sorted(BRIDGE_IDS):
            rep.err(f"scale {s}: bridge players are {have}, expected {sorted(BRIDGE_IDS)}")
    rep.note(f"configs: {len(by_key)} rows, {archives} network archives checked; "
             f"data files present {data_seen}, absent {data_missing}" if data_root is not None else f"configs: {len(by_key)} rows")
    return by_key


def check_net(repo, r, index, rep, data_root, where):
    src = r["net_source"]
    entry = index.get(src)
    if entry is None:
        rep.err(f"{where}: net_source {src} is not a source in models/index.json")
        return
    if r["net_archive"] != "models/" + entry["file"]:
        rep.err(f"{where}: net_archive {r['net_archive']} differs from models/index.json ({'models/' + entry['file']})")
    if r["sha256_raw"] != entry["sha256Raw"]:
        rep.err(f"{where}: sha256_raw differs from models/index.json for {src}")
    if r["sha256_stored"] != entry["sha256Stored"]:
        rep.err(f"{where}: sha256_stored differs from models/index.json for {src}")
    variant, rows = VARIANT[r["scale"]]
    if variant not in entry["variants"] or str(entry["rows"]) != rows:
        rep.err(f"{where}: network {src} plays variants {entry['variants']} on {entry['rows']} rows; scale {r['scale']} needs {variant} on {rows}")
    archive = repo / r["net_archive"]
    if not archive.is_file():
        rep.err(f"{where}: archive {r['net_archive']} does not exist")
        return
    with open(archive, "rb") as f:
        head = f.read(32)
    if head.startswith(b"version https://git-lfs"):
        rep.err(f"{where}: {r['net_archive']} is an LFS pointer, not the file (git lfs pull)")
        return
    if sha256_file(archive) != r["sha256_stored"]:
        rep.err(f"{where}: SHA-256 of the file {r['net_archive']} is not sha256_stored")
    try:
        raw = sha256_file(archive, gz=True)
    except (OSError, EOFError) as e:
        rep.err(f"{where}: {r['net_archive']} does not unpack: {e}")
        return
    if raw != r["sha256_raw"]:
        rep.err(f"{where}: unpacked SHA-256 of {r['net_archive']} is not sha256_raw")
    if data_root is not None and (data_root / src).is_file() and sha256_file(data_root / src) != r["sha256_raw"]:
        rep.err(f"{where}: {src} under the data root has another SHA-256 than the registry")


# ------------------------------------------------------------------ designs
def parse_players(row, rep):
    return row["players"].split("|")


def check_design_rows(rows, tier, cfgs, rep):
    seen_ids = set()
    for r in rows:
        where = f"{r['_line']} ({r['edge_id']})"
        r["_tier"] = tier
        if r["edge_id"] in seen_ids:
            rep.err(f"{where}: edge id used twice")
        seen_ids.add(r["edge_id"])
        if not r["edge_id"].startswith(f"t{tier}.{r['scale']}."):
            rep.err(f"{where}: edge id must start with t{tier}.{r['scale']}.")
        scale = r["scale"]
        if scale not in SCALES:
            rep.err(f"{where}: unknown scale {scale!r}")
            continue
        kind = r["kind"]
        if kind not in GAMES_PER_UNIT:
            rep.err(f"{where}: unknown kind {kind!r}")
            continue
        want_kind = KIND_OF_SCALE[PLAYERS[scale]]
        if kind != want_kind and not (kind == "table" and PLAYERS[scale] == 3):
            rep.err(f"{where}: kind {kind} does not fit scale {scale}")
        pl = parse_players(r, rep)
        r["_pl"] = pl
        if len(pl) != (3 if kind == "table" else 2) or len(set(pl)) != len(pl):
            rep.err(f"{where}: players {r['players']} must be {3 if kind == 'table' else 2} different configs")
        for c in pl:
            if (scale, c) not in cfgs:
                rep.err(f"{where}: config {c} is not registered for scale {scale}")
            elif int(cfgs[(scale, c)]["tier"]) > tier:
                rep.err(f"{where}: config {c} is registered for tier {cfgs[(scale, c)]['tier']} but used in tier {tier}")
        for col in ("units", "existing_units", "priority"):
            if not r[col].isdigit():
                rep.err(f"{where}: {col} must be a non-negative integer, got {r[col]!r}")
                r[col] = "0"
        src = r["source"]
        if src not in ("new", "existing", "topup"):
            rep.err(f"{where}: source must be new, existing or topup, got {src!r}")
        if src == "existing":
            if int(r["units"]) != 0 or int(r["existing_units"]) == 0 or r["seed"] != "-":
                rep.err(f"{where}: an existing edge has units 0, existing_units > 0 and seed '-'")
            if tier != 1:
                rep.err(f"{where}: existing edges belong in design-tier1.tsv")
        else:
            if int(r["units"]) == 0:
                rep.err(f"{where}: a {src} edge needs units > 0")
            if int(r["existing_units"]) != 0:
                rep.err(f"{where}: existing_units must be 0 for source {src}")
            if r["seed"] != SEEDS.get(scale):
                rep.err(f"{where}: seed {r['seed']} but scale {scale} uses {SEEDS.get(scale)}")
        if src == "topup" and tier != 2:
            rep.err(f"{where}: topup rows belong in design-tier2.tsv")


def edge_key(r):
    """Identity of an edge: unordered for pairs, ordered (candidate, opponent) for one-vs-two, sorted triple for tables."""
    pl = r["_pl"]
    if r["kind"] == "pair":
        return (r["scale"], r["kind"], tuple(sorted(pl)))
    if r["kind"] == "table":
        return (r["scale"], r["kind"], tuple(sorted(pl)))
    return (r["scale"], r["kind"], tuple(pl))


def check_designs(rows1, rows2, cfgs, directives, rep, repo):
    # duplicates and existing-edge sources
    for tier, rows in ((1, rows1), (2, rows2)):
        seen = {}
        for r in rows:
            if "_pl" not in r:
                continue
            if r["source"] == "topup":
                continue
            k = edge_key(r)
            if k in seen:
                rep.err(f"{r['_line']}: edge {r['players']} of {r['scale']} is listed twice (also {seen[k]})")
            seen[k] = r["_line"]
            if r["source"] == "existing" and not (repo / r["of"]).is_file():
                rep.err(f"{r['_line']}: existing edge cites {r['of']}, which does not exist")
    by_id1 = {r["edge_id"]: r for r in rows1 if "_pl" in r}
    new1 = {edge_key(r) for r in rows1 if "_pl" in r and r["source"] == "new"}
    # top-ups
    final, blk = {}, {}
    for r in rows1:
        if "_pl" in r and r["source"] == "new":
            final[edge_key(r)] = int(r["units"])
            blk[edge_key(r)] = r["block"]
    for r in rows2:
        if "_pl" not in r:
            continue
        if r["source"] == "topup":
            base = by_id1.get(r["of"])
            if base is None or base["source"] != "new" or edge_key(base) != edge_key(r) or base["players"] != r["players"]:
                rep.err(f"{r['_line']}: topup names {r['of']!r}, which is not a new Tier 1 edge between the same players in the same order")
                continue
            final[edge_key(r)] = final.get(edge_key(r), 0) + int(r["units"])
        elif r["source"] == "new":
            if edge_key(r) in new1:
                rep.err(f"{r['_line']}: edge {r['players']} exists in Tier 1; use a topup row")
            final[edge_key(r)] = int(r["units"])
            blk[edge_key(r)] = r["block"]
    for k, u in final.items():
        want = TABLE_UNITS if k[1] == "table" else 200 if blk[k] in LOW_UNIT_BLOCKS else FINAL_UNITS[k[1]]
        if u != want:
            rep.err(f"{k[0]} {k[1]} {' | '.join(k[2])}: {u} units after Tier 2, the plan wants {want}")
    # role-swap partners
    for tier, rows in ((1, rows1), (2, rows2)):
        keys = {(r["scale"], r["kind"], tuple(r["_pl"])) for r in rows if "_pl" in r and r["kind"] == "one-vs-two"}
        for r in rows:
            if "_pl" in r and r["kind"] == "one-vs-two" and (r["scale"], "one-vs-two", tuple(reversed(r["_pl"]))) not in keys:
                rep.err(f"{r['_line']}: no role-swap row (the same match with candidate and opponent swapped) in tier {tier}")

    # graphs
    declared = {}
    for d in directives:
        if len(d) != 3:
            rep.err(f"#bridge-allowed needs scale, config, config; got {d}")
            continue
        declared.setdefault(d[0], set()).add(tuple(sorted(d[1:])))
    all_rows = [r for r in rows1 + rows2 if "_pl" in r]
    for s in SCALES:
        reg1 = {c for (sc, c), v in cfgs.items() if sc == s and v["tier"] == "1"}
        reg = {c for (sc, c) in cfgs if sc == s}
        # an edge that names an unregistered config was reported with its row; it stays out of the graph
        g1 = [tuple(r["_pl"]) for r in rows1 if "_pl" in r and r["scale"] == s and r["kind"] != "table" and set(r["_pl"]) <= reg]
        g2 = [tuple(r["_pl"]) for r in all_rows if r["scale"] == s and r["kind"] != "table" and r["source"] != "topup"
              and set(r["_pl"]) <= reg]
        # used configs
        used1 = {c for r in rows1 if "_pl" in r and r["scale"] == s for c in r["_pl"]}
        used = {c for r in all_rows if r["scale"] == s for c in r["_pl"]}
        for c in sorted(reg1 - used1):
            rep.err(f"scale {s}: config {c} is registered for tier 1 but no Tier 1 edge uses it")
        for c in sorted(reg - used):
            rep.err(f"scale {s}: config {c} is registered but no edge uses it")
        for c in sorted(used1 - reg1):
            pass  # tier mismatch is reported per row
        # Tier 1
        if reg1:
            comps = components(reg1, [e for e in g1 if e[0] in reg1 and e[1] in reg1])
            if len(comps) != 1:
                rep.err(f"scale {s}: Tier 1 graph has {len(comps)} components")
            found = set(bridges(reg1, [e for e in g1 if e[0] in reg1 and e[1] in reg1])) if len(comps) == 1 else set()
            allowed = declared.get(s, set())
            for b in sorted(found - allowed):
                rep.err(f"scale {s}: Tier 1 edge {b[0]} - {b[1]} is a bridge and not declared with #bridge-allowed")
            for b in sorted(allowed - found):
                rep.err(f"scale {s}: #bridge-allowed {b[0]} {b[1]} is not a bridge any more; remove the line")
            prio1 = [tuple(r["_pl"]) for r in rows1 if "_pl" in r and r["scale"] == s and r["kind"] != "table"
                     and int(r["priority"]) <= 1 and set(r["_pl"]) <= reg]
            if len(components(reg1, prio1)) != 1:
                rep.err(f"scale {s}: the Tier 1 edges of priority 0 and 1 do not connect the graph")
        elif s in declared:
            rep.err(f"scale {s}: #bridge-allowed for a scale without Tier 1 configs")
        # union
        comps = components(reg, g2)
        if len(comps) != 1:
            rep.err(f"scale {s}: graph of both tiers has {len(comps)} components")
        else:
            for b in bridges(reg, g2):
                rep.err(f"scale {s}: edge {b[0]} - {b[1]} is a bridge in the graph of both tiers")
        nbr = {c: set() for c in reg}
        for a, b in g2:
            nbr[a].add(b)
            nbr[b].add(a)
        for c, n in sorted(nbr.items()):
            if len(n) < 2:
                rep.err(f"scale {s}: config {c} has {len(n)} neighbour(s); every config needs at least two")
        g_cap = g1 + [tuple(r["_pl"]) for r in rows2 if "_pl" in r and r["scale"] == s and r["kind"] != "table"
                      and r["source"] == "new" and int(r["priority"]) <= 1 and set(r["_pl"]) <= reg]
        if len(components(reg, g_cap)) != 1:
            rep.err(f"scale {s}: Tier 1 plus the Tier 2 edges of priority 1 do not connect the graph")
        # tables: players must be vertices of this scale (checked per row); no further graph meaning
    # totals
    check_totals(rows1, rows2, rep)


def games(r):
    return int(r["units"]) * GAMES_PER_UNIT[r["kind"]]


def check_totals(rows1, rows2, rep):
    blocks = {}
    for r in rows1:
        if "_pl" in r and r["source"] == "new":
            e, g = blocks.get(r["block"], (0, 0))
            blocks[r["block"]] = (e + 1, g + games(r))
    for b, (e, g) in PLAN_TIER1.items():
        if blocks.get(b) != (e, g):
            rep.err(f"Tier 1 block {b}: {blocks.get(b)} (edges, games), the plan says {(e, g)}")
    for b in sorted(set(blocks) - set(PLAN_TIER1)):
        rep.err(f"Tier 1 block {b} is not in the plan")
    total = sum(g for _, g in blocks.values())
    if total != PLAN_TIER1_TOTAL_GAMES:
        rep.err(f"Tier 1 total is {total} games, the plan says {PLAN_TIER1_TOTAL_GAMES}")
    n_existing = sum(1 for r in rows1 if r.get("source") == "existing")
    if n_existing != PLAN_TIER1_EXISTING:
        rep.err(f"Tier 1 lists {n_existing} existing edges, expected {PLAN_TIER1_EXISTING}")

    cnt = {}
    for r in rows2:
        if "_pl" not in r or r["kind"] == "table":
            continue
        if r["scale"] in ("2p-adj-r5", "2p-adj-r4"):
            key = r["block"]
            cnt[key] = cnt.get(key, 0) + 1
    r4 = ("r4-topup", "r4-new")
    for b, e in PLAN_TIER2_EDGES.items():
        if cnt.get(b, 0) != e:
            rep.err(f"Tier 2 block {b}: {cnt.get(b, 0)} edges, the plan says {e}")
    if sum(cnt.get(b, 0) for b in r4) != PLAN_R4_TOTAL_EDGES:
        rep.err(f"Tier 2 2p-adj-r4: {sum(cnt.get(b, 0) for b in r4)} edges, the plan says about {PLAN_R4_TOTAL_EDGES}")
    for b in sorted(set(cnt) - set(PLAN_TIER2_EDGES)):
        rep.err(f"Tier 2 block {b} is not in the plan")
    for s in ("3p-adj-r4", "3p-long-r4"):
        tabs = [r for r in rows2 if r.get("scale") == s and r.get("kind") == "table"]
        if len(tabs) != TABLES_PER_3P_SCALE:
            rep.err(f"Tier 2 {s}: {len(tabs)} mixed tables, the plan says {TABLES_PER_3P_SCALE}")
    for r in rows2:
        if "_pl" in r and r["kind"] == "table":
            n_net = sum(1 for c in r["_pl"] if c.startswith("net:"))
            n_ab = sum(1 for c in r["_pl"] if c.startswith("ab:"))
            if (n_net, n_ab) not in ((2, 1), (1, 2)):
                rep.err(f"{r['_line']}: a mixed table holds networks and ab3 players, got {n_net} networks and {n_ab} ab3")


# ------------------------------------------------------------------ driver
def run_checks(repo, configs=None, tier1=None, tier2=None, data_root=None):
    repo = Path(repo)
    elo = repo / "scripts/elo"
    rep = Report()
    cfg_rows, _ = read_tsv(configs or elo / "configs.tsv", CFG_COLS, rep)
    rows1, directives = read_tsv(tier1 or elo / "design-tier1.tsv", DESIGN_COLS, rep)
    rows2, d2 = read_tsv(tier2 or elo / "design-tier2.tsv", DESIGN_COLS, rep)
    if d2:
        rep.err("design-tier2.tsv: '#bridge-allowed' is allowed in design-tier1.tsv only")
    cfgs = check_configs(repo, cfg_rows, rep, Path(data_root) if data_root else None)
    check_design_rows(rows1, 1, cfgs, rep)
    check_design_rows(rows2, 2, cfgs, rep)
    check_designs(rows1, rows2, cfgs, directives, rep, repo)
    for s in SCALES:
        n1 = sum(1 for r in rows1 if r["scale"] == s and r["source"] in ("new", "existing"))
        n2 = sum(1 for r in rows2 if r["scale"] == s and r["source"] == "new" and r["kind"] != "table")
        rep.note(f"{s}: {sum(1 for (sc, _) in cfgs if sc == s)} configs, Tier 1 {n1} edges, Tier 2 {n2} new edges")
    rep.note(f"Tier 1: {len(rows1)} rows, Tier 2: {len(rows2)} rows")
    return rep


def main(argv=None):
    ap = argparse.ArgumentParser(description="check the Elo study's config registry and designs")
    ap.add_argument("--repo", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--data-root", default=None, help="also compare the network files under DIR/data/... with the registry")
    args = ap.parse_args(argv)
    rep = run_checks(args.repo, data_root=args.data_root)
    for n in rep.info:
        print(n)
    for e in rep.errors:
        print("ERROR:", e, file=sys.stderr)
    print(f"{len(rep.errors)} error(s)")
    return 1 if rep.errors else 0


if __name__ == "__main__":
    sys.exit(main())
