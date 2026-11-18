// The mark of a guide band as SVG: used on the board (inside a socket) and in the legend. Pure drawing, no state.
import { MARKS } from '../guides/marks';
import type { Band } from '../guides/bands';
import type { TextKey } from './texts';

/** i18n key suffix of a band: much-better -> much_better. */
export const bandSuffix = (band: Band): string => band.replace('-', '_');
export const bandTextKey = (band: Band): TextKey => `guide_band_${bandSuffix(band)}` as TextKey;
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
