// @vitest-environment jsdom
// The net hints end to end in the whole app: the REAL useNetHints and NetAdvisor, a FAKE net-webgpu engine as the advisor's engine.
// The fake searches with the fake core's classical scores: the classically best move gets few visits and a low value (a Falle),
// the lowest-rated move gets most visits (a Spezialzug). Its init and choose calls can be held, so that the states over time can be seen.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkerEngine } from '../engines/client';
import type { ChooseResult, Engine, EngineInfo, LoadReport } from '../engines/engine';
import { moveFrom, moveTo } from '../engines/engine';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { FakeEngineCore, FakeWorker } from '../engines/testing/fake-engine';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Variant } from '../game/variant';
import { GUIDES_KEY } from '../guides/preference';
import en from '../i18n/en.json';
import { hotSeatVsEngine, humanVsTwoEngines } from '../game/setup';
import { App } from './App';
import { FakeNavigation } from './fake-navigation';

const gpu = vi.hoisted(() => ({ available: true }));
vi.mock('../engines/net/webgpu-backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../engines/net/webgpu-backend')>()),
  isWebGpuAvailable: async () => (gpu.available ? { available: true } : { available: false, reason: 'webgpu-no-api' }),
}));
vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/x.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const entry = { files: [file], binaryBytes: 1, binaryGzipBytes: 1 };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': entry, 'net-webgpu': entry } };

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(selector: string): T | null => host.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string): T[] => [...host.querySelectorAll<T>(selector)];
const status = (): string => q('.ml-status__who span')?.textContent ?? '';
const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);
const chip = (): HTMLElement | null => q('[data-nethint-chip]');
const chipState = (): string | null => chip()?.getAttribute('data-state') ?? null;

async function until(condition: () => boolean, what: string, ms = 4000): Promise<void> {
  const end = Date.now() + ms;
  while (!condition()) {
    if (Date.now() > end) throw new Error(`timeout waiting for: ${what}`);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 5));
    });
  }
}
const click = async (element: Element | null): Promise<void> => {
  if (!element) throw new Error('no element to click');
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
};

interface Gate { promise: Promise<void>; open: () => void }
function gate(): Gate {
  let open!: () => void;
  const promise = new Promise<void>((r) => { open = r; });
  return { promise, open };
}

/** What the fakes did, and the switches the tests turn. */
const fake = {
  /** 'marks': Falle and Spezialzug; 'none': the net agrees with the classical best move. */
  mode: 'marks' as 'marks' | 'none',
  initGate: null as Gate | null,
  chooseGate: null as Gate | null,
  /** Holds the reply of the PLAYING engine (cpp-wasm). */
  replyGate: null as Gate | null,
  netEnginesMade: 0,
  netDisposed: 0,
  searches: [] as Array<{ history: readonly number[]; best: number; fav: number }>,
  playingEngineIds: [] as string[],
};

/** The advisor's engine: a fake net-webgpu engine on the fake core's legal moves and classical scores. */
function makeNetEngine(info: EngineInfo): Engine {
  const core = new FakeEngineCore(5);
  const ask = <T,>(type: string, rest: Record<string, unknown>): T => core.handle({ id: 0, type, ...rest } as never) as T;
  return {
    info,
    warnings: [],
    async init(): Promise<LoadReport> {
      await fake.initGate?.promise;
      return { fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, totalMs: 0, tableVerifiedBy: 'engine' } as LoadReport;
    },
    async newGame() { /* nothing to set up */ },
    legalMoves: async (history, variant) => ask<number[]>('legalMoves', { history, variant }),
    async choose(history, _signal, variant): Promise<ChooseResult> {
      await fake.chooseGate?.promise;
      const v: Variant = variant ?? DEFAULT_VARIANT;
      const legal = ask<number[]>('legalMoves', { history, variant: v });
      const scores = ask<{ scores: number[] }>('analyse', { history, moves: null, nodes: 1, variant: v }).scores;
      const best = legal[scores.indexOf(Math.max(...scores))]!;
      const fav = fake.mode === 'marks' ? legal[scores.indexOf(Math.min(...scores))]! : best;
      fake.searches.push({ history: [...history], best, fav });
      const visits = legal.map((m) => (m === fav ? 600 : m === best ? 100 : 1));
      const values = legal.map((m) => (m === fav ? 0.5 : m === best ? 0.1 : 0));
      return {
        move: fav, score: 0.5, depth: 0, nodes: 0, ms: 0, raceMove: false, rows1Requests: 0,
        net: { backend: 'webgpu', sims: 800, batches: 1, root: { moves: legal, visits, values } },
        warnings: [],
      };
    },
    analyse: () => Promise.reject(new Error('the advisor engine does not analyse')),
    outcome: async (history, variant) => ask('outcome', { history, variant }),
    dispose() { fake.netDisposed++; },
  } as Engine;
}

function register(): void {
  clearEngines();
  registerEngine({
    id: 'cpp-wasm', labelKey: 'engine.cppWasm', runtime: 'wasm', language: 'C++', isAvailable: () => true,
    create: (context) => {
      fake.playingEngineIds.push(context.info.id);
      const engine = new WorkerEngine({ info: context.info, assets: { engine: [], manifest: '', tableGz: '' }, createWorker: () => new FakeWorker(new FakeEngineCore(7), {}) });
      const choose = engine.choose.bind(engine);
      // the fake scores 30 points per row; a Spezialzug needs a favourite that loses more than 3 steps, so the guides get ten times that (same order)
      const analyse = engine.analyse.bind(engine);
      engine.analyse = async (...args) => {
        const r = await analyse(...args);
        return { ...r, scores: r.scores.map((x) => x * 10), position: r.position * 10 };
      };
      engine.choose = async (...args) => {
        await fake.replyGate?.promise;
        return choose(...args);
      };
      return engine;
    },
  });
  registerEngine({
    id: 'net-webgpu', labelKey: 'engine.netWebgpu', runtime: 'wasm', language: 'Java + C++', isAvailable: () => true,
    supportsVariant: (variant) => variant.players === 2,
    create: (context) => {
      fake.netEnginesMade++;
      return makeNetEngine(context.info);
    },
  });
}

async function start(setup?: Parameters<typeof App>[0]['initialSetup']): Promise<void> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced') || query.includes('min-width'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  register();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} navigation={new FakeNavigation('')} {...(setup ? { initialSetup: setup } : {})} />);
  });
  if (!setup) await click(document.querySelector('[data-choice="begin-no"]'));
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem(GUIDES_KEY, 'net');
  gpu.available = true;
  Object.assign(fake, { mode: 'marks', initGate: null, chooseGate: null, replyGate: null, netEnginesMade: 0, netDisposed: 0, searches: [], playingEngineIds: [] });
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  localStorage.clear();
});

describe('net hints in the app with a fake net-webgpu engine', () => {
  it('goes loading, thinking, marks; draws the Falle and the Spezialzug on their pieces; names them; and clears them after the human move', async () => {
    fake.initGate = gate();
    fake.chooseGate = gate();
    await start();
    await until(() => chipState() === 'loading', 'the chip says loading while the model loads');
    expect(chip()!.getAttribute('aria-busy')).toBe('true');
    expect(chip()!.getAttribute('aria-label')).toBe(`${en.nethints_chip_label}: ${en.nethints_state_loading}`);
    expect(all('[data-nethint]')).toHaveLength(0);
    // the bands of the classical analysis are there meanwhile; the net marks are a second layer
    await until(() => all('[data-guide="piece"]').length > 0, 'the classical piece badges');

    fake.initGate.open();
    await until(() => chipState() === 'thinking', 'the chip says thinking while the net searches');
    expect(chip()!.getAttribute('aria-label')).toBe(`${en.nethints_chip_label}: ${en.nethints_state_thinking}`);
    expect(all('[data-nethint]')).toHaveLength(0);

    fake.chooseGate.open();
    await until(() => chipState() === 'marks', 'the chip says marks');
    expect(chip()!.getAttribute('aria-label')).toBe(`${en.nethints_chip_label}: ${en.nethints_state_marks}`);
    const { best, fav, history } = fake.searches[0]!;
    expect(best, 'the fake made two different moves').not.toBe(fav);

    // badges on the ORIGIN pieces of the two moves, the Falle on the best move, the Spezialzug on the favourite
    const trapPiece = q(`[data-nethint="trap"][data-nethint-at="piece"]`);
    const specialPiece = q(`[data-nethint="special"][data-nethint-at="piece"]`);
    expect(trapPiece?.getAttribute('data-cell-of')).toBe(String(moveFrom(best)));
    expect(specialPiece?.getAttribute('data-cell-of')).toBe(String(moveFrom(fav)));
    expect(all('[data-nethint-at="target"]'), 'the target badges wait for a selection').toHaveLength(0);
    expect(all('[data-nethint-chip] [data-net-glyph]').map((g) => g.getAttribute('data-net-glyph'))).toEqual(['trap', 'special']);
    // accessible names of the pieces and the live announcement
    expect(q(`[data-cell="${moveFrom(best)}"]`)!.getAttribute('aria-label')).toContain(en.nethint_piece_trap);
    expect(q(`[data-cell="${moveFrom(fav)}"]`)!.getAttribute('aria-label')).toContain(en.nethint_piece_special);
    expect(q('[data-nethint-live]')!.textContent).toContain(en.nethint_trap_name);
    expect(q('[data-nethint-live]')!.textContent).toContain(en.nethint_special_name);
    // the net block of the card names both
    expect(q('#ml-nethint-block [data-nethint-row="trap"]')).not.toBeNull();
    expect(q('#ml-nethint-block [data-nethint-row="special"]')).not.toBeNull();

    // selecting the piece of the Falle shows the target badge on the target cell and names it
    await click(q(`[data-cell="${moveFrom(best)}"]`));
    const targetBadge = q('[data-nethint="trap"][data-nethint-at="target"]');
    expect(targetBadge?.getAttribute('data-cell-of')).toBe(String(moveTo(best)));
    expect(q(`[data-cell="${moveTo(best)}"]`)!.getAttribute('aria-label')).toContain(en.nethint_move_trap);

    // the human moves; the opponent's reply is held: no mark of the old position, the chip waits for the human turn
    fake.replyGate = gate();
    fake.mode = 'none';
    const before = plies();
    await click(q(`[data-cell="${moveTo(best)}"]`));
    await until(() => plies() > before, 'the move was played');
    expect(all('[data-nethint]')).toHaveLength(0);
    expect(all('[data-nethint-chip] [data-net-glyph]')).toHaveLength(0);
    expect(chipState()).toBe('idle');
    expect(history.length).toBe(before);

    // the opponent answers; the net looks at the new position and agrees with the classical best move: no mark
    fake.replyGate.open();
    await until(() => status() === 'Your move' && chipState() === 'none', 'the chip says none for the next position');
    expect(all('[data-nethint]')).toHaveLength(0);
    expect(fake.searches).toHaveLength(2);
    expect(fake.searches[1]!.history.length).toBe(before + 2);
    expect(chip()!.getAttribute('aria-label')).toBe(`${en.nethints_chip_label}: ${en.nethints_state_none}`);
  });

  it('makes its own net engine for the advisor: not the playing engine, one for the search and none for the probes it did not need', async () => {
    await start();
    await until(() => chipState() === 'marks', 'marks');
    expect(fake.netEnginesMade).toBe(1);
    expect(fake.playingEngineIds).toEqual(['cpp-wasm']); // the net-webgpu fake was never made by the session for playing
    await act(async () => root.unmount());
    expect(fake.netDisposed, 'the advisor frees its engine when the screen goes').toBe(1);
    root = createRoot(host); // so that afterEach can unmount
  });

  it('without WebGPU the net option is disabled with its reason, eval is checked, no net engine is made, and the stored level stays net', async () => {
    gpu.available = false;
    await start();
    await until(() => q('[data-guides-net-off]') !== null, 'the reason under the level control');
    expect(q('[data-guides-net-off]')!.textContent).toContain(en['net.reason.webgpu-no-api']);
    const net = q<HTMLInputElement>('[data-guides-level="net"]')!;
    expect(net.getAttribute('aria-disabled')).toBe('true');
    expect(net.getAttribute('aria-describedby')).toBe(q('[data-guides-net-off]')!.id);
    expect(q<HTMLInputElement>('[data-guides-level="eval"]')!.checked).toBe(true);
    await until(() => all('[data-guide="piece"]').length > 0, 'the bands of the level eval');
    expect(chip()).toBeNull();
    expect(all('[data-nethint]')).toHaveLength(0);
    expect(fake.netEnginesMade).toBe(0);
    expect(localStorage.getItem(GUIDES_KEY)).toBe('net');
  });

  it('with three players the net option is disabled with the three-player reason, and no net engine is made', async () => {
    await start(humanVsTwoEngines(1, 2));
    await until(() => plies() === 1 && status() === 'Your move', 'the engine of seat 0 opened');
    await until(() => q('[data-guides-net-off]') !== null, 'the reason');
    expect(q('[data-guides-net-off]')!.textContent).toContain(en.guides_net_reason_three);
    expect(q('[data-guides-level="net"]')!.getAttribute('aria-disabled')).toBe('true');
    expect(q<HTMLInputElement>('[data-guides-level="eval"]')!.checked).toBe(true);
    expect(chip()).toBeNull();
    expect(fake.netEnginesMade).toBe(0);
  });

  it('a hot-seat game with three players is no different', async () => {
    await start(hotSeatVsEngine(2, 2));
    await until(() => q('[data-guides-net-off]') !== null, 'the reason');
    expect(q('[data-guides-net-off]')!.textContent).toContain(en.guides_net_reason_three);
  });
});
