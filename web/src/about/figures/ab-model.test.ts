// V5 (alpha-beta): the model against the data files of schema 2. Expected values come from the design spec (v5-mid-spec.md, sections 3, 5 and 6:
// the first three root moves of the mid-game example, 298 calls, 231 of 3,180) and from the independent path search of the app (jumpPath).
import { describe, expect, it } from 'vitest';
import en from '../i18n/en/classical.json';
import de from '../i18n/de/classical.json';
import { makeAboutCatalog } from '../i18n/catalog';
import { jumpPath } from '../../board/path';
import midJson from '../data/ab-tree-mid.json';
import startJson from '../data/ab-tree-start.json';
import {
  alphaAt,
  alphaPaths,
  decidingIndex,
  gaugeAlpha,
  gaugeLayout,
  longestNarration,
  moveText,
  narration,
  neighbourNotable,
  notableSteps,
  overviewLayout,
  pluralForm,
  replyChain,
  replyStrip,
  scoreText,
  stepCount,
  stepDelayMs,
  stepKind,
  valueCell,
  verdictAt,
  visitedAt,
} from './ab-model';
import type { AbData, StepKind } from './ab-model';

const mid = midJson as unknown as AbData;
const start = startJson as unknown as AbData;
const catalog = makeAboutCatalog({ en, de });

describe('steps: one per root move (spec, section 6)', () => {
  it('the mid-game example has 68 steps, the start position 15', () => {
    expect(stepCount(mid)).toBe(68);
    expect(stepCount(start)).toBe(15);
  });

  it('step 0 is idle, step 1 is the first move, a root move is cut off or a new best, the last step is the result', () => {
    const kinds: StepKind[] = Array.from({ length: 69 }, (_, s) => stepKind(mid, s));
    expect(kinds.slice(0, 4)).toEqual(['idle', 'first', 'cut', 'best']); // spec section 3: steps 1 to 3
    expect(kinds.slice(4, 68).every((k) => k === 'cut')).toBe(true); // 64 cut-offs
    expect(kinds[68]).toBe('result');
    expect(verdictAt(mid, 0)).toBeNull();
    expect(verdictAt(mid, 3)).toBe('best');
    const startKinds = Array.from({ length: 16 }, (_, s) => stepKind(start, s));
    expect(startKinds).toEqual(['idle', 'first', ...Array(13).fill('cut'), 'result']);
  });
});

describe('the counter compares depth 2 with depth 2', () => {
  it('counts the calls of depth 2: 56 after the first move (1 + 55 replies), 231 of 3,180 at the end', () => {
    expect(visitedAt(mid, 0)).toBe(0);
    expect(visitedAt(mid, 1)).toBe(56);
    expect(visitedAt(mid, 2)).toBe(58); // the second move: 1 call and 1 reply
    expect(visitedAt(mid, 67)).toBe(231);
    expect(visitedAt(mid, 68)).toBe(231);
    expect(mid.meta.minimaxNodes).toBe(3180);
    expect(visitedAt(start, 1)).toBe(15);
    expect(visitedAt(start, 15)).toBe(41);
    expect(start.meta.minimaxNodes).toBe(210);
  });
});

describe('alpha', () => {
  it('is minus infinity at step 0, -20 after the first move, stays there at the cut-off, rises to -10 at step 3', () => {
    expect([0, 1, 2, 3, 4, 68].map((s) => alphaAt(mid, s))).toEqual(['-infinity', -20, -20, -10, -10, -10]);
    expect(scoreText(alphaAt(mid, 0))).toBe('−∞');
    expect(scoreText(alphaAt(mid, 1))).toBe('−20');
    expect(scoreText(0)).toBe('0');
    expect(scoreText('+infinity')).toBe('+∞');
  });

  it('the gauge shows the alpha before the move, so that a reply is judged against it', () => {
    expect([0, 1, 2, 3, 68].map((s) => gaugeAlpha(mid, s))).toEqual(['-infinity', '-infinity', -20, -20, -10]);
  });

  it('draws the staircase up to the current step: no line at step 0, a step up at a new best, the dotted line ahead until the end', () => {
    const L = overviewLayout(mid, false);
    expect(alphaPaths(mid, 0, L)).toBeNull();
    const at2 = alphaPaths(mid, 2, L)!;
    expect(at2.solid).not.toContain('V'); // no step up yet
    expect(at2.ahead).not.toBeNull();
    const at3 = alphaPaths(mid, 3, L)!;
    expect(at3.solid).toContain('V'); // -20 to -10
    expect(at3.label.value).toBe(-10);
    expect(alphaPaths(mid, 67, L)!.ahead).toBeNull();
  });
});

describe('the readout, the narration and the close view', () => {
  it('names the first three root moves of the spec in the notation of the app', () => {
    expect(narration(mid, 1)).toEqual({ key: 'first', values: { move: 'H5»N3', replies: '55', value: '−20' } });
    const cut = narration(mid, 2);
    expect(cut.key).toBe('cut');
    expect(cut.values).toMatchObject({ move: 'H6»N2', reply: 'N1»H7', bound: '−40', alpha: '−20', skipped: '59', replies: '60' });
    expect(narration(mid, 3)).toEqual({
      key: 'best',
      values: { move: 'H5»L6', replies: '44', value: '−10', alphaOld: '−20', alphaNew: '−10' },
    });
    expect(narration(mid, 0)).toEqual({ key: 'idle', values: { n: '67', d1: '67' } });
    expect(narration(mid, 68).values).toMatchObject({ score: '−10', visited: '231', total: '3180' });
    expect(narration(start, 0).values).toMatchObject({ n: '14', d1: '14' });
  });

  it('picks the plural form of a count with the rules of the language: "one" only for 1, in English and in German', () => {
    expect([0, 1, 2, 59].map((k) => pluralForm('en', k))).toEqual(['other', 'one', 'other', 'other']);
    expect([0, 1, 2, 59].map((k) => pluralForm('de', k))).toEqual(['other', 'one', 'other', 'other']);
  });

  it('every cut-off root move has 2 or more replies, so the cut-off narration "{skipped} of its {replies} replies" needs no singular', () => {
    for (const data of [mid, start]) {
      const fewest = Math.min(...data.rootMoves.filter((m) => m.cutoff).map((m) => m.replies));
      expect(fewest).toBeGreaterThanOrEqual(2);
    }
  });

  it('writes "= value" for an exact value and "<= bound" for a cut-off, and a dash when there is no root move', () => {
    expect([0, 1, 2, 3, 68].map((s) => valueCell(mid, s))).toEqual(['–', '= −20', '≤ −40', '= −10', '–']);
  });

  it('the strip has one entry per reply of the largest root move; the deciding reply is marked', () => {
    expect(replyStrip(mid, 0)).toEqual(Array(60).fill('none'));
    const at2 = replyStrip(mid, 2); // 60 replies, 1 searched (the deciding one), 59 skipped
    expect(at2).toHaveLength(60);
    expect(at2.filter((s) => s === 'min')).toHaveLength(1);
    expect(at2.filter((s) => s === 'skip')).toHaveLength(59);
    const at1 = replyStrip(mid, 1); // 55 replies, all searched; the first with the lowest score decides
    expect(at1.filter((s) => s !== 'none')).toHaveLength(55);
    expect(at1.filter((s) => s === 'none')).toHaveLength(5);
    expect(at1.filter((s) => s === 'min')).toHaveLength(1);
    expect(at1[0]).toBe('min'); // the first reply (-20) is the lowest
    expect(decidingIndex(mid.rootMoves[0]!)).toBe(0);
  });

  it('keeps the view boxes independent of the step (computed from the whole example)', () => {
    expect(overviewLayout(mid, true).width).toBe(260);
    expect(overviewLayout(mid, false).width).toBe(640);
    expect(overviewLayout(mid, true).pitch).toBeCloseTo(3.4, 1);
    expect(overviewLayout(mid, false).pitch).toBeCloseTo(9.1, 1);
    expect(gaugeLayout(mid, true).width).toBe(236);
    expect(gaugeLayout(mid, false).lo).toBe(-100);
  });

  it('a searched block of one reply is at least 2.5 units high when compact', () => {
    expect(overviewLayout(mid, true).minBlock).toBe(2.5);
    expect(overviewLayout(mid, false).minBlock).toBe(3);
  });
});

describe('playback and keys', () => {
  it('notable steps: 0, 1, every new best, the step after it, the last root move and the result', () => {
    expect([...notableSteps(mid)].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 67, 68]);
    expect(stepDelayMs(mid, 3)).toBe(1200);
    expect(stepDelayMs(mid, 2)).toBe(1200); // the step after the first move
    expect(stepDelayMs(mid, 10)).toBe(250);
    expect(stepDelayMs(mid, 68)).toBe(1200);
  });

  it('a playback of the mid-game example takes about 23 seconds, the start position about 6', () => {
    const total = (d: AbData): number => Array.from({ length: stepCount(d) + 1 }, (_, s) => stepDelayMs(d, s)).reduce((a, b) => a + b, 0) / 1000;
    expect(total(mid)).toBeGreaterThan(20);
    expect(total(mid)).toBeLessThan(28);
    expect(total(start)).toBeLessThan(10);
  });

  it('Page Up and Page Down go to the previous or the next notable step and stop at the ends', () => {
    expect(neighbourNotable(mid, 4, 1)).toBe(67);
    expect(neighbourNotable(mid, 10, 1)).toBe(67);
    expect(neighbourNotable(mid, 10, -1)).toBe(4);
    expect(neighbourNotable(mid, 4, -1)).toBe(3);
    expect(neighbourNotable(mid, 1, -1)).toBe(0);
    expect(neighbourNotable(mid, 68, 1)).toBe(68);
    expect(neighbourNotable(mid, 67, 1)).toBe(68);
  });
});

describe('stable narration: no step is longer than the sizer of its template, in both languages', () => {
  for (const [name, data] of [['mid', mid], ['start', start]] as const) {
    for (const lang of ['en', 'de'] as const) {
      it(`${name}, ${lang}`, () => {
        const sizers = new Map(longestNarration(data).map((s) => [s.key, catalog.lookup(lang, `fig.ab.narr.${s.key}` as never, s.values)]));
        expect([...sizers.keys()].sort()).toEqual(['best', 'cut', 'first', 'idle', 'result'].filter((k) => sizers.has(k as StepKind)).sort());
        for (let step = 0; step <= stepCount(data); step++) {
          const nar = narration(data, step);
          const text = catalog.lookup(lang, `fig.ab.narr.${nar.key}` as never, nar.values);
          expect(text.length, `step ${step}`).toBeLessThanOrEqual(sizers.get(nar.key)!.length);
        }
      });
    }
  }
});

describe('the paths of the data file agree with the path search of the app', () => {
  for (const [name, data] of [['mid', mid], ['start', start]] as const) {
    it(`${name}: path and replyPath are the chains that jumpPath finds on the position`, () => {
      const occupied = data.cells.map((c) => c >= 0);
      for (const m of data.rootMoves) {
        expect(m.path).toEqual(jumpPath(m.from, m.to, occupied));
        // the deciding reply is played after the root move
        const after = [...occupied];
        after[m.to] = true;
        after[m.from] = false;
        const r = m.searched[decidingIndex(m)]!;
        expect(replyChain(m)).toEqual(jumpPath(r[0], r[1], after));
        expect(r[2] === 1).toBe(replyChain(m).length > 2 || !jumpPath(r[0], r[1], after) || m.replyPath !== undefined);
      }
    });
  }

  it('names cells in axial coordinates as before', () => {
    expect(moveText(start.meta.bestMove.from, start.meta.bestMove.to)).toBe('(2,-6)->(2,-4)');
    expect(start.meta.bestMove.text).toBe('(2,-6)->(2,-4)');
  });
});
