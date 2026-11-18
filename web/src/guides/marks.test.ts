import { describe, expect, it } from 'vitest';
import { BAND_ORDER } from './bands';
import { MARKS, MARK_TEXT } from './marks';

describe('band marks', () => {
  it('gives each band its own glyph and its own text form', () => {
    expect(new Set(BAND_ORDER.map((b) => MARKS[b].d)).size).toBe(5);
    expect(new Set(BAND_ORDER.map((b) => MARK_TEXT[b])).size).toBe(5);
  });
  it('gives each band its own ring style', () => {
    expect(new Set(BAND_ORDER.map((b) => `${MARKS[b].ringWidth}/${MARKS[b].ringDash}`)).size).toBe(5);
  });
  it('keeps every glyph within +-7 units, so that it fits into a socket of radius 11.5', () => {
    for (const band of BAND_ORDER) {
      const numbers = [...MARKS[band].d.matchAll(/-?\d+(\.\d+)?/g)].map((m) => Math.abs(Number(m[0])));
      expect(Math.max(...numbers), band).toBeLessThanOrEqual(7);
    }
  });
});
