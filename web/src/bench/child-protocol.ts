// What a benchmark child process (web/bench/child.ts) prints on stdout, and how the parent turns it into an EngineReport.
// The parent starts one child per engine, so the peak RSS of a child belongs to one engine only.
import type { LoadReport } from '../engines/engine';
import { findExpected, summarize } from './core';
import type { BenchSpec, BenchTarget, EngineReport, Expected, GameStat } from './core';
import { processResources } from './resources';

export interface ChildMeasure {
  /** Wall time around all timed games (not the warm-up, not the load). */
  wallMs: number;
  /** process.cpuUsage() user + system over the same interval. */
  cpuMs: number;
  /** RSS before the engine is loaded, after the load, and process.resourceUsage().maxRSS at the end. */
  rssStartBytes: number;
  rssAfterLoadBytes: number;
  peakRssBytes: number;
}

export interface ChildOutput {
  v: 1;
  id: string;
  info: BenchTarget['info'];
  load: LoadReport;
  games: GameStat[];
  measure: ChildMeasure;
}

/** One line of JSON. The checksum is a decimal string because it does not fit a double. */
export function encodeChild(out: ChildOutput): string {
  return JSON.stringify({ ...out, games: out.games.map((g) => ({ ...g, fingerprint: { checksum: String(g.fingerprint.checksum), totalNodes: g.fingerprint.totalNodes } })) });
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

export function decodeChild(text: string): ChildOutput {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new Error(`the child output is not JSON: ${text.slice(0, 80)}`);
  }
  if (!isObject(raw)) throw new Error('the child output is not an object');
  if (raw.v !== 1) throw new Error(`unsupported child output version ${String(raw.v)}`);
  for (const key of ['id', 'info', 'load', 'games', 'measure']) {
    if (raw[key] === undefined) throw new Error(`the child output lacks "${key}"`);
  }
  if (!Array.isArray(raw.games)) throw new Error('the child output has no games array');
  const games = (raw.games as Array<Record<string, unknown>>).map((g) => {
    const f = g.fingerprint as { checksum: string; totalNodes: number };
    return { ...g, fingerprint: { checksum: BigInt(f.checksum), totalNodes: f.totalNodes } } as unknown as GameStat;
  });
  return { ...raw, games } as unknown as ChildOutput;
}

/** The fingerprint check is the unchanged `summarize`; the resources come from the child's measurements. */
export function reportFromChild(out: ChildOutput, spec: Pick<BenchSpec, 'plies' | 'nodes'>, expected: Expected | null): EngineReport {
  const target: BenchTarget = { id: out.id, info: out.info, bench: () => { throw new Error('the child already played'); } };
  const report = summarize(target, out.load, out.games, findExpected(expected, spec));
  const wasm = out.games.map((g) => g.memory?.wasmBytes ?? null).filter((v): v is number => v !== null);
  return {
    ...report,
    resources: processResources({ ...out.measure, wasmMemoryBytes: wasm.length ? Math.max(...wasm) : null }),
  };
}
