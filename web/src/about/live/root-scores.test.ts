// The root-scores adapter with a hand-made analysis: sorting, bands against the best move, the piece bands, the call of analyse; and the
// committed example file against the same rules.
import { describe, expect, it } from 'vitest';
import type { AnalysisResult } from '../../engines/engine';
import { DEFAULT_VARIANT } from '../../game/variant';
import { replay } from '../../game/board-state';
import { GUIDE_NODES } from '../../engines/analysis';
import example from '../data/root-scores-mid.json';
import { buildRootScores, pieceBands, runRootScores } from './root-scores';
import type { RootScoresData } from './root-scores';

const mv = (from: number, to: number): number => from | (to << 8);

// A legal start position: seat 0's pieces on the top house. Moves are not checked by the adapter, but the cells come from the replay.
const analysis = (): AnalysisResult => ({
  moves: [mv(0, 1), mv(0, 2), mv(1, 3), mv(2, 4), mv(2, 5)],
  scores: [100, 100 - 200, 100 + 0, 100 - 31, 100 - 400],
  position: 100,
  nodes: 5 * 4000,
  ms: 0,
});

describe('buildRootScores', () => {
  const data = buildRootScores([], DEFAULT_VARIANT, analysis(), 4000);

  it('lists every move, best score first, equal scores in the engine order', () => {
    expect(data.moves.map((m) => `${m.from}->${m.to}`)).toEqual(['0->1', '1->3', '2->4', '0->2', '2->5']);
    expect(data.moves.map((m) => m.score)).toEqual([100, 100, 69, -100, -300]);
  });

  it('bands each move by its loss against the best: 30 points top, 90 good, 180 fair, 300 weak, more poor', () => {
    // losses 0, 0, 31, 200, 400
    expect(data.moves.map((m) => m.band)).toEqual(['much-better', 'much-better', 'better', 'worse', 'much-worse']);
  });

  it('names the seat that moves, the budget, the legal moves and the owners of the board', () => {
    expect(data.meta).toEqual({ ply: 0, sideToMove: 0, nodes: 4000, legalMoves: 5 });
    expect(data.cells).toEqual(Array.from(replay([], DEFAULT_VARIANT)));
    const odd = buildRootScores([mv(3, 16)], DEFAULT_VARIANT, analysis(), 4000);
    expect(odd.meta.sideToMove).toBe(1);
    expect(odd.meta.ply).toBe(1);
  });

  it('gives each moved text in the axial form of the other data files', () => {
    expect(data.moves.find((m) => m.from === 0 && m.to === 1)!.text).toMatch(/^\(-?\d+,-?\d+\)->\(-?\d+,-?\d+\)$/);
  });
});

describe('pieceBands', () => {
  it('rates a piece by its best move', () => {
    const data = buildRootScores([], DEFAULT_VARIANT, analysis(), 4000);
    const bands = pieceBands(data.moves);
    expect([...bands.keys()].sort((a, b) => a - b)).toEqual([0, 1, 2]);
    expect(bands.get(0)).toBe('much-better'); // 0->1 is the best, 0->2 is worse
    expect(bands.get(1)).toBe('much-better');
    expect(bands.get(2)).toBe('better'); // 2->4 (loss 31) beats 2->5
  });
});

describe('runRootScores', () => {
  it('calls analyse once for all legal moves with the guide budget and the variant, and builds the data from the reply', async () => {
    const calls: unknown[][] = [];
    const out = await runRootScores(
      { analyse: async (...args) => (calls.push(args), analysis()) },
      [],
      DEFAULT_VARIANT,
    );
    expect(calls).toHaveLength(1);
    expect(calls[0]![1]).toBeNull();
    expect(calls[0]![2]).toBe(GUIDE_NODES);
    expect(calls[0]![4]).toBe(DEFAULT_VARIANT);
    expect(out.meta.nodes).toBe(GUIDE_NODES);
    expect(out.moves).toHaveLength(5);
  });
});

describe('root-scores-mid.json (the example)', () => {
  const d = example as unknown as RootScoresData;
  it('has the shape of the figure data: bands follow from the scores, pieces are the movers\' own', () => {
    expect(d.moves).toHaveLength(d.meta.legalMoves);
    const best = d.moves[0]!.score;
    for (const m of d.moves) {
      const loss = best - m.score;
      expect(m.band, `${m.text}, loss ${loss}`).toBe(loss <= 30 ? 'much-better' : loss <= 90 ? 'better' : loss <= 180 ? 'equal' : loss <= 300 ? 'worse' : 'much-worse');
      expect(d.cells[m.from]).toBe(d.meta.sideToMove);
      expect(d.cells[m.to]).toBe(-1);
    }
  });
});
