// The advantage indicator: a gauge near the board and the same value as text ("You: slightly ahead").
import type { AdvantageInfo } from '../guides/advantage';
import { useT } from './texts';

export function AdvantageBar({ info, stale }: { info: AdvantageInfo; stale: boolean }): React.JSX.Element {
  const { t } = useT();
  const who = info.lead === 'you' ? t('advantage_you') : t('advantage_engine');
  const text =
    info.level === 'even'
      ? t('advantage_even')
      : t(`advantage_${info.level}`, { who, plies: info.plies ?? 0 });
  const percent = Math.round(Math.abs(info.value) * 50); // half of the track at full advantage
  const fillStyle =
    info.lead === 'you' ? { insetInlineStart: '50%', inlineSize: `${percent}%` } : { insetInlineEnd: '50%', inlineSize: `${percent}%` };
  return (
    <div className={stale ? 'ml-advantage is-stale' : 'ml-advantage'} data-advantage={info.lead} data-level={info.level}>
      <p className="ml-advantage__text">
        <span>{t('advantage_heading')}: </span>
        {text}
        {stale && <span> ({t('advantage_updating')})</span>}
      </p>
      <div
        className="ml-advantage__track"
        role="meter"
        aria-label={t('advantage_heading')}
        aria-valuemin={-100}
        aria-valuemax={100}
        aria-valuenow={Math.round(info.value * 100)}
        aria-valuetext={text}
      >
        {info.lead !== 'none' && <div className={`ml-advantage__fill ml-advantage__fill--${info.lead}`} style={fillStyle} />}
        <div className="ml-advantage__centre" />
      </div>
      <div className="ml-advantage__ends" aria-hidden="true">
        <span>{t('advantage_engine')}</span>
        <span>{t('advantage_you')}</span>
      </div>
    </div>
  );
}
