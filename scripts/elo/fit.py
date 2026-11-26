#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Elo study fit: one rating scale per game type from paired match results (plan sections 2.2 to 2.5, 5.2 to 5.4).

usage: fit.py [--configs configs.tsv] [--anchor SCALE=CONFIG ...] [--pairs pairs.tsv] [--out DIR] [--work DIR]
              [--replicates N] [--gof-replicates N] [--seed S] [--prior-games G] [--allow-partial]
              [--date YYYY-MM-DD] [--commit SHA] [--status] EDGES.jsonl [EDGES.jsonl ...]

Writes DIR/ratings.json and DIR/fit-report.md (default DIR: models/ratings of this repository).

INPUT FORMAT (the terms of plan section 5.1)
--------------------------------------------
The common edge format of scripts/elo/FORMAT.md (task T5, branch feat/elo-t5-ingest when this was written) is read as
it stands there; what follows is the part this fit uses, plus the three-player lines.

EDGES.jsonl: one JSON object per line, one line per edge (a match between configs). Several files may be given (for
example the ingested existing data and a tier's new data); edge ids must be unique over all of them. Fields:

  id            edge id, unique
  scale         "<players>p-<jump>-r<rows>", for example "2p-adj-r5"; the player count must be 2 or 3
  kind          "pair"        (2 players, MctsMatch): players = [subject, opponent]
                "one-vs-two"  (3 players, ThreeSeatMatch): players = [candidate, opponent]; the opponent sits in both
                              other seats
                "table"       (3 players, ThreeSeatMatch --table, task T2): players = [A, B, C]
  players       config ids; all different (a config against itself carries no rating information and is refused)
  file          result file; a relative path is taken relative to the directory of this EDGES.jsonl. null: the edge has
                only totals (two players only); each of its games becomes its own bootstrap unit and the report notes it
  unitsPlanned  planned number of units (pairs or openings); units with an index >= unitsPlanned are ignored, fewer
                units than planned is an error unless --allow-partial
  totals        {"wins", "draws", "losses"} of the first player (optional with a file, required without). With a file
                the sums over its lines must equal it, else an error
  unitsPresent  optional; must equal the number of units in the file
  seed          match seed (optional). Edges of one scale with the same (seed, openingPlies) played the same
                openings and are resampled with the same opening indices in the bootstrap; an edge without a seed is
                its own cluster group.
  openingPlies  random opening plies (optional, part of the cluster key)
  source        "new" or "existing", or {"kind": "new" | "existing", ...} (optional, default "new"); used by the
                sensitivity fit "new data only" and by the old-against-new comparison
  Other fields of 5.1 (specs, nets, commit, threads) are carried along and not read.

Result files, one JSON line per unit. A torn (unparsable) last line is ignored, as the match tools do on resume; any
other unparsable line, a repeated unit index or a value out of range is an error that names file and line.

  kind "pair": the MctsMatch line, one per opening pair (two games, colours swapped):
      {"pair": 0, "score": 0.5, "wins": 1, "draws": 0, "losses": 1, "plies": 239, "s_cpu_ns": ..., "s_moves": ...,
       "o_cpu_ns": ..., "o_moves": ..., ...}
    score = the subject's mean score over the pair in [0, 1] (fractional under SCORE_BY_DISTANCE). games = wins +
    draws + losses (2 if absent). The subject scores games*score, the opponent games*(1-score).
  kind "one-vs-two": the ThreeSeatMatch line, one per opening (three games, candidate in seat 0, 1, 2):
      {"opening": 0, "open_hash": "...", "r0": "W", "r1": "L", "r2": "D", "plies0": ..., "c_cpu_ns": ...,
       "c_moves": ...}
    rK = the candidate's result with the candidate in seat K: W (candidate won), L (an opponent won), D (no winner).
  kind "table": the TableMatch line of task T2 (ThreeSeatMatch --table), one per opening, six seatings k = 0..5:
      {"opening": 0, "open_hash": "...", "perm0": "012", "w0": 1, "wp0": 1, "plies0": 140, "ml0": false, ...,
       "perm5": "210", "w5": -1, "wp5": -1, ..., "cpu_ns_p0": ..., "moves_p0": ..., ...}
    permK[seat] = index into the edge's players of the player in that seat; wK = seat of the winner or -1 (no
    winner); wpK (optional) = the winner's player index, checked against permK and wK.
    TableMatch counts a game without a winner for no player; this fit counts it 1/3 for each (plan 2.2).

configs.tsv (optional, tab separated; leading '#' lines are comments; the first other line = column names; unknown
columns are ignored; '-' means empty):
  id or config_id (required), scale, level (app level 1 to 5 of a classical config), anchor (1 marks the anchor of
  its scale; without this column the level-3 config is the anchor), nodes, sims, net or net_id, engine and budget
  (budget = nodes for engine ab, sims for engine net), basis (comma separated tags such as sims200,time), bridge
  (bridge id shared by the same engine setting in several scales).
  Without configs.tsv every scale needs --anchor SCALE=CONFIG, net and sims are parsed from ids of the form
  net:<net>@s<sims>, and levels, bridge test and level-1 sensitivity are skipped.

pairs.tsv (optional): lines "a<TAB>b" of config ids of the same scale whose pairwise difference goes into
ratings.json "pairs". Every network config (id "net:...") is also paired with the app level whose rating is closest.

MODEL
-----
Per scale, config x has R(x) = 1000 + (400/ln 10) * (ln g_x - ln g_anchor). A game contributes, for every group of
seats that can be named as the winner, (its weight) * ln(sum of theta over the group) - (game weight) * ln(sum of theta
over all seats), theta = g_player * s_seat. Two players: groups = the two players, no seat factor (both colours are
played), score s counts as s wins and 1-s losses (Bradley-Terry). Three players: Luce first choice with seat factors s1,
s2 (s0 = 1) per scale; one-vs-two records only whether the candidate won, so the opponents' two seats form one group; a
game without a winner counts 1/3 for each player. Prior: one virtual two-player game per played pair of configs, split
evenly (--prior-games). Maximised by damped Newton steps (Levenberg-Marquardt) on the log parameters to 0.01 Elo.
Uncertainty: cluster bootstrap over opening indices (2.3), 95 % percentile intervals.

LONG RUNS
---------
The bootstrap replicates are cached per scale under --work (default: fit-work beside the first EDGES.jsonl), keyed by
a hash of the inputs, so an interrupted run continues where it stopped. A lock (--work/.fit.lock) makes a second start
exit with 75; --status reports whether a fit runs and since when. SIGINT and SIGTERM stop between replicates, write no
output and exit 130 or 143. Progress lines on stderr: n/N, rate of this run, ETA, elapsed.
Exit codes: 0 written, 1 fit error (missing or broken data, disconnected graph), 2 wrong call, 75 already running.
"""
import argparse
import bisect
import hashlib
import json
import math
import os
import random
import re
import signal
import subprocess
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

ELO = 400.0 / math.log(10.0)          # Elo per natural-log unit of gamma
TOL = 0.01 / ELO                      # 0.01 Elo
ANCHOR_ELO = 1000.0
ANCHOR_RULE = "classical browser engine at 20000 nodes = 1000, per scale"
SATURATED = 0.9
REPO = Path(__file__).resolve().parents[2]


class FitError(Exception):
    """Broken input or a scale that cannot be fitted; the message names the edge, file or line."""


# ----------------------------------------------------------------------------------------------------------------- input

def players_of_scale(scale):
    m = re.match(r"^(?P<p>\d)p-(?P<jump>[a-z]+)-r(?P<rows>\d+)$", scale)    # matches: 2p-adj-r5, 3p-long-r4
    if not m or m["p"] not in ("2", "3"):
        raise FitError(f"scale {scale!r}: expected <2|3>p-<jump>-r<rows>")
    return int(m["p"])


def rows_of_scale(scale):
    return int(re.match(r"^\dp-[a-z]+-r(?P<rows>\d+)$", scale)["rows"])


def load_configs(path):
    """configs.tsv -> {id: row}. Leading '#' lines are comments; '-' means empty. Column aliases (scripts/elo/FORMAT.md
    and the registry of task T3): config_id = id, net_id = net, budget = nodes (engine ab) or sims (engine net)."""
    if path is None:
        return {}
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    start = 0
    while start < len(lines) and (lines[start].startswith("#") or not lines[start].strip()):
        start += 1
    if start == len(lines):
        raise FitError(f"{path}: no header line")
    head = [h.strip() for h in lines[start].split("\t")]
    head = ["id" if h == "config_id" else h for h in head]
    if "id" not in head:
        raise FitError(f"{path}:{start + 1}: no column 'id' or 'config_id'")
    out = {}
    for n, line in enumerate(lines[start + 1:], start + 2):
        if not line.strip() or line.startswith("#"):
            continue
        row = {k: ("" if v.strip() == "-" else v.strip()) for k, v in zip(head, line.split("\t"))}
        cid = row.get("id", "")
        if not cid:
            raise FitError(f"{path}:{n}: empty id")
        if cid in out:
            raise FitError(f"{path}:{n}: config {cid} listed twice")
        if not row.get("net") and row.get("net_id"):
            row["net"] = row["net_id"]
        if row.get("budget"):
            key = {"ab": "nodes", "net": "sims"}.get(row.get("engine", ""))
            if key and not row.get(key):
                row[key] = row["budget"]
        out[cid] = row
    return out


class Edge:
    def __init__(self, d, base, where):
        for k in ("id", "scale", "kind", "players", "file", "unitsPlanned"):
            if k not in d:
                raise FitError(f"{where}: field {k!r} missing")
        self.id = str(d["id"])
        self.scale = d["scale"]
        self.kind = d["kind"]
        self.players = list(d["players"])
        if d["file"] is None:
            self.file = None
        else:
            p = Path(d["file"])
            self.file = p if p.is_absolute() else base / p
        self.planned = int(d["unitsPlanned"])
        self.totals = d.get("totals")
        self.units_present = d.get("unitsPresent")
        self.where = where
        if self.file is None and not isinstance(self.totals, dict):
            raise FitError(f"{where}: edge {self.id}: file is null and no totals")
        self.seed = d.get("seed")
        self.opening_plies = d.get("openingPlies")
        self.source = d.get("source", "new")
        if isinstance(self.source, dict):
            self.source = self.source.get("kind", "existing")
        self.units = {}            # unit index -> parsed unit
        n = players_of_scale(self.scale)
        want = {"pair": (2, 2), "one-vs-two": (3, 2), "table": (3, 3)}
        if self.kind not in want:
            raise FitError(f"{where}: edge {self.id}: kind {self.kind!r} unknown")
        if want[self.kind][0] != n:
            raise FitError(f"{where}: edge {self.id}: kind {self.kind} does not fit scale {self.scale}")
        if len(self.players) != want[self.kind][1]:
            raise FitError(f"{where}: edge {self.id}: {self.kind} needs {want[self.kind][1]} players")
        if len(set(self.players)) != len(self.players):
            raise FitError(f"{where}: edge {self.id}: a config plays itself ({self.players}); such games are never "
                           "evidence (plan 2.6)")

    @property
    def cluster(self):
        if self.seed is None or self.file is None:
            return ("edge", self.id)
        return ("seed", str(self.seed), str(self.opening_plies))


def _num(rec, key, where, lo=None, hi=None, default=None):
    if key not in rec:
        if default is not None:
            return default
        raise FitError(f"{where}: field {key!r} missing")
    try:
        v = float(rec[key])
    except (TypeError, ValueError):
        raise FitError(f"{where}: field {key!r} = {rec[key]!r} is not a number") from None
    if (lo is not None and v < lo) or (hi is not None and v > hi) or math.isnan(v):
        raise FitError(f"{where}: field {key!r} = {v} out of range")
    return v


def parse_unit(edge, rec, where):
    """One result line -> (index, unit): unit = {"cells": [(cell key, weights)], "games", "draws", "cpu": {...}}."""
    cpu = defaultdict(lambda: [0, 0])          # player index -> [cpu ns, moves]
    if edge.kind == "pair":
        idx = int(_num(rec, "pair", where, lo=0))
        score = _num(rec, "score", where, 0.0, 1.0)
        wdl = tuple(int(_num(rec, k, where, lo=0, default=0)) for k in ("wins", "draws", "losses"))
        games = sum(wdl) or 2
        draws = wdl[1]
        cells = [("pair", (games * score, games * (1.0 - score)))]
        for p, pre in ((0, "s"), (1, "o")):
            if f"{pre}_cpu_ns" in rec and f"{pre}_moves" in rec:
                cpu[p] = [float(rec[f"{pre}_cpu_ns"]), float(rec[f"{pre}_moves"])]
    elif edge.kind == "one-vs-two":
        idx = int(_num(rec, "opening", where, lo=0))
        cells, games, draws = [], 0, 0
        for seat in range(3):
            r = rec.get(f"r{seat}")
            w = {"W": (1.0, 0.0), "L": (0.0, 1.0), "D": (1 / 3, 2 / 3)}.get(r)
            if w is None:
                raise FitError(f"{where}: r{seat} = {r!r}, expected W, L or D")
            cells.append((("seat", seat), w))
            games += 1
            draws += r == "D"
        wdl = tuple(sum(rec[f"r{s}"] == x for s in range(3)) for x in "WDL")
        if "c_cpu_ns" in rec and "c_moves" in rec:
            cpu[0] = [float(rec["c_cpu_ns"]), float(rec["c_moves"])]
    else:
        idx = int(_num(rec, "opening", where, lo=0))
        cells, games, draws = [], 0, 0
        wins = 0
        k = 0
        while f"perm{k}" in rec:
            perm = [int(ch) for ch in str(rec[f"perm{k}"]) if ch.isdigit()]
            if sorted(perm) != [0, 1, 2] or len(str(rec[f"perm{k}"])) != 3:
                raise FitError(f"{where}: perm{k} = {rec[f'perm{k}']!r} is not a permutation of 012")
            win = int(_num(rec, f"w{k}", where, -1, 2))
            if f"wp{k}" in rec and int(rec[f"wp{k}"]) != (-1 if win < 0 else perm[win]):
                raise FitError(f"{where}: wp{k} = {rec[f'wp{k}']} disagrees with perm{k} and w{k}")
            w = (1 / 3, 1 / 3, 1 / 3) if win < 0 else tuple(1.0 if s == win else 0.0 for s in range(3))
            cells.append((("perm", tuple(perm)), w))
            games += 1
            draws += win < 0
            wins += win >= 0 and perm[win] == 0
            k += 1
        if not games:
            raise FitError(f"{where}: no seating (perm0, w0, ...)")
        wdl = (wins, draws, games - wins - draws)
        for p in range(3):
            if f"cpu_ns_p{p}" in rec and f"moves_p{p}" in rec:
                cpu[p] = [float(rec[f"cpu_ns_p{p}"]), float(rec[f"moves_p{p}"])]
    return idx, {"cells": cells, "games": games, "draws": draws, "cpu": dict(cpu), "wdl": wdl}


def totals_only_units(edge):
    """An edge without a result file (FORMAT.md rule 2): each game becomes its own unit, so the bootstrap treats the
    games as independent; the report names such edges."""
    if edge.kind != "pair":
        raise FitError(f"{edge.where}: edge {edge.id}: a three-player edge needs its per-opening file")
    t = edge.totals
    try:
        wins, draws, losses = (int(t[k]) for k in ("wins", "draws", "losses"))
    except (KeyError, TypeError, ValueError):
        raise FitError(f"{edge.where}: edge {edge.id}: totals need integer wins, draws, losses") from None
    if wins + draws + losses != 2 * edge.planned:
        raise FitError(f"{edge.where}: edge {edge.id}: totals {wins}+{draws}+{losses} games, expected "
                       f"2 * unitsPlanned = {2 * edge.planned}")
    scores = [1.0] * wins + [0.5] * draws + [0.0] * losses
    edge.units = {i: {"cells": [("pair", (s, 1.0 - s))], "games": 1, "draws": int(s == 0.5), "cpu": {}}
                  for i, s in enumerate(scores)}


def load_results(edge, allow_partial, notes):
    if edge.file is None:
        totals_only_units(edge)
        notes.append(f"edge {edge.id}: no result file, {len(edge.units)} games from totals, resampled as independent "
                     "games (its interval is too narrow by the within-pair correlation)")
        return
    if not edge.file.is_file():
        raise FitError(f"edge {edge.id}: result file {edge.file} not found")
    lines = edge.file.read_text(encoding="utf-8").split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    ignored = 0
    for n, line in enumerate(lines, 1):
        where = f"{edge.file}:{n}"
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            if n == len(lines):
                notes.append(f"edge {edge.id}: torn last line {where} ignored")
                continue
            raise FitError(f"{where}: not a JSON line") from None
        if not isinstance(rec, dict):
            raise FitError(f"{where}: not a JSON object")
        idx, unit = parse_unit(edge, rec, where)
        if idx in edge.units:
            raise FitError(f"{where}: unit {idx} appears twice")
        if idx >= edge.planned:
            ignored += 1
            edge.units[idx] = None
            continue
        edge.units[idx] = unit
    edge.units = {k: v for k, v in edge.units.items() if v is not None}
    if ignored:
        notes.append(f"edge {edge.id}: {ignored} units beyond unitsPlanned={edge.planned} ignored")
    if not edge.units:
        raise FitError(f"edge {edge.id}: no result lines in {edge.file} (0 of {edge.planned} units)")
    if len(edge.units) < edge.planned:
        msg = f"edge {edge.id}: {len(edge.units)} of {edge.planned} planned units in {edge.file}"
        if not allow_partial:
            raise FitError(msg + " (--allow-partial to fit anyway)")
        notes.append(msg + ", fitted as partial")
        return
    if edge.units_present is not None and int(edge.units_present) != len(edge.units):
        raise FitError(f"{edge.where}: edge {edge.id}: unitsPresent = {edge.units_present}, the file holds "
                       f"{len(edge.units)} units")
    if isinstance(edge.totals, dict):
        got = tuple(sum(u["wdl"][i] for u in edge.units.values()) for i in range(3))
        want = tuple(int(edge.totals.get(k, -1)) for k in ("wins", "draws", "losses"))
        if got != want:
            raise FitError(f"{edge.where}: edge {edge.id}: totals W-D-L {want[0]}-{want[1]}-{want[2]}, the lines of "
                           f"{edge.file} sum to {got[0]}-{got[1]}-{got[2]}")


def load_edges(paths, allow_partial=False, notes=None):
    notes = [] if notes is None else notes
    edges, seen = [], set()
    for path in paths:
        path = Path(path)
        text = path.read_text(encoding="utf-8").splitlines()
        count = 0
        for n, line in enumerate(text, 1):
            if not line.strip():
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                raise FitError(f"{path}:{n}: not a JSON line") from None
            e = Edge(d, path.parent, f"{path}:{n}")
            if e.id in seen:
                raise FitError(f"{path}:{n}: edge id {e.id} appears twice")
            seen.add(e.id)
            load_results(e, allow_partial, notes)
            edges.append(e)
            count += 1
        if count == 0:
            raise FitError(f"{path}: no edges")
    return edges


# ----------------------------------------------------------------------------------------------------------------- model

class Cell:
    """One kind of game of one edge: winner groups of slots (player index, seat or -1) and the focus group, the group
    of the edge's first player."""
    __slots__ = ("groups", "all", "focus", "edge")

    def __init__(self, groups, focus, edge):
        self.groups = groups
        self.all = [s for g in groups for s in g]
        self.focus = focus
        self.edge = edge


class Model:
    """The likelihood of one scale over a set of edges. Parameters: ln gamma of every player except the anchor, then
    ln s1, ln s2 if the scale has three-player games."""

    def __init__(self, scale, edges, anchor, prior_games):
        self.scale = scale
        self.edges = edges
        self.anchor = anchor
        players = sorted({p for e in edges for p in e.players})
        if anchor not in players:
            raise FitError(f"scale {scale}: anchor {anchor} plays in no edge")
        check_connected(scale, edges)
        self.players = players
        self.pidx = {p: i for i, p in enumerate(players)}
        param, k = [], 0
        for p in players:
            param.append(-1 if p == anchor else k)
            k += p != anchor
        self._param = dict(zip(players, param))
        self.nplay = k
        self.seats = players_of_scale(scale) == 3
        self.nparam = k + (2 if self.seats else 0)
        seat_param = {0: -1, 1: k, 2: k + 1} if self.seats else {}

        def slot(p, seat):
            return (param[self.pidx[p]], seat_param.get(seat, -1))

        self.cells = []
        self.units = []            # (edge index, cluster key, unit index, [(cell id, weights)], games)
        for ei, e in enumerate(edges):
            ck = {}
            if e.kind == "pair":
                a, b = e.players
                ck["pair"] = self._cell([[slot(a, -1)], [slot(b, -1)]], 0, ei)
            elif e.kind == "one-vs-two":
                c, o = e.players
                for s in range(3):
                    ck[("seat", s)] = self._cell([[slot(c, s)], [slot(o, t) for t in range(3) if t != s]], 0, ei)
            for idx in sorted(e.units):
                u = e.units[idx]
                contrib = []
                for key, w in u["cells"]:
                    if key not in ck:                 # table seatings appear in the data
                        perm = key[1]
                        ck[key] = self._cell([[slot(e.players[perm[s]], s)] for s in range(3)], perm.index(0), ei)
                    contrib.append((ck[key], w))
                self.units.append((ei, e.cluster, idx, contrib, u["games"]))
        self.ndata = len(self.cells)
        self.prior_games = prior_games
        pairs = sorted({tuple(sorted((a, b))) for e in edges for i, a in enumerate(e.players)
                        for b in e.players[i + 1:]})
        self.prior_pairs = pairs
        for a, b in pairs:
            self._cell([[slot(a, -1)], [slot(b, -1)]], 0, -1)
        self.base = self.weights(Counter({(u[1], u[2]): 1 for u in self.units}))

    def _cell(self, groups, focus, ei):
        self.cells.append(Cell(groups, focus, ei))
        return len(self.cells) - 1

    def weights(self, mult, prior=True):
        """Cell weights for unit multiplicities {(cluster key, unit index): m}."""
        W = [[0.0] * len(c.groups) for c in self.cells]
        for ei, ck, idx, contrib, _g in self.units:
            m = mult.get((ck, idx), 0)
            if not m:
                continue
            for cid, w in contrib:
                row = W[cid]
                for g, x in enumerate(w):
                    row[g] += m * x
        if prior:
            h = self.prior_games / 2.0
            for cid in range(self.ndata, len(self.cells)):
                W[cid] = [h, h]
        return W

    def rating(self, theta, player):
        k = self.param_of(player)
        return ANCHOR_ELO + (ELO * theta[k] if k >= 0 else 0.0)

    def param_of(self, player):
        return self._param[player]

    def ratings(self, theta):
        return [self.rating(theta, p) for p in self.players]

    def seat_factors(self, theta):
        if not self.seats:
            return []
        return [math.exp(theta[self.nplay]), math.exp(theta[self.nplay + 1])]


def check_connected(scale, edges):
    adj = defaultdict(set)
    for e in edges:
        for a in e.players:
            adj[a].update(e.players)
    nodes = sorted(adj)
    seen, comps = set(), []
    for n in nodes:
        if n in seen:
            continue
        comp, stack = [], [n]
        seen.add(n)
        while stack:
            x = stack.pop()
            comp.append(x)
            for y in adj[x]:
                if y not in seen:
                    seen.add(y)
                    stack.append(y)
        comps.append(sorted(comp))
    if len(comps) != 1:
        raise FitError(f"scale {scale}: graph not connected, {len(comps)} components: "
                       + " | ".join(", ".join(c) for c in comps))


def _eta(theta, slot):
    p, s = slot
    return (theta[p] if p >= 0 else 0.0) + (theta[s] if s >= 0 else 0.0)


def _lse(theta, slots):
    etas = [_eta(theta, s) for s in slots]
    m = max(etas)
    ex = [math.exp(x - m) for x in etas]
    z = sum(ex)
    return m + math.log(z), [x / z for x in ex]


def loglik(model, theta, W):
    ll = 0.0
    for c, ws in zip(model.cells, W):
        tot = sum(ws)
        if tot == 0:
            continue
        ll -= tot * _lse(theta, c.all)[0]
        for g, w in zip(c.groups, ws):
            if w:
                ll += w * (_eta(theta, g[0]) if len(g) == 1 else _lse(theta, g)[0])
    return ll


def _acc(coef, slots, probs, g, H):
    m = defaultdict(float)
    second = defaultdict(float)
    for slot, pk in zip(slots, probs):
        idx = [i for i in slot if i >= 0]
        for i in idx:
            m[i] += pk
            for j in idx:
                second[(i, j)] += pk
    for i, v in m.items():
        g[i] += coef * v
    if len(slots) == 1:
        return
    for (i, j), v in second.items():
        H[i][j] += coef * v
    for i, vi in m.items():
        for j, vj in m.items():
            H[i][j] -= coef * vi * vj


def derivs(model, theta, W):
    n = model.nparam
    g = [0.0] * n
    H = [[0.0] * n for _ in range(n)]
    ll = 0.0
    for c, ws in zip(model.cells, W):
        tot = sum(ws)
        if tot == 0:
            continue
        lse, probs = _lse(theta, c.all)
        ll -= tot * lse
        _acc(-tot, c.all, probs, g, H)
        for grp, w in zip(c.groups, ws):
            if not w:
                continue
            lse, probs = _lse(theta, grp)
            ll += w * lse
            _acc(w, grp, probs, g, H)
    return ll, g, H


def cholesky(A):
    n = len(A)
    L = [[0.0] * n for _ in range(n)]
    for i in range(n):
        Li = L[i]
        for j in range(i + 1):
            Lj = L[j]
            s = A[i][j] - sum(Li[k] * Lj[k] for k in range(j))
            if i == j:
                if s <= 1e-300:
                    return None
                Li[i] = math.sqrt(s)
            else:
                Li[j] = s / Lj[j]
    return L


def chol_solve(L, b):
    n = len(L)
    y = [0.0] * n
    for i in range(n):
        y[i] = (b[i] - sum(L[i][k] * y[k] for k in range(i))) / L[i][i]
    x = [0.0] * n
    for i in reversed(range(n)):
        x[i] = (y[i] - sum(L[k][i] * x[k] for k in range(i + 1, n))) / L[i][i]
    return x


def inverse(A):
    L = cholesky(A)
    if L is None:
        return None
    n = len(A)
    cols = [chol_solve(L, [1.0 if i == j else 0.0 for i in range(n)]) for j in range(n)]
    return [[cols[j][i] for j in range(n)] for i in range(n)]


def maximise(model, W, theta0=None, max_iter=300):
    """Damped Newton (Levenberg-Marquardt) on the log-likelihood. Returns (theta, ll, converged)."""
    n = model.nparam
    theta = list(theta0) if theta0 is not None else [0.0] * n
    if n == 0:
        return theta, loglik(model, theta, W), True
    lam = 0.0
    ll, g, H = derivs(model, theta, W)
    for _ in range(max_iter):
        while True:
            A = [[-H[i][j] + (lam if i == j else 0.0) for j in range(n)] for i in range(n)]
            L = cholesky(A)
            if L is None:
                lam = max(1e-6, lam * 10)
                if lam > 1e12:
                    return theta, ll, False
                continue
            d = chol_solve(L, g)
            big = max(abs(x) for x in d)
            if big > 5.0:
                d = [x * 5.0 / big for x in d]
                big = 5.0
            new = [t + x for t, x in zip(theta, d)]
            ll_new = loglik(model, new, W)
            if ll_new >= ll - 1e-10 * max(1.0, abs(ll)):
                break
            lam = max(1e-4, lam * 10)
            if lam > 1e12:
                return theta, ll, big < TOL
        theta = new
        lam = lam / 10 if lam > 1e-8 else 0.0
        ll, g, H = derivs(model, theta, W)
        if big < TOL:
            return theta, ll, True
    return theta, ll, False


def cell_probs(model, theta, cid):
    c = model.cells[cid]
    lse_all = _lse(theta, c.all)[0]
    return [math.exp(_lse(theta, g)[0] - lse_all) for g in c.groups]


# ----------------------------------------------------------------------------------------------------------- statistics

def from_score(score):
    """Elo.fromScore of search/src/main/java/sternhalma/search/Elo.java: clamped to [0.001, 0.999]."""
    s = min(0.999, max(0.001, score))
    return 400.0 * math.log10(s / (1.0 - s))


def one_vs_two_elo(p):
    """R(candidate) - R(opponent) for a win share p against two identical opponents: 400 log10(2p / (1 - p))."""
    s = min(0.999, max(0.001, p))
    return 400.0 * math.log10(2.0 * s / (1.0 - s))


def percentile(xs, q):
    s = sorted(xs)
    if not s:
        return float("nan")
    pos = q * (len(s) - 1)
    lo = int(math.floor(pos))
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)


def ci95(xs):
    return [percentile(xs, 0.025), percentile(xs, 0.975)]


def sd(xs):
    if len(xs) < 2:
        return float("nan")
    m = sum(xs) / len(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))


def _gammq(a, x):
    """Regularised upper incomplete gamma Q(a, x) (series below a+1, continued fraction above)."""
    if x <= 0:
        return 1.0
    gln = math.lgamma(a)
    if x < a + 1:
        ap, s, d = a, 1.0 / a, 1.0 / a
        for _ in range(10000):
            ap += 1
            d *= x / ap
            s += d
            if abs(d) < abs(s) * 1e-15:
                break
        return 1.0 - s * math.exp(-x + a * math.log(x) - gln)
    b = x + 1 - a
    c = 1e300
    d = 1.0 / b
    h = d
    for i in range(1, 10000):
        an = -i * (i - a)
        b += 2
        d = an * d + b
        d = 1e-300 if abs(d) < 1e-300 else d
        c = b + an / c
        c = 1e-300 if abs(c) < 1e-300 else c
        d = 1.0 / d
        de = d * c
        h *= de
        if abs(de - 1) < 1e-15:
            break
    return math.exp(-x + a * math.log(x) - gln) * h


def chi2_sf(x, k):
    return _gammq(k / 2.0, x / 2.0)


def edge_stats(model, W):
    """Per edge (focus weight, total weight) from cell weights, and the edge-only Elo of players[0] - players[1]."""
    foc = [0.0] * len(model.edges)
    tot = [0.0] * len(model.edges)
    for cid in range(model.ndata):
        c = model.cells[cid]
        foc[c.edge] += W[cid][c.focus]
        tot[c.edge] += sum(W[cid])
    out = []
    for e, f, t in zip(model.edges, foc, tot):
        if t == 0 or e.kind == "table":
            out.append(None)
        elif e.kind == "pair":
            out.append(from_score(f / t))
        else:
            out.append(one_vs_two_elo(f / t))
    return out, foc, tot


# ---------------------------------------------------------------------------------------------------------- bootstrap

class Stopped(Exception):
    pass


class Runner:
    """Signal flag, progress lines and the replicate cache."""

    def __init__(self, work, quiet=False):
        self.work = Path(work) if work else None
        self.stop = None
        self.quiet = quiet
        self.done_new = 0

    def check(self):
        if self.stop:
            raise Stopped()

    def log(self, msg):
        if not self.quiet:
            print(msg, file=sys.stderr, flush=True)

    def cached(self, name, key, n, fn, label):
        """Run fn(rep) -> JSON-able for rep in 0..n-1, caching each result as one line."""
        res = {}
        f = None
        if self.work:
            cdir = self.work / "cache"
            cdir.mkdir(parents=True, exist_ok=True)
            path = cdir / f"{name}-{key[:16]}.jsonl"
            if path.exists():
                lines = path.read_text(encoding="utf-8").split("\n")
                for line in lines:
                    try:
                        d = json.loads(line)
                    except json.JSONDecodeError:
                        continue                          # torn line of an aborted run
                    if d.get("key") == key and isinstance(d.get("rep"), int) and 0 <= d["rep"] < n:
                        res[d["rep"]] = d["v"]
                # rewrite without torn lines, so that appends start on a clean line
                tmp = path.with_suffix(f".tmp.{os.getpid()}")
                tmp.write_text("".join(json.dumps({"key": key, "rep": r, "v": v}) + "\n"
                                       for r, v in sorted(res.items())), encoding="utf-8")
                os.replace(tmp, path)
            f = open(path, "a", encoding="utf-8")
        skip = len(res)
        todo = [r for r in range(n) if r not in res]
        t0 = last = time.monotonic()
        done = 0
        try:
            for r in todo:
                self.check()
                v = fn(r)
                res[r] = v
                done += 1
                if f:
                    f.write(json.dumps({"key": key, "rep": r, "v": v}) + "\n")
                    f.flush()
                now = time.monotonic()
                if now - last >= 2 or done == len(todo):
                    last = now
                    el = now - t0
                    rate = done / el if el > 0 else 0.0
                    eta = f"{(len(todo) - done) / rate:.0f}s" if rate > 0 else "?"
                    self.log(f"{label}: {skip + done}/{n} ({skip} cached)  {rate:.1f}/s  ETA {eta}  elapsed {el:.0f}s")
        finally:
            if f:
                f.close()
        self.done_new += done
        if not todo:
            self.log(f"{label}: {n}/{n} cached, nothing to compute")
        return [res[r] for r in range(n)]


def model_key(model, extra):
    h = hashlib.sha256()
    h.update(json.dumps([model.scale, model.anchor, model.players, model.prior_games, extra]).encode())
    for e in model.edges:
        h.update(json.dumps([e.id, e.kind, e.players, list(e.cluster)]).encode())
    for ei, ck, idx, contrib, _g in model.units:
        h.update(json.dumps([ei, list(ck), idx, [[c, list(w)] for c, w in contrib]]).encode())
    return h.hexdigest()


def cluster_universe(model):
    """Cluster key -> sorted unit indices present in any edge of that key."""
    uni = defaultdict(set)
    for _ei, ck, idx, _c, _g in model.units:
        uni[ck].add(idx)
    return {k: sorted(v) for k, v in sorted(uni.items(), key=lambda kv: json.dumps(list(kv[0])))}


def resample(model, universe, rng):
    """Opening indices drawn with replacement per cluster group; all edges of a group share the draw."""
    mult = Counter()
    for ck, idxs in universe.items():
        n = len(idxs)
        for _ in range(n):
            mult[(ck, idxs[rng.randrange(n)])] += 1
    return mult


def bootstrap(model, theta_hat, reps, seed, runner):
    universe = cluster_universe(model)
    key = model_key(model, ["boot", seed])

    def one(r):
        rng = random.Random(f"boot|{seed}|{model.scale}|{r}")
        W = model.weights(resample(model, universe, rng))
        th, _ll, ok = maximise(model, W, theta_hat)
        es, _f, _t = edge_stats(model, W)
        return {"R": model.ratings(th), "S": model.seat_factors(th), "E": es, "ok": ok}

    return runner.cached(f"{model.scale}-boot", key, reps, one, f"{model.scale} bootstrap")


def deviance(model, theta, W):
    D = 0.0
    df = 0
    for cid in range(model.ndata):
        ws = W[cid]
        tot = sum(ws)
        if tot == 0:
            continue
        df += len(ws) - 1
        pr = cell_probs(model, theta, cid)
        for w, p in zip(ws, pr):
            if w > 0:
                D += 2 * w * math.log(w / (tot * p))
    return D, df - model.nparam


def gof(model, theta_hat, reps, seed, runner):
    D, df = deviance(model, theta_hat, model.base)
    probs = [cell_probs(model, theta_hat, cid) for cid in range(model.ndata)]
    key = model_key(model, ["gof", seed])

    def one(r):
        rng = random.Random(f"gof|{seed}|{model.scale}|{r}")
        W = [[0.0] * len(c.groups) for c in model.cells]
        for cid in range(model.ndata):
            n = int(round(sum(model.base[cid])))
            pr = probs[cid]
            cum = [sum(pr[:k + 1]) for k in range(len(pr))]
            for _ in range(n):
                W[cid][min(bisect.bisect_left(cum, rng.random()), len(pr) - 1)] += 1
        h = model.prior_games / 2.0
        for cid in range(model.ndata, len(model.cells)):
            W[cid] = [h, h]
        th, _ll, _ok = maximise(model, W, theta_hat)
        return deviance(model, th, W)[0]

    sims = runner.cached(f"{model.scale}-gof", key, reps, one, f"{model.scale} goodness of fit") if reps else []
    p = (1 + sum(1 for x in sims if x >= D)) / (1 + len(sims)) if sims else None
    return D, df, p


# -------------------------------------------------------------------------------------------------------------- fitting

def predicted_share(model, theta, ei, focus_player=None):
    """Expected share of the focus group (players[0], or focus_player) of edge ei, weighted by its observed games."""
    num = den = 0.0
    e = model.edges[ei]
    for cid in range(model.ndata):
        c = model.cells[cid]
        if c.edge != ei:
            continue
        tot = sum(model.base[cid])
        g = c.focus if focus_player is None else _group_of(model, c, e, focus_player)
        num += tot * cell_probs(model, theta, cid)[g]
        den += tot
    return num / den if den else float("nan")


def _group_of(model, c, e, player):
    want = model.param_of(player)
    for g, grp in enumerate(c.groups):
        if any(s[0] == want for s in grp):
            return g
    raise ValueError(player)


def edge_residual(model, theta, ei, cov=None, focus_player=None):
    """Observed and predicted share of edge ei, and the standardised residual with a cluster-robust variance of the
    observation (sum over units of (observed - expected)^2), plus the prediction variance from cov if given."""
    e = model.edges[ei]
    pr = {}
    obs = exp = n = 0.0
    sq = 0.0
    for uei, _ck, _idx, contrib, _g in model.units:
        if uei != ei:
            continue
        o = x = 0.0
        for cid, w in contrib:
            c = model.cells[cid]
            g = c.focus if focus_player is None else _group_of(model, c, e, focus_player)
            if cid not in pr:
                pr[cid] = cell_probs(model, theta, cid)
            o += w[g]
            x += sum(w) * pr[cid][g]
            n += sum(w)
        obs += o
        exp += x
        sq += (o - x) ** 2
    var = sq / (n * n)
    O, E = obs / n, exp / n
    if cov is not None:
        grad = []
        for k in range(model.nparam):
            t1 = list(theta)
            t1[k] += 1e-5
            t0 = list(theta)
            t0[k] -= 1e-5
            grad.append((predicted_share(model, t1, ei, focus_player) - predicted_share(model, t0, ei, focus_player))
                        / 2e-5)
        var += sum(grad[i] * cov[i][j] * grad[j] for i in range(model.nparam) for j in range(model.nparam))
    z = (O - E) / math.sqrt(var) if var > 1e-18 else (0.0 if abs(O - E) < 1e-12 else math.copysign(math.inf, O - E))
    return O, E, z, n


def wald_cov(model, theta):
    _ll, _g, H = derivs(model, theta, model.base)
    return inverse([[-x for x in row] for row in H])


def fit_model(model):
    theta, ll, ok = maximise(model, model.base)
    return theta, ll, ok


def sub_fit(scale, edges, anchor, prior):
    """Fit a subset of edges; None with a reason if the subset is not fittable."""
    if not edges:
        return None, "no edges"
    try:
        m = Model(scale, edges, anchor, prior)
    except FitError as ex:
        return None, str(ex)
    th, _ll, ok = fit_model(m)
    return (m, th, ok), None


class ScaleResult:
    pass


def fit_scale(scale, edges, anchor, configs, args, runner, notes):
    r = ScaleResult()
    r.scale, r.anchor = scale, anchor
    m = Model(scale, edges, anchor, args.prior_games)
    r.model = m
    th, ll, ok = fit_model(m)
    if not ok:
        raise FitError(f"scale {scale}: fit did not converge")
    r.theta = th
    r.R = dict(zip(m.players, m.ratings(th)))
    r.seats = m.seat_factors(th)
    r.cov = wald_cov(m, th)
    runner.check()
    r.boot = bootstrap(m, th, args.replicates, args.seed, runner)
    r.Rrep = {p: [b["R"][i] for b in r.boot] for i, p in enumerate(m.players)}
    r.ci = {p: ci95(v) for p, v in r.Rrep.items()}
    r.boot_failed = sum(1 for b in r.boot if not b["ok"])
    r.gof = gof(m, th, args.gof_replicates, args.seed, runner)
    r.edge_only, _f, _t = edge_stats(m, m.base)

    # per-edge residuals
    r.residuals = []
    for ei, e in enumerate(edges):
        O, E, z, n = edge_residual(m, th, ei)
        r.residuals.append({"edge": e.id, "kind": e.kind, "players": e.players, "games": n, "obs": O, "pred": E,
                            "z": z, "edgeElo": r.edge_only[ei], "units": len(e.units), "source": e.source})
    r.max_abs_res = max((abs(x["z"]) for x in r.residuals), default=0.0)

    # games, edges, non-win share, cost per config
    r.games = Counter()
    r.nedges = Counter()
    r.cpu = defaultdict(lambda: [0.0, 0.0])
    nonwin = total = 0
    for e in edges:
        g = sum(u["games"] for u in e.units.values())
        total += g
        nonwin += sum(u["draws"] for u in e.units.values())
        for p in e.players:
            r.games[p] += g
            r.nedges[p] += 1
        for u in e.units.values():
            for pi, (ns, mv) in u["cpu"].items():
                r.cpu[e.players[pi]][0] += ns
                r.cpu[e.players[pi]][1] += mv
    r.nonwin_share = nonwin / total if total else 0.0
    r.total_games = total
    diagnostics(r, configs, args, runner, notes)
    return r


def _pair_diffs(model, es_list):
    """Unordered pair -> list of signed edge-only differences (a minus b, a < b) of its pair and one-vs-two edges."""
    d = defaultdict(list)
    for e, x in zip(model.edges, es_list):
        if x is None:
            continue
        a, b = e.players[0], e.players[1]
        if a < b:
            d[(a, b)].append(x)
        else:
            d[(b, a)].append(-x)
    return {k: sum(v) / len(v) for k, v in d.items()}


def diagnostics(r, configs, args, runner, notes):
    m, edges = r.model, r.model.edges
    # cycles
    point = _pair_diffs(m, r.edge_only)
    reps = [_pair_diffs(m, b["E"]) for b in r.boot]
    nodes = sorted({p for k in point for p in k})
    r.cycles = []
    for i, a in enumerate(nodes):
        for j in range(i + 1, len(nodes)):
            b = nodes[j]
            if (a, b) not in point:
                continue
            for c in nodes[j + 1:]:
                if (b, c) in point and (a, c) in point:
                    s = point[(a, b)] + point[(b, c)] - point[(a, c)]
                    sr = [x[(a, b)] + x[(b, c)] - x[(a, c)] for x in reps
                          if (a, b) in x and (b, c) in x and (a, c) in x]
                    se = sd(sr)
                    r.cycles.append({"players": [a, b, c], "sum": s, "se": se,
                                     "z": s / se if se and se > 0 else float("nan")})

    # leave one edge out
    r.loo = []
    for ei, e in enumerate(edges):
        runner.check()
        rest = edges[:ei] + edges[ei + 1:]
        sub, why = sub_fit(r.scale, rest, r.anchor, args.prior_games)
        if sub is None:
            r.loo.append({"edge": e.id, "skipped": "not redundant: " + why.split(":", 1)[-1].strip()})
            continue
        ms, ths, _ok = sub
        full = Model(r.scale, edges, r.anchor, args.prior_games)       # full design, sub-fit parameters
        th_full = _map_theta(ms, ths, full)
        cov = wald_cov(ms, ths)
        cov_full = _map_cov(ms, cov, full) if cov else None
        O, E, z, n = edge_residual(full, th_full, ei, cov_full)
        r.loo.append({"edge": e.id, "obs": O, "pred": E, "z": z})

    # sensitivity
    r.sens = []

    def compare(label, sub):
        if sub is None:
            return
        ms, ths, ok = sub
        moved = []
        Rs = dict(zip(ms.players, ms.ratings(ths)))
        rows = []
        for p in m.players:
            if p not in Rs:
                rows.append((p, None, None))
                continue
            delta = Rs[p] - r.R[p]
            half = (r.ci[p][1] - r.ci[p][0]) / 2
            if p != r.anchor and abs(delta) > half:
                moved.append(p)
            rows.append((p, Rs[p], delta))
        r.sens.append({"label": label, "converged": ok, "rows": rows, "moved": moved})

    mp = Model(r.scale, edges, r.anchor, 0.0)
    th0, _ll, ok0 = maximise(mp, mp.base, r.theta)
    # Without the prior a perfect edge has no finite maximum; the steps stall when 1 - p underflows, so a parameter
    # beyond 15 (2600 Elo from the anchor, a win probability below 1e-6) counts as diverged.
    finite = ok0 and all(abs(x) < 15 for x in th0)
    compare("without the prior" + ("" if finite else " (did not converge: separation, some ratings infinite)"),
            (mp, th0, finite))
    keep = [e for i, e in enumerate(edges) if 1 - SATURATED <= r.residuals[i]["obs"] <= SATURATED]
    if len(keep) < len(edges):
        sub, why = sub_fit(r.scale, keep, r.anchor, args.prior_games)
        if sub:
            compare(f"without saturated edges ({len(edges) - len(keep)} dropped)", sub)
        else:
            r.sens.append({"label": "without saturated edges", "skipped": why})
    new = [e for e in edges if e.source == "new"]
    if 0 < len(new) < len(edges):
        sub, why = sub_fit(r.scale, new, r.anchor, args.prior_games)
        if sub:
            compare("new data only", sub)
        else:
            r.sens.append({"label": "new data only", "skipped": why})
    r.alt_anchor = None
    lvl1 = [p for p in m.players if configs.get(p, {}).get("level") == "1"]
    if lvl1:
        a = lvl1[0]
        r.alt_anchor = (a, {p: ci95([x - y for x, y in zip(r.Rrep[p], r.Rrep[a])]) for p in m.players})

    # old against new
    r.oldnew = []
    groups = defaultdict(lambda: {"new": [], "existing": []})
    for ei, e in enumerate(edges):
        k = (e.kind, tuple(e.players) if e.kind == "one-vs-two" else tuple(sorted(e.players)))
        groups[k]["new" if e.source == "new" else "existing"].append(ei)
    for k, g in groups.items():
        if g["new"] and g["existing"]:
            for ei in g["existing"] + g["new"]:
                xs = [b["E"][ei] for b in r.boot if b["E"][ei] is not None]
                r.oldnew.append({"edge": edges[ei].id, "source": edges[ei].source, "elo": r.edge_only[ei],
                                 "ci95": ci95(xs) if xs else None, "players": edges[ei].players})

    # three players
    r.roleswap, r.mixed, r.three_verdict, r.seat_ci = [], None, None, None
    if m.seats:
        r.seat_ci = [ci95([b["S"][k] for b in r.boot]) for k in range(2)]
        by = defaultdict(list)
        for ei, e in enumerate(edges):
            if e.kind == "one-vs-two":
                by[tuple(e.players)].append(ei)
        for (x, y), eis in sorted(by.items()):
            if x > y or (y, x) not in by:
                continue
            back = by[(y, x)]

            def dd(es):
                d1 = sum(es[i] for i in eis) / len(eis)
                d2 = -sum(es[i] for i in back) / len(back)
                return d1, d2
            d1, d2 = dd(r.edge_only)
            diffs = [a - b for a, b in (dd(b["E"]) for b in r.boot)]
            s = sd(diffs)
            z = (d1 - d2) / s if s and s > 0 else (0.0 if d1 == d2 else math.inf)
            r.roleswap.append({"x": x, "y": y, "dX": d1, "dY": d2, "z": z})
        tables = [ei for ei, e in enumerate(edges) if e.kind == "table"]
        if tables:
            rest = [e for e in edges if e.kind != "table"]
            sub, why = sub_fit(r.scale, rest, r.anchor, args.prior_games)
            if sub is None:
                r.mixed = {"skipped": "the one-against-two edges alone: " + why}
            else:
                ms, ths, _ok = sub
                full = Model(r.scale, edges, r.anchor, args.prior_games)
                missing = [p for p in full.players if p not in ms.pidx]
                if missing:
                    r.mixed = {"skipped": "players only at tables: " + ", ".join(missing)}
                else:
                    thf = _map_theta(ms, ths, full)
                    cov = wald_cov(ms, ths)
                    covf = _map_cov(ms, cov, full) if cov else None
                    rows, x2, df = [], 0.0, 0
                    for ei in tables:
                        e = edges[ei]
                        for p in e.players:
                            O, E, z, n = edge_residual(full, thf, ei, covf, focus_player=p)
                            rows.append({"edge": e.id, "player": p, "obs": O, "pred": E, "z": z})
                            x2 += n * (O - E) ** 2 / E if E > 0 else 0.0
                        df += 2
                    pval = chi2_sf(x2, df)
                    r.mixed = {"rows": rows, "x2": x2, "df": df, "p": pval}
        bad = [x for x in r.roleswap if abs(x["z"]) > 3]
        if r.mixed and "rows" in r.mixed:
            bad += [x for x in r.mixed["rows"] if abs(x["z"]) > 3]
            if r.mixed["p"] < 0.01:
                bad.append("deviance")
        r.three_verdict = "per-table-type-only" if bad else "one-dimensional"


def _map_theta(src, theta, dst):
    """Parameters of a sub-model, placed into the parameter vector of the full model (players absent: 0)."""
    out = [0.0] * dst.nparam
    for p in src.players:
        k = src.param_of(p)
        if k >= 0 and p in dst.pidx:
            out[dst.param_of(p)] = theta[k]
    if src.seats and dst.seats:
        out[dst.nplay], out[dst.nplay + 1] = theta[src.nplay], theta[src.nplay + 1]
    return out


def _map_cov(src, cov, dst):
    idx = {}
    for p in src.players:
        k = src.param_of(p)
        if k >= 0 and p in dst.pidx:
            idx[k] = dst.param_of(p)
    if src.seats and dst.seats:
        idx[src.nplay], idx[src.nplay + 1] = dst.nplay, dst.nplay + 1
    out = [[0.0] * dst.nparam for _ in range(dst.nparam)]
    for i, a in idx.items():
        for j, b in idx.items():
            out[a][b] = cov[i][j]
    return out


# ------------------------------------------------------------------------------------------------------------- bridge

def bridge_test(scale_g, scale_h, names, rg, rh, repg, reph):
    """The pre-registered test of plan 2.5 for one pair of scales.

    names: bridge ids; rg, rh: point ratings per bridge id; repg, reph: bootstrap replicates per bridge id (paired by
    replicate index; the scales are independent data, so pairing by index gives independent draws)."""
    k = len(names)
    d = [rg[b] - rh[b] for b in names]
    n = min(len(repg[names[0]]), len(reph[names[0]]))
    drep = [[repg[b][i] - reph[b][i] for i in range(n)] for b in names]
    mean = [sum(x) / n for x in drep]
    S = [[sum((drep[i][t] - mean[i]) * (drep[j][t] - mean[j]) for t in range(n)) / (n - 1) for j in range(k)]
         for i in range(k)]
    var = [max(S[i][i], 1e-6) for i in range(k)]
    half = [(percentile(drep[i], 0.975) - percentile(drep[i], 0.025)) / 2 for i in range(k)]
    # Wald test of the k-1 contrasts d_i - d_0
    C = [[(1 if j == i else 0) - (1 if j == 0 else 0) for j in range(k)] for i in range(1, k)]
    cd = [sum(C[i][j] * d[j] for j in range(k)) for i in range(k - 1)]
    CSC = [[sum(C[i][a] * S[a][b] * C[j][b] for a in range(k) for b in range(k)) for j in range(k - 1)]
           for i in range(k - 1)]
    L = cholesky(CSC) if k > 1 else None
    if L is not None:
        y = chol_solve(L, cd)
        chi2 = sum(a * b for a, b in zip(cd, y))
        p = chi2_sf(chi2, k - 1)
    else:
        chi2, p = None, None
    # offset: a bridge player without sampling error (the anchor) fixes it; else GLS
    fixed = [i for i in range(k) if S[i][i] < 1e-6]
    if fixed:
        offset = d[fixed[0]]
    else:
        Si = inverse(S)
        if Si:
            num = sum(Si[i][j] * d[j] for i in range(k) for j in range(k))
            den = sum(Si[i][j] for i in range(k) for j in range(k))
            offset = num / den
        else:
            offset = sum(d) / k
    # tau: DerSimonian-Laird between-player SD of d beyond its sampling error
    w = [1 / v for v in var]
    sw = sum(w)
    dbar = sum(wi * di for wi, di in zip(w, d)) / sw
    Q = sum(wi * (di - dbar) ** 2 for wi, di in zip(w, d))
    den = sw - sum(wi * wi for wi in w) / sw
    tau = math.sqrt(max(0.0, (Q - (k - 1)) / den)) if den > 0 else 0.0
    # Deming slope of R_H on R_G, error variance ratio from the bootstrap
    slope = None
    if k >= 3:
        x = [rg[b] for b in names]
        yv = [rh[b] for b in names]
        mx, my = sum(x) / k, sum(yv) / k
        sxx = sum((a - mx) ** 2 for a in x) / (k - 1)
        syy = sum((a - my) ** 2 for a in yv) / (k - 1)
        sxy = sum((a - mx) * (b - my) for a, b in zip(x, yv)) / (k - 1)
        vx = sum(sd(repg[b]) ** 2 for b in names) / k
        vy = sum(sd(reph[b]) ** 2 for b in names) / k
        lam = vy / vx if vx > 0 else 1.0
        if sxy != 0:
            slope = (syy - lam * sxx + math.sqrt((syy - lam * sxx) ** 2 + 4 * lam * sxy ** 2)) / (2 * sxy)
    reasons = []
    if rows_of_scale(scale_g) != rows_of_scale(scale_h):
        reasons.append("4 against 5 rows: no network plays both board sizes, the bridge rests on one engine family")
    if p is None:
        reasons.append("test not computable")
    elif p < 0.05:
        reasons.append(f"one offset rejected (p = {p:.3g})")
    if tau > 50:
        reasons.append(f"tau = {tau:.0f} > 50")
    wide = [b for b, h in zip(names, half) if h > 60]
    if wide:
        reasons.append("interval of d wider than +-60 for " + ", ".join(wide) + " (no power)")
    verdict = "separate" if reasons else "mixed"
    out = {"scales": [scale_g, scale_h], "chi2": chi2, "df": k - 1, "p": p, "offset": offset, "slope": slope,
           "tau": tau, "verdict": verdict, "reasons": reasons, "players": names, "d": d, "dHalf": half}
    if verdict == "mixed":
        pooled = {}
        for i, b in enumerate(names):
            vg, vh = sd(repg[b]) ** 2, sd(reph[b]) ** 2
            if vg <= 0 or vh <= 0:
                est = rg[b]
                ci = 0.0
            else:
                est = (rg[b] / vg + (rh[b] + offset) / vh) / (1 / vg + 1 / vh)
                ci = 1.96 * math.sqrt(1 / (1 / vg + 1 / vh))
            hw = math.sqrt(ci ** 2 + (1.96 * tau) ** 2)
            pooled[b] = {"elo": est, "ci95": [est - hw, est + hw]}
        out["pooled"] = pooled
    return out


# ------------------------------------------------------------------------------------------------------------- output

def parse_id(cid):
    net = re.match(r"^net:(?P<net>.+)@s(?P<sims>\d+)$", cid)        # matches: net:r5-small-gen9@s200
    return (net["net"], int(net["sims"])) if net else (None, None)


def config_meta(cid, configs):
    c = configs.get(cid, {})
    net, sims = parse_id(cid)
    if c.get("net"):
        net = c["net"]
    if c.get("sims"):
        try:
            sims = int(c["sims"])
        except ValueError:
            pass
    basis = [b for b in c.get("basis", "").split(",") if b]
    return net, sims, basis


def pair_entry(r, a, b):
    reps = [x - y for x, y in zip(r.Rrep[a], r.Rrep[b])]
    return {"a": a, "b": b, "elo": r.R[a] - r.R[b], "ci95": ci95(reps)}


def build_json(results, bridge, configs, wanted_pairs, args):
    out = {"schema": 1, "date": args.date, "commit": args.commit, "anchorRule": ANCHOR_RULE, "scales": {},
           "bridge": bridge, "levels": {}}
    for s, r in results.items():
        m = r.model
        players = []
        for p in m.players:
            net, sims, basis = config_meta(p, configs)
            players.append({"id": p, "net": net, "sims": sims, "basis": basis, "elo": r.R[p], "ci95": r.ci[p],
                            "games": r.games[p], "edges": r.nedges[p]})
        levels = sorted(((int(configs[p]["level"]), p) for p in m.players
                         if configs.get(p, {}).get("level", "").isdigit()))
        pairs, seen = [], set()
        for a, b in wanted_pairs:
            if a in r.R and b in r.R and (a, b) not in seen:
                seen.add((a, b))
                pairs.append(pair_entry(r, a, b))
        for p in m.players:
            if p.startswith("net:") and levels:
                lv = min(levels, key=lambda x: abs(r.R[x[1]] - r.R[p]))[1]
                if (p, lv) not in seen:
                    seen.add((p, lv))
                    pairs.append(pair_entry(r, p, lv))
        D, df, pval = r.gof
        fit = {"deviance": D, "df": df, "p": pval, "maxAbsResidual": r.max_abs_res, "priorGames": args.prior_games}
        if r.three_verdict:
            fit["threePlayer"] = r.three_verdict
        out["scales"][s] = {"anchor": r.anchor, "players": players, "pairs": pairs, "fit": fit}
        if levels:
            out["levels"][s] = [{"level": lv, "nodes": _int(configs[p].get("nodes")), "elo": r.R[p], "ci95": r.ci[p]}
                                for lv, p in levels]
    return out


def _int(x):
    try:
        return int(x)
    except (TypeError, ValueError):
        return None


def f0(x):
    return "n/a" if x is None or (isinstance(x, float) and math.isnan(x)) else f"{x:.0f}"


def pm(lo_hi):
    return f"{(lo_hi[1] - lo_hi[0]) / 2:.0f}"


def build_report(results, bridge, js, args, notes):
    L = [f"# Elo fit report", "", f"Date {args.date}, commit {args.commit}. Anchor rule: {ANCHOR_RULE}.",
         f"Bootstrap: {args.replicates} replicates over openings, seed {args.seed}; prior {args.prior_games} virtual "
         "game per played pair. \"+-\" is half the 95 % percentile interval.", ""]
    if notes:
        L += ["Input notes:", ""] + [f"- {n}" for n in notes] + [""]
    for s, r in results.items():
        m = r.model
        D, df, pval = r.gof
        L += [f"## {s}", "", f"Anchor `{r.anchor}` = 1000. {len(m.edges)} edges, {len(m.players)} configs, "
              f"{r.total_games} games; share of games without a winner (draws, move limit): {r.nonwin_share:.3f}.", ""]
        if r.boot_failed:
            L += [f"Warning: {r.boot_failed} bootstrap replicates did not converge.", ""]
        L += ["### Ratings", "", "| Config | Elo | 95 % | +- | Wald +- | games | edges |", "|---|---|---|---|---|---|---|"]
        for p in sorted(m.players, key=lambda x: -r.R[x]):
            k = m.param_of(p)
            wald = 1.96 * ELO * math.sqrt(r.cov[k][k]) if k >= 0 and r.cov else 0.0
            L.append(f"| `{p}` | {r.R[p]:.0f} | [{r.ci[p][0]:.0f}, {r.ci[p][1]:.0f}] | {pm(r.ci[p])} | {wald:.0f} | "
                     f"{r.games[p]} | {r.nedges[p]} |")
        pairs = js["scales"][s]["pairs"]
        if pairs:
            L += ["", "Pairwise:", "", "| a | b | a - b | 95 % |", "|---|---|---|---|"]
            for x in pairs:
                L.append(f"| `{x['a']}` | `{x['b']}` | {x['elo']:+.0f} | [{x['ci95'][0]:.0f}, {x['ci95'][1]:.0f}] |")
        L += ["", "### 1. Edges", "", "| Edge | kind | players | games | observed | predicted | residual | edge-only Elo |",
              "|---|---|---|---|---|---|---|---|"]
        for x in r.residuals:
            flag = " **!**" if abs(x["z"]) > 2.5 else ""
            L.append(f"| {x['edge']} | {x['kind']} | {' / '.join(x['players'])} | {x['games']:.0f} | {x['obs']:.3f} | "
                     f"{x['pred']:.3f} | {x['z']:+.2f}{flag} | {f0(x['edgeElo'])} |")
        L += ["", "Observed and predicted: share of the first player (draws as 1/2 for two players, 1/3 for three). "
              "Residual: (observed - predicted) / cluster-robust SE; **!** marks |residual| > 2.5.", ""]
        L += ["### 2. Goodness of fit", "", f"Deviance {D:.1f} on {df} df; parametric-bootstrap p = "
              f"{'n/a' if pval is None else f'{pval:.3f}'} ({args.gof_replicates} replicates, games simulated as "
              "independent: the within-pair correlation is not simulated).", ""]
        L += ["### 3. Cycles", ""]
        if r.cycles:
            L += ["| a | b | c | (a-b) + (b-c) - (a-c) | SE | z |", "|---|---|---|---|---|---|"]
            for c in r.cycles:
                L.append(f"| `{c['players'][0]}` | `{c['players'][1]}` | `{c['players'][2]}` | {c['sum']:+.0f} | "
                         f"{f0(c['se'])} | {c['z']:+.2f} |")
        else:
            L.append("No triangle of directly measured pairs.")
        L += ["", "### 4. Leave one edge out", "", "| Edge | observed | predicted from the rest | z |", "|---|---|---|---|"]
        for x in r.loo:
            if "skipped" in x:
                L.append(f"| {x['edge']} | | {x['skipped']} | |")
            else:
                L.append(f"| {x['edge']} | {x['obs']:.3f} | {x['pred']:.3f} | {x['z']:+.2f} |")
        L += ["", "### 5. Sensitivity", ""]
        for sv in r.sens:
            if "skipped" in sv:
                L.append(f"- {sv['label']}: not fitted ({sv['skipped']})")
                continue
            moved = ", ".join(f"`{p}`" for p in sv["moved"]) or "none"
            L.append(f"- {sv['label']}: moved by more than their interval: {moved}")
            for p, val, delta in sv["rows"]:
                L.append(f"  - `{p}`: " + ("not rated" if val is None else f"{val:.0f} ({delta:+.0f})"))
        if r.alt_anchor:
            a, cis = r.alt_anchor
            L.append(f"- alternative anchor `{a}` (level 1): +- against it: "
                     + ", ".join(f"`{p}` {pm(c)}" for p, c in cis.items() if p != a))
        L += ["", "### 6. Old against new", ""]
        if r.oldnew:
            L += ["| Edge | source | edge-only Elo | 95 % |", "|---|---|---|---|"]
            for x in r.oldnew:
                ci = x["ci95"]
                L.append(f"| {x['edge']} | {x['source']} | {f0(x['elo'])} | "
                         + (f"[{ci[0]:.0f}, {ci[1]:.0f}]" if ci else "") + " |")
        else:
            L.append("No edge measured both in earlier runs and in this study.")
        if m.seats:
            L += ["", "### 7. Three players", "",
                  f"Seat factors (seat 0 = 1): seat 1 {r.seats[0]:.3f} [{r.seat_ci[0][0]:.3f}, {r.seat_ci[0][1]:.3f}], "
                  f"seat 2 {r.seats[1]:.3f} [{r.seat_ci[1][0]:.3f}, {r.seat_ci[1][1]:.3f}].", ""]
            if r.roleswap:
                L += ["Role swap (X against 2 x Y and Y against 2 x X, closed form 400 log10(2p/(1-p))):", "",
                      "| X | Y | X - Y from X as candidate | from Y as candidate | z |", "|---|---|---|---|---|"]
                for x in r.roleswap:
                    flag = " **!**" if abs(x["z"]) > 3 else ""
                    L.append(f"| `{x['x']}` | `{x['y']}` | {x['dX']:+.0f} | {x['dY']:+.0f} | {x['z']:+.2f}{flag} |")
            else:
                L.append("No pair measured in both roles.")
            if r.mixed:
                if "skipped" in r.mixed:
                    L += ["", f"Mixed tables: not tested ({r.mixed['skipped']})."]
                else:
                    L += ["", f"Mixed tables against the one-against-two fit: X2 = {r.mixed['x2']:.1f} on "
                          f"{r.mixed['df']} df, p = {r.mixed['p']:.3g}.", "",
                          "| Edge | player | observed share | predicted | z |", "|---|---|---|---|---|"]
                    for x in r.mixed["rows"]:
                        L.append(f"| {x['edge']} | `{x['player']}` | {x['obs']:.3f} | {x['pred']:.3f} | {x['z']:+.2f} |")
            L += ["", f"Verdict: **{r.three_verdict}** (role-swap or mixed-table |z| > 3, or mixed-table p < 0.01, "
                  "means three-player ratings are reported per table type only). Kingmaking is not removable and "
                  "applies to every three-player figure.", ""]
        L += ["", "### 9. Cost", "", "| Config | CPU-ms per move | CPU hours |", "|---|---|---|"]
        for p in m.players:
            ns, mv = r.cpu.get(p, (0.0, 0.0))
            L.append(f"| `{p}` | {ns / mv / 1e6:.1f} | {ns / 3.6e12:.2f} |" if mv else f"| `{p}` | n/a | n/a |")
        timed = [p for p in m.players if "time" in config_meta(p, args.configs_map)[2] and r.cpu.get(p, (0, 0))[1]]
        if len(timed) >= 2:
            ms = {p: r.cpu[p][0] / r.cpu[p][1] / 1e6 for p in timed}
            L += ["", "Equal-time configs: " + ", ".join(f"`{p}` {v:.1f} ms" for p, v in ms.items())
                  + f"; ratio max/min {max(ms.values()) / min(ms.values()):.2f}."]
        L += ["", "Hours and kWh per block: see the energy report of the run (scripts/energy/report.py).", ""]
    L += ["## 8. Bridge", ""]
    bp = bridge["players"]
    if bp:
        scales = sorted({s for b in bp for s in b["ratings"]})
        L += ["1000 means \"level 3 of this game type\"; numbers of different columns are not comparable beyond that.",
              "", "| Bridge | " + " | ".join(scales) + " |", "|---|" + "---|" * len(scales)]
        for b in bp:
            cells = []
            for s in scales:
                x = b["ratings"].get(s)
                cells.append(f"{x['elo']:.0f} +- {pm(x['ci95'])}" if x else "")
            L.append(f"| {b['id']} | " + " | ".join(cells) + " |")
        L.append("")
        for t in bridge["tests"]:
            chi = "n/a" if t["chi2"] is None else f"{t['chi2']:.1f}"
            pv = "n/a" if t["p"] is None else f"{t['p']:.3g}"
            sl = "n/a" if t["slope"] is None else f"{t['slope']:.2f}"
            L.append(f"- {t['scales'][0]} against {t['scales'][1]}: chi2 = {chi} on {t['df']} df, p = {pv}, offset "
                     f"{t['offset']:+.0f}, slope {sl}, tau {t['tau']:.0f}: **{t['verdict']}**"
                     + (f" ({'; '.join(t['reasons'])})" if t["reasons"] else ""))
    else:
        L.append("No bridge players (configs.tsv column 'bridge') in two or more scales.")
    return "\n".join(L) + "\n"


def run_bridge(results, configs):
    ids = defaultdict(dict)                     # bridge id -> scale -> config
    for s, r in results.items():
        for p in r.model.players:
            b = configs.get(p, {}).get("bridge")
            if b:
                ids[b][s] = p
    players = [{"id": b, "ratings": {s: {"config": p, "elo": results[s].R[p], "ci95": results[s].ci[p]}
                                     for s, p in sorted(per.items())}}
               for b, per in sorted(ids.items()) if len(per) >= 2]
    tests = []
    scales = sorted(results)
    for i, g in enumerate(scales):
        for h in scales[i + 1:]:
            names = sorted(b for b, per in ids.items() if g in per and h in per)
            if len(names) < 2:
                continue
            rg = {b: results[g].R[ids[b][g]] for b in names}
            rh = {b: results[h].R[ids[b][h]] for b in names}
            repg = {b: results[g].Rrep[ids[b][g]] for b in names}
            reph = {b: results[h].Rrep[ids[b][h]] for b in names}
            tests.append(bridge_test(g, h, names, rg, rh, repg, reph))
    return {"players": players, "tests": tests}


# --------------------------------------------------------------------------------------------------------------- main

def atomic_write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp.{os.getpid()}")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)


def resolve_anchors(edges, configs, overrides):
    anchors = {}
    for s in sorted({e.scale for e in edges}):
        if s in overrides:
            anchors[s] = overrides[s]
            continue
        players = {p for e in edges if e.scale == s for p in e.players}
        marked = sorted(p for p in players if configs.get(p, {}).get("anchor") == "1")
        l3 = marked or sorted(p for p in players if configs.get(p, {}).get("level") == "3")
        if len(l3) != 1:
            raise FitError(f"scale {s}: anchor needs exactly one config with anchor = 1 or level 3 in configs.tsv "
                           f"among its players (found {len(l3)}), or --anchor {s}=CONFIG")
        anchors[s] = l3[0]
    return anchors


def fit_all(edge_paths, args, runner, notes):
    configs = load_configs(args.configs)
    args.configs_map = configs
    edges = load_edges(edge_paths, args.allow_partial, notes)
    anchors = resolve_anchors(edges, configs, dict(a.split("=", 1) for a in args.anchor))
    wanted = []
    if args.pairs:
        for n, line in enumerate(Path(args.pairs).read_text(encoding="utf-8").splitlines(), 1):
            if line.strip() and not line.startswith("#"):
                parts = line.split("\t")
                if len(parts) != 2:
                    raise FitError(f"{args.pairs}:{n}: expected a<TAB>b")
                wanted.append((parts[0].strip(), parts[1].strip()))
    results = {}
    for s, a in anchors.items():
        results[s] = fit_scale(s, [e for e in edges if e.scale == s], a, configs, args, runner, notes)
    bridge = run_bridge(results, configs)
    js = build_json(results, bridge, configs, wanted, args)
    rep = build_report(results, bridge, js, args, notes)
    return js, rep


def _lock(work):
    import fcntl
    work.mkdir(parents=True, exist_ok=True)
    fh = open(work / ".fit.lock", "a")
    try:
        fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        fh.close()
        return None
    return fh


def _status_text(work):
    sf = work / ".fit.status"
    if not sf.exists():
        return "pid=? start=?"
    txt = sf.read_text(encoding="utf-8").strip()
    pid = txt.split()[0].removeprefix("pid=")
    et = subprocess.run(["ps", "-o", "etime=", "-p", pid], capture_output=True, text=True).stdout.strip()
    return f"{txt}, running for {et or '? (process gone)'}"


def parse_args(argv):
    ap = argparse.ArgumentParser(description="Elo study fit (see the docstring of this file).")
    ap.add_argument("edges", nargs="+")
    ap.add_argument("--configs")
    ap.add_argument("--anchor", action="append", default=[], help="SCALE=CONFIG")
    ap.add_argument("--pairs")
    ap.add_argument("--out", default=str(REPO / "models" / "ratings"))
    ap.add_argument("--work")
    ap.add_argument("--replicates", type=int, default=2000)
    ap.add_argument("--gof-replicates", type=int, default=200)
    ap.add_argument("--seed", type=int, default=8100)
    ap.add_argument("--prior-games", type=float, default=1.0)
    ap.add_argument("--allow-partial", action="store_true")
    ap.add_argument("--date")
    ap.add_argument("--commit")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    for a in args.anchor:
        if "=" not in a:
            ap.error(f"--anchor {a}: expected SCALE=CONFIG")
    if args.replicates < 20:
        ap.error("--replicates must be at least 20")
    return args


def main(argv=None):
    try:
        args = parse_args(argv)
    except SystemExit as ex:
        return ex.code if isinstance(ex.code, int) else 2
    work = Path(args.work) if args.work else Path(args.edges[0]).resolve().parent / "fit-work"
    lock = _lock(work)
    if lock is None:
        print(f"already running: {_status_text(work)}", file=sys.stderr)
        return 75
    if args.status:
        print("not running")
        return 0
    (work / ".fit.status").write_text(f"pid={os.getpid()} start={time.strftime('%Y-%m-%dT%H:%M:%S')}\n",
                                      encoding="utf-8")
    if args.date is None:
        args.date = time.strftime("%Y-%m-%d", time.gmtime())
    if args.commit is None:
        try:
            args.commit = subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True,
                                         text=True, check=True).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            args.commit = "unknown"
    runner = Runner(work, args.quiet)
    old = {}
    for sig in (signal.SIGINT, signal.SIGTERM):
        old[sig] = signal.signal(sig, lambda s, _f: setattr(runner, "stop", s))
    notes = []
    try:
        js, rep = fit_all(args.edges, args, runner, notes)
    except FitError as ex:
        print(f"fit error: {ex}", file=sys.stderr)
        return 1
    except Stopped:
        print(f"stopped by signal {runner.stop}: no output written; {runner.done_new} replicates of this run are "
              f"cached in {work / 'cache'}, a rerun continues there", file=sys.stderr)
        return 128 + int(runner.stop)
    finally:
        for sig, h in old.items():
            signal.signal(sig, h)
        lock.close()
    out = Path(args.out)
    atomic_write(out / "ratings.json", json.dumps(js, indent=1) + "\n")
    atomic_write(out / "fit-report.md", rep)
    print(f"wrote {out / 'ratings.json'} and {out / 'fit-report.md'} ({len(js['scales'])} scales, "
          f"{runner.done_new} replicates computed in this run)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
