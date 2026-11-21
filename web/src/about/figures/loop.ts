// V11: the gate scores and the ladder of loop run 3, read from loop3.json (transcribed from docs/training.md, lines 1740 to 1760).
import loop3 from '../data/loop3.json';
import { parseSigned } from './numbers';

export interface Gate {
  gen: number;
  score: number;
  promoted: boolean;
}

export const gates = (): Gate[] =>
  loop3.gateScores.map((g) => ({ gen: Number(g.generation), score: Number(g.score.value), promoted: g.decision === 'promoted' }));

/** Rejected generations that the old rule (`score > 0.5`) would have promoted. The new rule is `score - 1.28 * se > 0.5`; the stored decision is its outcome. */
export const oldRuleExtras = (list: readonly Gate[] = gates()): number[] => list.filter((g) => !g.promoted && g.score > 0.5).map((g) => g.gen);

export const RUNG = '102400';

export interface LadderPoint {
  gen: number;
  elo: number;
  lo: number;
  hi: number;
}

/** The best network at generations 0, 5, 9 and 13 against 102,400 nodes, with the 95 % interval (1.96 standard errors). */
export const ladder = (): LadderPoint[] =>
  loop3.strength.map((s) => {
    const r = (s.rungs as Record<string, { elo: string; se: string }>)[RUNG] as { elo: string; se: string };
    const elo = parseSigned(r.elo);
    return { gen: Number(s.generation), elo, lo: elo - 1.96 * Number(r.se), hi: elo + 1.96 * Number(r.se) };
  });

export const STAGES = ['selfplay', 'samples', 'window', 'training', 'gate', 'decision'] as const;
export type StageId = (typeof STAGES)[number];

export const STAGE_SOURCE: Record<StageId, string> = {
  selfplay: 'selfplay/src/main/java/sternhalma/selfplay/LoopGame.java',
  samples: 'selfplay/src/main/java/sternhalma/selfplay/SampleFile.java',
  window: 'selfplay/src/main/java/sternhalma/selfplay/ReplayBuffer.java',
  training: 'nn/src/main/java/sternhalma/nn/LearningRateSchedule.java',
  gate: 'selfplay/src/main/java/sternhalma/selfplay/Gate.java',
  decision: 'selfplay/src/main/java/sternhalma/selfplay/Loop.java',
};
