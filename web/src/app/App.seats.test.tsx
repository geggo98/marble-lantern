// @vitest-environment jsdom
// The play screen for three seats, through game links with the fake engine: whose turn in the colour of the seat, seat names, the
// seats card, the thinking indication that blocks input, undo in hot-seat, and the result card at the move limit (ranking, no winner,
// a close button on a small screen only).
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { seeded } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { applyMove, initialOwners } from '../game/board-state';
import type { Variant } from '../game/variant';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { FakeNavigation } from './fake-navigation';
import { encodeMoves } from './game-url';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } } };
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const THREE: Variant = { jump: 'adjacent', players: 3 };

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(selector: string): T | null => document.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string): T[] => [...document.querySelectorAll<T>(selector)];
const status = (): string => q('.ml-status__who span')?.textContent ?? '';
const statusMarble = (): string => q('.ml-status__who use')?.getAttribute('href') ?? '';
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

async function mount(hash: string, wide: boolean, delayMs = 0): Promise<FakeNavigation> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced') || (query.includes('max-width') ? !wide : query.includes('min-width') && wide),
    media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(delayMs);
  const nav = new FakeNavigation(hash);
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

function legalGame(count: number, seed = 4): number[] {
  const random = seeded(seed);
  const owner = initialOwners(THREE);
  const moves: number[] = [];
  for (let i = 0; i < count; i++) {
    const legal = tinyLegalMoves(owner, i % 3, THREE);
    const move = legal[Math.floor(random() * legal.length)]!;
    applyMove(owner, move);
    moves.push(move);
  }
  return moves;
}

describe('hot-seat on the play screen', () => {
  it('shows whose turn it is in the marble of that seat, names the seats, and lists them in a card', async () => {
    await mount('#g=v1.&l=2&p=3&s=hhe', true);
    await until(() => status() === 'Player 1 to move', 'seat 1 on turn');
    expect(statusMarble()).toBe('#ml-piece-a');
    const rows = all('.ml-seats__row').map((r) => r.textContent ?? '');
    expect(rows.map((r) => r.replace(/to move$/, ''))).toEqual(['Player 1', 'Player 2', 'Player 3 (engine)']);
    expect(q('.ml-seats__row[data-on-move]')!.textContent).toContain('Player 1');
    expect(q('.ml-seats')!.textContent).toContain('Pass the device on');
    await anyMove();
    await until(() => status() === 'Player 2 to move', 'seat 2 on turn');
    expect(statusMarble()).toBe('#ml-piece-b');
    expect(q('.ml-seats__row[data-on-move]')!.textContent).toContain('Player 2');
  });

  it('names the pieces by seat for a screen reader', async () => {
    await mount('#g=v1.&l=2&p=3&s=hhe', true);
    await until(() => status() === 'Player 1 to move', 'started');
    const labels = all('[data-cell]').map((c) => c.getAttribute('aria-label') ?? '');
    expect(labels.some((l) => l.includes('player 1 piece') && !l.includes('engine'))).toBe(true);
    expect(labels.some((l) => l.includes('player 2 piece'))).toBe(true);
    expect(labels.some((l) => l.includes('player 3 piece, engine'))).toBe(true);
  });

  it('Undo after the engine answered takes back the engine move and the move before it', async () => {
    await mount('#g=v1.&l=2&p=3&s=hhe', true);
    await until(() => status() === 'Player 1 to move', 'started');
    await anyMove();
    await until(() => status() === 'Player 2 to move', 'seat 2');
    await anyMove();
    await until(() => plies() === 3 && status() === 'Player 1 to move', 'the engine answered');
    await click(all('button').find((b) => b.textContent === 'Undo')!);
    await until(() => plies() === 1, 'one ply is left');
    expect(status()).toBe('Player 2 to move');
  });
});

describe('an engine seat on the play screen', () => {
  it('shows who is thinking and blocks the board meanwhile; the person is "Your move" again afterwards', async () => {
    await mount('#g=v1.&l=2&p=3&s=ehh', true, 150); // seat 1 is an engine and opens
    await until(() => status() === 'Player 1 (engine) is thinking', 'the engine of seat 1 thinks');
    expect(all('[data-cell]').every((c) => c.getAttribute('aria-disabled') === 'true')).toBe(true);
    expect(q('.ml-status')!.getAttribute('data-state')).toBe('thinking');
    await until(() => status() === 'Player 2 to move', 'the engine moved', 6000);
    expect(all('[data-cell][aria-disabled="false"]').length).toBeGreaterThan(0);
  });
});

describe('the result card at the move limit of a three-player game', () => {
  const hash = `#g=v1.${encodeMoves(legalGame(300))}&l=2&p=3&s=hhe`;

  it('ranks the three seats by distance to the goal and claims no winner', async () => {
    await mount(hash, true);
    await until(() => q('.ml-result') !== null, 'the result card');
    const card = q('.ml-result')!;
    expect(card.textContent).toContain('No winner at the move limit');
    expect(card.textContent).not.toMatch(/ wins/);
    const rows = all('.ml-score__row');
    expect(rows).toHaveLength(3);
    const values = rows.map((r) => Number(r.querySelector('.ml-score__value')!.textContent!.replace(/\D/g, '')));
    expect([...values].sort((a, b) => a - b)).toEqual(values); // closest first
    expect(rows.filter((r) => r.hasAttribute('data-ahead'))).toHaveLength(values[0] === values[1] ? 0 : 1);
    expect(card.textContent).toContain('300');
    expect(status()).toBe('Game over');
  });

  it('has no close button on a wide screen', async () => {
    await mount(hash, true);
    await until(() => q('.ml-result') !== null, 'the result card');
    expect(q('[data-result-close]')).toBeNull();
  });

  it('has a close button on a small screen; closing shows the board, the status still says "Game over"', async () => {
    await mount(hash, false);
    await until(() => q('.ml-result') !== null, 'the result card');
    const close = q('[data-result-close]')!;
    expect(close.getAttribute('aria-label')).toBe('Close the result');
    await click(close);
    expect(q('.ml-result')).toBeNull();
    expect(status()).toBe('Game over');
  });
});
