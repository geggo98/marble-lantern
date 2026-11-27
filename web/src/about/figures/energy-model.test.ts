// The pure part of Fig. 4.4 (energy-model.ts). The expected values come from the document (docs/training.md), not from the code: the front status of the
// four complete runs at both bases is the table of the design spec; the synthetic rows pin each rule and each boundary of the strength model.
import { describe, expect, it } from 'vitest';
import {
  ELO_DOMAIN, ENERGY_DATA, VIEWS, compareStrength, daysOf, dominates, formatHours, formatKwh, frontPath, frontPoints, frontStatus, groupRows, hitRadii, markKind, matchOf, matchY, parentRun,
  powerW, ratingOf, rowById, rowCode, strengthY, viewSpec, xOf,
} from './energy-model';
import type { Basis, Cost, EnergyData, EnergyRow, FrontPoint, FrontStatus, Rating } from './energy-model';
import { linearScale, logScale } from '../kit';

const D = ENERGY_DATA;
const row = (id: string): EnergyRow => rowById(D, id);
const RUNS = ['small-gen0', 'large-gen0', 'small-gen9', 'large-gen10'] as const;

/** A synthetic complete run for a rule test: rating interval elo ± hw at both bases, the cost as energy and time. */
const rating = (basis: Basis, elo: number, hw: number): Rating => ({ basis, config: 'x', sims: 200, elo, hw95: hw, displayElo: elo, displayHw95: hw, method: 'x' });
const synth = (id: string, net: 'SMALL' | 'LARGE', elo: number, hw: number, kwh: number, hours: number): EnergyRow => ({
  id, net, group: 'run', trainer: 'gpu', hours, kwh, status: 'measured', strength: { sims: rating('sims', elo, hw), time: rating('time', elo, hw) }, source: { doc: 'x', line: 1, needle: 'x' },
});
const world = (rows: EnergyRow[], matches: EnergyData['matches'] = [], pairs: EnergyData['pairs'] = []): EnergyData => ({ ...D, rows, matches, pairs });
const pooled = (a: string, b: string, basis: Basis, elo: number, hw95: number): EnergyData['pairs'][number] => ({ a, b, basis, elo, hw95 });
const match = (a: string, b: string, basis: Basis, elo: number, ci: number): EnergyData['matches'][number] => ({
  a, b, basis, elo, ci, simsA: 200, simsB: 200, source: { doc: 'x', line: 1, needle: 'x' },
});

describe('front status of the committed data', () => {
  // Hand reading of models/ratings/ratings.json (scale 2p-adj-r5). Costs rise S0 < L0 < S9 < L10 in energy and in time.
  // Equal simulations: every pair is decided (L0-S0 +124 ± 76, S9-S0 +642 ± 87, L10-L0 +585 ± 92, L10-S0 +710 ± 97, S9-L0 +517 ± 91, and the match L10-S9 +131 ± 24),
  // each dearer row is stronger than every cheaper one, so all four are on the front.
  // Equal time: L0 against S0 at 1,000 simulations is -239 ± 135, so the cheaper S0 beats L0; S9 beats S0 (+591 ± 175) and L0 (2151 - 1321 = 830 against 164 + 116);
  // the match L10-S9 is -173 ± 36, so the cheaper S9 beats L10.
  const EXPECTED: Record<Basis, Record<(typeof RUNS)[number], FrontStatus>> = {
    sims: { 'small-gen0': 'front', 'large-gen0': 'front', 'small-gen9': 'front', 'large-gen10': 'front' },
    time: { 'small-gen0': 'front', 'large-gen0': 'dominated', 'small-gen9': 'front', 'large-gen10': 'dominated' },
  };
  it.each(RUNS.flatMap((id) => (['sims', 'time'] as const).map((basis) => [id, basis] as const)))('%s at %s', (id, basis) => {
    expect(frontStatus(row(id), basis, D)).toBe(EXPECTED[basis][id]);
  });

  it('LARGE generation 10 is on the front at both bases when the equal-time match is removed', () => {
    const data = { ...D, matches: D.matches.filter((m) => m.basis === 'sims') };
    expect(frontStatus(row('large-gen10'), 'sims', data)).toBe('front');
    // without the match the pair has no pooled difference in the data, and the ratings 2151 and 1907 differ by 244, less than 164 + 132:
    // the pair is unresolved, so the status is `unknown`, never `dominated`
    expect(frontStatus(row('large-gen10'), 'time', data)).toBe('unknown');
  });

  it('LARGE generation 10 is dominated at equal simulations when the sign of that match is flipped', () => {
    const data = { ...D, matches: D.matches.map((m) => (m.basis === 'sims' ? { ...m, elo: -m.elo } : m)) };
    expect(frontStatus(row('large-gen10'), 'sims', data)).toBe('dominated');
  });

  it('the front by energy alone and by time alone equals the front by both (the design assumes the views need no third axis)', () => {
    for (const basis of ['sims', 'time'] as const) {
      for (const id of RUNS) {
        const both = frontStatus(row(id), basis, D);
        expect(frontStatus(row(id), basis, D, ['energy']), `${id} ${basis} energy`).toBe(both);
        expect(frontStatus(row(id), basis, D, ['time']), `${id} ${basis} time`).toBe(both);
      }
    }
  });

  it('the cost order of the four runs is the same in energy and in time', () => {
    const runs = groupRows(D, 'run');
    expect(runs.map((r) => r.id)).toEqual(['small-gen0', 'large-gen0', 'small-gen9', 'large-gen10']);
    expect([...runs].sort((a, b) => a.hours - b.hours).map((r) => r.id)).toEqual(runs.map((r) => r.id));
  });
});

describe('compareStrength: the rules', () => {
  it('rule 1: a match decides when |elo| is larger than its interval, and not when elo equals the interval', () => {
    const a = synth('a', 'LARGE', 500, 10, 1, 1);
    const b = synth('b', 'SMALL', 500, 10, 1, 1);
    expect(compareStrength(a, b, 'sims', world([a, b], [match('a', 'b', 'sims', 25, 24)]))).toBe('stronger');
    expect(compareStrength(a, b, 'sims', world([a, b], [match('a', 'b', 'sims', 24, 24)]))).toBe('unresolved');
    expect(compareStrength(a, b, 'sims', world([a, b], [match('a', 'b', 'sims', -24, 24)]))).toBe('unresolved');
    expect(compareStrength(a, b, 'sims', world([a, b], [match('a', 'b', 'sims', -25, 24)]))).toBe('weaker');
  });

  it('rule 1: a match of the other basis does not apply, and an unresolved match does not fall back to a pooled pair or the ratings', () => {
    const a = synth('a', 'LARGE', 900, 10, 1, 1); // the ratings alone would say: a is far stronger
    const b = synth('b', 'SMALL', 100, 10, 1, 1);
    expect(compareStrength(a, b, 'time', world([a, b], [match('a', 'b', 'sims', -100, 5)]))).toBe('stronger');
    expect(compareStrength(a, b, 'sims', world([a, b], [match('a', 'b', 'sims', 3, 5)]))).toBe('unresolved');
    expect(compareStrength(a, b, 'sims', world([a, b], [match('a', 'b', 'sims', 3, 5)], [pooled('a', 'b', 'sims', 800, 10)]))).toBe('unresolved');
    expect(compareStrength(a, b, 'sims', world([a, b]))).toBe('stronger');
  });

  it('rule 1 is read in both directions: the match is stored for (a, b) and queried for (b, a)', () => {
    const a = synth('a', 'LARGE', 500, 10, 1, 1);
    const b = synth('b', 'SMALL', 500, 10, 1, 1);
    const data = world([a, b], [match('a', 'b', 'sims', 50, 10)]);
    expect(compareStrength(b, a, 'sims', data)).toBe('weaker');
  });

  it('rule 2: the pooled difference decides when it is larger than its half-width, not when it equals it, in both directions', () => {
    const a = synth('a', 'SMALL', 500, 10, 1, 1);
    const b = synth('b', 'SMALL', 500, 10, 1, 1);
    const at = (elo: number, hw: number): EnergyData => world([a, b], [], [pooled('a', 'b', 'sims', elo, hw)]);
    expect(compareStrength(a, b, 'sims', at(86, 85))).toBe('stronger');
    expect(compareStrength(a, b, 'sims', at(85, 85))).toBe('unresolved');
    expect(compareStrength(a, b, 'sims', at(-85, 85))).toBe('unresolved');
    expect(compareStrength(a, b, 'sims', at(-86, 85))).toBe('weaker');
    expect(compareStrength(b, a, 'sims', at(86, 85))).toBe('weaker'); // stored for (a, b), queried for (b, a)
  });

  it('rule 2: the pooled difference wins over the ratings, and a pair of the other basis does not apply', () => {
    const a = synth('a', 'SMALL', 900, 10, 1, 1);
    const b = synth('b', 'SMALL', 100, 10, 1, 1);
    // the ratings are far apart; the pooled interval of the fit says it is open
    expect(compareStrength(a, b, 'sims', world([a, b], [], [pooled('a', 'b', 'sims', 30, 85)]))).toBe('unresolved');
    expect(compareStrength(a, b, 'time', world([a, b], [], [pooled('a', 'b', 'sims', 30, 85)]))).toBe('stronger');
  });

  it('rule 3: with no match and no pooled pair, bars that touch or overlap are unresolved; one Elo beyond the summed half-widths is resolved', () => {
    const lo = synth('lo', 'SMALL', 100, 10, 1, 1); // 90 .. 110
    const touching = synth('touch', 'SMALL', 120, 10, 1, 1); // 110 .. 130
    const apart = synth('apart', 'SMALL', 121, 10, 1, 1); // 111 .. 131
    const overlapping = synth('over', 'SMALL', 115, 10, 1, 1); // 105 .. 125
    const data = world([lo, touching, apart, overlapping]);
    expect(compareStrength(touching, lo, 'sims', data)).toBe('unresolved');
    expect(compareStrength(lo, touching, 'sims', data)).toBe('unresolved');
    expect(compareStrength(overlapping, lo, 'sims', data)).toBe('unresolved');
    expect(compareStrength(apart, lo, 'sims', data)).toBe('stronger');
    expect(compareStrength(lo, apart, 'sims', data)).toBe('weaker');
  });

  it('rule 3 sums the half-widths of both rows and reads the rating of the asked basis', () => {
    const wide = synth('wide', 'SMALL', 100, 50, 1, 1);
    const narrow = synth('narrow', 'SMALL', 160, 5, 1, 1);
    expect(compareStrength(narrow, wide, 'sims', world([wide, narrow]))).toBe('stronger'); // 60 > 50 + 5
    const wider = synth('wider', 'SMALL', 100, 56, 1, 1);
    expect(compareStrength(narrow, wider, 'sims', world([wider, narrow]))).toBe('unresolved'); // 60 < 56 + 5
    const other = { ...narrow, strength: { sims: rating('sims', 160, 5), time: rating('time', 120, 5) } };
    expect(compareStrength(other, wide, 'time', world([wide, other]))).toBe('unresolved'); // 20 < 55
    expect(compareStrength(other, wide, 'sims', world([wide, other]))).toBe('stronger');
  });

  it('a row without strength is unresolved against everything', () => {
    const cpu = row('small-steps-cpu');
    expect(compareStrength(cpu, row('small-gen9'), 'sims', D)).toBe('unresolved');
    expect(compareStrength(row('small-gen9'), cpu, 'time', D)).toBe('unresolved');
  });

  it('is the mirror of itself for every pair of the committed data at both bases', () => {
    const MIRROR = { stronger: 'weaker', weaker: 'stronger', unresolved: 'unresolved' } as const;
    for (const basis of ['sims', 'time'] as const) {
      for (const a of D.rows) {
        for (const b of D.rows) {
          expect(compareStrength(a, b, basis, D), `${a.id} / ${b.id} at ${basis}`).toBe(MIRROR[compareStrength(b, a, basis, D)]);
        }
      }
    }
  });
});

describe('dominates', () => {
  it('needs both costs: less energy but more time than a stronger row is not dominated (the three-objective guard)', () => {
    const strong = synth('strong', 'SMALL', 900, 10, 5, 1); // costs more energy, less time
    const weak = synth('weak', 'SMALL', 100, 10, 2, 5); // costs less energy, more time
    const data = world([strong, weak]);
    expect(dominates(strong, weak, 'sims', data)).toBe(false);
    expect(frontStatus(weak, 'sims', data)).toBe('front');
    expect(frontStatus(strong, 'sims', data)).toBe('front');
    // looking at one cost only would drop one of them: this is what the guard test of the committed data compares against
    const costs: [Cost[], Cost[]] = [['energy'], ['time']];
    expect(dominates(strong, weak, 'sims', data, costs[0])).toBe(false);
    expect(dominates(strong, weak, 'sims', data, costs[1])).toBe(true);
    expect(frontStatus(weak, 'sims', data, costs[1])).toBe('dominated');
  });

  it('equal cost counts as not worse, and a row never dominates itself', () => {
    const a = synth('a', 'SMALL', 900, 10, 2, 2);
    const b = synth('b', 'SMALL', 100, 10, 2, 2);
    const data = world([a, b]);
    expect(dominates(a, b, 'sims', data)).toBe(true);
    expect(dominates(a, a, 'sims', data)).toBe(false);
  });

  it('needs a strictly better strength: unresolved is not enough', () => {
    const a = synth('a', 'SMALL', 100, 50, 1, 1);
    const b = synth('b', 'SMALL', 110, 50, 2, 2);
    expect(dominates(a, b, 'sims', world([a, b]))).toBe(false);
  });
});

describe('strengthY, ratingOf and matchOf', () => {
  // the published ratings (models/ratings/ratings.json): SMALL generation 9 is 1839 at 200 and 2151 at 1,000 simulations
  it('is the rating of the basis: S0 1197 / 1560, L0 1321 both, S9 1839 / 2151, L10 1907 both', () => {
    expect(RUNS.map((id) => strengthY(row(id), 'sims', D))).toEqual([1197, 1321, 1839, 1907]);
    expect(RUNS.map((id) => strengthY(row(id), 'time', D))).toEqual([1560, 1321, 2151, 1907]);
  });
  it('has the half-widths and the display values of the rating file', () => {
    expect(RUNS.map((id) => ratingOf(row(id), 'sims').hw95)).toEqual([100, 116, 125, 132]);
    expect(RUNS.map((id) => [ratingOf(row(id), 'sims').displayElo, ratingOf(row(id), 'sims').displayHw95])).toEqual([[1200, 100], [1320, 120], [1840, 130], [1910, 140]]);
    expect(ratingOf(row('small-gen9'), 'time')).toMatchObject({ elo: 2151, hw95: 164, displayElo: 2150, displayHw95: 170, sims: 1000 });
  });
  it('the triangle of the match stands at the Elo of SMALL generation 9 plus the match: 1970 at equal simulations, 1978 at equal time', () => {
    const at = (basis: Basis): number => matchY(matchOf(row('large-gen10'), basis, D)!, basis);
    expect(at('sims')).toBe(1839 + 131);
    expect(at('time')).toBe(2151 - 173);
    expect(matchOf(row('large-gen10'), 'time', D)).toMatchObject({ elo: -173, ci: 36, simsA: 200, simsB: 1000 });
    expect(matchOf(row('small-gen9'), 'time', D)).toBeUndefined();
  });
  it('refuses a row with no rating', () => {
    expect(() => strengthY(row('small-steps-cpu'), 'sims', D)).toThrow(/no rating/);
  });
});

describe('frontPath', () => {
  const pts: FrontPoint[] = [
    { x: 0.021, y: 1197 },
    { x: 0.05, y: 1321 },
    { x: 0.177, y: 1839, dashed: true },
  ];
  it('starts at the first point, goes horizontally then vertically, and ends at the right edge at the last y', () => {
    const { line } = frontPath(pts, 10, 0);
    expect(line.map(({ x, y }) => [x, y])).toEqual([[0.021, 1197], [0.05, 1197], [0.05, 1321], [0.177, 1321], [0.177, 1839], [10, 1839]]);
    expect(line.map((v) => v.dashed)).toEqual([false, false, false, true, true, false]);
  });
  it('is monotone in x and in y, and sorts its input by x', () => {
    const { line } = frontPath([...pts].reverse(), 10, 0);
    for (let i = 1; i < line.length; i++) {
      expect(line[i]!.x).toBeGreaterThanOrEqual(line[i - 1]!.x);
      expect(line[i]!.y).toBeGreaterThanOrEqual(line[i - 1]!.y);
    }
    expect(line[0]).toMatchObject({ x: 0.021, y: 1197 });
  });
  it('closes the region at the bottom edge and contains a point just below and right of the last point, not one just above', () => {
    const { region } = frontPath(pts, 10, 0);
    expect(region.at(-1)).toEqual({ x: 0.021, y: 0 });
    expect(region.at(-2)).toEqual({ x: 10, y: 0 });
    const inside = (p: { x: number; y: number }): boolean => {
      let c = false;
      for (let i = 0, j = region.length - 1; i < region.length; j = i++) {
        const a = region[i]!;
        const b = region[j]!;
        if (a.y > p.y !== b.y > p.y && p.x < ((b.x - a.x) * (p.y - a.y)) / (b.y - a.y) + a.x) c = !c;
      }
      return c;
    };
    expect(inside({ x: 0.18, y: 1800 })).toBe(true);
    expect(inside({ x: 0.18, y: 1900 })).toBe(false);
    expect(inside({ x: 0.03, y: 1250 })).toBe(false); // above the first step
    expect(inside({ x: 0.03, y: 1150 })).toBe(true);
  });
  it('returns nothing for no points', () => {
    expect(frontPath([], 10, 0)).toEqual({ line: [], region: [] });
  });
  it('for the committed data: at equal simulations the line runs S0, L0, S9, L10 with no dashed step; at equal time only S0 and S9 are on it', () => {
    const sims = frontPoints('energy', 'sims', D);
    expect(sims.map((p) => [p.x, p.y, p.dashed])).toEqual([[0.021, 1197, false], [0.05, 1321, false], [0.177, 1839, false], [0.712, 1907, false]]);
    const time = frontPoints('energy', 'time', D);
    expect(time.map((p) => [p.x, p.y, p.dashed])).toEqual([[0.021, 1560, false], [0.177, 2151, false]]);
    expect(frontPoints('time', 'time', D).map((p) => p.x)).toEqual([0.534, 4.254]);
  });
});

describe('viewSpec', () => {
  const log = (v: number): number => Math.log10(v);
  for (const compact of [false, true]) {
    describe(compact ? 'compact' : 'wide', () => {
      it('has the domains of the design: 0.01 to 10 kWh, 0.25 to 250 h, 1,000 to 2,400 Elo, and 0.3 to 300 h against 0.01 to 10 kWh in the view both', () => {
        expect(viewSpec('energy', compact).x.domain).toEqual([0.01, 10]);
        expect(viewSpec('time', compact).x.domain).toEqual([0.25, 250]);
        expect(viewSpec('energy', compact).y.domain).toEqual([1000, 2400]);
        expect(ELO_DOMAIN).toEqual([1000, 2400]);
        expect(viewSpec('both', compact).x.domain).toEqual([0.3, 300]);
        expect(viewSpec('both', compact).y.domain).toEqual([0.01, 10]);
      });
      it('keeps every tick inside its domain, in ascending order', () => {
        for (const view of VIEWS) {
          for (const axis of [viewSpec(view, compact).x, viewSpec(view, compact).y]) {
            expect(axis.ticks.length).toBeGreaterThan(1);
            expect([...axis.ticks].sort((a, b) => a - b)).toEqual(axis.ticks);
            for (const t of axis.ticks) expect(t, `${view} ${axis.key}`).toBeGreaterThanOrEqual(axis.domain[0]);
            for (const t of axis.ticks) expect(t).toBeLessThanOrEqual(axis.domain[1]);
          }
        }
      });
    });
  }
  it('has fewer ticks in the compact layout', () => {
    expect(viewSpec('energy', true).x.ticks).toEqual([0.01, 0.1, 1, 10]);
    expect(viewSpec('energy', false).x.ticks).toHaveLength(10);
    expect(viewSpec('time', true).x.ticks).toEqual([1, 10, 100]);
  });
  it('the domains of the views energy and time span the same number of decades (a point moves by its power only)', () => {
    const span = (view: 'energy' | 'time'): number => log(viewSpec(view, false).x.domain[1]) - log(viewSpec(view, false).x.domain[0]);
    expect(span('energy')).toBeCloseTo(3, 9);
    expect(span('time')).toBeCloseTo(3, 9);
  });
  it('contains every drawn mark of the committed rows, whisker and range ends included', () => {
    for (const view of VIEWS) {
      const spec = viewSpec(view, false);
      const inside = (axis: typeof spec.x, v: number, what: string): void => {
        expect(v, `${view} ${what}`).toBeGreaterThanOrEqual(axis.domain[0]);
        expect(v, `${view} ${what}`).toBeLessThanOrEqual(axis.domain[1]);
      };
      for (const r of D.rows) {
        const kind = markKind(r, view);
        if (kind === 'none') continue;
        if (view === 'both') {
          inside(spec.x, r.hours, `${r.id} hours`);
          for (const v of [r.kwh, ...(r.kwhRange ?? [])]) inside(spec.y, v, `${r.id} kwh`);
          continue;
        }
        inside(spec.x, xOf(r, view), `${r.id} x`);
        if (kind === 'point' && r.strength) {
          for (const basis of ['sims', 'time'] as const) {
            const q = ratingOf(r, basis);
            inside(spec.y, q.elo - q.hw95, `${r.id} ${basis} rating low`);
            inside(spec.y, q.elo + q.hw95, `${r.id} ${basis} rating high`);
            const m = matchOf(r, basis, D);
            if (m) {
              inside(spec.y, matchY(m, basis) - m.ci, `${r.id} ${basis} match low`);
              inside(spec.y, matchY(m, basis) + m.ci, `${r.id} ${basis} match high`);
            }
          }
        }
      }
    }
  });
  it('log scale maps a tick to a position inside the frame and orders them (the kit scale is used, not a copy)', () => {
    const s = logScale([0.01, 10], [0, 300]);
    expect(s(0.01)).toBeCloseTo(0, 9);
    expect(s(10)).toBeCloseTo(300, 9);
    expect(s(0.1)).toBeCloseTo(100, 9);
    expect(linearScale([1000, 2400], [280, 0])(1700)).toBeCloseTo(140, 9);
  });
});

describe('markKind and parentRun', () => {
  it('draws runs as points, CPU steps as bounds and GPU steps not at all in a strength view; everything as points in the view both', () => {
    expect(markKind(row('small-gen9'), 'energy')).toBe('point');
    expect(markKind(row('small-steps-cpu'), 'time')).toBe('bound');
    expect(markKind(row('large-steps-gpu'), 'energy')).toBe('none');
    for (const r of D.rows) expect(markKind(r, 'both')).toBe('point');
  });
  it('a GPU steps row belongs to the latest run of its net: S-GPU to S9, L-GPU to L10; other rows have none', () => {
    expect(parentRun(row('small-steps-gpu'), D)?.id).toBe('small-gen9');
    expect(parentRun(row('large-steps-gpu'), D)?.id).toBe('large-gen10');
    expect(parentRun(row('small-steps-cpu'), D)).toBeUndefined();
    expect(parentRun(row('small-gen9'), D)).toBeUndefined();
  });
});

describe('rowCode and rows', () => {
  it('names the eight rows S0, L0, S9, L10, S-GPU, S-CPU, L-GPU, L-CPU', () => {
    expect(D.rows.map(rowCode)).toEqual(['S0', 'S9', 'L0', 'L10', 'S-GPU', 'S-CPU', 'L-GPU', 'L-CPU']);
    expect(groupRows(D, 'run').map(rowCode)).toEqual(['S0', 'L0', 'S9', 'L10']);
    expect(groupRows(D, 'steps').map(rowCode)).toEqual(['S-GPU', 'S-CPU', 'L-GPU', 'L-CPU']);
  });
  it('has the eight rows of the design and a rating for exactly the four complete runs', () => {
    expect(D.rows).toHaveLength(8);
    expect(D.rows.filter((r) => r.strength).map((r) => r.id).sort()).toEqual([...RUNS].sort());
  });
});

describe('powerW', () => {
  it('is kWh per hour in watts', () => {
    expect(powerW({ kwh: 0.5, hours: 10 })).toBeCloseTo(50, 9);
    expect(powerW({ kwh: 1.2, hours: 24 })).toBeCloseTo(50, 9);
  });
  it('lies in the band of 36 to 49 W, widened by 2 %, for every committed row (catches a slip of hours or kWh)', () => {
    const [lo, hi] = D.machine.trainW;
    expect([lo, hi]).toEqual([36, 49]);
    for (const r of D.rows) {
      expect(powerW(r), r.id).toBeGreaterThanOrEqual(lo * 0.98);
      expect(powerW(r), r.id).toBeLessThanOrEqual(hi * 1.02);
    }
  });
  it('the transcribed factors agree with the rows within 10 % (the data keeps the documented 36, 51, 34, 52)', () => {
    const f = (net: 'SMALL' | 'LARGE'): (typeof D.factors)[number] => D.factors.find((x) => x.net === net)!;
    expect([f('SMALL').time, f('LARGE').time, f('SMALL').energy, f('LARGE').energy]).toEqual([36, 51, 34, 52]);
    for (const net of ['SMALL', 'LARGE'] as const) {
      const gpu = D.rows.find((r) => r.net === net && r.group === 'steps' && r.trainer === 'gpu')!;
      const cpu = D.rows.find((r) => r.net === net && r.group === 'steps' && r.trainer === 'cpu')!;
      expect(f(net).time / (cpu.hours / gpu.hours), `${net} time`).toBeGreaterThan(0.9);
      expect(f(net).time / (cpu.hours / gpu.hours), `${net} time`).toBeLessThan(1.1);
      expect(f(net).energy / (cpu.kwh / gpu.kwh), `${net} energy`).toBeGreaterThan(0.9);
      expect(f(net).energy / (cpu.kwh / gpu.kwh), `${net} energy`).toBeLessThan(1.1);
    }
  });
});

describe('formatHours', () => {
  it.each([
    [0.53, 2],
    [0.534, 2],
    [4.25, 1],
    [4.254, 1],
    [1.223, 1],
    [17, 0],
    [17.053, 0],
    [175, 0],
    [25.8, 0],
    [3.25, 1],
  ])('%s hours print with %s decimals', (h, digits) => {
    expect(formatHours(h)).toBe(digits);
  });
  it('decides the boundary after rounding: 9.96 prints "10", not "10.0"; 0.996 prints "1.0", not "1.00"', () => {
    expect(formatHours(9.96)).toBe(0);
    expect(new Intl.NumberFormat('en', { minimumFractionDigits: 0, maximumFractionDigits: 0 }).format(9.96)).toBe('10');
    expect(formatHours(9.94)).toBe(1);
    expect(formatHours(0.996)).toBe(1);
    expect(formatHours(0.994)).toBe(2);
    expect(formatHours(1)).toBe(1);
    expect(formatHours(10)).toBe(0);
  });
});

describe('formatKwh', () => {
  it('prints a measured value with three decimals, so 0.05 becomes 0.050', () => {
    expect(formatKwh({ kwh: 0.05, status: 'measured' })).toBe(3);
    expect(formatKwh(row('large-gen0'))).toBe(3);
    expect(formatKwh(row('large-steps-gpu'))).toBe(3);
  });
  it('keeps two significant digits of an estimate: 0.035 three decimals, 1.19 one decimal, 7.46 one decimal, 12.4 none', () => {
    expect(formatKwh(row('small-steps-gpu'))).toBe(3);
    expect(formatKwh(row('small-steps-cpu'))).toBe(1);
    expect(formatKwh(row('large-steps-cpu'))).toBe(1);
    expect(formatKwh({ kwh: 12.4, status: 'estimate' })).toBe(0);
    expect(formatKwh({ kwh: 0.0521, status: 'estimate' })).toBe(3);
  });
  it('decides the magnitude after rounding: 9.96 prints "10", not "10.0"; 0.0996 prints "0.10"', () => {
    expect(formatKwh({ kwh: 9.96, status: 'estimate' })).toBe(0);
    expect(formatKwh({ kwh: 0.0996, status: 'estimate' })).toBe(2);
  });
});

describe('daysOf', () => {
  it('gives days from 24 h on', () => {
    expect(daysOf(23.9)).toBeUndefined();
    expect(daysOf(24)).toBe(1);
    expect(daysOf(175)).toBeCloseTo(7.29, 2);
  });
});

describe('hitRadii', () => {
  it('gives two points 10 apart the minimum of 8 (the minimum beats half the distance of 5)', () => {
    expect(hitRadii([{ x: 0, y: 0 }, { x: 10, y: 0 }])).toEqual([8, 8]);
  });
  it('gives a lone point the maximum of 22', () => {
    expect(hitRadii([{ x: 5, y: 5 }])).toEqual([22]);
  });
  it('gives half the distance to the nearest neighbour when that lies between the limits, and never more than the maximum', () => {
    expect(hitRadii([{ x: 0, y: 0 }, { x: 30, y: 0 }, { x: 100, y: 0 }])).toEqual([15, 15, 22]);
    expect(hitRadii([{ x: 0, y: 0 }, { x: 16, y: 0 }])).toEqual([8, 8]);
    expect(hitRadii([{ x: 0, y: 0 }, { x: 17, y: 0 }])).toEqual([8.5, 8.5]);
  });
  it('takes the nearest neighbour, not the first one, and a Euclidean distance', () => {
    const r = hitRadii([{ x: 0, y: 0 }, { x: 60, y: 0 }, { x: 36, y: 0 }, { x: 36, y: 40 }]);
    expect(r[0]).toBe(18);
    expect(r[3]).toBe(20);
  });
});
