// CPU time and memory of a benchmark run, as far as the runtime can tell. The pure parts live here so that the node runner,
// the browser page and the tests share them. Rule: a value that cannot be measured is `null` with a reason code, never 0 and
// never an estimate.

/** Why a figure is not available. The UI maps each code to a text; `naText` is the English text for the terminal and docs. */
export type NaCode = 'no-cpu-api' | 'no-rss-api' | 'jvm-via-gradle' | 'wasm-gc-heap' | 'no-js-heap-api' | 'not-isolated' | 'no-ua-memory-api';

/** What the "peak memory" figure is. The three are different things and must not be compared with each other blindly. */
export type MemoryKind = 'process-rss' | 'wasm-linear-memory' | 'js-heap-sampled';

export interface Resources {
  /** Wall time of the self-play phase (all timed games, measured around them), ms. */
  wallMs: number | null;
  /** User plus system CPU time of that phase, ms. */
  cpuMs: number | null;
  /** cpuMs / wallMs. About 1.0 for a 40-ply game on a quiet machine (measured: 1.00 to 1.01); a short game shows more than 1 because the runtime compiles code on other threads. Well below 1 means the process waited for a CPU. */
  cpuToWall: number | null;
  /** Resident set size of the engine's process before the engine is loaded, after the load, and its peak over the whole life. */
  rssStartBytes: number | null;
  rssAfterLoadBytes: number | null;
  peakRssBytes: number | null;
  /** Size of the Wasm linear memory (cpp-wasm only). It never shrinks, so the last size is the peak. */
  wasmMemoryBytes: number | null;
  /** Used JS heap, highest value sampled after a game (Chrome `performance.memory` in the worker). */
  jsHeapBytes: number | null;
  /** The figure for the "peak memory" column and what it is. */
  peakMemoryBytes: number | null;
  peakMemoryKind: MemoryKind | null;
  /** Reasons for the two displayed columns when they are null. */
  na: { cpu?: NaCode; memory?: NaCode };
}

const NA_TEXT: Record<NaCode, string> = {
  'no-cpu-api': 'a browser page has no API for the CPU time of a worker',
  'no-rss-api': 'this runtime has no API for the resident memory of the process',
  'jvm-via-gradle': 'the JVM row runs through Gradle; its process also holds Gradle and the compiler, so its CPU time and memory would not describe the engine',
  'wasm-gc-heap': 'Wasm GC objects live in the browser garbage-collected heap, which no page API reports for a worker',
  'no-js-heap-api': 'performance.memory is not offered inside a worker (absent in Chromium workers, measured 2026-11-18)',
  'not-isolated': 'performance.measureUserAgentSpecificMemory needs a cross-origin isolated page, which this host does not serve',
  'no-ua-memory-api': 'this browser has no performance.measureUserAgentSpecificMemory (Chrome only), or it refused the call',
};

export const naText = (code: NaCode): string => NA_TEXT[code];

const KIND_TEXT: Record<MemoryKind, string> = {
  'process-rss': 'peak resident set size of the engine\'s own process (includes the Node.js runtime)',
  'wasm-linear-memory': 'size of the Wasm linear memory (HEAPU8.byteLength; Wasm memory never shrinks, so it is the peak)',
  'js-heap-sampled': 'JS heap in use (performance.memory), highest value sampled after a game; not a true peak',
};

export function cpuMsFromUsage(before: { user: number; system: number }, after: { user: number; system: number }): number {
  const micros = after.user - before.user + (after.system - before.system);
  if (micros < 0) throw new Error('the CPU counter went backwards');
  return micros / 1000;
}

export const cpuToWall = (cpuMs: number | null, wallMs: number | null): number | null =>
  cpuMs === null || wallMs === null || wallMs <= 0 ? null : cpuMs / wallMs;

/** `process.resourceUsage().maxRSS` is in KiB on Linux and macOS (libuv converts the byte count of macOS). */
export const maxRssBytes = (maxRssKiB: number): number => maxRssKiB * 1024;

const empty = (): Resources => ({
  wallMs: null, cpuMs: null, cpuToWall: null, rssStartBytes: null, rssAfterLoadBytes: null, peakRssBytes: null,
  wasmMemoryBytes: null, jsHeapBytes: null, peakMemoryBytes: null, peakMemoryKind: null, na: {},
});

/** Both columns n/a, with a reason each. */
export const unavailableResources = (cpu: NaCode, memory: NaCode): Resources => ({ ...empty(), na: { cpu, memory } });

export interface ProcessMeasure {
  wallMs: number;
  cpuMs: number;
  rssStartBytes: number;
  rssAfterLoadBytes: number;
  peakRssBytes: number;
  wasmMemoryBytes: number | null;
}

/** An engine that ran in its own child process. */
export function processResources(m: ProcessMeasure): Resources {
  return {
    ...empty(),
    wallMs: m.wallMs, cpuMs: m.cpuMs, cpuToWall: cpuToWall(m.cpuMs, m.wallMs),
    rssStartBytes: m.rssStartBytes, rssAfterLoadBytes: m.rssAfterLoadBytes, peakRssBytes: m.peakRssBytes,
    wasmMemoryBytes: m.wasmMemoryBytes, peakMemoryBytes: m.peakRssBytes, peakMemoryKind: 'process-rss',
  };
}

export interface BrowserMeasure {
  wallMs: number;
  wasmMemoryBytes: number | null;
  jsHeapBytes: number | null;
  /** The engine is a Wasm GC build. */
  wasmGc: boolean;
}

/** An engine in a worker of a page: wall time, and the memory figure the browser offers. CPU time is always n/a. */
export function browserResources(m: BrowserMeasure): Resources {
  const r: Resources = { ...empty(), wallMs: m.wallMs, wasmMemoryBytes: m.wasmMemoryBytes, jsHeapBytes: m.jsHeapBytes, na: { cpu: 'no-cpu-api' } };
  if (m.wasmMemoryBytes !== null) {
    r.peakMemoryBytes = m.wasmMemoryBytes;
    r.peakMemoryKind = 'wasm-linear-memory';
  } else if (m.wasmGc) {
    r.na.memory = 'wasm-gc-heap';
  } else if (m.jsHeapBytes !== null) {
    r.peakMemoryBytes = m.jsHeapBytes;
    r.peakMemoryKind = 'js-heap-sampled';
  } else {
    r.na.memory = 'no-js-heap-api';
  }
  return r;
}

export interface LoadInput {
  cores: number;
  loadAverage1: number | null;
  cpuToWall: number | null;
  spread: number | null;
}

/** Below this CPU/wall ratio the process waited for a CPU for more than 15 % of the time. */
export const MIN_CPU_TO_WALL = 0.85;
/** Above this share of the cores, the 1-minute load average says the machine was busy. */
export const MAX_LOAD_PER_CORE = 0.5;
const NOISY = 1.05;

/** Plain-text warnings that the numbers of a run may not be quiet-machine figures. Empty when nothing looks wrong. */
export function loadWarnings(i: LoadInput): string[] {
  const out: string[] = [];
  if (i.loadAverage1 !== null && i.loadAverage1 > i.cores * MAX_LOAD_PER_CORE) {
    out.push(`the 1-minute load average ${i.loadAverage1.toFixed(2)} is above half of the ${i.cores} cores: other jobs were running`);
  }
  if (i.cpuToWall !== null && i.cpuToWall < MIN_CPU_TO_WALL) {
    out.push(`CPU time is ${i.cpuToWall.toFixed(2)} of wall time (expected 1.0 or more): the engine waited for a CPU, so the machine was busy or throttled`);
  }
  if (i.spread !== null && i.spread > NOISY) {
    out.push(`the spread of the timed games is ${i.spread.toFixed(3)} (limit ${NOISY}): repeat the run before quoting nodes/s`);
  }
  return out;
}

// ---- formatting ----

const num = (v: number, digits: number): string => v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
export const formatCpu = (cpuMs: number | null): string => (cpuMs === null ? 'n/a' : num(cpuMs, 0));
export const formatMemory = (bytes: number | null): string => (bytes === null ? 'n/a' : num(bytes / (1024 * 1024), 1));
export const formatRatio = (ratio: number | null): string => (ratio === null ? 'n/a' : num(ratio, 2));

/** The footnotes of the table: each n/a reason with the engines it applies to, and what the memory figure is per engine kind. */
export function formatNotes(reports: ReadonlyArray<{ id: string; resources?: Resources }>): string {
  const lines: string[] = [];
  const group = (column: 'cpu' | 'memory', label: string): void => {
    const byCode = new Map<NaCode, string[]>();
    for (const r of reports) {
      const code = r.resources?.na[column];
      if (code) byCode.set(code, [...(byCode.get(code) ?? []), r.id]);
    }
    for (const [code, ids] of byCode) lines.push(`${label}: n/a for ${ids.join(', ')}: ${naText(code)}`);
  };
  group('cpu', 'cpu ms');
  group('memory', 'peak MiB');
  const kinds = new Map<MemoryKind, string[]>();
  for (const r of reports) {
    const kind = r.resources?.peakMemoryKind;
    if (kind) kinds.set(kind, [...(kinds.get(kind) ?? []), r.id]);
  }
  for (const [kind, ids] of kinds) lines.push(`peak MiB for ${ids.join(', ')}: ${KIND_TEXT[kind]}`);
  return lines.join('\n');
}
