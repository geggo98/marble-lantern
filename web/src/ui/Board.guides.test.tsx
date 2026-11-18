// @vitest-environment jsdom
// The board with move guides: bands on pieces and targets, accessible names, marks per band, and no change without guides.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { initialOwners } from '../game/board-state';
import { makeMove, moveFrom, moveTo } from '../engines/engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { BAND_ORDER } from '../guides/bands';
import type { Band } from '../guides/bands';
import { MARKS } from '../guides/marks';
import { movableCells, targetsOf } from './board-model';
import { Board } from './Board';
import type { BoardGuides } from './Board';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const owner = initialOwners();
const legal = tinyLegalMoves(owner, 0);
const movable = movableCells(legal);
const first = movable[0]!;
const firstTargets = targetsOf(legal, first);

/** Gives the movable pieces and the targets of the first piece the five bands in turn. */
function guidesFor(): BoardGuides {
  const pieces = new Map<number, Band>();
  movable.forEach((c, i) => pieces.set(c, BAND_ORDER[i % 5]!));
  const moves = new Map<number, Band>();
  legal.forEach((m, i) => moves.set(m, BAND_ORDER[(i + 2) % 5]!));
  return { pieces, moves };
}

let host: HTMLElement;
let root: Root;
const all = (selector: string): HTMLElement[] => [...host.querySelectorAll<HTMLElement>(selector)];
const cell = (id: number): HTMLElement => host.querySelector<HTMLElement>(`[data-cell="${id}"]`)!;

async function draw(props: { guides?: BoardGuides | null; interactive?: boolean }): Promise<void> {
  await act(async () => {
    root.render(
      <Board
        owner={owner}
        legal={legal}
        interactive={props.interactive ?? true}
        inert={false}
        aSide={0}
        rotated={false}
        plies={0}
        last={null}
        halo={[]}
        sideLabel={() => 'your piece'}
        onMove={() => undefined}
        {...(props.guides !== undefined ? { guides: props.guides } : {})}
      />,
    );
  });
}

const click = async (element: Element): Promise<void> => {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
};

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

describe('Board without guides', () => {
  it('draws no guide mark and keeps the plain target dot and labels', async () => {
    await draw({});
    await click(cell(first));
    expect(all('[data-band]')).toHaveLength(0);
    expect(all('.ml-target__dot')).toHaveLength(firstTargets.length);
    expect(cell(firstTargets[0]!).getAttribute('aria-label')).toMatch(/target$/);
    expect(cell(first).getAttribute('aria-label')).not.toMatch(/best move/);
  });
});

describe('Board with guides', () => {
  it('marks every movable own piece with its band, and nothing else on the board before a selection', async () => {
    const guides = guidesFor();
    await draw({ guides });
    const marks = all('[data-guide="piece"]');
    expect(marks).toHaveLength(movable.length);
    for (const mark of marks) {
      const c = Number(mark.getAttribute('data-cell-of'));
      expect(mark.getAttribute('data-band')).toBe(guides.pieces.get(c));
    }
    expect(all('[data-guide="target"]')).toHaveLength(0);
  });

  it('names the band of a piece in its accessible name', async () => {
    const guides = guidesFor();
    await draw({ guides });
    expect(cell(first).getAttribute('aria-label')).toContain('best move: top'); // movable[0] has band much-better
  });

  it('marks each target of the selected piece with the band of that move, by name and by mark', async () => {
    const guides = guidesFor();
    await draw({ guides });
    await click(cell(first));
    const marks = all('[data-guide="target"]');
    expect(marks).toHaveLength(firstTargets.length);
    expect(all('.ml-target__dot')).toHaveLength(0); // the band mark takes the place of the dot, the ring stays
    expect(all('.ml-target__ring')).toHaveLength(firstTargets.length);
    for (const target of firstTargets) {
      const band = guides.moves.get(makeMove(first, target))!;
      const mark = marks.find((m) => m.getAttribute('data-band') === band)!;
      expect(mark.querySelector('path')?.getAttribute('d')).toBe(MARKS[band].d);
      const text = { 'much-better': 'much better', better: 'better', equal: 'about equal', worse: 'worse', 'much-worse': 'much worse' }[band];
      expect(cell(target).getAttribute('aria-label')).toMatch(new RegExp(`target, ${text}(,|$)`));
    }
  });

  it('keeps the selection ring and the legal-move rings next to the guide marks', async () => {
    await draw({ guides: guidesFor() });
    await click(cell(first));
    expect(all('.ml-select')).toHaveLength(1);
    expect(all('.ml-target__ring')).toHaveLength(firstTargets.length);
  });

  it('draws the five bands with five different glyphs and ring styles', async () => {
    const pieces = new Map<number, Band>();
    movable.slice(0, 5).forEach((c, i) => pieces.set(c, BAND_ORDER[i]!));
    expect(pieces.size).toBe(5);
    await draw({ guides: { pieces, moves: new Map() } });
    const marks = all('[data-guide="piece"]');
    const glyphs = new Set(marks.map((m) => m.querySelector('.ml-guide__glyph')?.getAttribute('d')));
    const rings = new Set(marks.map((m) => `${m.querySelector('.ml-guide__ring')?.getAttribute('stroke-width')}/${m.querySelector('.ml-guide__ring')?.getAttribute('stroke-dasharray')}`));
    expect(glyphs.size).toBe(5);
    expect(rings.size).toBe(5);
  });

  it('works from the keyboard: Enter on a piece shows the same marked targets', async () => {
    await draw({ guides: guidesFor() });
    await act(async () => {
      cell(first).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    });
    expect(all('[data-guide="target"]')).toHaveLength(firstTargets.length);
  });

  it('draws a target without a band as a plain dot (a move the analysis did not list)', async () => {
    await draw({ guides: { pieces: new Map(), moves: new Map() } });
    await click(cell(first));
    expect(all('.ml-target__dot')).toHaveLength(firstTargets.length);
    expect(all('[data-guide="target"]')).toHaveLength(0);
  });

  it('shows no guides while the board is inert', async () => {
    await draw({ guides: guidesFor(), interactive: false });
    expect(all('[data-band]')).toHaveLength(0);
  });

  it('moves are keyed from | to << 8', () => {
    const m = legal[0]!;
    expect(makeMove(moveFrom(m), moveTo(m))).toBe(m);
  });
});
