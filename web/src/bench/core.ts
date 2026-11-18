// The benchmark of plan section 8, independent of where it runs: it needs only an object that plays the benchmark game
// (BenchTarget). The node runner (bench/run-node.ts) wraps CppCore and JavaCore; the browser page (page.ts) wraps a worker.
//
// One game: `plies` plies from the start position, both sides `nodes` per move, one player per side for the whole game.
// The time is the sum of the engine's own `choose` timings. The fingerprint is (checksum, total nodes).
import type { BenchResult, LoadReport } from '../engines/engine';
import { formatCpu, formatMemory, formatRatio } from './resources';
import type { Resources } from './resources';

/** What the benchmark needs from an engine. `bench` plays one whole game. */
export interface BenchTarget {
  readonly id: string;
  readonly info: { runtime: string; language: string; binaryBytes: number; binaryGzipBytes: number; version: string };
  bench(plies: number, nodes: number): BenchResult | Promise<BenchResult>;
}

/** One line of expected.json. `checksum` is a decimal string because it does not fit a double. */
export interface ExpectedEntry {
  plies: number;
  nodes: number;
  checksum: string;
  totalNodes: number;
}

export interface Expected {
  v: 1;
  entries: ExpectedEntry[];
}

export interface Fingerprint {
  checksum: bigint;
  totalNodes: number;
}

export interface GameStat {
  fingerprint: Fingerprint;
  /** Total nodes over the sum of the choose times. */
  nodesPerSecond: number;
  /** ms of every choose call, in ply order. */
  msPerMove: number[];
  rows1Requests: number;
  /** Memory sizes the engine reported after the game (see BenchResult.memory). */
  memory?: { wasmBytes: number | null; jsHeapBytes: number | null };
}

export type FingerprintStatus = 'ok' | 'mismatch' | 'unchecked';

export interface EngineReport {
  id: string;
  language: string;
  runtime: string;
  version: string;
  binaryBytes: number;
  binaryGzipBytes: number;
  load: LoadReport | null;
  games: GameStat[];
  status: FingerprintStatus;
  /** What differs, or why nothing could be compared. Empty for "ok". */
  detail: string;
  rows1Requests: number;
  /** Median nodes per second over the runs. */
  nodesPerSecond: number;
  /** Median ms per move over all moves of all runs. */
  msPerMove: number;
  /** max/min of the nodes per second of the runs; null with fewer than two runs. */
  spread: number | null;
  /** CPU time and memory of the engine's self-play phase; absent in results of older runners (shown as n/a). */
  resources?: Resources;
}

export interface BenchSpec {
  plies: number;
  nodes: number;
  runs: number;
}

/** A spread above this value (max/min of the runs) means the machine was too busy for the nodes/s figure to be trusted. */
export const NOISY_SPREAD = 1.05;

/** checksum = checksum * 31 + move, signed 64-bit wrap-around, over the moves in order. */
export function checksumOf(moves: readonly number[]): bigint {
  let c = 0n;
  for (const m of moves) c = BigInt.asIntN(64, c * 31n + BigInt(m));
  return c;
}

export const median = (values: readonly number[]): number => {
  if (values.length === 0) return NaN;
  const s = [...values].sort((a, b) => a - b);
  const mid = s.length >> 1;
  return s.length % 2 ? s[mid]! : (s[mid - 1]! + s[mid]!) / 2;
};

const sum = (values: readonly number[]): number => values.reduce((a, b) => a + b, 0);

/** Validates the shape of a result and turns it into a GameStat. A malformed result is a bug of the adapter: throw. */
export function toGameStat(result: BenchResult, plies: number): GameStat {
  const lengths = [result.moves.length, result.nodes.length, result.ms.length];
  if (lengths.some((n) => n !== plies)) {
    throw new Error(`the engine returned ${lengths.join('/')} moves, nodes and timings for ${plies} plies`);
  }
  const ms = sum(result.ms);
  const totalNodes = sum(result.nodes);
  return {
    fingerprint: { checksum: checksumOf(result.moves), totalNodes },
    nodesPerSecond: ms > 0 ? totalNodes / (ms / 1000) : NaN,
    msPerMove: [...result.ms],
    rows1Requests: result.rows1Requests,
    ...(result.memory ? { memory: result.memory } : {}),
  };
}

export function parseExpected(text: string): Expected {
  const v = JSON.parse(text) as Partial<Expected> | null;
  const ok = (e: Partial<ExpectedEntry>): boolean =>
    Number.isInteger(e.plies) && Number.isInteger(e.nodes) && Number.isInteger(e.totalNodes) &&
    typeof e.checksum === 'string' && /^-?\d+$/.test(e.checksum);
  if (!v || v.v !== 1 || !Array.isArray(v.entries) || !v.entries.every(ok)) {
    throw new Error('expected.json has an unexpected shape');
  }
  return v as Expected;
}

export const findExpected = (expected: Expected | null, spec: Pick<BenchSpec, 'plies' | 'nodes'>): ExpectedEntry | undefined =>
  expected?.entries.find((e) => e.plies === spec.plies && e.nodes === spec.nodes);

/** Every game must give the same fingerprint (a search that depends on the run is broken) and it must equal the expected one. */
export function judge(games: readonly GameStat[], expected: ExpectedEntry | undefined): { status: FingerprintStatus; detail: string } {
  const first = games[0];
  if (!first) return { status: 'mismatch', detail: 'no game was played' };
  for (const [i, g] of games.entries()) {
    if (g.fingerprint.checksum !== first.fingerprint.checksum || g.fingerprint.totalNodes !== first.fingerprint.totalNodes) {
      return { status: 'mismatch', detail: `run ${i + 1} differs from run 1: ${show(g.fingerprint)} instead of ${show(first.fingerprint)}` };
    }
  }
  if (!expected) return { status: 'unchecked', detail: `no expected value for this plies/nodes; got ${show(first.fingerprint)}` };
  if (first.fingerprint.checksum !== BigInt(expected.checksum) || first.fingerprint.totalNodes !== expected.totalNodes) {
    return { status: 'mismatch', detail: `got ${show(first.fingerprint)}, expected checksum=${expected.checksum} nodes=${expected.totalNodes}` };
  }
  return { status: 'ok', detail: '' };
}

const show = (f: Fingerprint): string => `checksum=${f.checksum} nodes=${f.totalNodes}`;

export function summarize(
  target: BenchTarget,
  load: LoadReport | null,
  games: GameStat[],
  expected: ExpectedEntry | undefined,
): EngineReport {
  const rates = games.map((g) => g.nodesPerSecond);
  const { status, detail } = judge(games, expected);
  return {
    id: target.id,
    language: target.info.language,
    runtime: target.info.runtime,
    version: target.info.version,
    binaryBytes: target.info.binaryBytes,
    binaryGzipBytes: target.info.binaryGzipBytes,
    load,
    games,
    status,
    detail,
    rows1Requests: Math.max(0, ...games.map((g) => g.rows1Requests)),
    nodesPerSecond: median(rates),
    msPerMove: median(games.flatMap((g) => g.msPerMove)),
    spread: games.length >= 2 ? Math.max(...rates) / Math.min(...rates) : null,
  };
}

export interface RunHooks {
  /** Called after each game, for progress lines. */
  onGame?(info: { engine: string; run: number; runs: number; stat: GameStat }): void;
  /** Checked between games: return true to stop. The running game is never cut short. */
  shouldStop?(): boolean;
  /** Where the loads of the engines come from (the first `init`); optional. */
  loads?: ReadonlyMap<string, LoadReport>;
}

/** Warm-up game length in plies (plan section 8). */
export const WARMUP_PLIES = 2;

/**
 * Plays the benchmark on every target. Each engine first plays the 2-ply warm-up game, then `spec.runs` games. The order of
 * the engines rotates from run to run (run r starts with engine r mod n), so that a slow phase of the machine does not
 * always hit the same engine. Returns null for a stopped run.
 */
export async function runBenchmark(
  targets: readonly BenchTarget[],
  spec: BenchSpec,
  expected: Expected | null,
  hooks: RunHooks = {},
): Promise<EngineReport[] | null> {
  const games = new Map<string, GameStat[]>(targets.map((t) => [t.id, []]));
  for (const t of targets) {
    if (hooks.shouldStop?.()) return null;
    await t.bench(WARMUP_PLIES, spec.nodes);
  }
  for (let run = 0; run < spec.runs; run++) {
    for (let k = 0; k < targets.length; k++) {
      if (hooks.shouldStop?.()) return null;
      const t = targets[(run + k) % targets.length]!;
      const stat = toGameStat(await t.bench(spec.plies, spec.nodes), spec.plies);
      games.get(t.id)!.push(stat);
      hooks.onGame?.({ engine: t.id, run: run + 1, runs: spec.runs, stat });
    }
  }
  const entry = findExpected(expected, spec);
  return targets.map((t) => summarize(t, hooks.loads?.get(t.id) ?? null, games.get(t.id)!, entry));
}

// ---- output ----

const fixed = (v: number, digits = 0): string => (Number.isFinite(v) ? v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits }) : 'n/a');
const kb = (bytes: number): string => `${(bytes / 1024).toFixed(0)}`;

export function fingerprintText(r: EngineReport): string {
  return r.status === 'ok' ? 'ok' : r.status === 'unchecked' ? `unchecked (${r.detail})` : `MISMATCH (${r.detail})`;
}

/** The one-row-per-engine table. Plain text with aligned columns, the same in the terminal and on the page. */
export function formatTable(reports: readonly EngineReport[]): string {
  const header = ['engine', 'check', 'rows1', 'nodes/s', 'ms/move', 'spread', 'cpu ms', 'cpu/wall', 'peak MiB', 'load ms', 'size KiB (gzip)'];
  const rows = reports.map((r) => [
    r.id,
    r.status === 'ok' ? 'fingerprint=ok' : r.status === 'unchecked' ? 'fingerprint=unchecked' : 'fingerprint=MISMATCH',
    String(r.rows1Requests),
    fixed(r.nodesPerSecond),
    fixed(r.msPerMove, 2),
    r.spread === null ? 'n/a' : `${r.spread.toFixed(3)}${r.spread > NOISY_SPREAD ? ' noisy' : ''}`,
    formatCpu(r.resources?.cpuMs ?? null),
    formatRatio(r.resources?.cpuToWall ?? null),
    formatMemory(r.resources?.peakMemoryBytes ?? null),
    r.load ? fixed(r.load.totalMs) : 'n/a',
    r.binaryBytes > 0 ? `${kb(r.binaryBytes)} (${kb(r.binaryGzipBytes)})` : 'n/a',
  ]);
  const width = header.map((h, c) => Math.max(h.length, ...rows.map((row) => row[c]!.length)));
  const line = (cells: string[]): string => cells.map((cell, c) => (c < 2 || c === header.length - 1 ? cell.padEnd(width[c]!) : cell.padStart(width[c]!))).join('  ').trimEnd();
  return [line(header), ...rows.map(line)].join('\n');
}

/** The load fields of the first init, one line per engine. */
export function formatLoads(reports: readonly EngineReport[]): string {
  return reports
    .filter((r) => r.load)
    .map((r) => {
      const l = r.load!;
      return `${r.id}: fetch ${fixed(l.fetchMs)} ms, inflate ${fixed(l.inflateMs)} ms, verify ${fixed(l.verifyMs)} ms, ` +
        `instantiate ${fixed(l.instantiateMs)} ms, table install ${fixed(l.tableInstallMs)} ms, total ${fixed(l.totalMs)} ms (verified by ${l.tableVerifiedBy})`;
    })
    .join('\n');
}

/** JSON for the result file and for the copy button; BigInt becomes a decimal string. */
export function toJson(reports: readonly EngineReport[], spec: BenchSpec, environment: unknown): string {
  return JSON.stringify(
    { v: 1, spec, environment, engines: reports.map((r) => ({ ...r, games: r.games.map((g) => ({ ...g, fingerprint: { checksum: String(g.fingerprint.checksum), totalNodes: g.fingerprint.totalNodes } })) })) },
    null,
    2,
  );
}

export function parseSpec(plies: unknown, nodes: unknown, runs: unknown): BenchSpec {
  const n = (v: unknown, name: string): number => {
    const x = Number(v);
    if (!Number.isInteger(x) || x < 1) throw new Error(`${name} must be a whole number of at least 1, got ${String(v)}`);
    return x;
  };
  return { plies: n(plies, 'plies'), nodes: n(nodes, 'nodes'), runs: n(runs, 'runs') };
}
