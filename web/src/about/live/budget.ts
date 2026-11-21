// V7 live: the completed search depth of the reader's own position at each of the five node budgets of the app's strength slider.
// For each level L: newGame({ nodes: [L, L] }) on the classical Info engine, then choose(history). Nothing here touches engine code.
import type { ChooseResult, Engine } from '../../engines/engine';
import { moveFrom } from '../../engines/engine';
import type { Variant } from '../../game/variant';
import { moveText } from '../figures/ab-model';

/** One level of the plot: the shape of a `browser` row of ab-budget-mid.json. */
export interface BudgetRow {
  nodes: number;
  depth: number;
  score: number;
  move: { from: number; to: number; text: string };
}

export interface BudgetResult {
  /** The position is a race: the engine plays from the race table without a search, so there is no depth to show. */
  race: boolean;
  /** The levels that finished, in the order of `levels`. Empty for a race. */
  rows: BudgetRow[];
}

/** The part of the engine this adapter calls. */
export type BudgetEngine = Pick<Engine, 'newGame' | 'choose'>;

export const rowOf = (nodes: number, r: Pick<ChooseResult, 'move' | 'depth' | 'score'>): BudgetRow => {
  const from = moveFrom(r.move);
  const to = r.move >>> 8;
  return { nodes, depth: r.depth, score: r.score, move: { from, to, text: moveText(from, to) } };
};

/**
 * Runs the levels one after the other. `onLevel` gets the rows finished so far after each level, so that the figure can draw them while
 * the next one runs. A race position (`raceMove`) stops at once with `race: true`: the race table decides, whatever the budget.
 */
export async function runBudget(
  engine: BudgetEngine,
  history: readonly number[],
  variant: Variant,
  levels: readonly number[],
  signal?: AbortSignal,
  onLevel?: (rows: readonly BudgetRow[]) => void,
): Promise<BudgetResult> {
  const rows: BudgetRow[] = [];
  for (const level of levels) {
    signal?.throwIfAborted();
    await engine.newGame({ nodes: [level, level], variant });
    const r = await engine.choose(history, signal, variant);
    if (r.raceMove) return { race: true, rows: [] };
    rows.push(rowOf(level, r));
    onLevel?.([...rows]);
  }
  return { race: false, rows };
}
