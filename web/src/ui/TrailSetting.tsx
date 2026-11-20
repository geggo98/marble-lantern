// How long the trail of the last move stays visible: a small slider, 0 = always visible.
import { useId } from 'react';
import { TRAIL_MAX_SECONDS } from './trail-fade';
import { useT } from './texts';

export function TrailSetting({ seconds, onChange }: { seconds: number; onChange: (seconds: number) => void }): React.JSX.Element {
  const { t } = useT();
  const id = useId();
  return (
    <section className="ml-card ml-trail-card" aria-label={t('trail_label')}>
      <label htmlFor={id}>{t('trail_label')}</label>
      <input
        id={id}
        type="range"
        min={0}
        max={TRAIL_MAX_SECONDS}
        step={1}
        value={seconds}
        data-trail-setting=""
        onChange={(e) => onChange(Number(e.currentTarget.value))}
      />
      <output htmlFor={id} data-trail-value="">
        {seconds === 0 ? t('trail_always') : t('trail_seconds', { n: seconds })}
      </output>
    </section>
  );
}
