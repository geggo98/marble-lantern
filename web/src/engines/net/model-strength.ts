// The strength figures of a model as the manifest states them (models.json, field `strength`), turned into plain facts the UI can
// print. Only what was measured, each with the player count and jump rule it belongs to and the opponent and simulations it was
// measured at: a 2-player Elo difference of a search with N simulations against the classical alpha-beta engine at M nodes, or a
// 3-player score against two such engines. Unknown fields are ignored; a model without figures gives an empty list.
import type { JumpRule } from '../../game/variant';
import type { ModelInfo } from './model-manifest';

export interface StrengthFact {
  /** The player count of the games the figure was measured in. */
  players: 2 | 3;
  jump: JumpRule;
  /** Simulations per move of the net player. */
  sims: number;
  /** Node budget of the classical opponent(s). */
  opponent: number;
  /** How many classical opponents played (1 in 2-player games, 2 in 3-player games). */
  opponents: number;
  /** 2 players: Elo of the net player relative to the opponent (negative: the opponent is stronger). */
  elo?: number;
  /** Half width of the 95 % interval of `elo`, when the manifest has one. */
  ci95?: number;
  /** 3 players: share of the points of the net player (1 = all, one third = even). */
  score?: number;
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Every figure of the manifest that has a recognised shape. */
export function allStrengthFacts(info: Pick<ModelInfo, 'strength'>): StrengthFact[] {
  const all: StrengthFact[] = [];
  for (const [key, value] of Object.entries(info.strength ?? {})) {
    const single = /^mcts(\d+)_vs_ab(\d+)_(adjacent|long)$/.exec(key);
    if (single && isObject(value) && typeof value.elo === 'number') {
      all.push({
        players: 2,
        jump: single[3] as JumpRule,
        sims: Number(single[1]),
        opponent: Number(single[2]),
        opponents: 1,
        elo: value.elo,
        ...(typeof value.ci95 === 'number' ? { ci95: value.ci95 } : {}),
      });
      continue;
    }
    const table = /^mcts(\d+)_(adjacent|long)_elo$/.exec(key);
    if (table && isObject(value)) {
      for (const [vs, elo] of Object.entries(value)) {
        const opponent = /^vs_ab(\d+)$/.exec(vs);
        if (opponent && typeof elo === 'number') {
          all.push({ players: 2, jump: table[2] as JumpRule, sims: Number(table[1]), opponent: Number(opponent[1]), opponents: 1, elo });
        }
      }
      continue;
    }
    // mcts200_3p_score_vs_2xab3_5000: { adjacent: 0.913, long: 0.707 }   (200 simulations, two ab3 opponents at 5000 nodes)
    const three = /^mcts(?<sims>\d+)_3p_score_vs_(?<count>\d+)xab\d+_(?<nodes>\d+)$/.exec(key);
    if (three?.groups && isObject(value)) {
      for (const jump of ['adjacent', 'long'] as const) {
        const score = value[jump];
        if (typeof score === 'number') {
          all.push({ players: 3, jump, sims: Number(three.groups.sims), opponent: Number(three.groups.nodes), opponents: Number(three.groups.count), score });
        }
      }
    }
  }
  return all;
}

/**
 * The facts to print for a game with `players` players: per jump rule (adjacent first) the measurement against the strongest
 * opponent (the largest node budget). When several were measured against that opponent, the one whose simulations equal the selected
 * preset wins, else the one with the most simulations. Figures of another player count are never returned.
 */
export function strengthFacts(info: Pick<ModelInfo, 'strength'>, players: 2 | 3, selectedSims?: number): StrengthFact[] {
  const best = new Map<JumpRule, StrengthFact>();
  const rank = (f: StrengthFact): number => f.opponent * 1e6 + (f.sims === selectedSims ? 1e5 : 0) + f.sims;
  for (const f of allStrengthFacts(info)) {
    if (f.players !== players) continue;
    const old = best.get(f.jump);
    if (!old || rank(f) > rank(old)) best.set(f.jump, f);
  }
  return (['adjacent', 'long'] as const).flatMap((j) => best.get(j) ?? []);
}
