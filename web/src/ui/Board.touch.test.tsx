// @vitest-environment jsdom
// Touch and pointer input on the board: tap and tap, drag and drop with snapping, cancel, jitter, no context menu,
// 44 px hit circles, vibration. The pointer events are dispatched by hand: jsdom has no layout, so the board box is fixed.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initialOwners } from '../game/board-state';
import { makeMove } from '../engines/engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { cellPoint, movableCells, targetsOf, VIEW_BOX } from './board-model';
import { Board } from './Board';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const owner = initialOwners();
const legal = tinyLegalMoves(owner, 0);
const movable = movableCells(legal);
const from = movable[0]!;
const targets = targetsOf(legal, from);
const target = targets[0]!;
/** A cell that is not movable (an own piece with no move, or an empty cell far from any target). */
const idle = Array.from(owner.keys()).find((c) => owner[c] === 0 && !movable.includes(c))!;

// The board box: 234 x 266 px, 0.5 px per unit, at the origin of the page.
const BOX = { left: 0, top: 0, width: 234, height: 266, right: 234, bottom: 266, x: 0, y: 0, toJSON: () => ({}) } as DOMRect;
const PX = 0.5;
const client = (cell: number, dx = 0, dy = 0): { x: number; y: number } => {
  const p = cellPoint(cell, false);
  return { x: (p.x - VIEW_BOX.x) * PX + dx, y: (p.y - VIEW_BOX.y) * PX + dy };
};

let host: HTMLElement;
let root: Root;
let moves: number[];
const svg = (): SVGSVGElement => host.querySelector<SVGSVGElement>('svg.ml-board__svg')!;
const cell = (id: number): HTMLElement => host.querySelector<HTMLElement>(`[data-cell="${id}"]`)!;

async function draw(interactive = true): Promise<void> {
  await act(async () => {
    root.render(
      <Board
        owner={owner}
        legal={legal}
        interactive={interactive}
        inert={!interactive}
        aSide={0}
        rotated={false}
        plies={0}
        last={null}
        halo={[]}
        sideLabel={() => 'own piece'}
        onMove={(m) => moves.push(m)}
      />,
    );
  });
}

async function pointer(type: string, at: { x: number; y: number }, extra: { pointerType?: string; id?: number } = {}): Promise<void> {
  await act(async () => {
    const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientX: at.x, clientY: at.y, button: 0 });
    Object.defineProperty(event, 'pointerId', { value: extra.id ?? 7 });
    Object.defineProperty(event, 'pointerType', { value: extra.pointerType ?? 'touch' });
    Object.defineProperty(event, 'isPrimary', { value: true });
    svg().dispatchEvent(event);
  });
}
const click = async (at: { x: number; y: number }): Promise<void> => {
  await act(async () => {
    svg().dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, clientX: at.x, clientY: at.y }));
  });
};
const tap = async (at: { x: number; y: number }): Promise<void> => {
  await pointer('pointerdown', at);
  await pointer('pointerup', at);
  await click(at);
};
const selectedCell = (): string | null => {
  const named = Array.from(host.querySelectorAll<HTMLElement>('[data-cell]')).find((e) => /selected/i.test(e.getAttribute('aria-label') ?? ''));
  return named?.getAttribute('data-cell') ?? null;
};

beforeEach(async () => {
  moves = [];
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue(BOX);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await draw();
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
  Reflect.deleteProperty(navigator, 'vibrate');
});

describe('tap and tap', () => {
  it('selects a piece and plays the target', async () => {
    await tap(client(from));
    expect(selectedCell()).toBe(String(from));
    await tap(client(target));
    expect(moves).toEqual([makeMove(from, target)]);
  });

  it('a tap 15 px beside a target still plays it (44 px hit area, nearest cell wins)', async () => {
    await tap(client(from));
    await tap(client(target, 15, 0));
    expect(moves).toEqual([makeMove(from, target)]);
  });
});

describe('drag and drop', () => {
  it('drags a piece onto a target: one move, and the click that follows does not undo it', async () => {
    await pointer('pointerdown', client(from));
    await pointer('pointermove', client(from, 0, 20));
    await pointer('pointermove', client(target));
    await pointer('pointerup', client(target));
    await click(client(target));
    expect(moves).toEqual([makeMove(from, target)]);
    expect(selectedCell()).toBeNull();
  });

  it('shows the dragged piece under the finger while dragging', async () => {
    await pointer('pointerdown', client(from));
    await pointer('pointermove', client(from, 30, 30));
    const piece = host.querySelector('.ml-pieces use.is-dragging');
    expect(piece).not.toBeNull();
    expect(piece!.getAttribute('transform')).toContain(`translate(${(cellPoint(from, false).x + 60).toFixed(1)} ${(cellPoint(from, false).y + 60).toFixed(1)})`);
  });

  it('selects the piece when the drag starts, so that its targets show', async () => {
    await pointer('pointerdown', client(from));
    expect(selectedCell()).toBeNull(); // a press alone changes nothing
    await pointer('pointermove', client(from, 0, 20));
    expect(selectedCell()).toBe(String(from));
    expect(host.querySelectorAll('.ml-target__dot')).toHaveLength(targets.length);
  });

  it('snaps a drop 15 px beside the target', async () => {
    await pointer('pointerdown', client(from));
    await pointer('pointermove', client(target, 12, 10));
    await pointer('pointerup', client(target, 12, 10));
    expect(moves).toEqual([makeMove(from, target)]);
  });

  it('cancels a drop far from every target: no move, piece stays selected, no dragged piece left', async () => {
    await pointer('pointerdown', client(from));
    await pointer('pointermove', client(from, 0, 150));
    await pointer('pointerup', client(from, 0, 150));
    await click(client(from, 0, 150));
    expect(moves).toEqual([]);
    expect(selectedCell()).toBe(String(from));
    expect(host.querySelector('.is-dragging')).toBeNull();
  });

  it('a cancelled pointer (the system took the touch) plays nothing', async () => {
    await pointer('pointerdown', client(from));
    await pointer('pointermove', client(target));
    await pointer('pointercancel', client(target));
    await pointer('pointerup', client(target));
    expect(moves).toEqual([]);
    expect(host.querySelector('.is-dragging')).toBeNull();
  });

  it('keeps the drag when the implicit capture of a touch moves from the cell to the board (lostpointercapture bubbles up)', async () => {
    await pointer('pointerdown', client(from));
    await pointer('pointermove', client(from, 0, 20));
    await act(async () => {
      cell(from).dispatchEvent(new MouseEvent('lostpointercapture', { bubbles: true }));
    });
    expect(host.querySelector('.is-dragging')).not.toBeNull();
    await pointer('pointermove', client(target));
    await pointer('pointerup', client(target));
    expect(moves).toEqual([makeMove(from, target)]);
  });

  it('a trembling finger (under 6 px) is a tap, not a drag', async () => {
    await pointer('pointerdown', client(from));
    await pointer('pointermove', client(from, 3, 3));
    await pointer('pointerup', client(from, 3, 3));
    await click(client(from, 3, 3));
    expect(host.querySelector('.is-dragging')).toBeNull();
    expect(selectedCell()).toBe(String(from)); // the click selected it
  });

  it('does not drag a piece that cannot move, or when the board is inert', async () => {
    await pointer('pointerdown', client(idle));
    await pointer('pointermove', client(idle, 0, 30));
    expect(selectedCell()).toBeNull();
    await pointer('pointerup', client(idle, 0, 30));
    await draw(false);
    await pointer('pointerdown', client(from));
    await pointer('pointermove', client(target));
    await pointer('pointerup', client(target));
    expect(moves).toEqual([]);
  });

  it('a press next to the selected piece\'s target starts no drag of a neighbour', async () => {
    await tap(client(from));
    await pointer('pointerdown', client(target)); // empty target cell
    await pointer('pointermove', client(target, 0, 30));
    expect(host.querySelector('.is-dragging')).toBeNull();
  });

  it('works with the mouse as well', async () => {
    await pointer('pointerdown', client(from), { pointerType: 'mouse' });
    await pointer('pointermove', client(target), { pointerType: 'mouse' });
    await pointer('pointerup', client(target), { pointerType: 'mouse' });
    expect(moves).toEqual([makeMove(from, target)]);
  });
});

describe('touch behaviour of the board', () => {
  it('suppresses the context menu of a long press', async () => {
    const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
    await act(async () => {
      svg().dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(true);
  });

  it('draws hit circles of at least 44 CSS px (22 px reach) on a small board', () => {
    const r = Number(cell(from).getAttribute('r'));
    expect(r * PX).toBeGreaterThanOrEqual(22);
  });

  it('vibrates on a selection and on a move, and survives a browser without vibrate', async () => {
    const vibrate = vi.fn(() => true);
    Object.defineProperty(navigator, 'vibrate', { value: vibrate, configurable: true });
    await tap(client(from));
    expect(vibrate).toHaveBeenCalledTimes(1);
    await tap(client(target));
    expect(vibrate).toHaveBeenCalledTimes(2);
    Object.defineProperty(navigator, 'vibrate', { value: () => { throw new Error('blocked'); }, configurable: true });
    await expect(tap(client(from))).resolves.toBeUndefined();
  });

  it('keeps keyboard play: Enter on a focused cell selects, Enter on a target plays', async () => {
    const press = async (el: HTMLElement): Promise<void> => {
      await act(async () => {
        el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
      });
    };
    await press(cell(from));
    expect(selectedCell()).toBe(String(from));
    await press(cell(target));
    expect(moves).toEqual([makeMove(from, target)]);
  });
});
