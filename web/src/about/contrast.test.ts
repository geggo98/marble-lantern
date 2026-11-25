// Contrast of the figure colours. The design notes (web/design/about/about-notes.md, "Contrast table") list 47 token pairs with their
// resolved colours and WCAG 2 ratios. This test recomputes every row from the CSS: it parses the `--ml-*` and `--ml-fig-*` tokens of
// tokens.css and tokens-about.css for light and dark, resolves the `var()` aliases, and checks per row
//   - the resolved hex values written in the table are the ones the CSS gives,
//   - the ratio written in the table is the computed one (the table rounds down to one decimal),
//   - the ratio reaches the needed value (text 4.5, graphics 3).
// The two dark blocks of each file (prefers-color-scheme and data-theme="dark") must also be equal, as the files say.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const DESIGN = fileURLToPath(new URL('../../design/', import.meta.url));
const read = (name: string): string => readFileSync(`${DESIGN}${name}`, 'utf8');

type Tokens = Record<string, string>;

/** The declarations (`--name: value;`) of the first block that starts with `opener` (a selector and its `{`). */
function block(css: string, opener: string): Tokens {
  const start = css.indexOf(opener);
  if (start < 0) throw new Error(`no block "${opener}"`);
  const open = start + opener.length;
  let depth = 1;
  let i = open;
  for (; i < css.length && depth > 0; i++) {
    if (css[i] === '{') depth++;
    else if (css[i] === '}') depth--;
  }
  const out: Tokens = {};
  const body = css.slice(open, i - 1).replace(/\/\*[\s\S]*?\*\//g, '');
  for (const m of body.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) out[m[1]!] = m[2]!.trim();
  return out;
}

const files = [read('tokens.css'), read('about/tokens-about.css')];
const LIGHT_OPENER = ':root {';
const DARK_OPENER = ':root[data-theme="dark"] {';
const MEDIA_DARK_OPENER = ':root:not([data-theme="light"]) {';

const tokensOf = (opener: string): Tokens => files.reduce<Tokens>((all, css) => ({ ...all, ...block(css, opener) }), {});
const light = tokensOf(LIGHT_OPENER);
const dark = { ...light, ...tokensOf(DARK_OPENER) };

/** Resolves `var(--x)` chains to a hex colour. */
function resolve(tokens: Tokens, name: string, depth = 0): string {
  const value = tokens[name];
  if (value === undefined) throw new Error(`token ${name} is not defined`);
  if (depth > 8) throw new Error(`alias loop at ${name}`);
  const alias = /^var\((--[a-z0-9-]+)\)$/.exec(value);
  return alias ? resolve(tokens, alias[1]!, depth + 1) : value;
}

/** WCAG 2 relative luminance of `#rrggbb`. */
function luminance(hex: string): number {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) throw new Error(`not a #rrggbb colour: ${hex}`);
  const channel = (k: number): number => {
    const v = parseInt(m[1]!.slice(k, k + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

interface Row {
  fg: string;
  bg: string;
  lightHex: [string, string];
  lightRatio: number;
  darkHex: [string, string];
  darkRatio: number;
  needed: number;
}

function tableRows(): Row[] {
  const notes = read('about/about-notes.md');
  const section = notes.slice(notes.indexOf('### Contrast table'));
  const rows: Row[] = [];
  for (const line of section.split('\n')) {
    // | `--ml-fig-accent` on `--ml-fig-bg` | #0b6660 on #fffdf8 | 6.6 | #62cfc2 on #1e1a16 | 9.2 | 3 |
    const m = /^\|\s*`(--[a-z0-9-]+)` on `(--[a-z0-9-]+)`[^|]*\|\s*(#\w{6}) on (#\w{6})\s*\|\s*([\d.]+)\s*\|\s*(#\w{6}) on (#\w{6})\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)\s*\|\s*$/.exec(line);
    if (m) rows.push({ fg: m[1]!, bg: m[2]!, lightHex: [m[3]!, m[4]!], lightRatio: Number(m[5]), darkHex: [m[6]!, m[7]!], darkRatio: Number(m[8]), needed: Number(m[9]) });
  }
  return rows;
}

const rows = tableRows();

describe('contrast of the figure tokens', () => {
  it('reads the whole table of the design notes (47 pairs; the notes say "All 47 pairs")', () => {
    expect(rows).toHaveLength(47);
    expect(rows.filter((r) => r.needed === 4.5)).toHaveLength(13); // the text pairs
    expect(rows.filter((r) => r.needed === 3)).toHaveLength(34); // the graphic pairs
  });

  it('knows the two dark blocks of each file are equal (the files require it)', () => {
    for (const css of files) expect(block(css, MEDIA_DARK_OPENER)).toEqual(block(css, DARK_OPENER));
  });

  it('computes the WCAG ratio of a known pair: black on white is 21, #777777 on white is 4.48', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrast('#777777', '#ffffff')).toBeCloseTo(4.48, 2);
  });

  describe.each(rows.map((r) => [`${r.fg} on ${r.bg}`, r] as const))('%s', (_name, row) => {
    for (const [theme, tokens, hex, written] of [
      ['light', light, row.lightHex, row.lightRatio],
      ['dark', dark, row.darkHex, row.darkRatio],
    ] as const) {
      it(`${theme}: the resolved colours, the written ratio and the threshold ${row.needed}`, () => {
        const fg = resolve(tokens, row.fg);
        const bg = resolve(tokens, row.bg);
        expect([fg, bg]).toEqual([...hex]);
        const ratio = contrast(fg, bg);
        // the table rounds down to one decimal
        expect(Math.floor(ratio * 10 + 1e-9) / 10, `computed ${ratio.toFixed(3)}`).toBe(written);
        expect(ratio, `computed ${ratio.toFixed(3)}, needed ${row.needed}`).toBeGreaterThanOrEqual(row.needed);
      });
    }
  });
});

// Figure J7 (call boundary, figures/BoundaryFigure.tsx, design spec section 8): the pairs of its lanes, walls, tags and table. The J7 design adds
// no token, so these are pairs of existing tokens. Some are in the table of the notes already; this block states every pair the figure relies on,
// with the threshold of its use (text 4.5, lines and marks 3), so that a change of a token that breaks the figure fails here.
describe('contrast of the pairs of figure J7', () => {
  const pairs: [string, string, number, string][] = [
    ['--ml-fig-mark', '--ml-fig-bg', 3, 'lane outline, wall A outside the region'],
    ['--ml-fig-mark', '--ml-fig-panel-bg', 3, 'wall B convert and slot'],
    ['--ml-color-ink', '--ml-fig-panel-bg', 4.5, 'wall names in the region'],
    ['--ml-fig-dim', '--ml-fig-panel-bg', 3, 'wall B none (dotted)'],
    ['--ml-fig-dim', '--ml-fig-bg', 3, 'outline of a dim lane'],
    ['--ml-fig-accent', '--ml-fig-panel-bg', 3, 'active wall B, badge ring'],
    ['--ml-fig-accent', '--ml-fig-bg', 3, 'active wall A, badge arrow, accent bars'],
    ['--ml-fig-accent', '--ml-fig-accent-bg', 3, 'outline of an active lane'],
    ['--ml-color-ink', '--ml-fig-accent-bg', 4.5, 'text in an active lane and in the selected column'],
    ['--ml-color-ink-muted', '--ml-fig-panel-bg', 4.5, 'region label, wall tags'],
    ['--ml-color-ink-muted', '--ml-fig-bg', 4.5, 'file names and notes in a lane, row headers'],
    ['--ml-color-on-primary', '--ml-color-primary', 4.5, 'the tag "numbers"'],
  ];
  describe.each(pairs)('%s on %s (needs %s: %s)', (fg, bg, needed) => {
    for (const [theme, tokens] of [
      ['light', light],
      ['dark', dark],
    ] as const) {
      it(`${theme}`, () => {
        const ratio = contrast(resolve(tokens, fg), resolve(tokens, bg));
        expect(ratio, `computed ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(needed);
      });
    }
  });
});
