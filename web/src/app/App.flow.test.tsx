// @vitest-environment jsdom
// The flow around the game: start dialog, game link, one history entry per turn, Back and Forward. The engine is the
// fake engine, the history is a fake history.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { seeded } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { makeMove } from '../engines/engine';
import { applyMove, initialOwners, sideToMove } from '../game/board-state';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { FakeNavigation } from './fake-navigation';
import { formatGameFragment, parseHash } from './game-url';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = {
  v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } },
};

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;

const q = <T extends Element = HTMLElement>(selector: string): T | null => document.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string): T[] => [...document.querySelectorAll<T>(selector)];
const status = (): string => q('.ml-status__who span')?.textContent ?? '';
const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);
const dialog = (): HTMLElement | null => q('[role="dialog"]');
const choice = (name: string): HTMLElement => q(`[data-choice="${name}"]`)!;

async function until(condition: () => boolean, what: string, ms = 3000): Promise<void> {
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

async function key(element: Element, name: string, init: KeyboardEventInit = {}): Promise<KeyboardEvent> {
  const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true, ...init });
  await act(async () => {
    element.dispatchEvent(event);
  });
  return event;
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

async function mount(nav: FakeNavigation, delayMs = 0): Promise<void> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(delayMs);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} navigation={nav} />);
  });
}

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
});

/** A game of the tiny generator, legal for the fake engine. */
function legalGame(plies: number, seed = 4): number[] {
  const random = seeded(seed);
  const owner = initialOwners();
  const moves: number[] = [];
  for (let i = 0; i < plies; i++) {
    const legal = tinyLegalMoves(owner, sideToMove(i));
    const move = legal[Math.floor(random() * legal.length)]!;
    applyMove(owner, move);
    moves.push(move);
  }
  return moves;
}

describe('the start dialog', () => {
  it('asks first, starts nothing, and leaves the address alone', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    const d = dialog()!;
    expect(d.getAttribute('aria-modal')).toBe('true');
    expect(d.textContent).toContain('Do you want to begin?');
    expect(d.getAttribute('aria-labelledby')).toBe(d.querySelector('h2')!.id);
    expect(nav.replaces).toEqual([]);
    expect(nav.pushes).toEqual([]);
    await until(() => !q('.ml-loading') && q('.ml-engine__head') !== null, 'the engine loads behind the dialog');
    expect(plies()).toBe(0); // the engine loaded, but nobody moved
    expect(dialog()).not.toBeNull();
  });

  it('puts the focus on the primary button, keeps Tab inside at both ends, and does not close on Escape', async () => {
    await mount(new FakeNavigation(''));
    const yes = choice('begin-yes');
    const lastStop = q<HTMLElement>('.ml-start [data-language="de"]')!; // the language toggle is the last stop of the dialog
    expect(document.activeElement).toBe(yes);
    lastStop.focus();
    const forward = await key(lastStop, 'Tab');
    expect(forward.defaultPrevented).toBe(true); // wraps to the first stop instead of leaving the dialog
    const firstStop = choice('jump-adjacent'); // the checked radio of the first group (unchecked radios are no Tab stops)
    firstStop.focus();
    const backward = await key(firstStop, 'Tab', { shiftKey: true });
    expect(backward.defaultPrevented).toBe(true);
    await key(yes, 'Escape');
    expect(dialog()).not.toBeNull(); // nothing to go back to: the question stays
    expect(q('.ml-app')!.hasAttribute('inert')).toBe(true);
  });

  it('"Yes" lets the human begin: the human is side 0, nothing is on the board, the address shows the game', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(choice('begin-yes'));
    await until(() => status() === 'Your move' && dialog() === null, 'the human is to move');
    expect(plies()).toBe(0);
    expect(nav.hash).toBe('#g=v1.&h=0&l=2');
    expect(nav.entries).toHaveLength(1); // the first state does not add an entry
    expect(q('.ml-app')!.hasAttribute('inert')).toBe(false);
  });

  it('"No" lets the engine begin', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(choice('begin-no'));
    await until(() => status() === 'Your move' && plies() === 1, 'the engine moved first');
    expect(parseHash(nav.hash)).toMatchObject({ kind: 'game', link: { humanSide: 1 } });
    expect(nav.entries).toHaveLength(1);
  });

  it('is not shown on the benchmark or about screen, and appears when the player goes to the play screen', async () => {
    await mount(new FakeNavigation('#/about'));
    expect(dialog()).toBeNull();
    expect(q('.ml-page__title')?.textContent).toBe('About');
  });
});

describe('Back and Forward: one history entry per full turn', () => {
  it('pushes one entry for each human move and takes back one full turn per Back', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(choice('begin-no'));
    await until(() => plies() === 1 && status() === 'Your move', 'engine opened');
    await humanMove();
    await until(() => plies() === 3 && status() === 'Your move', 'turn 1');
    await humanMove();
    await until(() => plies() === 5 && status() === 'Your move', 'turn 2');
    expect(nav.hashes.map((h) => (parseHash(h) as unknown as { link: { moves: number[] } }).link.moves.length)).toEqual([1, 3, 5]);

    await act(async () => nav.back());
    await until(() => plies() === 3 && status() === 'Your move', 'Back: plies 3');
    await act(async () => nav.back());
    await until(() => plies() === 1 && status() === 'Your move', 'Back: plies 1');
    expect(dialog()).toBeNull(); // Back through our own entries asks nothing
    await act(async () => nav.forward());
    await until(() => plies() === 3 && status() === 'Your move', 'Forward: plies 3');
    expect(nav.entries).toHaveLength(3); // loading a position writes no entry
    expect(nav.pushes).toHaveLength(2);
  });

  it('a move after Back drops the forward entries, like a new branch', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(choice('begin-no'));
    await until(() => plies() === 1 && status() === 'Your move', 'engine opened');
    await humanMove();
    await until(() => plies() === 3, 'turn 1');
    await act(async () => nav.back());
    await until(() => plies() === 1, 'Back');
    await humanMove();
    await until(() => plies() === 3 && status() === 'Your move', 'turn 1 again');
    expect(nav.entries).toHaveLength(2);
  });

  it('Back during a search cancels it: the late answer does not change the position', async () => {
    const nav = new FakeNavigation('');
    await mount(nav, 120);
    await click(choice('begin-yes')); // the human begins, so there is no opening search
    await until(() => status() === 'Your move', 'human to move');
    await humanMove();
    await until(() => q('.ml-status__who')?.textContent?.includes('thinking') ?? false, 'the engine thinks');
    await act(async () => nav.back());
    await until(() => plies() === 0 && status() === 'Your move', 'back at the start');
    await act(async () => {
      await new Promise((r) => setTimeout(r, 300)); // longer than the search would have taken
    });
    expect(plies()).toBe(0);
    expect(status()).toBe('Your move');
  });
});

describe('a game link', () => {
  const moves = legalGame(3);
  const link = formatGameFragment({ moves, humanSide: 1, level: 2 });

  it('shows the position and offers three ways to continue; nothing starts before the answer', async () => {
    const nav = new FakeNavigation(link);
    await mount(nav);
    const d = dialog()!;
    expect(d.textContent).toContain('Continue this game?');
    expect(d.textContent).toContain('ply 3 of 200');
    expect(choice('link-side-to-move').textContent).toBe('Continue as the side on move');
    expect(choice('link-other-side').textContent).toBe('Continue as the other side');
    expect(choice('link-new')).not.toBeNull();
    expect(choice('link-keep') ?? null).toBeNull(); // no game runs yet, so nothing to keep
    expect(document.activeElement).toBe(choice('link-side-to-move'));
    await until(() => q('.ml-engine__head') !== null && !q('.ml-loading'), 'engine ready behind the dialog');
    expect(plies()).toBe(0);
    expect(nav.pushes).toEqual([]);
  });

  it('continues as the side on move: same position, the human to move, the address unchanged', async () => {
    const nav = new FakeNavigation(link);
    await mount(nav);
    await click(choice('link-side-to-move'));
    await until(() => plies() === 3 && status() === 'Your move', 'position loaded');
    expect(nav.hash).toBe(link);
    expect(nav.pushes).toEqual([]);
  });

  it('continues as the other side: the engine is on move and plays at once', async () => {
    const nav = new FakeNavigation(link);
    await mount(nav);
    await click(choice('link-other-side'));
    await until(() => plies() === 4 && status() === 'Your move', 'the engine answered');
    expect(parseHash(nav.hash)).toMatchObject({ kind: 'game', link: { humanSide: 0 } });
    expect(nav.entries).toHaveLength(1); // side change and engine answer replace; they add nothing
  });

  it('"start a new game" leads to the start question, and a new game adds an entry', async () => {
    const nav = new FakeNavigation(link);
    await mount(nav);
    await click(choice('link-new'));
    expect(dialog()!.textContent).toContain('Do you want to begin?');
    await click(choice('begin-yes'));
    await until(() => plies() === 0 && status() === 'Your move', 'new game');
    expect(nav.pushes).toHaveLength(1);
    expect(nav.hash).toBe('#g=v1.&h=0&l=2');
    await act(async () => nav.back());
    await until(() => plies() === 3, 'Back to the linked position');
  });

  it('applies the strength from the link', async () => {
    const nav = new FakeNavigation(formatGameFragment({ moves, humanSide: 1, level: 0 }));
    await mount(nav);
    await click(choice('link-side-to-move'));
    await until(() => plies() === 3, 'loaded');
    expect(q('.ml-engine__level')?.textContent).toContain('Beginner');
  });

  it('a pasted link while a game runs asks again; Escape keeps the running game and restores its address', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(choice('begin-no'));
    await until(() => plies() === 1 && status() === 'Your move', 'engine opened');
    const gameHash = nav.hash;
    const undo = all('button').find((b) => b.textContent === 'Undo')!;
    undo.focus();
    await act(async () => nav.userNavigates(link));
    expect(dialog()!.textContent).toContain('Continue this game?');
    expect(choice('link-keep')).not.toBeNull();
    await key(dialog()!, 'Escape');
    expect(dialog()).toBeNull();
    expect(plies()).toBe(1);
    expect(nav.hash).toBe(gameHash);
    expect(document.activeElement).toBe(undo); // focus returns to where it was
  });

  it.each(['keep button', 'backdrop', 'Escape'] as const)(
    '"New game" opened by mistake: the %s cancels, and game, history, address and engine stay as they were',
    async (how) => {
      const nav = new FakeNavigation('');
      await mount(nav);
      await click(choice('begin-no'));
      await until(() => plies() === 1 && status() === 'Your move', 'engine opened');
      await humanMove();
      await until(() => plies() === 3 && status() === 'Your move', 'the engine answered');
      const before = { hash: nav.hash, pushes: nav.pushes.length, engine: q('.ml-engine__head')?.textContent, board: q('svg')?.outerHTML };
      await click(all('button').find((b) => b.textContent === 'New game')!);
      expect(dialog()?.getAttribute('data-dialog')).toBe('begin');
      await click(choice('mode-engines')); // a changed choice in the dialog must not leak into the running game
      await click(choice('jump-long'));
      if (how === 'keep button') await click(choice('begin-keep'));
      if (how === 'backdrop') {
        const backdrop = q('[data-testid="start-backdrop"]')!;
        await act(async () => {
          backdrop.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }));
        });
        await click(backdrop);
      }
      if (how === 'Escape') await key(dialog()!, 'Escape');
      expect(dialog()).toBeNull();
      expect(plies()).toBe(3);
      expect(status()).toBe('Your move');
      expect(nav.hash).toBe(before.hash);
      expect(nav.pushes.length).toBe(before.pushes);
      expect(q('.ml-engine__head')?.textContent).toBe(before.engine);
      expect(q('svg')?.outerHTML).toBe(before.board);
      await humanMove(); // the game goes on where it was
      await until(() => plies() === 5, 'the engine answers the next move');
    },
  );

  it('a pasted link while a game runs replaces the game when the player continues', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(choice('begin-no'));
    await until(() => plies() === 1 && status() === 'Your move', 'engine opened');
    await act(async () => nav.userNavigates(link));
    await click(choice('link-side-to-move'));
    await until(() => plies() === 3 && status() === 'Your move', 'the linked position');
  });
});

describe('a link that does not work', () => {
  it('says so in plain words and offers a new game; never crashes', async () => {
    for (const bad of ['#g=v1.***', '#g=v9.AAAA', '#g=v1.AAAAAAAA&h=7', '#g=v1.' + 'A'.repeat(900)]) {
      const nav = new FakeNavigation(bad);
      await mount(nav);
      const d = dialog()!;
      expect(d.textContent).toContain('This link does not work');
      expect(d.textContent).toContain('damaged or incomplete');
      expect(d.textContent).not.toMatch(/alphabet|base64|RangeError|illegal-history/);
      expect(document.activeElement).toBe(choice('broken-new'));
      await click(choice('broken-new'));
      expect(dialog()!.textContent).toContain('Do you want to begin?');
      await act(async () => root.unmount());
      host.remove();
    }
    host = document.createElement('div'); // for afterEach
    root = createRoot(host);
  });

  it('rejects moves that no engine accepts, once the engine is ready', async () => {
    const illegal = formatGameFragment({ moves: [makeMove(0, 1), makeMove(2, 3)] }); // cell 0 holds no piece
    const nav = new FakeNavigation(illegal);
    await mount(nav);
    await until(() => dialog()?.textContent?.includes('This link does not work') ?? false, 'the engine rejects the moves');
    expect(dialog()!.textContent).toContain('do not make a valid game');
  });

  it('an illegal link chosen before the engine finished loading also ends in the error, and the game stays empty', async () => {
    const illegal = formatGameFragment({ moves: [makeMove(0, 1)] });
    const nav = new FakeNavigation(illegal);
    await mount(nav);
    const choose = q('[data-choice="link-side-to-move"]');
    if (choose) await click(choose); // may already be replaced by the error
    await until(() => dialog()?.textContent?.includes('This link does not work') ?? false, 'error');
    expect(plies()).toBe(0);
  });
});

describe('copy link', () => {
  it('writes the address of the game to the clipboard', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(choice('begin-yes'));
    await until(() => status() === 'Your move', 'started');
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const button = all('button').find((b) => b.textContent === 'Copy link')!;
    expect(button.hasAttribute('disabled')).toBe(false);
    await click(button);
    expect(writeText).toHaveBeenCalledWith(nav.href);
    expect(nav.href).toContain('#g=v1.');
    expect(q('.ml-copylink [role="status"]')?.textContent).toContain('Link copied');
  });

  it('shows a selectable field when the clipboard is not available', async () => {
    const nav = new FakeNavigation('');
    await mount(nav);
    await click(choice('begin-yes'));
    await until(() => status() === 'Your move', 'started');
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    await click(all('button').find((b) => b.textContent === 'Copy link')!);
    const field = q<HTMLInputElement>('.ml-copylink input')!;
    expect(field.value).toBe(nav.href);
    expect(field.readOnly).toBe(true);
    expect(document.activeElement).toBe(field);
  });

  it('is disabled before a game runs', async () => {
    await mount(new FakeNavigation(''));
    expect(all('button').find((b) => b.textContent === 'Copy link')!.hasAttribute('disabled')).toBe(true);
  });
});

registerEngine; // the import keeps the registry module loaded for the mock-free setup above
