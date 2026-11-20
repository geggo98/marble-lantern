// The settings of the move guides in one small component, so that a later package can move it into a menu:
// the level choice (three radio buttons), a one-line status, and the legend (collapsible).
import { useId } from 'react';
import { BAND_ORDER } from '../guides/bands';
import type { GuideStatus } from '../guides/analyser';
import { GUIDE_LEVELS } from '../guides/preference';
import type { GuideLevel } from '../guides/preference';
import { GuideGlyph, bandTextKey, pieceTextKey } from './GuideGlyph';
import { useT } from './texts';
import type { TextKey } from './texts';

export interface GuideSettingsProps {
  level: GuideLevel;
  onChange: (level: GuideLevel) => void;
  /** The state of the analysis; "off" below the level "eval". */
  status: GuideStatus | 'off';
  /** A three-player game: the scores are paranoid scores, a note says how to read the marks. */
  three?: boolean;
  /**
   * A net engine plays and a classical engine computes the marks (docs/web-ui.md, "Analysis for the net engines"): the label key
   * of that engine. A note then says where the marks come from, so that nobody reads them as the net's own view.
   */
  analysisLabelKey?: string;
}

export function GuideSettings({ level, onChange, status, three = false, analysisLabelKey }: GuideSettingsProps): React.JSX.Element {
  const { t } = useT();
  const hintId = useId();
  const message =
    status === 'analysing' ? t('guides_analysing') : status === 'unavailable' ? t('guides_unavailable') : status === 'failed' ? t('guides_failed') : '';
  const evaluating = level === 'eval';
  const name = useId();
  return (
    <section className="ml-card ml-guides-card" aria-label={t('guides_heading')} data-guides-status={status} data-guides-level-now={level}>
      {/* native radio buttons: one tab stop, the arrow keys move the choice, a screen reader announces "2 of 3" */}
      <div className="ml-guides-card__levels" role="radiogroup" aria-label={t('guides_heading')} aria-describedby={hintId} data-guides-levels="">
        {GUIDE_LEVELS.map((l) => (
          <label key={l} className="ml-guides-card__level">
            <input type="radio" name={name} value={l} checked={level === l} data-guides-level={l} onChange={() => onChange(l)} />
            <span>{t(`guides_level_${l}` as TextKey)}</span>
          </label>
        ))}
      </div>
      <p className="ml-guides-card__hint" id={hintId}>
        {t(`guides_hint_${level}` as TextKey)}
      </p>
      {evaluating && three && <p className="ml-guides-card__note" data-guides-note-three="">{t('guides_note_three')}</p>}
      {evaluating && analysisLabelKey && (
        <p className="ml-guides-card__note" data-guides-note-net="">
          {t('guides_note_net', { engine: t(analysisLabelKey as TextKey) })}
        </p>
      )}
      <p className="ml-guides-card__status" role="status" aria-live="polite" aria-busy={status === 'analysing'}>
        {evaluating ? message : ''}
      </p>
      {evaluating && (
      <details className="ml-guides-card__legend">
        <summary>{t('guides_legend')}</summary>
        <ul className="ml-guide-legend">
          {BAND_ORDER.map((band) => (
            <li key={band} data-legend-band={band}>
              <GuideGlyph band={band} />
              <span>
                {t('guides_legend_move')}: {t(bandTextKey(band))}
                <br />
                <span className="ml-guide-legend__piece">
                  {t('guides_legend_piece')}, {t(pieceTextKey(band))}
                </span>
              </span>
            </li>
          ))}
        </ul>
        <p className="ml-guides-card__note">{t('guides_legend_note')}</p>
      </details>
      )}
    </section>
  );
}
