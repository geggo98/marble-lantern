// @vitest-environment jsdom
// The net hints hook with a faked advisor: availability reasons, gating, and the state shown for the position.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Engine } from '../engines/engine';
import type { ProbeResult } from '../engines/warnings';
import { DEFAULT_VARIANT, withRows } from '../game/variant';
import en from '../i18n/en.json';
import de from '../i18n/de.json';
import type { Variant } from '../game/variant';
import type { Divergence } from './divergence';
import type { AdvisorLike, UseNetHintsInput } from './useNetHints';
import { REASON_CODES, defaultModelCheck, knownReason, useNetHints } from './useNetHints';
import type { NetAdvisorState } from './net-advisor';
import type { NetHintsModel } from './net-hints-types';
import { NetAdvisor } from './net-advisor';
import { getNetLoss, resetNetLoss, setNetLoss } from './net-loss';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const V3: Variant = { jump: 'adjacent', players: 3 };
const view = { moves: [7, 9], scores: [100, -20] };
const marksDivergence = (move: number): Divergence => ({ move, netValue: 0.1, favouriteValue: 0.5, classicalBand: 'equal' });

/** An advisor that the test drives by hand. Its key is `k:<history>` so that a moved game has another key. */
function fakeAdvisor() {
  let state: NetAdvisorState = { status: 'idle', key: null, trap: null, special: null, lost: null };
  const listeners = new Set<() => void>();
  const requests: Array<{ scope: string; history: readonly number[]; variant: Variant; classical: { moves: number[]; scores: number[] } }> = [];
  const log = { cancels: 0, disposed: 0 };
  const advisor: AdvisorLike = {
    getState: () => state,
    subscribe: (l) => { listeners.add(l); return () => listeners.delete(l); },
    request: (scope, history, variant, classical) => { requests.push({ scope, history, variant, classical }); },
    cancel: () => { log.cancels++; },
    dispose: () => { log.disposed++; },
    keyFor: (scope, history) => `k:${scope}:${history.join(',')}`,
  };
  return {
    advisor, requests, log,
    set(next: Partial<NetAdvisorState>) { state = { ...state, ...next }; act(() => { for (const l of listeners) l(); }); },
  };
}

const okProbe = (): Promise<ProbeResult> => Promise.resolve({ available: true });
const noEngine = (): Engine => { throw new Error('the faked advisor makes no engine'); };

let host: HTMLDivElement;
let root: Root;
beforeEach(() => { resetNetLoss(); host = document.createElement('div'); document.body.append(host); root = createRoot(host); });
afterEach(() => { act(() => root.unmount()); host.remove(); });

type Input = Partial<UseNetHintsInput>;
function setup(fake: ReturnType<typeof fakeAdvisor>, initial: Input = {}) {
  let latest!: NetHintsModel;
  const base: UseNetHintsInput = {
    level: 'net', variant: DEFAULT_VARIANT, humanTurn: true, history: [1, 2], classical: view, scope: 'cpp',
    createEngine: noEngine, probeWebGpu: okProbe, modelCheck: () => null, makeAdvisor: () => fake.advisor,
  };
  function Probe(props: { input: UseNetHintsInput }) { latest = useNetHints(props.input); return null; }
  const render = async (over: Input = {}) => {
    Object.assign(base, over);
    await act(async () => { root.render(<Probe input={{ ...base }} />); });
  };
  return { render, get: () => latest, initial };
}

describe('useNetHints: availability', () => {
  it('is checking until the probe answers, then available', async () => {
    let answer!: (p: ProbeResult) => void;
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render({ probeWebGpu: () => new Promise<ProbeResult>((r) => { answer = r; }) });
    expect(h.get().availability).toEqual({ available: false, reason: { kind: 'checking' }, lostInSession: false });
    expect(h.get().state).toBe('off');
    await act(async () => { answer({ available: true }); });
    expect(h.get().availability).toEqual({ available: true, reason: null, lostInSession: false });
  });

  it('three players: never available, whatever the probe says, and the probe is not what is blamed', async () => {
    const h = setup(fakeAdvisor());
    await h.render({ variant: V3 });
    expect(h.get().availability.reason).toEqual({ kind: 'three-players' });
    expect(h.get().availability.available).toBe(false);
  });

  it('a failed probe gives its reason code; without a code, device-failed', async () => {
    const h = setup(fakeAdvisor());
    await h.render({ probeWebGpu: () => Promise.resolve({ available: false, reason: 'webgpu-no-adapter' }) });
    expect(h.get().availability.reason).toEqual({ kind: 'reason', code: 'webgpu-no-adapter' });
    await h.render({ probeWebGpu: () => Promise.resolve({ available: false, reason: 'webgpu-no-api' }) });
    expect(h.get().availability.available).toBe(false);
  });

  it('a probe that throws counts as no WebGPU API', async () => {
    const h = setup(fakeAdvisor());
    await h.render({ probeWebGpu: () => Promise.reject(new Error('import failed')) });
    expect(h.get().availability.reason).toEqual({ kind: 'reason', code: 'webgpu-no-api' });
  });

  it('a probe without a reason code says device-failed', async () => {
    const h = setup(fakeAdvisor());
    await h.render({ probeWebGpu: () => Promise.resolve({ available: false }) });
    expect(h.get().availability.reason).toEqual({ kind: 'reason', code: 'webgpu-device-failed' });
  });

  it('no model for the variant gives the code of the check', async () => {
    const h = setup(fakeAdvisor());
    await h.render({ modelCheck: () => 'variant-not-supported' });
    expect(h.get().availability.reason).toEqual({ kind: 'reason', code: 'variant-not-supported' });
    await h.render({ modelCheck: () => 'no-model-id' });
    expect(h.get().availability.reason).toEqual({ kind: 'reason', code: 'no-model-id' });
  });

  // web/models/models.json: small-2p-adjacent-expert (4 rows), small-2p-adjacent-r5-expert (5 rows), small-shared-all (adjacent and long, 4 rows)
  it.each([
    ['4 rows, adjacent: the expert model', { jump: 'adjacent', players: 2 } as Variant, null],
    ['4 rows, long hop: the shared model', { jump: 'long', players: 2 } as Variant, null],
    ['5 rows, adjacent: the r5 expert', withRows({ jump: 'adjacent', players: 2 }, 5), null],
    ['5 rows, long hop: no model', withRows({ jump: 'long', players: 2 }, 5), 'variant-not-supported'],
  ])('the default model check, two players, %s', (_name, variant, expected) => {
    expect(defaultModelCheck(variant)).toBe(expected);
  });

  it('the long hop on 5 rows gives the reason variant-not-supported through the hook', async () => {
    const h = setup(fakeAdvisor());
    await h.render({ variant: withRows({ jump: 'long', players: 2 }, 5), modelCheck: defaultModelCheck });
    expect(h.get().availability.reason).toEqual({ kind: 'reason', code: 'variant-not-supported' });
    expect(h.get().state).toBe('off');
  });

  it.each(REASON_CODES)('the reason code %s has a text in both catalogs', (code) => {
    expect((en as Record<string, string>)[`net.reason.${code}`]).toEqual(expect.any(String));
    expect((de as Record<string, string>)[`net.reason.${code}`]).toEqual(expect.any(String));
  });

  it('a lost code without a catalog text falls back to webgpu-lost; known codes pass through', async () => {
    expect(knownReason('load-failed')).toBe('load-failed');
    expect(knownReason('webassembly-unavailable')).toBe('webassembly-unavailable');
    expect(knownReason('something-new')).toBe('webgpu-lost');
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    fake.set({ status: 'failed', key: 'k:cpp:1,2', lost: 'something-new' });
    expect(h.get().availability.reason).toEqual({ kind: 'reason', code: 'webgpu-lost' });
    fake.set({ status: 'failed', key: 'k:cpp:1,2', lost: 'load-failed' });
    expect(h.get().availability.reason).toEqual({ kind: 'reason', code: 'load-failed' });
  });

  it('a build without the net engine says net-engine-not-shipped, not "no WebGPU API"', async () => {
    const h = setup(fakeAdvisor());
    await h.render({ createEngine: null });
    expect(h.get().availability).toMatchObject({ available: false, reason: { kind: 'reason', code: 'net-engine-not-shipped' } });
  });

  it('no manifest yet (engineSourcePending) is checking; once it is there without an engine, net-engine-not-shipped', async () => {
    const h = setup(fakeAdvisor());
    await h.render({ createEngine: null, engineSourcePending: true });
    expect(h.get().availability.reason).toEqual({ kind: 'checking' });
    await h.render({ createEngine: null, engineSourcePending: false });
    expect(h.get().availability.reason).toEqual({ kind: 'reason', code: 'net-engine-not-shipped' });
    await h.render({ createEngine: noEngine, engineSourcePending: false });
    expect(h.get().availability.available).toBe(true);
  });

  it('the GPU loss survives a remount: a new mount with a new advisor starts out as lost', async () => {
    const first = fakeAdvisor();
    const h = setup(first);
    await h.render();
    expect(h.get().availability.available).toBe(true);
    setNetLoss('webgpu-device-failed'); // what NetAdvisor.lose() writes
    await h.render();
    act(() => root.unmount());
    root = createRoot(host);
    const second = fakeAdvisor(); // a fresh advisor knows nothing of the loss
    const h2 = setup(second);
    await h2.render();
    expect(h2.get().availability).toEqual({ available: false, reason: { kind: 'reason', code: 'webgpu-device-failed' }, lostInSession: true });
  });

  it('the hook follows a loss that is recorded while it is mounted', async () => {
    const h = setup(fakeAdvisor());
    await h.render();
    expect(h.get().availability.lostInSession).toBe(false);
    act(() => setNetLoss('webgpu-lost'));
    expect(h.get().availability).toMatchObject({ available: false, lostInSession: true });
  });

  it('a real advisor that loses the GPU writes the store; the next real advisor does not call an engine', async () => {
    let made = 0;
    const engine = { warnings: [], async init() { return {}; }, async newGame() {}, dispose() {}, async outcome() { return { kind: 'ongoing' }; },
      async choose() { return { move: 1, score: 0, depth: 0, nodes: 0, ms: 0, raceMove: false, rows1Requests: 0, net: { backend: 'random', sims: 0, batches: 0 } }; } } as unknown as Engine;
    const a = new NetAdvisor({ createEngine: () => { made++; return engine; }, modelOf: () => 'm' });
    a.request('cpp', [], DEFAULT_VARIANT, { moves: [1, 2], scores: [100, -20] });
    await act(async () => { await new Promise((r) => setTimeout(r, 20)); });
    expect(getNetLoss()).toBe('webgpu-lost');
    a.dispose();
    const madeBefore = made;
    const b = new NetAdvisor({ createEngine: () => { made++; return engine; }, modelOf: () => 'm' });
    expect(b.getState().lost).toBe('webgpu-lost');
    b.request('cpp', [], DEFAULT_VARIANT, { moves: [1, 2], scores: [100, -20] });
    expect(b.getState()).toMatchObject({ status: 'failed', lost: 'webgpu-lost' });
    expect(made).toBe(madeBefore);
  });

  it('the GPU lost in the session: lostInSession, reason webgpu-lost, and the mark of that position is "unavailable" until the next one', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    fake.set({ status: 'failed', key: 'k:cpp:1,2', lost: 'webgpu-lost' });
    expect(h.get().availability).toEqual({ available: false, reason: { kind: 'reason', code: 'webgpu-lost' }, lostInSession: true });
    expect(h.get().state).toBe('unavailable');
    // the person's level falls back to eval; still the same position: the chip says unavailable
    await h.render({ level: 'eval' });
    expect(h.get().state).toBe('unavailable');
    // next position: gone
    await h.render({ level: 'eval', history: [1, 2, 3, 4] });
    expect(h.get().state).toBe('off');
    expect(h.get().availability.lostInSession).toBe(true);
  });
});

describe('useNetHints: gating of the advisor', () => {
  it('requests the position with the classical scores as arrays, on the human turn at level net', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    expect(fake.requests).toHaveLength(1);
    expect(fake.requests[0]).toMatchObject({ scope: 'cpp', history: [1, 2], variant: DEFAULT_VARIANT, classical: { moves: [7, 9], scores: [100, -20] } });
  });

  it.each([
    ['level eval', { level: 'eval' as const }],
    ['level targets', { level: 'targets' as const }],
    ['level none', { level: 'none' as const }],
    ['the opponent to move', { humanTurn: false }],
    ['no classical view yet', { classical: null }],
    ['no scope', { scope: null }],
    ['three players', { variant: V3 }],
    ['no net engine', { createEngine: null }],
    ['no model', { modelCheck: () => 'no-model-id' as const }],
    ['WebGPU missing', { probeWebGpu: () => Promise.resolve({ available: false, reason: 'webgpu-no-api' as const }) }],
  ])('no request with %s', async (_name, over: Input) => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render(over);
    expect(fake.requests).toEqual([]);
    expect(fake.log.cancels).toBeGreaterThan(0);
  });

  it('no request while the probe has not answered', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render({ probeWebGpu: () => new Promise<ProbeResult>(() => undefined) });
    expect(fake.requests).toEqual([]);
  });

  it('a new position asks again; the same input does not', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    await h.render();
    expect(fake.requests).toHaveLength(1);
    await h.render({ history: [1, 2, 3, 4] });
    expect(fake.requests).toHaveLength(2);
  });

  it('turning the level down cancels the advisor', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    const before = fake.log.cancels;
    await h.render({ level: 'eval' });
    expect(fake.log.cancels).toBeGreaterThan(before);
  });

  it('disposes the advisor on unmount', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    act(() => root.unmount());
    expect(fake.log.disposed).toBe(1);
    root = createRoot(host); // for afterEach
  });
});

describe('useNetHints: state shown', () => {
  it('off below level net', async () => {
    const h = setup(fakeAdvisor());
    await h.render({ level: 'eval' });
    expect(h.get().state).toBe('off');
    expect(h.get().trap).toBeNull();
  });

  it('idle on the opponent turn, thinking on the human turn while the classical analysis is missing', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render({ humanTurn: false });
    expect(h.get().state).toBe('idle');
    await h.render({ humanTurn: false, classical: null });
    expect(h.get().state).toBe('idle');
    await h.render({ humanTurn: true, classical: null });
    expect(h.get().state).toBe('thinking');
    expect(fake.requests).toHaveLength(0); // thinking, but the advisor waits for the classical scores
  });

  it.each([{ demo: true }, { over: true }])('no hints in a demo or after the game: %o is off, never "idle"', async (over) => {
    const h = setup(fakeAdvisor());
    await h.render({ humanTurn: false, ...over });
    expect(h.get().state).toBe('off');
    await h.render({ humanTurn: false, demo: false, over: false }); // the same input without it: the opponent is to move
    expect(h.get().state).toBe('idle');
  });

  it('the chip of a lost GPU is off in a demo too', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    fake.set({ status: 'failed', key: 'k:cpp:1,2', lost: 'webgpu-lost' });
    expect(h.get().state).toBe('unavailable');
    await h.render({ demo: true });
    expect(h.get().state).toBe('off');
  });

  it('thinking until the advisor reports for this position', async () => {
    const h = setup(fakeAdvisor());
    await h.render();
    expect(h.get().state).toBe('thinking');
  });

  it.each(['loading', 'thinking', 'none', 'skipped', 'failed'] as const)('shows the advisor state %s for this position', async (status) => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    fake.set({ status, key: 'k:cpp:1,2' });
    expect(h.get().state).toBe(status);
    expect(h.get().trap).toBeNull();
    expect(h.get().special).toBeNull();
  });

  it('marks carry trap and special of this position', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    fake.set({ status: 'marks', key: 'k:cpp:1,2', trap: marksDivergence(7), special: marksDivergence(9) });
    expect(h.get().state).toBe('marks');
    expect(h.get().trap?.move).toBe(7);
    expect(h.get().special?.move).toBe(9);
  });

  it('marks of another position are not shown (the human moved)', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    fake.set({ status: 'marks', key: 'k:cpp:1,2', trap: marksDivergence(7), special: null });
    await h.render({ history: [1, 2, 7], humanTurn: false });
    expect(h.get().state).toBe('idle');
    expect(h.get().trap).toBeNull();
    // the opponent has answered, the advisor has not: no stale marks either
    await h.render({ history: [1, 2, 7, 3], humanTurn: true });
    expect(h.get().state).toBe('thinking');
    expect(h.get().trap).toBeNull();
  });

  it('marks are hidden when the level drops below net, although the advisor still holds them', async () => {
    const fake = fakeAdvisor();
    const h = setup(fake);
    await h.render();
    fake.set({ status: 'marks', key: 'k:cpp:1,2', trap: marksDivergence(7), special: null });
    await h.render({ level: 'eval' });
    expect(h.get().state).toBe('off');
    expect(h.get().trap).toBeNull();
  });
});
