// Figure J3: the model (counts, steps, code strip, geometry) and the data file data/java-simd.json, whose entries cite a line of the repository each.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import enCatalog from '../i18n/en/java.json';
import deCatalog from '../i18n/de/java.json';
import {
  BODY_FMAS, CELLS, CHANNELS, CODE_FILE, CODE_SOURCE, LANE_STOPS, LAST_STEP, LOOP_REPEATS, MEAS, SIMD_ENTRIES, accName, accState, codeLines, fmaIndex, fmasDone, fmasForRow,
  gridGeometry, inputState, lanesAt, longestNarration, machineNote, measX, narration, stepKind, tileCount, weightState,
} from './simd-model';
import type { SimdLayout } from './simd-model';

const REPO = fileURLToPath(new URL('../../../../', import.meta.url));
const lineOf = (doc: string, line: number): string => readFileSync(`${REPO}${doc}`, 'utf8').split('\n')[line - 1] ?? '';

describe('java-simd.json', () => {
  describe.each(SIMD_ENTRIES.map((e) => [`${e.id} (${e.source.doc}:${e.source.line})`, e] as const))('%s', (_name, e) => {
    it('cites a line of the repository that contains the needle', () => {
      const text = lineOf(e.source.doc, e.source.line);
      expect(text, `${e.source.doc}:${e.source.line} is empty or missing`).not.toBe('');
      expect(text).toContain(e.needle);
    });
  });
});

describe('counts', () => {
  it('has 4 cells by 32 channels, 14 statements in the body and 11 steps', () => {
    expect([CELLS, CHANNELS, BODY_FMAS, LAST_STEP, LOOP_REPEATS]).toEqual([4, 32, 8, 11, 224]);
  });
  it('gives 16, 4, 2, 1 tiles and 128, 32, 16, 8 FMAs for the row at 1, 4, 8, 16 lanes', () => {
    expect(LANE_STOPS.map(tileCount)).toEqual([16, 4, 2, 1]);
    expect(LANE_STOPS.map(fmasForRow)).toEqual([128, 32, 16, 8]);
  });
  it('maps a slider index to a lane count and clamps', () => {
    expect([0, 1, 2, 3, -1, 9].map(lanesAt)).toEqual([1, 4, 8, 16, 1, 16]);
  });
  it('names the machine note by lane count', () => {
    expect(LANE_STOPS.map(machineNote)).toEqual(['scalar', 'neon', 'avx2', 'avx512']);
  });
  it('keeps the measured dot inside its whisker, on the axis', () => {
    expect(MEAS.lo).toBeLessThanOrEqual(MEAS.dot);
    expect(MEAS.dot).toBeLessThanOrEqual(MEAS.hi);
    expect([MEAS.textLo, MEAS.textHi]).toEqual([15, 17]);
    expect(MEAS.lanes).toBe(4);
  });
});

describe('steps', () => {
  it('has the kinds idle, load, bcast, 8 times fma, done', () => {
    expect(Array.from({ length: LAST_STEP + 1 }, (_, s) => stepKind(s))).toEqual([
      'idle', 'load', 'bcast', 'fma', 'fma', 'fma', 'fma', 'fma', 'fma', 'fma', 'fma', 'done',
    ]);
  });
  it('runs the FMAs in source order a00, a01, a10, ..., a31', () => {
    expect(Array.from({ length: 8 }, (_, i) => accName(fmaIndex(3 + i)))).toEqual(['a00', 'a01', 'a10', 'a11', 'a20', 'a21', 'a30', 'a31']);
    expect(fmaIndex(2)).toBe(-1);
    expect(fmaIndex(11)).toBe(-1);
  });
  it('at step 3 + i has exactly one current accumulator and i done ones', () => {
    for (let i = 0; i < 8; i++) {
      const states = Array.from({ length: 8 }, (_, a) => accState(3 + i, a));
      expect(states.filter((s) => s === 'current')).toHaveLength(1);
      expect(states.filter((s) => s === 'done')).toHaveLength(i);
      expect(states[i]).toBe('current');
    }
  });
  it('has all accumulators pending before the FMAs and done after them', () => {
    for (const s of [0, 1, 2]) expect(Array.from({ length: 8 }, (_, a) => accState(s, a))).toEqual(Array(8).fill('pending'));
    expect(Array.from({ length: 8 }, (_, a) => accState(11, a))).toEqual(Array(8).fill('done'));
  });
  it('shows the weights and inputs as empty, current, loaded', () => {
    expect([0, 1].map((j) => weightState(0, j))).toEqual(['empty', 'empty']);
    expect([0, 1].map((j) => weightState(1, j))).toEqual(['current', 'current']);
    expect([0, 1].map((j) => weightState(2, j))).toEqual(['loaded', 'loaded']);
    expect([0, 1].map((j) => weightState(4, j))).toEqual(['loaded', 'current']); // step 4 is FMA 1 (a01 uses w1)
    expect([0, 1, 2, 3].map((c) => inputState(1, c))).toEqual(['none', 'none', 'none', 'none']);
    expect([0, 1, 2, 3].map((c) => inputState(2, c))).toEqual(['current', 'current', 'current', 'current']);
    expect([0, 1, 2, 3].map((c) => inputState(5, c))).toEqual(['loaded', 'current', 'loaded', 'loaded']);
    expect([0, 1, 2, 3].map((c) => inputState(11, c))).toEqual(['loaded', 'loaded', 'loaded', 'loaded']);
  });
});

describe('weight register of an FMA step', () => {
  it('is w0 for even and w1 for odd FMAs', () => {
    expect([3, 4, 5, 6].map((s) => [0, 1].map((j) => weightState(s, j) === 'current'))).toEqual([[true, false], [false, true], [true, false], [false, true]]);
  });
});

describe('FMAs done', () => {
  it('counts the current FMA and ends at 8', () => {
    expect(Array.from({ length: LAST_STEP + 1 }, (_, s) => fmasDone(s))).toEqual([0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 8]);
  });
});

describe('code strip', () => {
  it('shows at most two lines per step', () => {
    for (let s = 0; s <= LAST_STEP; s++) expect(codeLines(s).length).toBeLessThanOrEqual(2);
  });
  it('quotes lines of VectorNetwork.java as they are', () => {
    for (const [n, text] of Object.entries(CODE_SOURCE)) expect(lineOf(CODE_FILE, Number(n)).trim(), `line ${n}`).toBe(text);
  });
  it('shows line 305, 306-307, 308 and 311, 312-319, 322 and 325', () => {
    expect([0, 1, 2, 3, 10, 11].map((s) => codeLines(s).map((l) => l.n).filter((n) => n !== null))).toEqual([[305], [306, 307], [308, 311], [312], [319], [322, 325]]);
  });
  it('highlights the identifiers of the current FMA', () => {
    expect(codeLines(6)[0]!.text).toBe('a11 = w1.fma(v1, a11);');
    expect(codeLines(6)[0]!.hl).toEqual(['a11', 'w1', 'v1']);
  });
});

describe('narration', () => {
  it('fills the values of each kind', () => {
    expect(narration(0, 4).values).toEqual({ cells: 4, channels: 32 });
    expect(narration(1, 8).values).toEqual({ lanes: 8 });
    expect(narration(4, 4).values).toEqual({ acc: 'a01', w: 'w1', v: 'v0', lanes: 4 });
    expect(narration(11, 4)).toEqual({ key: 'fig.simd.narr.done', values: { fmas: 8, products: 32, repeats: 224, stores: 4 } });
  });
  const fill = (text: string, values: Record<string, string | number>): string => text.replace(/\{(\w+)\}/g, (_m, k: string) => String(values[k] ?? `{${k}}`));
  it.each([
    ['en', enCatalog],
    ['de', deCatalog],
  ] as const)('has a ghost line that is not shorter than any narration (%s)', (_lang, catalog) => {
    const texts = catalog as Record<string, string>;
    const ghosts = longestNarration();
    expect(ghosts.map((g) => g.key)).toEqual(['idle', 'load', 'bcast', 'fma', 'done'].map((k) => `fig.simd.narr.${k}`));
    for (let s = 0; s <= LAST_STEP; s++) {
      for (const lanes of LANE_STOPS) {
        const n = narration(s, lanes);
        const ghost = ghosts.find((g) => g.key === n.key)!;
        expect(fill(texts[ghost.key]!, ghost.values).length, `${n.key} step ${s} lanes ${lanes}`).toBeGreaterThanOrEqual(fill(texts[n.key]!, n.values).length);
      }
    }
  });
});

describe('grid geometry', () => {
  for (const layout of ['compact', 'wide'] as SimdLayout[]) {
    it(`keeps one view box over all lane counts and every box inside it (${layout})`, () => {
      const first = gridGeometry(layout, 4);
      for (const lanes of LANE_STOPS) {
        const g = gridGeometry(layout, lanes);
        expect([g.W, g.H]).toEqual([first.W, first.H]);
        expect(g.tiles).toBe(tileCount(lanes));
        expect(g.x(0)).toBeGreaterThanOrEqual(g.GL);
        expect(g.x(CHANNELS - 1) + g.box).toBeLessThanOrEqual(g.W);
        expect(g.box).toBeGreaterThan(2);
        // boxes keep their order and do not overlap
        for (let ch = 1; ch < CHANNELS; ch++) expect(g.x(ch)).toBeGreaterThanOrEqual(g.x(ch - 1) + g.box);
      }
    });
  }
  it('has the specified view boxes', () => {
    expect([gridGeometry('compact', 4).W, gridGeometry('compact', 4).H]).toEqual([260, 126]);
    expect([gridGeometry('wide', 4).W, gridGeometry('wide', 4).H]).toEqual([640, 147]);
  });
  it('puts a lane box of about 6 by 14 (compact) and 17 by 18 (wide) units at 4 lanes', () => {
    const c = gridGeometry('compact', 4);
    const w = gridGeometry('wide', 4);
    expect([Math.round(c.box), c.rh]).toEqual([6, 14]);
    expect([Math.round(w.box), w.rh]).toEqual([17, 18]);
  });
  it('maps the number line: 0 at the left margin, ticks 1 4 8 16 in order', () => {
    for (const layout of ['compact', 'wide'] as SimdLayout[]) {
      expect(measX(layout, 0)).toBe(8);
      const xs = LANE_STOPS.map((l) => measX(layout, l));
      expect([...xs].sort((a, b) => a - b)).toEqual(xs);
    }
  });
});
