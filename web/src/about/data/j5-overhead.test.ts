// Figure J5 rests on data/j5-overhead.json: 20 series rows, 4 CPU rows, 4 + 6 split rows, each citing a line of a file of this repository. The
// test reads that line and checks that it contains the value, so that a document or a result file that moves its lines fails here (same
// pattern as java-speed.test.ts). The facts of the where-block (pool, tree, one search) are checked against their sources the same way.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import data from './j5-overhead.json';
import type { J5Data, MacSplitRow, T4SplitRow } from '../figures/j5-model';

const REPO = fileURLToPath(new URL('../../../../', import.meta.url));
const d = data as unknown as J5Data;
const lineOf = (doc: string, line: number): string => readFileSync(`${REPO}${doc}`, 'utf8').split('\n')[line - 1] ?? '';
/** A value as the result files print it: three decimals ("0.353", "2.590"). */
const fixed3 = (v: number): string => v.toFixed(3);
/** A number of the cited line: the cells of a table or of a TSV, split at blanks, bars and tabs. */
const cells = (line: string): string[] => line.split(/[\s|]+/).filter(Boolean);

describe('j5-overhead.json: shape', () => {
  it('has the same five batch sizes in all four series of both machines, in ascending order', () => {
    for (const m of ['mac', 't4'] as const) {
      for (const n of ['SMALL', 'LARGE'] as const) {
        expect(d.machines[m].series[n].map((r) => r.batch), `${m} ${n}`).toEqual([1, 16, 64, 256, 1024]);
      }
    }
  });

  it('names 11 kernels for SMALL and 15 for LARGE', () => {
    expect(d.machines.mac.kernels).toEqual({ SMALL: 11, LARGE: 15 });
    expect(d.machines.t4.kernels).toEqual({ SMALL: 11, LARGE: 15 });
  });

  it('has the split only where it was measured: Mac LARGE (4 batches), T4 both networks (batch 1, 256, 1,024)', () => {
    expect(d.machines.mac.split.SMALL).toBeUndefined();
    expect(d.machines.mac.split.LARGE!.map((r) => r.batch)).toEqual([1, 64, 256, 1024]);
    expect(d.machines.t4.split.SMALL!.map((r) => r.batch)).toEqual([1, 256, 1024]);
    expect(d.machines.t4.split.LARGE!.map((r) => r.batch)).toEqual([1, 256, 1024]);
  });
});

describe.each(
  (['mac', 't4'] as const).flatMap((m) =>
    (['SMALL', 'LARGE'] as const).flatMap((n) => d.machines[m].series[n].map((r) => [`${m} ${n} batch ${r.batch} (${r.source.doc}:${r.source.line})`, r] as const)),
  ),
)('series row %s', (_name, r) => {
  it('cites a line that contains the needle and the number of evaluations per second', () => {
    const text = lineOf(r.source.doc, r.source.line);
    expect(text, `${r.source.doc}:${r.source.line} is empty or missing`).not.toBe('');
    expect(text).toContain(r.needle);
    // the TSV prints 4701.0, the Markdown table 118,539 or 118539: compare the number, not the spelling
    const printed = cells(text).map((c) => Number(c.replace(/,/g, '')));
    expect(printed, `${r.evals} not in ${text}`).toContain(r.evals);
  });
});

describe.each((['mac', 't4'] as const).flatMap((m) => (['SMALL', 'LARGE'] as const).map((n) => [`${m} ${n}`, d.machines[m].cpu[n]] as const)))('CPU row %s', (_name, c) => {
  it('cites a line that contains the needle and the rate', () => {
    const text = lineOf(c.source.doc, c.source.line);
    expect(text).toContain(c.needle);
    const printed = cells(text).map((x) => Number(x.replace(/,/g, '')));
    expect(printed, `${c.evals} not in ${text}`).toContain(c.evals);
  });
});

describe('split rows', () => {
  it.each((d.machines.mac.split.LARGE as MacSplitRow[]).map((r) => [`mac LARGE batch ${r.batch}`, r] as const))('%s: wall and GPU time on one line, the copies on the other', (_n, r) => {
    const a = lineOf(r.source.doc, r.source.line);
    const b = lineOf(r.source.doc, r.source.line2);
    expect(a).toContain('cpp:metal-copy');
    expect(b).toContain('cpp:metal-copy');
    expect(cells(a)[1]).toBe(String(r.batch));
    expect(cells(b)[1]).toBe(String(r.batch));
    const na = cells(a).map(Number);
    expect(na, a).toContain(r.wall);
    expect(na, a).toContain(r.gpu);
    const nb = cells(b).map(Number);
    expect(nb, b).toContain(r.write);
    expect(nb, b).toContain(r.read);
  });

  describe.each(
    (['SMALL', 'LARGE'] as const).flatMap((n) => (d.machines.t4.split[n] as T4SplitRow[]).map((r) => [`t4 ${n} batch ${r.batch}`, n, r] as const)),
  )('%s', (_n, net, r) => {
    it('is a cuda-pinned line of the benchmark file with copy in, kernels and copy out', () => {
      const a = lineOf(r.source.doc, r.source.line);
      expect(a).toContain('C++ cuda-pinned');
      expect(cells(a.replace('C++ cuda-pinned', 'cuda-pinned'))[1]).toBe(String(r.batch));
      for (const v of [r.h2d, r.kernel, r.d2h]) expect(a, `${net}: ${fixed3(v)}`).toContain(fixed3(v));
    });
  });
});

describe('where-block', () => {
  const sourceText = (ref: string): string => {
    const m = /^(?<doc>.+?):(?<from>\d+)(?:-(?<to>\d+))?$/.exec(ref);
    if (!m?.groups) throw new Error(`no path:line in ${ref}`);
    const from = Number(m.groups.from);
    const to = Number(m.groups.to ?? from);
    expect(existsSync(`${REPO}${m.groups.doc}`), `${m.groups.doc} exists`).toBe(true);
    return readFileSync(`${REPO}${m.groups.doc}`, 'utf8').split('\n').slice(from - 1, to).join('\n');
  };

  it('cites existing lines for every source', () => {
    for (const ref of [...d.where.pool.sources, ...d.where.tree.sources, ...d.where.one.sources]) expect(sourceText(ref), ref).not.toBe('');
  });

  it('has the pool facts in its sources: 512 games, 250 µs of quiet, 20 ms of waiting, batch 256', () => {
    const text = d.where.pool.sources.map(sourceText).join('\n');
    expect(d.where.pool).toMatchObject({ games: 512, cohorts: 2, batch: 256, quietMicros: 250, maxWaitMs: 20 });
    expect(text).toMatch(/512/);
    expect(text).toMatch(/250/);
    expect(text).toMatch(/20/);
  });

  it('has the tree facts in docs/web-ui.md: batch 4, batch 8 and the loss of 0.20 to 0.26', () => {
    const text = d.where.tree.sources.map(sourceText).join('\n');
    expect(d.where.tree).toMatchObject({ batchBelow800: 4, batchAt800: 8, lossBatch16Sims200: [0.2, 0.26] });
    expect(text).toMatch(/0\.20/);
    expect(text).toMatch(/0\.26/);
  });
});
