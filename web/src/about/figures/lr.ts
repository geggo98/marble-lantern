// V13: the learning-rate schedule in TypeScript, the same formula as nn/src/main/java/sternhalma/nn/LearningRateSchedule.java, `rate()`:
//   warm-up  (step < warmup):   base * (step + 1) / warmup                                  Java lines 100 (generation 0), 115 (self-play)
//   cosine   (step >= warmup):  final + (base - final) * 0.5 * (1 + cos(pi * progress)),
//            progress = min(1, (step - warmup) / (steps - 1 - warmup))
// Generation 0 writes the end value as finalFraction * lr; a constant rate is the case final = base.
import presets from '../data/lr-presets.json';

export interface Schedule {
  base: number;
  final: number;
  warmup: number;
  steps: number;
}

export function rateAt(s: Schedule, step: number): number {
  if (step < s.warmup) return (s.base * (step + 1)) / s.warmup;
  const progress = Math.min(1, (step - s.warmup) / (s.steps - 1 - s.warmup));
  return s.final + (s.base - s.final) * 0.5 * (1 + Math.cos(Math.PI * progress));
}

/** `points` samples over the run: every warm-up corner and an even grid, always including step 0 and the last step. */
export function curve(s: Schedule, points = 160): { x: number; y: number }[] {
  const last = s.steps - 1;
  const xs = new Set<number>([0, s.warmup - 1, s.warmup, last]);
  for (let i = 0; i < points; i++) xs.add(Math.round((i / (points - 1)) * last));
  return [...xs]
    .filter((x) => x >= 0 && x <= last)
    .sort((a, b) => a - b)
    .map((x) => ({ x, y: rateAt(s, x) }));
}

export type PresetId = 'gen0' | 'constant' | 'sweep1';

interface PresetRow {
  id: string;
  lr: { value: string };
  warmup?: { value: string };
  steps?: { value: string };
  lrFinal?: { value: string };
  cosineTo?: { value: string };
}

const rows = presets.presets as PresetRow[];
const row = (id: string): PresetRow => {
  const found = rows.find((r) => r.id === id);
  if (!found) throw new Error(`lr-presets.json has no preset ${id}`);
  return found;
};
const num = (text: string | undefined): number => Number(String(text).replace(/,/g, ''));

/**
 * The three presets of the figure, read from lr-presets.json (generation 0; the constant rate of loop run 2, whose data has no step count,
 * so it takes the 15,000 steps of the sweep that compared it with the decay; the winner of sweep 1, warm-up of the loop defaults).
 */
export function presetSchedules(): Record<PresetId, Schedule> {
  const gen0 = row('generation0');
  const loop = row('loopDefault');
  const sweep = row('sweep1F');
  const sweepSteps = num(sweep.steps?.value);
  return {
    gen0: {
      base: num(gen0.lr.value),
      final: num(gen0.lr.value) * num(gen0.cosineTo?.value),
      warmup: num(gen0.warmup?.value),
      steps: num(gen0.steps?.value),
    },
    constant: { base: num(loop.lr.value), final: num(loop.lr.value), warmup: num(loop.warmup?.value), steps: sweepSteps },
    sweep1: { base: num(sweep.lr.value), final: num(sweep.lrFinal?.value), warmup: num(loop.warmup?.value), steps: sweepSteps },
  };
}

export const sameSchedule = (a: Schedule, b: Schedule): boolean =>
  a.base === b.base && a.final === b.final && a.warmup === b.warmup && a.steps === b.steps;

/** Mantissa and exponent for "3 times 10 to the power of -4". */
export function scientific(value: number): { mantissa: string; exponent: number } {
  const [m = '0', e = '0'] = value.toExponential(1).split('e');
  return { mantissa: String(Number(m)), exponent: Number(e) };
}

/** "3e-4" as the docs write a rate (a real minus sign is not used here: the text is code-like). */
export function rateText(value: number): string {
  if (value === 0) return '0';
  const { mantissa, exponent } = scientific(value);
  return `${mantissa}e${exponent}`;
}
