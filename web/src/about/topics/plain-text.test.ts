import { describe, expect, it } from 'vitest';
import { plainText } from './plain-text';

describe('plainText', () => {
  const n = (v: number): string => `<${v}>`;
  it('drops the backticks of code, formats {n:} with the given formatter and shows the text of a term or a section link', () => {
    expect(plainText('a `c` {n:0.5} {term:elo|Elo} {sec:tuning/grids|Grids} b', n)).toBe('a c <0.5> Elo Grids b');
  });
  it('keeps a malformed token as it stands, without a dev warning', () => {
    expect(plainText('x {n:abc} y', n)).toBe('x {n:abc} y');
  });
});
