// @vitest-environment jsdom
// The net hints in the play screen: the call of useNetHints, the board marks, the chip, the net block on wide screens and the panel on small ones.
// useNetHints is replaced by a stand-in that takes a real move from the classical result it receives, so that the board marks a legal move.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkerEngine } from '../engines/client';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { FakeEngineCore, FakeWorker } from '../engines/testing/fake-engine';
import en from '../i18n/en.json';
import { GUIDES_KEY } from '../guides/preference';
import type { NetAvailability, NetHintsModel, NetHintState } from '../guides/net-hints-types';
import type { UseNetHintsInput } from '../guides/useNetHints';
import { App } from './App';

const hook = vi.hoisted(() => ({
  availability: { available: true, reason: null, lostInSession: false } as NetAvailability,
  /** 'marks' needs a classical result; until it arrives the state is 'thinking'. */
  state: 'marks' as NetHintState,
  inputs: [] as UseNetHintsInput[],
  /** Forces the state that the stand-in returns (the lost GPU, 'unavailable'); null: the logic below. */
  force: null as NetHintState | null,
}));
vi.mock('../guides/useNetHints', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../guides/useNetHints')>()),
  useNetHints: (input: UseNetHintsInput): NetHintsModel => {
    hook.inputs.push(input);
    if (hook.force !== null) return { availability: hook.availability, state: hook.force, trap: null, special: null };
    const active = input.level === 'net' && hook.availability.available && input.humanTurn;
    if (!active) return { availability: hook.availability, state: input.level === 'net' ? 'idle' : 'off', trap: null, special: null };
    const first = input.classical?.moves[0];
    if (hook.state !== 'marks' || first === undefined) return { availability: hook.availability, state: first === undefined ? 'thinking' : hook.state, trap: null, special: null };
    return { availability: hook.availability, state: 'marks', trap: { move: first, netValue: -0.2, favouriteValue: 0.3, classicalBand: 'much-better' }, special: null };
  },
}));
vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } } };

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(selector: string): T | null => host.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string): T[] => [...host.querySelectorAll<T>(selector)];

async function until(condition: () => boolean, what: string, ms = 3000): Promise<void> {
  const end = Date.now() + ms;
  while (!condition()) {
    if (Date.now() > end) throw new Error(`timeout waiting for: ${what}`);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 5));
    });
  }
}
const click = async (element: Element): Promise<void> => {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
};
const status = (): string => q('.ml-status__who span')?.textContent ?? '';
const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);

function registerFake(): void {
  clearEngines();
  registerEngine({
    id: 'cpp-wasm',
    labelKey: 'engine.cppWasm',
    runtime: 'wasm',
    language: 'C++',
    isAvailable: () => true,
    create: (context) =>
      new WorkerEngine({
        info: context.info,
        assets: { engine: [], manifest: '', tableGz: '' },
        createWorker: () => new FakeWorker(new FakeEngineCore(7), {}),
      }),
  });
}

async function start(small: boolean): Promise<void> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced') || (small && query.includes('max-width')), media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  registerFake();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} />);
  });
  await click(document.querySelector('[data-choice="begin-no"]')!);
  await until(() => status() === 'Your move', 'the engine loads and moves first');
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem(GUIDES_KEY, 'net');
  hook.availability = { available: true, reason: null, lostInSession: false };
  hook.state = 'marks';
  hook.inputs = [];
  hook.force = null;
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  localStorage.clear();
  history.replaceState(null, '', location.pathname);
});

describe('net hints in the play screen, wide', () => {
  it('calls useNetHints with the level, variant, turn, history, classical result and scope', async () => {
    await start(false);
    await until(() => all('[data-nethint-at="piece"]').length > 0, 'the net badge');
    const withClassical = hook.inputs.find((i) => i.classical !== null)!;
    expect(Object.keys(withClassical).sort()).toEqual(['classical', 'createEngine', 'demo', 'engineSourcePending', 'history', 'humanTurn', 'level', 'over', 'scope', 'variant']);
    expect(withClassical.demo).toBe(false);
    expect(withClassical.over).toBe(false);
    expect(withClassical.engineSourcePending).toBe(false); // the manifest is there once the session has started
    expect(withClassical.level).toBe('net');
    expect(withClassical.humanTurn).toBe(true);
    expect(withClassical.classical!.moves.length).toBe(withClassical.classical!.scores.length);
    expect(withClassical.scope).toEqual(expect.any(String));
  });

  it('draws the badge on the origin piece, the chip with the Falle glyph, the net block in the card, and no panel', async () => {
    await start(false);
    await until(() => all('[data-nethint-at="piece"]').length > 0, 'the net badge');
    expect(all('[data-nethint="trap"][data-nethint-at="piece"]')).toHaveLength(1);
    expect(q('[data-nethint-chip]')!.getAttribute('data-state')).toBe('marks');
    expect(all('[data-nethint-chip] [data-net-glyph]').map((g) => g.getAttribute('data-net-glyph'))).toEqual(['trap']);
    expect(q('#ml-nethint-block [data-nethint-row="trap"]')).not.toBeNull();
    expect(q('#ml-nethint-panel')).toBeNull();
    expect(q('[data-nethint-chip]')!.hasAttribute('aria-expanded')).toBe(false);
    // the chip follows the board in the DOM
    expect(q('.ml-board')!.querySelector('svg.ml-board__svg + [data-nethint-chip]')).not.toBeNull();
  });

  it('announces the marks once, with the name of the mark and the cell', async () => {
    await start(false);
    await until(() => all('[data-nethint-at="piece"]').length > 0, 'the net badge');
    const live = q('[data-nethint-live]')!.textContent!;
    expect(live).toContain(en.nethint_trap_name);
    expect(live).toMatch(/[A-Z]\d+|[a-z]\d+/);
  });

  it('moves the focus into the net block when the chip is clicked', async () => {
    await start(false);
    await until(() => q('[data-nethint-chip]') !== null, 'the chip');
    await click(q('[data-nethint-chip]')!);
    expect(document.activeElement).toBe(q('#ml-nethint-block'));
  });

  it('the chip names the net block it controls when the block is on the screen', async () => {
    await start(false);
    await until(() => q('[data-nethint-chip]') !== null, 'the chip');
    expect(q('[data-nethint-chip]')!.getAttribute('aria-controls')).toBe('ml-nethint-block');
    expect(document.getElementById('ml-nethint-block')).not.toBeNull();
    expect(q('[data-nethint-chip]')!.hasAttribute('aria-disabled')).toBe(false);
  });

  it('the chip of a lost GPU (state unavailable, no net block in the card) is not a dead button: no aria-controls, aria-disabled, the click does nothing', async () => {
    hook.availability = { available: false, reason: { kind: 'reason', code: 'webgpu-lost' }, lostInSession: true };
    hook.force = 'unavailable';
    await start(false);
    await until(() => q('[data-nethint-chip]') !== null, 'the chip');
    const chip = q('[data-nethint-chip]')!;
    expect(chip.getAttribute('data-state')).toBe('unavailable');
    expect(document.getElementById('ml-nethint-block')).toBeNull(); // the level fell back to eval: no block
    expect(chip.hasAttribute('aria-controls')).toBe(false);
    expect(chip.getAttribute('aria-disabled')).toBe('true');
    const active = document.activeElement;
    await click(chip);
    expect(document.activeElement).toBe(active);
  });

  it('draws no net mark and no chip below the level net', async () => {
    localStorage.setItem(GUIDES_KEY, 'eval');
    await start(false);
    await until(() => all('[data-guide="piece"]').length > 0, 'band badges');
    expect(all('[data-nethint]')).toHaveLength(0);
    expect(q('[data-nethint-chip]')).toBeNull();
  });

  it('falls back to eval when the net is not available: no marks, eval checked, the stored level untouched, the reason shown', async () => {
    hook.availability = { available: false, reason: { kind: 'reason', code: 'webgpu-no-api' }, lostInSession: false };
    await start(false);
    await until(() => all('[data-guide="piece"]').length > 0, 'band badges at the level eval');
    expect(q<HTMLInputElement>('[data-guides-level="eval"]')!.checked).toBe(true);
    expect(all('[data-nethint]')).toHaveLength(0);
    expect(localStorage.getItem(GUIDES_KEY)).toBe('net');
    expect(q('[data-guides-net-off]')!.textContent).toContain(en['net.reason.webgpu-no-api']);
  });

  it('shows the chip alone in the states thinking and idle', async () => {
    hook.state = 'thinking';
    await start(false);
    await until(() => q('[data-nethint-chip]') !== null, 'the chip');
    expect(all('[data-nethint]')).toHaveLength(0);
    expect(q('[data-nethint-chip]')!.getAttribute('aria-busy')).toBe('true');
  });
});

describe('net hints in the play screen, small', () => {
  it('opens the panel by itself the first time a mark appears, once, and records it', async () => {
    await start(true);
    await until(() => q('#ml-nethint-panel') !== null, 'the panel');
    expect(q('[data-nethint-chip]')!.getAttribute('aria-expanded')).toBe('true');
    expect(q('#ml-nethint-panel [data-nethint-row="trap"]')).not.toBeNull();
    expect(q('#ml-nethint-panel')!.getAttribute('aria-modal')).toBeNull(); // non-modal
    expect(q('#ml-nethint-block')).toBeNull(); // the card is in the menu on small screens
    expect(localStorage.getItem('ml.nethints.seen')).not.toBeNull();
  });

  it('does not open by itself once the flag is set; the chip opens it, the chip and the close button close it', async () => {
    localStorage.setItem('ml.nethints.seen', '1');
    await start(true);
    await until(() => all('[data-nethint-at="piece"]').length > 0, 'the net badge');
    expect(q('#ml-nethint-panel')).toBeNull();
    await click(q('[data-nethint-chip]')!);
    expect(q('#ml-nethint-panel')).not.toBeNull();
    await click(q('[data-nethint-chip]')!);
    expect(q('#ml-nethint-panel')).toBeNull();
    await click(q('[data-nethint-chip]')!);
    await click(q('.ml-nethints-panel__close')!);
    expect(q('#ml-nethint-panel')).toBeNull();
    expect(document.activeElement).toBe(q('[data-nethint-chip]'));
  });

  it('closes on Escape and gives the focus back to the chip', async () => {
    await start(true);
    await until(() => q('#ml-nethint-panel') !== null, 'the panel');
    q<HTMLElement>('.ml-nethints-panel__close')!.focus();
    await act(async () => {
      q('.ml-nethints-panel__close')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    });
    expect(q('#ml-nethint-panel')).toBeNull();
    expect(document.activeElement).toBe(q('[data-nethint-chip]'));
  });

  it('no aria-controls while the panel is closed (its element does not exist); the open panel is named', async () => {
    localStorage.setItem('ml.nethints.seen', '1');
    await start(true);
    await until(() => q('[data-nethint-chip]') !== null, 'the chip');
    expect(q('[data-nethint-chip]')!.hasAttribute('aria-controls')).toBe(false);
    await click(q('[data-nethint-chip]')!);
    expect(q('[data-nethint-chip]')!.getAttribute('aria-controls')).toBe('ml-nethint-panel');
  });

  it('marks the action bar inert while the panel is open, and only then', async () => {
    localStorage.setItem('ml.nethints.seen', '1');
    await start(true);
    await until(() => all('[data-nethint-at="piece"]').length > 0, 'the net badge');
    expect(q('.ml-play__bar')!.hasAttribute('inert')).toBe(false);
    await click(q('[data-nethint-chip]')!);
    expect(q('.ml-play__bar')!.hasAttribute('inert')).toBe(true);
    await click(q('.ml-nethints-panel__close')!);
    expect(q('.ml-play__bar')!.hasAttribute('inert')).toBe(false);
  });

  it('Escape closes the panel with the bar inert and gives the bar back', async () => {
    await start(true);
    await until(() => q('#ml-nethint-panel') !== null, 'the panel');
    expect(q('.ml-play__bar')!.hasAttribute('inert')).toBe(true);
    q<HTMLElement>('.ml-nethints-panel__close')!.focus();
    await act(async () => {
      q('.ml-nethints-panel__close')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    });
    expect(q('.ml-play__bar')!.hasAttribute('inert')).toBe(false);
    expect(document.activeElement).toBe(q('[data-nethint-chip]'));
  });

  it('a focus inside the panel goes to the chip when the position closes the panel, not to the body', async () => {
    localStorage.setItem('ml.nethints.seen', '1');
    await start(true);
    await until(() => all('[data-nethint-at="piece"]').length > 0, 'the net badge');
    await click(q('[data-nethint-chip]')!);
    q<HTMLElement>('.ml-nethints-panel__close')!.focus();
    expect(document.activeElement).toBe(q('.ml-nethints-panel__close'));
    const piece = all('[data-nethint-at="piece"]')[0]!.getAttribute('data-cell-of')!;
    await click(q(`[data-cell="${piece}"]`)!);
    q<HTMLElement>('.ml-nethints-panel__close')!.focus(); // the board click does not take the focus in jsdom
    const before = plies();
    await click(all('[data-cell][aria-label*="target"]')[0]!);
    await until(() => plies() > before, 'the move was played');
    expect(q('#ml-nethint-panel')).toBeNull();
    expect(document.activeElement).toBe(q('[data-nethint-chip]'));
  });

  it('a focus elsewhere is left alone when the position closes the panel', async () => {
    localStorage.setItem('ml.nethints.seen', '1');
    await start(true);
    await until(() => all('[data-nethint-at="piece"]').length > 0, 'the net badge');
    await click(q('[data-nethint-chip]')!);
    const piece = all('[data-nethint-at="piece"]')[0]!.getAttribute('data-cell-of')!;
    await click(q(`[data-cell="${piece}"]`)!);
    (document.activeElement as HTMLElement | null)?.blur();
    const before = plies();
    await click(all('[data-cell][aria-label*="target"]')[0]!);
    await until(() => plies() > before, 'the move was played');
    expect(document.activeElement).not.toBe(q('[data-nethint-chip]'));
  });

  it('closes by itself when the position changes', async () => {
    localStorage.setItem('ml.nethints.seen', '1');
    await start(true);
    await until(() => all('[data-nethint-at="piece"]').length > 0, 'the net badge');
    await click(q('[data-nethint-chip]')!);
    expect(q('#ml-nethint-panel')).not.toBeNull();
    const piece = all('[data-nethint-at="piece"]')[0]!.getAttribute('data-cell-of')!;
    await click(q(`[data-cell="${piece}"]`)!);
    const before = plies();
    await click(all('[data-cell][aria-label*="target"]')[0]!);
    await until(() => plies() > before, 'the move was played');
    expect(q('#ml-nethint-panel')).toBeNull();
  });
});
