// The chip in the upper inline-end corner of the board: the state of the net hints on every screen size (design: net hints, sections 3.4, 4.4).
// A button of 44 x 32 px with a 44 x 44 px hit area, followed by a visually hidden live region that announces the arrival of marks.
import type { NetHintState } from '../guides/net-hints-types';
import type { NetMarkKind } from '../guides/marks';
import { NetGlyph } from './GuideGlyph';
import { useNetStateText } from './NetHintBlock';
import { useT } from './texts';

export interface NetHintChipProps {
  state: NetHintState;
  /** The marks present, in the order they show (Falle first). */
  marks: readonly NetMarkKind[];
  /** Small screens: whether the panel is open; absent on wide screens (the click moves the focus into the net block). */
  expanded?: boolean;
  /** The id of the panel or the net block that the chip controls. */
  controls?: string;
  onToggle: () => void;
  /** The announcement for this position; the live region shows it only while the state is "marks". */
  live?: string;
  /** The reason text when the state is "unavailable". */
  unavailableReason?: string;
}

export function NetHintChip({ state, marks, expanded, controls, onToggle, live = '', unavailableReason }: NetHintChipProps): React.JSX.Element | null {
  const { t } = useT();
  const stateText = useNetStateText(state, unavailableReason);
  if (state === 'off') return null;
  const busy = state === 'loading' || state === 'thinking';
  return (
    <>
      <button
        type="button"
        className="ml-nethint-chip"
        data-nethint-chip=""
        data-state={state}
        aria-label={`${t('nethints_chip_label')}: ${stateText}`}
        aria-busy={busy}
        {...(expanded !== undefined ? { 'aria-expanded': expanded } : {})}
        {...(controls ? { 'aria-controls': controls } : {})}
        onClick={onToggle}
      >
        {busy ? (
          <span className="ml-nethint-chip__dots" aria-hidden="true">
            <i />
            <i />
            <i />
          </span>
        ) : state === 'marks' ? (
          marks.map((kind) => <NetGlyph key={kind} kind={kind} />)
        ) : state === 'failed' || state === 'unavailable' ? (
          <svg className="ml-icon" viewBox="0 0 24 24" aria-hidden="true">
            <use href="#i-alert" />
          </svg>
        ) : (
          <span className="ml-nethint-chip__dash" aria-hidden="true" />
        )}
      </button>
      <p className="ml-visually-hidden" role="status" aria-live="polite" data-nethint-live="">
        {state === 'marks' ? live : ''}
      </p>
    </>
  );
}
