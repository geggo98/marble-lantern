// The advantage strip: a gauge and the same value as text ("You: slightly ahead"). It is on the play screen in every game and has the same
// height in every state (docs/web-ui.md, "Advantage strip"):
//   value        an evaluation exists; the fill grows from the centre towards the side that is ahead
//   pending      no evaluation yet (first move, after undo to the start): an empty track
//   unavailable  the analysis engine cannot evaluate, the analysis failed, or a demo runs: an empty track with a dashed border
// The two marbles at the ends of the track name the sides without words: the engine on the left, the person on the right.
import type { AdvantageInfo } from '../guides/advantage';
import { Swatch } from './primitives';
import type { SideStyle } from './primitives';
import { useT } from './texts';

export type AdvantageState = 'value' | 'pending' | 'unavailable';

export interface AdvantageBarProps {
  state: AdvantageState;
  /** The value, in the state "value". */
  info?: AdvantageInfo | null;
  stale?: boolean;
  /** The marbles at the ends of the track; absent: no marbles (a demo has no "you"). */
  ends?: { engine: SideStyle; you: SideStyle } | null;
}

export function AdvantageBar({ state, info = null, stale = false, ends = null }: AdvantageBarProps): React.JSX.Element {
  const { t } = useT();
  const value = state === 'value' && info !== null ? info : null;
  const who = value?.lead === 'you' ? t('advantage_you') : t('advantage_engine');
  const text = value
    ? value.level === 'even'
      ? t('advantage_even')
      : t(`advantage_${value.level}`, { who, plies: value.plies ?? 0 })
    : t(state === 'unavailable' ? 'advantage_unavailable' : 'advantage_pending');
  const percent = value ? Math.round(Math.abs(value.value) * 50) : 0; // half of the track at full advantage
  const fillStyle =
    value?.lead === 'you' ? { insetInlineStart: '50%', inlineSize: `${percent}%` } : { insetInlineEnd: '50%', inlineSize: `${percent}%` };
  const track = (
    <div
      className="ml-advantage__track"
      {...(value
        ? { role: 'meter', 'aria-label': t('advantage_heading'), 'aria-valuemin': -100, 'aria-valuemax': 100, 'aria-valuenow': Math.round(value.value * 100), 'aria-valuetext': text }
        : { role: 'img', 'aria-label': `${t('advantage_heading')}: ${text}` })}
    >
      {value && value.lead !== 'none' && (
        <div
          className={`ml-advantage__fill ml-advantage__fill--${value.lead}`}
          // the colour of the marble at the end the fill grows towards: "you" grows to the right (your marble), the engine to the left
          {...(ends ? { 'data-fill': value.lead === 'you' ? ends.you : ends.engine } : {})}
          style={fillStyle}
        />
      )}
      <div className="ml-advantage__centre" />
    </div>
  );
  return (
    <div
      className={stale ? 'ml-advantage is-stale' : 'ml-advantage'}
      data-state={value ? 'value' : state === 'value' ? 'pending' : state}
      {...(value ? { 'data-advantage': value.lead, 'data-level': value.level } : {})}
    >
      <p className="ml-advantage__text">
        {value && <span className="ml-advantage__heading">{t('advantage_heading')}: </span>}
        {text}
        {value && stale && <span> ({t('advantage_updating')})</span>}
      </p>
      <div className="ml-advantage__gauge">
        {ends && <Swatch style={ends.engine} />}
        {track}
        {ends && <Swatch style={ends.you} />}
      </div>
    </div>
  );
}
