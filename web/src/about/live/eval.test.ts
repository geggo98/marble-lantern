// The V6 live adapter: the report of the engine (here built from the committed golden eval-mid.json plus an independent breadth-first
// distance table) must give back exactly the golden's sums and marks, and a report that does not fit the board must be refused.
import { describe, expect, it } from 'vitest';
import { CELL_COUNT, DIRECTIONS, GOAL_CELLS, cellAt, neighbor } from '../../board/cells';
import { encodeMoves } from '../../app/game-url';
import { EVAL_MID_MOVES } from '../../../e2e/eval-mid-link';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { FeatureReport } from '../../engines/engine';
import { makeMove } from '../../engines/engine';
import { EngineFailure } from '../../engines/protocol';
import golden from '../data/eval-mid.json';
import type { EvalData } from '../figures/eval-model';
import { LiveEngines, LiveError } from './engines';
import { buildEvalData, runEvalLive } from './eval';

const DATA = golden as unknown as EvalData & { history: { from: number; to: number }[] };
const HISTORY = DATA.history.map((m) => makeMove(m.from, m.to));

/** Layout.distance of player p, written again here: breadth-first from the goal over the cells of the player (hexagon and the two corners). */
function distances(p: 0 | 1): number[] {
  const allowed = (c: number): boolean => {
    const { q, r } = cellAt(c);
    return Math.abs(r) > 4 || (Math.abs(q) <= 4 && Math.abs(q + r) <= 4);
  };
  const dist = new Array<number>(CELL_COUNT).fill(-1);
  const queue: number[] = [...GOAL_CELLS[p]];
  for (const c of queue) dist[c] = 0;
  for (let i = 0; i < queue.length; i++) {
    const c = queue[i]!;
    for (let d = 0; d < DIRECTIONS; d++) {
      const n = neighbor(c, d);
      if (n >= 0 && allowed(n) && dist[n] === -1) {
        dist[n] = dist[c]! + 1;
        queue.push(n);
      }
    }
  }
  return dist;
}

describe('the fixture of the browser tests', () => {
  it('e2e/eval-mid-link.ts holds the game-URL payload of the history of eval-mid.json', () => {
    expect(EVAL_MID_MOVES).toBe(encodeMoves(HISTORY));
  });
});

const goldenReport =(): FeatureReport => ({
  features: DATA.features.map((f) => f.value),
  score: DATA.meta.score,
  sideToMove: DATA.meta.sideToMove as 0 | 1,
  distance: [distances(0), distances(1)],
});

describe('buildEvalData on the position of eval-mid.json', () => {
  it('gives back the owners, the sums and every mark of the golden, in its order', () => {
    const built = buildEvalData(goldenReport(), HISTORY, DEFAULT_VARIANT, DATA.meta.legalMoves);
    expect(built.cells).toEqual(DATA.cells);
    expect(built.sides).toEqual(DATA.sides);
    expect(built.meta.score).toBe(179);
    expect(built.meta.ply).toBe(30);
    expect(built.features.map((f) => f.name)).toEqual(DATA.features.map((f) => f.name));
    built.features.forEach((f, i) => {
      expect(f.value).toBe(DATA.features[i]!.value);
      expect(f.weight).toBe(DATA.features[i]!.weight);
      expect(f.contribution).toBeCloseTo(DATA.features[i]!.contribution, 3);
    });
  });

  it('refuses a feature that is not the difference of the two sides, naming the feature', () => {
    const report = goldenReport();
    report.features[7] = 5; // jumps: the board says 3 - 1 = 2
    expect(() => buildEvalData(report, HISTORY, DEFAULT_VARIANT, 54)).toThrow(/jumps/);
    try {
      buildEvalData(report, HISTORY, DEFAULT_VARIANT, 54);
    } catch (e) {
      expect((e as LiveError).code).toBe('internal');
    }
  });

  it('refuses a score that is not the weighted sum of the features', () => {
    const report = goldenReport();
    report.score = 178;
    expect(() => buildEvalData(report, HISTORY, DEFAULT_VARIANT, 54)).toThrow(/score/);
  });

  it('refuses a report with the wrong number of features, and a side to move that does not fit the history', () => {
    const short = goldenReport();
    short.features.pop();
    expect(() => buildEvalData(short, HISTORY, DEFAULT_VARIANT, 54)).toThrow(/13/);
    const wrongSide = goldenReport();
    wrongSide.sideToMove = 1;
    expect(() => buildEvalData(wrongSide, HISTORY, DEFAULT_VARIANT, 54)).toThrow(/side to move/);
  });
});

describe('runEvalLive', () => {
  const snapshot = { id: 1, history: HISTORY, variant: DEFAULT_VARIANT, ply: 30, over: false };
  const holder = (features: undefined | (() => Promise<FeatureReport>)): LiveEngines =>
    new LiveEngines(() => ({
      classical: {
        id: 'cpp-wasm',
        create: () =>
          ({
            init: async () => ({}),
            dispose: () => undefined,
            legalMoves: async () => new Array<number>(54).fill(0),
            ...(features ? { features } : {}),
          }) as never,
      },
      net: null,
    }));
  const report = { progress: () => undefined, step: () => undefined };

  it('asks the classical Info engine once and returns the figure data', async () => {
    const data = await runEvalLive(holder(async () => goldenReport()), snapshot, new AbortController().signal, report);
    expect(data.meta).toMatchObject({ ply: 30, legalMoves: 54, score: 179 });
  });

  it('an engine without the features export is "unsupported" (an older engine file in the cache)', async () => {
    await expect(runEvalLive(holder(undefined), snapshot, new AbortController().signal, report)).rejects.toMatchObject({ code: 'unsupported' });
  });

  it('passes the failure of the engine on', async () => {
    const failing = holder(async () => {
      throw new EngineFailure('illegal-history', 'x');
    });
    await expect(runEvalLive(failing, snapshot, new AbortController().signal, report)).rejects.toBeInstanceOf(EngineFailure);
  });
});
