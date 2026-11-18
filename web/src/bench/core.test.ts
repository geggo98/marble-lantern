import { describe, expect, it } from 'vitest';
import type { BenchResult } from '../engines/engine';
import { checksumOf, formatTable, judge, median, parseExpected, parseSpec, runBenchmark, toGameStat } from './core';
import type { BenchTarget, Expected, GameStat } from './core';

const result = (moves: number[], nodesEach = 100, msEach = 2): BenchResult => ({
  moves, nodes: moves.map(() => nodesEach), ms: moves.map(() => msEach), rows1Requests: 0,
});

describe('checksumOf', () => {
  it('folds as checksum * 31 + move', () => {
    expect(checksumOf([])).toBe(0n);
    expect(checksumOf([5])).toBe(5n);
    expect(checksumOf([1, 2, 3])).toBe(1n * 31n * 31n + 2n * 31n + 3n);
  });

  it('wraps around as a signed 64-bit value, like Java long', () => {
    // 40 moves of 30000 overflow 2^63 several times; the reference computes the same with modular arithmetic.
    const moves = Array.from({ length: 40 }, (_, i) => 30000 + i);
    let ref = 0n;
    for (const m of moves) ref = (ref * 31n + BigInt(m)) % (1n << 64n);
    if (ref >= 1n << 63n) ref -= 1n << 64n;
    expect(checksumOf(moves)).toBe(ref);
    expect(ref < 0n || ref > 0n).toBe(true);
  });
});

describe('median', () => {
  it('takes the middle, or the mean of the two middle values', () => {
    expect(median([3, 1, 2])).toBe(2);
    expect(median([4, 1, 3, 2])).toBe(2.5);
    expect(median([])).toBeNaN();
  });
});

describe('toGameStat', () => {
  it('computes nodes per second from the engine timings', () => {
    const s = toGameStat(result([1, 2], 500, 10), 2);
    expect(s.fingerprint.totalNodes).toBe(1000);
    expect(s.nodesPerSecond).toBe(50_000);
  });

  it('rejects a result of the wrong length instead of reporting a wrong fingerprint', () => {
    expect(() => toGameStat(result([1, 2]), 3)).toThrow(/2\/2\/2 moves/);
  });
});

describe('judge', () => {
  const stat = (checksum: bigint, totalNodes: number): GameStat => ({ fingerprint: { checksum, totalNodes }, nodesPerSecond: 1, msPerMove: [1], rows1Requests: 0 });
  const entry = { plies: 6, nodes: 2000, checksum: '42', totalNodes: 12000 };

  it('accepts equal checksum and nodes', () => {
    expect(judge([stat(42n, 12000)], entry).status).toBe('ok');
  });

  it('flags a different checksum, a different node count and a run that differs from the first', () => {
    expect(judge([stat(43n, 12000)], entry).status).toBe('mismatch');
    expect(judge([stat(42n, 11999)], entry).status).toBe('mismatch');
    expect(judge([stat(42n, 12000), stat(42n, 12001)], entry).detail).toMatch(/run 2 differs/);
  });

  it('is "unchecked", never "ok", without an expected entry', () => {
    expect(judge([stat(42n, 12000)], undefined).status).toBe('unchecked');
    expect(judge([], entry).status).toBe('mismatch');
  });
});

describe('runBenchmark', () => {
  const expected: Expected = { v: 1, entries: [{ plies: 3, nodes: 10, checksum: String(checksumOf([1, 2, 3])), totalNodes: 300 }] };
  const fake = (id: string, log: string[], moves = [1, 2, 3]): BenchTarget => ({
    id,
    info: { runtime: 'wasm', language: 'C++', binaryBytes: 1024, binaryGzipBytes: 512, version: 'test' },
    bench: (plies) => {
      log.push(`${id}:${plies}`);
      return result(moves.slice(0, plies));
    },
  });

  it('warms up each engine with 2 plies, then rotates the engine order from run to run', async () => {
    const log: string[] = [];
    await runBenchmark([fake('a', log), fake('b', log)], { plies: 3, nodes: 10, runs: 3 }, expected);
    expect(log).toEqual(['a:2', 'b:2', 'a:3', 'b:3', 'b:3', 'a:3', 'a:3', 'b:3']);
  });

  it('reports ok for the right fingerprint and MISMATCH for an engine that plays another move', async () => {
    const reports = await runBenchmark([fake('good', []), fake('bad', [], [1, 2, 4])], { plies: 3, nodes: 10, runs: 2 }, expected);
    expect(reports!.map((r) => [r.id, r.status])).toEqual([['good', 'ok'], ['bad', 'mismatch']]);
    expect(reports![0]!.spread).toBe(1);
  });

  it('stops between games and returns null', async () => {
    let calls = 0;
    const out = await runBenchmark([fake('a', [])], { plies: 3, nodes: 10, runs: 5 }, expected, { shouldStop: () => ++calls > 3 });
    expect(out).toBeNull();
  });
});

describe('parsing and output', () => {
  it('parseExpected rejects a checksum that is not a decimal string', () => {
    expect(() => parseExpected('{"v":1,"entries":[{"plies":1,"nodes":1,"checksum":5,"totalNodes":1}]}')).toThrow(/unexpected shape/);
    expect(parseExpected('{"v":1,"entries":[{"plies":1,"nodes":1,"checksum":"-5","totalNodes":1}]}').entries).toHaveLength(1);
  });

  it('parseSpec rejects zero and fractions', () => {
    expect(() => parseSpec(0, 1, 1)).toThrow(/plies/);
    expect(() => parseSpec(1, 1.5, 1)).toThrow(/nodes/);
    expect(parseSpec('6', '2000', '1')).toEqual({ plies: 6, nodes: 2000, runs: 1 });
  });

  it('the table has one row per engine with the fingerprint status in it', async () => {
    const t: BenchTarget = { id: 'x', info: { runtime: 'js', language: 'Java', binaryBytes: 2048, binaryGzipBytes: 1024, version: 'v' }, bench: () => result([1, 2, 3]) };
    const reports = await runBenchmark([t], { plies: 3, nodes: 10, runs: 1 }, null);
    const lines = formatTable(reports!).split('\n');
    expect(lines).toHaveLength(2);
    expect(lines[1]).toContain('fingerprint=unchecked');
  });
});
