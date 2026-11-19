// The strength figures of a model as the manifest states them (models.json, field `strength`), turned into plain facts the UI can
// print. Only what was measured: an Elo difference of a search with N simulations against the classical alpha-beta engine at M nodes,
// for one jump rule. Unknown fields are ignored; a model without figures gives an empty list.
import type { JumpRule } from '../../game/variant';
import type { ModelInfo } from './model-manifest';

export interface StrengthFact {
  /** Simulations per move of the net player. */
  sims: number;
  /** Node budget of the classical opponent. */
  opponent: number;
  jump: JumpRule;
  /** Elo of the net player relative to the opponent (negative: the opponent is stronger). */
  elo: number;
  /** Half width of the 95 % interval, when the manifest has one. */
  ci95?: number;
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/**
 * Per jump rule the measurement against the strongest opponent (the largest node budget), adjacent first.
 * Recognised shapes: `mcts<S>_vs_ab<N>_<jump>: { elo, ci95 }` and `mcts<S>_<jump>_elo: { vs_ab<N>: elo }`.
 */
export function strengthFacts(info: Pick<ModelInfo, 'strength'>): StrengthFact[] {
  const all: StrengthFact[] = [];
  for (const [key, value] of Object.entries(info.strength ?? {})) {
    const single = /^mcts(\d+)_vs_ab(\d+)_(adjacent|long)$/.exec(key);
    if (single && isObject(value) && typeof value.elo === 'number') {
      all.push({
        sims: Number(single[1]),
        opponent: Number(single[2]),
        jump: single[3] as JumpRule,
        elo: value.elo,
        ...(typeof value.ci95 === 'number' ? { ci95: value.ci95 } : {}),
      });
      continue;
    }
    const table = /^mcts(\d+)_(adjacent|long)_elo$/.exec(key);
    if (table && isObject(value)) {
      for (const [vs, elo] of Object.entries(value)) {
        const opponent = /^vs_ab(\d+)$/.exec(vs);
        if (opponent && typeof elo === 'number') all.push({ sims: Number(table[1]), opponent: Number(opponent[1]), jump: table[2] as JumpRule, elo });
      }
    }
  }
  const best = new Map<JumpRule, StrengthFact>();
  for (const f of all) {
    const old = best.get(f.jump);
    if (!old || f.opponent > old.opponent) best.set(f.jump, f);
  }
  return (['adjacent', 'long'] as const).flatMap((j) => best.get(j) ?? []);
}
