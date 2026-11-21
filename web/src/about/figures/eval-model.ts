// The model of V6 (evaluation features on the board): the score of the position from the features and weights of `eval-mid.json`,
// the win probability, and what each feature marks on the board. Pure functions; every number comes from the data file.
import { heatLevel } from '../kit';
import type { Arrow, HeatLevel } from '../kit';

export interface Feature {
  name: string;
  value: number;
  weight: number;
  contribution: number;
}

export interface SideData {
  role: 'mover' | 'opponent';
  player: number;
  pieces: readonly { cell: number; distance: number; lateral: number }[];
  sums: { dmax: number; dmin: number } & Record<string, number>;
  marks: {
    inGoal: readonly number[];
    inStart: readonly number[];
    adjacent: readonly (readonly [number, number])[];
    jumps: readonly (readonly [number, number])[];
    steps: readonly (readonly [number, number])[];
  };
}

export interface EvalData {
  meta: { ply: number; legalMoves: number; sideToMove: number; scale: number; score: number; winProbability: number };
  cells: readonly number[];
  features: readonly Feature[];
  sides: readonly SideData[];
}

export interface Setting {
  /** Names of the features that count. */
  include: ReadonlySet<string>;
  /** Weight per feature name; a name that is missing keeps the learned weight of the data file. */
  weights: Readonly<Record<string, number>>;
}

export const weightOf = (f: Feature, setting: Setting): number => setting.weights[f.name] ?? f.weight;

/** `scale × value × weight` of one feature, or 0 when it is switched off. */
export function contributionOf(f: Feature, setting: Setting, scale: number): number {
  return setting.include.has(f.name) ? scale * f.value * weightOf(f, setting) : 0;
}

/** The score of the evaluator: `round(scale × Σ weight × feature)` over the features that count. */
export function scoreOf(features: readonly Feature[], setting: Setting, scale: number): number {
  return Math.round(features.reduce((sum, f) => sum + contributionOf(f, setting, scale), 0));
}

export const winProbability = (score: number, scale: number): number => 1 / (1 + Math.exp(-score / scale));

/** What a feature marks on the board. Discs: a heat level per piece; rings: marked cells; pairs: joined cells; arrows: moves. */
export interface Overlay {
  discs: { cell: number; level: HeatLevel }[];
  rings: number[];
  pairs: [number, number][];
  arrows: Arrow[];
}

export function overlayOf(name: string, data: EvalData): Overlay {
  const out: Overlay = { discs: [], rings: [], pairs: [], arrows: [] };
  const maxDistance = Math.max(...data.sides.flatMap((s) => s.pieces.map((p) => p.distance)));
  const maxLateral = Math.max(...data.sides.flatMap((s) => s.pieces.map((p) => p.lateral)));
  for (const side of data.sides) {
    const dashed = side.role === 'opponent';
    switch (name) {
      case 'dsum':
      case 'dsq':
      case 'dsumPhase':
        for (const p of side.pieces) {
          const level = heatLevel(p.distance, 0, maxDistance);
          if (level) out.discs.push({ cell: p.cell, level });
        }
        break;
      case 'lateral':
        for (const p of side.pieces) {
          const level = heatLevel(p.lateral, 0, maxLateral);
          if (level) out.discs.push({ cell: p.cell, level });
        }
        break;
      case 'dmax':
        out.rings.push(...side.pieces.filter((p) => p.distance === side.sums.dmax).map((p) => p.cell));
        break;
      case 'spread':
        out.rings.push(...side.pieces.filter((p) => p.distance === side.sums.dmax || p.distance === side.sums.dmin).map((p) => p.cell));
        break;
      case 'inGoal':
        out.rings.push(...side.marks.inGoal);
        break;
      case 'inStart':
        out.rings.push(...side.marks.inStart);
        break;
      case 'adjacent':
        out.pairs.push(...side.marks.adjacent.map(([a, b]): [number, number] => [a, b]));
        break;
      case 'jumps':
        out.arrows.push(...side.marks.jumps.map(([from, to]): Arrow => ({ from, to, dashed, width: 3 })));
        break;
      case 'steps':
        out.arrows.push(...side.marks.steps.map(([from, to]): Arrow => ({ from, to, dashed, width: 2 })));
        break;
      default:
        // tempo and bestGain have no cells in the data file: nothing to mark.
        break;
    }
  }
  return out;
}

/** Share of the half-width of a contribution bar, from -1 to 1, against the largest learned contribution. */
export function barShare(contribution: number, largest: number): number {
  if (!(largest > 0)) return 0;
  return Math.max(-1, Math.min(1, contribution / largest));
}
