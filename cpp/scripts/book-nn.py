#!/usr/bin/env -S uv --quiet run --frozen --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
#
# [tool.uv]
# exclude-newer = "2026-11-01T00:00:00Z"
# ///
"""Books the network measurement of one Mac session (docs/cpp-comparison-plan.md, WP13, "Amendment 2026-11-15 (lead)").

usage: book-nn.py <work-dir> <out-prefix> [--date YYYY-MM-DD] [--build TEXT ...] [--size TEXT ...] [--merge-bench PATH]

Reads the work directory of cpp/scripts/bench-nn-mac.py (steps.tsv and one <step>.tsv per run), writes
  <out-prefix>.tsv   one row per booked figure: table, program, case, batch, metric, median, min, max, status, anchor_start,
                     anchor_mid, anchor_end, note
  <out-prefix>.txt   the human-readable summary of the same numbers

Rules of the amendment (lead, 2026-11-15), applied here:
  * CPU rows (reference, scalar, simd, simd-nofma, Vector API) are "valid" if the anchor of their program lies within 2 % between start
    and end of the session, otherwise "unsettled". A row without an anchor of its own is judged by the anchor of the named program.
  * GPU rows (TornadoVM Metal, Metal copy / shared) are always "unsettled", with the measured drift of the anchors in the row.
  * A Java:C++ ratio of a GPU pair is formed only from the two processes of one pair (J_i, C_i, run back to back), is labelled
    "unsettled (GPU clock drift)", and carries no statement below 5 %.
  * Nothing is smoothed, re-measured or dropped. A row that did not pass its output check does not exist (nn_bench exits 2 without a
    line); this script fails (exit 3) on any step with rc != 0, any row whose "verified" column is not "ok", and any step whose rows
    plus skipped cases do not add up to the schedule in its header.
Exit codes: 0 booked, 2 usage, 3 verification failure.
"""
import os
import re
import statistics
import subprocess
import sys

GPU_IMPLS = {"tornado", "metal-copy", "metal-shared", "cuda-pageable", "cuda-pinned"}
ANCHOR_TOL = 0.02          # plan, Hygiene: anchors of start and end within 2 %
RATIO_LIMIT = 0.05         # plan, Decision rule: network on the Mac, more than 5 %
COLUMNS = ["table", "program", "case", "batch", "metric", "median", "min", "max", "status",
           "anchor_start", "anchor_mid", "anchor_end", "note"]
# the program whose anchor judges a row that has no anchor of its own
ANCHOR_OF = {"java:scalar": "java:vector", "java:vector": "java:vector", "java:tornado": "java:tornado",
             "cpp:reference": "cpp:simd", "cpp:simd": "cpp:simd", "cpp:simd-nofma": "cpp:simd",
             "cpp:metal-copy": "cpp:metal-copy", "cpp:metal-shared": "cpp:metal-copy"}
PAIRS = [("java:scalar", "cpp:reference"), ("java:vector", "cpp:simd"), ("java:vector", "cpp:simd-nofma"),
         ("java:tornado", "cpp:metal-copy"), ("java:tornado", "cpp:metal-shared")]
GPU_HINT = "unsettled (GPU clock drift)"


class BookingError(Exception):
    pass


def med(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def num(text):
    return None if text in ("NA", "", None) else float(text)


def is_gpu(program):
    return program.split(":", 1)[1] in GPU_IMPLS


def case_name(preset, weights):
    return "%s %s" % (preset.upper(), weights)


def fnum(v, digits):
    return "NA" if v is None else ("%.*f" % (digits, v))


# ----------------------------------------------------------------------------------------------------------------- reading

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


def parse_step(text):
    st = {"rows": [], "cpuwall": {}, "gpu": {}, "skipped": [], "cold": None, "expected": None, "profiled": [], "setup": []}
    for line in text.split("\n"):
        if not line:
            continue
        m = re.match(r"^# golden .*presets .*batches .*impls ", line)
        if m and "anchors only" not in line and " cold" not in line and "kernels" not in line and st["expected"] is None:
            st["expected"] = parse_schedule(line)
            continue
        m = re.match(r"^# cpu: (\S+) (\S+) (\S+) batch (\d+): .* ([\d.]+)$", line)
        if m:
            st["cpuwall"][(m.group(1), m.group(2), m.group(3), int(m.group(4)))] = float(m.group(5))
            continue
        m = re.match(r"^# gpu: (\S+) (\S+) (\S+) batch (\d+): GPU time of the command buffer ([\d.]+) ms", line)
        if m:
            st["gpu"][(m.group(1), m.group(2), m.group(3), int(m.group(4)))] = float(m.group(5))
            continue
        m = re.match(r"^# skipped: (\S+) (\S+) (\S+) batch (\d+) \((.*)\)$", line)
        if m:
            st["skipped"].append(((m.group(1), m.group(2), m.group(3), int(m.group(4))), m.group(5)))
            continue
        m = re.match(r"^# cold: construct ([\d.]+) ms, first call ([\d.]+) ms, (?:JVM|process) uptime[^0-9]*([\d.]+) ms", line)
        if m:
            st["cold"] = {"construct_ms": float(m.group(1)), "first_call_ms": float(m.group(2)), "uptime_ms": float(m.group(3))}
            continue
        m = re.match(r"^# (\S+) (\S+) (\S+): metal set-up of bucket (\d+): pipelines ([\d.]+) ms, buffers ([\d.]+) ms", line)
        if m:
            st["setup"].append((m.group(1), m.group(2), m.group(3), int(m.group(4)), float(m.group(5)), float(m.group(6))))
            continue
        m = re.match(r"^# (\S+) (\S+) (\S+): metal library compile .*: ([\d.]+) ms$", line)
        if m:
            st["setup"].append((m.group(1), m.group(2), m.group(3), 0, float(m.group(4)), None))
            continue
        if line.startswith("#"):
            continue
        c = line.split("\t")
        if len(c) == 22 and c[0] in ("java", "cpp"):
            st["rows"].append({"lang": c[0], "impl": c[1], "device": c[2], "preset": c[3], "weights": c[4], "batch": int(c[5]),
                               "eps": num(c[8]), "ms50": num(c[9]), "ms90": num(c[10]), "rmin": num(c[11]), "rmax": num(c[12]),
                               "copy_in": num(c[13]), "kernel": num(c[15]), "copy_out": num(c[17]), "priors": num(c[18]),
                               "first": num(c[19]), "load": num(c[20]), "verified": c[21]})
    return st


class Session:
    def __init__(self, workdir):
        self.dir = workdir
        self.steps = []
        with open(os.path.join(workdir, "steps.tsv"), encoding="utf-8") as f:
            head = f.readline().rstrip("\n").split("\t")
            for line in f:
                s = dict(zip(head, line.rstrip("\n").split("\t")))
                with open(os.path.join(workdir, s["name"] + ".tsv"), encoding="utf-8") as g:
                    s.update(parse_step(g.read()))
                s["pair"] = int(s["pair"])
                self.steps.append(s)

    def of(self, kind, lang=None):
        return [s for s in self.steps if s["kind"] == kind and (lang is None or s["lang"] == lang)]


def verify(session):
    """(b): every row passed its output check. Returns the lines for the result file; raises BookingError on any violation."""
    problems, report = [], []
    totals = {}
    for s in session.steps:
        if s["rc"] != "0":
            problems.append("step %s: rc %s" % (s["name"], s["rc"]))
        bad = [r for r in s["rows"] if r["verified"] != "ok"]
        if bad:
            problems.append("step %s: %d row(s) not verified" % (s["name"], len(bad)))
        skipped = len({k for k, _ in s["skipped"]})
        if s["kind"] in ("main", "gen11", "checks") and s["expected"] is not None and len(s["rows"]) + skipped != s["expected"]:
            problems.append("step %s: %d rows + %d skipped != %d scheduled" % (s["name"], len(s["rows"]), skipped, s["expected"]))
        if s["kind"] in ("main", "gen11", "checks") and s["expected"] is None:
            problems.append("step %s: no schedule line in the header" % s["name"])
        key = s["kind"] if s["kind"] != "checks" else "checks"
        t = totals.setdefault(key, {"steps": 0, "rows": 0, "ok": 0, "skipped": 0})
        t["steps"] += 1
        t["rows"] += len(s["rows"])
        t["ok"] += len(s["rows"]) - len(bad)
        t["skipped"] += skipped
    if problems:
        raise BookingError("; ".join(problems))
    for k in ("anchors", "main", "gen11", "checks", "kernels", "cold"):
        if k in totals:
            t = totals[k]
            report.append((k, t))
    return report


# ----------------------------------------------------------------------------------------------------------------- booking

class Book:
    def __init__(self, session):
        self.s = session
        self.rows = []
        self.anchors = self._anchors()
        self.agg = self._aggregate()

    def add(self, table, program, case, batch, metric, median, lo, hi, status, anchor, note):
        a = anchor or (None, None, None)
        self.rows.append({"table": table, "program": program, "case": case, "batch": "" if batch is None else str(batch), "metric": metric,
                          "median": median, "min": lo, "max": hi, "status": status,
                          "anchor_start": a[0], "anchor_mid": a[1], "anchor_end": a[2], "note": note})

    def _anchors(self):
        out = {}
        for s in self.s.of("anchors"):
            m = re.match(r"^anchors-(\w+)-", s["name"])
            for r in s["rows"]:
                if r["batch"] == 256:
                    out.setdefault(("%s:%s" % (r["lang"], r["impl"]), r["preset"]), {})[m.group(1)] = r["eps"]
        return out

    def anchor_info(self, program, preset):
        """(anchor triple, end/start, own?, anchor program) for a row of this program and preset (gen11 rows use LARGE)"""
        aprog = ANCHOR_OF[program]
        a = self.anchors.get((aprog, preset))
        if not a or None in (a.get("start"), a.get("mid"), a.get("end")):
            return None, None, aprog == program, aprog
        return (a["start"], a["mid"], a["end"]), a["end"] / a["start"], aprog == program, aprog

    def status(self, program, preset):
        """(status, note) of a row"""
        trip, q, own, aprog = self.anchor_info(program, preset)
        src = "anchor of %s %s at batch 256%s" % (aprog, preset, "" if own else " (no anchor of its own)")
        if trip is None:
            return "unsettled", "no anchor available"
        drift = "start/mid/end %.1f / %.1f / %.1f, end/start %.4f, lowest/start %.4f" % (trip[0], trip[1], trip[2], q, min(trip) / trip[0])
        if is_gpu(program):
            return "unsettled", "%s: %s; %s" % (GPU_HINT, src, drift)
        if ANCHOR_TOL and abs(q - 1) <= ANCHOR_TOL:
            return "valid", "%s within 2 %%: end/start %.4f" % (src, q)
        return "unsettled", "CPU anchor outside 2 %%: %s; end/start %.4f" % (src, q)

    def _aggregate(self):
        """agg[(program, preset, weights, batch)] = {pair: row}"""
        agg = {}
        for s in self.s.steps:
            if s["kind"] not in ("main", "gen11"):
                continue
            for r in s["rows"]:
                agg.setdefault(("%s:%s" % (r["lang"], r["impl"]), r["preset"], r["weights"], r["batch"]), {})[s["pair"]] = r
        return agg

    def gpu_ms(self, program, preset, weights, batch):
        out = []
        impl = program.split(":", 1)[1]
        for s in self.s.of("main", program.split(":", 1)[0]):
            v = s["gpu"].get((impl, preset, weights, batch))
            if v is not None:
                out.append(v)
        return out

    # ---- tables
    def throughput(self):
        for key in sorted(self.agg, key=lambda k: (k[1], k[2], k[0], k[3])):
            program, preset, weights, batch = key
            rows = [self.agg[key][p] for p in sorted(self.agg[key])]
            status, note = self.status(program, preset)
            self.add("throughput", program, case_name(preset, weights), batch, "evals_per_s", med([r["eps"] for r in rows]),
                     min(r["rmin"] for r in rows), max(r["rmax"] for r in rows), status, self.anchor_info(program, preset)[0],
                     "median over %d pairs of the per-process median round, range = smallest round minimum to largest round maximum; %s"
                     % (len(rows), note))
        seen = set()
        for s in self.s.of("main") + self.s.of("gen11"):
            for (impl, preset, weights, batch), why in s["skipped"]:
                k = ("%s:%s" % (s["lang"], impl), preset, weights, batch)
                if k in seen:
                    continue
                seen.add(k)
                self.add("not_measured", k[0], case_name(preset, weights), batch, "evals_per_s", None, None, None, "not measured", None,
                         "skipped by the schedule: %s" % why)

    def ratios(self):
        for jp, cp in PAIRS:
            cases = sorted({(k[1], k[2], k[3]) for k in self.agg if k[0] == jp} & {(k[1], k[2], k[3]) for k in self.agg if k[0] == cp},
                           key=lambda c: (c[1], c[0], c[2]))
            for preset, weights, batch in cases:
                jr, cr = self.agg[(jp, preset, weights, batch)], self.agg[(cp, preset, weights, batch)]
                pairs = sorted(set(jr) & set(cr))
                q = [cr[p]["eps"] / jr[p]["eps"] for p in pairs]
                m = med(q)
                jm, cm = med([jr[p]["eps"] for p in pairs]), med([cr[p]["eps"] for p in pairs])
                jlo, jhi = min(jr[p]["rmin"] for p in pairs), max(jr[p]["rmax"] for p in pairs)
                clo, chi = min(cr[p]["rmin"] for p in pairs), max(cr[p]["rmax"] for p in pairs)
                disjoint = (cm < jlo or cm > jhi) and (jm < clo or jm > chi)
                js, _ = self.status(jp, preset)
                cs, _ = self.status(cp, preset)
                per = ", ".join("%.3f" % v for v in q)
                if is_gpu(jp) or is_gpu(cp):
                    status = "unsettled"
                    if abs(m - 1) <= RATIO_LIMIT:
                        verdict = "no statement (difference below 5 %%); %s" % GPU_HINT
                    elif min(q) < 1 < max(q):
                        verdict = "no statement (the pairs disagree on the direction); %s" % GPU_HINT
                    else:
                        verdict = "indicative only, C++ %s by %.1f %%, round ranges %s; %s" % (
                            "faster" if m > 1 else "slower", 100 * abs(m - 1), "disjoint" if disjoint else "overlap", GPU_HINT)
                    note = "ratio from adjacent pairs (J_i then C_i, back to back); the GPU state during each case was not recorded, same state is assumed, not shown; per pair: %s; %s" % (per, verdict)
                else:
                    status = "valid" if js == "valid" and cs == "valid" else "unsettled"
                    resolved = abs(m - 1) > RATIO_LIMIT and disjoint
                    size = "C++ %s by %.1f %%" % ("faster" if m > 1 else "slower", 100 * abs(m - 1))
                    if status == "valid":
                        verdict = "real: " + size if resolved else "not resolved (within 5 % or round ranges overlap)"
                    else:
                        # "real" is the label of a resolved difference of a valid row; an unsettled row never carries it
                        verdict = "indicative only (CPU anchor outside 2 %% on %s); %s" % (
                            "Java" if js != "valid" else "C++", size if resolved else "not resolved (within 5 % or round ranges overlap)")
                    note = "median of the per-pair ratios; per pair: %s; %s" % (per, verdict)
                self.add("ratio", "%s / %s" % (cp, jp), case_name(preset, weights), batch, "cpp_over_java_evals_per_s", m, min(q), max(q), status,
                         None, note + " (ratio above 1: C++ faster)")

    def latency(self):
        for impl_prog in ["java:scalar", "java:vector", "java:tornado", "cpp:reference", "cpp:simd", "cpp:simd-nofma", "cpp:metal-copy", "cpp:metal-shared"]:
            for preset in ("small", "large"):
                d = self.agg.get((impl_prog, preset, "u5", 1))
                if not d:
                    continue
                rows = [d[p] for p in sorted(d)]
                status, note = self.status(impl_prog, preset)
                for metric, field in (("p50_ms", "ms50"), ("p90_ms", "ms90")):
                    vals = [r[field] for r in rows]
                    self.add("latency", impl_prog, case_name(preset, "u5"), 1, metric, med(vals), min(vals), max(vals), status,
                             self.anchor_info(impl_prog, preset)[0], "per-call time at batch 1, median over %d pairs, range over the pairs; %s" % (len(rows), note))

    def phases(self):
        for prog in ("java:tornado", "cpp:metal-copy", "cpp:metal-shared"):
            for preset in ("small", "large"):
                for batch in (1, 16, 64, 256, 512, 1024):
                    d = self.agg.get((prog, preset, "u5", batch))
                    if not d:
                        continue
                    rows = [d[p] for p in sorted(d)]
                    status, note = self.status(prog, preset)
                    anchor = self.anchor_info(prog, preset)[0]
                    meta = ("Java: execute = TornadoVM execute (host to device, kernels, device to host in one), host-device transfers are not separable; "
                            "C++: execute = commit-and-wait of one command buffer; host-side figures have no anchor of their own; %s" % note)
                    for metric, vals in (("copy_in_ms", [r["copy_in"] for r in rows]), ("execute_ms", [r["kernel"] for r in rows]),
                                         ("copy_out_ms", [r["copy_out"] for r in rows]), ("host_priors_ms", [r["priors"] for r in rows]),
                                         ("wall_p50_ms", [r["ms50"] for r in rows]), ("first_call_ms", [r["first"] for r in rows]),
                                         ("gpu_time_ms", self.gpu_ms(prog, preset, "u5", batch))):
                        v = [x for x in vals if x is not None]
                        if v:
                            self.add("phases", prog, case_name(preset, "u5"), batch, metric, med(v), min(v), max(v), status, anchor, meta)

    def from_summary(self, text):
        """profiled / instrumented pass, kernel table: taken from the sections of merge-bench.pl"""
        sec = None
        n = {"profiled": 0, "kernel": 0}
        for line in text.split("\n"):
            if line.startswith("## "):
                sec = ("profiled" if line.startswith("## Kernel, write and read") else "kernel" if line.startswith("## Convolution kernel") else None)
                continue
            if sec == "profiled":
                m = re.match(r"^(java|cpp)\s+(\w+)\s+(\w+)\s+(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s*$", line)
                if m and m.group(1) in ("java", "cpp") and m.group(2) in ("small", "large"):
                    prog = "java:tornado" if m.group(1) == "java" else "cpp:metal-copy"
                    status, note = self.status(prog, m.group(2))
                    for metric, v in (("wall_ms", m.group(5)), ("kernel_ms", m.group(6)), ("device_write_ms", m.group(7)), ("device_read_ms", m.group(8))):
                        self.add("profiled", prog, case_name(m.group(2), m.group(3)), int(m.group(4)), metric, num(v), None, None, status,
                                 self.anchor_info(prog, m.group(2))[0],
                                 "separate pass with instrumentation (Java: TornadoVM profiler; C++: Metal instrumented mode, one command buffer per kernel and blit), "
                                 "slows the call; NA = backend reports no value; %s" % note)
                    n["profiled"] += 1
            if sec == "kernel":
                m = re.match(r"^(\d+)\s+(\d+)\s+(\w+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s*$", line)
                if m:
                    status, note = self.status("java:tornado", "large")
                    meta = ("kernel table, the convolution kernels alone, median of 2 rounds, run after the third pair; %s; Java kernel time from the TornadoVM profiler "
                            "(the plan names a floor of about 0.5 ms per task, values below that are listed as measured and not explained); "
                            "no output check for this table" % note)
                    for prog, t, g in (("java:KernelsBenchmark", m.group(4), m.group(7)), ("cpp:nn_bench-kernels", m.group(5), m.group(8))):
                        self.add("kernel_table", prog, "C=%s %s" % (m.group(1), m.group(3)), int(m.group(2)), "kernel_ms", num(t), None, None, "unsettled",
                                 self.anchor_info("java:tornado", "large")[0], meta)
                        self.add("kernel_table", prog, "C=%s %s" % (m.group(1), m.group(3)), int(m.group(2)), "gflops_kernel", num(g), None, None, "unsettled",
                                 self.anchor_info("java:tornado", "large")[0], meta)
                    n["kernel"] += 1
        return n

    def setup(self):
        first = None
        for s in self.s.of("main", "cpp"):
            first = s
            break
        if not first:
            return
        for impl, preset, weights, bucket, a, b in first["setup"]:
            prog = "cpp:" + impl
            status, note = self.status(prog, preset)
            meta = "Metal set-up, first pair only (one sample per figure); %s" % note
            if bucket == 0:
                self.add("setup", prog, case_name(preset, weights), None, "library_compile_ms", a, None, None, status, None, meta)
            else:
                self.add("setup", prog, case_name(preset, weights), bucket, "pipelines_ms", a, None, None, status, None, meta)
                self.add("setup", prog, case_name(preset, weights), bucket, "buffers_ms", b, None, None, status, None, meta)

    def checks(self):
        by_pair = {}
        for s in self.s.of("checks"):
            by_pair.setdefault(s["pair"], {})[s["lang"]] = {(r["impl"], r["preset"]): r for r in s["rows"] if r["batch"] == 256}
        for impl in ("simd", "simd-nofma", "metal-copy", "metal-shared"):
            for preset in ("small", "large"):
                q = []
                for p in sorted(by_pair):
                    h, r = by_pair[p].get("cpp-hard", {}).get((impl, preset)), by_pair[p].get("cpp-rel", {}).get((impl, preset))
                    if h and r:
                        q.append(r["eps"] / h["eps"])
                if not q:
                    continue
                prog = "cpp:" + impl
                status, note = self.status(prog, preset)
                m = med(q)
                if abs(m - 1) <= RATIO_LIMIT:
                    verdict = "not resolved (within 5 %)"
                elif min(q) < 1 < max(q):
                    verdict = "no statement (the pairs disagree on the direction)"
                elif m < 1:
                    # ratio = throughput release / throughput hardened, so 1/m - 1 is the extra time of the unchecked build
                    verdict = "the unchecked build needs %.1f %% more time than the hardened one, not explained, not a cost of the checks" % (100 * (1 / m - 1))
                else:
                    verdict = "the checks cost %.1f %%" % (100 * (1 - 1 / m))
                self.add("checks_cost", prog, case_name(preset, "u5"), 256, "release_over_hardened_evals_per_s", m, min(q), max(q), status,
                         self.anchor_info(prog, preset)[0],
                         "median over %d pairs of the per-pair ratio release / release-hardened (order alternates between pairs); %s; %s" % (len(q), verdict, note))

    def cold(self):
        groups = {}
        for s in self.s.of("cold"):
            m = re.match(r"^cold-r\d+-b(\d+)-(java|cpp)-(\S+)$", s["name"])
            groups.setdefault((m.group(2) + ":" + ("metal-copy" if m.group(3) == "metal" else m.group(3) if m.group(3) != "tornado" else "tornado"), int(m.group(1))), []).append(s)
        for (prog, batch), steps in sorted(groups.items()):
            status, note = self.status(prog, "large")
            meta = "cold start of LARGE u5, median of %d runs; %s" % (len(steps), note)
            for metric, vals in (("process_wall_s", [float(s["wall_s"]) for s in steps]),
                                 ("construct_ms", [s["cold"]["construct_ms"] for s in steps if s["cold"]]),
                                 ("first_call_ms", [s["cold"]["first_call_ms"] for s in steps if s["cold"]]),
                                 ("uptime_at_end_ms", [s["cold"]["uptime_ms"] for s in steps if s["cold"]])):
                extra = " (process_wall_s is quantised in steps of about 0.25 s by the session script; the figures of the program are finer; uptime counts from main or JVM start)" if metric in ("process_wall_s", "uptime_at_end_ms") else ""
                self.add("cold_start", prog, "LARGE u5", batch, metric, med(vals), min(vals) if vals else None, max(vals) if vals else None, status,
                         self.anchor_info(prog, "large")[0], meta + extra)

    def anchor_table(self):
        for (prog, preset), a in sorted(self.anchors.items()):
            trip, q, own, _ = self.anchor_info(prog, preset)
            ok = abs(q - 1) <= ANCHOR_TOL
            status = "unsettled" if is_gpu(prog) or not ok else "valid"
            self.add("anchors", prog, case_name(preset, "u5"), 256, "end_over_start", q, min(trip) / trip[0], max(trip) / trip[0], status, trip,
                     "anchor rows at batch 256 (evals/s); min/max columns = lowest and highest of start, mid, end over start; "
                     "%s" % (("outside 2 % between start and end, session check failed" if not ok else "within 2 %") + ("; " + GPU_HINT if is_gpu(prog) else "")))

    def verification(self, report):
        for kind, t in report:
            self.add("verification", kind, "all steps of the kind", None, "rows_verified_ok", t["ok"], None, None,
                     "valid" if t["ok"] == t["rows"] else "unsettled", None,
                     "%d steps, %d rows, all with verified=ok, %d cases skipped by the schedule, every step rc 0; rows plus skipped cases equal the schedule of the step header "
                     "(main, gen11, checks); a case that fails its output check prints no row and exits 2" % (t["steps"], t["rows"], t["skipped"]))

    def cpu_wall(self):
        vals = []
        for s in self.s.steps:
            if s["lang"].startswith("cpp") and s["kind"] in ("main", "gen11", "checks"):
                vals += [v for k, v in s["cpuwall"].items() if k[0] in ("reference", "simd", "simd-nofma")]
        self.add("verification", "cpp CPU cases", "thread CPU time over wall time", None, "ratio", med(vals), min(vals), max(vals),
                 "valid" if min(vals) >= 0.95 else "unsettled", None, "%d cases, the plan requires at least 0.8" % len(vals))


def tsv_cell(v):
    if v is None:
        return "NA"
    if isinstance(v, float):
        return "%.4g" % v if abs(v) < 1 else "%.3f" % v if abs(v) < 100 else "%.1f" % v
    return str(v).replace("\t", " ").replace("\n", " ")


def write_tsv(path, rows):
    with open(path, "w", encoding="utf-8") as f:
        f.write("\t".join(COLUMNS) + "\n")
        for r in rows:
            f.write("\t".join(tsv_cell(r[c]) for c in COLUMNS) + "\n")


# ----------------------------------------------------------------------------------------------------------------- summary text

def table(headers, rows):
    w = [max(len(str(x)) for x in col) for col in zip(headers, *rows)]
    out = ["  ".join(str(h).ljust(w[i]) for i, h in enumerate(headers)), "  ".join("-" * x for x in w)]
    out += ["  ".join(str(c).ljust(w[i]) for i, c in enumerate(r)) for r in rows]
    return "\n".join(out)


def write_summary(path, book, date, report, effort):
    rows = book.rows
    get = lambda **kw: [r for r in rows if all(r[k] == v for k, v in kw.items())]

    def one(table_, program, case, batch, metric="evals_per_s"):
        x = get(table=table_, program=program, case=case, batch="" if batch is None else str(batch), metric=metric)
        return x[0] if x else None

    def cell(r, digits=1):
        if r is None:
            return "skipped"
        return "%s %s" % (fnum(r["median"], digits), "V" if r["status"] == "valid" else "u")

    L = []
    w = L.append
    w("# Network measurement on the Mac, session 5, booked under the amendment of 2026-11-15")
    w("# date %s; Apple M3 Max; details of the machine, compiler and JVM flags: the header of the raw output of the session" % date)
    w("")
    w("## Status: read this first")
    w("The session is INVALID by the original rule of the plan (anchor start against end within 2 %): 4 of 8 anchors are outside (table below).")
    w("It is booked under the amendment (docs/cpp-comparison-plan.md, section Decision rule, 'Amendment 2026-11-15 (lead)'):")
    w("  V = valid     CPU row whose anchor lies within 2 % between start and end of the session")
    w("  u = unsettled a CPU row whose anchor lies outside 2 %, or any GPU row (Java TornadoVM on Metal, C++ Metal): the GPU clock drifts")
    w("      during the session; both GPU programs fall together by 20 to 30 % in the GPU-heavy middle and recover only partly")
    w("A GPU figure is NOT an absolute value of the hardware: it depends on the state of the GPU clock at the time. Nothing here was")
    w("smoothed, re-measured or dropped. No further Mac session was run. The ratio C++ over Java is C++ evals/s divided by Java evals/s (above 1: C++ faster).")
    w("")
    base = os.path.basename(path)[:-4]
    w("Files: %s.tsv (every booked figure, one row each), %s-raw.tsv (the raw output of every run of the session, one section per run), %s-summary.txt" % (base, base, base))
    w("(merge-bench.pl over the raw data, with its four CHECK FAILED lines). Booked by cpp/scripts/book-nn.py; tests: cpp/scripts/test_book_nn.py.")
    w("")
    w("## Anchors (batch 256, evals/s)")
    arows = []
    for r in get(table="anchors"):
        arows.append([r["program"], r["case"].split()[0], fnum(r["anchor_start"], 1), fnum(r["anchor_mid"], 1), fnum(r["anchor_end"], 1),
                      "%.4f" % r["median"], "%.4f" % r["min"], "OK" if abs(r["median"] - 1) <= ANCHOR_TOL else "OUTSIDE 2 %", "V" if r["status"] == "valid" else "u"])
    w(table(["program", "preset", "start", "mid", "end", "end/start", "lowest/start", "check", "status"], arows))
    w("")
    w("## Throughput, evals/s (median over 3 pairs; ranges are in the TSV; V valid, u unsettled)")
    progs = ["java:scalar", "cpp:reference", "java:vector", "cpp:simd", "cpp:simd-nofma", "java:tornado", "cpp:metal-copy", "cpp:metal-shared"]
    for case in ("SMALL u5", "LARGE u5", "LARGE gen11"):
        w("")
        w("### %s" % case)
        trows = []
        for p in progs:
            cells = [cell(one("throughput", p, case, b)) if one("throughput", p, case, b) else (
                "skipped" if one("not_measured", p, case, b) else "-") for b in (1, 256, 1024)]
            if all(c == "-" for c in cells):
                continue
            trows.append([p] + cells)
        w(table(["program", "batch 1", "batch 256", "batch 1024"], trows))
    w("")
    w("## Ratio C++ over Java (median of the 3 per-pair ratios, [lowest, highest pair]); status and verdict")
    for case in ("SMALL u5", "LARGE u5", "LARGE gen11"):
        w("")
        w("### %s" % case)
        rrows = []
        for jp, cp in PAIRS:
            for b in (1, 256, 1024):
                r = one("ratio", "%s / %s" % (cp, jp), case, b, "cpp_over_java_evals_per_s")
                if not r:
                    continue
                v = re.search(r"(real: [^;]*|not resolved[^;]*|no statement[^;]*|indicative only[^;]*)", r["note"])
                rrows.append(["%s / %s" % (cp, jp), b, "%.3f [%.3f, %.3f]" % (r["median"], r["min"], r["max"]), "V" if r["status"] == "valid" else "u",
                              v.group(1) if v else ""])
        w(table(["C++ / Java", "batch", "ratio", "st", "verdict"], rrows))
    w("")
    w("## TornadoVM breakdown (SMALL and LARGE, u5 weights; all of it GPU-state dependent, status u)")
    w("")
    w("### Latency at batch 1, ms per call p50 / p90")
    lrows = []
    for p in progs:
        c = []
        for case in ("SMALL u5", "LARGE u5"):
            a, b = one("latency", p, case, 1, "p50_ms"), one("latency", p, case, 1, "p90_ms")
            c.append("%s / %s %s" % (fnum(a["median"], 3), fnum(b["median"], 3), "V" if a["status"] == "valid" else "u") if a else "-")
        lrows.append([p] + c)
    w(table(["program", "SMALL", "LARGE"], lrows))
    w("")
    w("### Phases per call, LARGE u5, ms (median; Java execute = host to device + kernels + device to host in ONE figure, not separable)")
    prow = []
    for p in ("java:tornado", "cpp:metal-copy", "cpp:metal-shared"):
        for b in (1, 64, 256, 512, 1024):
            g = lambda m: one("phases", p, "LARGE u5", b, m)
            if not g("wall_p50_ms"):
                continue
            tot = sum((g(m)["median"] or 0) for m in ("copy_in_ms", "execute_ms", "copy_out_ms", "host_priors_ms"))
            prow.append([p, b, fnum(g("copy_in_ms")["median"], 3), fnum(g("execute_ms")["median"], 3), fnum(g("copy_out_ms")["median"], 3),
                         fnum(g("host_priors_ms")["median"], 3), fnum(g("gpu_time_ms")["median"], 3) if g("gpu_time_ms") else "NA",
                         fnum(g("wall_p50_ms")["median"], 3), "%.3f" % (g("wall_p50_ms")["median"] - tot)])
    w("wall - sum = wall p50 minus the four host and execute figures; all are medians of different quantities, a difference of 0.1 ms is noise")
    w(table(["program", "batch", "copy_in", "execute", "copy_out", "host priors", "GPU time", "wall p50", "wall - sum"], prow))
    w("")
    w("### Kernel, device write and device read in a separate instrumented pass (slows the call), LARGE u5, ms")
    krow = []
    for p in ("java:tornado", "cpp:metal-copy"):
        for b in (1, 64, 256, 512, 1024):
            g = lambda m: one("profiled", p, "LARGE u5", b, m)
            if g("kernel_ms"):
                krow.append([p, b, fnum(g("wall_ms")["median"], 3), fnum(g("kernel_ms")["median"], 3), fnum(g("device_write_ms")["median"], 3), fnum(g("device_read_ms")["median"], 3)])
    w(table(["program", "batch", "wall", "kernel", "device write", "device read"], krow))
    w("")
    w("### Plan build, compilation, first call per bucket (LARGE u5, first call of the case in ms; C++ set-up from the first pair)")
    frow = []
    for p in ("java:tornado", "cpp:metal-copy", "cpp:metal-shared"):
        for b in (1, 16, 64, 256, 512, 1024):
            r = one("phases", p, "LARGE u5", b, "first_call_ms")
            if r:
                s1 = one("setup", p, "LARGE u5", b, "pipelines_ms")
                s2 = one("setup", p, "LARGE u5", b, "buffers_ms")
                frow.append([p, b, fnum(r["median"], 1), (fnum(s1["median"], 3) + " + " + fnum(s2["median"], 3)) if s1 else "-"])
    w(table(["program", "batch", "first call ms", "C++ pipelines + buffers ms"], frow))
    lib = get(table="setup", metric="library_compile_ms")
    if lib:
        w("C++ Metal library compile (newLibraryWithSource, once per process): %s ms" % fnum(lib[0]["median"], 3))
    w("")
    w("### Cold start, LARGE u5 (process wall is quantised, about 0.25 s steps; construct and first call are the program's own figures)")
    crow = []
    for r in get(table="cold_start", metric="first_call_ms"):
        pw = one("cold_start", r["program"], "LARGE u5", r["batch"], "process_wall_s")
        co = one("cold_start", r["program"], "LARGE u5", r["batch"], "construct_ms")
        up = one("cold_start", r["program"], "LARGE u5", r["batch"], "uptime_at_end_ms")
        crow.append([r["program"], r["batch"], fnum(pw["median"], 2), fnum(co["median"], 1), fnum(r["median"], 1), fnum(up["median"], 1), "V" if r["status"] == "valid" else "u"])
    w(table(["program", "batch", "process wall s", "construct ms", "first call ms", "uptime at end ms", "st"], crow))
    w("")
    w("## Cost of the checks (batch 256, release over release-hardened; a value below 1 means the unchecked build is slower; the verdict gives its extra time, 1/ratio - 1)")
    krows = []
    for r in get(table="checks_cost"):
        v = re.search(r"(not resolved[^;]*|the unchecked build [^;]*|no statement[^;]*|the checks cost [^;]*)", r["note"])
        krows.append([r["program"], r["case"].split()[0], "%.3f [%.3f, %.3f]" % (r["median"], r["min"], r["max"]), "V" if r["status"] == "valid" else "u", v.group(1) if v else ""])
    w(table(["program", "preset", "ratio [pairs]", "st", "verdict"], krows))
    w("")
    w("## Convolution kernel table (Java KernelsBenchmark against nn_bench --kernels; ms, status u, no output check in this table). Clocks differ: Java = kernelNanos of the TornadoVM profiler (arrays resident on the device, floor of about 0.5 ms per task, values below it not explained); C++ = GPUEndTime - GPUStartTime of one command buffer with one dispatch (buffers created before it, outside the span). The C++/Java column is indicative only.")
    kt = []
    seen = []
    for r in get(table="kernel_table", metric="kernel_ms"):
        if r["program"].startswith("java"):
            c = one("kernel_table", "cpp:nn_bench-kernels", r["case"], r["batch"], "kernel_ms")
            kt.append([r["case"], r["batch"], fnum(r["median"], 3), fnum(c["median"], 3), "%.3f" % (c["median"] / r["median"])])
    w(table(["case", "batch", "Java ms", "C++ ms", "C++/Java"], kt))
    w("")
    w("## Output checks (b)")
    for kind, t in report:
        w("  %-8s %2d steps, %4d rows, %4d verified ok, %2d cases skipped by the schedule (counted per step, 3 pairs)" % (kind, t["steps"], t["rows"], t["ok"], t["skipped"]))
    w("Every step ended with rc 0, every row has verified=ok, rows plus skipped cases equal the schedule of each step. A case that fails its check")
    w("prints no row and exits 2, so the raw data contain only checked rows. Not checked: the kernel table (kernel timings only), the cold-start")
    w("figures are of rows that passed the same check.")
    cw = one("verification", "cpp CPU cases", "thread CPU time over wall time", None, "ratio")
    w("C++ CPU cases, thread CPU time over wall time: min %.3f, median %.3f (%s)" % (cw["min"], cw["median"], cw["note"]))
    w("")
    w("## Development effort (plan, TornadoVM breakdown, rows 'Development effort')")
    for line in effort:
        w("  " + line)
    w("")
    w("## What is not measured or not meaningful")
    for line in NOT_MEANT:
        w("  - " + line)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")


NOT_MEANT = [
    "GPU absolute values: they depend on the GPU clock state (anchors of the same program differ by up to 29 % within the session); every GPU row is 'unsettled'.",
    "GPU ratios: formed per pair of adjacent processes; the GPU state inside each case was not recorded, so 'same state' is an assumption, not a measurement.",
    "No statement about Java against C++ on the GPU below 5 %; the larger ratios are indicative only.",
    "The Java vector rows for SMALL: their anchor moved by 2.85 % (outside 2 %), so they and every ratio built from them are 'unsettled'.",
    "Rows without an anchor of their own (Java scalar, C++ reference, simd-nofma, Metal shared) are judged by the anchor of the named program; this is a proxy.",
    "The load limit of the plan (2.0) was not met: the load average at the start of the cases was 2.08 to 4.59 (base load of this Mac, see the README); the window server used the GPU (about 17 to 20 % before every step).",
    "Cost of the checks for simd-nofma and Metal: negative or within noise; not explained and not a cost of the checks. SMALL simd, LARGE simd: not resolved.",
    "CUDA: not part of the Mac measurement. Java scalar and C++ reference at batches whose single call exceeds 3 s are skipped by the schedule (listed in the TSV, table not_measured).",
    "gen11 weights: only vector/simd/tornado/metal rows were run, not scalar, reference or simd-nofma.",
    "The Java kernel table lists values below the 0.5 ms floor that the plan names for the TornadoVM profiler; not investigated.",
    "Provenance: the raw header reads 'git commit b2ded13 (working tree has uncommitted changes)'; what the uncommitted changes were was not recorded (the commits between b2ded13 and the booking change only scripts and the README).",
    "Not run: any repetition of the session (the abort rule of the plan was reached after three invalid sessions; the lead decided to book).",
]


def effort_lines(repo_root, builds, sizes):
    ncloc = os.path.join(repo_root, "cpp", "scripts", "ncloc.pl")

    def count(files):
        out = subprocess.run(["perl", ncloc] + [os.path.join(repo_root, f) for f in files], capture_output=True, text=True, check=True).stdout.strip().split("\n")[-1]
        return int(out.split("\t")[0])

    t = "nn-tornado/src/main/java/sternhalma/tornado/"
    java = count([t + n + ".java" for n in ["TornadoNetwork", "KernelsHexConv", "KernelsHexConvLayer", "OpsKernels", "OpsHeads", "OpsParameters", "KernelsDense",
                                           "DeviceMath", "DeviceBackend", "TornadoSupport", "OpsCopy", "ExecutionProfile"]])
    metal = count(["cpp/src/gpu/metal/metal_net.mm", "cpp/src/gpu/metal/kernels.metal"])
    cuda = count(["cpp/src/gpu/cuda/cuda_net.cu", "cpp/src/gpu/cuda/kernels.cuh", "cpp/src/nn/cuda_backend.cpp"])
    lines = ["NCLOC (cpp/scripts/ncloc.pl): TornadoVM inference path (Java, one source for Metal and CUDA) %d; C++ Metal backend (metal_net.mm + kernels.metal) %d; "
             "C++ CUDA backend (cuda_net.cu + kernels.cuh + cuda_backend.cpp) %d; the two C++ backends together %d" % (java, metal, cuda, metal + cuda),
             "Agent-hours per backend: not recorded, not stated."]
    for b in builds:
        lines.append("Build time: " + b)
    for s in sizes:
        lines.append("Size: " + s)
    return lines


def main(argv):
    args = argv[1:]
    if len(args) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    work, prefix = args[0], args[1]
    date, builds, sizes = "unknown", [], []
    merge = os.path.join(os.path.dirname(os.path.abspath(__file__)), "merge-bench.pl")
    i = 2
    while i < len(args):
        if args[i] == "--date":
            date = args[i + 1]
        elif args[i] == "--build":
            builds.append(args[i + 1])
        elif args[i] == "--size":
            sizes.append(args[i + 1])
        elif args[i] == "--merge-bench":
            merge = args[i + 1]
        else:
            print("unknown option " + args[i], file=sys.stderr)
            return 2
        i += 2
    try:
        session = Session(work)
        report = verify(session)
    except BookingError as e:
        print("book-nn: verification failed: %s" % e, file=sys.stderr)
        return 3
    summary = subprocess.run(["perl", merge, work], capture_output=True, text=True, check=True).stdout
    book = Book(session)
    book.anchor_table()
    book.throughput()
    book.ratios()
    book.latency()
    book.phases()
    book.setup()
    book.checks()
    book.cold()
    n = book.from_summary(summary)
    book.verification(report)
    book.cpu_wall()
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    effort = effort_lines(root, builds, sizes)
    write_tsv(prefix + ".tsv", book.rows)
    write_summary(prefix + ".txt", book, date, report, effort)
    unsettled = sum(1 for r in book.rows if r["status"] == "unsettled")
    print("booked %d rows (%d valid, %d unsettled, %d not measured); kernel-table lines %d, instrumented lines %d" % (
        len(book.rows), sum(1 for r in book.rows if r["status"] == "valid"), unsettled,
        sum(1 for r in book.rows if r["status"] == "not measured"), n["kernel"], n["profiled"]), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
