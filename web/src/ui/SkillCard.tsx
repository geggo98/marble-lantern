// The skill card: what the app estimates about the strength of the human, and the button that sets the engine to it.
// Self-contained: it needs a tracker, the calibration and the current level, and reports two actions. It does not know the
// play screen, so it can move into a menu later. Method and limits: docs/web-ui.md, section "Skill estimate".
import { useMemo, useSyncExternalStore } from 'react';
import { estimateSkill } from '../skill/calibration';
import type { Calibration, SkillEstimate } from '../skill/calibration';
import { CALIBRATION } from '../skill/data';
import { describeLevel } from '../skill/describe';
import type { LevelDescription } from '../skill/describe';
import { isCustomLevel, levelForNodes } from '../skill/level';
import type { SkillTracker } from '../skill/tracker';
import { MIN_MOVES } from '../skill/stats';
import { NODE_LEVELS } from './Panels';
import { Icon } from './primitives';
import { useT } from './texts';
import './skill-card.css';

export interface SkillCardProps {
  tracker: SkillTracker;
  /** The strength the engine has now (skill/level.ts): a preset index or a custom node budget. */
  level: number;
  /** "Match engine to my strength": the level (a custom node budget) to play at. */
  onMatch: (level: number) => void;
  /** "Reset to level": back to a preset level. */
  onReset: () => void;
  calibration?: Calibration;
}

/** Two significant figures: 7,300 rather than 7,284.6; the estimate is not that exact. */
export function roundNodes(nodes: number): number {
  const digits = Math.pow(10, Math.max(0, Math.floor(Math.log10(nodes)) - 1));
  return Math.max(1, Math.round(nodes / digits) * digits);
}

export function SkillCard({ tracker, level, onMatch, onReset, calibration = CALIBRATION }: SkillCardProps): React.JSX.Element {
  const { t, n } = useT();
  const state = useSyncExternalStore(tracker.subscribe, tracker.getState);
  const estimate: SkillEstimate = useMemo(() => estimateSkill(state.losses, calibration), [state.losses, calibration]);

  const words = (d: LevelDescription): string => {
    switch (d.kind) {
      case 'at': return t('skill_at', { level: d.level });
      case 'between': return t('skill_between', { low: d.low, high: d.high });
      case 'below': return t('skill_below');
      case 'above': return t('skill_above');
    }
  };

  let body: React.JSX.Element;
  if (state.status === 'unsupported') {
    body = <p className="ml-skill__note">{t('skill_unsupported')}</p>;
  } else if (!estimate.enough) {
    body = (
      <p className="ml-skill__note">
        {t('skill_need_more', { n: estimate.n, needed: MIN_MOVES })}
      </p>
    );
  } else {
    const nodes = roundNodes(estimate.nodes);
    body = (
      <>
        <p className="ml-skill__value">
          <span className="ml-skill__label">{t('skill_estimate')}</span>
          <strong data-testid="skill-estimate">{words(describeLevel(estimate.nodes, NODE_LEVELS))}</strong>
        </p>
        <p className="ml-skill__note">
          {t('skill_nodes', { nodes: n(nodes) })}. {t('skill_range', { low: n(roundNodes(estimate.lowNodes)), high: n(roundNodes(estimate.highNodes)) })}
        </p>
        {estimate.clamp && <p className="ml-skill__note">{t(estimate.clamp === 'weak' ? 'skill_edge_weak' : 'skill_edge_strong')}</p>}
        <p className="ml-skill__note" data-testid="skill-moves">{t('skill_based_on', { n: estimate.n })}</p>
      </>
    );
  }

  const matchLevel = estimate.enough ? levelForNodes(roundNodes(estimate.nodes)) : null;
  return (
    <section className="ml-card ml-skill" aria-label={t('skill_heading')}>
      <p className="ml-kicker">{t('skill_heading')}</p>
      {body}
      {state.status === 'error' && <p className="ml-skill__note" role="status">{t('skill_error')}</p>}
      {state.status !== 'unsupported' && state.total > state.done && (
        <p className="ml-skill__note" role="status" data-testid="skill-progress">{t('skill_progress', { done: state.done, total: state.total })}</p>
      )}
      <div className="ml-skill__actions">
        <button
          type="button"
          className="ml-btn"
          disabled={matchLevel === null || matchLevel === level}
          onClick={() => matchLevel !== null && onMatch(matchLevel)}
        >
          <Icon name="tune" />
          <span>{t('skill_match')}</span>
        </button>
        {isCustomLevel(level) && (
          <button type="button" className="ml-btn ml-btn--quiet" onClick={onReset}>
            <span>{t('skill_reset')}</span>
          </button>
        )}
      </div>
      <p className="ml-skill__note ml-skill__fine">{t('skill_disclaimer')}</p>
    </section>
  );
}
