// The game URL for the game variants: &j=long, &p=3 and &s=<seats>, backward compatible with the links of the default game.
import { describe, expect, it } from 'vitest';
import { applyMove, initialOwners } from '../game/board-state';
import { seeded } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { MOVE_LIMIT } from '../game/board-state';
import { makeMove } from '../engines/engine';
import { WorkerEngine } from '../engines/client';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from '../engines/testing/fake-engine';
import { GameController } from '../game/game';
import { moveLimit } from '../game/variant';
import type { Variant } from '../game/variant';
import { FakeNavigation } from './fake-navigation';
import { GameUrlSync } from './game-nav';
import { encodeMoves, formatGameFragment, parseGameFragment, parseHash } from './game-url';
import type { GameLink } from './game-url';

const LONG: Variant = { jump: 'long', players: 2 };
const THREE: Variant = { jump: 'adjacent', players: 3 };
const THREE_LONG: Variant = { jump: 'long', players: 3 };

/** A random game of the tiny generator under the variant. */
function randomGame(seed: number, plies: number, variant: Variant): number[] {
  const random = seeded(seed);
  const owner = initialOwners(variant);
  const moves: number[] = [];
  for (let i = 0; i < plies; i++) {
    const legal = tinyLegalMoves(owner, i % variant.players, variant);
    if (legal.length === 0) break;
    const move = legal[Math.floor(random() * legal.length)]!;
    applyMove(owner, move);
    moves.push(move);
  }
  return moves;
}

const m = randomGame(3, 12, THREE);
const payload = encodeMoves(m);

const isBroken = (fragment: string): boolean => parseHash(fragment).kind === 'broken';
const linkOf = (fragment: string): GameLink => {
  const parsed = parseHash(fragment);
  if (parsed.kind !== 'game') throw new Error(`not a game link: ${fragment}`);
  return parsed.link;
};

describe('old links stay valid', () => {
  it('a link of the default game has no variant fields, parses without a variant, and is written back unchanged', () => {
    const moves = randomGame(1, 9, { jump: 'adjacent', players: 2 });
    const fragment = `#g=v1.${encodeMoves(moves)}&h=1&l=2`;
    expect(parseGameFragment(fragment)).toEqual({ moves, humanSide: 1, level: 2 });
    expect(formatGameFragment({ moves, humanSide: 1, level: 2 })).toBe(fragment);
    expect(formatGameFragment({ moves, humanSide: 1, level: 2, variant: { jump: 'adjacent', players: 2 } })).toBe(fragment);
    expect(parseGameFragment('#g=v1.')).toEqual({ moves: [] });
    expect(parseGameFragment('#g=v1.&h=0&l=2')).toEqual({ moves: [], humanSide: 0, level: 2 });
  });
});

describe('long hop', () => {
  it('round trip: j=long after the level, absent for the adjacent rule', () => {
    const moves = randomGame(2, 10, LONG);
    const link: GameLink = { moves, humanSide: 0, level: 3, variant: LONG };
    const fragment = formatGameFragment(link);
    expect(fragment).toBe(`#g=v1.${encodeMoves(moves)}&h=0&l=3&j=long`);
    expect(parseGameFragment(fragment)).toEqual(link);
  });

  it('the fields come in any order', () => {
    const moves = randomGame(2, 4, LONG);
    expect(parseGameFragment(`#g=v1.${encodeMoves(moves)}&j=long&l=1&h=1`)).toEqual({ moves, humanSide: 1, level: 1, variant: LONG });
  });

  it('only "long" is a value of j: j=adjacent is not canonical, so the link is broken', () => {
    for (const bad of ['j=adjacent', 'j=Long', 'j=', 'j=1', 'j=long&j=long']) expect(isBroken(`#g=v1.&h=0&${bad}`), bad).toBe(true);
  });
});

describe('three players', () => {
  it('round trip for hot-seat (hhe), one human (ehe) and the long hop', () => {
    for (const [seats, variant] of [
      [['human', 'human', 'engine'], THREE], [['engine', 'human', 'engine'], THREE], [['human', 'engine', 'engine'], THREE_LONG],
    ] as const) {
      const link: GameLink = { moves: m, level: 2, variant, seats: [...seats] };
      const fragment = formatGameFragment(link);
      const text = seats.map((s) => (s === 'human' ? 'h' : 'e')).join('');
      expect(fragment).toBe(`#g=v1.${payload}&l=2${variant.jump === 'long' ? '&j=long' : ''}&p=3&s=${text}`);
      expect(parseGameFragment(fragment)).toEqual(link);
    }
  });

  it('s is required with p=3, p=3 with s, and a link without level or moves is fine', () => {
    expect(parseGameFragment('#g=v1.&p=3&s=hhe')).toEqual({ moves: [], variant: THREE, seats: ['human', 'human', 'engine'] });
    expect(isBroken('#g=v1.&p=3')).toBe(true);
    expect(isBroken('#g=v1.&p=3&l=2')).toBe(true);
    expect(isBroken('#g=v1.&s=hhe')).toBe(true); // s without p=3: two players use h
  });

  it('p must be 3, written once; p=2 is not canonical', () => {
    for (const bad of ['p=2', 'p=4', 'p=', 'p=03', 'p=3&p=3']) expect(isBroken(`#g=v1.&${bad}&s=hhe`), bad).toBe(true);
  });

  it('s has one character per seat, h or e, with one or two humans', () => {
    for (const bad of ['s=', 's=h', 's=he', 's=hheh', 's=hhh', 's=eee', 's=HHE', 's=hhx', 's=h-e', 's=1,0']) {
      expect(isBroken(`#g=v1.&p=3&${bad}`), bad).toBe(true);
    }
    for (const good of ['s=hee', 's=ehe', 's=eeh', 's=hhe', 's=heh', 's=ehh']) expect(isBroken(`#g=v1.&p=3&${good}`), good).toBe(false);
    expect(isBroken('#g=v1.&p=3&s=hee&s=hee')).toBe(true);
  });

  it('three players have no h: the side of one human is a seat, not 0 or 1', () => {
    expect(isBroken('#g=v1.&p=3&s=hee&h=0')).toBe(true);
    expect(isBroken('#g=v1.&p=3&s=hee&h=2')).toBe(true);
  });

  it('refuses to write a three-player link without valid seats', () => {
    expect(() => formatGameFragment({ moves: [], variant: THREE })).toThrow(/seats/);
    expect(() => formatGameFragment({ moves: [], variant: THREE, seats: ['human', 'engine'] })).toThrow(/seats/);
    expect(() => formatGameFragment({ moves: [], variant: THREE, seats: ['human', 'human', 'human'] })).toThrow(/seats/);
  });

  it('the move limit is the one of the variant: 300 plies for three players, 200 for two', () => {
    const many = (n: number): number[] => Array.from({ length: n }, (_, i) => makeMove(i % 120, (i + 1) % 121));
    expect(moveLimit(THREE)).toBe(300);
    expect(parseGameFragment(`#g=v1.${encodeMoves(many(300))}&p=3&s=hhe`).moves).toHaveLength(300);
    expect(isBroken(`#g=v1.${encodeMoves(many(301))}&p=3&s=hhe`)).toBe(true);
    expect(parseGameFragment(`#g=v1.${encodeMoves(many(MOVE_LIMIT))}&j=long`).moves).toHaveLength(200);
    expect(isBroken(`#g=v1.${encodeMoves(many(201))}&j=long`)).toBe(true);
    expect(isBroken(`#g=v1.${encodeMoves(many(201))}`)).toBe(true); // the default game: as before
  });

  it('a three-player fragment with 300 moves is still short: its length is calculated', () => {
    const moves = Array.from({ length: 300 }, (_, i) => makeMove(i % 120, (i + 1) % 121));
    const fragment = formatGameFragment({ moves, level: 2, variant: THREE, seats: ['human', 'human', 'engine'] });
    expect(fragment.length).toBe(`#g=v1.`.length + 700 + '&l=2&p=3&s=hhe'.length); // 4200 bits = 525 bytes = 700 base64 characters
  });
});

describe('malformed variant links are broken, never repaired', () => {
  it('unknown keys, repeated keys and stray text', () => {
    for (const bad of ['x=1', 'jlong', 'j=long&', '&j=long', 'j=long&h=7', 'p=3&s=hhe&q=1']) expect(isBroken(`#g=v1.&${bad}`), bad).toBe(true);
  });
});

describe('the engine decides what is legal under the variant', () => {
  async function controller(): Promise<GameController> {
    const core = new FakeEngineCore(11);
    const engine = new WorkerEngine({ info: FAKE_INFO, assets: { engine: [], manifest: '', tableGz: '' }, createWorker: () => new FakeWorker(core) });
    await engine.init();
    return new GameController({ engine, seats: ['human', 'engine'], nodes: [10, 10] });
  }

  /** A history that is legal for long hops and contains a hop that the adjacent rule forbids, found with the independent generator. */
  function longHopGame(): number[] {
    for (let seed = 1; seed < 200; seed++) {
      const owner = initialOwners();
      const moves: number[] = [];
      const random = seeded(seed);
      for (let ply = 0; ply < 80; ply++) {
        const long = tinyLegalMoves(owner, ply % 2, LONG);
        const adjacent = tinyLegalMoves(owner, ply % 2);
        const hops = long.filter((x) => !adjacent.includes(x));
        if (hops.length > 0) {
          const move = hops[0]!;
          return [...moves, move];
        }
        const move = adjacent[Math.floor(random() * adjacent.length)]!;
        applyMove(owner, move);
        moves.push(move);
      }
    }
    throw new Error('no long-hop game found');
  }

  it('a link with a long hop loads with j=long and is refused without it: the app shows "broken" for the second', async () => {
    const moves = longHopGame();
    const withJ = parseHash(`#g=v1.${encodeMoves(moves)}&h=0&j=long`);
    const without = parseHash(`#g=v1.${encodeMoves(moves)}&h=0`);
    if (withJ.kind !== 'game' || without.kind !== 'game') throw new Error('both are well-formed links');
    const game = await controller();
    await game.loadGame(withJ.link.moves, { humanSide: 0, ...(withJ.link.variant ? { variant: withJ.link.variant } : {}) });
    expect(game.state.history.slice(0, moves.length)).toEqual(moves);
    expect(game.state.variant).toEqual(LONG);
    const other = await controller();
    await expect(other.loadGame(without.link.moves, { humanSide: 0 })).rejects.toMatchObject({ code: 'illegal-history' });
    expect(other.state.history).toEqual([]); // the game stays as it was
  });
});

describe('GameUrlSync with variants', () => {
  const moves = randomGame(7, 8, THREE);
  const seats = ['human', 'engine', 'engine'] as const;
  const snap = (n: number, variant: Variant = THREE) => ({ moves: moves.slice(0, n), humanSide: 0 as const, level: 2, variant, seats: [...seats] });

  it('pushes after the human move, replaces after each engine answer: three plies, one new entry', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    expect(sync.observe(snap(0))).toBe('replace');
    expect(sync.observe(snap(1))).toBe('push'); // seat 0 is the human
    expect(sync.observe(snap(2))).toBe('replace'); // seat 1, an engine
    expect(sync.observe(snap(3))).toBe('replace'); // seat 2, an engine
    expect(sync.observe(snap(4))).toBe('push'); // the human again
    expect(nav.entries).toHaveLength(3);
    expect(nav.hash).toBe(formatGameFragment(snap(4)));
    expect(linkOf(nav.hash).seats).toEqual([...seats]);
  });

  it('hot-seat: every move of a human is a new entry', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    const hot = (n: number) => ({ moves: moves.slice(0, n), humanSide: 0 as const, level: 2, variant: THREE, seats: ['human', 'human', 'engine'] as const });
    sync.observe({ ...hot(0), seats: [...hot(0).seats] });
    expect(sync.observe({ ...hot(1), seats: [...hot(1).seats] })).toBe('push');
    expect(sync.observe({ ...hot(2), seats: [...hot(2).seats] })).toBe('push');
    expect(sync.observe({ ...hot(3), seats: [...hot(3).seats] })).toBe('replace'); // the engine of seat 2
  });

  it('a change of the seats or the variant alone is written, with the same moves', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    sync.observe(snap(2));
    expect(sync.observe({ ...snap(2), seats: ['engine', 'human', 'engine'] })).toBe('replace');
    expect(sync.observe({ ...snap(2), seats: ['engine', 'human', 'engine'] })).toBeNull();
    expect(sync.observe({ ...snap(2, THREE_LONG), seats: ['engine', 'human', 'engine'] })).toBe('replace');
    expect(nav.hash).toContain('&j=long&p=3&s=ehe');
  });

  it('a two-player long-hop game keeps the rule of turns by side', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    const two = randomGame(5, 4, LONG);
    const s = (n: number) => ({ moves: two.slice(0, n), humanSide: 0 as const, level: 2, variant: LONG });
    sync.observe(s(0));
    expect(sync.observe(s(1))).toBe('push');
    expect(sync.observe(s(2))).toBe('replace');
    expect(nav.hash).toContain('&j=long');
  });
});
