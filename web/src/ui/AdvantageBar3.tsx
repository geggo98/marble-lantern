// The advantage indicator of a three-player game: one segment per seat, in seat order, as wide as the share of the seat.
// Not colour alone: each segment carries the marble of its seat (plain, ring, diamond), the seat number and the percentage, and
// the background pattern differs (solid, stripes, dots). A seat that has finished shows its place instead of a share.
// The accessible name lists every seat: "Advantage: Player 1 (you) 45 %, Player 2 (engine) 30 %, Player 3 (engine) 25 %".
import type { SeatShare } from '../guides/advantage3';
import { Swatch } from './primitives';
import type { SideStyle } from './primitives';
import { useT } from './texts';

export interface AdvantageBar3Props {
  /** One entry per seat, in seat order. Null before the first evaluation: three equal empty segments (state "pending"). */
  shares: readonly SeatShare[] | null;
  /** The name of each seat as the play screen calls it (seat order). */
  names: readonly string[];
  /** The marble of each seat (seat order). */
  styles: readonly SideStyle[];
  stale: boolean;
}

export function AdvantageBar3({ shares, names, styles, stale }: AdvantageBar3Props): React.JSX.Element {
  const { t } = useT();
  const pending = shares === null;
  const shown: readonly SeatShare[] = shares ?? names.map((_, seat) => ({ seat, share: 1, percent: null, place: null }));
  const textOf = (s: SeatShare): string => (pending ? '…' : s.place !== null ? t(`advantage3_place_${s.place}`) : t('advantage3_percent', { percent: s.percent ?? 0 }));
  const items = shown.map((s) =>
    s.place !== null ? `${names[s.seat] ?? ''} ${t(`advantage3_place_${s.place}`)}` : t('advantage3_share', { player: names[s.seat] ?? '', percent: s.percent ?? 0 }),
  );
  const name = pending ? `${t('advantage_heading')}: ${t('advantage_pending')}` : `${t('advantage_heading')}: ${items.join(', ')}`;
  return (
    <div className={stale ? 'ml-advantage ml-advantage--three is-stale' : 'ml-advantage ml-advantage--three'} data-advantage3="" data-state={pending ? 'pending' : 'value'} data-stale={stale ? '' : undefined}>
      <p className="ml-advantage__text" aria-hidden="true">
        {t('advantage_heading')}
        {stale && <span> ({t('advantage_updating')})</span>}
      </p>
      <div className="ml-adv3" role="img" aria-label={name}>
        {shown.map((s) => (
          <div
            key={s.seat}
            className={`ml-adv3__seg ml-adv3__seg--${styles[s.seat] ?? 'a'}`}
            data-seat={s.seat}
            {...(pending ? {} : s.percent !== null ? { 'data-percent': s.percent } : { 'data-place': s.place ?? 0 })}
            style={s.share !== null ? { flexGrow: Math.max(s.share, 0.0001) } : { flexGrow: 0 }}
          >
            <span className="ml-adv3__label">
              <Swatch style={styles[s.seat] ?? 'a'} />
              <span className="ml-adv3__num">{s.seat + 1}</span>
              <span className="ml-adv3__value">{textOf(s)}</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
