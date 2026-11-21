// The V7 adapter with a scripted engine: the mapping of a choose() result to a plot row, the order of the levels, the race case, abort.
import { describe, expect, it } from 'vitest';
import type { ChooseResult, GameSetup } from '../../engines/engine';
import { DEFAULT_VARIANT } from '../../game/variant';
import { runBudget } from './budget';
import type { BudgetEngine } from './budget';

const LEVELS = [1000, 5000, 20000, 100000, 500000];

const result = (over: Partial<ChooseResult>): ChooseResult => ({ move: 0, score: 0, depth: 0, nodes: 0, ms: 0, raceMove: false, rows1Requests: 0, ...over });

/** Records the calls and answers `answer(budget)` for each choose. */
function scripted(answer: (budget: number) => ChooseResult): { engine: BudgetEngine; calls: string[]; setups: GameSetup[] } {
  const calls: string[] = [];
  const setups: GameSetup[] = [];
  let budget = 0;
  const engine: BudgetEngine = {
    newGame: async (setup) => {
      setups.push(setup);
      budget = setup.nodes[0]!;
      calls.push(`newGame ${budget}`);
    },
    choose: async () => {
      calls.push(`choose ${budget}`);
      return answer(budget);
    },
  };
  return { engine, calls, setups };
}

describe('runBudget', () => {
  it('maps depth, score and move of each level (move 15 -> 40 is (1,-4)->(1,-2)), and names the budget', async () => {
    const { engine } = scripted((b) => result({ move: 15 | (40 << 8), depth: b / 1000, score: -b }));
    const { race, rows } = await runBudget(engine, [], DEFAULT_VARIANT, LEVELS);
    expect(race).toBe(false);
    expect(rows.map((r) => r.nodes)).toEqual(LEVELS);
    expect(rows.map((r) => r.depth)).toEqual([1, 5, 20, 100, 500]);
    expect(rows.map((r) => r.score)).toEqual([-1000, -5000, -20000, -100000, -500000]);
    expect(rows[0]!.move).toEqual({ from: 15, to: 40, text: '(1,-4)->(1,-2)' });
  });

  it('starts a new game with the level as the budget of both seats before each choose, in the order of the levels', async () => {
    const { engine, calls, setups } = scripted(() => result({}));
    await runBudget(engine, [3, 4], DEFAULT_VARIANT, LEVELS);
    expect(calls).toEqual(LEVELS.flatMap((l) => [`newGame ${l}`, `choose ${l}`]));
    expect(setups.map((s) => s.nodes)).toEqual(LEVELS.map((l) => [l, l]));
    expect(setups.every((s) => s.variant === DEFAULT_VARIANT)).toBe(true);
  });

  it('reports each finished level while the next one has not started', async () => {
    const { engine } = scripted((b) => result({ depth: b }));
    const seen: number[][] = [];
    await runBudget(engine, [], DEFAULT_VARIANT, LEVELS, undefined, (rows) => seen.push(rows.map((r) => r.nodes)));
    expect(seen).toEqual([[1000], [1000, 5000], [1000, 5000, 20000], [1000, 5000, 20000, 100000], LEVELS]);
  });

  it('a race move stops the run at once with no rows: the race table decides, not the budget', async () => {
    const { engine, calls } = scripted(() => result({ raceMove: true, depth: 0 }));
    const seen: number[] = [];
    const out = await runBudget(engine, [], DEFAULT_VARIANT, LEVELS, undefined, (rows) => seen.push(rows.length));
    expect(out).toEqual({ race: true, rows: [] });
    expect(calls).toEqual(['newGame 1000', 'choose 1000']);
    expect(seen).toEqual([]);
  });

  it('an aborted signal stops before the next level', async () => {
    const { engine, calls } = scripted(() => result({}));
    const controller = new AbortController();
    const run = runBudget(engine, [], DEFAULT_VARIANT, LEVELS, controller.signal, () => controller.abort());
    await expect(run).rejects.toThrow();
    expect(calls).toEqual(['newGame 1000', 'choose 1000']);
  });
});
