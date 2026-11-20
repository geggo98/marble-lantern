// @vitest-environment jsdom
// The board with 15-cell houses: the whole goal house is marked in the marble of its seat. The board marks goal houses only, and goal
// houses never overlap (in three players the shared vertex cells lie in one goal house and in a neighbour's START house), so every
// goal cell has exactly one mark and no neutral colour is needed.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { goalCells, initialOwners } from '../game/board-state';
import type { Variant } from '../game/variant';
import { Board } from './Board';
import type { SideStyle } from './primitives';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const STYLES: SideStyle[] = ['a', 'b', 'c'];
let host: HTMLElement;
let root: Root;
const all = (selector: string): HTMLElement[] => [...host.querySelectorAll<HTMLElement>(selector)];

async function draw(variant: Variant): Promise<void> {
  const owner = initialOwners(variant);
  await act(async () => {
    root.render(
      <Board
        owner={owner} legal={[]} interactive={false} inert aSide={0} rotated={false} plies={0} last={null} halo={[]}
        sideLabel={(s) => `player ${s + 1} piece`} seatStyle={(s) => STYLES[s]!}
        goals={goalCells(variant).map((cells, seat) => ({ cells, style: STYLES[seat]! }))}
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

describe('15-cell houses on the board', () => {
  it('marks 15 goal cells per seat and draws 15 marbles per seat (two players)', async () => {
    await draw({ jump: 'adjacent', players: 2, rows: 5 });
    expect(all('.ml-socket--goal-a')).toHaveLength(15);
    expect(all('.ml-socket--goal-b')).toHaveLength(15);
    expect(all('.ml-pieces use[href="#ml-piece-a"]')).toHaveLength(15);
    expect(all('.ml-pieces use[href="#ml-piece-b"]')).toHaveLength(15);
  });

  it('marks 15 goal cells per seat in three players, the shared vertex cells included, each with one mark only', async () => {
    await draw({ jump: 'adjacent', players: 3, rows: 5 });
    for (const style of STYLES) expect(all(`.ml-socket--goal-${style}`)).toHaveLength(15);
    expect(all('.ml-socket--goal-a, .ml-socket--goal-b, .ml-socket--goal-c')).toHaveLength(45);
    const marked = new Set(goalCells({ jump: 'adjacent', players: 3, rows: 5 }).flat());
    expect(marked.size).toBe(45);
  });

  it('keeps 10 marked goal cells per seat on 4 rows', async () => {
    await draw({ jump: 'adjacent', players: 3 });
    for (const style of STYLES) expect(all(`.ml-socket--goal-${style}`)).toHaveLength(10);
  });
});
