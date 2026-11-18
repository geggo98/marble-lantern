// Turns the games of an engine that ran in a worker of a browser page into resources and into rows of the benchmark screen.
// A browser offers little: wall time of the searches, the Wasm memory size the C++ adapter reads, and in Chrome the JS heap.
// Everything else is n/a with a reason code (see resources.ts); nothing is estimated.
import type { BenchRow } from '../ui/Screens';
import type { EngineReport } from './core';
import { browserResources } from './resources';
import type { NaCode } from './resources';

const maxOf = (values: Array<number | null | undefined>): number | null => {
  const v = values.filter((x): x is number => typeof x === 'number');
  return v.length ? Math.max(...v) : null;
};

/** Adds `resources` to a report. Wall time is the sum of the engine's own search timings over the timed games. */
export function browserReport(report: EngineReport, wasmGc: boolean): EngineReport {
  const wallMs = report.games.reduce((sum, g) => sum + g.msPerMove.reduce((a, b) => a + b, 0), 0);
  return {
    ...report,
    resources: browserResources({
      wallMs,
      wasmMemoryBytes: maxOf(report.games.map((g) => g.memory?.wasmBytes)),
      jsHeapBytes: maxOf(report.games.map((g) => g.memory?.jsHeapBytes)),
      wasmGc,
    }),
  };
}

export function toBenchRows(reports: readonly EngineReport[]): BenchRow[] {
  return reports.map((r) => ({
    engineLabel: r.id,
    fingerprintOk: r.status === 'ok' ? true : r.status === 'mismatch' ? false : null,
    nodesPerSecond: r.nodesPerSecond,
    msPerMove: r.msPerMove,
    spread: r.spread ?? 1,
    loadMs: r.load?.totalMs ?? 0,
    binaryBytes: r.binaryBytes,
    binaryGzipBytes: r.binaryGzipBytes > 0 ? r.binaryGzipBytes : null,
    rows1Requests: r.rows1Requests,
    cpuMs: r.resources?.cpuMs ?? null,
    cpuNa: r.resources?.na.cpu ?? null,
    memoryBytes: r.resources?.peakMemoryBytes ?? null,
    memoryKind: r.resources?.peakMemoryKind ?? null,
    memoryNa: r.resources?.na.memory ?? null,
  }));
}

interface UaMemoryPerformance {
  measureUserAgentSpecificMemory?: () => Promise<{ bytes: number }>;
}

/**
 * Memory of the whole page and its workers, as the browser counts it. Only a cross-origin isolated page may ask (COOP and
 * COEP headers), and GitHub Pages cannot send them, so the live site gets n/a. The call is skipped, not tried, without isolation.
 */
export async function userAgentMemory(
  scope: { crossOriginIsolated?: boolean },
  perf: UaMemoryPerformance,
): Promise<{ bytes: number | null; na: NaCode | null }> {
  if (!scope.crossOriginIsolated) return { bytes: null, na: 'not-isolated' };
  if (typeof perf.measureUserAgentSpecificMemory !== 'function') return { bytes: null, na: 'no-ua-memory-api' };
  try {
    return { bytes: (await perf.measureUserAgentSpecificMemory()).bytes, na: null };
  } catch {
    return { bytes: null, na: 'no-ua-memory-api' };
  }
}
