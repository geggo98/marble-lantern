// Fig. 4.4 rests on data/energy.json: eight rows, two matches, two nets, two factors and the machine, each citing a line of a file of this repository.
// The test reads that line and checks that it contains the needle and the value as the document prints it, so that a document that moves its lines
// fails here (same pattern as j5-overhead.test.ts). The durations of the four complete runs are sums of the phase rows of the two energy reports.
// Re-run this test after every edit of docs/training.md: the lines are pinned.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import data from './energy.json';
import type { Cite, EnergyData, EnergyRow } from '../figures/energy-model';

const REPO = fileURLToPath(new URL('../../../../', import.meta.url));
const d = data as unknown as EnergyData;
const lineOf = (c: Pick<Cite, 'doc' | 'line'>): string => readFileSync(`${REPO}${c.doc}`, 'utf8').split('\n')[c.line - 1] ?? '';
/** The text with real minus signs and no-break spaces folded to the ASCII the documents use. */
const fold = (s: string): string => s.replace(/−/g, '-').replace(/ /g, ' ');
const thousands = (n: number): string => new Intl.NumberFormat('en-US').format(n);
const signed = (n: number): string => (n >= 0 ? `+${n}` : `${n}`);
/** How the document spells a kWh value: three decimals for a measured one ("0.050"), the shortest spelling for an estimate ("1.19"). */
const kwhText = (r: Pick<EnergyRow, 'kwh' | 'status'>): string => (r.status === 'measured' ? r.kwh.toFixed(3) : String(r.kwh));

const expectCite = (c: Cite, spellings: string[], what: string): void => {
  const text = fold(lineOf(c));
  expect(text, `${what}: ${c.doc}:${c.line} is empty or missing`).not.toBe('');
  expect(text, `${what}: needle at ${c.doc}:${c.line}`).toContain(c.needle);
  for (const s of spellings) expect(text, `${what}: "${s}" at ${c.doc}:${c.line}`).toContain(s);
};

/** "h:mm:ss" as hours. */
const hoursOf = (hms: string): number => {
  const [h, m, s] = hms.split(':').map(Number) as [number, number, number];
  return h + m / 60 + s / 3600;
};

describe('energy.json: shape', () => {
  it('has the eight rows of the design, in the order of the table, and no other', () => {
    expect(d.rows.map((r) => r.id)).toEqual([
      'small-gen0', 'small-gen9', 'large-gen0', 'large-gen10', 'small-steps-gpu', 'small-steps-cpu', 'large-steps-gpu', 'large-steps-cpu',
    ]);
    expect(d.rows.filter((r) => r.group === 'run').every((r) => r.status === 'measured' && r.trainer === 'gpu' && r.strength)).toBe(true);
    expect(d.rows.filter((r) => r.group === 'steps').every((r) => !r.strength)).toBe(true);
  });
  it('is a transcription, with the date and how the durations were derived', () => {
    expect(d.source.generator).toBe('transcribed');
    expect(d.source.how).toContain('phase durations');
    expect(d.source.how).toContain('loop-large-10');
  });
  it('marks exactly the two CPU estimates as not checked by a real run', () => {
    expect(d.rows.filter((r) => r.checked === false).map((r) => r.id)).toEqual(['small-steps-cpu', 'large-steps-cpu']);
  });
  it('has the band 36 to 49 W and the idle power 12.7 W', () => {
    expect(d.machine.trainW).toEqual([36, 49]);
    expect(d.machine.idleW).toBe(12.7);
  });
});

describe.each(d.rows.map((r) => [r.id, r] as const))('row %s', (_id, r) => {
  it('cites a line with the needle and its energy as the document prints it, and its range', () => {
    expectCite(r.source, [kwhText(r), ...(r.kwhRange ?? []).map(String)], 'kWh');
  });

  if (r.hoursFrom) {
    it('has a duration that is the sum of the cited phase rows of the energy report', () => {
      let sum = 0;
      for (const p of r.hoursFrom!) {
        expectCite(p, [p.duration], 'phase');
        // the duration is the third cell of the report row: | phase | start | duration | covered | ...
        expect(lineOf(p).split('|')[3]?.trim(), `${p.doc}:${p.line}`).toBe(p.duration);
        sum += hoursOf(p.duration);
      }
      expect(Math.abs(sum - r.hours), `sum ${sum} against ${r.hours}`).toBeLessThan(0.0005);
    });
    it('cites each phase once and only phases of the energy reports', () => {
      const keys = r.hoursFrom!.map((p) => `${p.doc}:${p.line}`);
      expect(new Set(keys).size).toBe(keys.length);
      for (const p of r.hoursFrom!) expect(p.doc).toMatch(/^models\/runs\/net5(-large)?\/net5(-large)?\.report\.md$/);
    });
  } else {
    it('cites a line with its duration', () => {
      expectCite(r.hoursSource ?? r.source, [String(r.hours)], 'hours');
    });
  }

  if (r.steps !== undefined) {
    it('cites its step count', () => {
      expectCite(r.stepsSource ?? r.source, [thousands(r.steps!)], 'steps');
    });
  }
  if (r.aboveIdleKwh !== undefined) {
    it('cites the energy above idle on its line', () => {
      expectCite(r.aboveIdleSource ?? r.source, [String(r.aboveIdleKwh)], 'above idle');
    });
  }
  if (r.strength) {
    it('cites the ladder line with its Elo and interval', () => {
      expectCite(r.strength!.source, [`${signed(r.strength!.elo)} ± ${r.strength!.ci}`], 'strength');
    });
  }
});

describe('the four ladder values sit on one line, the rung ab:25600 of the table of the four networks', () => {
  it('names the columns SMALL Gen0, LARGE Gen0, SMALL generation 9, LARGE generation 10 in the order of the cells', () => {
    const run = d.rows.filter((r) => r.group === 'run');
    const line = fold(lineOf(run[0]!.strength!.source));
    const order = ['small-gen0', 'large-gen0', 'small-gen9', 'large-gen10'].map((id) => d.rows.find((r) => r.id === id)!);
    const positions = order.map((r) => line.indexOf(`${signed(r.strength!.elo)} ± ${r.strength!.ci}`));
    expect(positions.every((p) => p >= 0)).toBe(true);
    expect([...positions].sort((a, b) => a - b)).toEqual(positions);
    const header = fold(lineOf({ doc: run[0]!.strength!.source.doc, line: run[0]!.strength!.source.line - 4 }));
    expect(header).toContain('| Opponent | SMALL Gen0 (E1) | LARGE Gen0 (E1) | SMALL generation 9 (E3) | LARGE generation 10 (E3) |');
  });
});

describe('matches', () => {
  it('cites the head-to-head lines: +131 ± 24 at equal simulations, -173 ± 36 at equal CPU time per move', () => {
    const [sims, time] = [d.matches.find((m) => m.basis === 'sims')!, d.matches.find((m) => m.basis === 'time')!];
    expectCite(sims.source, [`${signed(sims.elo)} ± ${sims.ci} Elo`, `both at ${sims.simsA} simulations`], 'sims match');
    expectCite(time.source, [`${signed(time.elo)} ± ${time.ci} Elo`, `at ${thousands(time.simsB)} simulations`, `at ${time.simsA} simulations`], 'time match');
    expect(d.matches.map((m) => [m.a, m.b])).toEqual([['large-gen10', 'small-gen9'], ['large-gen10', 'small-gen9']]);
  });
});

describe('nets', () => {
  it.each(['SMALL', 'LARGE'] as const)('%s: parameters, packed size and CPU time per move are on their cited lines', (net) => {
    const n = d.nets[net];
    const col = net === 'SMALL' ? 0 : 1;
    expectCite(n.source, [thousands(n.weights)], 'weights');
    expectCite(n.packedSource, [`${thousands(n.packedBytes)} B`], 'packed bytes');
    expectCite(n.msSource, [String(n.cpuMsPerMove)], 'cpu ms');
    // SMALL is the first number cell, LARGE the second
    const cells = (c: Cite): string[] => fold(lineOf(c)).split('|').map((s) => s.trim()).filter(Boolean);
    expect(cells(n.source)[1 + col]).toBe(thousands(n.weights));
    expect(cells(n.packedSource)[1 + col]).toBe(`${thousands(n.packedBytes)} B`);
    expect(cells(n.msSource)[1 + col]).toBe(String(n.cpuMsPerMove));
  });
  it('has the channels and blocks of the two presets', () => {
    expect([d.nets.SMALL.channels, d.nets.SMALL.blocks, d.nets.LARGE.channels, d.nets.LARGE.blocks]).toEqual([32, 4, 64, 6]);
  });
});

describe('factors and machine', () => {
  it('cites the documented ratios, 36 and 51 for the step time and 34 and 52 for the energy', () => {
    const small = d.factors.find((f) => f.net === 'SMALL')!;
    const large = d.factors.find((f) => f.net === 'LARGE')!;
    expectCite(small.timeSource, [`${small.time} times (SMALL) and ${large.time} times (LARGE)`], 'time factor');
    expectCite(small.energySource, [`${small.energy} times (SMALL) and ${large.energy} times (LARGE)`], 'energy factor');
  });
  it('cites the idle power and the two powers that bound the band', () => {
    expectCite(d.machine.source, ['12.69 W'], 'idle');
    expectCite(d.machine.trainWSource, ['48.5 W', '36.5 W'], 'band');
    expect(d.machine.trainW[0]).toBeLessThanOrEqual(36.5);
    expect(d.machine.trainW[1]).toBeGreaterThanOrEqual(48.5);
    expect(Math.round(d.machine.idleW * 10) / 10).toBe(12.7);
  });
});
