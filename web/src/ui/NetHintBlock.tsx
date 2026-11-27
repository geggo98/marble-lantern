// The net block: what the net says about this position (design: net hints, section 3). One row per mark (glyph, name, reason line, open question),
// the caveat, and the state text. Used inside the guide card (wide screens) and inside the panel (small screens). Never a tooltip.
import type { Divergence } from '../guides/divergence';
import type { NetHintState, NetUnavailableReason } from '../guides/net-hints-types';
import type { NetMarkKind } from '../guides/marks';
import { NetGlyph, bandTextKey, netNameKey } from './GuideGlyph';
import { useT } from './texts';
import type { TextKey } from './texts';

/**
 * The win chance of a net value v in [-1, 1] as a rounded percentage: p = (v + 1) / 2, to the nearest 5 %, clamped to 5..95.
 * Origin: design, section 3.2. A probe of 100 simulations deviates from the root value by about 4 percentage points in 90 % of the
 * cases, so a finer number would be false precision.
 */
export function netPercent(value: number): number {
  const p = ((value + 1) / 2) * 100;
  return Math.min(95, Math.max(5, Math.round(p / 5) * 5));
}

export interface NetHintBlockProps {
  state: NetHintState;
  trap?: Divergence | null;
  special?: Divergence | null;
  /** The text of the reason when the state is "unavailable" (the lost-GPU reason), already translated. */
  unavailableReason?: string;
  /** Id of the element, so that the chip can name it with aria-controls or move the focus into it. */
  id?: string;
  /** Show the caveat sentence (default true). */
  caveat?: boolean;
}

/** The text of a reason why the level "net" cannot be used (design, section 4.2); empty without a reason. */
export function useNetReasonText(reason: NetUnavailableReason | null): string {
  const { t } = useT();
  if (reason === null) return '';
  if (reason.kind === 'checking') return t('guides_net_reason_checking');
  if (reason.kind === 'three-players') return t('guides_net_reason_three');
  return t(`net.reason.${reason.code}` as TextKey);
}

/** The state text of the chip, the status line and the block (design, section 4.4); empty for `off`. */
export function useNetStateText(state: NetHintState, unavailableReason = ''): string {
  const { t } = useT();
  if (state === 'off') return '';
  if (state === 'unavailable') return `${t('guides_net_off', { reason: unavailableReason })} ${t('guides_net_lost_fallback')}`.trim();
  return t(`nethints_state_${state}` as TextKey);
}

export function NetHintBlock({ state, trap = null, special = null, unavailableReason = '', id, caveat = true }: NetHintBlockProps): React.JSX.Element {
  const { t } = useT();
  const stateText = useNetStateText(state, unavailableReason);
  const rows: { kind: NetMarkKind; d: Divergence }[] = [];
  if (state === 'marks') {
    if (trap) rows.push({ kind: 'trap', d: trap }); // the validated mark first
    if (special) rows.push({ kind: 'special', d: special });
  }
  return (
    <div className="ml-nethint-block" id={id} tabIndex={-1} data-nethint-state={state}>
      {rows.map(({ kind, d }) => (
        <div key={kind} className="ml-nethint-block__row" data-nethint-row={kind}>
          <NetGlyph kind={kind} />
          <div>
            <p className="ml-nethint-block__name">{t(netNameKey(kind))}</p>
            <p className="ml-nethint-block__reason">
              {kind === 'trap'
                ? t('nethint_trap_reason', { band: t(bandTextKey(d.classicalBand)), move: netPercent(d.netValue), favourite: netPercent(d.favouriteValue) })
                : t('nethint_special_reason', { band: t(bandTextKey(d.classicalBand)) })}
            </p>
            <p className="ml-nethint-block__prompt">{t(`nethint_${kind}_prompt` as TextKey)}</p>
          </div>
        </div>
      ))}
      {state !== 'off' && (
        <p className="ml-nethint-block__state" data-nethint-state-text="">
          {stateText}
        </p>
      )}
      {rows.length > 0 && caveat && <p className="ml-nethint-block__caveat">{t('nethints_caveat')}</p>}
    </div>
  );
}
