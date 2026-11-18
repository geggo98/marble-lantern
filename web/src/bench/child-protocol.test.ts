import { describe, expect, it } from 'vitest';
import { checksumOf } from './core';
import type { Expected, GameStat } from './core';
import { decodeChild, encodeChild, reportFromChild } from './child-protocol';
import type { ChildOutput } from './child-protocol';

const MIB = 1024 * 1024;
const moves = [3, 5, 8];
const game = (over: Partial<GameStat> = {}): GameStat => ({
  fingerprint: { checksum: checksumOf(moves), totalNodes: 300 }, nodesPerSecond: 1000, msPerMove: [100, 100, 100], rows1Requests: 0,
  memory: { wasmBytes: 64 * MIB, jsHeapBytes: null }, ...over,
});
const out = (over: Partial<ChildOutput> = {}): ChildOutput => ({
  v: 1,
  id: 'cpp-wasm',
  info: { runtime: 'wasm', language: 'C++', binaryBytes: 1000, binaryGzipBytes: 500, version: 't' },
  load: { fetchMs: 1, inflateMs: 2, verifyMs: 3, instantiateMs: 4, tableInstallMs: 5, totalMs: 15, tableVerifiedBy: 'subtle-sha256' },
  games: [game(), game()],
  measure: { wallMs: 700, cpuMs: 690, rssStartBytes: 40 * MIB, rssAfterLoadBytes: 70 * MIB, peakRssBytes: 90 * MIB },
  ...over,
});
const expected: Expected = { v: 1, entries: [{ plies: 3, nodes: 100, checksum: String(checksumOf(moves)), totalNodes: 300 }] };

describe('encodeChild / decodeChild', () => {
  it('round-trips a result, including a checksum beyond 2^53', () => {
    const big = game({ fingerprint: { checksum: -1986550544382302149n, totalNodes: 800000 } });
    const back = decodeChild(encodeChild(out({ games: [big] })));
    expect(back.games[0]!.fingerprint.checksum).toBe(-1986550544382302149n);
    expect(back).toEqual(out({ games: [big] }));
  });

  it('is one line, so the parent can read it from a stream that also carries other output', () => {
    expect(encodeChild(out())).not.toContain('\n');
  });

  it('rejects text that is no result, a wrong version and a missing field, with a message', () => {
    expect(() => decodeChild('hello')).toThrow(/not JSON/);
    expect(() => decodeChild('{"v":2}')).toThrow(/version/);
    const noMeasure = JSON.parse(encodeChild(out())) as Record<string, unknown>;
    delete noMeasure.measure;
    expect(() => decodeChild(JSON.stringify(noMeasure))).toThrow(/measure/);
  });
});

describe('reportFromChild', () => {
  const spec = { plies: 3, nodes: 100, runs: 2 };

  it('checks the fingerprint exactly like before: ok for the expected value', () => {
    expect(reportFromChild(out(), spec, expected).status).toBe('ok');
  });

  it('a different move sequence is a mismatch, whatever the resources say', () => {
    const bad = out({ games: [game({ fingerprint: { checksum: checksumOf([3, 5, 9]), totalNodes: 300 } })] });
    expect(reportFromChild(bad, spec, expected).status).toBe('mismatch');
  });

  it('carries the resources, with the wasm memory of the largest game', () => {
    const r = reportFromChild(out({ games: [game({ memory: { wasmBytes: 64 * MIB, jsHeapBytes: null } }), game({ memory: { wasmBytes: 96 * MIB, jsHeapBytes: null } })] }), spec, expected);
    expect(r.resources!.peakRssBytes).toBe(90 * MIB);
    expect(r.resources!.cpuToWall).toBeCloseTo(690 / 700, 8);
    expect(r.resources!.wasmMemoryBytes).toBe(96 * MIB);
  });

  it('wasm memory is null when the engine reports none (Java)', () => {
    const r = reportFromChild(out({ games: [game({ memory: { wasmBytes: null, jsHeapBytes: null } })] }), spec, expected);
    expect(r.resources!.wasmMemoryBytes).toBeNull();
  });
});
