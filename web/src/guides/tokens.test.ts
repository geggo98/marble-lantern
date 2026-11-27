// Contrast of the guide colours, computed from the token file itself (web/design/tokens.css), in both themes.
// Formula: WCAG 2.x relative luminance and contrast ratio.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { BAND_ORDER } from './bands';

const css = readFileSync(new URL('../../design/tokens.css', import.meta.url), 'utf8');

/** The declarations of the block that starts at `header`, as name -> value. */
function block(header: string): Record<string, string> {
  const start = css.indexOf(header);
  if (start < 0) throw new Error(`no block ${header}`);
  const open = css.indexOf('{', start);
  const close = css.indexOf('}', open);
  const out: Record<string, string> = {};
  for (const m of css.slice(open + 1, close).matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) out[m[1]!] = m[2]!.trim();
  return out;
}

const light = block(':root {');
const darkMedia = block(':root:not([data-theme="light"])');
const darkAttr = block(':root[data-theme="dark"]');

const channel = (v: number): number => {
  const s = v / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
};
function luminance(hex: string): number {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) throw new Error(`not a #rrggbb colour: ${hex}`);
  const n = parseInt(m[1]!, 16);
  return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255);
}
export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
}

const guideName = (band: string): string => `--ml-guide-${band}`;
const BACKGROUNDS = ['--ml-board-socket', '--ml-board-star', '--ml-board-glow'] as const;

describe('contrast helper', () => {
  it('gives 21:1 for black on white and 1:1 for equal colours', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrast('#123456', '#123456')).toBeCloseTo(1, 5);
  });
});

describe.each([
  ['light', light],
  ['dark', darkAttr],
] as const)('guide colours, %s theme', (_name, theme) => {
  it.each(BAND_ORDER.map((b) => [b]))('%s reaches 3:1 against socket, plate and glow', (band) => {
    for (const bg of BACKGROUNDS) {
      const ratio = contrast(theme[guideName(band)]!, theme[bg]!);
      expect(ratio, `${band} on ${bg}`).toBeGreaterThanOrEqual(3);
    }
  });

  it('keeps the five colours apart', () => {
    const colours = BAND_ORDER.map((b) => theme[guideName(b)]);
    expect(new Set(colours).size).toBe(5);
  });

  it('keeps the better bands blue-ish and the worse bands orange-ish (blue channel above red, or the other way round)', () => {
    const rgb = (hex: string): [number, number, number] => {
      const n = parseInt(hex.slice(1), 16);
      return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
    };
    for (const band of ['much-better', 'better'] as const) {
      const [r, , b] = rgb(theme[guideName(band)]!);
      expect(b, band).toBeGreaterThan(r);
    }
    for (const band of ['worse', 'much-worse'] as const) {
      const [r, , b] = rgb(theme[guideName(band)]!);
      expect(r, band).toBeGreaterThan(b);
    }
  });
});

describe('theme blocks', () => {
  it('has the same guide colours in the media query block and the attribute block', () => {
    for (const band of BAND_ORDER) expect(darkMedia[guideName(band)], band).toBe(darkAttr[guideName(band)]);
  });
  it('defines a colour for every band in the light theme', () => {
    for (const band of BAND_ORDER) expect(light[guideName(band)], band).toMatch(/^#[0-9a-f]{6}$/i);
  });
  it('falls back to system colours in forced-colors mode', () => {
    const forced = css.slice(css.indexOf('@media (forced-colors: active)'));
    for (const band of BAND_ORDER) expect(forced).toContain(`${guideName(band)}: CanvasText`);
  });
});

// The net marks (Falle, Spezialzug) share one colour, --ml-guide-net (design: net hints, section 1.2). The design computes 5.53 / 9.99 on the socket.
describe.each([
  ['light', light, 5.5],
  ['dark', darkAttr, 9.9],
] as const)('net mark colour, %s theme', (_name, theme, onSocket) => {
  it('reaches 3:1 against socket, plate and glow', () => {
    expect(theme['--ml-guide-net'], 'token present').toMatch(/^#[0-9a-f]{6}$/i);
    for (const bg of BACKGROUNDS) {
      expect(contrast(theme['--ml-guide-net']!, theme[bg]!), `net on ${bg}`).toBeGreaterThanOrEqual(3);
    }
  });
  it('matches the contrast that the design computed on the socket', () => {
    expect(contrast(theme['--ml-guide-net']!, theme['--ml-board-socket']!)).toBeGreaterThanOrEqual(onSocket);
  });
  it('differs from every band colour', () => {
    for (const b of BAND_ORDER) expect(theme['--ml-guide-net'], b).not.toBe(theme[guideName(b)]);
  });
});

describe('net mark colour in the theme blocks', () => {
  it('is the same in the media query block and the attribute block', () => {
    expect(darkMedia['--ml-guide-net']).toBe(darkAttr['--ml-guide-net']);
    expect(darkMedia['--ml-guide-net']).toBeDefined();
  });
  it('falls back to CanvasText in forced-colors mode', () => {
    const forced = css.slice(css.indexOf('@media (forced-colors: active)'));
    expect(forced).toContain('--ml-guide-net: CanvasText');
  });
});
