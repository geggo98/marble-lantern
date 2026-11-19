import { describe, expect, it } from 'vitest';
import { listModels } from './model-manifest';
import { strengthFacts } from './model-strength';

describe('strengthFacts', () => {
  const byId = (id: string) => strengthFacts(listModels().find((m) => m.id === id)!);

  it('reads the expert model from models.json: one adjacent fact with its interval', () => {
    expect(byId('small-2p-adjacent-expert')).toEqual([{ sims: 200, opponent: 25600, jump: 'adjacent', elo: 490, ci95: 75 }]);
  });

  it('takes the strongest opponent per jump rule for the shared model, adjacent first', () => {
    expect(byId('small-shared-all')).toEqual([
      { sims: 200, opponent: 25600, jump: 'adjacent', elo: -123 },
      { sims: 200, opponent: 6400, jump: 'long', elo: 102 },
    ]);
  });

  it('gives nothing for no figures, unknown keys, wrong types and a non-object', () => {
    expect(strengthFacts({})).toEqual([]);
    expect(strengthFacts({ strength: { note: 'x', mcts200_vs_ab1_adjacent: { elo: 'high' }, mcts200_adjacent_elo: 5, other: { elo: 1 } } })).toEqual([]);
    expect(strengthFacts({ strength: { mcts50_adjacent_elo: { vs_ab100: 'x', foo: 3, vs_ab200: 7 } } })).toEqual([{ sims: 50, opponent: 200, jump: 'adjacent', elo: 7 }]);
  });
});
