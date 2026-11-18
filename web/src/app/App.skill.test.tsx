// @vitest-environment jsdom
// The skill estimate in the app, with the fake engine: the human's moves are analysed in the background, the card shows the
// estimate after 8 moves, "Match engine to my strength" sets a custom level (in the address too), "Back to preset level" undoes it.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { FakeNavigation } from './fake-navigation';
import { parseHash } from './game-url';

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
const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);

async function until(condition: () => boolean, what: string, ms = 5000): Promise<void> {
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
  const before = plies();
  for (const piece of all('[data-cell][aria-label*="your piece"]')) {
    await click(piece);
    const target = all('[data-cell][aria-label*="target"]')[0];
    if (target) {
      await click(target);
      await until(() => plies() >= before + 2 && !q('.ml-status[data-state="thinking"]'), 'the engine answered');
      return;
    }
  }
  throw new Error('no own piece has a legal move');
}
const button = (label: string): HTMLButtonElement =>
  all<HTMLButtonElement>('button').find((b) => b.textContent?.includes(label))!;

async function start(nav: FakeNavigation): Promise<void> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(0);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} navigation={nav} />);
  });
  await until(() => !q('.ml-loading') && q('[data-choice="begin-yes"]') !== null, 'the dialog');
  await click(q('[data-choice="begin-yes"]')!);
  await until(() => q('.ml-skill') !== null && all('[data-cell][aria-label*="your piece"]').length > 0, 'the game and the skill card');
}

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

describe('skill estimate in the app', () => {
  it('shows progress with fewer than 8 moves, then the estimate with the number of moves, and no engine move waits for the analysis', async () => {
    await start(new FakeNavigation(''));
    expect(q('.ml-skill')!.textContent).toContain('0 of 8 moves analysed');
    expect(button('Match engine to my strength').disabled).toBe(true);
    for (let i = 0; i < 3; i++) await humanMove();
    await until(() => (q('.ml-skill')!.textContent ?? '').includes('3 of 8 moves analysed'), 'three moves analysed');
    for (let i = 0; i < 5; i++) await humanMove();
    await until(() => q('[data-testid="skill-estimate"]') !== null, 'the estimate');
    expect(q('[data-testid="skill-moves"]')!.textContent).toBe('Based on 8 of your moves.');
    expect(q('.ml-skill')!.textContent).toContain('not a rating');
    expect(button('Match engine to my strength').disabled).toBe(false);
  }, 30_000);

  it('"Match engine to my strength" sets a custom level shown on the engine card and in the address; "Back to preset level" goes back', async () => {
    const nav = new FakeNavigation('');
    await start(nav);
    for (let i = 0; i < 8; i++) await humanMove();
    await until(() => q('[data-testid="skill-estimate"]') !== null, 'the estimate');
    const movesBefore = plies();
    await click(button('Match engine to my strength'));
    await until(() => (q('.ml-engine__level')?.textContent ?? '').includes('Custom'), 'custom level on the engine card');
    const link = parseHash(nav.hash);
    expect(link.kind).toBe('game');
    if (link.kind !== 'game') return;
    expect(link.link.level).toBeGreaterThanOrEqual(10); // a node budget, not a preset index
    expect(plies()).toBe(movesBefore); // the game stays
    await click(button('Back to preset level'));
    await until(() => !(q('.ml-engine__level')?.textContent ?? '').includes('Custom'), 'preset level again');
    const back = parseHash(nav.hash);
    expect(back.kind === 'game' && back.link.level).toBe(2);
    expect(plies()).toBe(movesBefore);
  }, 30_000);
});
