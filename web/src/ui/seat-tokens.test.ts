// The third marble (pearl with a diamond): contrast of its tokens in both themes, computed from design/tokens.css (WCAG 2.x).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('../../design/tokens.css', import.meta.url), 'utf8');
function block(header: string): Record<string, string> {
  const open = css.indexOf('{', css.indexOf(header));
  const out: Record<string, string> = {};
  for (const m of css.slice(open + 1, css.indexOf('}', open)).matchAll(/(--[\w-]+)\s*:\s*(#[0-9a-f]{6})/gi)) out[m[1]!] = m[2]!;
  return out;
}
const channel = (v: number): number => (v / 255 <= 0.03928 ? v / 255 / 12.92 : ((v / 255 + 0.055) / 1.055) ** 2.4);
const lum = (hex: string): number => {
  const n = parseInt(hex.slice(1), 16);
  return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255);
};
const contrast = (a: string, b: string): number => {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
};

describe.each([
  ['light', block(':root {')],
  ['dark', block(':root[data-theme="dark"]')],
] as const)('pearl marble, %s theme', (_name, t) => {
  it('has an outline that reaches 3:1 against the plate and its glow', () => {
    expect(contrast(t['--ml-piece-c-edge']!, t['--ml-board-star']!)).toBeGreaterThanOrEqual(3);
    expect(contrast(t['--ml-piece-c-edge']!, t['--ml-board-glow']!)).toBeGreaterThanOrEqual(3);
  });
  it('has a diamond that reaches 4.5:1 on the marble', () => {
    expect(contrast(t['--ml-piece-c-mark']!, t['--ml-piece-c']!)).toBeGreaterThanOrEqual(4.5);
  });
  it('has a text and trail colour that reaches 4.5:1 on the page', () => {
    expect(contrast(t['--ml-piece-c-text']!, t['--ml-color-bg']!)).toBeGreaterThanOrEqual(4.5);
  });
  it('differs from amber and blue in lightness by at least 1.5:1 (the colour-blind safe cue)', () => {
    for (const other of ['a', 'b']) {
      expect(contrast(t['--ml-piece-c']!, t[`--ml-piece-${other}`]!)).toBeGreaterThanOrEqual(1.5);
    }
  });
});
