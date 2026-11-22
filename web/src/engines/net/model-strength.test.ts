import { describe, expect, it } from 'vitest';
import { listModels } from './model-manifest';
import { strengthFacts } from './model-strength';

describe('strengthFacts', () => {
  const byId = (id: string, players: 2 | 3 = 2, sims?: number) => strengthFacts(listModels().find((m) => m.id === id)!, players, sims);

  it('reads the expert model from models.json: one adjacent fact with its interval, labelled 2 players', () => {
    expect(byId('small-2p-adjacent-expert')).toEqual([{ players: 2, rows: 4, sims: 200, opponent: 25600, opponents: 1, jump: 'adjacent', elo: 490, ci95: 75 }]);
  });

  it('takes the strongest opponent per jump rule for the shared model, adjacent first', () => {
    expect(byId('small-shared-all')).toEqual([
      { players: 2, rows: 4, sims: 200, opponent: 25600, opponents: 1, jump: 'adjacent', elo: -123 },
      { players: 2, rows: 4, sims: 200, opponent: 6400, opponents: 1, jump: 'long', elo: 102 },
    ]);
  });

  it('a 3-player game gets the 3-player score of the manifest, never a 2-player line', () => {
    expect(byId('small-shared-all', 3)).toEqual([
      { players: 3, rows: 4, sims: 200, opponent: 5000, opponents: 2, jump: 'adjacent', score: 0.913 },
      { players: 3, rows: 4, sims: 200, opponent: 5000, opponents: 2, jump: 'long', score: 0.707 },
    ]);
  });

  it('a 3-player game of a model without a 3-player measurement gets nothing (the UI says "not measured")', () => {
    expect(byId('small-2p-adjacent-expert', 3)).toEqual([]);
  });

  it('reads the 5-row expert from models.json: its 5-row figure against the strongest 5-row rung, nothing for a 4-row game', () => {
    const r5 = listModels().find((m) => m.id === 'small-2p-adjacent-r5-expert')!;
    expect(strengthFacts(r5, 2, 200, 5)).toEqual([{ players: 2, rows: 5, sims: 200, opponent: 102400, opponents: 1, jump: 'adjacent', elo: 512, ci95: 81 }]);
    expect(strengthFacts(r5, 2, 200, 4)).toEqual([]);
  });

  it('a key with _r5 is a 5-row figure: shown for a 5-row game only, and a 4-row figure never for a 5-row game', () => {
    const info = { strength: { mcts200_vs_ab25600_adjacent: { elo: 490, ci95: 75 }, mcts200_vs_ab25600_adjacent_r5: { elo: 300, ci95: 40 }, mcts200_adjacent_r5_elo: { vs_ab102400: 100 } } };
    expect(strengthFacts(info, 2)).toEqual([{ players: 2, rows: 4, sims: 200, opponent: 25600, opponents: 1, jump: 'adjacent', elo: 490, ci95: 75 }]);
    expect(strengthFacts(info, 2, 200, 5)).toEqual([{ players: 2, rows: 5, sims: 200, opponent: 102400, opponents: 1, jump: 'adjacent', elo: 100 }]);
    expect(strengthFacts({ strength: { mcts200_vs_ab25600_adjacent: { elo: 490 } } }, 2, 200, 5)).toEqual([]);
    expect(strengthFacts({ strength: { mcts200_vs_ab25600_adjacent_r4: { elo: 1 }, mcts200_vs_ab25600_adjacent_r5x: { elo: 1 } } }, 2, 200, 5)).toEqual([]);
  });

  it('ties of the opponent: the measurement at the selected simulations wins, else the largest simulations', () => {
    const info = { strength: { mcts50_adjacent_elo: { vs_ab100: 1 }, mcts200_adjacent_elo: { vs_ab100: 2 }, mcts800_adjacent_elo: { vs_ab100: 3 } } };
    expect(strengthFacts(info, 2, 50)[0]).toMatchObject({ sims: 50, elo: 1 });
    expect(strengthFacts(info, 2, 200)[0]).toMatchObject({ sims: 200, elo: 2 });
    expect(strengthFacts(info, 2, 123)[0]).toMatchObject({ sims: 800, elo: 3 });
    expect(strengthFacts(info, 2)[0]).toMatchObject({ sims: 800, elo: 3 });
  });

  it('a stronger opponent beats a matching simulation count', () => {
    const info = { strength: { mcts50_adjacent_elo: { vs_ab200: 1 }, mcts800_adjacent_elo: { vs_ab100: 3 } } };
    expect(strengthFacts(info, 2, 800)[0]).toMatchObject({ opponent: 200, sims: 50 });
  });

  it('gives nothing for no figures, unknown keys, wrong types and a non-object', () => {
    expect(strengthFacts({}, 2)).toEqual([]);
    expect(strengthFacts({ strength: { note: 'x', mcts200_vs_ab1_adjacent: { elo: 'high' }, mcts200_adjacent_elo: 5, other: { elo: 1 }, mcts200_3p_score_vs_2xab3_5000: 'x' } }, 2)).toEqual([]);
    expect(strengthFacts({ strength: { mcts200_3p_score_vs_2xab3_5000: { adjacent: 'x', long: 0.5 } } }, 3)).toEqual([
      { players: 3, rows: 4, sims: 200, opponent: 5000, opponents: 2, jump: 'long', score: 0.5 },
    ]);
    expect(strengthFacts({ strength: { mcts50_adjacent_elo: { vs_ab100: 'x', foo: 3, vs_ab200: 7 } } }, 2)).toEqual([
      { players: 2, rows: 4, sims: 50, opponent: 200, opponents: 1, jump: 'adjacent', elo: 7 },
    ]);
  });
});
