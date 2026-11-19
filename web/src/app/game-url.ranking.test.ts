// The ranking rule in the game URL: &r=full (docs/web-ui.md, "Game URL"). Old links stay as they are.
import { describe, expect, it } from 'vitest';
import { applyMove, initialOwners } from '../game/board-state';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import type { Variant } from '../game/variant';
import { FakeNavigation } from './fake-navigation';
import { GameUrlSync } from './game-nav';
import { encodeMoves, formatGameFragment, parseGameFragment, parseHash } from './game-url';
import type { GameLink } from './game-url';

const THREE: Variant = { jump: 'adjacent', players: 3 };
const THREE_FULL: Variant = { jump: 'adjacent', players: 3, ranking: 'full' };
const THREE_LONG_FULL: Variant = { jump: 'long', players: 3, ranking: 'full' };

/** The first legal move of each seat in turn, `plies` times. */
function moves(plies: number, variant: Variant = THREE): number[] {
  const owner = initialOwners(variant);
  const out: number[] = [];
  for (let i = 0; i < plies; i++) {
    const move = tinyLegalMoves(owner, i % 3, variant)[0]!;
    applyMove(owner, move);
    out.push(move);
  }
  return out;
}

describe('r=full', () => {
  const m = moves(4);
  const link = (variant: Variant): GameLink => ({ moves: m, level: 2, variant, seats: ['human', 'engine', 'engine'] });

  it('is written for full ranking, after the seats, and read back', () => {
    const fragment = formatGameFragment(link(THREE_FULL));
    expect(fragment).toBe(`#g=v1.${encodeMoves(m)}&l=2&p=3&s=hee&r=full`);
    expect(parseGameFragment(fragment)).toEqual(link(THREE_FULL));
  });

  it('is left out for first-winner rules: the link of a three-player game is as before', () => {
    expect(formatGameFragment(link(THREE))).toBe(`#g=v1.${encodeMoves(m)}&l=2&p=3&s=hee`);
    expect(parseGameFragment(formatGameFragment(link(THREE))).variant).toEqual(THREE);
  });

  it('goes with the long jump rule', () => {
    const fragment = formatGameFragment(link(THREE_LONG_FULL));
    expect(fragment).toContain('&j=long');
    expect(fragment.endsWith('&r=full')).toBe(true);
    expect(parseGameFragment(fragment).variant).toEqual(THREE_LONG_FULL);
  });

  it('is accepted in any order of the fields and ends up in the same link', () => {
    const parsed = parseGameFragment(`#g=v1.${encodeMoves(m)}&r=full&s=hee&p=3&l=2`);
    expect(parsed).toEqual(link(THREE_FULL));
  });

  it.each([
    ['another value', '&r=first'],
    ['an empty value', '&r='],
    ['upper case', '&r=FULL'],
    ['a number', '&r=1'],
    ['twice', '&r=full&r=full'],
  ])('is a broken link with %s', (_name, extra) => {
    expect(parseHash(`#g=v1.${encodeMoves(m)}&p=3&s=hee${extra}`)).toEqual({ kind: 'broken', reason: 'field' });
  });

  it('is a broken link for two players, and the writer refuses it too', () => {
    expect(parseHash(`#g=v1.${encodeMoves([])}&h=1&r=full`)).toEqual({ kind: 'broken', reason: 'field' });
    expect(() => formatGameFragment({ moves: [], humanSide: 1, variant: { jump: 'adjacent', players: 2, ranking: 'full' } })).toThrow(/three players/);
  });

  it('is a broken link without the three-player fields it belongs to', () => {
    expect(parseHash(`#g=v1.${encodeMoves([])}&r=full&p=3`)).toEqual({ kind: 'broken', reason: 'field' });
  });

  it('a link without r reads as before', () => {
    expect(parseGameFragment(`#g=v1.${encodeMoves(m)}&p=3&s=hee`).variant).toEqual(THREE);
  });
});

describe('Play on in the browser history', () => {
  const m = moves(4);
  const snap = (variant: Variant) => ({ moves: m, humanSide: 1 as const, level: 2, variant, seats: ['human', 'engine', 'engine'] as const });

  it('writes a new entry for the same moves: the Back button leads to the position before Play on', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    sync.observe(snap(THREE));
    expect(nav.entries).toHaveLength(1);
    expect(sync.observe(snap(THREE_FULL))).toBe('push');
    expect(nav.entries).toHaveLength(2);
    expect(nav.hash).toContain('&r=full');
    expect(sync.observe(snap(THREE_FULL))).toBeNull();
  });

});
