import { describe, expect, it } from 'vitest';
import { checksumOf } from './core';
import type { EngineReport, GameStat } from './core';
import { browserReport, toBenchRows, userAgentMemory } from './browser-report';

const MIB = 1024 * 1024;
const game = (ms: number, memory?: GameStat['memory']): GameStat => ({
  fingerprint: { checksum: checksumOf([1]), totalNodes: 100 }, nodesPerSecond: 1000, msPerMove: [ms, ms], rows1Requests: 0, ...(memory ? { memory } : {}),
});
const report = (id: string, games: GameStat[], status: EngineReport['status'] = 'ok'): EngineReport => ({
  id, language: 'x', runtime: 'wasm', version: 'v', binaryBytes: 2000, binaryGzipBytes: 1000,
  load: { fetchMs: 1, inflateMs: 1, verifyMs: 1, instantiateMs: 1, tableInstallMs: 1, totalMs: 5, tableVerifiedBy: 'subtle-sha256' },
  games, status, detail: '', rows1Requests: 0, nodesPerSecond: 1000, msPerMove: 10, spread: 1.01,
});

describe('browserReport', () => {
  it('takes the wall time from the search timings and the largest memory over the games', () => {
    const r = browserReport(report('cpp-wasm', [game(10, { wasmBytes: 40 * MIB, jsHeapBytes: 3 * MIB }), game(10, { wasmBytes: 56 * MIB, jsHeapBytes: 2 * MIB })]), false);
    expect(r.resources!.wallMs).toBe(40);
    expect(r.resources!.wasmMemoryBytes).toBe(56 * MIB);
    expect(r.resources!.jsHeapBytes).toBe(3 * MIB);
    expect(r.resources!.peakMemoryKind).toBe('wasm-linear-memory');
    expect(r.resources!.cpuMs).toBeNull();
  });

  it('a Java JS engine with no performance.memory is n/a, not 0', () => {
    const r = browserReport(report('java-js', [game(10, { wasmBytes: null, jsHeapBytes: null })]), false);
    expect(r.resources!.peakMemoryBytes).toBeNull();
    expect(r.resources!.na.memory).toBe('no-js-heap-api');
  });

  it('a result without any memory field (older worker) is n/a too', () => {
    expect(browserReport(report('java-js', [game(10)]), false).resources!.na.memory).toBe('no-js-heap-api');
  });

  it('a Wasm GC engine says why there is no memory figure', () => {
    expect(browserReport(report('java-wasmgc', [game(10, { wasmBytes: null, jsHeapBytes: 4 * MIB })]), true).resources!.na.memory).toBe('wasm-gc-heap');
  });
});

describe('toBenchRows', () => {
  it('maps the report to the table rows of the UI, carrying n/a codes instead of numbers', () => {
    const rows = toBenchRows([
      browserReport(report('cpp-wasm', [game(10, { wasmBytes: 32 * MIB, jsHeapBytes: null })]), false),
      browserReport(report('java-wasmgc', [game(10, { wasmBytes: null, jsHeapBytes: null })]), true),
    ]);
    expect(rows.map((r) => r.engineLabel)).toEqual(['cpp-wasm', 'java-wasmgc']);
    expect(rows[0]).toMatchObject({ cpuMs: null, cpuNa: 'no-cpu-api', memoryBytes: 32 * MIB, memoryKind: 'wasm-linear-memory', memoryNa: null });
    expect(rows[1]).toMatchObject({ memoryBytes: null, memoryKind: null, memoryNa: 'wasm-gc-heap' });
  });

  it('fingerprintOk is true only for "ok"', () => {
    const rows = toBenchRows([browserReport(report('a', [game(1)]), false), browserReport(report('b', [game(1)], 'mismatch'), false), browserReport(report('c', [game(1)], 'unchecked'), false)]);
    expect(rows.map((r) => r.fingerprintOk)).toEqual([true, false, null]);
  });
});

describe('userAgentMemory', () => {
  it('is n/a with the reason when the page is not cross-origin isolated, without calling the API', async () => {
    let called = false;
    const perf = { measureUserAgentSpecificMemory: () => { called = true; return Promise.resolve({ bytes: 1 }); } };
    expect(await userAgentMemory({ crossOriginIsolated: false }, perf)).toEqual({ bytes: null, na: 'not-isolated' });
    expect(called).toBe(false);
  });

  it('returns the bytes when isolated', async () => {
    const perf = { measureUserAgentSpecificMemory: () => Promise.resolve({ bytes: 123 }) };
    expect(await userAgentMemory({ crossOriginIsolated: true }, perf)).toEqual({ bytes: 123, na: null });
  });

  it('is n/a when the browser has no such API, or when it throws', async () => {
    expect(await userAgentMemory({ crossOriginIsolated: true }, {})).toEqual({ bytes: null, na: 'no-ua-memory-api' });
    const perf = { measureUserAgentSpecificMemory: () => Promise.reject(new Error('SecurityError')) };
    expect(await userAgentMemory({ crossOriginIsolated: true }, perf)).toEqual({ bytes: null, na: 'no-ua-memory-api' });
  });
});
