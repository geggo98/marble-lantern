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
    """(dict step -> status, exit status) of summary.txt; every step must be OK and the exit status 0."""
    steps = {}
    status = None
    for line in text.split("\n"):
        m = re.match(r"^exit status: (\d+)$", line)
        if m:
            status = int(m.group(1))
        m = re.match(r"^([\w\-]+): (OK|SKIP|FAIL\S*|\S+)$", line)
        if m and m.group(1) not in ("backend", "suite", "gate", "seconds"):
            steps[m.group(1)] = m.group(2)
    bad = [k for k, v in steps.items() if v != "OK"]
    if status != 0 or bad or not steps:
        raise BookingError("summary.txt: exit status %s, steps not OK: %s" % (status, ", ".join(bad) or "(none read)"))
    return steps, status


def parse_cost(text):
    """cost.tsv of aws-spot.sh. The price column of the file holds a trailing newline, so the single line is split in two; the
    tokens are read in order: suite, launched, seconds, price_usd_per_h, 'None' (the estimate's label), estimate, worst case."""
    t = text.split()
    if len(t) != 7 or t[4] != "None":
        raise BookingError("cost.tsv: unexpected layout (%d tokens)" % len(t))
    return {"suite": t[0], "launched": t[1], "seconds": int(t[2]), "price": float(t[3]), "estimate": float(t[5]), "worst": float(t[6])}


# ------------------------------------------------------------------------------------------------------------------ anchors

def program_of(row):
    if row["impl"].startswith("cuda-"):
        return "cpp:" + row["impl"]
    return ("cpp:" if row["lang"] == "cpp" else "java:") + row["impl"]


def read_anchors(blocks):
    """{(program, preset): {'start': [v...], 'end': [v...]}} in the order of the blocks (round 1 C++, round 1 Java, round 2 ...)."""
    out = {}
    order = []
    for b in blocks:
        m = re.match(r"^anchors-(start|end) round (\d): (C\+\+|Java)$", b["label"])
        if not m:
            raise BookingError("anchors: unexpected block '%s'" % b["label"])
        for r in b["rows"]:
            if r["batch"] != 256:
                continue
            key = (program_of(r), r["preset"])
            out.setdefault(key, {"start": [], "end": []})[m.group(1)].append(r["eps"])
        order.append((m.group(1), int(m.group(2)), m.group(3)))
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
        self.anch, self.anchor_order = read_anchors(anchor_blocks)
        self.anchors = Anchors(self.anch)
        self.main = [b for b in nn if re.match(r"^hardened, l(8|16): ", b["label"])]
        self.unchecked = [b for b in nn if b["label"].startswith("unchecked, ")]
        self.kernel_blocks = [b for b in nn if "kernels" in b["label"]]
        self.cold_nn = [b for b in nn if b["label"].endswith("cold start")]
        self.jmain = [b for b in cross if b["label"] == "vector and tornado"]
        self.jcold = [b for b in cross if b["label"] == "cold start"]
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
        for b in self.cold_nn:
            for r in b["rows"]:
                if b["cold"]:
                    self.add("cold_start", "cpp:" + r["impl"], case_name(r["preset"]), r["batch"], "first_call_ms", b["cold"]["first_call_ms"], None, None, "valid"
                             if self.anchors.judge("cpp:simd", "small")[0] == "valid" else "unsettled", (None, None, None),
                             "separate process; construct %.1f ms, process uptime at the end %.1f ms; one case only (see not_measured)" %
                             (b["cold"]["construct_ms"], b["cold"]["uptime_ms"]))
        for b in self.jcold:
            for r in b["rows"]:
                if b["cold"]:
                    st = self.status_of("java:" + r["impl"], r["preset"])[0]
                    self.add("cold_start", "java:" + r["impl"], case_name(r["preset"]), r["batch"], "first_call_ms", b["cold"]["first_call_ms"], None, None, st,
                             (None, None, None),
                             "separate JVM; construct %.1f ms, JVM uptime at the end %.0f ms; one case only (see not_measured)" %
                             (b["cold"]["construct_ms"], b["cold"]["uptime_ms"]))
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
    return rows


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
    opts = {"date": "2026-11-15", "cost": [], "mac": None, "commit": None}
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


def run(res, out, opts):
    steps, _ = parse_summary(read(os.path.join(res, "summary.txt")))
    nn = parse_blocks(read(os.path.join(res, "nn-bench.tsv")), r"^# (hardened|unchecked), ")
    cross = parse_blocks(read(os.path.join(res, "crossbench.tsv")), r"^# (vector and tornado|cold start)$")
    anchor_blocks = parse_blocks(read(os.path.join(res, "anchors.tsv")), r"^# anchors-(start|end) round \d: ")
    n_nn = check_blocks("nn-bench.tsv", nn)
    n_cross = check_blocks("crossbench.tsv", cross)
    n_anch = check_blocks("anchors.tsv", anchor_blocks, schedule_checked=False)
    bk = Booking(nn, cross, anchor_blocks)
    bk.book()
    feeds = {p: parse_gpufeed(read(os.path.join(res, "gpufeed-%s.log" % p))) for p in ("small", "large")}
    for p, f in feeds.items():
        bk.add("gpufeed", "java:tornado-feed", case_name(p), f["batch"], "evals_per_s", f["eps"], None, None, "unanchored", (None, None, None),
               "gpuFeed row of 30 s, 4 compute threads, 2 cohorts, device busy %d %%; not anchored, judged only against the earlier AWS runs" % f["busy"])
    bk.add("verification", "run", "all", None, "rows_verified_ok", n_nn + n_cross + n_anch, None, None, "valid", (None, None, None),
           "nn-bench.tsv %d, crossbench.tsv %d, anchors.tsv %d rows, every one verified=ok; %d summary steps, every one OK, exit status 0" %
           (n_nn, n_cross, n_anch, len(steps)))
    write_tsv(out + ".tsv", bk.rows)
    mac_rows = []
    if opts["mac"]:
        with open(opts["mac"], encoding="utf-8") as f:
            mac_rows = [l.rstrip("\n").split("\t") for l in f][1:]
    text = summary_text(res, bk, feeds, steps, (n_nn, n_cross, n_anch), mac_rows, opts)
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


def summary_text(res, bk, feeds, steps, counts, mac_rows, opts):
    L = []
    P = L.append
    A = bk.anchors
    n_nn, n_cross, n_anch = counts
    unsettled = [k for k, v in sorted(A.v.items()) if v[1] != "valid"]
    P("# AWS run on a Tesla T4 (g4dn.xlarge, spot), network measurement and CUDA check, %s" % opts["date"])
    P("")
    P("## Status: read this first")
    P("n = 1 run on a shared, virtualised instance (4 vCPUs of an Intel Xeon Platinum 8259CL, one Tesla T4, CUDA 13.2). All %d steps of the run ended OK" % len(steps))
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
    # ---- correctness
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
    gh = parse_goldenhash(read(os.path.join(res, "golden-hash.log")))
    must = [r for r in gh if r[3] == "must"]
    info = [r for r in gh if r[3] == "info"]
    if any(r[2] != "equal" for r in must):
        raise BookingError("golden-hash.log: a must line that is not equal")
    P("  golden hash: %d 'must' lines (weights, inputs, cases) are equal to the Mac hashes; %d 'info' lines: %s." % (
        len(must), len(info), "; ".join("%s %s" % (r[0].replace(".safetensors", ""), r[2]) for r in info)))
    P("    The two DIFFERENT lines are the Java reference OUTPUTS (nn-ref-small/large-u5) as the JVM on this instance computed them. Weights, inputs and cases are bit-equal, so the")
    P("    inputs of every check are the Mac's; the outputs of the same Java code differ in the last bits on another CPU (Math.exp and tanh may differ by one ulp between platforms,")
    P("    other FMA and vector paths). That is what the plan's 'info' kind expects ('a difference is recorded only'), not a failure. It does mean that nn_check on the instance")
    P("    compared against the instance's own reference files, not against the Mac's. The third info line (gen11) has no expected hash by design (real weights).")
    P("    Not shown by this run: nn_check --impl reference (bit-identity with the Java reference) is not in cpp-check.log.")
    P("")
    # ---- toolchain
    P("## Toolchain")
    tc = read(os.path.join(res, "cpp-toolchain.log"))
    gx = re.search(r"^cxx:\s+(g\+\+-12 \(.*\) [\d.]+)", read(os.path.join(res, "cpp-build.log")), re.M) or re.search(r"^(g\+\+-12 \(.*\) [\d.]+)$", tc, re.M)
    nv = re.search(r"release ([\d.]+), V([\d.]+)", read(os.path.join(res, "nvcc-version.txt")))
    arch = re.search(r"compute_75", tc)
    drv = re.search(r"^Tesla T4, ([\d.]+)$", tc, re.M)
    jv = re.search(r'openjdk version "([\d.]+)"', read(os.path.join(res, "java-version.txt")))
    tv = re.search(r"tornadovm-([\d.]+)-", read(os.path.join(res, "tornado-setup.log")))
    P("  C++: %s, -O3 -march=native, -ffp-contract=off (FMA only in simd_net), 8 and 16 lanes, hardened (-D_GLIBCXX_ASSERTIONS) and unchecked builds; check-fma: 31 fused" % (gx.group(1) if gx else "g++-12"))
    P("  multiply-adds in simd_net for both lane counts, 0 in simd_net_nofma (read from cpp-build.log).")
    P("  CUDA: nvcc release %s (V%s), compute_75 %s in nvcc's architecture list; flags of build-cuda.sh at the commit: -std=c++20 -ccbin g++-12 -O3 -lineinfo --fmad=true" % (
        nv.group(1) if nv else "?", nv.group(2) if nv else "?", "present" if arch else "MISSING"))
    P("  -gencode arch=compute_75,code=sm_75 and PTX compute_75, no fast math. Driver %s, Tesla T4, 15,360 MiB; compute-sanitizer 2026.1.0.0." % (drv.group(1) if drv else "?"))
    P("  Java %s (Zulu), TornadoVM %s (CUDA backend), JVM flags -Xms4g -Xmx4g, G1, 512-bit Vector API (16 float lanes), UseAVX 3. OS: Ubuntu 22.04, kernel 6.8 (aws)." % (jv.group(1) if jv else "?", tv.group(1) if tv else "?"))
    P("  CPU: Intel Xeon Platinum 8259CL @ 2.50 GHz, 4 vCPUs, 15.4 GiB, AVX-512 (F, BW, VL, VNNI).")
    P("")
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


def gpu_clocks(res):
    path = os.path.join(res, "nvidia-smi.csv")
    if not os.path.exists(path):
        return "not measured"
    rows = [l.strip().split(",") for l in read(path).strip().split("\n")][1:]
    return "; ".join("%s %s" % (r[0], r[1].strip()) for r in rows) + " (memory 5000 MHz, no throttling seen at the sampled moments)"


if __name__ == "__main__":
    sys.exit(main(sys.argv))
