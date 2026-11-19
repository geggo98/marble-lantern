// @vitest-environment jsdom
// "Play on for 2nd place" on the play screen, through game links with the fake engine: the button after a three-player win only, the
// game that goes on with the seat of the winner out, the rank badges, the address (r=full, a new history entry), Back, and a reload.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PLAY_ON_WIN } from '../../e2e/fixtures';
import { clearEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { seeded } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { applyMove, initialOwners } from '../game/board-state';
import type { Variant } from '../game/variant';
import { ResultCard } from '../ui/Panels';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { FakeNavigation } from './fake-navigation';
import { encodeMoves } from './game-url';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } } };
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const THREE: Variant = { jump: 'adjacent', players: 3 };
const WIN = `#g=v1.${PLAY_ON_WIN.moves}&l=0&p=3&s=hee`; // the human is seat 0; seat 2, an engine, has won
const WIN_FULL = `${WIN}&r=full`;

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(selector: string): T | null => document.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string): T[] => [...document.querySelectorAll<T>(selector)];
const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);

async function until(condition: () => boolean, what: string, ms = 4000): Promise<void> {
  const end = Date.now() + ms;
  while (!condition()) {
    if (Date.now() > end) throw new Error(`timeout waiting for: ${what}`);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 5));
    });
  }
}
async function click(element: Element): Promise<void> {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
}
/** Plays the first piece of the seat on turn that has a target. */
async function anyMove(): Promise<void> {
  for (const piece of all('[data-cell][aria-disabled="false"]').filter((c) => /piece/.test(c.getAttribute('aria-label') ?? ''))) {
    await click(piece);
    const target = all('[data-cell][aria-label*="target"]')[0];
    if (target) {
      await click(target);
      return;
    }
  }
  throw new Error('no piece has a legal move');
}
async function mount(hash: string, wide: boolean, nav = new FakeNavigation(hash)): Promise<FakeNavigation> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced') || (query.includes('max-width') ? !wide : query.includes('min-width') && wide),
    media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(0);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} navigation={nav} />);
  });
  return nav;
}
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

const playOnButton = (): HTMLButtonElement | null => q<HTMLButtonElement>('[data-play-on]');

describe('the Play on button', () => {
  it('is on the result card after a win in a three-player game, whether an engine or a person won', async () => {
    await mount(WIN, true);
    await until(() => q('.ml-result') !== null, 'the result card');
    expect(plies()).toBe(PLAY_ON_WIN.plies);
    expect(playOnButton()).not.toBeNull();
    await act(async () => root.unmount());
    host.remove();
    await mount(`#g=v1.${PLAY_ON_WIN.moves}&l=0&p=3&s=eeh`, true); // seat 2 is the person and has won
    await until(() => q('.ml-result') !== null, 'the result card');
    expect(playOnButton()).not.toBeNull();
  });

  it('is not on the card at the move limit', async () => {
    const random = seeded(4);
    const owner = initialOwners(THREE);
    const moves: number[] = [];
    for (let i = 0; i < 300; i++) {
      const legal = tinyLegalMoves(owner, i % 3, THREE);
      const move = legal[Math.floor(random() * legal.length)]!;
      applyMove(owner, move);
      moves.push(move);
    }
    await mount(`#g=v1.${encodeMoves(moves)}&l=2&p=3&s=hhe`, true);
    await until(() => q('.ml-result') !== null, 'the result card');
    expect(playOnButton()).toBeNull();
  });

  it('is a text button of its own, not an icon only', async () => {
    await mount(WIN, false);
    await until(() => q('.ml-result') !== null, 'the result card');
    const button = playOnButton()!;
    expect(button.tagName).toBe('BUTTON');
    expect(button.getAttribute('type')).toBe('button');
    expect((button.textContent ?? '').length).toBeGreaterThan(0); // a text label, not colour or icon only
  });
});

describe('after Play on', () => {
  it('closes the card, keeps the moves, skips the seat of the winner and gives the turn to the person; the address gets a new entry with r=full', async () => {
    const nav = await mount(WIN, true);
    await until(() => playOnButton() !== null, 'the button');
    const entries = nav.entries.length;
    await click(playOnButton()!);
    await until(() => q('.ml-result') === null, 'the card is gone');
    expect(plies()).toBe(PLAY_ON_WIN.plies);
    // seat 2 is out and shows its rank; the person on seat 0 is on move, seat 1 is not skipped
    const rows = all('.ml-seats__row');
    expect(rows).toHaveLength(3);
    expect(rows[2]!.hasAttribute('data-rank-text')).toBe(true);
    expect(rows[0]!.hasAttribute('data-rank-text')).toBe(false);
    expect(rows[0]!.hasAttribute('data-on-move')).toBe(true);
    // the move list marks the move that finished seat 2
    expect(all('.ml-moves__move .ml-tag')).toHaveLength(1);
    // the address
    expect(nav.entries.length).toBe(entries + 1);
    expect(nav.hash).toBe(WIN_FULL);
    // the person moves; the engine of seat 1 answers; the move after that is the person again (seat 2 is never on turn)
    const before = plies();
    await anyMove();
    await until(() => plies() === before + 2, 'the person moved and the engine of seat 1 answered');
    expect(q('.ml-seats__row[data-on-move]')).toBe(all('.ml-seats__row')[0]);
  });

  it('Back leads to the won position with the button; a reload of the r=full address shows the game that goes on', async () => {
    const nav = await mount(WIN, true);
    await until(() => playOnButton() !== null, 'the button');
    await click(playOnButton()!);
    await until(() => nav.hash === WIN_FULL && q('.ml-result') === null, 'playing on');
    await act(async () => nav.back());
    await until(() => playOnButton() !== null, 'the result card is back with its button');
    expect(nav.hash).toBe(WIN);
    // a reload: a new page opened with the address of the game that goes on
    await act(async () => root.unmount());
    host.remove();
    await mount(WIN_FULL, true);
    await until(() => plies() === PLAY_ON_WIN.plies && all('.ml-seats__row').length === 3, 'the game of the link');
    expect(q('.ml-result')).toBeNull();
    expect(all('.ml-seats__row')[2]!.hasAttribute('data-rank-text')).toBe(true);
    expect(all('.ml-seats__row')[0]!.hasAttribute('data-on-move')).toBe(true);
  });
});

describe('the result card', () => {
  it('lists the ranking, first place first, and offers no Play on button', () => {
    const body = document.createElement('div');
    document.body.appendChild(body);
    const r = createRoot(body);
    act(() => {
      r.render(
        <ResultCard
          winner="c"
          headline="x wins"
          plies={142}
          ranking={[
            { style: 'c', label: 'Pearl', rank: '1.' },
            { style: 'b', label: 'Blue', rank: '2.' },
            { style: 'a', label: 'Amber', rank: '3.' },
          ]}
        />,
      );
    });
    const rows = [...body.querySelectorAll('.ml-ranking li')];
    expect(rows.map((x) => x.getAttribute('data-rank'))).toEqual(['1', '2', '3']);
    expect(rows[0]!.textContent).toContain('Pearl');
    expect(rows[2]!.textContent).toContain('Amber');
    expect(body.querySelector('[data-play-on]')).toBeNull();
    act(() => r.unmount());
    body.remove();
  });
});
