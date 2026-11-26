#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Turn the existing match results into the common edge format of scripts/elo/FORMAT.md.

usage: ingest.py [--root DIR] [--data DIR] [--out DIR] [--require-files] [SUMMARY_FILE ...]

Input: the summary lines of head-to-head and ladder matches (models/runs/**/*.txt, the lines that
docs/training.md cites). Without file arguments every registered source is read. Per-pair result files
(data/**/pairs-<hash>.jsonl, git-ignored, only on the machine that ran the match) are copied next to the
edge when they exist and agree with the summary line.

Exit codes: 0 done, 1 inconsistent input, 2 refused input or wrong usage, 75 another run holds the lock.
"""
import argparse
import fcntl
import hashlib
import json
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# --- Registry of the existing sources ---------------------------------------------------------------------------

R5_SPEC = ("ab:eval=linear-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,"
           "rows=5")  # Strategies.BROWSER_ROWS5_SPEC, as driver.zsh passes it (variable R5)
LADDER_NAME = "browser-r5"

# net id -> (path of the file on the training machine, 16 hex digits of its SHA-256 as models/index.md lists them)
NETS = {
    "r5-small-gen0": ("data/net5/gen0-small/gen-0000/candidate.safetensors", "6381176933b830da"),
    "r5-small-gen9": ("data/net5/loop-small/gen-0009/candidate.safetensors", "c236ae0eac7a6a51"),
    "r5-large-gen0": ("data/net5/gen0-large/gen-0000/candidate.safetensors", "134b02f0c4f90deb"),
    "r5-large-gen10": ("data/net5/loop-large/gen-0010/candidate.safetensors", "4c5dfc9a5336fb06"),
}
NET_BY_PATH = {path: nid for nid, (path, _) in NETS.items()}

LEVELS = {1000: 1, 5000: 2, 20000: 3, 100000: 4, 500000: 5}


@dataclass(frozen=True)
class Source:
    path: str          # repository-relative path of the summary file
    kind: str          # "mcts" (MctsMatch summary lines) or "arena" (Arena output)
    scale: str
    seed: int          # opening seed of the run (driver.zsh, docs/training.md)
    subject_net: str   # net id of --net (the subject's network; also the opponent's if its spec has no net=)
    threads: int
    pairs_dir: str = ""  # directory of the pairs file under data/; the hash alone does not name the network


def _s(path, seed, net, pairs_dir="", kind="mcts"):
    return Source(path, kind, "2p-adj-r5", seed, net, 14, pairs_dir)


# Seeds: driver.zsh (E1 3001, E2 3002, H2H 3003) and docs/training.md ("Five-row expert net", E3 "--seed 3001";
# "Five-row LARGE net", equal-time "seed 3003"); the ladder check ran with --seed 4101 (driver.zsh line 87).
SOURCES = [
    _s("models/runs/net5/e1.txt", 3001, "r5-small-gen0", "net5/eval/e1"),
    _s("models/runs/net5/e2.txt", 3002, "r5-small-gen9", "net5/eval/e2"),
    _s("models/runs/net5/e3.txt", 3001, "r5-small-gen9", "net5/eval/e3"),  # no pair file: E3 ran outside the driver
    _s("models/runs/net5/ladder-check.txt", 4101, "", "", kind="arena"),
    _s("models/runs/net5-large/e1.txt", 3001, "r5-large-gen0", "net5/eval/e1-large"),
    _s("models/runs/net5-large/e2.txt", 3002, "r5-large-gen10", "net5/eval/e2-large"),
    _s("models/runs/net5-large/h2h.txt", 3003, "r5-large-gen10", "net5/eval/h2h-large"),
    _s("models/runs/net5-large/equal-time.txt", 3003, "r5-large-gen10", "net5/eval/equal-time"),  # directory unknown
    _s("models/runs/net5-large/e3.txt", 3001, "r5-large-gen10", "net5/eval/e3-large"),
]


class Refused(Exception):
    """Input that must not enter the fit (gate file, non-rated configuration, unknown source). Exit 2."""


class Inconsistent(Exception):
    """Input that contradicts itself or its result file. Exit 1."""


class LockHeld(Exception):
    pass


@dataclass
class Result:
    ok: int = 0
    skip: int = 0
    error: int = 0
    cleanup: int = 0
    edges: list = field(default_factory=list)


# --- Refusal of gate files --------------------------------------------------------------------------------------

GATE_TOKENS = {"gate", "decision", "decisions", "metrics"}


def check_not_gate(rel):
    """Raise Refused if the path belongs to a training-loop gate or a loop generation.

    Origin: docs/training.md, loop run 4, "Findings": five promotions summed to about +140 Elo by their gate
    scores, the direct match gave +78 +- 19. A path is judged by its components, split at every character that
    is not a letter or a digit, so "aggregate.txt" is not a gate and "gate-pairs/x" is.
    """
    for part in Path(rel).parts:
        if re.fullmatch(r"gen-\d{4}", part):
            raise Refused(f"{rel}: a loop generation directory ({part}); gate files are not ingested")
        if GATE_TOKENS & set(re.split(r"[^a-z0-9]+", part.lower())):
            raise Refused(f"{rel}: a gate, decision or metrics file ({part}); gate files are not ingested")


# --- Parsing ----------------------------------------------------------------------------------------------------

# matches: mcts:sims=200,batch=1,cpuct=1.0,noise=0,temp=0,race=0 vs ab:1600: Elo +402 +- 60 (95%), score 0.910 +- 0.014, 364-0-36 games, 200 pairs, 131 plies; ...
LINE = re.compile(r"""^ (?P<subject>\S+) \s vs \s (?P<opponent>\S+) : \s Elo \s [+-]\d+ \s \+- \s \d+ \s \(95%\),
    \s score \s (?P<score>[0-9.]+) \s \+- \s [0-9.]+ ,
    \s (?P<w>\d+)-(?P<d>\d+)-(?P<l>\d+) \s games , \s (?P<pairs>\d+) \s pairs ,""", re.X)

SCORE_TOLERANCE = 0.0006  # the printed score has three decimals


@dataclass(frozen=True)
class Summary:
    line_no: int
    subject: str
    opponent: str
    pairs: int
    wins: int
    draws: int
    losses: int


def parse_summary_lines(text, where):
    found = []
    for no, raw in enumerate(text.splitlines(), 1):
        m = LINE.match(raw)
        if not m:
            continue
        w, d, l, pairs = (int(m[k]) for k in ("w", "d", "l", "pairs"))
        if w + d + l != 2 * pairs:
            raise Inconsistent(f"{where}:{no}: {w}-{d}-{l} games do not add up to 2 x {pairs} pairs")
        expected = (w + d / 2) / (2 * pairs)
        if abs(expected - float(m["score"])) > SCORE_TOLERANCE:
            raise Inconsistent(f"{where}:{no}: printed score {m['score']} but the counts {w}-{d}-{l} give "
                               f"{expected:.4f}")
        found.append(Summary(no, m["subject"], m["opponent"], pairs, w, d, l))
    if not found:
        raise Inconsistent(f"{where}: no summary line recognised; a filter that sees nothing is an error, not an "
                           f"empty result")
    return found


@dataclass(frozen=True)
class ArenaResult:
    nodes_a: int
    nodes_b: int
    pairs: int
    wins: int
    draws: int
    losses: int


def parse_arena(text):
    # matches: A=ab:eval=...,rows=5,nodes=6400
    #          pairs=100 games=200  A wins=162 draws=0 B wins=38  avg plies=127.9
    specs = {}
    for side in "AB":
        m = re.search(rf"^{side}=(?P<spec>\S+),nodes=(?P<n>\d+)\s*$", text, re.M)
        if not m:
            raise Inconsistent(f"arena output has no line {side}=<spec>,nodes=N")
        specs[side] = (m["spec"], int(m["n"]))
    for side, (spec, _) in specs.items():
        if spec != R5_SPEC:
            raise Refused(f"arena side {side} plays an unknown spec: {spec}")
    m = re.search(r"^pairs=(\d+) games=(\d+)\s+A wins=(\d+) draws=(\d+) B wins=(\d+)", text, re.M)
    if not m:
        raise Inconsistent("arena output has no 'pairs=N games=N A wins=N draws=N B wins=N' line")
    pairs, games, w, d, l = (int(x) for x in m.groups())
    if games != 2 * pairs or w + d + l != games:
        raise Inconsistent(f"arena counts disagree: pairs={pairs} games={games} {w}-{d}-{l}")
    return ArenaResult(specs["A"][1], specs["B"][1], pairs, w, d, l)


def parse_mcts_spec(text, where):
    """mcts:sims=200,batch=1,...[,net=PATH] -> (sims, net path or None). Refuse what is not the rated config."""
    if not text.startswith("mcts:"):
        raise Refused(f"{where}: not an mcts spec: {text}")
    params = dict(p.split("=", 1) for p in text[len("mcts:"):].split(","))
    if params.get("batch") != "1":
        raise Refused(f"{where}: batch={params.get('batch')}; the rated configuration is batch=1 (owner decision)")
    return int(params["sims"]), params.get("net")


def strip_net(text):
    return ",".join(p for p in text.split(",") if not p.startswith("net="))


# --- Config ids -------------------------------------------------------------------------------------------------

def ab_id(nodes):
    return f"ab:{LADDER_NAME}@n{nodes}"


def net_id(net, sims):
    return f"net:{net}@s{sims}"


def ab_spec(nodes):
    return f"{R5_SPEC},nodes={nodes}"


def basis_tags(net, sims):
    tags = []
    if sims == 200:
        tags.append("sims200")
    if (sims == 1000 and "small" in net) or (sims == 200 and "large" in net):
        tags.append("time")  # equal time, plan 3.2: SMALL at 1,000 and LARGE at 200 simulations
    return ",".join(tags)


# --- Pair files -------------------------------------------------------------------------------------------------

def pair_hash(subject, opponent, seed, opening_plies, ladder_spec, own, rows):
    """The name part of pairs-<hash>.jsonl, as MctsMatch.java builds it (lines 262 to 267), cpu evaluator, adjacent."""
    key = (f"{subject}|{opponent}|{seed}|{opening_plies}|{ladder_spec}|{'own' if own else ''}"
           + ("" if rows == 4 else f"|rows{rows}"))
    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:12]


def find_pair_file(data, pairs_dir, h):
    """data/<pairs_dir>/pairs-<h>.jsonl if it exists. The hash does not contain the network (--net is not part of the
    key in MctsMatch.java), so the directory names the run; a search by hash alone finds files of other networks
    (measured: data/net5/eval/e1, e1-large and e3-large all hold pairs-ca35ba7de6ae.jsonl)."""
    if data is None or not pairs_dir:
        return None
    check_not_gate(pairs_dir)
    p = Path(data) / pairs_dir / f"pairs-{h}.jsonl"
    return p if p.is_file() else None


def check_pair_file(path, summary_pairs, totals, where):
    seen = {}
    w = d = l = 0
    for no, raw in enumerate(Path(path).read_text().splitlines(), 1):
        if not raw.strip():
            continue
        try:
            rec = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise Inconsistent(f"{path}:{no}: not JSON ({exc})")
        idx = rec["pair"]
        if idx in seen:
            raise Inconsistent(f"{path}:{no}: pair {idx} appears twice")
        seen[idx] = rec
        w += rec["wins"]
        d += rec["draws"]
        l += rec["losses"]
    if sorted(seen) != list(range(summary_pairs)):
        raise Inconsistent(f"{where}: {path} holds {len(seen)} pairs, the summary line says {summary_pairs} "
                           f"(indices 0 to {summary_pairs - 1} must each appear once)")
    if (w, d, l) != (totals["wins"], totals["draws"], totals["losses"]):
        raise Inconsistent(f"{where}: {path} sums to {w}-{d}-{l}, the summary line says "
                           f"{totals['wins']}-{totals['draws']}-{totals['losses']}")
    return len(seen)


# --- Building edges ---------------------------------------------------------------------------------------------

def safe(text):
    return re.sub(r"[^A-Za-z0-9._-]", "-", text)


def net_entry(net):
    path, sha = NETS[net]
    return {"path": path, "sha256Prefix": sha}


def build_edges(root, src, data):
    """Yield (edge dict without file fields, pair-file source path or None, configs dict) per summary line."""
    where = src.path
    text = (Path(root) / src.path).read_text()
    out = []
    if src.kind == "arena":
        a = parse_arena(text)
        line_no = next(i for i, ln in enumerate(text.splitlines(), 1) if ln.startswith("pairs="))
        players = [ab_id(a.nodes_a), ab_id(a.nodes_b)]
        specs = {p: ab_spec(n) for p, n in zip(players, (a.nodes_a, a.nodes_b))}
        configs = {p: cfg_row_ab(src.scale, n) for p, n in zip(players, (a.nodes_a, a.nodes_b))}
        totals = {"wins": a.wins, "draws": a.draws, "losses": a.losses}
        # Arena writes no per-pair file and has no opening index (plan 1.3).
        out.append((line_no, players, specs, {}, configs, totals, a.pairs, None))
        return out
    if src.kind != "mcts":
        raise Refused(f"{where}: unknown source kind {src.kind}")
    for s in parse_summary_lines(text, where):
        at = f"{where}:{s.line_no}"
        subj_sims, subj_net_path = parse_mcts_spec(s.subject, at)
        if subj_net_path is not None:
            raise Refused(f"{at}: the subject spec carries net=; the registry expects --net")
        subject = net_id(src.subject_net, subj_sims)
        players, specs, nets, configs = [subject], {subject: s.subject}, {subject: net_entry(src.subject_net)}, {}
        configs[subject] = cfg_row_net(src.scale, src.subject_net, subj_sims, strip_net(s.subject))
        if s.opponent.startswith("ab:"):
            nodes = int(s.opponent[3:])
            opp = ab_id(nodes)
            specs[opp] = ab_spec(nodes)
            configs[opp] = cfg_row_ab(src.scale, nodes)
        else:
            sims, path = parse_mcts_spec(s.opponent, at)
            onet = src.subject_net if path is None else NET_BY_PATH.get(path)
            if onet is None:
                raise Refused(f"{at}: opponent network {path} is not in the registry")
            opp = net_id(onet, sims)
            specs[opp] = s.opponent
            nets[opp] = net_entry(onet)
            configs[opp] = cfg_row_net(src.scale, onet, sims, strip_net(s.opponent))
        players.append(opp)
        totals = {"wins": s.wins, "draws": s.draws, "losses": s.losses}
        h = pair_hash(s.subject, s.opponent, src.seed, 4, R5_SPEC, True, 5)
        out.append((s.line_no, players, specs, nets, configs, totals, s.pairs, h))
    return out


def cfg_row_ab(scale, nodes):
    return {"id": ab_id(nodes), "scale": scale, "engine": "ab", "spec": ab_spec(nodes), "net": "", "sha256Prefix": "",
            "budget": str(nodes), "basis": "", "level": str(LEVELS.get(nodes, "")), "bridge": ""}


def cfg_row_net(scale, net, sims, spec):
    return {"id": net_id(net, sims), "scale": scale, "engine": "net", "spec": spec, "net": net,
            "sha256Prefix": NETS[net][1], "budget": str(sims), "basis": basis_tags(net, sims), "level": "",
            "bridge": ""}


CONFIG_COLUMNS = ["id", "scale", "engine", "spec", "net", "sha256Prefix", "budget", "basis", "level", "bridge"]


def atomic_write(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def run(root, sources, data, out, require_files=False):
    root, out = Path(root), Path(out)
    for src in sources:  # refuse before anything is read or written
        check_not_gate(src.path)
    out.mkdir(parents=True, exist_ok=True)
    lock = open(out / ".ingest.lock", "a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise LockHeld(f"ingest is running already (lock {out / '.ingest.lock'})")
    res = Result()
    try:
        for stale in out.glob("**/*.tmp.*"):  # CLEANUP: leftovers of an aborted run
            stale.unlink()
            res.cleanup += 1
        edge_lines, configs, ids = [], {}, set()
        copies = []  # (destination relative path, source path)
        for src in sources:
            for line_no, players, specs, nets, cfgs, totals, pairs, h in build_edges(root, src, data):
                where = f"{src.path}:{line_no}"
                eid = f"{safe(players[0])}__{safe(players[1])}__s{src.seed}"
                if eid in ids:
                    raise Inconsistent(f"{where}: edge id {eid} exists twice")
                ids.add(eid)
                for cid, row in cfgs.items():
                    if configs.setdefault(cid, row) != row:
                        raise Inconsistent(f"{where}: config {cid} is described two different ways")
                file_rel, present = None, 0
                if h is not None:
                    found = find_pair_file(data, src.pairs_dir, h)
                    if found is not None:
                        present = check_pair_file(found, pairs, totals, where)
                        file_rel = f"{src.scale}/{eid}/pairs-{h}.jsonl"
                        copies.append((file_rel, found))
                    elif require_files:
                        raise Inconsistent(f"{where}: no pairs-{h}.jsonl found under {data}")
                edge_lines.append({
                    "id": eid, "scale": src.scale, "kind": "pair", "players": players, "specs": specs,
                    "nets": nets, "seed": src.seed, "openingPlies": 4, "unitsPlanned": pairs, "totals": totals,
                    "file": file_rel, "unitsPresent": present, "commit": None, "threads": src.threads,
                    "source": {"kind": "existing", "origin": where}})
        edges_bytes = "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in edge_lines).encode()
        rows = ["\t".join(CONFIG_COLUMNS)] + ["\t".join(configs[c][k] for k in CONFIG_COLUMNS) for c in sorted(configs)]
        configs_bytes = ("\n".join(rows) + "\n").encode()
        old_edges = (out / "edges.jsonl").read_bytes() if (out / "edges.jsonl").exists() else None
        old_by_id = {}
        if old_edges:
            for ln in old_edges.decode().splitlines():
                old_by_id[json.loads(ln)["id"]] = ln
        for e in edge_lines:
            same = old_by_id.get(e["id"]) == json.dumps(e, ensure_ascii=False)
            res.skip += same
            res.ok += not same
        for rel, srcfile in copies:
            dest = out / rel
            content = srcfile.read_bytes()
            if not (dest.exists() and dest.read_bytes() == content):
                atomic_write(dest, content)
        if old_edges != edges_bytes:
            atomic_write(out / "edges.jsonl", edges_bytes)
        if not (out / "configs.tsv").exists() or (out / "configs.tsv").read_bytes() != configs_bytes:
            atomic_write(out / "configs.tsv", configs_bytes)
        res.edges = edge_lines
        return res
    finally:
        lock.close()


# --- Command line -----------------------------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--data", default=None, help="directory with the per-pair files (default: <root>/data if it exists)")
    ap.add_argument("--out", default=None, help="output directory (default: <root>/data/elo/tier0)")
    ap.add_argument("--require-files", action="store_true", help="fail if a pair file is missing")
    ap.add_argument("files", nargs="*", help="summary files (default: every registered source)")
    a = ap.parse_args(argv)
    root = Path(a.root).resolve()
    data = Path(a.data) if a.data else (root / "data" if (root / "data").is_dir() else None)
    out = Path(a.out) if a.out else root / "data" / "elo" / "tier0"
    try:
        if a.files:
            registry = {s.path: s for s in SOURCES}
            chosen = []
            rels = []
            for f in a.files:
                p = Path(f)
                rels.append(str(p.resolve().relative_to(root)) if p.is_absolute() else str(p))
            for rel in rels:
                check_not_gate(rel)  # every refusal first, then the registry
            for rel in rels:
                if rel not in registry:
                    raise Refused(f"{rel}: not a registered source; the subject network and seed of a summary "
                                  f"file are not in the file, add it to SOURCES in ingest.py")
                chosen.append(registry[rel])
        else:
            chosen = SOURCES
        res = run(root, chosen, data, out, a.require_files)
    except Refused as exc:
        print(f"ingest: refused: {exc}", file=sys.stderr)
        return 2
    except Inconsistent as exc:
        print(f"ingest: error: {exc}", file=sys.stderr)
        return 1
    except LockHeld as exc:
        print(f"ingest: {exc}", file=sys.stderr)
        return 75
    with_files = sum(1 for e in res.edges if e["file"])
    print(f"ingest: {res.ok} ok, {res.skip} skip, {res.error} error, {res.cleanup} cleanup; "
          f"{len(res.edges)} edges, {with_files} with a pairs file, {len(res.edges) - with_files} from the summary "
          f"line only -> {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
