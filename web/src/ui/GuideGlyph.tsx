// The mark of a guide band as SVG: used on the board (inside a socket) and in the legend. Pure drawing, no state.
import { MARKS, NET_MARKS } from '../guides/marks';
import type { NetMarkKind } from '../guides/marks';
import type { Band } from '../guides/bands';
import type { TextKey } from './texts';

/** i18n key suffix of a band: much-better -> much_better. */
export const bandSuffix = (band: Band): string => band.replace('-', '_');
export const bandTextKey = (band: Band): TextKey => `guide_move_${bandSuffix(band)}` as TextKey;
export const pieceTextKey = (band: Band): TextKey => `guide_piece_${bandSuffix(band)}` as TextKey;

/** The ring around a socket or a piece, for the band. `r` is the radius in board units. */
export function GuideRing({ band, r }: { band: Band; r: number }): React.JSX.Element {
  const mark = MARKS[band];
  return (
    <circle
      className="ml-guide__ring"
      r={r}
      strokeWidth={mark.ringWidth}
      {...(mark.ringDash ? { strokeDasharray: mark.ringDash, strokeLinecap: 'round' as const } : {})}
    />
  );
}

/** The glyph of the band (▲▲ ▲ = ▼ ▼▼) around the point (0, 0). */
export function GuideGlyphPath({ band }: { band: Band }): React.JSX.Element {
  return <path className="ml-guide__glyph" d={MARKS[band].d} />;
}

/**
 * The badge of a net mark around (0, 0): a rounded-square plate of 14 units (design: net hints, section 1.1). The Falle is a solid plate
 * with a knocked-out zigzag, the Spezialzug a hollow plate with an asterisk. Both keep a socket-coloured halo or backing.
 */
export function NetBadge({ kind }: { kind: NetMarkKind }): React.JSX.Element {
  const mark = NET_MARKS[kind];
  return (
    <g className={`ml-net ml-net--${kind}`}>
      {mark.solid ? (
        <rect className="ml-net__plate" x={-7} y={-7} width={14} height={14} rx={3} />
      ) : (
        <>
          <rect className="ml-net__back" x={-7.8} y={-7.8} width={15.6} height={15.6} rx={3.6} />
          <rect className="ml-net__plate" x={-6.3} y={-6.3} width={12.6} height={12.6} rx={2.6} />
        </>
      )}
      <path className="ml-net__glyph" d={mark.d} strokeWidth={mark.strokeWidth} />
    </g>
  );
}

/** A standalone net badge, 24 x 24: legend, chip and panel. Hidden from assistive technology; the text next to it names the mark. */
export function NetGlyph({ kind, className }: { kind: NetMarkKind; className?: string }): React.JSX.Element {
  return (
    <svg className={className ? `ml-net-glyph ${className}` : 'ml-net-glyph'} data-net-glyph={kind} viewBox="-12 -12 24 24" aria-hidden="true" focusable="false">
      <g transform="scale(1.4)">
        <NetBadge kind={kind} />
      </g>
    </svg>
  );
}

/** i18n keys of the net marks. */
export const netNameKey = (kind: NetMarkKind): TextKey => `nethint_${kind}_name` as TextKey;

/** A standalone glyph with its ring: the legend. */
export function GuideGlyph({ band }: { band: Band }): React.JSX.Element {
  return (
    <svg className={`ml-guide ml-guide--${band} ml-guide-legend__glyph`} data-band={band} viewBox="-12 -12 24 24" aria-hidden="true" focusable="false">
      <circle className="ml-guide__socket" r={9} />
      <GuideRing band={band} r={10} />
      <GuideGlyphPath band={band} />
    </svg>
  );
}
