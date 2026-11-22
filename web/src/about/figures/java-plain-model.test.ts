// Model of figure J2 (plain Java): the data file against the alpha-beta example it claims to come from, the counts of the three views and the
// walk of seven states. Numbers in the expectations are those of the design spec (section 5) and are recomputed here from the data.
import { describe, expect, it } from 'vitest';
import tree from '../data/ab-tree-mid.json';
import {
  DATA, FRAME_INTS, LAST, LONGEST_MOVE_NAME, N, PIECES, SIZES, STEP_COUNT, WORDS_PER_LINE, bytesAllocated, fullEvalAt, hex4, mapRows, moveAtPick, moveFrom, moveName, moveTo,
  narrationKey, narrationKind, narrationValues, objectCounts, objectsMap, piecesAt, primitiveCounts, primitivesMap, slotPly, sumsAt, updateLineAt, walkAt,
} from './java-plain-model';

describe('data/java-plain.json', () => {
  it('has the 67 legal moves of the alpha-beta example, at its ply and for its side', () => {
    expect(DATA.legalMoves).toHaveLength(tree.rootMoves.length);
    expect(DATA.position.ply).toBe(tree.meta.ply);
    expect(DATA.position.sideToMove).toBe(tree.meta.sideToMove);
    expect(DATA.steps[0]!.ply).toBe(tree.meta.ply);
    expect(new Set(DATA.legalMoves).size).toBe(N);
  });

  it('contains every root move of the example, as the same from and to cells', () => {
    const packed = new Set(DATA.legalMoves);
    for (const m of tree.rootMoves) expect(packed.has(m.from | (m.to << 8))).toBe(true);
  });

  it('walks the first root move and its first two searched replies (make, make, unmake, make, unmake, unmake)', () => {
    expect(DATA.steps.map((s) => s.op)).toEqual(['root', 'make', 'make', 'unmake', 'make', 'unmake', 'unmake']);
    const root0 = tree.rootMoves[0]!;
    expect(DATA.steps[1]!.move).toBe(root0.from | (root0.to << 8));
    const [r0, r1] = root0.searched as number[][];
    expect(DATA.steps[2]!.move).toBe(r0![0]! | (r0![1]! << 8));
    expect(DATA.steps[4]!.move).toBe(r1![0]! | (r1![1]! << 8));
  });

  it('has the dsum of every state equal to the sum of its piece distances, and the distances equal to the distance table', () => {
    for (const s of DATA.steps) {
      for (const side of [0, 1] as const) {
        const st = side === 0 ? s.s0 : s.s1;
        expect(st.pieces).toHaveLength(PIECES);
        expect(st.pieces.reduce((a, [, d]) => a + d, 0), `step ${s.op} ply ${s.ply} side ${side}`).toBe(st.dsum);
        for (const [cell, d] of st.pieces) expect(DATA.dist[side][cell]).toBe(d);
      }
    }
  });

  it('returns to equal states: 0 and 6, and 1, 3 and 5', () => {
    const key = (i: number): string => JSON.stringify([DATA.steps[i]!.hash, DATA.steps[i]!.s0, DATA.steps[i]!.s1]);
    expect(key(0)).toBe(key(6));
    expect(key(1)).toBe(key(3));
    expect(key(3)).toBe(key(5));
    expect(key(0)).not.toBe(key(1));
  });

  it('has the distance sums of the spec table', () => {
    expect(DATA.steps.map((s) => [s.s0.dsum, s.s1.dsum])).toEqual([[70, 66], [64, 66], [64, 62], [64, 66], [64, 64], [64, 66], [70, 66]]);
  });

  it('records the JVM settings that the sizes hold for', () => {
    expect(SIZES.jvm).toMatch(/^OpenJDK 25/);
    expect(SIZES.compactObjectHeaders).toBe(false);
    expect(SIZES.compressedOops).toBe(true);
    expect(SIZES.cacheLine).toBe(128);
  });
});

describe('sizes and counts (view move)', () => {
  it('derives the object header from the measured sizes: 24 B object = 12 B header + 8 B fields + 4 B padding', () => {
    expect(SIZES.moveObject).toBe(SIZES.objectHeader + 2 * 4 + 4);
    expect(SIZES.arrayHeader + N * 4).toBe(284);
    expect(Math.ceil((SIZES.arrayHeader + N * SIZES.reference) / SIZES.alignment) * SIZES.alignment).toBe(SIZES.refArray67);
  });

  it('counts 68 allocations, 1,896 bytes and at least 15 cache lines for objects', () => {
    expect(objectCounts()).toEqual({ allocations: 68, bytes: 1896, lines: 15 });
  });

  it('counts 0 allocations, 284 bytes and at least 3 cache lines for primitives', () => {
    expect(primitiveCounts()).toEqual({ allocations: 0, bytes: 284, lines: 3 });
  });

  it('packs a move as from | (to << 8) and names it in the notation of the app', () => {
    const m = DATA.steps[1]!.move;
    expect(m).toBe(0x7132);
    expect(hex4(m)).toBe('7132');
    expect([moveFrom(m), moveTo(m)]).toEqual([50, 113]);
    expect(moveName(m)).toBe('H5»N3');
    expect(moveName(DATA.steps[2]!.move)).toBe('N4»J5');
    expect(moveName(DATA.steps[4]!.move)).toBe('N4»L7');
  });

  it('writes a step with a single arrow and a jump with a double one', () => {
    const names = DATA.legalMoves.map(moveName);
    expect(names.some((n) => n.includes('›'))).toBe(true);
    expect(names.some((n) => n.includes('»'))).toBe(true);
    expect(LONGEST_MOVE_NAME.length).toBeGreaterThanOrEqual(5);
  });

  it('clamps the pick into 1 to N', () => {
    expect(moveAtPick(0)).toBe(DATA.legalMoves[0]);
    expect(moveAtPick(999)).toBe(DATA.legalMoves[N - 1]);
  });

  it('draws the object map as 72 words of array and 67 objects of 6 words, in 15 rows, with the pick as one reference and one object', () => {
    const m = objectsMap(5);
    expect(m.words).toHaveLength(72 + N * 6);
    expect(mapRows(m)).toBe(15);
    expect(m.words.filter((w) => w.t === 'ref')).toHaveLength(N);
    expect(m.words.filter((w) => w.sel)).toHaveLength(1 + 6);
    const [ref, obj] = m.hop!;
    expect(m.words[ref]).toMatchObject({ t: 'ref', sel: true });
    expect(obj).toBe(72 + 4 * 6);
    expect(m.boxes).toEqual([[obj, obj + 5]]);
    expect(m.words[obj + 5]).toMatchObject({ t: 'pad', sel: true });
  });

  it('draws the primitive map as 3 cache lines of 32 words with one selected word and free buffer after the moves', () => {
    const m = primitivesMap(67);
    expect(m.words).toHaveLength(3 * WORDS_PER_LINE);
    expect(mapRows(m)).toBe(4);
    expect(m.words.filter((w) => w.sel)).toHaveLength(1);
    expect(m.words.filter((w) => w.t === 'val')).toHaveLength(N);
    expect(m.words.filter((w) => w.t === 'free')).toHaveLength(3 * WORDS_PER_LINE - 4 - N);
    expect(m.words[4 + N - 1]).toMatchObject({ t: 'val', sel: true });
  });
});

describe('walk (views undo and eval)', () => {
  it('has seven states, 0 to 6', () => {
    expect(STEP_COUNT).toBe(7);
    expect(LAST).toBe(6);
  });

  it('writes slot 0 at step 1 and slot 1 at step 2, then reads slot 1 back and overwrites it at step 4', () => {
    const states = (step: number): string[] => walkAt(step).slots.map((s) => s?.state ?? 'empty');
    expect(states(0)).toEqual(['empty', 'empty', 'empty']);
    expect(states(1)).toEqual(['write', 'empty', 'empty']);
    expect(states(2)).toEqual(['live', 'write', 'empty']);
    expect(states(3)).toEqual(['live', 'read', 'empty']);
    expect(states(4)).toEqual(['live', 'write', 'empty']);
    expect(states(5)).toEqual(['live', 'read', 'empty']);
    expect(states(6)).toEqual(['read', 'stale', 'empty']);
  });

  it('saves the hash, move, mover and mask that the make starts from', () => {
    const w = walkAt(4);
    expect(w.slots[0]).toMatchObject({ hash: DATA.steps[0]!.hash.slice(-4), move: '7132', mover: 0, mask: 0 });
    expect(w.slots[1]).toMatchObject({ hash: DATA.steps[1]!.hash.slice(-4), move: '5c72', mover: 1, mask: 0 });
  });

  it('uses the slot of the ply a make starts from and the ply an unmake returns to', () => {
    expect([1, 2, 3, 4, 5, 6].map(slotPly)).toEqual([24, 25, 25, 25, 25, 24]);
  });

  it('creates one object per make and drops one per unmake: three objects, 96 bytes at the end, all of them garbage', () => {
    const live = (s: number): number[] => walkAt(s).objects.filter((o) => o.live).map((o) => o.id);
    expect(live(2)).toEqual([1, 2]);
    expect(live(3)).toEqual([1]);
    expect(live(4)).toEqual([1, 3]);
    const end = walkAt(LAST);
    expect(end.allocations).toBe(3);
    expect(bytesAllocated(end)).toBe(96);
    expect(end.objects.filter((o) => !o.live)).toHaveLength(3);
    expect(walkAt(4).objects.filter((o) => o.fresh).map((o) => o.id)).toEqual([3]);
  });

  it('reaches a leaf at steps 2 and 4 only; the full way reads 20 pieces at each, 40 in all', () => {
    expect([0, 1, 2, 3, 4, 5, 6].map((s) => walkAt(s).leaf)).toEqual([false, false, true, false, true, false, false]);
    expect([0, 1, 2, 3, 4, 5, 6].map((s) => fullEvalAt(s).computed)).toEqual([false, false, true, false, true, false, false]);
    expect(fullEvalAt(LAST).piecesRead).toBe(40);
    expect(walkAt(LAST).updates).toBe(3);
  });

  it('keeps the order of the pieces and changes the number of the moved one in place', () => {
    const root = piecesAt(0);
    expect(root.mover).toBe(-1);
    expect(root.sides[0].every((c) => !c.changed)).toBe(true);
    const one = piecesAt(1);
    expect(one.mover).toBe(0);
    const moved = one.sides[0].findIndex((c) => c.changed);
    expect(moved).toBe(root.sides[0].findIndex((c) => c.cell === 50));
    expect(one.sides[0][moved]).toMatchObject({ cell: 113, dist: 0, changed: true });
    expect(one.sides[1].some((c) => c.changed)).toBe(false);
    const two = piecesAt(2);
    expect(two.mover).toBe(1);
    expect(two.sides[1].filter((c) => c.changed)).toEqual([expect.objectContaining({ cell: 69, dist: 6 })]);
  });

  it('shows the pieces of every state with the distance sums of the data', () => {
    for (let step = 0; step <= LAST; step++) {
      const p = piecesAt(step);
      expect(p.sides.map((side) => side.reduce((a, c) => a + c.dist, 0))).toEqual(sumsAt(step));
      for (const side of [0, 1]) expect(p.sides[side]!.map((c) => c.cell).sort((a, b) => a - b)).toEqual(DATA.steps[step]![side === 0 ? 's0' : 's1'].pieces.map(([c]) => c));
    }
  });

  it('writes the update line of the incremental way', () => {
    expect(updateLineAt(0)).toEqual({ kind: 'idle' });
    expect(updateLineAt(1)).toEqual({ kind: 'update', side: 0, from: 6, to: 0, sum: 64 });
    expect(updateLineAt(2)).toEqual({ kind: 'update', side: 1, from: 10, to: 6, sum: 62 });
    expect(updateLineAt(3)).toEqual({ kind: 'restore', sums: [64, 66] });
    expect(updateLineAt(4)).toEqual({ kind: 'update', side: 1, from: 10, to: 8, sum: 64 });
  });

  it('saves the sums before each make in the frame of its slot (20 ints per frame)', () => {
    expect(FRAME_INTS).toBe(20);
    expect(walkAt(2).slots.map((s) => s?.sums)).toEqual([[70, 66], [64, 66], undefined]);
  });
});

describe('narration', () => {
  it('follows the table of the spec for both views', () => {
    expect([0, 1, 2, 3, 4, 5, 6].map((s) => narrationKind('undo', s))).toEqual(['root', 'make', 'make', 'unmake', 'reuse', 'unmake', 'done']);
    expect([0, 1, 2, 3, 4, 5, 6].map((s) => narrationKind('eval', s))).toEqual(['root', 'make', 'leaf', 'unmake', 'leaf', 'unmake', 'done']);
    expect(narrationKey('move', 3)).toBe('fig.javaplain.narr.move');
    expect(narrationKey('eval', 2)).toBe('fig.javaplain.narr.eval.leaf');
    expect(narrationKey('undo', 4)).toBe('fig.javaplain.narr.undo.reuse');
  });

  it('gives the values of the placeholders', () => {
    expect(narrationValues('move', 0, 35)).toEqual({ k: 35, move: 'H5»N3' });
    expect(narrationValues('undo', 0, 1)).toEqual({ ply: 24 });
    expect(narrationValues('undo', 1, 1)).toMatchObject({ move: 'H5»N3', ply: 24 });
    expect(narrationValues('eval', 1, 1)).toMatchObject({ move: 'H5»N3', df: 6, dt: 0 });
    expect(narrationValues('eval', 2, 1)).toMatchObject({ move: 'N4»J5', df: 10, dt: 6 });
    expect(narrationValues('undo', 3, 1)).toMatchObject({ ply: 25 });
  });
});
