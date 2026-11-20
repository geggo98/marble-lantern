// The skill estimate per game variant (docs/web-ui.md, "Skill estimate" and "Game variants").
//
// The calibration (calibration.json) was fitted on two-player games with the adjacent jump rule. Three players have no analysis,
// so no skill estimate exists. For the long hop (two players) the analysis works, but the node budget that matches a loss
// statistic was never measured for that rule: the estimate uses the adjacent calibration for lack of a better one and says so
// with `calibrated: false`.
import { estimateSkill } from './calibration';
import type { Calibration, SkillEstimate } from './calibration';
import { campsOf, rowsOf } from '../game/variant';
import type { Variant } from '../game/variant';

export type VariantEstimate = SkillEstimate & {
  /** True when the calibration was measured for this variant. False for the long hop: the numbers are a rough guide only. */
  calibrated: boolean;
};

/** Moves are measured and an estimate is offered: two players. */
export const skillEnabled = (variant: Variant): boolean => variant.players === 2;

/** The calibration was measured for this variant: two players with the adjacent jump rule, closed foreign corners and 4 rows. The 5-row board is on, but uncalibrated (10 pieces were measured, not 15). */
export const skillCalibrated = (variant: Variant): boolean => variant.players === 2 && variant.jump === 'adjacent' && campsOf(variant) === 'closed' && rowsOf(variant) === 4;

/** The estimate from the losses of `variant`, or null for a variant without skill measurement (three players). */
export function estimateForVariant(losses: readonly number[], calibration: Calibration, variant: Variant): VariantEstimate | null {
  if (!skillEnabled(variant)) return null;
  return { ...estimateSkill(losses, calibration), calibrated: skillCalibrated(variant) };
}
