import { describe, expect, it } from 'vitest';
import type { BenchResult } from '../engines/engine';
import { runBenchmark, toJson, formatTable } from './core';
import type { BenchTarget, EngineReport } from './core';
import {
  browserResources, cpuMsFromUsage, cpuToWall, formatCpu, formatMemory, formatNotes, formatRatio, loadWarnings, maxRssBytes,
  naText, processResources, unavailableResources,
} from './resources';

const MIB = 1024 * 1024;

describe('cpuMsFromUsage', () => {
  it('adds the user and system deltas and turns microseconds into ms', () => {
    expect(cpuMsFromUsage({ user: 1_500, system: 500 }, { user: 3_500, system: 1_500 })).toBe(3);
  });
  it('refuses a counter that went backwards instead of reporting a negative time', () => {
    expect(() => cpuMsFromUsage({ user: 10, system: 0 }, { user: 5, system: 0 })).toThrow(/backwards/);
  });
});

describe('cpuToWall', () => {
  it('divides CPU time by wall time', () => {
    expect(cpuToWall(900, 1000)).toBe(0.9);
  });
  it('is null for a missing value or a wall time of zero', () => {
    expect(cpuToWall(null, 1000)).toBeNull();
    expect(cpuToWall(5, 0)).toBeNull();
    expect(cpuToWall(5, null)).toBeNull();
  });
});

describe('maxRssBytes', () => {
  it('takes the KiB of process.resourceUsage().maxRSS', () => {
    expect(maxRssBytes(2048)).toBe(2 * MIB);
  });
});

describe('processResources', () => {
  const input = { wallMs: 1000, cpuMs: 980, rssStartBytes: 40 * MIB, rssAfterLoadBytes: 70 * MIB, peakRssBytes: 90 * MIB, wasmMemoryBytes: 48 * MIB };
  it('derives the ratio and uses the process peak RSS as the memory figure', () => {
    const r = processResources(input);
    expect(r.cpuToWall).toBeCloseTo(0.98, 5);
    expect(r.peakMemoryBytes).toBe(90 * MIB);
    expect(r.peakMemoryKind).toBe('process-rss');
    expect(r.na).toEqual({});
  });
  it('keeps the wasm memory size beside it', () => {
    expect(processResources(input).wasmMemoryBytes).toBe(48 * MIB);
    expect(processResources({ ...input, wasmMemoryBytes: null }).wasmMemoryBytes).toBeNull();
  });
});

describe('browserResources', () => {
  it('never invents CPU time: n/a with the reason', () => {
    const r = browserResources({ wallMs: 800, wasmMemoryBytes: null, jsHeapBytes: null, wasmGc: false });
    expect(r.cpuMs).toBeNull();
    expect(r.na.cpu).toBe('no-cpu-api');
  });
  it('uses the wasm linear memory when there is one', () => {
    const r = browserResources({ wallMs: 800, wasmMemoryBytes: 64 * MIB, jsHeapBytes: 5 * MIB, wasmGc: false });
    expect(r.peakMemoryBytes).toBe(64 * MIB);
    expect(r.peakMemoryKind).toBe('wasm-linear-memory');
    expect(r.na.memory).toBeUndefined();
  });
  it('falls back to the sampled JS heap, and says n/a without it', () => {
    expect(browserResources({ wallMs: 1, wasmMemoryBytes: null, jsHeapBytes: 5 * MIB, wasmGc: false }).peakMemoryKind).toBe('js-heap-sampled');
    expect(browserResources({ wallMs: 1, wasmMemoryBytes: null, jsHeapBytes: null, wasmGc: false }).na.memory).toBe('no-js-heap-api');
  });
  it('Wasm GC memory lives in the GC heap, which no API reports', () => {
    const r = browserResources({ wallMs: 1, wasmMemoryBytes: null, jsHeapBytes: null, wasmGc: true });
    expect(r.peakMemoryBytes).toBeNull();
    expect(r.na.memory).toBe('wasm-gc-heap');
  });
});

describe('loadWarnings', () => {
  const quiet = { cores: 16, loadAverage1: 1.2, cpuToWall: 1.0, spread: 1.01 };
  it('is silent on a quiet machine', () => {
    expect(loadWarnings(quiet)).toEqual([]);
  });
  it('warns about a busy machine, a starved process and a noisy spread', () => {
    expect(loadWarnings({ ...quiet, loadAverage1: 9 }).join(' ')).toMatch(/load average/);
    expect(loadWarnings({ ...quiet, cpuToWall: 0.6 }).join(' ')).toMatch(/CPU time is 0\.60/);
    expect(loadWarnings({ ...quiet, spread: 1.2 }).join(' ')).toMatch(/spread/);
  });
  it('ignores values that are not known', () => {
    expect(loadWarnings({ cores: 16, loadAverage1: null, cpuToWall: null, spread: null })).toEqual([]);
  });
  it('at the limits: load = cores/2 is fine, above is not; ratio 0.85 is fine, below is not', () => {
    expect(loadWarnings({ ...quiet, loadAverage1: 8 })).toEqual([]);
    expect(loadWarnings({ ...quiet, loadAverage1: 8.01 })).toHaveLength(1);
    expect(loadWarnings({ ...quiet, cpuToWall: 0.85 })).toEqual([]);
    expect(loadWarnings({ ...quiet, cpuToWall: 0.849 })).toHaveLength(1);
  });
});

describe('formatting', () => {
  it('writes n/a, never 0, for a missing value', () => {
    expect(formatCpu(null)).toBe('n/a');
    expect(formatMemory(null)).toBe('n/a');
    expect(formatRatio(null)).toBe('n/a');
    expect(formatCpu(1234.5)).toBe('1,235');
    expect(formatMemory(90 * MIB)).toBe('90.0');
    expect(formatRatio(0.98765)).toBe('0.99');
  });
  it('gives every n/a code a reason text', () => {
    for (const c of ['no-cpu-api', 'no-rss-api', 'jvm-via-gradle', 'wasm-gc-heap', 'no-js-heap-api', 'not-isolated'] as const) {
      expect(naText(c).length).toBeGreaterThan(20);
    }
  });
  it('the notes list each n/a reason once, with the engines it applies to', () => {
    const jvm = unavailableResources('jvm-via-gradle', 'jvm-via-gradle');
    const reports = [{ id: 'jvm', resources: jvm }, { id: 'java-js', resources: jvm }] as unknown as EngineReport[];
    const notes = formatNotes(reports);
    expect(notes.match(/jvm, java-js/g)).toHaveLength(2); // once for cpu, once for memory
    expect(notes).toContain(naText('jvm-via-gradle'));
  });
});

describe('integration with the report', () => {
  const t: BenchTarget = {
    id: 'x', info: { runtime: 'js', language: 'Java', binaryBytes: 2048, binaryGzipBytes: 1024, version: 'v' },
    bench: (): BenchResult => ({ moves: [1, 2, 3], nodes: [1, 1, 1], ms: [1, 1, 1], rows1Requests: 0 }),
  };
  const base = async (): Promise<EngineReport> => (await runBenchmark([t], { plies: 3, nodes: 10, runs: 1 }, null))![0]!;

  it('the table has cpu and memory columns; a report without resources (old JSON, old runner) shows n/a', async () => {
    const lines = formatTable([await base()]).split('\n');
    expect(lines[0]).toMatch(/cpu ms\s+cpu\/wall\s+peak MiB/);
    expect(lines[1]).toMatch(/n\/a\s+n\/a\s+n\/a/);
  });

  it('with resources the table shows them', async () => {
    const r = { ...(await base()), resources: processResources({ wallMs: 1000, cpuMs: 990, rssStartBytes: 1 * MIB, rssAfterLoadBytes: 2 * MIB, peakRssBytes: 90 * MIB, wasmMemoryBytes: null }) };
    expect(formatTable([r]).split('\n')[1]).toMatch(/990\s+0\.99\s+90\.0/);
  });

  it('JSON keeps every old key and adds resources only when present', async () => {
    const plain = JSON.parse(toJson([await base()], { plies: 3, nodes: 10, runs: 1 }, {}));
    expect(plain.v).toBe(1);
    expect(plain.engines[0]).not.toHaveProperty('resources');
    expect(plain.engines[0]).toHaveProperty('nodesPerSecond');
    const withRes = JSON.parse(toJson([{ ...(await base()), resources: unavailableResources('no-cpu-api', 'no-rss-api') }], { plies: 3, nodes: 10, runs: 1 }, {}));
    expect(withRes.engines[0].resources.cpuMs).toBeNull();
    expect(withRes.engines[0].resources.na).toEqual({ cpu: 'no-cpu-api', memory: 'no-rss-api' });
  });
});
