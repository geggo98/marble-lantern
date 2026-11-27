// Figure J1 rests on data/java-speed.json: eight rows, each citing a line of a document of this repository. The test reads that line and
// checks that it contains the value, so that a document that moves its lines fails here. (backends.json cites the same line of
// docs/web-ui.md for the two WebAssembly figures, but has no such test: a move of the lines must be followed there by hand.)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import data from './java-speed.json';

interface Row {
  id: string;
  group: string;
  kind: string;
  value: number;
  lo?: number;
  hi?: number;
  needle: string;
  source: { doc: string; line: number };
}

const REPO = fileURLToPath(new URL('../../../../', import.meta.url));
const rows = data.rows as Row[];
const lineOf = (doc: string, line: number): string => readFileSync(`${REPO}${doc}`, 'utf8').split('\n')[line - 1] ?? '';

describe('java-speed.json', () => {
  it('has the eight rows of the fact sheet, in two groups', () => {
    expect(rows.map((r) => r.id)).toEqual([
      'java-scalar-b1', 'java-vec-1t', 'tornado-metal-b256', 'java-vec-avx512', 'tornado-cuda-t4', 'cpp-cuda-t4', 'teavm-scalar-b1', 'cpp-wasm-simd',
    ]);
    expect(rows.filter((r) => r.group === 'mac-run').map((r) => r.id)).toEqual(['java-scalar-b1', 'java-vec-1t', 'tornado-metal-b256']);
    expect(rows.filter((r) => r.kind === 'cpp').map((r) => r.id)).toEqual(['cpp-cuda-t4', 'cpp-wasm-simd']);
  });

  it('lists the Mac rows first (the figure numbers them in file order and draws one divider after the group)', () => {
    expect(rows.map((r) => r.group)).toEqual(['mac-run', 'mac-run', 'mac-run', 'other', 'other', 'other', 'other', 'other']);
  });

  it('has a range only on the TeaVM row, and its dot inside the range', () => {
    expect(rows.filter((r) => r.lo !== undefined || r.hi !== undefined).map((r) => r.id)).toEqual(['teavm-scalar-b1']);
    const t = rows.find((r) => r.id === 'teavm-scalar-b1')!;
    expect([t.lo, t.hi]).toEqual([126, 163]);
    expect(t.value).toBeGreaterThanOrEqual(t.lo!);
    expect(t.value).toBeLessThanOrEqual(t.hi!);
  });

  it('keeps the values inside the log axis of the figure (100 to 200,000)', () => {
    for (const r of rows) {
      expect(r.value, r.id).toBeGreaterThanOrEqual(100);
      expect(r.value, r.id).toBeLessThanOrEqual(200000);
    }
  });

  describe.each(rows.map((r) => [`${r.id} (${r.source.doc}:${r.source.line})`, r] as const))('%s', (_name, r) => {
    it('cites a line of the repository that contains the value', () => {
      const text = lineOf(r.source.doc, r.source.line);
      expect(text, `${r.source.doc}:${r.source.line} is empty or missing`).not.toBe('');
      expect(text).toContain(r.needle);
    });
  });
});
