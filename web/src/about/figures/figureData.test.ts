// What the figures of topics 3 and 4 read from the data files: V9a (shapes, parameter counts, gather), V9b (planes, activations, top moves,
// backends), V11 (gate decisions, ladder) and V12 (throughput). Expected values come from other sources than the code under test:
// web/models/models.json, the fact sheets quoted in the plan, the text of docs/training.md, and hand calculation.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import loop3 from '../data/loop3.json';
import { BLOCK_IDS, LAYER_IDS, PRIORS, layerInfo, modelRow, neighboursOf } from './netModel';
import { activationBounds, activationCells, arrowWidth, backendPoints, forwardData, piecesOf, planeCells, topMoves } from './forward';
import { gates, ladder, oldRuleExtras } from './loop';
import { STEPS, layoutFor, throughput } from './pooled';
import { heatLevel } from '../kit/board-heat';

const readJson = (rel: string): { models: { id: string; params: number; planes: number; players: number }[] } =>
  JSON.parse(readFileSync(new URL(rel, import.meta.url), 'utf8'));

describe('V9a: the layers', () => {
  const models = readJson('../../../models/models.json').models;
  it.each([
    ['expert', 'small-2p-adjacent-expert'],
    ['shared', 'small-shared-all'],
  ] as const)('the layer parameters of the %s model add up to the params of models.json', (id, file) => {
    const expected = models.find((m) => m.id === file) as (typeof models)[number];
    const sum = LAYER_IDS.reduce((a, l) => a + layerInfo(id, l).params, 0);
    expect(sum).toBe(expected.params);
    expect(modelRow(id).params).toBe(expected.params);
    expect(modelRow(id).inputPlanes).toBe(expected.planes);
  });
  it('has the numbers of the fact sheet: 61,734 and 62,215; 15 planes and 3 value outputs for the shared model', () => {
    expect(modelRow('expert').params).toBe(61734);
    expect(modelRow('shared').params).toBe(62215);
    expect(layerInfo('shared', 'input').shape).toBe('[122][15]');
    expect(layerInfo('expert', 'input').shape).toBe('[122][13]');
    expect(layerInfo('shared', 'head_value').shape).toBe('[3]');
    expect(layerInfo('expert', 'head_value').shape).toBe('[2]');
  });
  it('shapes: 122 rows with the padding row, 121 cells at the policy head, 32 channels; four blocks of 14,400', () => {
    expect(layerInfo('expert', 'conv_in').shape).toBe('[122][32]');
    expect(layerInfo('expert', 'head_policy').shape).toBe('[121][2]');
    expect(BLOCK_IDS.map((b) => layerInfo('expert', b).params)).toEqual([14400, 14400, 14400, 14400]);
    expect(layerInfo('expert', 'input').params).toBe(0);
    expect(layerInfo('expert', 'conv_in').params).toBe(2944);
    expect(layerInfo('shared', 'conv_in').params).toBe(3392);
  });
  it('the residual priors are those stored with the position data', () => {
    expect(PRIORS).toEqual({ beta: 0.6021, k: 0.708 });
  });
  it('neighbour counts follow the fact sheet: 73 cells with 6, 6 with 5, 36 with 4, 6 with 2; the padding row is not a neighbour', () => {
    const histogram: Record<number, number> = {};
    for (let c = 0; c < 121; c++) histogram[neighboursOf(c).length] = (histogram[neighboursOf(c).length] ?? 0) + 1;
    expect(histogram).toEqual({ 6: 73, 5: 6, 4: 36, 2: 6 });
    expect(neighboursOf(0)).toEqual([2, 1]); // the top tip: rows [0,121,121,2,121,121,1] without padding
  });
});

describe('V9b: the board per layer', () => {
  const mid = forwardData('mid');
  it('an input plane of 0 and 1 draws level 5 exactly where it is 1 (plane 0: the ten pieces of the side to move)', () => {
    const cells = planeCells(mid.planes[0] as number[]);
    const drawn = cells.flatMap((c, i) => (c ? [i] : []));
    expect(drawn).toEqual(mid.cells.flatMap((o, i) => (o === 0 ? [i] : [])));
    expect(drawn).toHaveLength(10);
    expect(cells.every((c) => c === null || ('level' in c && c.level === 5))).toBe(true);
  });
  it('a scaled plane uses the five levels between 0 and its maximum and leaves zero empty (plane 7: distance to the goal)', () => {
    const plane = mid.planes[7] as number[];
    const max = Math.max(...plane);
    const cells = planeCells(plane);
    plane.forEach((v, i) => {
      if (v === 0) expect(cells[i]).toBeNull();
      else expect(cells[i]).toEqual({ level: heatLevel(v, 0, max) });
    });
    expect(new Set(cells.flatMap((c) => (c && 'level' in c ? [c.level] : []))).size).toBeGreaterThan(2);
  });
  it('the activation levels use one scale per position over all layers: both ends are reached somewhere, and the level follows the bounds', () => {
    const { min, max } = activationBounds(mid);
    const levels = mid.layers.flatMap((l) => activationCells(mid, l.id).map((c) => (c && 'level' in c ? c.level : 0)));
    expect(Math.min(...levels)).toBe(1);
    expect(Math.max(...levels)).toBe(5);
    const first = mid.layers[0] as (typeof mid.layers)[number];
    activationCells(mid, first.id).forEach((c, i) => expect(c).toEqual({ level: heatLevel(first.norm[i] as number, min, max) }));
    expect(min).toBe(Math.min(...mid.layers.flatMap((l) => l.norm)));
  });
  it('the five highest priors of the mid-game position, with and without the residual priors', () => {
    const on = topMoves(mid, true);
    expect(on).toHaveLength(5);
    expect(on[0]).toEqual({ from: 2, to: 5, prior: 0.594 });
    // hand-sorted from the policy list of forward-mid.json
    expect(on).toEqual([
      { from: 2, to: 5, prior: 0.594 },
      { from: 2, to: 4, prior: 0.1452 },
      { from: 15, to: 40, prior: 0.0861 },
      { from: 18, to: 30, prior: 0.0301 },
      { from: 27, to: 39, prior: 0.0154 },
    ]);
    const off = topMoves(mid, false);
    expect(off.map((m) => [m.from, m.to, m.prior])).toEqual([[2, 5, 0.5199], [2, 4, 0.1271], [15, 40, 0.0413], [2, 0, 0.0398], [9, 5, 0.0362]]);
    expect(on).not.toEqual(off);
  });
  it('arrow width is 2 for a prior of 0 and 6 for the highest prior', () => {
    expect(arrowWidth(0, 0.5)).toBe(2);
    expect(arrowWidth(0.5, 0.5)).toBe(6);
    expect(arrowWidth(0.25, 0.5)).toBe(4);
  });
  it('pieces: ten of each seat in every position at the start; the data cell list gives seat a for owner 0', () => {
    const start = piecesOf(forwardData('start'));
    expect(start.a).toHaveLength(10);
    expect(start.b).toHaveLength(10);
    expect(start.a).toContain(0);
  });
  it('the side to move is player 0 in all three positions, so the value rows read values[0]', () => {
    for (const p of ['start', 'mid', 'race'] as const) expect(forwardData(p).meta.sideToMove).toBe(0);
    expect(mid.values).toEqual([0.9278, -0.9278]);
    expect(mid.valuesNetOnly).toEqual([0.304, -0.304]);
  });
});

describe('V9b: the backends', () => {
  const points = backendPoints();
  it('six backends in the order of the chart with the rates of the inference fact sheet', () => {
    expect(points.map((p) => p.id)).toEqual(['scalar', 'vector', 'tornado', 'wasm', 'webgpu', 'webgpu16']);
    expect(points.map((p) => p.value)).toEqual([115, 1760, 1968, 3100, 2000, 60000]);
  });
  it('WebGPU at batch 1 is the call time turned around: 0.5 ms per call = 2000 evaluations per second', () => {
    expect(points.find((p) => p.id === 'webgpu')?.value).toBe(1000 / 0.5);
  });
  it('the WebAssembly point has the relaxed-SIMD build as its upper end; WebGPU pipelined is batch 16', () => {
    expect(points.find((p) => p.id === 'wasm')?.high).toBe(4000);
    expect(points.find((p) => p.id === 'webgpu16')?.batch).toBe(16);
    expect(points.find((p) => p.id === 'scalar')?.batch).toBe(1);
  });
  it('each point names the document of its measurement', () => {
    expect(points.map((p) => p.doc)).toEqual(['docs/network.md', 'docs/network.md', 'docs/tornado.md', 'docs/web-ui.md', 'docs/web-ui.md', 'docs/web-ui.md']);
  });
});

describe('V11: the gate decisions and the ladder of loop run 3', () => {
  const docs = readFileSync(new URL('../../../../docs/training.md', import.meta.url), 'utf8');
  const line = docs.split('\n').find((l) => l.startsWith('Gate scores against the previous best network')) as string;

  it('has the 14 gate scores of docs/training.md, in order', () => {
    expect(line).toBeDefined();
    const scores = [...line.matchAll(/\b0\.\d{3}\b/g)].map((m) => Number(m[0]));
    expect(gates().map((g) => g.score)).toEqual(scores);
    expect(scores).toHaveLength(14);
  });
  it('promotes generations 1, 2, 3, 5, 8, 9, 12 and 13 and rejects 4, 6, 7, 10, 11 and 14 (the "(rejected" marks of the text)', () => {
    const promoted = gates().filter((g) => g.promoted).map((g) => g.gen);
    expect(promoted).toEqual([1, 2, 3, 5, 8, 9, 12, 13]);
    expect(gates().filter((g) => !g.promoted).map((g) => g.gen)).toEqual([4, 6, 7, 10, 11, 14]);
    expect(loop3.promotedGenerations.values.map(Number)).toEqual(promoted);
    expect(loop3.rejectedGenerations.values.map(Number)).toEqual([4, 6, 7, 10, 11, 14]);
    // the text marks seven rejections: "(rejected)" five times plus "(rejected, generation 14)" and the one in the list start
    expect([...line.matchAll(/rejected/g)]).toHaveLength(6);
  });
  it('every rejection but two lies above 0.5: the old rule would have promoted generations 4, 6, 7 and 11', () => {
    expect(oldRuleExtras()).toEqual([4, 6, 7, 11]);
    expect(gates().filter((g) => !g.promoted && g.score <= 0.5).map((g) => g.gen)).toEqual([10, 14]);
  });
  it('every promoted generation scored above 0.5 (the new rule is stricter than the old one)', () => {
    for (const g of gates().filter((x) => x.promoted)) expect(g.score).toBeGreaterThan(0.5);
  });
  it('the ladder: +211, +288, +354, +298 against 102,400 nodes with 1.96 standard errors of 35, 44, 54, 46', () => {
    const l = ladder();
    expect(l.map((p) => p.gen)).toEqual([0, 5, 9, 13]);
    expect(l.map((p) => p.elo)).toEqual([211, 288, 354, 298]);
    expect(l[0]?.lo).toBeCloseTo(211 - 1.96 * 35, 9);
    expect(l[2]?.hi).toBeCloseTo(354 + 1.96 * 54, 9);
    expect(l[3]?.lo).toBeCloseTo(298 - 1.96 * 46, 9);
  });
});

describe('V12: the schematic and the throughput', () => {
  it('has six steps and the ping-pong order: A fills, A closes, B fills while the device computes A, A returns, B closes, B computes', () => {
    expect(STEPS).toHaveLength(6);
    expect(STEPS.map((s) => s.device)).toEqual([false, false, true, false, false, true]);
    expect(STEPS[1]?.edges).toEqual(['aOut']);
    expect(STEPS[3]?.edges).toEqual(['backA']);
    expect(STEPS[4]?.edges).toContain('bOut');
  });
  it('the layouts draw both cohorts equally often; compact has 12 games, wide 24', () => {
    for (const [compact, n] of [[true, 12], [false, 24]] as const) {
      const t = layoutFor(compact).tokens;
      expect(t).toHaveLength(n);
      expect(t.filter((x) => x.cohort === 'A')).toHaveLength(n / 2);
    }
  });
  it('the throughput is 1.35 games per second on 8 CPU threads and 3.3 on the GPU (the mean of 2.97, 3.54 and 3.26), as the caption says', () => {
    const t = throughput();
    expect(t.cpu).toBe(1.35);
    expect(t.gpu).toBeCloseTo((2.97 + 3.54 + 3.26) / 3, 9);
    expect(Math.round(t.gpu * 10) / 10).toBe(3.3);
  });
});
