// The proportion bar of figure J5 (design spec, section 12, "ShareBar"): a bar of full width with two or three patterned segments, a total
// above it, one legend row per segment with its value and share, an optional row without a segment, and padding rows so that bars with fewer
// rows keep one height. Local to the figure for now; the spec proposes it for the kit, where the integrator can move it.
// The bar itself is aria-hidden: the legend rows carry the same values as text.
import type { Fill } from './j5-model';
import './j5-overhead.css';

export interface ShareSegment {
  id: string;
  fill: Fill;
  /** Width in percent of the bar, 0 to 100. A segment of exactly 0 is not drawn (its legend row stays). */
  widthPct: number;
  label: string;
  /** The value as text ("2.3 ms"). */
  value: string;
  /** The share as text ("6 %"); left out for a row without a share. */
  share?: string;
}

export interface ShareBarProps {
  /** `data-part` of the bar; left out for a hidden copy that only gives the size. */
  part?: string;
  /** Label of the total, and the total as text. */
  head: string;
  total: string;
  segments: readonly ShareSegment[];
  /** A row without a segment (the copies on the Mac): a dashed empty swatch and a number. */
  note?: Omit<ShareSegment, 'fill' | 'widthPct' | 'share'>;
  /** Legend rows to pad to (empty rows keep the height of a bar with fewer rows). */
  rows: number;
  /** `role="group"` with this name, for the bar that stands alone. */
  ariaLabel?: string;
  /** More attributes for the root (`data-*`). */
  attrs?: Record<string, string | undefined>;
}

export function ShareBar({ part, head, total, segments, note, rows, ariaLabel, attrs }: ShareBarProps): React.JSX.Element {
  const listed = note ? segments.length + 1 : segments.length;
  return (
    <div className="ml-fig-share" data-part={part} {...(ariaLabel ? { role: 'group', 'aria-label': ariaLabel } : {})} {...attrs}>
      <div className="ml-fig-share__head">
        <span>{head}</span>
        <span className="ml-fig-share__total" data-total="">
          {total}
        </span>
      </div>
      <div className="ml-fig-share__bar" aria-hidden="true">
        {segments.map((s) => (
          <span key={s.id} className="ml-fig-share__seg" data-seg={s.id} data-fill={s.fill} {...(s.widthPct === 0 ? { 'data-zero': '' } : {})} style={{ width: `${s.widthPct.toFixed(2)}%` }} />
        ))}
      </div>
      <ul className="ml-fig-share__list">
        {segments.map((s) => (
          <li key={s.id} className="ml-fig-share__row" data-row={s.id}>
            <span className="ml-fig-share__key" data-fill={s.fill} aria-hidden="true" />
            <span>{s.label}</span>
            <span className="ml-fig-share__val">
              <b>{s.value}</b>
              {s.share !== undefined && ` · ${s.share}`}
            </span>
          </li>
        ))}
        {note && (
          <li className="ml-fig-share__row" data-row={note.id}>
            <span className="ml-fig-share__key" data-fill="none" aria-hidden="true" />
            <span>{note.label}</span>
            <span className="ml-fig-share__val">
              <b>{note.value}</b>
            </span>
          </li>
        )}
        {Array.from({ length: Math.max(0, rows - listed) }, (_, i) => (
          <li key={`pad${i}`} className="ml-fig-share__row" aria-hidden="true" />
        ))}
      </ul>
    </div>
  );
}
