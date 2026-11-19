// @vitest-environment jsdom
// Move guides and the advantage indicator for three players, in the whole app against the fake engine (analyse: 30 points per
// gained row for the mover; evaluate3 is replaced by a spy where the test needs distinct values).
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockInstance } from 'vitest';
import { GUIDE_NODES } from '../engines/analysis';
import { WorkerEngine } from '../engines/client';
import { clearEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import en from '../i18n/en.json';
import { hotSeatVsEngine, humanVsTwoEngines } from '../game/setup';
import type { GameSetup } from '../game/setup';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { FakeNavigation } from './fake-navigation';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } } };
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(selector: string): T | null => document.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string): T[] => [...document.querySelectorAll<T>(selector)];
const status = (): string => q('.ml-status__who span')?.textContent ?? '';
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
/** The human plays the first move of the first piece that has one; `owner` names the pieces ("player 2 piece"). */
async function humanMove(owner: RegExp): Promise<void> {
  for (const piece of all('[data-cell]').filter((c) => owner.test(c.getAttribute('aria-label') ?? ''))) {
    await click(piece);
    const target = all('[data-cell][aria-label*="target"]')[0];
    if (target) {
      await click(target);
      return;
    }
  }
  throw new Error('no piece has a legal move');
}
async function mount(setup: GameSetup, chooseDelayMs = 0): Promise<void> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced') || query.includes('min-width'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(chooseDelayMs);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} navigation={new FakeNavigation('')} initialSetup={setup} />);
  });
}

let analyseSpy: MockInstance<WorkerEngine['analyse']>;
const calls = (): { length: number; nodes: number; players: number | undefined; signal: AbortSignal | undefined }[] =>
  analyseSpy.mock.calls.map(([history, , nodes, signal, variant]) => ({ length: history.length, nodes, players: variant?.players, signal }));

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem('ml.guides', 'on');
  analyseSpy = vi.spyOn(WorkerEngine.prototype, 'analyse');
});
afterEach(async () => {
  vi.restoreAllMocks();
  await act(async () => root.unmount());
  host.remove();
  localStorage.clear();
});

const indicator = (): HTMLElement | null => q('[data-advantage3] .ml-adv3');
const shareOf = (seat: number): number => Number(q(`.ml-adv3__seg[data-seat="${seat}"]`)?.getAttribute('data-percent') ?? NaN);

describe('move guides for three players', () => {
  it('one person on seat 2: badges on the person turn only, with the guide budget and the variant; none while the engine seats play', async () => {
    await mount(humanVsTwoEngines(1, 2));
    await until(() => plies() === 1 && status() === 'Your move', 'the engine of seat 0 opened');
    await until(() => all('[data-guide="piece"]').length > 0, 'piece badges');
    expect(all('[data-guide="piece"]')).toHaveLength(all('[data-cell][aria-label*="best move"]').length);
    expect(calls().length).toBeGreaterThan(0);
    for (const c of calls()) expect(c).toMatchObject({ nodes: GUIDE_NODES, players: 3 });
    await humanMove(/your piece/);
    await until(() => plies() === 4 && status() === 'Your move', 'both engines answered');
    await until(() => all('[data-guide="piece"]').length > 0, 'badges for the next turn');
    // the human is seat 1: a request only for histories after which seat 1 is to move (length 1, 4, ...), never for an engine seat
    for (const c of calls()) expect(c.length % 3, `analysis for a history of ${c.length} plies`).toBe(1);
    expect(new Set(calls().map((c) => c.length))).toEqual(new Set([1, 4]));
  });

  it('target marks: selecting a piece tints its targets with a band in the accessible name', async () => {
    await mount(humanVsTwoEngines(1, 2));
    await until(() => all('[data-guide="piece"]').length > 0, 'piece badges');
    await click(all('[data-cell][aria-label*="best move"]')[0]!);
    await until(() => all('[data-guide="target"]').length > 0, 'target marks');
    const names = all('[data-cell][aria-label*="target"]').map((e) => e.getAttribute('aria-label') ?? '');
    expect(names.length).toBe(all('[data-guide="target"]').length);
    const bands = ['much_better', 'better', 'equal', 'worse', 'much_worse'].map((b) => en[`guide_move_${b}` as keyof typeof en]);
    for (const n of names) expect(bands.some((b) => n.endsWith(b)), n).toBe(true);
  });

  it('hot-seat: the guides follow the seat to move, and the engine seat gets none', async () => {
    await mount(hotSeatVsEngine(2, 2));
    await until(() => all('[data-guide="piece"]').length > 0, 'badges for seat 0');
    const owners = (): string[] => all('[data-guide="piece"]').map((g) => q(`[data-cell="${g.getAttribute('data-cell-of')}"]`)?.getAttribute('aria-label') ?? '');
    expect(owners().every((l) => /player 1 piece/.test(l)), owners().join('|')).toBe(true);
    await humanMove(/player 1 piece/);
    await until(() => plies() === 1 && all('[data-guide="piece"]').length > 0 && owners().every((l) => /player 2 piece/.test(l)), 'badges for seat 1');
    await humanMove(/player 2 piece/);
    await until(() => plies() === 3 && status() === 'Player 1 to move', 'the engine of seat 2 answered');
    await until(() => owners().length > 0 && owners().every((l) => /player 1 piece/.test(l)), 'badges for seat 0 again');
    // one request per human turn: histories of 0, 1 and 3 plies; none of length 2 (the engine seat)
    expect(new Set(calls().map((c) => c.length))).toEqual(new Set([0, 1, 3]));
  });

  it('a running analysis is aborted when the turn ends', async () => {
    await mount(humanVsTwoEngines(0, 2), 600); // the fake answers after 600 ms: the analysis is still running when the person moves
    await until(() => calls().length > 0, 'an analysis request');
    const signal = calls()[0]!.signal!;
    expect(signal.aborted).toBe(false);
    await humanMove(/your piece/);
    expect(signal.aborted).toBe(true);
    expect(all('[data-guide="piece"]')).toHaveLength(0);
  });

  it('switched off: no request, no marks, no indicator', async () => {
    localStorage.setItem('ml.guides', 'off');
    await mount(humanVsTwoEngines(1, 2));
    await until(() => plies() === 1 && status() === 'Your move', 'the engine opened');
    await new Promise((r) => setTimeout(r, 100));
    expect(calls()).toHaveLength(0);
    expect(all('[data-guide]')).toHaveLength(0);
    expect(q('[data-advantage3]')).toBeNull();
  });
});

describe('advantage indicator for three players', () => {
  it('shows three segments in seat order with the percentages in the accessible name', async () => {
    const spy = vi.spyOn(WorkerEngine.prototype, 'evaluate3').mockResolvedValue([120, 0, -120]);
    await mount(humanVsTwoEngines(1, 2));
    await until(() => indicator() !== null, 'the indicator');
    const segs = all('.ml-adv3__seg');
    expect(segs.map((s) => s.getAttribute('data-seat'))).toEqual(['0', '1', '2']);
    // T = 120: weights e^1, 1, e^-1 over e + 1 + 1/e = 4.0861 -> 66.5 %, 24.5 %, 9.0 %; rounded to 67, 24, 9
    expect([0, 1, 2].map(shareOf)).toEqual([67, 24, 9]);
    expect(indicator()!.getAttribute('role')).toBe('img');
    expect(indicator()!.getAttribute('aria-label')).toBe('Advantage: Player 1 (engine) 67 %, Player 2 (you) 24 %, Player 3 (engine) 9 %');
    // each segment shows marble, seat number and the share: not colour alone
    for (const seat of [0, 1, 2]) {
      const seg = q(`.ml-adv3__seg[data-seat="${seat}"]`)!;
      expect(seg.querySelector('.ml-swatch use')?.getAttribute('href')).toBe(`#ml-piece-${['b', 'a', 'c'][seat]}`);
      expect(seg.querySelector('.ml-adv3__num')?.textContent).toBe(String(seat + 1));
      expect(seg.querySelector('.ml-adv3__value')?.textContent).toBe(`${[67, 24, 9][seat]} %`);
    }
    // the two-player bar is not there
    expect(q('.ml-advantage__track')).toBeNull();
    expect(spy).toHaveBeenCalled();
  });

  it('updates after each move, also after engine moves, and equal values show equal shares', async () => {
    const spy = vi.spyOn(WorkerEngine.prototype, 'evaluate3').mockImplementation(async (history) => (history.length >= 4 ? [0, 0, 0] : [60, 0, 0]));
    await mount(humanVsTwoEngines(1, 2));
    await until(() => indicator() !== null, 'the indicator');
    expect(shareOf(0)).toBeGreaterThan(shareOf(1));
    await humanMove(/your piece/);
    await until(() => plies() === 4, 'both engines answered');
    await until(() => !q('[data-advantage3].is-stale') && shareOf(0) === 34, 'the indicator follows the position');
    expect([0, 1, 2].map(shareOf)).toEqual([34, 33, 33]);
    expect(new Set(spy.mock.calls.map(([h]) => h.length))).toContain(4);
  });

  it('shows the place of a finished seat instead of a share', async () => {
    vi.spyOn(WorkerEngine.prototype, 'evaluate3').mockResolvedValue([1_000_000, 0, 120]);
    await mount(humanVsTwoEngines(1, 2));
    await until(() => indicator() !== null, 'the indicator');
    const first = q('.ml-adv3__seg[data-seat="0"]')!;
    expect(first.getAttribute('data-place')).toBe('1');
    expect(first.hasAttribute('data-percent')).toBe(false);
    expect(first.querySelector('.ml-adv3__value')?.textContent).toBe(en.advantage3_place_1);
    expect([1, 2].map(shareOf)).toEqual([27, 73]);
    expect(indicator()!.getAttribute('aria-label')).toContain(`Player 1 (engine) ${en.advantage3_place_1}, Player 2 (you) 27 %, Player 3 (engine) 73 %`);
  });

  it('shows no indicator when the engine has no static evaluation, and the guides still work', async () => {
    vi.spyOn(WorkerEngine.prototype, 'evaluate3').mockRejectedValue(Object.assign(new Error('no'), { code: 'unsupported' }));
    await mount(humanVsTwoEngines(1, 2));
    await until(() => all('[data-guide="piece"]').length > 0, 'badges');
    await new Promise((r) => setTimeout(r, 50));
    expect(q('[data-advantage3]')).toBeNull();
  });

  it('keeps the two-player bar for two players (unchanged) and has no skill card for three', async () => {
    await mount(humanVsTwoEngines(1, 2));
    await until(() => indicator() !== null, 'the indicator');
    expect(q('[data-testid="skill-estimate"]')).toBeNull();
    expect(q('[data-testid="skill-pause"]')).toBeNull();
    expect(q('[data-guides-note-three]')).not.toBeNull();
  });
});
