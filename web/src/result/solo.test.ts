import { describe, expect, it } from 'vitest';
import { CELLS, TOP_CORNER, neighbor } from '../board/cells';
import { goalCells, initialOwners, startCells } from '../game/board-state';
import type { Player, Variant } from '../game/variant';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { seeded } from '../engines/testing/fake-engine';
import { soloMoves, soloRace } from './solo';

const ADJ: Variant = { jump: 'adjacent', players: 2 };
const LONG: Variant = { jump: 'long', players: 2 };

/** Side 1 has filled its goal (the top corner) except `hole`, which is a cell of the bottom row of the goal; one more piece stands `steps` cells below it in a straight line. */
function nearlyDone(steps: number): { owner: Int8Array; hole: number } {
  const owner = new Int8Array(121).fill(-1);
  const hole = TOP_CORNER.reduce((best, c) => (CELLS[c]!.r > CELLS[best]!.r ? c : best), TOP_CORNER[0]!);
  for (const c of TOP_CORNER) if (c !== hole) owner[c] = 1;
  let at = hole;
  for (let i = 0; i < steps; i++) at = neighbor(at, 2); // direction 2 is (0, +1): one row down, towards the centre
  owner[at] = 1;
  return { owner, hole };
}

describe('soloRace', () => {
  it('is 0 when the goal is already full', async () => {
    const owner = new Int8Array(121).fill(-1);
    for (const c of TOP_CORNER) owner[c] = 1;
    expect(await soloRace(owner, 1, ADJ, { cap: 10 })).toEqual({ moves: 0, capped: false });
  });

  it('is 1 when one step fills the last hole', async () => {
    const { owner } = nearlyDone(1);
    expect(await soloRace(owner, 1, ADJ, { cap: 10 })).toEqual({ moves: 1, capped: false });
  });

  it('is 2 when the last piece is two steps away and nothing is there to jump over (no jump shortens it)', async () => {
    const { owner } = nearlyDone(2);
    expect(await soloRace(owner, 1, ADJ, { cap: 10 })).toEqual({ moves: 2, capped: false });
    expect(await soloRace(owner, 1, LONG, { cap: 10 })).toEqual({ moves: 2, capped: false });
  });

  it('reports "capped" with the cap when the race is longer than the cap, and does not change its input', async () => {
    const { owner } = nearlyDone(2);
    const before = owner.slice();
    expect(await soloRace(owner, 1, ADJ, { cap: 1 })).toEqual({ moves: 1, capped: true });
    expect(owner).toEqual(before);
  });

  it('finishes a whole race from a far-behind position in a plausible number of moves, for both jump rules and both sides', async () => {
    // the winner fills the start corner of the loser; the loser still stands in the ten centre cells nearest to that corner.
    // Every one of its ten pieces has to move at least once; a solo race of Chinese checkers takes some thirty moves.
    for (const variant of [ADJ, LONG]) {
      for (const loser of [0, 1] as const) {
        const owner = new Int8Array(121).fill(-1);
        for (const c of startCells(variant)[loser]!) owner[c] = loser === 0 ? 1 : 0; // the winner stands in the loser's start corner
        const sign = loser === 0 ? 1 : -1; // nearest to the top corner: smallest r
        const centre = CELLS.filter((c) => Math.abs(c.q) <= 4 && Math.abs(c.r) <= 4 && Math.abs(-c.q - c.r) <= 4)
          .sort((x, y) => sign * (x.r - y.r) || x.q - y.q)
          .slice(0, 10);
        for (const c of centre) owner[c.id] = loser;
        const r = await soloRace(owner, loser, variant, { cap: 100 });
        expect(r.capped, `${variant.jump} loser ${loser}`).toBe(false);
        expect(r.moves).toBeGreaterThanOrEqual(10);
        expect(r.moves).toBeLessThanOrEqual(80);
      }
    }
  });

  it('rejects with an AbortError when the signal has fired', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(soloRace(initialOwners(ADJ), 0, ADJ, { cap: 10, signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('stops at an abort in the middle of the race', async () => {
    const controller = new AbortController();
    const race = soloRace(initialOwners(ADJ), 0, ADJ, { cap: 100, signal: controller.signal });
    setTimeout(() => controller.abort(), 5);
    await expect(race).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('rejects when the side has no legal move (here: no piece at all)', async () => {
    const owner = new Int8Array(121).fill(-1);
    await expect(soloRace(owner, 1, ADJ, { cap: 5 })).rejects.toThrow('no legal move');
  });
});

describe('soloMoves', () => {
  it('agrees with the test move generator on random two-player positions (except for the leave-goal rule)', () => {
    for (const variant of [ADJ, LONG]) {
      const rand = seeded(11);
      const owner = initialOwners(variant);
      for (let ply = 0; ply < 60; ply++) {
        const side = (ply % 2) as Player;
        const moves = tinyLegalMoves(owner, side, variant);
        const move = moves[Math.floor(rand() * moves.length)]!;
        owner[move & 0xff] = -1;
        owner[move >>> 8] = side;
        for (const s of [0, 1] as const) {
          const goal = new Set(goalCells(variant)[s]);
          const expected = tinyLegalMoves(owner, s, variant).filter((m) => !(goal.has(m & 0xff) && !goal.has(m >>> 8)));
          expect(soloMoves(owner, s, variant).sort((a, b) => a - b), `${variant.jump} ply ${ply} side ${s}`).toEqual(expected);
        }
      }
    }
  });

  it('forbids a piece in its goal to end a move outside the goal', () => {
    const owner = new Int8Array(121).fill(-1);
    const piece = TOP_CORNER.reduce((best, c) => (CELLS[c]!.r > CELLS[best]!.r ? c : best), TOP_CORNER[0]!);
    owner[piece] = 1;
    const targets = soloMoves(owner, 1, ADJ).map((m) => m >>> 8);
    expect(targets.length).toBeGreaterThan(0);
    expect(targets.every((t) => TOP_CORNER.includes(t))).toBe(true);
    // the same piece one row outside may step into the goal and back to the centre
    const outside = neighbor(piece, 2);
    owner[piece] = -1;
    owner[outside] = 1;
    expect(soloMoves(owner, 1, ADJ).map((m) => m >>> 8)).toContain(piece);
  });
});
