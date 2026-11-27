// @vitest-environment jsdom
// The board with net hints (design: net hints, sections 1.4, 2; acceptance checks 3 to 6 and 10): the badges of Falle and Spezialzug at rest on the
// origin piece, the target badge after selection, the slot rule, dimming, accessible names, unchanged input, fade-in and reduced motion.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CELLS } from '../board/cells';
import { initialOwners } from '../game/board-state';
import { makeMove } from '../engines/engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import en from '../i18n/en.json';
import { BAND_ORDER } from '../guides/bands';
import type { Band } from '../guides/bands';
import type { BoardNetHints } from '../guides/net-hints-types';
import { NET_MARKS } from '../guides/marks';
import { cellPoint, movableCells, targetsOf } from './board-model';
import { Board } from './Board';
import type { BoardGuides } from './Board';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const owner = initialOwners();
const legal = tinyLegalMoves(owner, 0);
const movable = movableCells(legal);
const pieceA = movable[0]!;
const pieceB = movable[1]!;
const targetsA = targetsOf(legal, pieceA);
const targetsB = targetsOf(legal, pieceB);
const trapMove = makeMove(pieceA, targetsA[0]!);
const specialMove = makeMove(pieceB, targetsB[0]!);
const both: BoardNetHints = { trap: trapMove, special: specialMove };

function guidesFor(): BoardGuides {
  const pieces = new Map<number, Band>();
  movable.forEach((c, i) => pieces.set(c, BAND_ORDER[i % 5]!));
  const moves = new Map<number, Band>();
  legal.forEach((m, i) => moves.set(m, BAND_ORDER[(i + 2) % 5]!));
  return { pieces, moves };
}

let host: HTMLElement;
let root: Root;
let style: HTMLStyleElement;
const all = (selector: string): HTMLElement[] => [...host.querySelectorAll<HTMLElement>(selector)];
const cell = (id: number): HTMLElement => host.querySelector<HTMLElement>(`[data-cell="${id}"]`)!;
const svg = (): SVGElement => host.querySelector<SVGElement>('.ml-board__svg')!;

interface DrawProps {
  netHints?: BoardNetHints | null;
  interactive?: boolean;
  dimOthers?: boolean;
  rotated?: boolean;
  targetMarks?: boolean;
}
async function draw(props: DrawProps): Promise<void> {
  await act(async () => {
    root.render(
      <Board
        owner={owner}
        legal={legal}
        interactive={props.interactive ?? true}
        inert={false}
        aSide={0}
        rotated={props.rotated ?? false}
        plies={0}
        last={null}
        halo={[]}
        sideLabel={() => 'your piece'}
        onMove={() => undefined}
        guides={guidesFor()}
        dimOthers={props.dimOthers ?? true}
        {...(props.targetMarks !== undefined ? { targetMarks: props.targetMarks } : {})}
        {...(props.netHints !== undefined ? { netHints: props.netHints } : {})}
      />,
    );
  });
}
const click = async (element: Element): Promise<void> => {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
};
const keydown = async (el: Element, key: string): Promise<void> => {
  await act(async () => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  });
};
const cssText = (name: string): string => readFileSync(fileURLToPath(new URL(name, import.meta.url)), 'utf8');

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  // the real style sheets, so that the dimming and the fade-in are the ones that ship
  style = document.createElement('style');
  style.textContent = cssText('ui.css') + cssText('guides.css');
  document.head.appendChild(style);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  style.remove();
});

describe('net badges at rest (check 3)', () => {
  it('draws exactly two piece badges, on the origin cells, and no target badge', async () => {
    await draw({ netHints: both });
    const pieces = all('[data-nethint][data-nethint-at="piece"]');
    expect(pieces.map((p) => `${p.getAttribute('data-nethint')}@${p.getAttribute('data-cell-of')}`).sort()).toEqual(
      [`trap@${pieceA}`, `special@${pieceB}`].sort(),
    );
    expect(all('[data-nethint-at="target"]')).toHaveLength(0);
    expect(all('[data-nethint]')).toHaveLength(2);
  });

  it('draws the plate shapes of the two kinds: solid with the zigzag, hollow with the asterisk', async () => {
    await draw({ netHints: both });
    const trap = all('[data-nethint="trap"]')[0]!;
    const special = all('[data-nethint="special"]')[0]!;
    expect(trap.querySelector('.ml-net__glyph')?.getAttribute('d')).toBe(NET_MARKS.trap.d);
    expect(special.querySelector('.ml-net__glyph')?.getAttribute('d')).toBe(NET_MARKS.special.d);
    expect(trap.querySelector('.ml-net__back')).toBeNull();
    expect(special.querySelector('.ml-net__back')).not.toBeNull();
  });

  it('puts the badge at the lower right of the piece, mirroring the band badge, and keeps the band mark', async () => {
    await draw({ netHints: both });
    const badge = all('[data-nethint="trap"]')[0]!;
    expect(badge.querySelector('g[transform^="translate(12 12)"]')).not.toBeNull();
    expect(all('[data-guide="piece"]')).toHaveLength(movable.length);
  });

  it('shows one badge on a piece that has both marks: the Falle wins at rest', async () => {
    const twoFromOne: BoardNetHints = { trap: makeMove(pieceA, targetsA[0]!), special: makeMove(pieceA, targetsA[1]!) };
    await draw({ netHints: twoFromOne });
    const pieces = all('[data-nethint-at="piece"]');
    expect(pieces).toHaveLength(1);
    expect(pieces[0]!.getAttribute('data-nethint')).toBe('trap');
  });

  it('draws nothing without hints, with null, while inert, and for a piece that cannot move', async () => {
    for (const props of [{}, { netHints: null }, { netHints: both, interactive: false }, { netHints: { trap: makeMove(0, 1) } }] as DrawProps[]) {
      await draw(props);
      expect(all('[data-nethint]'), JSON.stringify(props)).toHaveLength(0);
    }
  });

  it('draws a single mark alone', async () => {
    await draw({ netHints: { special: specialMove } });
    expect(all('[data-nethint]').map((b) => b.getAttribute('data-nethint'))).toEqual(['special']);
  });
});

describe('net badges after a selection (check 4)', () => {
  it('shows the target badge of the selected Falle piece, keeps the band glyph, and dims the other badge to 0.4', async () => {
    await draw({ netHints: both });
    await click(cell(pieceA));
    const targetBadges = all('[data-nethint-at="target"]');
    expect(targetBadges).toHaveLength(1);
    expect(targetBadges[0]!.getAttribute('data-nethint')).toBe('trap');
    expect(Number(targetBadges[0]!.getAttribute('data-cell-of'))).toBe(targetsA[0]);
    // the band glyph of that target is still drawn
    expect(all('[data-guide="target"]')).toHaveLength(targetsA.length);
    const targetGlyphs = all('[data-guide="target"] .ml-guide__glyph');
    expect(targetGlyphs).toHaveLength(targetsA.length);
    // dimming: the badge of the other piece is dimmed, the badge of the selected piece and the target badge are not
    const other = all('[data-nethint-at="piece"]').find((b) => b.getAttribute('data-cell-of') === String(pieceB))!;
    const own = all('[data-nethint-at="piece"]').find((b) => b.getAttribute('data-cell-of') === String(pieceA))!;
    expect(getComputedStyle(other).opacity).toBe('0.4');
    expect(getComputedStyle(own).opacity).not.toBe('0.4');
    expect(getComputedStyle(targetBadges[0]!).opacity).not.toBe('0.4');
  });

  it('shows the target badge of the Spezialzug when its piece is selected, and not the Falle target', async () => {
    await draw({ netHints: both });
    await click(cell(pieceB));
    const targetBadges = all('[data-nethint-at="target"]');
    expect(targetBadges.map((b) => b.getAttribute('data-nethint'))).toEqual(['special']);
    expect(Number(targetBadges[0]!.getAttribute('data-cell-of'))).toBe(targetsB[0]);
  });

  it('shows no target badge for a piece without a mark', async () => {
    const third = movable.find((c) => c !== pieceA && c !== pieceB)!;
    await draw({ netHints: both });
    await click(cell(third));
    expect(all('[data-nethint-at="target"]')).toHaveLength(0);
  });

  it('shows the target badge only for the piece the mark starts from, also when another piece reaches the same cell', async () => {
    // two pieces that can reach one cell: the mark belongs to the first piece's move only
    let shared: { owner: number; other: number; cell: number } | undefined;
    for (const x of movable) {
      for (const y of movable) {
        const common = targetsOf(legal, x).find((c) => y !== x && targetsOf(legal, y).includes(c));
        if (common !== undefined && !shared) shared = { owner: x, other: y, cell: common };
      }
    }
    expect(shared, 'the start position has two pieces with a common target').toBeDefined();
    await draw({ netHints: { trap: makeMove(shared!.owner, shared!.cell) } });
    await click(cell(shared!.other));
    expect(all('[data-nethint-at="target"]')).toHaveLength(0);
    expect(cell(shared!.cell).getAttribute('aria-label')).not.toContain(en.nethint_move_trap);
    await keydown(cell(shared!.other), 'Escape');
    await click(cell(shared!.owner));
    expect(all('[data-nethint-at="target"]')).toHaveLength(1);
  });

  it('shows each target its own badge when both marks start from the selected piece, and keeps the piece badge as the Falle', async () => {
    await draw({ netHints: { trap: makeMove(pieceA, targetsA[0]!), special: makeMove(pieceA, targetsA[1]!) } });
    await click(cell(pieceA));
    const byTarget = new Map(all('[data-nethint-at="target"]').map((b) => [Number(b.getAttribute('data-cell-of')), b.getAttribute('data-nethint')]));
    expect(byTarget.get(targetsA[0]!)).toBe('trap');
    expect(byTarget.get(targetsA[1]!)).toBe('special');
    expect(all('[data-nethint-at="piece"]').map((b) => b.getAttribute('data-nethint'))).toEqual(['trap']);
  });

  it('removes the target badge when the selection ends', async () => {
    await draw({ netHints: both });
    await click(cell(pieceA));
    await keydown(cell(pieceA), 'Escape');
    expect(all('[data-nethint-at="target"]')).toHaveLength(0);
  });

  it('does not dim net badges without dimOthers', async () => {
    await draw({ netHints: both, dimOthers: false });
    await click(cell(pieceA));
    const other = all('[data-nethint-at="piece"]').find((b) => b.getAttribute('data-cell-of') === String(pieceB))!;
    expect(getComputedStyle(other).opacity).not.toBe('0.4');
  });

  it('puts the target badge above-right when the origin is level with or below the target, else below-right', async () => {
    const seen = new Set<string>();
    for (const rotated of [false, true]) {
      await draw({ netHints: { trap: trapMove }, rotated });
      await click(cell(pieceA));
      const badge = all('[data-nethint-at="target"]')[0]!;
      const origin = cellPoint(pieceA, rotated);
      const target = cellPoint(targetsA[0]!, rotated);
      const expected = origin.y >= target.y ? 'translate(12 -12)' : 'translate(12 12)';
      seen.add(expected);
      const slot = badge.querySelector('g[transform^="translate("]')!;
      expect(slot.getAttribute('transform')!.startsWith(expected), `rotated ${rotated}`).toBe(true);
      await keydown(cell(pieceA), 'Escape');
    }
    expect(seen.size, 'both slots are exercised by the two orientations').toBe(2);
  });

  it('puts the badge above-right when the origin is exactly level with the target (the boundary of the rule)', async () => {
    // the start position has no sideways move: build a position with one piece next to an empty cell in the same row
    const a = CELLS.find((c) => CELLS.some((d) => d.id !== c.id && Math.abs(cellPoint(c.id, false).y - cellPoint(d.id, false).y) < 1e-6 && Math.abs(cellPoint(c.id, false).x - cellPoint(d.id, false).x) < 40))!.id;
    const b = CELLS.find((d) => d.id !== a && Math.abs(cellPoint(a, false).y - cellPoint(d.id, false).y) < 1e-6 && Math.abs(cellPoint(a, false).x - cellPoint(d.id, false).x) < 40)!.id;
    const level = makeMove(a, b);
    const lone = new Int8Array(owner.length).fill(-1);
    lone[a] = 0;
    const from = a;
    await act(async () => {
      root.render(
        <Board owner={lone} legal={[level]} interactive inert={false} aSide={0} rotated={false} plies={0} last={null} halo={[]} sideLabel={() => 'x'} onMove={() => undefined} guides={guidesFor()} dimOthers netHints={{ trap: level }} />,
      );
    });
    await click(cell(from));
    const slot = all('[data-nethint-at="target"]')[0]!.querySelector('g[transform^="translate("]')!;
    expect(slot.getAttribute('transform')!.startsWith('translate(12 -12)')).toBe(true);
  });

  it('puts the layer after the selection ring and before the hit circles', async () => {
    await draw({ netHints: both });
    await click(cell(pieceA));
    const children = [...svg().children];
    const idx = (selector: string): number => children.findIndex((c) => c.matches(selector));
    expect(idx('.ml-select')).toBeGreaterThanOrEqual(0);
    expect(idx('.ml-nethints')).toBeGreaterThan(idx('.ml-select'));
    expect(idx('.ml-nethints')).toBeLessThan(idx('.ml-hit'));
  });
});

describe('accessible names (check 6)', () => {
  it('appends the piece text to the label of the origin cell, and keeps the rest of the label', async () => {
    await draw({ netHints: both });
    const plain = await labelsWithout();
    await draw({ netHints: both });
    expect(cell(pieceA).getAttribute('aria-label')!.endsWith(en.nethint_piece_trap)).toBe(true);
    expect(cell(pieceB).getAttribute('aria-label')!.endsWith(en.nethint_piece_special)).toBe(true);
    expect(cell(pieceA).getAttribute('aria-label')).toBe(`${plain.get(pieceA)}, ${en.nethint_piece_trap}`);
  });

  it('names both marks on a piece that has both', async () => {
    await draw({ netHints: { trap: makeMove(pieceA, targetsA[0]!), special: makeMove(pieceA, targetsA[1]!) } });
    const label = cell(pieceA).getAttribute('aria-label')!;
    expect(label).toContain(en.nethint_piece_trap);
    expect(label).toContain(en.nethint_piece_special);
    expect(label.indexOf(en.nethint_piece_trap)).toBeLessThan(label.indexOf(en.nethint_piece_special));
  });

  it('names the move on the target cell, and only for the selected piece', async () => {
    await draw({ netHints: both });
    expect(cell(targetsA[0]!).getAttribute('aria-label')).not.toContain(en.nethint_move_trap);
    await click(cell(pieceA));
    expect(cell(targetsA[0]!).getAttribute('aria-label')).toContain(en.nethint_move_trap);
    expect(cell(targetsA[0]!).getAttribute('aria-label')).toContain('target');
    await keydown(cell(pieceA), 'Escape');
    await click(cell(pieceB));
    expect(cell(targetsB[0]!).getAttribute('aria-label')).toContain(en.nethint_move_special);
  });

  async function labelsWithout(): Promise<Map<number, string>> {
    await draw({ netHints: null });
    return new Map(all('[data-cell]').map((c) => [Number(c.getAttribute('data-cell')), c.getAttribute('aria-label')!]));
  }
});

describe('input unchanged (check 5)', () => {
  const snapshot = (): string[] =>
    all('[data-cell]').map((c) => `${c.getAttribute('data-cell')}|${c.getAttribute('tabindex')}|${c.getAttribute('r')}|${c.getAttribute('aria-disabled')}|${c.getAttribute('role')}`);
  const stripped = (): string[] =>
    all('[data-cell]').map((c) =>
      (c.getAttribute('aria-label') ?? '')
        .replace(`, ${en.nethint_piece_trap}`, '')
        .replace(`, ${en.nethint_piece_special}`, '')
        .replace(`, ${en.nethint_move_trap}`, '')
        .replace(`, ${en.nethint_move_special}`, ''),
    );

  it('keeps hit circles, the single tab stop and the names without the new suffix', async () => {
    await draw({ netHints: null });
    const plain = [snapshot(), stripped()];
    await draw({ netHints: both });
    expect([snapshot(), stripped()]).toEqual(plain);
    expect(all('[data-cell][tabindex="0"]')).toHaveLength(1);
    await click(cell(pieceA));
    const selectedWith = [snapshot(), stripped()];
    await draw({ netHints: null });
    expect([snapshot(), stripped()]).toEqual(selectedWith);
  });

  it('gives every board key the same result with and without hints', async () => {
    const keys = ['ArrowRight', 'ArrowDown', 'End', 'Home', 'PageDown', 'PageDown', 'Enter', 'PageDown', 'ArrowUp', 'Escape', 'PageUp', ' ', 'Enter'];
    const run = async (netHints: BoardNetHints | null): Promise<string[]> => {
      await act(async () => root.unmount());
      root = createRoot(host);
      await draw({ netHints });
      const trace: string[] = [];
      let current: Element = cell(pieceA);
      for (const key of keys) {
        await keydown(current, key);
        const stop = all('[data-cell][tabindex="0"]').map((c) => c.getAttribute('data-cell')).join(',');
        trace.push(`${key}: stop=${stop} dim=${svg().hasAttribute('data-dim')} selected=${all('.ml-pieces .is-selected').length} targets=${all('.ml-target__ring').length}`);
        current = host.querySelector(`[data-cell="${stop.split(',')[0]}"]`) ?? current;
      }
      return trace;
    };
    const without = await run(null);
    const withHints = await run(both);
    expect(withHints).toEqual(without);
    expect(without.some((l) => l.includes('selected=1')), 'the key script selects a piece').toBe(true);
  });

  it('plays the same move on a tap whether or not the target has a badge', async () => {
    const played: number[] = [];
    await act(async () => {
      root.render(
        <Board owner={owner} legal={legal} interactive inert={false} aSide={0} rotated={false} plies={0} last={null} halo={[]} sideLabel={() => 'x'} onMove={(m) => played.push(m)} guides={guidesFor()} dimOthers netHints={both} />,
      );
    });
    await click(cell(pieceA));
    await click(cell(targetsA[0]!));
    expect(played).toEqual([trapMove]);
  });

  it('gives the badges no pointer events', () => {
    const rule = /\.ml-nethints[^{]*\{[^}]*pointer-events:\s*none/;
    expect(cssText('guides.css')).toMatch(rule);
  });
});

describe('motion (check 10)', () => {
  it('fades the badge in once in 0.2 s and never loops', () => {
    const css = cssText('guides.css');
    const rule = css.match(/\.ml-nethints \[data-nethint\][^{]*\{([^}]*)\}/)![1]!;
    expect(rule).toMatch(/animation:\s*ml-nethint-in 0\.2s ease\b/);
    expect(rule).not.toMatch(/infinite/);
    const frames = css.match(/@keyframes ml-nethint-in\s*\{([^]*?\})\s*\}/)![1]!;
    expect(frames).toMatch(/from\s*\{\s*opacity:\s*0/);
    expect(frames).toMatch(/to\s*\{\s*opacity:\s*1/);
    expect(css).not.toMatch(/ml-nethints[^{]*\{[^}]*infinite/);
  });

  it('turns the fade-in and the dimming transition off under prefers-reduced-motion', () => {
    const css = cssText('guides.css');
    const block = css.match(/@media \(prefers-reduced-motion: reduce\)\s*\{([^]*?)\n\}/g)!.find((b) => b.includes('ml-nethints'))!;
    expect(block).toMatch(/\.ml-nethints \[data-nethint\][^{]*\{[^}]*animation:\s*none/);
    expect(block).toMatch(/transition:\s*none/);
    const ui = cssText('ui.css');
    expect(ui).toMatch(/@media \(prefers-reduced-motion: reduce\)[^}]*\.ml-nethints \[data-nethint\][^}]*transition:\s*none/);
  });
});
