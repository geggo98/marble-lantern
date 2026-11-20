// The rule switches of the start dialog in one table: the panel, the summary line, the count of changed rules and the reset read it, so a
// new rule is one new entry. A rule is "editable" when `StartChoice` carries a field for it; the rest of the engine's switches are "fixed" and
// shown as text (docs/web-ui.md, "Rules panel").
import { moveLimit, withCamps, withRanking, withRows } from '../game/variant';
import type { Variant } from '../game/variant';
import type { StartChoice } from './start-choice';

export type RuleId = 'jump' | 'camps' | 'ranking';
export type RuleFamily = 'moves' | 'end';

export interface RuleOption {
  value: string;
  /** Keys of the label and of the hint of the option in the catalogs. */
  label: string;
  hint: string;
  choice: string;
}

export interface RuleDef {
  id: RuleId;
  family: RuleFamily;
  /** The field of `StartChoice` the rule edits (equals the id). */
  field: 'jump' | 'camps' | 'ranking';
  default: string;
  legend: string;
  options: readonly RuleOption[];
  /** Only a three-player mode has the rule; with two players the panel shows it as a fixed row. */
  threeOnly: boolean;
}

/** Panel order within a family: editable rules in this order. */
export const RULES: readonly RuleDef[] = [
  {
    id: 'jump', family: 'moves', field: 'jump', default: 'adjacent', legend: 'start_jump_legend', threeOnly: false,
    options: [
      { value: 'adjacent', label: 'start_jump_adjacent', hint: 'start_jump_adjacent_hint', choice: 'jump-adjacent' },
      { value: 'long', label: 'start_jump_long', hint: 'start_jump_long_hint', choice: 'jump-long' },
    ],
  },
  {
    id: 'camps', family: 'moves', field: 'camps', default: 'closed', legend: 'start_camps_legend', threeOnly: false,
    options: [
      { value: 'closed', label: 'start_camps_closed', hint: 'start_camps_closed_hint', choice: 'camps-closed' },
      { value: 'pass-through', label: 'start_camps_pass', hint: 'start_camps_pass_hint', choice: 'camps-pass' },
      { value: 'open', label: 'start_camps_open', hint: 'start_camps_open_hint', choice: 'camps-open' },
    ],
  },
  {
    id: 'ranking', family: 'end', field: 'ranking', default: 'first', legend: 'rules_ranking_legend', threeOnly: true,
    options: [
      { value: 'first', label: 'rules_ranking_first', hint: 'rules_ranking_first_hint', choice: 'ranking-first' },
      { value: 'full', label: 'rules_ranking_full', hint: 'rules_ranking_full_hint', choice: 'ranking-full' },
    ],
  },
] as const;

/** Whether the rule applies to the choice: the ranking rule needs a three-player mode. */
export const ruleApplies = (rule: RuleDef, choice: StartChoice): boolean => !rule.threeOnly || choice.mode !== 'two';

/** The rules of the panel that apply to the choice and differ from their default, in panel order. */
export function changedRules(choice: StartChoice): RuleDef[] {
  return RULES.filter((r) => ruleApplies(r, choice) && choice[r.field] !== r.default);
}

/** The choice with every editable rule back at its default. Board size, mode and seats stay. */
export function resetRules(choice: StartChoice): StartChoice {
  return { ...choice, jump: 'adjacent', camps: 'closed', ranking: 'first' };
}

/** The move limit of the game this choice starts (a fixed rule: it follows board size and player count). */
export function choiceMoveLimit(choice: StartChoice): number {
  return moveLimit(withRows({ jump: choice.jump, players: choice.mode === 'two' ? 2 : 3 }, choice.rows));
}

/** The variant of the game a choice starts, as the net notices see it. */
export function variantOfChoice(choice: StartChoice): Variant {
  const base = withRows(withCamps({ jump: choice.jump, players: choice.mode === 'two' ? 2 : 3 }, choice.camps), choice.rows);
  return choice.mode === 'two' ? base : withRanking(base, choice.ranking);
}

/** The variant with one rule set to a value (for the check "does the engine play this option?"). */
export function variantWithRule(choice: StartChoice, rule: RuleDef, value: string): Variant {
  return variantOfChoice({ ...choice, [rule.field]: value } as StartChoice);
}
