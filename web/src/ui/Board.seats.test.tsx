// @vitest-environment jsdom
// The board with three seats: one marble per seat (colour and a mark), the goal corners in the marble of their seat, labels by seat,
// and one tab stop that always lands on a piece of the seat to move.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { goalCells, initialOwners } from '../game/board-state';
import type { Variant } from '../game/variant';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { movableCells } from './board-model';
import { Board } from './Board';
import type { SideStyle } from './primitives';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const THREE: Variant = { jump: 'adjacent', players: 3 };
const owner = initialOwners(THREE);
const STYLES: SideStyle[] = ['a', 'b', 'c'];
const goals = goalCells(THREE).map((cells, seat) => ({ cells, style: STYLES[seat]! }));

let host: HTMLElement;
let root: Root;
const all = (selector: string): HTMLElement[] => [...host.querySelectorAll<HTMLElement>(selector)];
const cell = (id: number): HTMLElement => host.querySelector<HTMLElement>(`[data-cell="${id}"]`)!;

async function draw(seat: number, interactive = true): Promise<void> {
  await act(async () => {
    root.render(
      <Board
        owner={owner}
        legal={tinyLegalMoves(owner, seat, THREE)}
        interactive={interactive}
        inert={!interactive}
        aSide={1}
        rotated={false}
        plies={seat}
        last={null}
        halo={[]}
        sideLabel={(s) => `player ${s + 1} piece`}
        seatStyle={(s) => STYLES[s]!}
        goals={goals}
        onMove={() => undefined}
      />,
    );
  });
}

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

describe('three seats on the board', () => {
  it('draws ten pieces of each marble, one marble per seat', async () => {
    await draw(0);
    for (const style of STYLES) expect(all(`.ml-pieces use[href="#ml-piece-${style}"]`)).toHaveLength(10);
  });

  it('marks the goal corners in the marble of their seat, with a cue besides the colour (plain, ring, diamond)', async () => {
    await draw(0);
    expect(all('.ml-socket--goal-a')).toHaveLength(10);
    expect(all('.ml-socket--goal-b')).toHaveLength(10);
    expect(all('.ml-socket--goal-c')).toHaveLength(10);
    expect(all('.ml-socket__mark-b')).toHaveLength(10);
    expect(all('.ml-socket__mark-c')).toHaveLength(10);
  });

  it('names a piece by its seat in the accessible name', async () => {
    await draw(0);
    const start2 = owner.findIndex((o) => o === 2);
    expect(cell(start2).getAttribute('aria-label')).toContain('player 3 piece');
  });

  it('puts the one tab stop on a movable piece of the seat to move, and moves it with the turn', async () => {
    await draw(0);
    const stops = (): HTMLElement[] => all('[data-cell][tabindex="0"]');
    expect(stops()).toHaveLength(1);
    expect(movableCells(tinyLegalMoves(owner, 0, THREE))).toContain(Number(stops()[0]!.dataset.cell));
    // the person used a piece of seat 0 last; seat 1 is on turn now: the stop must not stay on that cell
    const used = Number(stops()[0]!.dataset.cell);
    await act(async () => {
      cell(used).dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await draw(1);
    expect(stops()).toHaveLength(1);
    const now = Number(stops()[0]!.dataset.cell);
    expect(now).not.toBe(used);
    expect(movableCells(tinyLegalMoves(owner, 1, THREE))).toContain(now);
    expect(stops()[0]!.getAttribute('aria-disabled')).toBe('false');
  });
});
