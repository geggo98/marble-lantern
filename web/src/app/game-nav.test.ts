import { describe, expect, it } from 'vitest';
import { applyMove, initialOwners, sideToMove } from '../game/board-state';
import { seeded } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { FakeNavigation } from './fake-navigation';
import { GameUrlSync, isOwnEntry } from './game-nav';
import { parseHash } from './game-url';

function game(plies: number): number[] {
  const random = seeded(21);
  const owner = initialOwners();
  const moves: number[] = [];
  for (let i = 0; i < plies; i++) {
    const legal = tinyLegalMoves(owner, sideToMove(i));
    const move = legal[Math.floor(random() * legal.length)]!;
    applyMove(owner, move);
    moves.push(move);
  }
  return moves;
}

const snap = (moves: number[], humanSide: 0 | 1 = 1, level = 2) => ({ moves, humanSide, level });
const movesIn = (hash: string): number[] => {
  const parsed = parseHash(hash);
  if (parsed.kind !== 'game') throw new Error(`not a game: ${hash}`);
  return [...parsed.link.moves];
};

describe('GameUrlSync: one history entry per full turn', () => {
  const m = game(8);

  it('writes the first state with replaceState: the entry the page was opened with', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    expect(sync.observe(snap([]))).toBe('replace');
    expect(sync.observe(snap([m[0]!]))).toBe('replace'); // the engine opens (human is side 1)
    expect(nav.entries).toHaveLength(1);
  });

  it('pushes after the human move and replaces when the engine answers: two plies, one new entry', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    sync.observe(snap([m[0]!]));
    expect(sync.observe(snap(m.slice(0, 2)))).toBe('push'); // side 1 moved: human
    expect(nav.entries).toHaveLength(2);
    expect(movesIn(nav.hash)).toEqual(m.slice(0, 2)); // the entry shows the state after the human's move
    expect(sync.observe(snap(m.slice(0, 3)))).toBe('replace'); // engine answer
    expect(nav.entries).toHaveLength(2);
    expect(movesIn(nav.hash)).toEqual(m.slice(0, 3));
  });

  it('Back goes back one full turn, Forward replays it', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    for (let n = 1; n <= 7; n++) sync.observe(snap(m.slice(0, n)));
    expect(nav.hashes.map((h) => movesIn(h).length)).toEqual([1, 3, 5, 7]);
    const seen: number[] = [];
    nav.subscribe((e) => seen.push(movesIn(e.hash).length));
    nav.back();
    nav.back();
    nav.forward();
    expect(seen).toEqual([5, 3, 5]);
  });

  it('writes nothing for a state the URL shows already (after a load from the URL)', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    sync.observe(snap(m.slice(0, 3)));
    sync.adopt(snap(m.slice(0, 1)));
    expect(sync.observe(snap(m.slice(0, 1)))).toBeNull();
    expect(nav.replaces).toHaveLength(1);
    expect(nav.pushes).toHaveLength(0);
  });

  it('pushes for undo and for a new game, replaces for a change of side or strength alone', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    sync.observe(snap(m.slice(0, 3)));
    expect(sync.observe(snap(m.slice(0, 1)))).toBe('push'); // undo
    expect(sync.observe(snap([]))).toBe('push'); // new game
    expect(sync.observe(snap([], 0))).toBe('replace'); // same moves, other side
    expect(sync.observe(snap([], 0, 4))).toBe('replace');
    expect(nav.entries).toHaveLength(3);
  });

  it('when the human is side 0, the human move is the even ply', () => {
    const nav = new FakeNavigation('');
    const sync = new GameUrlSync(nav);
    sync.observe(snap([], 0));
    expect(sync.observe(snap(m.slice(0, 1), 0))).toBe('push');
    expect(sync.observe(snap(m.slice(0, 2), 0))).toBe('replace');
  });

  it('recognises its own entries by the state marker', () => {
    const nav = new FakeNavigation('');
    expect(isOwnEntry(nav.state)).toBe(false);
    new GameUrlSync(nav).observe(snap([]));
    expect(isOwnEntry(nav.state)).toBe(true);
    expect(isOwnEntry(null)).toBe(false);
    expect(isOwnEntry({})).toBe(false);
  });
});
