#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Elo study, random-effects fit (method tag re-nma-pm-kh): the published ratings of docs/elo.md.

usage: fit_re.py [--configs configs.tsv] [--anchor SCALE=CONFIG ...] [--pairs pairs.tsv] [--bt ratings.json]
                 [--date YYYY-MM-DD] [--out FILE] [--allow-partial] EDGES.jsonl [EDGES.jsonl ...]

Writes one JSON file (default: stdout) with, per scale, the rating and 95 % half-width of every config, tau, the
leave-one-edge-out coverage and the requested pairwise differences; plus the energy-table rows of the scale 2p-adj-r5
and the level tables (app levels 1 to 5) of every scale. models/ratings/ratings.json was built from this output and the
editorial parts of the evaluation (compare rule, caveats, human table, Tier 2 advice), which this tool does not write.

WHY A SECOND FIT
----------------
fit.py (Bradley-Terry with a cluster bootstrap) gives intervals that hold sampling error only. In the Tier 1 data the
matches disagree with one rating per config by several times that error (deviance p <= 0.01 in every scale), so its
intervals are too narrow. This fit adds the disagreement as a random effect per edge and estimates its size.

INPUT
-----
The edge format of scripts/elo/FORMAT.md, read by fit.load_edges (the same checks: unit counts, totals, torn last line;
gzip'd result files are read when the plain file is missing). configs.tsv and --anchor work as in fit.py: the anchor of
a scale is its config with anchor = 1 or level = 3, or --anchor SCALE=CONFIG. Edges of kind "table" (three different
players) are refused: the edge-level model below has no form for them.

MODEL (per scale)
-----------------
1. Per edge: p = (points + prior) / (games + 1), prior 0.5 for two players and 1/3 for one-against-two.
   y = 400 log10(p / (1 - p)); one-against-two: y = 400 log10(2p / (1 - p)), the Luce difference of one player
   against two copies of another.
2. Standard error of y: the variance of the share over units (opening pairs or openings), at least a quarter of the
   binomial variance; a totals-only edge (no result file) gets the binomial variance. se = (400 / ln 10) sqrt(var) /
   (p (1 - p)), the delta method.
3. Weighted least squares of y on (rating of first player - rating of second), weights 1 / (se^2 + tau^2), the anchor's
   rating fixed at 1000.
4. tau (between-edge standard deviation, Elo) by Paule-Mandel: the tau at which the weighted residual sum Q equals
   its degrees of freedom (edges - rated configs); tau = 0 if Q <= df at tau = 0. 95 % interval of tau by the Q
   profile: the tau values at which Q equals the 2.5 % and 97.5 % chi-square quantiles of df.
5. Half-width of a rating or a difference: t(df, 0.975) times its standard error (Knapp-Hartung quantile).
6. Check: every edge whose removal leaves all configs connected to the anchor is left out once and predicted from
   the rest at the same tau; it is covered if |y - prediction| <= 1.96 sqrt(var(prediction) + se^2 + tau^2). The
   fixed-effect check does the same with tau = 0.

Limits: a normal random effect per edge, the same tau for every edge type, no seat factors for three players (every
opening is played in all seats), logit scale.

Exit codes: 0 written, 1 fit error (broken data, disconnected graph, table edge), 2 wrong call.
"""
import argparse
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fit  # noqa: E402  (the sibling scripts/elo/fit.py: edge reader, configs, anchors, incomplete gamma)

K = 400.0 / math.log(10.0)
METHOD = "re-nma-pm-kh"
ANCHOR_ELO = 1000.0
LEVEL_NODES = (1000, 5000, 20000, 100000, 500000)       # app levels 1 to 5 (web/src/skill/describe.ts)
# energy table of the Info part (web/src/about/data/energy.json): row -> basis -> config of the scale 2p-adj-r5
ENERGY_SCALE = "2p-adj-r5"
ENERGY_ROWS = {
    "small-gen0": {"sims": "net:r5-small-gen0@s200", "time": "net:r5-small-gen0@s1000"},
    "small-gen9": {"sims": "net:r5-small-gen9@s200", "time": "net:r5-small-gen9@s1000"},
    "large-gen0": {"sims": "net:r5-large-gen0@s200", "time": "net:r5-large-gen0@s200"},
    "large-gen10": {"sims": "net:r5-large-gen10@s200", "time": "net:r5-large-gen10@s200"},
}


class EdgeStat:
    """One edge reduced to its observed Elo difference y (first player minus second) and its standard error."""
    __slots__ = ("id", "players", "kind", "n", "pts", "share", "y", "se")

    def __init__(self, eid, players, y, se, kind="pair", n=0, pts=0.0):
        self.id, self.players, self.kind, self.n, self.pts = eid, list(players), kind, n, pts
        self.share = pts / n if n else float("nan")
        self.y, self.se = y, se


def edge_stat(edge):
    """fit.Edge -> EdgeStat (model steps 1 and 2)."""
    if edge.kind == "table":
        raise fit.FitError(f"edge {edge.id}: kind 'table' has no edge-level form in the random-effects fit")
    prior = 0.5 if edge.kind == "pair" else 1.0 / 3.0
    if edge.file is None:                    # totals only: binomial
        t = edge.totals
        n = int(t["wins"]) + int(t["draws"]) + int(t["losses"])
        pts = int(t["wins"]) + 0.5 * int(t["draws"])
        p = (pts + prior) / (n + 1)
        var = p * (1 - p) / n
    else:
        units = []
        for u in edge.units.values():
            if edge.kind == "pair":
                units.append((u["cells"][0][1][0], u["games"]))
            else:                            # one-vs-two: the candidate's weight over its three seats
                units.append((sum(w[0] for _cell, w in u["cells"]), u["games"]))
        n = sum(g for _x, g in units)
        pts = sum(x for x, _g in units)
        p = (pts + prior) / (n + 1)
        m = len(units)
        mu = pts / n
        if m > 1:
            var = sum((x - g * mu) ** 2 for x, g in units) / (m - 1) * m / (n * n)
        else:
            var = 0.0
        var = max(var, p * (1 - p) / n * 0.25)
    ratio = p / (1 - p) if edge.kind == "pair" else 2 * p / (1 - p)
    return EdgeStat(edge.id, edge.players, K * math.log(ratio), K * math.sqrt(var) / (p * (1 - p)), edge.kind, n, pts)


# ------------------------------------------------------------------------------------------------------- statistics

def chi2_cdf(x, k):
    return 1.0 - fit._gammq(k / 2.0, x / 2.0)


def chi2_quantile(q, k):
    lo, hi = 0.0, max(10.0, 10.0 * k)
    while chi2_cdf(hi, k) < q:
        hi *= 2
    for _ in range(200):
        mid = (lo + hi) / 2
        if chi2_cdf(mid, k) < q:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def _betacf(a, b, x):
    """Continued fraction of the regularised incomplete beta function (modified Lentz)."""
    tiny = 1e-300
    qab, qap, qam = a + b, a + 1.0, a - 1.0
    c, d = 1.0, 1.0 - qab * x / qap
    d = tiny if abs(d) < tiny else d
    d = 1.0 / d
    h = d
    for m in range(1, 10000):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        d = tiny if abs(d) < tiny else d
        c = 1.0 + aa / c
        c = tiny if abs(c) < tiny else c
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        d = tiny if abs(d) < tiny else d
        c = 1.0 + aa / c
        c = tiny if abs(c) < tiny else c
        d = 1.0 / d
        de = d * c
        h *= de
        if abs(de - 1.0) < 1e-15:
            break
    return h


def betainc(a, b, x):
    if x <= 0:
        return 0.0
    if x >= 1:
        return 1.0
    lb = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log(1 - x)
    if x < (a + 1) / (a + b + 2):
        return math.exp(lb) * _betacf(a, b, x) / a
    return 1.0 - math.exp(lb) * _betacf(b, a, 1 - x) / b


def t_cdf(t, df):
    tail = 0.5 * betainc(df / 2.0, 0.5, df / (df + t * t))
    return 1.0 - tail if t >= 0 else tail


def t_quantile(q, df):
    lo, hi = -1000.0, 1000.0
    for _ in range(200):
        mid = (lo + hi) / 2
        if t_cdf(mid, df) < q:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


# ------------------------------------------------------------------------------------------------------------ model

class Fit:
    """Weighted least squares of one scale at a given tau^2."""

    def __init__(self, stats, anchor, tau2=0.0):
        ids = sorted({p for s in stats for p in s.players})
        if anchor not in ids:
            raise fit.FitError(f"anchor {anchor} plays in no edge")
        ids.remove(anchor)
        self.anchor, self.ids, self.tau2 = anchor, ids, tau2
        self.ix = {c: i for i, c in enumerate(ids)}
        k = len(ids)
        A = [[0.0] * k for _ in range(k)]
        b = [0.0] * k
        rows = []
        for s in stats:
            x = {}
            a, c = s.players[0], s.players[1]
            if a in self.ix:
                x[self.ix[a]] = 1.0
            if c in self.ix:
                x[self.ix[c]] = -1.0
            w = 1.0 / (s.se ** 2 + tau2)
            rows.append((x, w, s.y))
            for i, xi in x.items():
                b[i] += w * xi * s.y
                for j, xj in x.items():
                    A[i][j] += w * xi * xj
        C = fit.inverse(A) if k else []
        if C is None:
            raise fit.FitError("normal equations singular: the edges do not connect every config to the anchor")
        self.C = C
        self.theta = [sum(C[i][j] * b[j] for j in range(k)) for i in range(k)]
        self.Q = 0.0
        self.residuals = []
        for x, w, y in rows:
            f = sum(xi * self.theta[i] for i, xi in x.items())
            self.Q += w * (y - f) ** 2
            self.residuals.append(((y - f), (y - f) * math.sqrt(w)))
        self.df = len(stats) - k

    def rating(self, c):
        return ANCHOR_ELO + (self.theta[self.ix[c]] if c in self.ix else 0.0)

    def diff(self, a, b):
        """(rating a - rating b, its standard error)."""
        x = [0.0] * len(self.ids)
        if a in self.ix:
            x[self.ix[a]] += 1.0
        if b in self.ix:
            x[self.ix[b]] -= 1.0
        d = sum(x[i] * self.theta[i] for i in range(len(x)))
        var = sum(x[i] * self.C[i][j] * x[j] for i in range(len(x)) for j in range(len(x)))
        return d, math.sqrt(max(var, 0.0))


def paule_mandel(stats, anchor):
    """tau^2 at which Q(tau^2) = df; 0 if the fixed-effect Q is already at or below df."""
    g = lambda t2: (lambda r: r.Q - r.df)(Fit(stats, anchor, t2))   # noqa: E731
    if g(0.0) <= 0:
        return 0.0
    lo, hi = 0.0, 1e6
    for _ in range(80):
        mid = (lo + hi) / 2
        if g(mid) > 0:
            lo = mid
        else:
            hi = mid
    return hi


def tau_interval(stats, anchor, df):
    """95 % Q-profile interval of tau (Elo)."""
    def root(target):
        if Fit(stats, anchor, 0.0).Q <= target:
            return 0.0
        lo, hi = 0.0, 2000.0
        for _ in range(60):
            mid = (lo + hi) / 2
            if Fit(stats, anchor, mid * mid).Q > target:
                lo = mid
            else:
                hi = mid
        return hi
    return root(chi2_quantile(0.975, df)), root(chi2_quantile(0.025, df))


def connected(stats, players, anchor):
    comp, changed = {anchor}, True
    while changed:
        changed = False
        for s in stats:
            a, b = s.players[0], s.players[1]
            if (a in comp) != (b in comp):
                comp |= {a, b}
                changed = True
    return comp == players


def loo_coverage(stats, anchor, tau2):
    """Leave-one-edge-out 95 % prediction coverage at tau2 and at 0: (covered RE, covered FE, redundant edges)."""
    players = {p for s in stats for p in s.players}
    cov = cov0 = tot = 0
    for i, s in enumerate(stats):
        rest = stats[:i] + stats[i + 1:]
        if not connected(rest, players, anchor):
            continue
        tot += 1
        d, se = Fit(rest, anchor, tau2).diff(s.players[0], s.players[1])
        cov += abs(s.y - d) <= 1.96 * math.sqrt(se ** 2 + s.se ** 2 + tau2)
        d0, se0 = Fit(rest, anchor, 0.0).diff(s.players[0], s.players[1])
        cov0 += abs(s.y - d0) <= 1.96 * math.sqrt(se0 ** 2 + s.se ** 2)
    return cov, cov0, tot


class ScaleFit:
    """The published fit of one scale."""

    def __init__(self, scale, stats, anchor):
        self.scale, self.stats, self.anchor = scale, stats, anchor
        self.fixed = Fit(stats, anchor, 0.0)
        self.tau2 = paule_mandel(stats, anchor)
        self.re = Fit(stats, anchor, self.tau2)
        self.df = self.re.df
        if self.df < 1:
            raise fit.FitError(f"scale {scale}: {len(stats)} edges for {len(self.re.ids)} rated configs, no degree of "
                               "freedom for tau")
        self.tq = t_quantile(0.975, self.df)
        self.tau_ci = tau_interval(stats, anchor, self.df)

    def rating(self, c):
        """(Elo, 95 % half-width against the anchor)."""
        return self.re.rating(c), (0.0 if c == self.anchor else self.tq * self.re.diff(c, self.anchor)[1])

    def pair(self, a, b):
        d, se = self.re.diff(a, b)
        return d, self.tq * se

    def players(self):
        return sorted(self.re.ids + [self.anchor], key=lambda c: -self.re.rating(c))


# ---------------------------------------------------------------------------------------------------------- output

def display_elo(x):
    return int(math.floor(round(x) / 10.0 + 0.5) * 10)


def display_hw(x):
    return int(math.ceil(round(x) / 10.0) * 10)


def scale_json(sf, wanted_pairs, bt):
    cov, cov0, tot = loo_coverage(sf.stats, sf.anchor, sf.tau2)
    btp = {p["id"]: p for p in bt.get("scales", {}).get(sf.scale, {}).get("players", [])} if bt else {}
    players = []
    for c in sf.players():
        elo, hw = sf.rating(c)
        rec = {"id": c, "elo": round(elo), "hw95": round(hw), "method": METHOD if c != sf.anchor else "anchor"}
        if c in btp:
            b = btp[c]
            rec["btElo"] = round(b["elo"])
            rec["btHw95"] = round((b["ci95"][1] - b["ci95"][0]) / 2)
        players.append(rec)
    out = {"anchor": sf.anchor, "edges": len(sf.stats), "games": sum(s.n for s in sf.stats), "df": sf.df,
           "qFixed": round(sf.fixed.Q, 1), "tauElo": round(math.sqrt(sf.tau2)),
           "tauCi95": [round(sf.tau_ci[0]), round(sf.tau_ci[1])], "tQuantile": round(sf.tq, 4),
           "medianEdgeSe": round(sorted(s.se for s in sf.stats)[len(sf.stats) // 2]),
           "looCoverage": {"redundantEdges": tot, "randomEffects": cov, "fixedEffect": cov0},
           "players": players}
    pairs = []
    for a, b in wanted_pairs:
        if a not in sf.re.ix and a != sf.anchor or b not in sf.re.ix and b != sf.anchor:
            continue
        d, hw = sf.pair(a, b)
        rec = {"a": a, "b": b, "pooled": round(d), "pooledHw95": round(hw)}
        direct = [s for s in sf.stats if set(s.players[:2]) == {a, b}]
        if direct:
            s = direct[0]
            sign = 1 if s.players[0] == a else -1
            rec.update(direct=round(sign * s.y), directHw95=round(1.96 * s.se), directGames=s.n)
        pairs.append(rec)
    if pairs:
        out["pairs"] = pairs
    return out


def energy_rows(sf):
    rows = {}
    for row, bases in ENERGY_ROWS.items():
        rows[row] = {}
        for basis, c in bases.items():
            if c not in sf.re.ix:
                rows[row][basis] = {"config": c, "elo": None, "note": "not rated"}
                continue
            elo, hw = sf.rating(c)
            rows[row][basis] = {"config": c, "elo": round(elo), "hw95": round(hw), "displayElo": display_elo(elo),
                                "displayHw95": display_hw(hw)}
    return rows


def level_table(sf, configs):
    cfg = fit.scoped(configs, sf.scale)
    rated = set(sf.re.ids) | {sf.anchor}
    out = []
    for k, nodes in enumerate(LEVEL_NODES, 1):
        c = sorted(p for p in rated if cfg.get(p, {}).get("level") == str(k))
        if c:
            elo, hw = sf.rating(c[0])
            out.append({"level": k, "nodes": nodes, "config": c[0], "elo": round(elo), "hw95": round(hw)})
        else:
            out.append({"level": k, "nodes": nodes, "elo": None, "note": "no rated config of this level"})
    return out


def run(edge_paths, configs_path=None, anchors=(), pairs_path=None, bt_path=None, date=None, allow_partial=False):
    configs = fit.load_configs(configs_path)
    edges = fit.load_edges(edge_paths, allow_partial)
    anchor_of = fit.resolve_anchors(edges, configs, dict(a.split("=", 1) for a in anchors))
    wanted = []
    if pairs_path:
        for n, line in enumerate(Path(pairs_path).read_text(encoding="utf-8").splitlines(), 1):
            if line.strip() and not line.startswith("#"):
                parts = [x.strip() for x in line.split("\t")]
                if len(parts) != 2:
                    raise fit.FitError(f"{pairs_path}:{n}: expected a<TAB>b")
                wanted.append(tuple(parts))
    bt = json.loads(Path(bt_path).read_text(encoding="utf-8")) if bt_path else None
    out = {"schema": 1, "date": date, "method": METHOD, "anchorRule": fit.ANCHOR_RULE, "scales": {}, "levels": {}}
    fits = {}
    for scale, anchor in sorted(anchor_of.items()):
        stats = [edge_stat(e) for e in edges if e.scale == scale]
        fit.check_connected(scale, [e for e in edges if e.scale == scale])
        sf = ScaleFit(scale, stats, anchor)
        fits[scale] = sf
        out["scales"][scale] = scale_json(sf, wanted, bt)
        if configs:
            out["levels"][scale] = level_table(sf, configs)
    if ENERGY_SCALE in fits:
        out["energyTable"] = {"scale": ENERGY_SCALE, "rows": energy_rows(fits[ENERGY_SCALE])}
    return out, fits


def main(argv=None):
    ap = argparse.ArgumentParser(description="Elo study random-effects fit (see the docstring of this file).")
    ap.add_argument("edges", nargs="+")
    ap.add_argument("--configs")
    ap.add_argument("--anchor", action="append", default=[], help="SCALE=CONFIG")
    ap.add_argument("--pairs")
    ap.add_argument("--bt", help="ratings.json of fit.py, for the comparison columns btElo and btHw95")
    ap.add_argument("--date")
    ap.add_argument("--out")
    ap.add_argument("--allow-partial", action="store_true")
    try:
        args = ap.parse_args(argv)
    except SystemExit as ex:
        return ex.code if isinstance(ex.code, int) else 2
    for a in args.anchor:
        if "=" not in a:
            print(f"--anchor {a}: expected SCALE=CONFIG", file=sys.stderr)
            return 2
    try:
        out, _fits = run(args.edges, args.configs, args.anchor, args.pairs, args.bt, args.date, args.allow_partial)
    except fit.FitError as ex:
        print(f"fit error: {ex}", file=sys.stderr)
        return 1
    text = json.dumps(out, indent=1, ensure_ascii=False) + "\n"
    if args.out:
        fit.atomic_write(Path(args.out), text)
        print(f"wrote {args.out} ({len(out['scales'])} scales)", file=sys.stderr)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
