// The numbers of figure V14. Expected values are computed by hand (log10 of a ratio) or by an independent script, never read back
// from the code under test. Source of the formulas: search/src/main/java/sternhalma/search/Elo.java.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  LEVEL_IDS, SCORE_MAX, confidence95, eloCurve, eloFromScore, eloPoint, futilityRange, futilityRows, levels, mctsSweeps, pairScoreSd, saturationScores,
  staircaseDomain, sweep1, sweep1Domain, sweepsDomain,
} from './tuning-model';

describe('Elo curve equals Elo.fromScore (Elo.java:9-13)', () => {
  // By hand: Elo = 400 * log10(s / (1 - s)).
  it('is 0 at a score of 0.5 (the odds are 1)', () => {
    expect(eloFromScore(0.5)).toBe(0);
  });
  it('is 400 * log10(16/9) = 99.951 at 0.64 (odds 0.64 / 0.36 = 16 / 9; log10(16/9) = 0.2498775)', () => {
    expect(eloFromScore(0.64)).toBeCloseTo(400 * 0.2498775, 3);
    expect(eloFromScore(0.64)).toBeCloseTo(99.951, 3);
  });
  it('is 400 * log10(9) = 381.697 at 0.9 (odds 9; log10(9) = 0.9542425)', () => {
    expect(eloFromScore(0.9)).toBeCloseTo(381.697, 3);
  });
  it('is 400 * log10(999) = 1199.826 at 0.999 (odds 999; log10(999) = 2.9995655)', () => {
    expect(eloFromScore(0.999)).toBeCloseTo(1199.826, 3);
  });
  it('clamps to [0.001, 0.999]: 1, 0.9999 and 0.999 agree, and 0 equals 0.001 mirrored', () => {
    expect(eloFromScore(1)).toBe(eloFromScore(0.999));
    expect(eloFromScore(0.9999)).toBe(eloFromScore(0.999));
    expect(eloFromScore(0)).toBeCloseTo(-1199.826, 3);
    expect(eloFromScore(-5)).toBe(eloFromScore(0.001));
  });
  it('the plotted curve is that function: first point 0, last point the clamp, 120 points by default', () => {
    const curve = eloCurve(200);
    expect(curve).toHaveLength(120);
    expect(curve[0]?.score).toBe(0.5);
    expect(curve[0]?.elo).toBe(0);
    expect(curve.at(-1)?.score).toBeCloseTo(SCORE_MAX, 12);
    expect(curve.at(-1)?.elo).toBeCloseTo(1199.826, 3);
    for (const p of curve) expect(p.elo).toBe(eloFromScore(p.score));
  });
});

describe('confidence95 (Elo.java:42-47)', () => {
  // Independent computation (a script of its own): half width of the interval in Elo.
  it('F of sweep 1: score 0.5887, se 0.0136 gives 19.1455', () => {
    expect(confidence95(0.5887, 0.0136)).toBeCloseTo(19.1455, 3);
  });
  it('LARGE of E3: score 0.688, se 0.021 gives 33.4569', () => {
    expect(confidence95(0.688, 0.021)).toBeCloseTo(33.4569, 3);
  });
  it('is zero without a standard error', () => {
    expect(confidence95(0.7, 0)).toBe(0);
  });
});

describe('the interval band of the panel', () => {
  it('takes its pair sd from the 400-pair confirmation of F: 0.0136 * sqrt(400) = 0.272', () => {
    expect(pairScoreSd()).toBeCloseTo(0.272, 10);
  });
  it('widens towards high scores (the curve gets steep) and narrows with more pairs', () => {
    expect(eloPoint(0.9, 200).ci).toBeGreaterThan(eloPoint(0.64, 200).ci);
    expect(eloPoint(0.64, 800).ci).toBeLessThan(eloPoint(0.64, 200).ci);
  });
  it('is the interval of confidence95 around the curve: lo < elo < hi, and (hi - lo) / 2 = ci', () => {
    const p = eloPoint(0.64, 200);
    expect(p.lo).toBeLessThan(p.elo);
    expect(p.hi).toBeGreaterThan(p.elo);
    expect((p.hi - p.lo) / 2).toBeCloseTo(p.ci, 9);
    // sd 0.272 / sqrt(200) = 0.019233, so the score interval is 0.64 +- 0.037697; by hand: 400 * log10(0.677697 / 0.322303) = 129.109 and
    // 400 * log10(0.602303 / 0.397697) = 72.105, half the difference 28.502
    expect(p.ci).toBeCloseTo(28.502, 2);
  });
  it('marks the two saturated scores of loop run 4 at 0.82 and 0.91', () => {
    expect(saturationScores()).toEqual([0.82, 0.91]);
  });
});

describe('the staircase', () => {
  const rows = levels();
  it('has the seven levels in the order of the topic, numbered 0 to 6', () => {
    expect(rows.map((r) => r.id)).toEqual([...LEVEL_IDS]);
    expect(rows.map((r) => r.k)).toEqual([0, 1, 2, 3, 4, 5, 6]);
  });
  it('shows level 0 without an effect and every other level with the effect of the docs', () => {
    expect(rows[0]).toEqual({ id: 'decisions', k: 0, measured: false });
    const effects = rows.slice(1).map((r) => [r.eloText, r.ci]);
    expect(effects).toEqual([
      ['+306', 41], // docs/search-and-evaluation.md:52
      ['+552', 89], // :157
      ['+139', 27], // :465
      ['+338', 70], // docs/training.md:1037
      ['+62', 19], // :1441, interval from 0.5887 +- 0.0136: 19.1455 rounded
      ['+138', 33], // :2169, interval from 0.688 +- 0.021: 33.4569 rounded
    ]);
  });
  it('has a shared axis from 0 to the next 100 above the highest interval end (552 + 89 = 641 gives 700)', () => {
    expect(staircaseDomain(rows)).toEqual([0, 700]);
  });
});

describe('the sweeps', () => {
  const s = mctsSweeps();
  it('has the rows of the docs table: 5 values of c, 4 batch sizes, 4 prior temperatures, the best one first marked by the highest Elo', () => {
    expect(s.c.map((d) => [d.label, d.elo, d.ci])).toEqual([['0.5', 252, 60], ['1.0', 338, 70], ['1.5', 269, 58], ['2.5', 108, 46], ['4.0', 33, 36]]);
    expect(s.batch.map((d) => [d.x, d.elo, d.ci])).toEqual([[1, 542, 136], [4, 478, 109], [16, 323, 67], [64, 85, 43]]);
    expect(s.ptemp.map((d) => [d.x, d.elo])).toEqual([[0.7, 249], [1, 338], [1.5, 282], [2.5, 230]]);
  });
  it('has a shared y domain round to 100 that holds every whisker (lowest 33 - 36 = -3, highest 542 + 136 = 678)', () => {
    expect(sweepsDomain(s)).toEqual([-100, 700]);
  });
});

describe('the futility grid', () => {
  const rows = futilityRows();
  it('has the 14 settings of the arena table, and exactly the setting fut=1, futk=30, fut2=1 chosen', () => {
    expect(rows).toHaveLength(14);
    const chosen = rows.filter((r) => r.chosen);
    expect(chosen).toHaveLength(1);
    expect([chosen[0]?.fut, chosen[0]?.futk, chosen[0]?.fut2]).toEqual(['1', '30', '1']);
    expect([chosen[0]?.seed31?.elo, chosen[0]?.seed31?.ci, chosen[0]?.seed32?.elo, chosen[0]?.seed32?.ci]).toEqual([177, 36, 189, 35]);
  });
  it('leaves a cell out where the docs have none: 2 settings without seed 31, 8 without seed 32, 4 without fut2', () => {
    expect(rows.filter((r) => r.seed31 === undefined)).toHaveLength(2);
    expect(rows.filter((r) => r.seed32 === undefined)).toHaveLength(8);
    expect(rows.filter((r) => r.fut2 === undefined)).toHaveLength(4);
  });
  it('has the Elo range of the discs from 79 (fut=20, futk=30) to 230 (fut=1, futk=20, fut2=20 on seed 32)', () => {
    expect(futilityRange(rows)).toEqual([79, 230]);
  });
});

describe('sweep 1', () => {
  const s = sweep1();
  it('has BASE and A to H, the winner F, and two confirmations of 400 fresh pairs', () => {
    expect(s.variants.map((v) => v.id)).toEqual(['BASE', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H']);
    expect(s.winner).toBe('F');
    expect(s.confirmation.map((c) => [c.id, c.score])).toEqual([['F', 0.5887], ['H', 0.565]]);
  });
  it('draws the whisker as 1.96 standard errors: F 0.5625 +- 1.96 * 0.0184', () => {
    expect(s.variants.find((v) => v.id === 'F')?.half).toBeCloseTo(0.036064, 6);
  });
  it('has a y domain in steps of 0.02 that holds every whisker: 0.4812 - 0.0374 = 0.4438 gives 0.44, 0.5887 + 0.0267 = 0.6154 gives 0.62', () => {
    expect(sweep1Domain(s)).toEqual([0.44, 0.62]);
  });
});

describe('tuning-ladder.json quotes the docs', () => {
  // The number strings must stand in the cited line or within three lines of it (plan section 3.0, "Data rule").
  const docs = new Map<string, string[]>();
  const lines = (doc: string): string[] => {
    if (!docs.has(doc)) docs.set(doc, readFileSync(new URL(`../../../../${doc}`, import.meta.url), 'utf8').split('\n'));
    return docs.get(doc) ?? [];
  };
  const near = (doc: string, line: number): string => lines(doc).slice(Math.max(0, line - 4), line + 3).join('\n');
  const ladder = JSON.parse(readFileSync(new URL('../data/tuning-ladder.json', import.meta.url), 'utf8')) as {
    levels: { id: string; elo?: { source: { doc: string; line: number }; value: string; ci95?: string }; score?: { source: { doc: string; line: number }; value: string; se: string } }[];
    futility: { rows: { source: { doc: string; line: number }; fut: string; futk: string; fut2?: string; seed31?: { elo: string; ci95: string }; seed32?: { elo: string; ci95: string } }[] };
  };

  it('has every level Elo (and interval) within three lines of its cited line', () => {
    let checked = 0;
    for (const level of ladder.levels) {
      if (!level.elo) continue;
      const text = near(level.elo.source.doc, level.elo.source.line);
      expect(text, `${level.id}: ${level.elo.value}`).toContain(level.elo.value);
      if (level.elo.ci95) expect(text, `${level.id} interval`).toMatch(new RegExp(`\\${level.elo.value} (±|\\+-) ${level.elo.ci95}\\b`));
      if (level.score) {
        const t2 = near(level.score.source.doc, level.score.source.line);
        expect(t2, `${level.id} score`).toMatch(new RegExp(`${level.score.value} (±|\\+-) ${level.score.se}`));
      }
      checked++;
    }
    expect(checked).toBe(6);
  });

  it('has every futility row on its line, with the margins in the keys and both Elo values with their intervals', () => {
    let cells = 0;
    for (const row of ladder.futility.rows) {
      const line = lines(row.source.doc)[row.source.line - 1] ?? '';
      const keys = `fut=${row.fut},futk=${row.futk}${row.fut2 ? `,fut2=${row.fut2}` : ''}`;
      expect(line, keys).toContain(`\`${keys}\``);
      for (const c of [row.seed31, row.seed32]) {
        if (!c) continue;
        expect(line, keys).toContain(`${c.elo} ± ${c.ci95}`);
        cells++;
      }
    }
    expect(cells).toBe(18) // 12 played on seed 31, 6 on seed 32;
  });
});
