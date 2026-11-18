// The settings of the move guides in one small component, so that a later package can move it into a menu:
// the switch, a one-line status, and the legend (collapsible).
import { useId } from 'react';
import { BAND_ORDER } from '../guides/bands';
import type { GuideStatus } from '../guides/analyser';
import { GuideGlyph, bandTextKey, pieceTextKey } from './GuideGlyph';
import { useT } from './texts';

export interface GuideSettingsProps {
  enabled: boolean;
  onChange: (enabled: boolean) => void;
  /** The state of the analysis; "off" while the switch is off. */
  status: GuideStatus | 'off';
}

export function GuideSettings({ enabled, onChange, status }: GuideSettingsProps): React.JSX.Element {
  const { t } = useT();
  const hintId = useId();
  const message =
    status === 'analysing' ? t('guides_analysing') : status === 'unavailable' ? t('guides_unavailable') : status === 'failed' ? t('guides_failed') : '';
  return (
    <section className="ml-card ml-guides-card" aria-label={t('guides_heading')} data-guides-status={status}>
      <label className="ml-guides-card__toggle">
        <input
          type="checkbox"
          role="switch"
          checked={enabled}
          aria-describedby={hintId}
          data-guides-toggle=""
          onChange={(e) => onChange(e.currentTarget.checked)}
        />
        <span>{t('guides_toggle')}</span>
      </label>
      <p className="ml-guides-card__hint" id={hintId}>
        {t('guides_hint')}
      </p>
      <p className="ml-guides-card__status" role="status" aria-live="polite" aria-busy={status === 'analysing'}>
        {enabled ? message : ''}
      </p>
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
    </section>
  );
}
