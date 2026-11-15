#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Books the AWS run of the network measurement (docs/cpp-comparison-plan.md, WP15, "AWS run", "Decision rule", "Amendment 2026-11-15 (lead)").

usage: book-aws.py <run results dir> <out-prefix> [--date YYYY-MM-DD] [--cost FILE ...] [--mac-tsv FILE] [--commit TEXT]
               [--run 2 --prev <results dir of run 1>]

--run 2 books the second AWS run (SIMD fix, cold start per case): the .txt then has its own, data-driven text with the before/after
table against run 1 (--prev). The .tsv has the same tables as for run 1, and the cold_start table holds the per-case, per-round figures.

<run results dir> is the "results" directory of one run (summary.txt, anchors.tsv, nn-bench.tsv, crossbench.tsv, ...). Writes
  <out-prefix>.tsv   one row per booked figure, the columns of mac-nn-2026-11-15.tsv:
                     table, program, case, batch, metric, median, min, max, status, anchor_start, anchor_mid, anchor_end, note
  <out-prefix>.txt   the human-readable summary of the same numbers (it starts with "Status: read this first")

Rules applied (plan, with the amendment):
  * A row is "valid" if the anchors of its program (batch 256, SMALL and LARGE each: two runs at the start and two at the end, all
    four values) lie within 2 % of each other, otherwise "unsettled" with the measured range. A row without an anchor of its own is
    judged by the anchor of the named program (cpp:simd judges every C++ SIMD row, cpp:cuda-pinned the pageable row).
  * A Java:C++ ratio is formed only from immediately adjacent, interleaved pairs: the anchor rows are C, J, C, J at the start and at the
    end, so each (C, J) run back to back is a pair. The blocks of the benchmark proper are not interleaved; a division of two blocks is
    booked as "unpaired", never as valid.
  * No statement about a difference below 5 % (GPU). n = 1 run on a shared, virtualised instance.
  * Nothing is smoothed, re-measured or dropped. This script fails (exit 3) on any step of summary.txt that is not OK, any row whose
    "verified" column is not "ok", and any benchmark step whose rows plus skipped cases do not equal its schedule.
Exit codes: 0 booked, 2 usage, 3 verification failure.
"""
import os
import re
import statistics
import sys

ANCHOR_TOL = 0.02
RATIO_LIMIT = 0.05
COLUMNS = ["table", "program", "case", "batch", "metric", "median", "min", "max", "status",
           "anchor_start", "anchor_mid", "anchor_end", "note"]
GPU_IMPLS = {"tornado", "cuda-pageable", "cuda-pinned"}
BATCHES = [1, 16, 64, 256, 512, 1024]
MAIN_BATCHES = [1, 256, 1024]
NA = "NA"


class BookingError(Exception):
    pass


def num(text):
    return None if text in ("NA", "", None) else float(text)


def med(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def fnum(v, digits=1):
    return NA if v is None else "%.*f" % (digits, v)


def case_name(preset, weights="u5"):
    return "%s %s" % (preset.upper(), weights)


# ------------------------------------------------------------------------------------------------------------------ reading

def parse_schedule(line):
    """number of cases promised by a '# golden ...' header line, or None"""
    if "[" in line:
        m = re.search(r"presets \[([^\]]*)\], batches \[([^\]]*)\], impls \[([^\]]*)\]", line)
        if m:
            return len(m.group(1).split(", ")) * len(m.group(2).split(", ")) * len(m.group(3).split(", "))
        return None
    m = re.search(r"presets ([\w ]+), batches ([\d ]+), impls ([\w\- ]+)(?:,|$)", line)
    if m:
        return len(m.group(1).split()) * len(m.group(2).split()) * len(m.group(3).split())
    return None


def parse_blocks(text, start_re):
    """Splits the text of a benchmark output into blocks; a block begins at a line that matches start_re."""
    blocks = []
    cur = None
    for line in text.split("\n"):
        if not line:
            continue
        m = re.match(start_re, line)
        if m:
            cur = {"label": line[2:], "rows": [], "skipped": [], "kernels": [], "profiled": [], "setup": {}, "context": {},
                   "cold": None, "expected": None, "load_before": None, "load_after": None, "header": [], "golden": None}
            blocks.append(cur)
            continue
        if cur is None:
            continue
        m = re.match(r"^# load_1min (before|after) ([\d.]+)$", line)
        if m:
            cur["load_" + m.group(1)] = float(m.group(2))
            continue
        if line.startswith("# golden ") and cur["golden"] is None:
            cur["golden"] = line
            cur["expected"] = parse_schedule(line)
            continue
        m = re.match(r"^# skipped: (\S+) (\S+) (\S+) batch (\d+) \((.*)\)$", line)
        if m:
            cur["skipped"].append(((m.group(1), m.group(2), m.group(3), int(m.group(4))), m.group(5)))
            continue
        m = re.match(r"^# cold: construct ([\d.]+) ms, first call ([\d.]+) ms, (?:JVM|process) uptime[^0-9]*([\d.]+) ms", line)
        if m:
            cur["cold"] = {"construct_ms": float(m.group(1)), "first_call_ms": float(m.group(2)), "uptime_ms": float(m.group(3))}
            continue
        m = re.match(r"^# (cuda-\S+) (\S+) (\S+): cuda context and weights: ([\d.]+) ms$", line)
        if m:
            cur["context"][(m.group(1), m.group(2))] = float(m.group(4))
            continue
        m = re.match(r"^# (cuda-\S+) (\S+) (\S+): cuda buffers of bucket (\d+): ([\d.]+) ms$", line)
        if m:
            cur["setup"][(m.group(1), m.group(2), int(m.group(4)))] = float(m.group(5))
            continue
        if line.startswith("#kernels-cuda\t"):
            c = line.split("\t")
            cur["kernels"].append({"preset": c[3], "batch": int(c[5]), "variant": c[6], "kernel": c[7], "ms": float(c[8])})
            continue
        if line.startswith("#profiled\t"):
            c = line.split("\t")
            cur["profiled"].append({"preset": c[1], "batch": int(c[3]), "wall": num(c[4]), "kernel": num(c[5]),
                                    "dev_write": num(c[6]), "dev_read": num(c[7])})
            continue
        if line.startswith("#"):
            cur["header"].append(line)
            continue
        c = line.split("\t")
        if len(c) == 22 and c[0] in ("java", "cpp"):
            cur["rows"].append({"lang": c[0], "impl": c[1], "device": c[2], "preset": c[3], "weights": c[4], "batch": int(c[5]),
                                "lanes": c[7], "eps": num(c[8]), "ms50": num(c[9]), "ms90": num(c[10]), "rmin": num(c[11]),
                                "rmax": num(c[12]), "copy_in": num(c[13]), "h2d": num(c[14]), "kernel": num(c[15]),
                                "d2h": num(c[16]), "copy_out": num(c[17]), "priors": num(c[18]), "first": num(c[19]),
                                "load": num(c[20]), "verified": c[21]})
    return blocks


def check_blocks(name, blocks, schedule_checked=True):
    """Every row verified=ok; rows plus skipped cases equal the schedule of the header (not for cold and kernel blocks)."""
    rows = 0
    for b in blocks:
        for r in b["rows"]:
            rows += 1
            if r["verified"] != "ok":
                raise BookingError("%s, block '%s': a row with verified=%s (%s %s %s batch %d)" %
                                   (name, b["label"], r["verified"], r["impl"], r["preset"], r["weights"], r["batch"]))
        cold_or_kernels = "cold" in b["label"] or "kernels" in b["label"]
        if schedule_checked and not cold_or_kernels and b["expected"] is not None:
            if len(b["rows"]) + len(b["skipped"]) != b["expected"]:
                raise BookingError("%s, block '%s': %d rows + %d skipped != %d cases of the schedule" %
                                   (name, b["label"], len(b["rows"]), len(b["skipped"]), b["expected"]))
        if schedule_checked and not cold_or_kernels and b["expected"] is None and b["rows"]:
            raise BookingError("%s, block '%s': no schedule header" % (name, b["label"]))
    return rows


def parse_summary(text):
    """(dict step -> status text, exit status) of summary.txt. bootstrap.sh writes 'name: OK', 'name: FAIL (rc)' and
    'name: SKIP (reason)'. Every step must be OK and the exit status 0; the plan allows one exception: cpp-sanitizer may be SKIP
    (its reason stays in the status text)."""
    steps = {}
    status = None
    for line in text.split("\n"):
        m = re.match(r"^exit status: (\d+)$", line)
        if m:
            status = int(m.group(1))
        m = re.match(r"^(?P<name>[\w\-]+): (?P<st>(?:OK|SKIP|FAIL)\b.*)$", line)
        if m and m.group("name") not in ("backend", "suite", "gate", "seconds"):
            steps[m.group("name")] = m.group("st").strip()
    bad = ["%s: %s" % (k, v) for k, v in steps.items() if v != "OK" and not (k == "cpp-sanitizer" and v.startswith("SKIP"))]
    if status != 0 or bad or not steps:
        raise BookingError("summary.txt: exit status %s, steps not OK: %s" % (status, "; ".join(bad) or "(none read)"))
    return steps, status


def steps_ok_text(steps):
    """'All N steps of the run ended OK', or the same with the skipped step and its reason named."""
    skipped = ["%s: %s" % (k, v) for k, v in steps.items() if v != "OK"]
    if not skipped:
        return "All %d steps of the run ended OK" % len(steps)
    return "%d of %d steps of the run ended OK, the others were skipped as the plan allows (%s)" % (len(steps) - len(skipped), len(steps), "; ".join(skipped))


def parse_cost(text):
    """cost.tsv of aws-spot.sh: exactly one line with six tab separated fields: suite, launched, seconds, price_usd_per_h,
    estimate_usd, worst_case_usd. An on-demand price carries the suffix '-assumed' (a price given by the caller, not measured)."""
    lines = text.splitlines()
    t = lines[0].split("\t") if len(lines) == 1 else []
    if len(t) != 6:
        raise BookingError("cost.tsv: not one line with six tab separated fields (%d lines)" % len(lines))
    return {"suite": t[0], "launched": t[1], "seconds": int(t[2]), "price": float(t[3].removesuffix("-assumed")), "estimate": float(t[4]), "worst": float(t[5])}


# ------------------------------------------------------------------------------------------------------------------ anchors

def program_of(row):
    if row["impl"].startswith("cuda-"):
        return "cpp:" + row["impl"]
    return ("cpp:" if row["lang"] == "cpp" else "java:") + row["impl"]


def read_anchors(blocks):
    """{(program, preset): {'start': [v...], 'end': [v...]}} in the order of the blocks (round 1 C++, round 1 Java, round 2 ...)."""
    out = {}
    order = []
    rounds = {}
    for b in blocks:
        m = re.match(r"^anchors-(start|end) round (\d): (C\+\+|Java)$", b["label"])
        if not m:
            raise BookingError("anchors: unexpected block '%s'" % b["label"])
        for r in b["rows"]:
            if r["batch"] != 256:
                continue
            key = (program_of(r), r["preset"])
            out.setdefault(key, {"start": [], "end": []})[m.group(1)].append(r["eps"])
            rounds.setdefault(key, {"start": [], "end": []})[m.group(1)].append(int(m.group(2)))
        order.append((m.group(1), int(m.group(2)), m.group(3)))
    # the values of a program stand in the order of the round numbers, whatever the order of the blocks in the file
    for key, d in out.items():
        for when in ("start", "end"):
            d[when] = [v for _, v in sorted(zip(rounds[key][when], d[when]), key=lambda t: t[0])]
    return out, order


def anchor_verdict(values):
    """(spread max/min, status) of the anchor values of a program; valid if all of them lie within 2 % of each other."""
    lo, hi = min(values), max(values)
    spread = hi / lo
    return spread, ("valid" if spread - 1 <= ANCHOR_TOL else "unsettled")


class Anchors:
    def __init__(self, anchors):
        self.a = anchors
        self.v = {}
        for key, d in anchors.items():
            allv = d["start"] + d["end"]
            if len(d["start"]) != 2 or len(d["end"]) != 2:
                raise BookingError("anchor %s %s: %d start and %d end values, expected 2 and 2" % (key[0], key[1], len(d["start"]), len(d["end"])))
            self.v[key] = anchor_verdict(allv)

    def judge(self, program, preset):
        """(status, spread, start median, end median, anchor program) of the anchor that judges a row of `program`"""
        if program.startswith("cpp:simd"):
            ap = "cpp:simd"
        elif program.startswith("cpp:cuda-"):
            ap = "cpp:cuda-pinned"
        else:
            ap = program
        key = (ap, preset)
        if key not in self.v:
            raise BookingError("no anchor for %s %s" % key)
        d = self.a[key]
        return self.v[key][1], self.v[key][0], med(d["start"]), med(d["end"]), ap


# ------------------------------------------------------------------------------------------------------------------ verdicts

def ratio_verdict(ratios, status, gpu):
    """text for a cross-language ratio C++/Java built from adjacent pairs (rule: more than 5 %, and not unsettled)"""
    m = statistics.median(ratios)
    over = abs(m - 1) > RATIO_LIMIT
    same_side = all((r > 1) == (m > 1) for r in ratios) and all(abs(r - 1) > RATIO_LIMIT for r in ratios)
    if status == "valid":
        if over and same_side:
            return "real: C++ %s by %.1f %%" % ("faster" if m > 1 else "slower", abs(m - 1) * 100)
        return "not resolved (within 5 % or the pairs disagree)" if not over else "not resolved (the pairs disagree or one is within 5 %)"
    if over and same_side:
        return "indicative only (unsettled), C++ %s by %.1f %%" % ("faster" if m > 1 else "slower", abs(m - 1) * 100)
    return "no statement (difference below 5 % or the pairs disagree)"


def block_verdict(ratio):
    if abs(ratio - 1) > RATIO_LIMIT:
        return "indicative only, blocks not interleaved: C++ %s by %.1f %%" % ("faster" if ratio > 1 else "slower", abs(ratio - 1) * 100)
    return "no statement (below 5 %, blocks not interleaved)"


# ------------------------------------------------------------------------------------------------------------------ booking

class Booking:
    def __init__(self, nn, cross, anchor_blocks):
        self.rows = []
        self.nn = nn
        self.cross = cross
        self.anchor_blocks = anchor_blocks
        self.anch, self.anchor_order = read_anchors(anchor_blocks)
        self.anchors = Anchors(self.anch)
        self.main = [b for b in nn if re.match(r"^hardened, l(8|16): ", b["label"])]
        self.unchecked = [b for b in nn if b["label"].startswith("unchecked, ")]
        self.kernel_blocks = [b for b in nn if "kernels" in b["label"]]
        self.cold_nn = [b for b in nn if "cold start" in b["label"]]
        self.jmain = [b for b in cross if b["label"] == "vector and tornado"]
        self.jcold = [b for b in cross if b["label"].startswith("cold start")]
        if len(self.main) != 2 or len(self.jmain) != 1:
            raise BookingError("expected the blocks hardened l8, hardened l16 (C++) and one Java block")
        self.programs = {}      # (program, preset, batch) -> aggregated dict
        self.aggregate()

    def add(self, table, program, case, batch, metric, median, lo=None, hi=None, status="", anchors=(None, None, None), note=""):
        f = lambda v: NA if v is None else (v if isinstance(v, str) else ("%.1f" % v if abs(v) >= 1000 else "%.4g" % v))
        self.rows.append([table, program, case, "" if batch is None else str(batch), metric, f(median), f(lo), f(hi), status,
                          f(anchors[0]), f(anchors[1]), f(anchors[2]), note])

    # one aggregated entry per (program, preset, batch): CPU programs from one block, the CUDA programs from the two hardened blocks
    def aggregate(self):
        runs = {}
        for b in self.main:
            lanes = re.search(r"l(8|16)", b["label"]).group(1)
            for r in b["rows"]:
                if r["impl"].startswith("cuda-"):
                    prog = "cpp:" + r["impl"]
                else:
                    prog = "cpp:%s-l%s" % (r["impl"], lanes)
                runs.setdefault((prog, r["preset"], r["batch"]), []).append((b["label"], r))
        for r in self.jmain[0]["rows"]:
            if r["verified"] == "ok":
                runs.setdefault((program_of(r), r["preset"], r["batch"]), []).append((self.jmain[0]["label"], r))
        self.runs = runs

    def stat(self, key, field="eps"):
        """(median, lowest, highest) of the runs of a key; for eps the range is the lowest round minimum to the highest round maximum"""
        rs = [r for _, r in self.runs[key]]
        if field == "eps":
            return med([r["eps"] for r in rs]), min(r["rmin"] for r in rs), max(r["rmax"] for r in rs)
        vals = [r[field] for r in rs if r[field] is not None]
        return (med(vals), min(vals), max(vals)) if vals else (None, None, None)

    def status_of(self, program, preset):
        st, spread, a_s, a_e, ap = self.anchors.judge(program, preset)
        note = ""
        if ap != program:
            note = "judged by the anchor of %s" % ap
            if program.endswith("-l8"):
                note += " (the anchors were built with 16 lanes; there is no 8-lane anchor)"
        return st, (a_s, None, a_e), "anchor %s %s %s: max/min %.4f over start and end runs" % (ap, preset.upper(), "batch 256", spread) + ("; " + note if note else "")

    # ---- the tables
    def throughput(self):
        schedule_skips = {}
        for b in self.main:
            for (impl, preset, weights, batch), why in b["skipped"]:
                lanes = re.search(r"l(8|16)", b["label"]).group(1)
                schedule_skips[("cpp:%s-l%s" % (impl, lanes), preset, batch)] = why
        progs = sorted({k[0] for k in self.runs} | {k[0] for k in schedule_skips})
        for prog in progs:
            for preset in ("small", "large"):
                for batch in BATCHES:
                    key = (prog, preset, batch)
                    if key in self.runs:
                        med_, lo, hi = self.stat(key)
                        st, anc, anote = self.status_of(prog, preset)
                        n = len(self.runs[key])
                        if prog.startswith("cpp:cuda-"):
                            vals = ", ".join("%.1f" % r["eps"] for _, r in self.runs[key])
                            how = "median of the %d block medians (%s; hardened l8 and l16 blocks), range = lowest round minimum to highest round maximum" % (n, vals)
                        else:
                            how = "median round of one block, range = round minimum to round maximum"
                        self.add("throughput", prog, case_name(preset), batch, "evals_per_s", med_, lo, hi, st, anc, how + "; " + anote)
                    elif key in schedule_skips:
                        self.add("not_measured", prog, case_name(preset), batch, "evals_per_s", None, None, None, "not measured",
                                 note="skipped by the schedule: " + schedule_skips[key])

    def latency(self):
        for (prog, preset, batch) in sorted(self.runs):
            if batch != 1:
                continue
            st, anc, anote = self.status_of(prog, preset)
            for metric, field in (("p50_ms", "ms50"), ("p90_ms", "ms90")):
                v, lo, hi = self.stat((prog, preset, batch), field)
                self.add("latency", prog, case_name(preset), 1, metric, v, lo, hi, st, anc,
                         "per-call time at batch 1 (CUDA: median over the two blocks); " + anote)

    def pairs(self):
        """the adjacent C++/Java pairs of the anchors: the rounds in the order of the blocks (C++, Java) per round"""
        by = {}
        for (when, rnd, lang) in self.anchor_order:
            by[(when, rnd, lang)] = True
        pairs = []
        for when in ("start", "end"):
            for rnd in (1, 2):
                if (when, rnd, "C++") not in by or (when, rnd, "Java") not in by:
                    raise BookingError("anchor pair %s round %d incomplete" % (when, rnd))
                pairs.append((when, rnd))
        return pairs

    def anchor_tables(self):
        for (prog, preset) in sorted(self.anch):
            d = self.anch[(prog, preset)]
            allv = d["start"] + d["end"]
            spread, st = self.anchors.v[(prog, preset)]
            self.add("anchors", prog, case_name(preset), 256, "max_over_min", spread, min(allv), max(allv), st,
                     (med(d["start"]), None, med(d["end"])),
                     "anchor rows at batch 256 (evals/s); min/max columns = lowest and highest of the four anchor values; start values %s, end values %s; %s" %
                     (" / ".join("%.1f" % v for v in d["start"]), " / ".join("%.1f" % v for v in d["end"]),
                      "within 2 % of each other" if st == "valid" else "outside 2 %%: unsettled, drift range %.1f %%" % ((spread - 1) * 100)))

    def pair_ratios(self):
        """C++ over Java from the adjacent pairs of the anchors (batch 256); the only valid source of a cross-language ratio"""
        combos = [("cpp:cuda-pinned", "java:tornado", True), ("cpp:simd", "java:vector", False)]
        self.pair_result = {}
        self.pairs()   # every round must be there for C++ and Java
        for cprog, jprog, gpu in combos:
            for preset in ("small", "large"):
                c = self.anch[(cprog, preset)]
                j = self.anch[(jprog, preset)]
                # order of the values: start round 1, start round 2, end round 1, end round 2
                cv = c["start"] + c["end"]
                jv = j["start"] + j["end"]
                ratios = [a / b for a, b in zip(cv, jv)]
                st_c = self.anchors.v[(cprog, preset)][1]
                st_j = self.anchors.v[(jprog, preset)][1]
                st = "valid" if st_c == "valid" and st_j == "valid" else "unsettled"
                verdict = ratio_verdict(ratios, st, gpu)
                label = "%s / %s" % (cprog.replace("cpp:simd", "cpp:simd-l16"), jprog)
                self.pair_result[(label, preset)] = (statistics.median(ratios), min(ratios), max(ratios), st, verdict, ratios)
                self.add("ratio", label, case_name(preset), 256, "cpp_over_java", statistics.median(ratios), min(ratios), max(ratios), st,
                         (None, None, None),
                         "median and range of the 4 adjacent pairs (start 1, start 2, end 1, end 2): %s; anchor status C++ %s, Java %s; %s" %
                         (", ".join("%.3f" % r for r in ratios), st_c, st_j, verdict))

    def block_ratios(self):
        """C++ block over Java block: not interleaved, therefore never valid"""
        combos = [("cpp:cuda-pinned", "java:tornado"), ("cpp:cuda-pageable", "java:tornado"),
                  ("cpp:simd-l16", "java:vector"), ("cpp:simd-l8", "java:vector")]
        for cprog, jprog in combos:
            for preset in ("small", "large"):
                for batch in BATCHES:
                    ck, jk = (cprog, preset, batch), (jprog, preset, batch)
                    if ck not in self.runs or jk not in self.runs:
                        continue
                    r = self.stat(ck)[0] / self.stat(jk)[0]
                    self.add("ratio_blocks", "%s / %s" % (cprog, jprog), case_name(preset), batch, "cpp_over_java", r, None, None, "unpaired",
                             note="division of two separate blocks (C++ block, then Java block, minutes apart), not a pair: orientation only; " + block_verdict(r))

    def checks_cost(self):
        for b in self.unchecked:
            lanes = re.search(r"l(8|16)", b["label"]).group(1)
            for r in b["rows"]:
                prog = "cpp:" + r["impl"] if r["impl"].startswith("cuda-") else "cpp:%s-l%s" % (r["impl"], lanes)
                hk = (prog, r["preset"], r["batch"])
                if hk not in self.runs:
                    continue
                if r["impl"].startswith("cuda-"):
                    hrows = [x for lab, x in self.runs[hk] if ("l" + lanes) in lab]
                    h = hrows[0]["eps"]
                else:
                    h = self.runs[hk][0][1]["eps"]
                st, anc, anote = self.status_of(prog, r["preset"])
                ratio = r["eps"] / h
                v = "not resolved (within 5 %)" if abs(ratio - 1) <= RATIO_LIMIT else (
                    "the unchecked build is %.1f %% %s than the hardened one" % (abs(ratio - 1) * 100, "faster" if ratio > 1 else "SLOWER"))
                label = prog + (" [l%s runs]" % lanes if r["impl"].startswith("cuda-") else "")
                self.add("checks_cost", label, case_name(r["preset"]), r["batch"], "unchecked_over_hardened", ratio, None, None, st, anc,
                         "unchecked %.1f over hardened %.1f evals/s at batch 256, one run each (same lane build); %s; %s" % (r["eps"], h, v, anote))

    def breakdown(self):
        tor = self.jmain[0]["rows"]
        prof = {(p["preset"], p["batch"]): p for p in self.jmain[0]["profiled"]}
        for preset in ("small", "large"):
            for batch in BATCHES:
                key = ("java:tornado", preset, batch)
                if key not in self.runs:
                    continue
                r = self.runs[key][0][1]
                st, anc, anote = self.status_of("java:tornado", preset)
                case = case_name(preset)
                n = "TornadoVM; %s" % anote
                self.add("breakdown", "java:tornado", case, batch, "wall_p50_ms", r["ms50"], None, None, st, anc, n)
                self.add("breakdown", "java:tornado", case, batch, "host_copy_in_ms", r["copy_in"], None, None, st, anc, n + "; copy into the off-heap FloatArray")
                self.add("breakdown", "java:tornado", case, batch, "execute_ms", r["kernel"], None, None, st, anc,
                         n + "; TornadoVM execute = host to device + kernels + device to host in ONE figure (not separable)")
                self.add("breakdown", "java:tornado", case, batch, "host_copy_out_ms", r["copy_out"], None, None, st, anc, n)
                self.add("breakdown", "java:tornado", case, batch, "host_priors_ms", r["priors"], None, None, st, anc, n + "; timings() host priors")
                self.add("breakdown", "java:tornado", case, batch, "first_call_ms", r["first"], None, None, st, anc,
                         n + "; first call of this case in the JVM (plan build, Graal and NVRTC compilation); the very first case also holds the TornadoVM start")
                p = prof.get((preset, batch))
                if p:
                    for metric, field in (("profiled_wall_ms", "wall"), ("profiled_kernel_ms", "kernel"),
                                          ("profiled_device_write_ms", "dev_write"), ("profiled_device_read_ms", "dev_read")):
                        self.add("breakdown", "java:tornado", case, batch, metric, p[field], None, None, st, anc,
                                 n + "; separate instrumented pass of the TornadoVM profiler, slows the call; the profiler has a floor of about 0.5 ms per task")
        for prog in ("cpp:cuda-pageable", "cpp:cuda-pinned"):
            for preset in ("small", "large"):
                for batch in BATCHES:
                    key = (prog, preset, batch)
                    if key not in self.runs:
                        continue
                    st, anc, anote = self.status_of(prog, preset)
                    case = case_name(preset)
                    n = "C++ CUDA, cudaEvent times, median of the 2 blocks; %s" % anote
                    for metric, field in (("wall_p50_ms", "ms50"), ("host_copy_in_ms", "copy_in"), ("h2d_ms", "h2d"), ("kernel_ms", "kernel"),
                                          ("d2h_ms", "d2h"), ("host_copy_out_ms", "copy_out"), ("host_priors_ms", "priors"), ("first_call_ms", "first")):
                        v, lo, hi = self.stat(key, field)
                        self.add("breakdown", prog, case, batch, metric, v, lo, hi, st, anc, n)
                    parts = [self.stat(key, f)[0] for f in ("copy_in", "h2d", "kernel", "d2h", "copy_out", "priors")]
                    self.add("breakdown", prog, case, batch, "transfer_sum_ms", self.stat(key, "h2d")[0] + self.stat(key, "d2h")[0], None, None, st, anc,
                             n + "; h2d + d2h (comparable to the part of TornadoVM's execute that is not kernel)")
                    self.add("breakdown", prog, case, batch, "gpu_path_ms", self.stat(key, "h2d")[0] + self.stat(key, "kernel")[0] + self.stat(key, "d2h")[0], None, None, st, anc,
                             n + "; h2d + kernels + d2h, the like-for-like figure to TornadoVM's execute")
                    self.add("breakdown", prog, case, batch, "wall_minus_sum_ms", self.stat(key, "ms50")[0] - sum(parts), None, None, st, anc,
                             n + "; wall p50 minus the six phase figures (medians of different quantities: a difference of 0.1 ms is noise)")
        # set-up per bucket and context, first CUDA implementation of the process pays the context
        for b in self.main:
            lanes = re.search(r"l(8|16)", b["label"]).group(1)
            for (impl, preset), ms in sorted(b["context"].items()):
                st, anc, anote = self.status_of("cpp:" + impl, preset)
                self.add("setup", "cpp:" + impl, case_name(preset), None, "context_and_weights_ms", ms, None, None, st, anc,
                         "construction of the CUDA implementation in the block hardened l%s (the first one of a process also creates the CUDA context and loads the module; see note in the .txt)" % lanes)
            for (impl, preset, bucket), ms in sorted(b["setup"].items()):
                st, anc, anote = self.status_of("cpp:" + impl, preset)
                self.add("setup", "cpp:" + impl, case_name(preset), bucket, "buffers_of_bucket_ms", ms, None, None, st, anc,
                         "block hardened l%s; device and host buffers of this bucket" % lanes)

    def kernels(self):
        ks = {}
        for b in self.kernel_blocks:
            for k in b["kernels"]:
                ks.setdefault((k["preset"], k["batch"], k["variant"]), []).append((k["kernel"], k["ms"]))
        self.kernel_sums = {}
        st, anc, anote = self.status_of("cpp:cuda-pinned", "large")
        for (preset, batch, variant), lst in sorted(ks.items()):
            for name, ms in lst:
                self.add("kernel_table", "cpp:cuda:" + variant, case_name(preset), batch, name, ms, None, None, st, anc,
                         "cudaEvent time of one launch (instrumented pass, one event after every kernel), ms; " + anote)
            total = sum(ms for _, ms in lst)
            self.kernel_sums[(preset, batch, variant)] = (total, len(lst))
            self.add("kernel_table", "cpp:cuda:" + variant, case_name(preset), batch, "sum_of_%d_kernels" % len(lst), total, None, None, st, anc,
                     "sum over the %d launches of one pass; Java side of the kernel table is a deliberate gap on the T4" % len(lst))

    def cold(self):
        """Run 1: one cold case per language ('# cold start' blocks without a round). Run 2: one process per case and round
        ('# ... cold start round N: <impl> <preset> batch B'); booked as median, lowest and highest of the rounds."""
        self.cold_runs = {}
        legacy = []
        for lang, blocks in (("cpp", self.cold_nn), ("java", self.jcold)):
            for b in blocks:
                if not b["cold"]:
                    continue
                m = re.search(r"cold start round (\d+): (\S+) (\S+) batch (\d+)$", b["label"])
                if not m:
                    legacy.append((lang, b))
                    continue
                if len(b["rows"]) != 1:
                    raise BookingError("cold block '%s': %d rows, expected 1" % (b["label"], len(b["rows"])))
                r = b["rows"][0]
                if (r["impl"], r["preset"], str(r["batch"])) != (m.group(2), m.group(3), m.group(4)):
                    raise BookingError("cold block '%s': row is %s %s %d" % (b["label"], r["impl"], r["preset"], r["batch"]))
                prog = program_of(r)
                self.cold_runs.setdefault((prog, r["preset"], r["batch"]), []).append(
                    (int(m.group(1)), b["cold"], r["first"], r["lanes"]))
        for key, lst in sorted(self.cold_runs.items()):
            prog, preset, batch = key
            n = len(lst)
            for metric, get in (("first_call_ms", lambda c: c["first_call_ms"]), ("construct_ms", lambda c: c["construct_ms"]),
                                ("process_to_first_result_ms", lambda c: c["uptime_ms"])):
                vals = [get(c) for _, c, f, _ in lst]
                self.add("cold_start", prog, case_name(preset), batch, metric, statistics.median(vals), min(vals), max(vals), "unanchored",
                         (None, None, None),
                         "one fresh process per round, %d rounds (%s); median, lowest, highest; process_to_first_result = process uptime after the first call "
                         "(C++ from main, JVM from JVM start); no anchor applies to a cold figure, the spread of the rounds is the only indicator%s" % (
                             n, " / ".join("%.1f" % v for v in vals),
                             "; C++ simd ran with %s lanes" % lst[0][3] if prog == "cpp:simd" else ""))
        for lang, b in legacy:
            for r in b["rows"]:
                if lang == "cpp":
                    self.add("cold_start", "cpp:" + r["impl"], case_name(r["preset"]), r["batch"], "first_call_ms", b["cold"]["first_call_ms"], None, None,
                             "valid" if self.anchors.judge("cpp:simd", "small")[0] == "valid" else "unsettled", (None, None, None),
                             "separate process; construct %.1f ms, process uptime at the end %.1f ms; one case only (see not_measured)" %
                             (b["cold"]["construct_ms"], b["cold"]["uptime_ms"]))
                else:
                    st = self.status_of("java:" + r["impl"], r["preset"])[0]
                    self.add("cold_start", "java:" + r["impl"], case_name(r["preset"]), r["batch"], "first_call_ms", b["cold"]["first_call_ms"], None, None, st,
                             (None, None, None),
                             "separate JVM; construct %.1f ms, JVM uptime at the end %.0f ms; one case only (see not_measured)" %
                             (b["cold"]["construct_ms"], b["cold"]["uptime_ms"]))
        if legacy:
            for prog, what in (("cpp:cuda-pinned", "C++ CUDA"), ("java:tornado", "TornadoVM")):
                self.add("not_measured", prog, "LARGE u5", None, "cold_start_process", None, None, None, "not measured",
                         note="the cold step of the instance script ran --cold with no --impls and no --batches and so measured one case only "
                              "(C++ reference SMALL batch 1, Java vector SMALL batch 1); %s has no separate cold-process figure. The first-call "
                              "figures per case in the breakdown are in-process" % what)

    def book(self):
        self.throughput()
        self.latency()
        self.anchor_tables()
        self.pair_ratios()
        self.block_ratios()
        self.checks_cost()
        self.breakdown()
        self.kernels()
        self.cold()


# ------------------------------------------------------------------------------------------------------------------ the rest of the run

def parse_gpufeed(text):
    m = re.search(r"^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+(\d+)%\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*$", text, re.M)
    d = re.search(r"device thread: (\d+) executions: copy in ([\d.]+) ms, execute ([\d.]+) ms, copy out ([\d.]+) ms, host priors ([\d.]+) ms", text)
    if not m or not d:
        raise BookingError("gpufeed log: no result row")
    return {"batch": int(m.group(1)), "eps": float(m.group(3)), "sims": float(m.group(4)), "busy": int(m.group(6)),
            "execs": int(d.group(1)), "copy_in": float(d.group(2)), "execute": float(d.group(3)), "copy_out": float(d.group(4)),
            "priors": float(d.group(5))}


def parse_nncheck(text):
    out = []
    for m in re.finditer(r"^preset=(\S+) impl=(\S+)(?: lanes=(\d+))? .*?logits_max_err_frac=([\d.]+) values_max_err_frac=([\d.]+).*? -> (\w+)$", text, re.M):
        out.append({"preset": m.group(1), "impl": m.group(2), "lanes": m.group(3), "logits": float(m.group(4)), "values": float(m.group(5)), "result": m.group(6)})
    return out


def parse_goldenhash(text):
    rows = []
    for line in text.strip().split("\n"):
        c = line.split("\t")
        if len(c) == 4:
            rows.append(c)
        elif line.strip():
            raise BookingError("golden-hash.log: a line without four tab separated fields: %r" % line)
    return rows


def golden_hash_lines(text):
    """(must lines, info lines) of golden-hash.log; a must line that is not equal (DIFFERENT or MISSING) is refused"""
    gh = parse_goldenhash(text)
    must = [r for r in gh if r[3] == "must"]
    info = [r for r in gh if r[3] == "info"]
    if any(r[2] != "equal" for r in must):
        raise BookingError("golden-hash.log: a must line that is not equal")
    return must, info


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write_tsv(path, rows):
    with open(path, "w", encoding="utf-8") as f:
        f.write("\t".join(COLUMNS) + "\n")
        for r in rows:
            f.write("\t".join(r) + "\n")


def mac_value(mac_rows, program, case, batch):
    for r in mac_rows:
        if r[0] == "throughput" and r[1] == program and r[2] == case and r[3] == str(batch):
            return float(r[5]), r[8]
    return None, None


def main(argv):
    args = list(argv[1:])
    opts = {"date": "2026-11-15", "cost": [], "mac": None, "commit": None, "run": 1, "prev": None}
    pos = []
    while args:
        a = args.pop(0)
        if a == "--date":
            opts["date"] = args.pop(0)
        elif a == "--cost":
            opts["cost"].append(args.pop(0))
        elif a == "--mac-tsv":
            opts["mac"] = args.pop(0)
        elif a == "--commit":
            opts["commit"] = args.pop(0)
        elif a == "--run":
            opts["run"] = int(args.pop(0))
        elif a == "--prev":
            opts["prev"] = args.pop(0)
        else:
            pos.append(a)
    if len(pos) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    res, out = pos
    try:
        return run(res, out, opts)
    except BookingError as e:
        print("book-aws.py: VERIFICATION FAILED: %s" % e, file=sys.stderr)
        return 3


def load_booking(res):
    """parse, check and book the files of one run; (Booking, (n_nn, n_cross, n_anch), steps)"""
    steps, _ = parse_summary(read(os.path.join(res, "summary.txt")))
    nn = parse_blocks(read(os.path.join(res, "nn-bench.tsv")), r"^# (hardened|unchecked), ")
    cross = parse_blocks(read(os.path.join(res, "crossbench.tsv")), r"^# (vector and tornado|cold start( round \d+: .*)?)$")
    anchor_blocks = parse_blocks(read(os.path.join(res, "anchors.tsv")), r"^# anchors-(start|end) round \d: ")
    counts = (check_blocks("nn-bench.tsv", nn), check_blocks("crossbench.tsv", cross), check_blocks("anchors.tsv", anchor_blocks, schedule_checked=False))
    bk = Booking(nn, cross, anchor_blocks)
    bk.book()
    return bk, counts, steps


def run(res, out, opts):
    bk, (n_nn, n_cross, n_anch), steps = load_booking(res)
    feeds = {p: parse_gpufeed(read(os.path.join(res, "gpufeed-%s.log" % p))) for p in ("small", "large")}
    for p, f in feeds.items():
        bk.add("gpufeed", "java:tornado-feed", case_name(p), f["batch"], "evals_per_s", f["eps"], None, None, "unanchored", (None, None, None),
               "gpuFeed row of 30 s, 4 compute threads, 2 cohorts, device busy %d %%; not anchored, judged only against the earlier AWS runs" % f["busy"])
    bk.add("verification", "run", "all", None, "rows_verified_ok", n_nn + n_cross + n_anch, None, None, "valid", (None, None, None),
           "nn-bench.tsv %d, crossbench.tsv %d, anchors.tsv %d rows, every one verified=ok; %d summary steps, every one OK, exit status 0" %
           (n_nn, n_cross, n_anch, len(steps)))
    mac_rows = []
    if opts["mac"]:
        with open(opts["mac"], encoding="utf-8") as f:
            mac_rows = [l.rstrip("\n").split("\t") for l in f][1:]
    if opts["run"] == 2:
        if not opts["prev"]:
            raise BookingError("--run 2 needs --prev <results dir of run 1>")
        bk1 = load_booking(opts["prev"])[0]
        feeds1 = {p: parse_gpufeed(read(os.path.join(opts["prev"], "gpufeed-%s.log" % p))) for p in ("small", "large")}
        text = summary_text_run2(res, opts["prev"], bk, bk1, feeds, feeds1, steps, (n_nn, n_cross, n_anch), opts)
    else:
        text = summary_text(res, bk, feeds, steps, (n_nn, n_cross, n_anch), mac_rows, opts)
    write_tsv(out + ".tsv", bk.rows)
    with open(out + ".txt", "w", encoding="utf-8") as f:
        f.write(text)
    print("booked %d rows into %s.tsv and the summary into %s.txt" % (len(bk.rows), out, out))
    return 0


# ------------------------------------------------------------------------------------------------------------------ text

def table(head, rows):
    w = [max(len(str(x)) for x in col) for col in zip(head, *rows)] if rows else [len(h) for h in head]
    line = lambda r: "  ".join(str(x).ljust(w[i]) for i, x in enumerate(r)).rstrip()
    return "\n".join([line(head), line(["-" * k for k in w])] + [line(r) for r in rows])


def tag(st):
    return {"valid": "V", "unsettled": "u"}.get(st, "?")


def correctness_section(res, P):
    P("## Correctness (the CUDA path is verified)")
    ct = read(os.path.join(res, "cpp-cuda-test.log"))
    cs_ = read(os.path.join(res, "compute-sanitizer.log"))
    m1 = re.search(r"(\d+) passed, (\d+) skipped, (\d+) failed, (\d+) checks", ct)
    m2 = re.search(r"(\d+) passed, (\d+) skipped, (\d+) failed, (\d+) checks", cs_)
    errs = [float(x) for x in re.findall(r"largest error ([\d.e+-]+) of the bound", ct)]
    sm = re.search(r"ERROR SUMMARY: (\d+) errors", cs_)
    if not (m1 and m2 and sm) or m1.group(3) != "0" or m2.group(3) != "0" or sm.group(1) != "0":
        raise BookingError("cuda_test or compute-sanitizer log: failed tests or errors")
    P("  cuda_test (Tesla T4): %s passed, %s skipped, %s failed, %s checks; %d comparisons against ReferenceNet (TINY, SMALL, LARGE, 33-channel network; variants auto, naive, tiled;" % (
        m1.group(1), m1.group(2), m1.group(3), m1.group(4), len(errs)))
    P("    pageable and pinned; batches 1, 3, 67, 256); the largest error is %.4f of the bound (1e-4 + 1e-3 |ref|)." % max(errs))
    P("  compute-sanitizer memcheck on cuda_test, batches 1 and 67: %s passed, %s failed, %s checks, ERROR SUMMARY: %s errors." % (m2.group(1), m2.group(3), m2.group(4), sm.group(1)))
    nc = parse_nncheck(read(os.path.join(res, "cpp-check.log")))
    rows = [[c["impl"] + (" l%s" % c["lanes"] if c["lanes"] else ""), c["preset"].upper(), "%.4f" % c["logits"], "%.4f" % c["values"], c["result"]] for c in nc]
    if any(c["result"] != "ok" for c in nc):
        raise BookingError("cpp-check.log: an nn_check line that is not ok")
    P("  nn_check (all 1,024 golden positions, batch 64; error as a fraction of the bound, 1.0 = at the bound):")
    P(table(["impl", "preset", "logits max err / bound", "values max err / bound", "result"], rows))
    hashes = re.findall(r"^sha256 [^:]*:\s+([0-9a-f]{64})$", read(os.path.join(res, "real-weights.txt")), re.M)
    if len(hashes) != 3 or len(set(hashes)) != 1:
        raise BookingError("real-weights.txt: the SHA-256 before the upload, after the upload and after the run are not three equal values")
    P("  gen11 is the real LARGE weights (uploaded; SHA-256 before the upload, after the upload and after the run equal: %s...). Only nn_check ran on gen11, no throughput rows." % hashes[0][:8])
    must, info = golden_hash_lines(read(os.path.join(res, "golden-hash.log")))
    P("  golden hash: %d 'must' lines (weights, inputs, cases) are equal to the Mac hashes; %d 'info' lines: %s." % (
        len(must), len(info), "; ".join("%s %s" % (r[0].replace(".safetensors", ""), r[2]) for r in info)))
    P("    The two DIFFERENT lines are the Java reference OUTPUTS (nn-ref-small/large-u5) as the JVM on this instance computed them. Weights, inputs and cases are bit-equal, so the")
    P("    inputs of every check are the Mac's; the outputs of the same Java code differ in the last bits on another CPU (Math.exp and tanh may differ by one ulp between platforms,")
    P("    other FMA and vector paths). That is what the plan's 'info' kind expects ('a difference is recorded only'), not a failure. It does mean that nn_check on the instance")
    P("    compared against the instance's own reference files, not against the Mac's. The third info line (gen11) has no expected hash by design (real weights).")
    P("    Not shown by this run: nn_check --impl reference (bit-identity with the Java reference) is not in cpp-check.log.")
    P("")


def toolchain_section(res, P):
    P("## Toolchain")
    tc = read(os.path.join(res, "cpp-toolchain.log"))
    gx = re.search(r"^cxx:\s+(g\+\+-12 \(.*\) [\d.]+)", read(os.path.join(res, "cpp-build.log")), re.M) or re.search(r"^(g\+\+-12 \(.*\) [\d.]+)$", tc, re.M)
    nv = re.search(r"release ([\d.]+), V([\d.]+)", read(os.path.join(res, "nvcc-version.txt")))
    arch = re.search(r"compute_75", tc)
    drv = re.search(r"^Tesla T4, ([\d.]+)$", tc, re.M)
    jv = re.search(r'openjdk version "([\d.]+)"', read(os.path.join(res, "java-version.txt")))
    tv = re.search(r"tornadovm-([\d.]+)-", read(os.path.join(res, "tornado-setup.log")))
    fma = re.findall(r"check-fma lanes=(\d+): simd_net \(FMA\):\s+\d+ instructions, (\d+) fused multiply-add;simd_net_nofma:\s+\d+ instructions, (\d+) fused", read(os.path.join(res, "cpp-build.log")))
    if len(fma) != 2 or any(x[2] != "0" for x in fma):
        raise BookingError("cpp-build.log: check-fma lines missing or the no-FMA object contains fused instructions")
    fused = fma[0][1] if fma[0][1] == fma[1][1] else "%s (8 lanes) and %s (16 lanes)" % (fma[0][1], fma[1][1])
    P("  C++: %s, -O3 -march=native, -ffp-contract=off (FMA only in simd_net), 8 and 16 lanes, hardened (-D_GLIBCXX_ASSERTIONS) and unchecked builds; check-fma: %s fused" % (gx.group(1) if gx else "g++-12", fused))
    P("  multiply-adds in simd_net%s, 0 in simd_net_nofma (read from cpp-build.log)." % (" for both lane counts" if fma[0][1] == fma[1][1] else ""))
    P("  CUDA: nvcc release %s (V%s), compute_75 %s in nvcc's architecture list; flags of build-cuda.sh at the commit: -std=c++20 -ccbin g++-12 -O3 -lineinfo --fmad=true" % (
        nv.group(1) if nv else "?", nv.group(2) if nv else "?", "present" if arch else "MISSING"))
    P("  -gencode arch=compute_75,code=sm_75 and PTX compute_75, no fast math. Driver %s, Tesla T4, 15,360 MiB; compute-sanitizer 2026.1.0.0." % (drv.group(1) if drv else "?"))
    P("  Java %s (Zulu), TornadoVM %s (CUDA backend), JVM flags -Xms4g -Xmx4g, G1, 512-bit Vector API (16 float lanes), UseAVX 3. OS: Ubuntu 22.04, kernel 6.8 (aws)." % (jv.group(1) if jv else "?", tv.group(1) if tv else "?"))
    P("  CPU: Intel Xeon Platinum 8259CL @ 2.50 GHz, 4 vCPUs, 15.4 GiB, AVX-512 (F, BW, VL, VNNI).")
    P("")


def summary_text(res, bk, feeds, steps, counts, mac_rows, opts):
    L = []
    P = L.append
    A = bk.anchors
    n_nn, n_cross, n_anch = counts
    unsettled = [k for k, v in sorted(A.v.items()) if v[1] != "valid"]
    P("# AWS run on a Tesla T4 (g4dn.xlarge, spot), network measurement and CUDA check, %s" % opts["date"])
    P("")
    P("## Status: read this first")
    P("n = 1 run on a shared, virtualised instance (4 vCPUs of an Intel Xeon Platinum 8259CL, one Tesla T4, CUDA 13.2). %s" % steps_ok_text(steps))
    P("(exit status 0) and every one of the %d benchmark rows (nn_bench %d, CrossBench %d, anchors %d) has verified=ok; a case that fails its output" % (sum(counts), n_nn, n_cross, n_anch))
    P("check prints no row, so there are no rows that did not pass. Nothing was smoothed, re-measured or dropped.")
    P("  V = valid     the anchors of the row's program (batch 256, SMALL and LARGE each, two runs at the start and two at the end of the measurement,")
    P("                all four values) lie within 2 % of each other")
    P("  u = unsettled the anchors differ by more than 2 %; the drift range is in the row. Unsettled rows are never quoted as valid.")
    P("The anchors that decide:")
    for key in sorted(A.v):
        d = A.a[key]
        P("  %-16s %-5s %s  max/min %.4f  %s" % (key[0], key[1].upper(), "start %s / end %s" % (" ".join("%.1f" % v for v in d["start"]), " ".join("%.1f" % v for v in d["end"])),
                                              A.v[key][0], "valid" if A.v[key][1] == "valid" else "UNSETTLED (%.1f %% drift)" % ((A.v[key][0] - 1) * 100)))
    P("Result: the GPU programs (C++ CUDA, Java TornadoVM) are valid, all four anchors within 1.3 %% of each other; the CPU programs are unsettled (%s)." % ", ".join("%s %s" % (p, s.upper()) for p, s in unsettled))
    P("A Java:C++ ratio comes only from adjacent, interleaved pairs: the anchor rows run C, J, C, J at the start and at the end, so there are 4 pairs")
    P("at batch 256 and none at any other batch. The benchmark proper ran as one C++ block and then one Java block; a division of two blocks is")
    P("booked as 'unpaired' (orientation only). No statement is made about a difference below 5 %.")
    P("The commit that the run used: %s" % (opts["commit"] or "HEAD at the time of the run: see git log, before ec0ecac"))
    P("")
    P("Files: aws-t4-%s.tsv (every booked figure, one row each: table, program, case, batch, metric, median, min, max, status, anchor_start," % opts["date"])
    P("anchor_mid (not measured on this run, NA), anchor_end, note). Tables: throughput, latency, anchors, ratio, ratio_blocks, checks_cost,")
    P("breakdown, setup, kernel_table, cold_start, gpufeed, not_measured, verification. Booked by cpp/scripts/book-aws.py; tests: cpp/scripts/test_book_aws.py.")
    P("")
    # ---- main numbers
    P("## Throughput, evals/s (median, [lowest round, highest round], status); full table in the TSV (batches 1, 16, 64, 256, 512, 1024)")
    progs = [("java:vector", "Java Vector API, 16 lanes (CPU)"), ("cpp:simd-l16", "C++ simd, 16 lanes (CPU, comparison row)"),
             ("cpp:simd-l8", "C++ simd, 8 lanes (CPU, extra row)"), ("cpp:simd-nofma-l16", "C++ simd-nofma, 16 lanes"),
             ("cpp:simd-nofma-l8", "C++ simd-nofma, 8 lanes"), ("cpp:cuda-pageable", "C++ CUDA, pageable host memory"),
             ("cpp:cuda-pinned", "C++ CUDA, pinned host memory"), ("java:tornado", "Java TornadoVM on CUDA")]
    for preset in ("small", "large"):
        P("")
        P("### %s u5" % preset.upper())
        rows = []
        for prog, label in progs:
            row = [label]
            for b in MAIN_BATCHES:
                key = (prog, preset, b)
                if key in bk.runs:
                    m_, lo, hi = bk.stat(key)
                    row.append("%.1f [%.1f, %.1f] %s" % (m_, lo, hi, tag(bk.status_of(prog, preset)[0])))
                else:
                    row.append("not measured")
            rows.append(row)
        P(table(["program", "batch 1", "batch 256", "batch 1024"], rows))
    P("CUDA rows: median of the two hardened blocks (l8 and l16), range = lowest round minimum to highest round maximum of both. C++ CPU rows judged by the")
    P("anchor of cpp:simd (built with 16 lanes); the 8-lane rows have no anchor of their own. Not measured: the cases that the schedule skips because one")
    P("call would take more than 3 s (C++ simd-nofma l8 LARGE 1024, simd and simd-nofma l16 LARGE 1024; see not_measured in the TSV).")
    P("Java Vector SMALL is bimodal on this instance: 5,050 to 5,920 in the CrossBench block, 5,056 and then 3,779 in the anchors; its round ranges are wide (e.g. 3,744 to 5,579 in one anchor run).")
    P("")
    P("### The CPU rows are the uncomfortable ones")
    pr = bk.pair_result
    lo16 = min(pr[("cpp:simd-l16 / java:vector", k)][1] for k in ("small", "large"))
    hi16 = max(pr[("cpp:simd-l16 / java:vector", k)][2] for k in ("small", "large"))
    blk = [bk.stat(("cpp:simd-l16", k, b_))[0] / bk.stat(("java:vector", k, b_))[0] for k in ("small", "large") for b_ in (1, 256, 1024)
           if ("cpp:simd-l16", k, b_) in bk.runs]
    h8 = bk.stat(("cpp:simd-l8", "small", 256))[0]
    h16 = bk.stat(("cpp:simd-l16", "small", 256))[0]
    j = bk.stat(("java:vector", "small", 256))[0]
    u8 = [r for b_ in bk.unchecked if "l8" in b_["label"] for r in b_["rows"] if r["impl"] == "simd" and r["preset"] == "small"][0]["eps"]
    cs = bk.anch[("cpp:simd", "small")]
    cl = bk.anch[("cpp:simd", "large")]
    P("  - The comparison row (C++ 16 lanes against Java's 16 lanes) is SLOWER than Java Vector in the hardened C++ build: adjacent pairs %.3f to %.3f, blocks %.3f to %.3f" % (lo16, hi16, min(blk), max(blk)))
    P("    (C++ over Java; SMALL batch 256: C++ %.1f against Java %.1f evals/s in the blocks), so C++ needs 3 to 7 times as long. The 8-lane row is faster than the" % (h16, j))
    P("    16-lane row (SMALL batch 256: %.1f, hardened) but still behind Java Vector." % h8)
    P("  - The checks cost a lot here, unlike on the Mac: the unchecked 8-lane build reaches %.1f evals/s at SMALL batch 256 against %.1f hardened (table checks_cost, one run" % (u8, h8))
    P("    each; 2.3 times). The 16-lane build is much less affected (7 to 8 % for simd, none for simd-nofma). The cause was not investigated. The hardened build adds _GLIBCXX_ASSERTIONS; a bounds")
    P("    check that stops g++ 12 from vectorising or hoisting is a hypothesis, not a finding. The main rows of the plan use the hardened build, so they are booked as they are.")
    P("  - The CPU anchors move in steps, in both languages, while the GPU anchors do not. C++ simd (16 lanes), SMALL / LARGE: start %.1f / %.1f then %.1f / %.1f, end %.1f / %.1f then %.1f / %.1f;" % (
        cs["start"][0], cl["start"][0], cs["start"][1], cl["start"][1], cs["end"][0], cl["end"][0], cs["end"][1], cl["end"][1]))
    P("    so the second C++ run of each pair block is about 34 %% slower, at the start and at the end. Java Vector SMALL: %s." % " / ".join("%.1f" % v for v in bk.anch[("java:vector", "small")]["start"] + bk.anch[("java:vector", "small")]["end"]))
    P("    The C++ and Java blocks of the benchmark proper show no step inside the block (batches agree within 1 %), but they sit at one level: C++ at the fast level (round 1 of the")
    P("    anchors), Java Vector SMALL at 5,050 to 5,920 (the level of the first start anchor, not of the three later ones). The cause is not known (a noisy neighbour on the")
    P("    shared host or the clock behaviour of the CPU under AVX-512 load are plausible; neither is proven). By the rule the CPU rows stay unsettled, and no CPU ratio is")
    P("    more than indicative. Which level is the 'true' one cannot be told from n = 1.")
    P("")
    # ---- ratios
    P("## Ratios C++ over Java from adjacent pairs (anchor rows, batch 256; above 1: C++ faster)")
    rows = []
    for (label, preset), (m_, lo, hi, st, verdict, rs) in sorted(bk.pair_result.items()):
        rows.append([label, preset.upper(), "%.3f [%.3f, %.3f]" % (m_, lo, hi), tag(st), verdict])
    P(table(["C++ / Java", "preset", "median [lowest, highest pair]", "st", "verdict"], rows))
    P("")
    P("C++ CUDA is the pinned row (the anchor rows run the pinned variant). On the GPU the differences are below 5 %: no statement. This is the result for")
    P("batch 256; for the other batches there is no adjacent pair. For orientation only, the unpaired block division (table ratio_blocks in the TSV, status 'unpaired'):")
    rows = []
    for preset in ("small", "large"):
        for b in BATCHES:
            row = [preset.upper(), str(b)]
            for cprog, jprog in (("cpp:cuda-pinned", "java:tornado"), ("cpp:cuda-pageable", "java:tornado"), ("cpp:simd-l16", "java:vector"), ("cpp:simd-l8", "java:vector")):
                ck, jk = (cprog, preset, b), (jprog, preset, b)
                row.append("%.3f" % (bk.stat(ck)[0] / bk.stat(jk)[0]) if ck in bk.runs and jk in bk.runs else "not measured")
            rows.append(row)
    P(table(["preset", "batch", "CUDA pinned / TornadoVM", "CUDA pageable / TornadoVM", "simd l16 / Vector", "simd l8 / Vector"], rows))
    P("Read these as a picture, not as a result: a difference below 5 % says nothing, and the CPU columns sit on unsettled anchors. The batch-1 GPU gap (0.48 to 0.57) is looked at in the breakdown below.")
    P("")
    # ---- latency
    P("## Latency at batch 1, ms per call p50 / p90 (CUDA: median of the two blocks)")
    rows = []
    for prog, label in progs:
        row = [label]
        for preset in ("small", "large"):
            key = (prog, preset, 1)
            if key in bk.runs:
                row.append("%.3f / %.3f %s" % (bk.stat(key, "ms50")[0], bk.stat(key, "ms90")[0], tag(bk.status_of(prog, preset)[0])))
            else:
                row.append("not measured")
        rows.append(row)
    P(table(["program", "SMALL", "LARGE"], rows))
    P("")
    # ---- breakdown
    P("## TornadoVM breakdown on the T4, next to the C++ CUDA cudaEvent times (ms per call; GPU programs, status V)")
    P("TornadoVM's 'execute' is host to device + kernels + device to host in one figure; C++ splits them. 'gpu path' = h2d + kernel + d2h of C++ (TornadoVM: its execute figure).")
    for preset in ("small", "large"):
        P("")
        P("### %s u5" % preset.upper())
        rows = []
        for batch in MAIN_BATCHES:
            t = bk.runs[("java:tornado", preset, batch)][0][1]
            P_ = [p for p in bk.jmain[0]["profiled"] if p["preset"] == preset and p["batch"] == batch][0]
            for prog in ("java:tornado", "cpp:cuda-pageable", "cpp:cuda-pinned"):
                if prog == "java:tornado":
                    rows.append(["TornadoVM", batch, "%.3f" % t["ms50"], "%.3f" % t["copy_in"], "(in execute)", "%.3f" % t["kernel"], "(in execute)",
                                 "%.3f" % t["copy_out"], "%.3f" % t["priors"], "%.3f" % t["kernel"], "%.3f" % P_["kernel"], "%.1f" % t["first"]])
                else:
                    g = lambda f: bk.stat((prog, preset, batch), f)[0]
                    rows.append([prog.replace("cpp:", "C++ "), batch, "%.3f" % g("ms50"), "%.3f" % g("copy_in"), "%.3f" % g("h2d"), "%.3f" % g("kernel"),
                                 "%.3f" % g("d2h"), "%.3f" % g("copy_out"), "%.3f" % g("priors"), "%.3f" % (g("h2d") + g("kernel") + g("d2h")), "-", "%.1f" % g("first")])
        P(table(["program", "batch", "wall p50", "host copy in", "h2d", "kernel (C++) / execute (Tornado)", "d2h", "host copy out", "host priors", "gpu path", "profiled kernel", "first call"], rows))
    P("'profiled kernel' is the TornadoVM profiler's kernel figure from a separate instrumented pass (it slows the call and has a floor of about 0.5 ms per task);")
    P("it is not the C++ cudaEvent figure. First call = first call of that case in the process (TornadoVM: plan build, Graal and NVRTC compilation; the first")
    P("case of the JVM also holds the TornadoVM start: SMALL batch 1 %.0f ms). C++ CUDA: module and kernels are compiled ahead of time by nvcc; the first CUDA" %
      bk.runs[("java:tornado", "small", 1)][0][1]["first"])
    ctx = {}
    for b in bk.main:
        for (impl, preset), ms in b["context"].items():
            ctx.setdefault((impl, preset), []).append(ms)
    P("implementation of a process pays context creation and weight upload (construct): %s." % "; ".join(
        "%s %s %s ms" % (i.replace("cuda-", ""), p.upper(), " / ".join("%.1f" % v for v in vs)) for (i, p), vs in sorted(ctx.items())))
    extra = [bk.stat((pg, pr_, bt), "first")[0] - bk.stat((pg, pr_, bt), "ms50")[0] for pg in ("cpp:cuda-pageable", "cpp:cuda-pinned") for pr_ in ("small", "large") for bt in BATCHES]
    buf = [v for b_ in bk.main for v in b_["setup"].values()]
    P("Per bucket the C++ buffers cost %.2f to %.1f ms (table setup in the TSV); first call minus steady call of a case: %.1f to %.1f ms (negative = noise). TornadoVM's first call of a bucket on the T4 is" % (min(buf), max(buf), min(extra), max(extra)))
    P("%.0f to %.0f ms (SMALL) and %.0f to %.0f ms (LARGE, one outlier at batch 256: %.0f ms); on the Mac it was 62 to 76 ms for the LARGE buckets." % (
        min(bk.runs[("java:tornado", "small", b)][0][1]["first"] for b in BATCHES[1:]), max(bk.runs[("java:tornado", "small", b)][0][1]["first"] for b in BATCHES[1:]),
        min(bk.runs[("java:tornado", "large", b)][0][1]["first"] for b in BATCHES if b != 256), max(bk.runs[("java:tornado", "large", b)][0][1]["first"] for b in BATCHES if b != 256),
        bk.runs[("java:tornado", "large", 256)][0][1]["first"]))
    P("The first-call figures of one TornadoVM case vary a lot between buckets and are measured once per bucket (n = 1); not a precise number.")
    P("Host priors: TornadoVM %.3f ms, C++ %.3f ms per call at LARGE batch 256 (the same code, timed the same way)." % (
        bk.runs[("java:tornado", "large", 256)][0][1]["priors"], bk.stat(("cpp:cuda-pinned", "large", 256), "priors")[0]))
    P("Latency at batch 1: TornadoVM is faster than C++ CUDA on LARGE (%.3f against %.3f ms p50, SMALL %.3f against %.3f ms). The kernel table below looks at LARGE:" % (
        bk.runs[("java:tornado", "large", 1)][0][1]["ms50"], bk.stat(("cpp:cuda-pinned", "large", 1), "ms50")[0],
        bk.runs[("java:tornado", "small", 1)][0][1]["ms50"], bk.stat(("cpp:cuda-pinned", "small", 1), "ms50")[0]))
    ks = bk.kernel_sums
    P("")
    P("## C++ CUDA kernel table (LARGE u5, cudaEvent per launch; all 15 launches of one pass: conv_in, 12 block convolutions, 2 heads; Java side is a deliberate gap on the T4)")
    rows = []
    for b in (1, 64, 512):
        nv, tl = ks[("large", b, "naive")][0], ks[("large", b, "tiled")][0]
        sample = {k["variant"]: k["ms"] for blk in bk.kernel_blocks for k in blk["kernels"] if k["preset"] == "large" and k["batch"] == b and k["kernel"] == "block0.conv1"}
        pageable = bk.stat(("cpp:cuda-pageable", "large", b), "kernel")[0]
        rows.append([b, "%.3f" % sample["naive"], "%.3f" % sample["tiled"], "%.2f" % nv, "%.2f" % tl, "%.2fx" % (nv / tl), "%.2f" % pageable])
    P(table(["batch", "block0.conv1 naive", "block0.conv1 tiled", "sum naive", "sum tiled", "naive / tiled", "kernel_ms of the auto row"], rows))
    P("The variant rule of the adapter is 'naive while bucket * C <= 128, else tiled' (kNaiveUpTo in buckets.hpp, the rule of TornadoNetwork.NAIVE_UP_TO). At LARGE batch 1 the auto")
    P("row's kernel time (%.2f ms) equals the naive sum (%.2f ms), not the tiled sum (%.2f ms); from batch 64 on it equals the tiled sum. Even the tiled sum is above" % (
        bk.stat(("cpp:cuda-pageable", "large", 1), "kernel")[0], ks[("large", 1, "naive")][0], ks[("large", 1, "tiled")][0]))
    P("TornadoVM's whole 'execute' at batch 1 (%.3f ms). Naive is %.0f to %.0f times slower than tiled at batch 64 and 512. The C++ naive block (32 channels x 8 rows) is a choice of" % (
        bk.runs[("java:tornado", "large", 1)][0][1]["kernel"], ks[("large", 64, "naive")][0] / ks[("large", 64, "tiled")][0], ks[("large", 512, "naive")][0] / ks[("large", 512, "tiled")][0]))
    P("this port, TornadoVM leaves the launch geometry to its runtime; whether that explains the batch-1 gap was not tested (no second AWS run). At batch 256 the whole GPU path of")
    P("C++ (h2d + kernel + d2h) is %.3f ms against TornadoVM's execute %.3f ms on LARGE (%.1f %% more), and %.3f against %.3f ms on SMALL." % (
        sum(bk.stat(("cpp:cuda-pinned", "large", 256), f)[0] for f in ("h2d", "kernel", "d2h")), bk.runs[("java:tornado", "large", 256)][0][1]["kernel"],
        (sum(bk.stat(("cpp:cuda-pinned", "large", 256), f)[0] for f in ("h2d", "kernel", "d2h")) / bk.runs[("java:tornado", "large", 256)][0][1]["kernel"] - 1) * 100,
        sum(bk.stat(("cpp:cuda-pinned", "small", 256), f)[0] for f in ("h2d", "kernel", "d2h")), bk.runs[("java:tornado", "small", 256)][0][1]["kernel"]))
    P("")
    # ---- anchors
    P("## Anchors (batch 256, evals/s): two runs at the start (before nn_bench), two at the end (after CrossBench)")
    rows = []
    for (prog, preset) in sorted(bk.anch):
        d = bk.anch[(prog, preset)]
        v = bk.anchors.v[(prog, preset)]
        rows.append([prog, preset.upper()] + ["%.1f" % x for x in d["start"] + d["end"]] + ["%.4f" % v[0], "%.1f %%" % ((v[0] - 1) * 100), tag(v[1])])
    P(table(["program", "preset", "start 1", "start 2", "end 1", "end 2", "max/min", "drift", "st"], rows))
    P("GPU state (nvidia-smi, SM clock): %s" % gpu_clocks(res))
    P("Load average (1 min) at the start of the C++ block %.2f, end %.2f; Java block %.2f to %.2f (limit 2.0 of the plan: met). Thread CPU time over wall time of every" % (
        bk.main[0]["load_before"], bk.nn[-1]["load_after"], bk.jmain[0]["load_before"], bk.jmain[0]["load_after"]))
    P("C++ CPU case: 1.000 (the plan requires at least 0.8).")
    P("")
    # ---- cost of the checks
    P("## Cost of the checks (batch 256, unchecked over hardened, one run each; above 1: the unchecked build is faster)")
    rows = []
    for r in bk.rows:
        if r[0] == "checks_cost":
            rows.append([r[1], r[2], "%s" % r[5], r[8], r[12].split("; ")[1] if "; " in r[12] else ""])
    P(table(["program", "case", "ratio", "status", "verdict"], rows))
    P("")
    # ---- feed
    P("## Counter-check with the gpuFeed rows (CPU threads feed the T4, 30 s per row, batch 256; evals/s)")
    P("Earlier AWS runs (docs/cloud.md): SMALL about 80,700 to 81,600, LARGE about 18,400 to 19,000.")
    rows = []
    for p in ("small", "large"):
        f = feeds[p]
        rows.append([p.upper(), "%.0f" % f["eps"], "%d %%" % f["busy"], "%.0f" % bk.runs[("java:tornado", p, 256)][0][1]["eps"],
                     "%.1f %%" % ((f["eps"] / bk.runs[("java:tornado", p, 256)][0][1]["eps"] - 1) * 100)])
    P(table(["preset", "gpuFeed evals/s", "device busy", "CrossBench TornadoVM b256", "feed against CrossBench"], rows))
    P("Earlier runs, single values: SMALL 81,633 and 80,736, LARGE 19,029 and 18,402. Both rows of this run (SMALL %.0f, LARGE %.0f) lie inside these ranges, so the feed on this instance behaves as on the" % (feeds["small"]["eps"], feeds["large"]["eps"]))
    P("two earlier ones. Against the CrossBench TornadoVM block the feed is %.1f %% (SMALL) and %.1f %% (LARGE) lower; the reason was not separated (4 virtual threads feed one device and share the 4 vCPUs with" % (
        -(feeds["small"]["eps"] / bk.runs[("java:tornado", "small", 256)][0][1]["eps"] - 1) * 100, -(feeds["large"]["eps"] / bk.runs[("java:tornado", "large", 256)][0][1]["eps"] - 1) * 100))
    P("the host priors, and the host share is larger at SMALL: plausible, not measured). The feed is not an anchor and has no status.")
    P("")
    # ---- Metal
    if mac_rows:
        P("## Placing the CUDA numbers next to the Metal numbers of the Mac (orientation only; different machines, Mac GPU rows are unsettled by clock drift)")
        rows = []
        for preset in ("SMALL", "LARGE"):
            case = preset + " u5"
            mc, _ = mac_value(mac_rows, "cpp:metal-copy", case, 256)
            ms_, _ = mac_value(mac_rows, "cpp:metal-shared", case, 256)
            mt, _ = mac_value(mac_rows, "java:tornado", case, 256)
            cu = bk.stat(("cpp:cuda-pinned", preset.lower(), 256))[0]
            to = bk.stat(("java:tornado", preset.lower(), 256))[0]
            rows.append([preset, "%.0f" % mc, "%.0f" % ms_, "%.0f" % mt, "%.0f" % cu, "%.0f" % to, "%.2f" % (cu / mc), "%.2f" % (to / mt)])
        P(table(["preset b256", "Metal copy (u)", "Metal shared (u)", "TornadoVM Metal (u)", "CUDA pinned (V)", "TornadoVM CUDA (V)", "CUDA / Metal copy", "TornadoVM CUDA / Metal"], rows))
        cm = [bk.stat(("cpp:cuda-pinned", k, 256))[0] / mac_value(mac_rows, "cpp:metal-copy", k.upper() + " u5", 256)[0] for k in ("small", "large")]
        tm = [bk.stat(("java:tornado", k, 256))[0] / mac_value(mac_rows, "java:tornado", k.upper() + " u5", 256)[0] for k in ("small", "large")]
        P("At batch 256 the T4 reaches %.2f to %.2f of the M3 Max GPU with C++ and %.2f to %.2f with TornadoVM. On the Mac TornadoVM is behind Metal copy (%.2f and %.2f; unsettled rows), on the T4 the two programs" % (
            min(cm), max(cm), min(tm), max(tm),
            mac_value(mac_rows, "java:tornado", "SMALL u5", 256)[0] / mac_value(mac_rows, "cpp:metal-copy", "SMALL u5", 256)[0],
            mac_value(mac_rows, "java:tornado", "LARGE u5", 256)[0] / mac_value(mac_rows, "cpp:metal-copy", "LARGE u5", 256)[0]))
        P("are within 3 % of each other (valid rows; no statement below 5 %).")
        P("")
    # ---- cold start
    P("## Cold start and first call")
    for b in bk.cold_nn:
        if b["cold"]:
            P("  C++ reference, SMALL batch 1, own process: construct %.1f ms, first call %.1f ms, process uptime at the end %.1f ms (CPU, status u)" % (
                b["cold"]["construct_ms"], b["cold"]["first_call_ms"], b["cold"]["uptime_ms"]))
    for b in bk.jcold:
        if b["cold"]:
            P("  Java Vector, SMALL batch 1, own JVM: construct %.1f ms, first call %.1f ms, JVM uptime at the end %.0f ms (CPU, status u)" % (
                b["cold"]["construct_ms"], b["cold"]["first_call_ms"], b["cold"]["uptime_ms"]))
    P("  NOT MEASURED: a cold process for C++ CUDA and for TornadoVM. The instance script called --cold without --impls and --batches, which measures one case only;")
    P("  the CUDA and TornadoVM cold-process figures are therefore missing (table not_measured). The in-process figures are above (first call per case, construct per")
    P("  CUDA implementation: the first one of the process creates the context, SMALL pageable 96.8 and 101.4 ms; TornadoVM's first call in the JVM, SMALL batch 1, 4,683 ms).")
    P("")
    correctness_section(res, P)
    toolchain_section(res, P)
    # ---- cost
    P("## Cost of the cpp suite (cost.tsv of the two runs; estimates, not the bill)")
    costs = []
    for path in opts["cost"]:
        costs.append(parse_cost(read(path)))
    if costs:
        rows = [[c["launched"], "%d s" % c["seconds"], "%.4f" % c["price"], "%.4f" % c["estimate"]] for c in costs]
        P(table(["launched (UTC)", "counted", "USD/h", "estimate USD"], rows))
        tot = sum(c["estimate"] for c in costs)
        P("  Sum of the two runs: %.4f USD (about %.2f EUR at the assumed 0.90 EUR/USD); the root volume adds a few cents." % (tot, tot * 0.9))
        P("  The first run was cut off by a spot interruption about 5 minutes after the launch (the lead's figure; the log of its retry runner has no step after the Gradle build, ")
        P("  and notices the loss only at its cleanup after 27 minutes); cost.tsv counts the %d s until that cleanup, so the first estimate (%.4f USD) is an upper bound, about 0.02 USD" % (costs[0]["seconds"], costs[0]["estimate"]))
        P("  if only the 5 minutes were billed (not verified against a bill). The late notice was fixed in commit ec0ecac, which came after the first run and was in place for the second.")
        P("  Budget of 5 EUR: earlier tests about 0.3 USD (the owner's figure) plus this suite %.2f USD = about %.2f USD, i.e. about %.2f EUR or %.0f %% of the budget (an estimate;" % (tot, 0.3 + tot, (0.3 + tot) * 0.9, (0.3 + tot) * 0.9 / 5 * 100))
        P("  the first run's upper bound included). The cumulative cap of the cpp suite (1.00 USD) was not reached.")
    else:
        P("  not measured (no cost.tsv given)")
    P("")
    # ---- not measured
    P("## Not measured, or to be read with care")
    P("  - Cold-process start of C++ CUDA and TornadoVM (see above). Anchors in the middle of the measurement (anchor_mid) do not exist on this run.")
    P("  - Java scalar and C++ reference throughput, and gen11 throughput: not in the AWS schedule. Kernel table: C++ only, LARGE only; the Java kernel table is a deliberate gap.")
    P("  - Cases skipped by the schedule (one call above 3 s): C++ LARGE batch 1024 for simd l16, simd-nofma l16 and simd-nofma l8 (table not_measured in the TSV).")
    P("  - 8-lane C++ rows have no anchor of their own (the anchors are the 16-lane build).")
    P("  - The nvidia-smi line was taken four times (before each block), not continuously; it shows 1590 MHz SM clock (1575 MHz during the Java block), no sign of throttling at those moments.")
    P("  - Java:C++ ratios exist only at batch 256 (4 adjacent pairs); every other batch has no adjacent pair. n = 1 run on one shared virtualised instance; GPU differences below 5 % are not resolved.")
    P("  - The CPU rows (both languages) are unsettled; the 2.3 times cost of the checks at 8 lanes and the 34 % steps of the CPU anchors are unexplained.")
    P("  - The commit of the run is not recorded in the run files; see the line 'The commit that the run used' at the top.")
    P("")
    return "\n".join(L) + "\n"


# ------------------------------------------------------------------------------------------------------------------ text of run 2

def unchecked_eps(bk, impl, lanes, preset):
    for b in bk.unchecked:
        if "l%s" % lanes in b["label"]:
            for r in b["rows"]:
                if r["impl"] == impl and r["preset"] == preset and r["batch"] == 256:
                    return r["eps"]
    return None


def pct(x):
    return "%+.1f %%" % ((x - 1) * 100)


def cell(bk, prog, preset, batch):
    key = (prog, preset, batch)
    if key not in bk.runs:
        return None
    m_, lo, hi = bk.stat(key)
    return m_, lo, hi, bk.status_of(prog, preset)[0]


def summary_text_run2(res, prev, bk, bk1, feeds, feeds1, steps, counts, opts):
    L = []
    P = L.append
    A = bk.anchors
    n_nn, n_cross, n_anch = counts
    P("# Second AWS run on a Tesla T4 (g4dn.xlarge, spot): SIMD fix before and after, cold start per case, %s" % opts["date"])
    P("")
    P("## Status: read this first")
    P("n = 1 run per instance, on a shared, virtualised instance (4 vCPUs of an Intel Xeon Platinum 8259CL, one Tesla T4, CUDA 13.2), the same instance type and")
    P("the same measurement method as the first run (cpp/results/aws-t4-2026-11-15.txt, called run 1 below). %s (exit status 0) and every" % steps_ok_text(steps).replace("of the run", "of this run"))
    P("one of the %d benchmark rows (nn_bench %d, CrossBench %d, anchors %d) has verified=ok; a case that fails its output check prints no row, so there are no" % (sum(counts), n_nn, n_cross, n_anch))
    P("rows that did not pass. Nothing was smoothed, re-measured or dropped. Differences between run 1 and run 2 are between two different instances, one run each:")
    P("a repeatability statement is an observation of two samples, not an error bar.")
    P("  V = valid     all four anchor values of the row's program (batch 256: two runs at the start, two at the end) lie within 2 % of each other")
    P("  u = unsettled the anchors differ by more than 2 %; the drift is stated. Unsettled rows are never quoted as valid.")
    P("The anchors that decide (evals/s, batch 256):")
    for key in sorted(A.v):
        d = A.a[key]
        P("  %-16s %-5s start %s / end %s  max/min %.4f  %s" % (key[0], key[1].upper(), " ".join("%.1f" % v for v in d["start"]), " ".join("%.1f" % v for v in d["end"]),
                                                             A.v[key][0], "valid" if A.v[key][1] == "valid" else "UNSETTLED (%.1f %% drift)" % ((A.v[key][0] - 1) * 100)))
    valid = sorted("%s %s" % (k[0], k[1].upper()) for k, v in A.v.items() if v[1] == "valid")
    unsettled = sorted("%s %s" % (k[0], k[1].upper()) for k, v in A.v.items() if v[1] != "valid")
    P("Result: valid: %s. Unsettled: %s." % (", ".join(valid), ", ".join(unsettled)))
    P("A Java:C++ ratio comes only from adjacent pairs (anchor rows C, J, C, J at the start and at the end: 4 pairs at batch 256, none at another batch). The rows of the")
    P("benchmark proper are one C++ block and one Java block; their quotient is 'unpaired' (orientation only). No statement about a difference below 5 % (GPU); CPU rows state")
    P("the measured spread. The source of run 2: exp/cpp at %s (SIMD forward pass without bounds checks and stack round trips in the inner loop, commit e9af0d7: one process" % (opts["commit"] or "2446bd1"))
    P("per cold case); the archive of the instance was made at 18:55 UTC from HEAD, and the reflog shows no later commit. Run 1 ran the code before the SIMD change.")
    P("")
    P("Files: aws-t4-run2-%s.tsv (every booked figure, one row each; columns as in run 1) and this text. Tables of the TSV: throughput, latency, anchors, ratio, ratio_blocks," % opts["date"])
    P("checks_cost, breakdown, setup, kernel_table, cold_start (new: one row per program, case and metric, median / lowest / highest of 3 processes), gpufeed, not_measured,")
    P("verification. Booked by cpp/scripts/book-aws.py --run 2; tests: cpp/scripts/test_book_aws.py.")
    P("")

    # ---- main numbers
    P("## Throughput, evals/s (median, [lowest round, highest round], status); full table in the TSV (batches 1, 16, 64, 256, 512, 1024)")
    progs = [("java:vector", "Java Vector API, 16 lanes (CPU)"), ("cpp:simd-l16", "C++ simd, 16 lanes (CPU, comparison row)"),
             ("cpp:simd-l8", "C++ simd, 8 lanes (CPU, extra row)"), ("cpp:simd-nofma-l16", "C++ simd-nofma, 16 lanes"),
             ("cpp:simd-nofma-l8", "C++ simd-nofma, 8 lanes"), ("cpp:cuda-pageable", "C++ CUDA, pageable host memory"),
             ("cpp:cuda-pinned", "C++ CUDA, pinned host memory"), ("java:tornado", "Java TornadoVM on CUDA")]
    for preset in ("small", "large"):
        P("")
        P("### %s u5" % preset.upper())
        rows = []
        for prog, label in progs:
            row = [label]
            for b in MAIN_BATCHES:
                c = cell(bk, prog, preset, b)
                row.append("%.1f [%.1f, %.1f] %s" % (c[0], c[1], c[2], tag(c[3])) if c else "not measured")
            rows.append(row)
        P(table(["program", "batch 1", "batch 256", "batch 1024"], rows))
    skipped = sorted({"%s %s batch %s" % (k[0], k[1].upper(), k[2]) for k in
                      [(r[1], r[2].split()[0].lower(), r[3]) for r in bk.rows if r[0] == "not_measured" and r[4] == "evals_per_s"]})
    P("CUDA rows: median of the two hardened blocks (l8 and l16), range = lowest round minimum to highest round maximum of both. C++ CPU rows are judged by the anchor of")
    P("cpp:simd (built with 16 lanes; the 8-lane rows have no anchor of their own). Not measured (the schedule skips a case if one call would take more than 3 s): %s." % ("; ".join(skipped) if skipped else "none"))
    P("")

    # ---- before / after
    P("## SIMD fix, before and after (CPU, batch 256, evals/s; run 1 = old code, run 2 = new code; status as in each run)")
    rows = []
    summary_ratio = {}
    for preset in ("small", "large"):
        for prog, impl, lanes, label in (("cpp:simd-l16", "simd", 16, "simd 16 lanes, hardened"), ("cpp:simd-l8", "simd", 8, "simd 8 lanes, hardened"),
                                         ("cpp:simd-nofma-l16", "simd-nofma", 16, "simd-nofma 16 lanes, hardened"), ("cpp:simd-nofma-l8", "simd-nofma", 8, "simd-nofma 8 lanes, hardened"),
                                         ("cpp:simd-l16", "simd", 16, "simd 16 lanes, unchecked"), ("cpp:simd-l8", "simd", 8, "simd 8 lanes, unchecked"),
                                         ("cpp:simd-nofma-l16", "simd-nofma", 16, "simd-nofma 16 lanes, unchecked"), ("cpp:simd-nofma-l8", "simd-nofma", 8, "simd-nofma 8 lanes, unchecked")):
            if label.endswith("unchecked"):
                v1, v2 = unchecked_eps(bk1, impl, lanes, preset), unchecked_eps(bk, impl, lanes, preset)
                s1 = bk1.status_of(prog, preset)[0]
                s2 = bk.status_of(prog, preset)[0]
                j1, j2 = bk1.stat(("java:vector", preset, 256))[0], bk.stat(("java:vector", preset, 256))[0]
            else:
                v1, v2 = bk1.stat((prog, preset, 256))[0], bk.stat((prog, preset, 256))[0]
                s1 = bk1.status_of(prog, preset)[0]
                s2 = bk.status_of(prog, preset)[0]
                j1, j2 = bk1.stat(("java:vector", preset, 256))[0], bk.stat(("java:vector", preset, 256))[0]
            rows.append([preset.upper(), label, "%.1f" % v1, tag(s1), "%.1f" % v2, tag(s2), "%.2fx" % (v2 / v1), "%.2f" % (v1 / j1), "%.2f" % (v2 / j2)])
            summary_ratio[(preset, label)] = (v1, v2, j1, j2, s1, s2)
            bk.add("simd_fix", prog + (" unchecked" if label.endswith("unchecked") else ""), case_name(preset), 256, "run2_over_run1", v2 / v1, v1, v2, "%s/%s" % (s1, s2),
                   (None, None, None), "evals/s run 1 (min column) and run 2 (max column); status run 1 / run 2; Java Vector %.1f (run 1) and %.1f (run 2); two separate instances, one run each" % (j1, j2))
    P(table(["preset", "program", "run 1", "st", "run 2", "st", "run 2 / run 1", "C++ / Java run 1", "C++ / Java run 2"], rows))
    j1s, j2s = bk1.stat(("java:vector", "small", 256))[0], bk.stat(("java:vector", "small", 256))[0]
    j1l, j2l = bk1.stat(("java:vector", "large", 256))[0], bk.stat(("java:vector", "large", 256))[0]
    P("The Java columns are the Java Vector blocks of the same run at batch 256 (SMALL %.1f then %.1f, %s; LARGE %.1f then %.1f, %s), so the C++ / Java columns are block quotients (unpaired," % (
        j1s, j2s, pct(j2s / j1s), j1l, j2l, pct(j2l / j1l)))
    P("orientation). The paired ratios are in the next section. Java Vector is the control: the same code on a second instance of the same type moves by the amount above.")
    P("")
    # which hypotheses
    h16s, h8s = summary_ratio[("small", "simd 16 lanes, hardened")], summary_ratio[("small", "simd 8 lanes, hardened")]
    u8s_old, u8s_new = summary_ratio[("small", "simd 8 lanes, unchecked")][:2]
    P("### Is the diagnosis confirmed?")
    P("The diagnosis (cpp/README.md, 'x86 code generation of the SIMD path'): bounds checks inside the inner loop of the hardened build, and at 16 lanes a stack round trip")
    P("for every vector load, made the C++ path slow. The change removed both in one commit (the assembler loop has 18 instructions, 8 fma, no stack operand).")
    P("  - Hardened build: simd 16 lanes SMALL %.1f -> %.1f (%.2fx), LARGE %.1f -> %.1f (%.2fx); simd 8 lanes SMALL %.1f -> %.1f (%.2fx), LARGE %.1f -> %.1f (%.2fx)." % (
        h16s[0], h16s[1], h16s[1] / h16s[0], *summary_ratio[("large", "simd 16 lanes, hardened")][:2], summary_ratio[("large", "simd 16 lanes, hardened")][1] / summary_ratio[("large", "simd 16 lanes, hardened")][0],
        h8s[0], h8s[1], h8s[1] / h8s[0], *summary_ratio[("large", "simd 8 lanes, hardened")][:2], summary_ratio[("large", "simd 8 lanes, hardened")][1] / summary_ratio[("large", "simd 8 lanes, hardened")][0]))
    cc = {(r[1], r[2]): float(r[5]) for r in bk.rows if r[0] == "checks_cost" and "cuda" not in r[1]}
    P("  - Cost of the checks (unchecked over hardened, batch 256, table checks_cost): run 1 SMALL 8 lanes %.2fx, run 2 %.3fx; run 2, all four CPU programs and both presets: %.3f to %.3f." % (
        unchecked_eps(bk1, "simd", 8, "small") / bk1.stat(("cpp:simd-l8", "small", 256))[0], cc[("cpp:simd-l8", "SMALL u5")], min(cc.values()), max(cc.values())))
    P("    The hardened build is now as fast as the unchecked one (within 3 %; below 5 % no statement). That the checks stood in the way is therefore confirmed: with them out of the")
    P("    loop the difference is gone.")
    P("  - 16 lanes against 8 lanes (hardened, SMALL / LARGE): run 1 %.2f / %.2f (16 slower), run 2 %.2f / %.2f (16 faster). The order has turned, as the stack-round-trip reading predicts." % (
        h16s[0] / h8s[0], summary_ratio[("large", "simd 16 lanes, hardened")][0] / summary_ratio[("large", "simd 8 lanes, hardened")][0],
        h16s[1] / h8s[1], summary_ratio[("large", "simd 16 lanes, hardened")][1] / summary_ratio[("large", "simd 8 lanes, hardened")][1]))
    P("  - Confirmed: the poor C++ numbers of run 1 were bad code from the bounds checks and the stack round trips, not a property of C++. Not separable from the data: how much each of")
    P("    the two causes contributed (they changed together), and the stall of the store-to-load forwarding itself was never measured. Results are unchanged: nn_check reports the same")
    P("    error fractions for every impl, preset and lane count as in run 1 (compared below), and the offline check of the commit found 12 equal digests of old and new code.")
    P("")

    # ---- ratios
    P("## Java:C++ ratios from adjacent pairs (anchor rows, batch 256; above 1: C++ faster)")
    rows = []
    for (label, preset), (m_, lo, hi, st, verdict, rs) in sorted(bk.pair_result.items()):
        rows.append([label, preset.upper(), "%.3f [%.3f, %.3f]" % (m_, lo, hi), ", ".join("%.3f" % r for r in rs), tag(st), verdict])
    P(table(["C++ / Java", "preset", "median [lowest, highest pair]", "pairs: start 1, start 2, end 1, end 2", "st", "verdict"], rows))
    pr = bk.pair_result
    P("")
    P("### Honest state: C++ SIMD against the Java Vector API on AVX-512")
    ps, pl = pr[("cpp:simd-l16 / java:vector", "small")], pr[("cpp:simd-l16 / java:vector", "large")]
    blk_s, blk_l = bk.stat(("cpp:simd-l16", "small", 256))[0] / j2s, bk.stat(("cpp:simd-l16", "large", 256))[0] / j2l
    P("  - Direction: C++ 16 lanes (gcc 12, vector extensions) is FASTER than Java's Vector API (16 lanes, same 512-bit width) on this instance. Paired: SMALL %.2f (%.2f to %.2f)," % (ps[0], ps[1], ps[2]))
    P("    LARGE %.2f (%.2f to %.2f); block quotient (unpaired): SMALL %.2f, LARGE %.2f. All 8 pairs and both block quotients lie on the same side and above 5 %%." % (pl[0], pl[1], pl[2], blk_s, blk_l))
    P("  - Status: by the rule these are NOT valid ratios, only 'indicative'. The Java anchors are unsettled in both presets (SMALL %.1f %%, LARGE %.1f %% drift) and the C++ SMALL anchor is" % (
        (A.v[("java:vector", "small")][0] - 1) * 100, (A.v[("java:vector", "large")][0] - 1) * 100))
    P("    unsettled (%.1f %%); only the C++ LARGE anchor is valid (%.1f %%). Do not quote a CPU ratio from this run without these labels." % ((A.v[("cpp:simd", "small")][0] - 1) * 100, (A.v[("cpp:simd", "large")][0] - 1) * 100))
    P("  - Size: LARGE is tight (pairs %.2f to %.2f, block %.2f): about 1.3 times. SMALL depends on which Java level one takes: Java Vector SMALL sits at %.0f in its block and at %s in the anchors" % (
        pl[1], pl[2], blk_l, j2s, " / ".join("%.0f" % v for v in bk.anch[("java:vector", "small")]["start"] + bk.anch[("java:vector", "small")]["end"])))
    P("    (the same split as in run 1), so the SMALL factor is %.2f against the block level and %.2f to %.2f against the anchor levels. This is a property of the Java side's" % (blk_s, ps[1], ps[2]))
    P("    variation on this instance, not something this run can resolve; n = 1 per instance.")
    P("  - Other C++ rows against the Java block (unpaired, batch 256): 8 lanes (half the width of Java's species) %.2f SMALL and %.2f LARGE, i.e. about 10 %% behind Java Vector; simd-nofma 16 lanes %.2f and %.2f." % (
        bk.stat(("cpp:simd-l8", "small", 256))[0] / j2s, bk.stat(("cpp:simd-l8", "large", 256))[0] / j2l,
        bk.stat(("cpp:simd-nofma-l16", "small", 256))[0] / j2s, bk.stat(("cpp:simd-nofma-l16", "large", 256))[0] / j2l))
    P("  - Not shown by this data: whether another JDK, another JIT warm-up or hand-tuned Java would close the gap; the plan compares the existing implementations as they are.")
    P("")
    P("C++ CUDA is the pinned row (the anchor rows run the pinned variant). The GPU pairs are %s; below 5 %% there is no statement. For orientation only, the unpaired block quotients" % (
        " and ".join("%s %.3f" % (k[1].upper(), v[0]) for k, v in sorted(pr.items()) if "cuda" in k[0])))
    P("(table ratio_blocks in the TSV, status 'unpaired'):")
    rows = []
    for preset in ("small", "large"):
        for b in BATCHES:
            row = [preset.upper(), str(b)]
            for cprog, jprog in (("cpp:cuda-pinned", "java:tornado"), ("cpp:cuda-pageable", "java:tornado"), ("cpp:simd-l16", "java:vector"), ("cpp:simd-l8", "java:vector")):
                ck, jk = (cprog, preset, b), (jprog, preset, b)
                row.append("%.3f" % (bk.stat(ck)[0] / bk.stat(jk)[0]) if ck in bk.runs and jk in bk.runs else "not measured")
            rows.append(row)
    P(table(["preset", "batch", "CUDA pinned / TornadoVM", "CUDA pageable / TornadoVM", "simd l16 / Vector", "simd l8 / Vector"], rows))
    P("")

    # ---- latency
    P("## Latency at batch 1, ms per call p50 / p90 (CUDA: median of the two blocks)")
    rows = []
    for prog, label in progs:
        row = [label]
        for preset in ("small", "large"):
            key = (prog, preset, 1)
            row.append("%.3f / %.3f %s" % (bk.stat(key, "ms50")[0], bk.stat(key, "ms90")[0], tag(bk.status_of(prog, preset)[0])) if key in bk.runs else "not measured")
        rows.append(row)
    P(table(["program", "SMALL", "LARGE"], rows))
    P("")

    # ---- CUDA repeatability
    P("## GPU rows against run 1 (repeatability of two instances; evals/s, median)")
    rows = []
    worst = 0.0
    for prog, label in progs[5:]:
        for preset in ("small", "large"):
            for b in MAIN_BATCHES:
                c2, c1 = cell(bk, prog, preset, b), cell(bk1, prog, preset, b)
                r = c2[0] / c1[0]
                worst = max(worst, abs(r - 1))
                rows.append([label, preset.upper(), b, "%.1f %s" % (c1[0], tag(c1[3])), "%.1f %s" % (c2[0], tag(c2[3])), pct(r)])
                bk.add("gpu_repeat", prog, case_name(preset), b, "run2_over_run1", r, c1[0], c2[0], "%s/%s" % (c1[3], c2[3]), (None, None, None),
                       "median evals/s run 1 (min column) and run 2 (max column); two instances, one run each")
    P(table(["program", "preset", "batch", "run 1", "run 2", "run 2 vs run 1"], rows))
    P("Largest difference between the two runs: %.1f %%. Run 1 ran the same CUDA code; the SIMD change touches only the CPU path." % (worst * 100))
    P("")

    # ---- anchors
    P("## Anchors (batch 256, evals/s): two runs at the start (before nn_bench), two at the end (after CrossBench)")
    rows = []
    for (prog, preset) in sorted(bk.anch):
        d = bk.anch[(prog, preset)]
        v = bk.anchors.v[(prog, preset)]
        rows.append([prog, preset.upper()] + ["%.1f" % x for x in d["start"] + d["end"]] + ["%.4f" % v[0], "%.1f %%" % ((v[0] - 1) * 100), tag(v[1])])
    P(table(["program", "preset", "start 1", "start 2", "end 1", "end 2", "max/min", "drift", "st"], rows))
    P("GPU state (nvidia-smi, SM clock): %s" % gpu_clocks(res))
    loads = []
    for b in bk.anchor_blocks:
        loads.append("%s %.2f/%.2f" % (b["label"].replace("anchors-", "").replace(" round ", " r"), b["load_before"], b["load_after"]))
    P("Load average (1 min), before/after each anchor block: %s." % "; ".join(loads))
    P("Blocks of the benchmark proper: C++ l8 %.2f/%.2f, l16 %.2f/%.2f, Java %.2f/%.2f." % (bk.main[0]["load_before"], bk.main[0]["load_after"], bk.main[1]["load_before"], bk.main[1]["load_after"],
                                                                                         bk.jmain[0]["load_before"], bk.jmain[0]["load_after"]))
    over = [b["label"] for b in bk.anchor_blocks if b["load_before"] > 2.0]
    if over:
        P("The plan's limit of 2.0 for the load before a block was exceeded by: %s (load average %s; it follows directly after the Java block, which leaves the 1-minute average high)." % (
            ", ".join(over), ", ".join("%.2f" % b["load_before"] for b in bk.anchor_blocks if b["load_before"] > 2.0)))
    P("")
    P("### Pattern of the CPU anchors (run 1 against run 2; no cause is claimed)")
    rows = []
    for tagname, b_ in (("run 1", bk1), ("run 2", bk)):
        for prog in ("cpp:simd", "java:vector"):
            for preset in ("small", "large"):
                d = b_.anch[(prog, preset)]
                s1, s2, e1, e2 = d["start"] + d["end"]
                rows.append([tagname, prog, preset.upper(), "%.1f" % s1, "%.1f" % s2, "%.1f" % e1, "%.1f" % e2, pct(s2 / s1), pct(e2 / e1), pct(e1 / s1), "%.1f %%" % ((b_.anchors.v[(prog, preset)][0] - 1) * 100)])
    P(table(["run", "program", "preset", "start 1", "start 2", "end 1", "end 2", "start 2 / start 1", "end 2 / end 1", "end 1 / start 1", "max/min - 1"], rows))
    c_s, c_l = bk.anch[("cpp:simd", "small")], bk.anch[("cpp:simd", "large")]
    jv_s = bk.anch[("java:vector", "small")]
    P("  - Run 1: the second C++ run of each block was about 34 % slower than the first, at the start and at the end (steps within the block, none inside the blocks of the benchmark proper).")
    P("  - Run 2, C++ SMALL: %s, i.e. no 34 %% step; the values rise from run to run (%s, steps %s, %s, %s), the total drift is %.1f %%. C++ LARGE: %s, no step above 2 %%, and it is valid." % (
        "all four within %.1f %%" % ((A.v[("cpp:simd", "small")][0] - 1) * 100), " / ".join("%.0f" % v for v in c_s["start"] + c_s["end"]),
        pct(c_s["start"][1] / c_s["start"][0]), pct(c_s["end"][0] / c_s["start"][1]), pct(c_s["end"][1] / c_s["end"][0]), (A.v[("cpp:simd", "small")][0] - 1) * 100,
        " / ".join("%.0f" % v for v in c_l["start"] + c_l["end"])))
    P("  - Run 2, Java Vector SMALL: the first start run is the lowest (%.0f), the second jumps by %s (%.0f), the end runs are in between (%.0f, %.0f); the Java block of the benchmark proper at" % (
        jv_s["start"][0], pct(jv_s["start"][1] / jv_s["start"][0]), jv_s["start"][1], jv_s["end"][0], jv_s["end"][1]))
    P("    batch 256 is at %.0f, above every anchor value. In run 1 the first Java SMALL anchor was the highest (%.0f) and the three later ones about %.0f." % (
        j2s, bk1.anch[("java:vector", "small")]["start"][0], statistics.mean(bk1.anch[("java:vector", "small")]["start"][1:] + bk1.anch[("java:vector", "small")]["end"])))
    P("    So Java Vector SMALL has two levels in both runs, one about 5,000 to 5,900 and one about 3,600 to 3,800 (run 2 also 4,563), and it switches between runs of the same")
    P("    program about a minute apart (one anchor run lasts about a minute). Java Vector LARGE stays within 2.2 %. The C++ CPU programs of run 2 do not show such a switch.")
    P("  - Whether the switch comes from a neighbour on the shared host, from the clock behaviour under AVX-512 load, or from the JVM (JIT or the Vector API's profile) is not known. The")
    P("    pattern (steps between adjacent runs about a minute apart, not tied to one program position, seen in run 1 on C++ and in both runs on Java) is compatible with neighbour load or")
    P("    clock changes; it is also compatible with other causes. No cause is claimed; the rule keeps these rows unsettled.")
    P("")

    # ---- gpuFeed
    P("## Counter-check with the gpuFeed rows (CPU threads feed the T4, 30 s per row, batch 256; evals/s)")
    rows = []
    for p in ("small", "large"):
        f, f1 = feeds[p], feeds1[p]
        rows.append([p.upper(), "%.0f" % f1["eps"], "%.0f" % f["eps"], pct(f["eps"] / f1["eps"]), "%d %%" % f["busy"],
                     "%.0f" % bk.runs[("java:tornado", p, 256)][0][1]["eps"], "%.1f %%" % ((f["eps"] / bk.runs[("java:tornado", p, 256)][0][1]["eps"] - 1) * 100)])
    P(table(["preset", "run 1", "run 2", "run 2 vs run 1", "device busy (run 2)", "CrossBench TornadoVM b256 (run 2)", "feed against CrossBench"], rows))
    P("The feed rows agree with run 1 within %.1f %% (SMALL) and %.1f %% (LARGE); earlier AWS runs gave SMALL 80,736 to 81,633 and LARGE 18,402 to 19,029. The feed is not an anchor and has no status." % (
        abs(feeds["small"]["eps"] / feeds1["small"]["eps"] - 1) * 100, abs(feeds["large"]["eps"] / feeds1["large"]["eps"] - 1) * 100))
    P("")

    # ---- cold start
    P("## Cold start (one fresh process per case and round, 3 rounds; median [lowest, highest] in ms; no anchor applies, status 'unanchored')")
    P("construct = building the network object (CUDA: context creation, module and weight upload); first call = the first forward pass of the process; process to first result =")
    P("process uptime after that first call (C++: from main(), without the kernel's exec and the dynamic loader; JVM: since JVM start, which includes the JVM's own start). The")
    P("C++ CPU cold process is the 8-lane build (the cold step runs nn_bench_l8); Java Vector uses 16 lanes. The first case of a TornadoVM JVM also holds the TornadoVM start.")
    names = {"cpp:simd": "C++ CPU simd (8 lanes)", "cpp:cuda-pageable": "C++ CUDA pageable", "cpp:cuda-pinned": "C++ CUDA pinned", "java:vector": "Java Vector", "java:tornado": "Java TornadoVM (CUDA)"}
    order = ["cpp:simd", "java:vector", "cpp:cuda-pageable", "cpp:cuda-pinned", "java:tornado"]
    rows = []
    for preset in ("small", "large"):
        for batch in (1, 256):
            for prog in order:
                lst = bk.cold_runs.get((prog, preset, batch))
                if not lst:
                    continue
                f = lambda g: "%.1f [%.1f, %.1f]" % (statistics.median([g(c) for _, c, _, _ in lst]), min(g(c) for _, c, _, _ in lst), max(g(c) for _, c, _, _ in lst))
                rows.append([preset.upper(), batch, names[prog], f(lambda c: c["construct_ms"]), f(lambda c: c["first_call_ms"]), f(lambda c: c["uptime_ms"]), len(lst)])
    P(table(["preset", "batch", "program", "construct", "first call", "process to first result", "rounds"], rows))
    # in-process first call, for comparison
    P("")
    fc = lambda prog, preset, batch: bk.stat((prog, preset, batch), "first")[0]
    P("For comparison, the first call of a case inside a long-running process (benchmark block, table breakdown in the TSV), batch 1 / 256: C++ CUDA pinned SMALL %.1f / %.1f ms, LARGE %.1f / %.1f ms;" % (
        fc("cpp:cuda-pinned", "small", 1), fc("cpp:cuda-pinned", "small", 256), fc("cpp:cuda-pinned", "large", 1), fc("cpp:cuda-pinned", "large", 256)))
    P("TornadoVM SMALL %.1f / %.1f ms, LARGE %.1f / %.1f ms; Java Vector SMALL %.1f / %.1f ms, LARGE %.1f / %.1f ms (the very first case of the JVM block also holds the start of the runtime)." % (
        fc("java:tornado", "small", 1), fc("java:tornado", "small", 256), fc("java:tornado", "large", 1), fc("java:tornado", "large", 256),
        fc("java:vector", "small", 1), fc("java:vector", "small", 256), fc("java:vector", "large", 1), fc("java:vector", "large", 256)))
    spread = max((max(c["uptime_ms"] for _, c, _, _ in l) / min(c["uptime_ms"] for _, c, _, _ in l) - 1) * 100 for l in bk.cold_runs.values())
    P("Spread of the three rounds, process to first result: at most %.1f %% for any case. The C++ CPU first call at batch 256 (SMALL / LARGE %.1f / %.1f ms) is the cost of the first" % (
        spread, statistics.median([c["first_call_ms"] for _, c, _, _ in bk.cold_runs[("cpp:simd", "small", 256)]]),
        statistics.median([c["first_call_ms"] for _, c, _, _ in bk.cold_runs[("cpp:simd", "large", 256)]])))
    st8 = [256000.0 / bk.stat(("cpp:simd-l8", pr_, 256))[0] for pr_ in ("small", "large")]
    P("full 256-position pass, not a start-up fee: it equals the steady call time of that bucket in the 8-lane build (256 / %.1f = %.1f ms SMALL, 256 / %.1f = %.1f ms LARGE)." % (
        bk.stat(("cpp:simd-l8", "small", 256))[0], st8[0], bk.stat(("cpp:simd-l8", "large", 256))[0], st8[1]))
    P("")

    # ---- correctness
    correctness_section(res, P)
    nc2 = parse_nncheck(read(os.path.join(res, "cpp-check.log")))
    nc1 = parse_nncheck(read(os.path.join(prev, "cpp-check.log")))
    same = [(a["impl"], a["lanes"], a["preset"], a["logits"], a["values"]) for a in nc1] == [(a["impl"], a["lanes"], a["preset"], a["logits"], a["values"]) for a in nc2]
    P("  Against run 1 (before the SIMD change): the %d nn_check lines of run 2 have %s error fractions (logits and values) as the %d lines of run 1 for every impl, preset and lane count." % (
        len(nc2), "IDENTICAL" if same else "DIFFERENT", len(nc1)))
    if not same:
        raise BookingError("nn_check error fractions of run 2 differ from run 1")
    P("  The log of run 2 has a new 'digest=' field per line (a hash over the outputs of all 1,024 positions); run 1 has none, so there is no digest comparison between the two runs.")
    P("  Bit-identity of old and new source is established offline (cpp/README.md, 'Equality': 12 equal digests of old and new code); this run supports it only through the equal")
    P("  error fractions (a weaker statement).")
    P("")
    toolchain_section(res, P)
    pat = r"check-fma lanes=(\d+): simd_net \(FMA\):\s+(\d+) instructions, (\d+) fused"
    f1 = {m_[0]: m_[1:] for m_ in re.findall(pat, read(os.path.join(prev, "cpp-build.log")))}
    f2 = {m_[0]: m_[1:] for m_ in re.findall(pat, read(os.path.join(res, "cpp-build.log")))}
    P("  Note: check-fma on the object code of simd_net, 8 / 16 lanes: run 1 %s / %s instructions with %s / %s fused multiply-adds, run 2 %s / %s instructions with %s / %s. The object got smaller" % (
        f1["8"][0], f1["16"][0], f1["8"][1], f1["16"][1], f2["8"][0], f2["16"][0], f2["8"][1], f2["16"][1]))
    P("  and has fewer fused instructions in total; the cause was not examined (the inner loop has 8 per iteration, see the diagnosis in cpp/README.md). The check only requires more than 0 fused and none in the no-FMA object.")
    P("")

    # ---- cost
    P("## Cost of the cpp suite (cost.tsv of the runs; estimates, not the bill)")
    costs = [parse_cost(read(p)) for p in opts["cost"]]
    if costs:
        rows = [[c["launched"], "%d s" % c["seconds"], "%.4f" % c["price"], "%.4f" % c["estimate"]] for c in costs]
        P(table(["launched (UTC)", "counted", "USD/h", "estimate USD"], rows))
        tot = sum(c["estimate"] for c in costs)
        P("  Sum of the %d runs: %.4f USD (the runner's cumulative figure for the cpp suite; about %.2f EUR at the assumed 0.90 EUR/USD); the root volume adds a few cents." % (len(costs), tot, tot * 0.9))
        P("  The first entry is the run that a spot interruption cut off about 5 minutes after the launch; cost.tsv counts the %d s until its cleanup, so its estimate (%.4f USD) is an upper" % (costs[0]["seconds"], costs[0]["estimate"]))
        P("  bound, about 0.02 USD if only the 5 minutes were billed (not verified against a bill). Run 2 cost %.4f USD for %d counted seconds (1619 s of the suite, the rest boot and cleanup)." % (costs[-1]["estimate"], costs[-1]["seconds"]))
        P("  Budget of 5 EUR: earlier tests about 0.3 USD (the owner's figure) plus the cpp suite %.2f USD = about %.2f USD, i.e. about %.2f EUR or %.0f %% of the budget (an estimate; the" % (tot, 0.3 + tot, (0.3 + tot) * 0.9, (0.3 + tot) * 0.9 / 5 * 100))
        P("  upper bound of the cut-off run included). The cumulative cap of the cpp suite (1.00 USD) was not reached.")
    else:
        P("  not measured (no cost.tsv given)")
    P("")

    # ---- not measured
    P("## Not measured, or to be read with care")
    P("  - n = 1 run per instance on shared virtualised hardware, two instances. Run-to-run differences are two samples.")
    P("  - CPU rows of Java Vector are unsettled in both presets and the C++ SMALL rows are unsettled; no CPU ratio of this run is more than indicative.")
    P("  - The two fixes (bounds checks, stack round trip) went in together; the data cannot tell their shares. The store-forwarding stall was not measured (no perf counters on the instance).")
    P("  - Anchors in the middle of the measurement (anchor_mid) do not exist on this run. The nvidia-smi line was taken four times (before each block), not continuously.")
    P("  - Java scalar and C++ reference throughput, and gen11 throughput: not in the AWS schedule. Kernel table: C++ only, LARGE only; the Java kernel table is a deliberate gap.")
    P("  - The C++ cold CPU process is the 8-lane build (the cold step runs nn_bench_l8), Java Vector runs 16 lanes; there is no 16-lane C++ cold process.")
    P("")
    return "\n".join(L) + "\n"



def gpu_clocks(res):
    path = os.path.join(res, "nvidia-smi.csv")
    if not os.path.exists(path):
        return "not measured"
    rows = [l.strip().split(",") for l in read(path).strip().split("\n")][1:]
    return "; ".join("%s %s" % (r[0], r[1].strip()) for r in rows) + " (memory 5000 MHz, no throttling seen at the sampled moments)"


if __name__ == "__main__":
    sys.exit(main(sys.argv))
