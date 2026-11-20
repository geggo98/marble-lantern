// The foreign-corner rule in the game URL: &c=pass or &c=open (docs/web-ui.md, "Game URL"). Old links keep closed corners.
import { describe, expect, it } from 'vitest';
import type { Variant } from '../game/variant';
import { encodeMoves, formatGameFragment, parseGameFragment } from './game-url';
import type { GameLink } from './game-url';
import { variantOf } from '../game/setup';
import { setupFromLink, snapshotOf } from './link-setup';

const m = [1 | (12 << 8)];
const OPEN2: Variant = { jump: 'adjacent', players: 2, camps: 'open' };
const PASS_LONG3: Variant = { jump: 'long', players: 3, camps: 'pass-through' };

describe('c=', () => {
  it('is written for open corners as c=open and read back', () => {
    const link: GameLink = { moves: m, humanSide: 0, level: 2, variant: OPEN2 };
    const fragment = formatGameFragment(link);
    expect(fragment).toBe(`#g=v1.${encodeMoves(m)}&h=0&l=2&c=open`);
    expect(parseGameFragment(fragment)).toEqual(link);
  });

  it('is written for pass-through as c=pass, after the three-player fields', () => {
    const link: GameLink = { moves: m, level: 1, variant: PASS_LONG3, seats: ['human', 'engine', 'engine'] };
    const fragment = formatGameFragment(link);
    expect(fragment).toBe(`#g=v1.${encodeMoves(m)}&l=1&j=long&p=3&s=hee&c=pass`);
    expect(parseGameFragment(fragment)).toEqual(link);
  });

  it('is left out for closed corners: an old link reads as closed and the default game has no variant', () => {
    const plain = `#g=v1.${encodeMoves(m)}&h=1&l=2`;
    expect(formatGameFragment({ moves: m, humanSide: 1, level: 2, variant: { jump: 'adjacent', players: 2, camps: 'closed' } })).toBe(plain);
    expect(parseGameFragment(plain).variant).toBeUndefined();
  });

  it('survives the way from the link to the game setup and back into the address', () => {
    const link = parseGameFragment(`#g=v1.${encodeMoves(m)}&h=1&l=2&c=open`);
    const setup = setupFromLink(link, { humanSide: 0, level: 0 });
    expect(variantOf(setup)).toEqual(OPEN2);
    expect(formatGameFragment(snapshotOf(m, setup, 1, 2))).toBe(`#g=v1.${encodeMoves(m)}&h=1&l=2&c=open`);
  });

  it.each([
    ['the long name', '&c=pass-through'],
    ['closed spelled out', '&c=closed'],
    ['upper case', '&c=OPEN'],
    ['an empty value', '&c='],
    ['twice', '&c=open&c=open'],
  ])('is a broken link with %s', (_name, extra) => {
    expect(() => parseGameFragment(`#g=v1.${encodeMoves(m)}${extra}`)).toThrow();
  });
});
