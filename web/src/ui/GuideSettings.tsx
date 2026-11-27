// The settings of the move guides in one small component, so that a later package can move it into a menu:
// the level choice (four radio buttons in a 2 x 2 grid), a one-line status, and the legend (collapsible).
// The level "net" adds a second opinion of a neural network (design: net hints, section 4).
import { useId } from 'react';
import { BAND_ORDER } from '../guides/bands';
import type { GuideStatus } from '../guides/analyser';
import type { Divergence } from '../guides/divergence';
import type { NetAvailability, NetHintState } from '../guides/net-hints-types';
import { GUIDE_LEVELS, effectiveGuideLevel } from '../guides/preference';
import type { GuideLevel } from '../guides/preference';
import { GuideGlyph, NetGlyph, bandTextKey, netNameKey, pieceTextKey } from './GuideGlyph';
import { NetHintBlock, useNetReasonText } from './NetHintBlock';
import { useT } from './texts';
import type { TextKey } from './texts';

/** What the guide card needs to know about the net hints (the model of useNetHints). */
export interface GuideSettingsNet {
  availability: NetAvailability;
  state: NetHintState;
  trap?: Divergence | null;
  special?: Divergence | null;
  /** Wide screens: show the net block in the card, under the status line. Small screens show it in the panel instead. */
  block?: boolean;
  /** Id of the net block, so that the chip can move the focus into it. */
  blockId?: string;
}

export interface GuideSettingsProps {
  /** The stored level. At "net" without the net the card shows "eval" as chosen and the stored value stays untouched. */
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
  /** The net hints. Absent: the level "net" is shown as unavailable, without a reason. */
  net?: GuideSettingsNet;
}

export function GuideSettings({ level: stored, onChange, status, three = false, analysisLabelKey, net }: GuideSettingsProps): React.JSX.Element {
  const { t } = useT();
  const hintId = useId();
  const reasonId = useId();
  const name = useId();
  const available = net?.availability.available ?? false;
  const level = effectiveGuideLevel(stored, available);
  const message =
    status === 'analysing' ? t('guides_analysing') : status === 'unavailable' ? t('guides_unavailable') : status === 'failed' ? t('guides_failed') : '';
  const evaluating = level === 'eval' || level === 'net';
  const reason = net?.availability.reason ?? null;
  const reasonText = useNetReasonText(reason);
  const netOff = !available && reasonText !== '';
  return (
    <section className="ml-card ml-guides-card" aria-label={t('guides_heading')} data-guides-status={status} data-guides-level-now={level}>
      {/* native radio buttons: one tab stop, the arrow keys move the choice, a screen reader announces "2 of 4" */}
      <div className="ml-guides-card__levels" role="radiogroup" aria-label={t('guides_heading')} aria-describedby={hintId} data-guides-levels="">
        {GUIDE_LEVELS.map((l) => {
          // the net option stays focusable when it cannot be chosen (aria-disabled, not disabled): a keyboard user must be able to reach the reason
          const blocked = l === 'net' && !available;
          return (
            <label key={l} className="ml-guides-card__level" {...(blocked ? { 'aria-disabled': 'true' as const } : {})}>
              <input
                type="radio"
                name={name}
                value={l}
                checked={level === l}
                data-guides-level={l}
                {...(blocked ? { 'aria-disabled': 'true' as const } : {})}
                {...(blocked && netOff ? { 'aria-describedby': reasonId } : {})}
                onChange={() => {
                  if (blocked) return;
                  onChange(l);
                }}
              />
              <span>{t(`guides_level_${l}` as TextKey)}</span>
            </label>
          );
        })}
      </div>
      {netOff && (
        <p className="ml-guides-card__netoff" id={reasonId} data-guides-net-off="">
          <svg className="ml-icon" viewBox="0 0 24 24" aria-hidden="true">
            <use href="#i-alert" />
          </svg>
          <span>{t('guides_net_off', { reason: reasonText })}{net?.availability.lostInSession ? ` ${t('guides_net_lost_fallback')}` : ''}</span>
        </p>
      )}
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
      {level === 'net' && net?.block && <NetHintBlock state={net.state} trap={net.trap ?? null} special={net.special ?? null} unavailableReason={reasonText || t('net.reason.webgpu-lost')} {...(net.blockId ? { id: net.blockId } : {})} />}
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
        {level === 'net' && (
          <>
            <p className="ml-guide-legend__heading" data-legend-net-heading="">{t('guides_legend_net_heading')}</p>
            <ul className="ml-guide-legend ml-guide-legend--net">
              {(['trap', 'special'] as const).map((kind) => (
                <li key={kind} data-legend-net={kind}>
                  <NetGlyph kind={kind} />
                  <span>
                    {t(netNameKey(kind))}
                    <br />
                    <span className="ml-guide-legend__piece">{t(`guides_legend_${kind}` as TextKey)}</span>
                  </span>
                </li>
              ))}
            </ul>
            <p className="ml-guides-card__note">{t('guides_legend_net_note')}</p>
          </>
        )}
        <p className="ml-guides-card__note">{t('guides_legend_note')}</p>
      </details>
      )}
    </section>
  );
}
