// The style rules of the net-hint chip and the small-screen panel, read from the shipped sheets (jsdom does no layout, so the rules are checked as text).
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const css = readFileSync(fileURLToPath(new URL('guides.css', import.meta.url)), 'utf8');
const rule = (selector: string): string => {
  const at = css.indexOf(`${selector} {`);
  if (at < 0) throw new Error(`no rule ${selector}`);
  return css.slice(at, css.indexOf('}', at));
};

describe('the chip grows with its glyphs (two glyphs at a root font size above 16 px)', () => {
  const chip = rule('.ml-nethint-chip');
  it('has a minimum of 44 x 32 px and no fixed inline size', () => {
    expect(chip).toMatch(/min-inline-size:\s*44px/);
    expect(chip).toMatch(/min-block-size:\s*32px/);
    expect(chip).not.toMatch(/(^|[\s;])inline-size:/);
    expect(chip).not.toMatch(/(^|[\s;])block-size:/);
  });
  it('keeps the hit area of 44 px in height through its ::before', () => {
    expect(rule('.ml-nethint-chip::before')).toMatch(/inset-block:\s*-6px/);
  });
  it('the glyphs are sized in rem, so they scale with the root font size', () => {
    expect(rule('.ml-nethint-chip .ml-net-glyph')).toMatch(/inline-size:\s*1\.25rem/);
  });
});

describe('the panel is as tall as its content', () => {
  const panel = rule('.ml-nethints-panel');
  it('starts at the top of its grid area and never grows past it', () => {
    expect(panel).toMatch(/align-self:\s*start/);
    expect(panel).toMatch(/max-block-size:\s*100%/);
    expect(panel).toMatch(/overflow-y:\s*auto/);
  });
});
