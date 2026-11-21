// V6 live: the adapter from the engine's `FeatureReport` to the figure's `EvalData`.
// The engine delivers the 13 features of linear-r3, the score and the per-cell distances. Everything the figure draws on the board (the
// owners, the per-side sums, the marked cells) is counted here from the geometry, as the test function `sideData` of ExplainerDataTest.java
// does for the committed example. Then two checks tie the two sources together; a failed check is a LiveError('internal'), never a wrong figure:
//   1. for each feature that is a per-side sum, mover sum minus opponent sum equals the engine's feature;
//   2. round(scale x sum of weight x feature) equals the engine's score.
import type { Engine, FeatureReport } from '../../engines/engine';
import { CELL_COUNT, DIRECTIONS, GOAL_CELLS, START_CELLS, cellAt, cellId, neighbor } from '../../board/cells';
import { replay } from '../../game/board-state';
import type { Variant } from '../../game/variant';
import example from '../data/eval-mid.json';
import type { EvalData, Feature, SideData } from '../figures/eval-model';
import { LiveError } from './engines';
import type { LiveEngines } from './engines';
import type { RunReport } from './useLiveRun';
import type { LiveSnapshot } from './types';

/** The order of LinearEvaluator.GAIN_NAMES: the order of `FeatureReport.features`. */
const NAMES = ['dsum', 'dmax', 'dsq', 'inGoal', 'inStart', 'spread', 'lateral', 'jumps', 'adjacent', 'steps', 'dsumPhase', 'tempo', 'bestGain'] as const;

const EXAMPLE = example as unknown as EvalData;
const WEIGHTS = new Map(EXAMPLE.features.map((f) => [f.name, f.weight]));
const SCALE = EXAMPLE.meta.scale;

const DQ = [1, -1, 0, 0, 1, -1] as const;
const DR = [0, 0, 1, -1, -1, 1] as const;

/** A cell a player of the classic game may stand on: the central hexagon and the two corners of the players (Layout.allowed, closed camps). */
const allowed = (cell: number): boolean => {
  const c = cellAt(cell);
  const s = -c.q - c.r;
  return Math.abs(c.r) > 4 || (Math.abs(c.q) <= 4 && Math.abs(s) <= 4);
};

/** Geometry.jumpLanding: the cell two steps away in the direction, or -1 off the board. */
const landing = (cell: number, direction: number): number => {
  const c = cellAt(cell);
  return cellId(c.q + 2 * (DQ[direction] ?? 0), c.r + 2 * (DR[direction] ?? 0));
};

const fail = (message: string): never => {
  throw new LiveError('internal', message);
};

/** The marks and sums of one side. `distance[cell]` is Layout.distance of that player. */
function sideOf(owner: Int8Array, player: 0 | 1, role: 'mover' | 'opponent', distance: readonly number[]): SideData {
  const dist = (cell: number): number => distance[cell] ?? fail(`no distance for cell ${cell}`);
  const closer = (cell: number, here: number): boolean => cell >= 0 && allowed(cell) && dist(cell) < here;
  const goal = new Set(GOAL_CELLS[player]);
  const start = new Set(START_CELLS[player]);
  const pieces: { cell: number; distance: number; lateral: number }[] = [];
  const inGoal: number[] = [];
  const inStart: number[] = [];
  const adjacent: [number, number][] = [];
  const jumps: [number, number][] = [];
  const steps: [number, number][] = [];
  let dsum = 0;
  let dsq = 0;
  let dmax = 0;
  let dmin = Number.POSITIVE_INFINITY;
  let lateral = 0;
  for (let c = 0; c < CELL_COUNT; c++) {
    if (owner[c] !== player) continue;
    const d = dist(c);
    const cell = cellAt(c);
    const lat = Math.abs(cell.q - (-cell.q - cell.r));
    dsum += d;
    dsq += d * d;
    dmax = Math.max(dmax, d);
    dmin = Math.min(dmin, d);
    lateral += lat;
    pieces.push({ cell: c, distance: d, lateral: lat });
    if (goal.has(c)) inGoal.push(c);
    else if (start.has(c)) inStart.push(c);
    for (let dir = 0; dir < DIRECTIONS; dir++) {
      const a = neighbor(c, dir);
      if (a < 0) continue;
      if ((owner[a] ?? -1) < 0) {
        if (closer(a, d)) steps.push([c, a]);
      } else {
        if (owner[a] === player && a > c) adjacent.push([c, a]);
        const land = landing(c, dir);
        if (closer(land, d) && (owner[land] ?? -1) < 0) jumps.push([c, land]);
      }
    }
  }
  return {
    role,
    player,
    pieces,
    sums: { dsum, dmax, dmin, dsq, inGoal: inGoal.length, inStart: inStart.length, lateral, jumps: jumps.length, adjacent: adjacent.length, steps: steps.length },
    marks: { inGoal, inStart, adjacent, jumps, steps },
  };
}

/** The per-side sums behind a feature, mover minus opponent (the features that are not such a difference have no entry). */
const SIDE_FEATURES: Record<string, (s: SideData['sums']) => number> = {
  dsum: (s) => s.dsum!,
  dmax: (s) => s.dmax!,
  dsq: (s) => s.dsq! / 10,
  inGoal: (s) => s.inGoal!,
  inStart: (s) => s.inStart!,
  spread: (s) => s.dmax! - s.dmin!,
  lateral: (s) => s.lateral!,
  jumps: (s) => s.jumps!,
  adjacent: (s) => s.adjacent!,
  steps: (s) => s.steps!,
};

export const winProbabilityOf = (score: number): number => 1 / (1 + Math.exp(-score / SCALE));

/**
 * The figure's data for a position, from the engine's report. Throws LiveError('internal') when the report does not fit the position
 * (wrong length, a feature that is not the difference of the sides' sums, a score that is not the weighted sum).
 */
export function buildEvalData(report: FeatureReport, history: readonly number[], variant: Variant, legalMoves: number): EvalData {
  if (report.features.length !== NAMES.length) fail(`${report.features.length} features instead of ${NAMES.length}`);
  const owner = replay(history, variant);
  const me = report.sideToMove;
  if (me !== history.length % 2) fail(`side to move ${me} after ${history.length} plies`);
  const you = (1 - me) as 0 | 1;
  const sides = [sideOf(owner, me, 'mover', report.distance[me]), sideOf(owner, you, 'opponent', report.distance[you])];
  const features: Feature[] = NAMES.map((name, i) => {
    const value = report.features[i]!;
    const weight = WEIGHTS.get(name) ?? fail(`no weight for ${name}`);
    return { name, value, weight, contribution: SCALE * weight * value };
  });
  for (const f of features) {
    const sum = SIDE_FEATURES[f.name];
    if (!sum) continue;
    const expected = sum(sides[0]!.sums) - sum(sides[1]!.sums);
    if (Math.abs(expected - f.value) > 1e-6) fail(`feature ${f.name}: the engine says ${f.value}, the board gives ${expected}`);
  }
  const score = Math.round(SCALE * features.reduce((s, f) => s + f.weight * f.value, 0));
  if (score !== report.score) fail(`score: the engine says ${report.score}, weights times features give ${score}`);
  return {
    meta: { ply: history.length, legalMoves, sideToMove: me, scale: SCALE, score, winProbability: winProbabilityOf(score) },
    cells: Array.from(owner),
    features,
    sides,
  };
}

/** The live run of V6: one `features` call (and one `legalMoves` call) on the classical Info engine. */
export async function runEvalLive(engines: LiveEngines, snapshot: LiveSnapshot, signal: AbortSignal, report: RunReport<never>): Promise<EvalData> {
  const engine: Engine = await engines.classical(report.progress);
  signal.throwIfAborted();
  if (!engine.features) throw new LiveError('unsupported', 'this engine has no features export');
  report.step(0, 1);
  const [features, legal] = await Promise.all([engine.features(snapshot.history, snapshot.variant), engine.legalMoves(snapshot.history, snapshot.variant)]);
  signal.throwIfAborted();
  const data = buildEvalData(features, snapshot.history, snapshot.variant, legal.length);
  report.step(1, 1);
  return data;
}
