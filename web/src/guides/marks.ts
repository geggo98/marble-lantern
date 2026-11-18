// The mark of each band: a shape that carries the band without colour, drawn in SVG around the point (0, 0), within +-6.
// much better ▲▲, better ▲, about equal =, worse ▼, much worse ▼▼. The ring style differs as well.
import type { Band } from './bands';

export interface BandMark {
  /** Path of the glyph, filled. */
  d: string;
  /** Stroke width and dash pattern of the ring that goes with the glyph. */
  ringWidth: number;
  ringDash: string | null;
}

export const MARKS: Readonly<Record<Band, BandMark>> = {
  'much-better': { d: 'M0 -6.6L3 -1.8H-3Z M0 -0.6L3 4.2H-3Z', ringWidth: 3, ringDash: null },
  better: { d: 'M0 -3.8L4 3H-4Z', ringWidth: 2, ringDash: null },
  equal: { d: 'M-4 -2.4H4V-0.6H-4Z M-4 0.6H4V2.4H-4Z', ringWidth: 2, ringDash: '5 3' },
  worse: { d: 'M-4 -3H4L0 3.8Z', ringWidth: 2, ringDash: '1.2 2.8' },
  'much-worse': { d: 'M-3 -4.2H3L0 0.6Z M-3 0.4H3L0 5.2Z', ringWidth: 3, ringDash: '1.2 2.8' },
};

/** The text form of the mark, for places that cannot draw SVG (and for tests). */
export const MARK_TEXT: Readonly<Record<Band, string>> = {
  'much-better': '▲▲',
  better: '▲',
  equal: '=',
  worse: '▼',
  'much-worse': '▼▼',
};
