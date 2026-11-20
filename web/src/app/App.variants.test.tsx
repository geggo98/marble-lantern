// @vitest-environment jsdom
// The app with game variants through the start hook (`initialSetup`) and through game links: no dialog for three players, the
// address carries the variant, hot-seat has no swap button, the skill card is for two players only (move guides and the advantage indicator: App.guides3.test.tsx). The engine is the
// fake engine, the history is a fake history.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkerEngine } from '../engines/client';
import { GUIDE_NODES } from '../engines/analysis';
import { clearEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { seeded } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { applyMove, initialOwners } from '../game/board-state';
import { hotSeatVsEngine, humanVsEngine, humanVsTwoEngines } from '../game/setup';
import type { GameSetup } from '../game/setup';
import type { Variant } from '../game/variant';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { FakeNavigation } from './fake-navigation';
import { encodeMoves, parseHash } from './game-url';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } } };
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const THREE: Variant = { jump: 'adjacent', players: 3 };
const LONG: Variant = { jump: 'long', players: 2 };

let host: HTMLElement;
let root: Root;

const q = <T extends Element = HTMLElement>(selector: string): T | null => document.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string): T[] => [...document.querySelectorAll<T>(selector)];
const status = (): string => q('.ml-status__who span')?.textContent ?? '';
const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);
const dialog = (): HTMLElement | null => q('[role="dialog"]');
const buttonTexts = (): string[] => all('button').map((b) => b.textContent ?? '');

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

async function humanMove(): Promise<void> {
  for (const piece of all('[data-cell][aria-label*="your piece"]')) {
    await click(piece);
    const target = all('[data-cell][aria-label*="target"]')[0];
    if (target) {
      await click(target);
      return;
    }
  }
  throw new Error('no own piece has a legal move');
}

async function mount(nav: FakeNavigation, initialSetup?: GameSetup): Promise<void> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced') || query.includes('min-width'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(0);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} navigation={nav} {...(initialSetup ? { initialSetup } : {})} />);
  });
}

afterEach(async () => {
  localStorage.removeItem('ml.start-choice'); // the start dialog remembers its last choice
  await act(async () => root.unmount());
  host.remove();
});

function legalGame(plies: number, variant: Variant, seed = 4): number[] {
  const random = seeded(seed);
  const owner = initialOwners(variant);
  const moves: number[] = [];
  for (let i = 0; i < plies; i++) {
    const legal = tinyLegalMoves(owner, i % variant.players, variant);
    const move = legal[Math.floor(random() * legal.length)]!;
    applyMove(owner, move);
    moves.push(move);
  }
  return moves;
}

describe('three players through the start hook', () => {
  it('one human on seat 1: no dialog, the engine of seat 0 opens, the human moves, both engines answer, the address shows the game', async () => {
    const nav = new FakeNavigation('');
    await mount(nav, humanVsTwoEngines(1, 2));
    expect(dialog()).toBeNull();
    await until(() => plies() === 1 && status() === 'Your move', 'the engine of seat 0 opened');
    await humanMove();
    await until(() => plies() === 4 && status() === 'Your move', 'the engines of seats 2 and 0 answered'); // a pause of 350 ms lies between them
    const parsed = parseHash(nav.hash);
    expect(parsed.kind).toBe('game');
    if (parsed.kind === 'game') {
      expect(parsed.link.variant).toEqual(THREE);
      expect(parsed.link.seats).toEqual(['engine', 'human', 'engine']);
      expect(parsed.link.moves).toHaveLength(4);
    }
    expect(nav.hash).toContain('&p=3&s=ehe');
    expect(nav.entries.length).toBeGreaterThan(1); // the human's move is an entry of its own
  });

  it('the skill card is not offered for three players; the guide levels are', async () => {
    const nav = new FakeNavigation('');
    await mount(nav, humanVsTwoEngines(1, 2));
    await until(() => plies() === 1, 'the game runs');
    expect(q('[data-guides-levels]')).not.toBeNull();
    expect(q('[data-testid="skill-pause"]')).toBeNull();
    expect(q('[data-testid="skill-estimate"]')).toBeNull();
  });

  it('with the guides on, a three-player game sends analysis requests for the guides only (guide budget, three players); a long-hop game sends them with its variant', async () => {
    localStorage.setItem('ml.guides', 'on');
    const spy = vi.spyOn(WorkerEngine.prototype, 'analyse');
    try {
      await mount(new FakeNavigation(''), humanVsTwoEngines(1, 2));
      await until(() => plies() === 1 && status() === 'Your move', 'started');
      await new Promise((r) => setTimeout(r, 1200)); // longer than the quiet period of the skill tracker
      expect(spy.mock.calls.length).toBeGreaterThan(0); // the guides analyse the position of the human
      expect(spy.mock.calls.every((call) => call[2] === GUIDE_NODES && call[4]?.players === 3)).toBe(true); // none of the skill estimate (SKILL_NODES)
      await act(async () => root.unmount());
      host.remove();

      spy.mockClear();
      await mount(new FakeNavigation(''), humanVsEngine(1, 2, 'long'));
      await until(() => spy.mock.calls.length > 0, 'an analysis of the long-hop game');
      expect(spy.mock.calls.every((call) => call[4]?.jump === 'long' && call[4].players === 2)).toBe(true);
    } finally {
      spy.mockRestore();
      localStorage.removeItem('ml.guides');
    }
  });

  it('hot-seat: two humans take turns, the engine answers after the second, and there is no swap button', async () => {
    const nav = new FakeNavigation('');
    await mount(nav, hotSeatVsEngine(2));
    await until(() => plies() === 0 && status() === 'Player 1 to move', 'hot-seat game runs');
    expect(buttonTexts()).not.toContain('Swap sides');
    expect(buttonTexts()).toContain('New game');
  });

  it('with one human the swap button stays, and a swap rotates the human seat (address and board stay)', async () => {
    const nav = new FakeNavigation('');
    await mount(nav, humanVsTwoEngines(1, 2));
    await until(() => plies() === 1 && status() === 'Your move', 'started');
    expect(buttonTexts()).toContain('Swap sides');
    await click(all('button').find((b) => b.textContent === 'Swap sides')!);
    await until(() => nav.hash.includes('s=eeh'), 'the human moved to seat 2');
    await until(() => plies() === 2 && status() === 'Your move', 'the engine of seat 1 played; seat 2 is the human');
  });

  it('"New game" opens the start dialog, which starts the game that is chosen there', async () => {
    const nav = new FakeNavigation('');
    await mount(nav, humanVsTwoEngines(1, 2));
    await until(() => plies() === 1 && status() === 'Your move', 'started');
    await humanMove();
    await until(() => plies() === 4, 'one round');
    await click(all('button').find((b) => b.textContent === 'New game')!);
    expect(dialog()).not.toBeNull();
    await click(q('[data-choice="mode-engines"]')!);
    await click(q('[data-choice="human-seat-2"]')!);
    await click(q('[data-choice="begin-start"]')!);
    expect(dialog()).toBeNull();
    await until(() => plies() === 1 && status() === 'Your move', 'a new game: the human is on seat 2');
    expect(nav.hash).toContain('&p=3&s=ehe');
  });
});

describe('the rules panel of the start dialog starts the game', () => {
  it('a three-player game with "play for 2nd place" writes r=full into the address and is stored; a later two-player game does not carry it', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(q('[data-choice="mode-engines"]')!);
    await click(q('[data-choice="ranking-full"]')!);
    await click(q('[data-choice="jump-long"]')!);
    await click(q('[data-choice="begin-start"]')!);
    await until(() => dialog() === null && status() === 'Your move', 'the human may move');
    expect(nav.hash).toContain('&j=long');
    expect(nav.hash).toContain('&r=full');
    expect(JSON.parse(localStorage.getItem('ml.start-choice')!).ranking).toBe('full');
    await click(all('button').find((b) => b.textContent === 'New game')!);
    expect(q<HTMLInputElement>('[data-choice="ranking-full"]')!.checked).toBe(true); // remembered
    await click(q('[data-choice="mode-two"]')!);
    expect(q('[data-choice="ranking-full"]')).toBeNull();
    await click(q('[data-choice="begin-yes"]')!);
    await until(() => dialog() === null && status() === 'Your move', 'a two-player game');
    expect(nav.hash).not.toContain('r=full');
    expect(nav.hash).not.toContain('p=3');
  });
});

describe('game links with variants', () => {
  it('a three-player link continues at once, without a dialog, with the seats of the link', async () => {
    const moves = legalGame(3, THREE);
    const nav = new FakeNavigation(`#g=v1.${encodeMoves(moves)}&l=2&p=3&s=hhe`);
    await mount(nav);
    expect(dialog()).toBeNull();
    await until(() => plies() === 3 && status() === 'Player 1 to move', 'the linked position; seat 0 is a human');
    expect(buttonTexts()).not.toContain('Swap sides'); // hot-seat
    expect(nav.hash).toContain('&p=3&s=hhe');
  });

  it('a three-player link with illegal moves shows the broken-link dialog', async () => {
    const nav = new FakeNavigation(`#g=v1.${encodeMoves([1 | (2 << 8), 3 | (4 << 8)])}&p=3&s=hhe`);
    await mount(nav);
    await until(() => (dialog()?.textContent ?? '').includes('do not make a valid game'), 'broken link');
  });

  it('a long-hop link keeps its dialog, and a link with a long hop but without j=long is broken', async () => {
    // the first game that contains a long hop which the adjacent rule forbids
    const owner = initialOwners();
    const random = seeded(2);
    const moves: number[] = [];
    let hop: number | null = null;
    for (let ply = 0; ply < 120 && hop === null; ply++) {
      const long = tinyLegalMoves(owner, ply % 2, LONG);
      const adjacent = tinyLegalMoves(owner, ply % 2);
      const hops = long.filter((m) => !adjacent.includes(m));
      if (hops.length > 0) hop = hops[0]!;
      else {
        const m = adjacent[Math.floor(random() * adjacent.length)]!;
        applyMove(owner, m);
        moves.push(m);
      }
    }
    expect(hop, 'a long hop appears in a random game').not.toBeNull();
    const withHop = [...moves, hop!];

    const nav = new FakeNavigation(`#g=v1.${encodeMoves(withHop)}&h=0&l=2&j=long`);
    await mount(nav);
    await until(() => (dialog()?.textContent ?? '').includes('Continue this game'), 'the dialog for a long-hop link');
    await act(async () => root.unmount());
    host.remove();

    const bad = new FakeNavigation(`#g=v1.${encodeMoves(withHop)}&h=0&l=2`);
    await mount(bad);
    await until(() => (dialog()?.textContent ?? '').includes('do not make a valid game'), 'the adjacent rule refuses the long hop');
  });

  it('a long-hop game from the start hook writes j=long into the address and keeps skill and guides', async () => {
    const nav = new FakeNavigation('');
    await mount(nav, humanVsEngine(1, 2, 'long'));
    await until(() => plies() === 1 && status() === 'Your move', 'a long-hop game');
    expect(nav.hash).toContain('&j=long');
    expect(q('[data-testid="skill-pause"]')).not.toBeNull();
    expect(q('[data-guides-levels]')).not.toBeNull();
  });

  it('the standard game is unchanged: no variant fields in the address', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    expect(dialog()!.textContent).toContain('Do you want to begin?');
    expect(nav.hash).toBe('');
  });
});

describe('the board size (4 or 5 rows) through the dialog and links', () => {
  const FIVE: Variant = { jump: 'adjacent', players: 2, rows: 5 };
  const pieces = (): number => all('.ml-pieces use').length;

  it('a fresh visitor sees the 5-row board behind the dialog, with 5 rows preselected; "Yes" starts it and the address says t=5', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    expect(dialog()).not.toBeNull();
    expect(pieces()).toBe(30); // the board behind the dialog is the default choice
    expect(q<HTMLInputElement>('[data-choice="rows-5"]')!.checked).toBe(true);
    await click(q('[data-choice="begin-yes"]')!);
    await until(() => dialog() === null && status() === 'Your move', 'the human may move');
    expect(pieces()).toBe(30);
    expect(nav.hash).toContain('&t=5');
  });

  it('a stored choice without a size preselects 5 rows, behind the dialog and in the new game; an explicit 4 stays 4', async () => {
    localStorage.setItem('ml.start-choice', '{"jump":"adjacent","mode":"two"}');
    const nav = new FakeNavigation('');
    await mount(nav);
    expect(pieces()).toBe(30);
    expect(q<HTMLInputElement>('[data-choice="rows-5"]')!.checked).toBe(true);
    await click(q('[data-choice="begin-yes"]')!);
    await until(() => dialog() === null && status() === 'Your move', 'the human may move');
    expect(pieces()).toBe(30);
    expect(nav.hash).toContain('&t=5');
  });

  it('a stored choice with an explicit 4 stays a 4-row new game, without t= in the address', async () => {
    localStorage.setItem('ml.start-choice', '{"jump":"adjacent","mode":"two","rows":4}');
    const nav = new FakeNavigation('');
    await mount(nav);
    expect(pieces()).toBe(20);
    expect(q<HTMLInputElement>('[data-choice="rows-4"]')!.checked).toBe(true);
    await click(q('[data-choice="begin-yes"]')!);
    await until(() => dialog() === null && status() === 'Your move', 'the human may move');
    expect(pieces()).toBe(20);
    expect(nav.hash).not.toContain('t=');
  });

  it('the size chosen in the dialog is stored and starts the game', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(q('[data-choice="rows-4"]')!);
    await click(q('[data-choice="begin-yes"]')!);
    await until(() => dialog() === null && status() === 'Your move', 'the human may move');
    expect(pieces()).toBe(20);
    expect(JSON.parse(localStorage.getItem('ml.start-choice')!).rows).toBe(4);
  });

  it('a 5-row link names its size in the dialog and continues on 5 rows (also with the other side)', async () => {
    const moves = legalGame(3, FIVE);
    const nav = new FakeNavigation(`#g=v1.${encodeMoves(moves)}&h=0&l=2&t=5`);
    await mount(nav);
    await until(() => (dialog()?.textContent ?? '').includes('Continue this game'), 'the link dialog');
    expect(q('[data-testid="link-rows"]')).not.toBeNull();
    expect(dialog()!.textContent).toContain('of 250'); // the move limit of 5 rows
    await click(q('[data-choice="link-side-to-move"]')!);
    await until(() => dialog() === null && plies() >= 3, 'the linked game runs');
    expect(pieces()).toBe(30);
    expect(nav.hash).toContain('&t=5');
  });

  it('a 4-row link names its size too, because the dialog now preselects 5 rows for a new game', async () => {
    const moves = legalGame(3, { jump: 'adjacent', players: 2 });
    const nav = new FakeNavigation(`#g=v1.${encodeMoves(moves)}&h=0&l=2`);
    await mount(nav);
    await until(() => (dialog()?.textContent ?? '').includes('Continue this game'), 'the link dialog');
    expect(q('[data-testid="link-rows"]')?.textContent).toBe('Board size: 4 rows');
    expect(dialog()!.textContent).toContain('of 200');
  });

  it('an old 4-row link without t= shows the 4-row board behind the dialog for a fresh visitor (default 5 rows), and continues as the 4-row game', async () => {
    const moves = legalGame(3, { jump: 'adjacent', players: 2 });
    const nav = new FakeNavigation(`#g=v1.${encodeMoves(moves)}&h=0&l=2`);
    await mount(nav); // no stored choice: the default choice is 5 rows
    await until(() => (dialog()?.textContent ?? '').includes('Continue this game'), 'the link dialog');
    expect(pieces()).toBe(20); // 4 rows: 10 pieces each, not the 30 of the 5-row default
    await click(q('[data-choice="link-side-to-move"]')!);
    await until(() => dialog() === null && plies() >= 3, 'the linked game runs');
    expect(pieces()).toBe(20);
    expect(nav.hash).not.toContain('t=');
  });
});
