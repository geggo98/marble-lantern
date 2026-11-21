// flattenMarkup turns catalog markup into plain text for the figures' SVG titles, status lines and aria labels (fig.plain).
import { describe, expect, it } from 'vitest';
import { flattenMarkup } from './shared';

describe('flattenMarkup', () => {
  const n = (v: number): string => `<${v}>`;
  it('writes {sci:} with Unicode superscripts in the language of the page, never as an empty string', () => {
    expect(flattenMarkup('from {sci:3e-4} to {sci:1e-5}', n, 'en')).toBe('from 3\u00a0·\u00a010⁻⁴ to 10⁻⁵');
    expect(flattenMarkup('{sci:1.5e-4}', n, 'de')).toBe('1,5\u00a0·\u00a010⁻⁴');
  });
  it('keeps the other tokens as before', () => {
    expect(flattenMarkup('a `c` {n:2} {term:elo|Elo}', n, 'en')).toBe('a c <2> Elo');
  });
});
