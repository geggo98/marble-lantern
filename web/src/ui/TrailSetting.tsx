// How long the trail of the last move stays visible: a two-way choice ("Fade out" or "Always show") and, for "Fade out", a slider of
// 1 to 10 seconds. The stored value keeps its format: 0 means always shown (docs/web-ui.md, "Fading move trail").
import { useId, useRef } from 'react';
import { TRAIL_DEFAULT_SECONDS, TRAIL_MAX_SECONDS, TRAIL_MIN_SECONDS } from './trail-fade';
import { useT } from './texts';

export function TrailSetting({ seconds, onChange }: { seconds: number; onChange: (seconds: number) => void }): React.JSX.Element {
  const { t } = useT();
  const id = useId();
  const name = useId();
  const always = seconds === 0;
  // "Fade out" again restores the last time of this session, or the default
  const lastTimed = useRef(always ? TRAIL_DEFAULT_SECONDS : seconds);
  if (!always) lastTimed.current = seconds;
  return (
    <section className="ml-card ml-trail-card" aria-label={t('trail_legend')} data-trail-card="" data-trail-mode={always ? 'always' : 'timed'}>
      <fieldset className="ml-trail-card__fieldset">
        <legend className="ml-kicker">{t('trail_legend')}</legend>
        <div className="ml-trail-card__modes">
          <label className="ml-trail-card__mode">
            <input type="radio" name={name} value="timed" checked={!always} data-trail-mode-input="timed" onChange={() => onChange(lastTimed.current)} />
            <span>{t('trail_mode_timed')}</span>
          </label>
          <label className="ml-trail-card__mode">
            <input type="radio" name={name} value="always" checked={always} data-trail-mode-input="always" onChange={() => onChange(0)} />
            <span>{t('trail_mode_always')}</span>
          </label>
        </div>
      </fieldset>
      {always ? (
        // the note takes the height of the slider row, so the card does not change size with the mode
        <p className="ml-trail-card__note" data-trail-note="">{t('trail_always_hint')}</p>
      ) : (
        <div className="ml-trail-card__time">
          <label htmlFor={id}>{t('trail_seconds_label')}</label>
          <input
            id={id}
            type="range"
            min={TRAIL_MIN_SECONDS}
            max={TRAIL_MAX_SECONDS}
            step={1}
            value={seconds}
            data-trail-setting=""
            onChange={(e) => onChange(Number(e.currentTarget.value))}
          />
          <output htmlFor={id} data-trail-value="">
            {t('trail_seconds', { n: seconds })}
          </output>
        </div>
      )}
    </section>
  );
}
