import { describe, expect, it } from 'vitest';
import { BAND_ORDER } from './bands';
import { MARKS, MARK_TEXT, NET_MARKS, NET_MARK_TEXT } from './marks';

describe('net marks', () => {
  it('gives the two marks different glyphs, text forms and plate styles (shape alone tells them apart)', () => {
    expect(NET_MARKS.trap.d).not.toBe(NET_MARKS.special.d);
    expect(NET_MARK_TEXT.trap).not.toBe(NET_MARK_TEXT.special);
    expect(NET_MARKS.trap.solid).toBe(true);
    expect(NET_MARKS.special.solid).toBe(false);
  });
  it('has glyphs that differ from every band glyph and text form', () => {
    for (const band of BAND_ORDER) {
      for (const kind of ['trap', 'special'] as const) {
        expect(NET_MARKS[kind].d, `${kind} vs ${band}`).not.toBe(MARKS[band].d);
        expect(NET_MARK_TEXT[kind], `${kind} vs ${band}`).not.toBe(MARK_TEXT[band]);
      }
    }
  });
  it('keeps every glyph within +-6 units, inside the 14-unit plate', () => {
    for (const kind of ['trap', 'special'] as const) {
      const numbers = [...NET_MARKS[kind].d.matchAll(/-?\d+(\.\d+)?/g)].map((m) => Math.abs(Number(m[0])));
      expect(Math.max(...numbers), kind).toBeLessThanOrEqual(6);
    }
  });
});

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
