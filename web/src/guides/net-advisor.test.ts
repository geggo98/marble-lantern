import { beforeEach, describe, expect, it } from 'vitest';
import { SCORE } from '../engines/analysis';
import type { ChooseResult, Engine, EngineOutcome } from '../engines/engine';
import type { EngineWarning } from '../engines/warnings';
import { DEFAULT_VARIANT } from '../game/variant';
import { MIN_VISITS } from './divergence';
import { ADVISOR_SIMS, NetAdvisor, PROBE_SIMS } from './net-advisor';
import type { NetAdvisorState } from './net-advisor';
import { getNetLoss, resetNetLoss } from './net-loss';

// the loss of the net is kept for the session (net-loss.ts); every test starts without it
beforeEach(() => { resetNetLoss(); });

const V = DEFAULT_VARIANT;
const abortError = (): Error => new DOMException('aborted', 'AbortError');
const tick = (): Promise<void> => new Promise((r) => setTimeout(r, 0));
const flush = async (): Promise<void> => { for (let i = 0; i < 5; i++) await tick(); };

// Classical scores against a best of 100: 80 = top, -20 = fair (band equal). Moves 1 and 2.
const BEST = 100;
const TOP = 80;
const FAIR = -20;
const classical = (scores: number[]) => ({ moves: scores.map((_, i) => i + 1), scores });

interface Call { history: readonly number[]; signal: AbortSignal | undefined; resolve: (r: ChooseResult) => void; reject: (e: unknown) => void }

const reply = (over: { score?: number; backend?: 'wasm' | 'webgpu' | 'random'; root?: { moves: number[]; visits: number[]; values?: number[] } | null; sims?: number; raceMove?: boolean; warnings?: EngineWarning[] } = {}): ChooseResult => ({
  move: 1, score: over.score ?? 0, depth: 0, nodes: 1, ms: 0, raceMove: over.raceMove ?? false, rows1Requests: 0,
  // a real search always reports its root; `root: null` is a result without one (no search happened)
  net: { backend: over.backend ?? 'webgpu', sims: over.sims ?? 1, batches: 1, ...(over.root === null ? {} : { root: over.root ?? { moves: [1, 2], visits: [1, 1], values: [0, 0] } }) },
  ...(over.warnings ? { warnings: over.warnings } : {}),
});
const root = (visits: number[], values: number[]) => ({ moves: visits.map((_, i) => i + 1), visits, values });

interface Fake {
  engine: Engine;
  setups: Array<{ model?: string | null; sims?: number }>;
  chosen: Array<readonly number[]>;
  disposed: () => boolean;
  inits: () => number;
}

/** An engine whose choose is scripted by `script(history)`: a result, or a function that the test settles by hand. */
function fake(opts: {
  script?: (history: readonly number[], signal: AbortSignal | undefined) => ChooseResult | Promise<ChooseResult>;
  outcome?: (history: readonly number[]) => EngineOutcome;
  init?: () => Promise<void>;
  warnings?: EngineWarning[];
} = {}): Fake {
  const setups: Fake['setups'] = [];
  const chosen: Array<readonly number[]> = [];
  let disposed = false;
  let inits = 0;
  const engine = {
    warnings: opts.warnings ?? [],
    async init() { inits++; await opts.init?.(); return {} as never; },
    async newGame(setup: { model?: string | null; netOptions?: { sims: number } }) { setups.push({ model: setup.model, sims: setup.netOptions?.sims }); },
    async choose(history: readonly number[], signal?: AbortSignal) { chosen.push(history); return opts.script!(history, signal); },
    async outcome(history: readonly number[]) { return opts.outcome?.(history) ?? ({ kind: 'ongoing' } as EngineOutcome); },
    dispose() { disposed = true; },
  } as unknown as Engine;
  return { engine, setups, chosen, disposed: () => disposed, inits: () => inits };
}

/** An advisor whose factory hands out the given engines in order (main, then probe). */
function advisorOf(engines: Fake[], extra: { cacheLimit?: number } = {}) {
  let n = 0;
  const made: Fake[] = [];
  const advisor = new NetAdvisor({
    createEngine: () => { const f = engines[n++]!; made.push(f); return f.engine; },
    modelOf: () => 'model-a',
    ...extra,
  });
  const states: NetAdvisorState[] = [];
  advisor.subscribe(() => states.push(advisor.getState()));
  return { advisor, states, made, created: () => n };
}

/** The special move case: the net favours move 2 (fair classically), move 1 is best and has enough visits and a close value. */
const specialRoot = root([MIN_VISITS + 4, 100], [0.4, 0.5]);

describe('NetAdvisor: states and marks', () => {
  it('goes loading, thinking, marks on first use, with the models and simulations of the contract', async () => {
    let release!: () => void;
    const main = fake({ init: () => new Promise<void>((r) => { release = r; }), script: () => reply({ root: specialRoot }) });
    const { advisor, states } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    expect(advisor.getState().status).toBe('loading');
    release();
    await flush();
    expect(states.map((s) => s.status)).toEqual(['loading', 'thinking', 'marks']);
    const s = advisor.getState();
    expect(s.trap).toBeNull();
    expect(s.special).toMatchObject({ move: 2, classicalBand: 'equal' });
    expect(main.setups).toEqual([{ model: 'model-a', sims: ADVISOR_SIMS }]);
    expect(ADVISOR_SIMS).toBe(800);
    expect(PROBE_SIMS).toBe(100);
  });

  it('a second position on a loaded engine starts at thinking, not loading', async () => {
    const main = fake({ script: () => reply({ root: specialRoot }) });
    const { advisor, states } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    states.length = 0;
    advisor.request('cpp', [1], V, classical([BEST, FAIR]));
    await flush();
    expect(states.map((s) => s.status)).toEqual(['thinking', 'marks']);
    expect(main.inits()).toBe(1);
    expect(main.setups).toHaveLength(1);
  });

  it('says none when net and classical agree', async () => {
    const main = fake({ script: () => reply({ root: root([100, 10], [0.5, 0.4]) }) });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, TOP]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'none', trap: null, special: null });
  });

  it('a failing search is failed, not lost; the next position works again', async () => {
    let bad = true;
    const main = fake({ script: () => { if (bad) throw new Error('boom'); return reply({ root: specialRoot }); } });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: null });
    bad = false;
    advisor.request('cpp', [1], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState().status).toBe('marks');
  });

  it('a root without values is failed (an engine file older than the field)', async () => {
    const main = fake({ script: () => reply({ root: { moves: [1, 2], visits: [1, 2] } }) });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState().status).toBe('failed');
  });

  it('a failing init is lost for the session with the reason load-failed: the engine is disposed and later positions do not retry', async () => {
    const main = fake({ init: async () => { throw new Error('no gpu device'); }, script: () => reply({ root: specialRoot }) });
    const { advisor, created } = advisorOf([main, fake({ script: () => reply({ root: specialRoot }) })]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: 'load-failed' });
    expect(main.disposed()).toBe(true);
    expect(getNetLoss()).toBe('load-failed');
    advisor.request('cpp', [1], V, classical([BEST, FAIR]));
    advisor.request('cpp', [1, 2], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: 'load-failed' });
    expect(main.inits()).toBe(1);
    expect(created()).toBe(1);
  });

  it('a failing init of the probe engine is lost too, and that engine is disposed', async () => {
    const main = fake({ script: () => reply({ root: root([MIN_VISITS - 1, 100], [-0.9, 0.5]) }) });
    const probe = fake({ init: async () => { throw new Error('boom'); }, script: () => reply({}) });
    const { advisor } = advisorOf([main, probe]);
    advisor.request('cpp', [], V, classical([BEST, TOP]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: 'load-failed' });
    expect(probe.disposed()).toBe(true);
  });
});

describe('NetAdvisor: skipped without calling the net', () => {
  const cases: Array<[string, number[]]> = [
    ['one legal move', [BEST]],
    ['a race position', [SCORE.raceBase + 10, 0]],
    ['a forced win', [SCORE.mateBound + 5, 0]],
    ['a forced loss of every move (best is mate)', [-SCORE.mateBound - 5, -SCORE.mateBound - 6]],
  ];
  it.each(cases)('%s', async (_n, scores) => {
    const { advisor, created } = advisorOf([fake({ script: () => reply() })]);
    advisor.request('cpp', [], V, classical(scores));
    await flush();
    expect(advisor.getState().status).toBe('skipped');
    expect(created()).toBe(0);
  });
  it('the best score decides, not the first one', async () => {
    const main = fake({ script: () => reply({ root: root([100, 20], [0.5, 0.4]) }) });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([-SCORE.raceBase - 1, BEST]));
    await flush();
    expect(advisor.getState().status).toBe('marks'); // best is 100, ordinary; the favourite, move 1, is far worse classically
  });
  it('a score just under raceBase is ordinary', async () => {
    const main = fake({ script: () => reply({ root: root([100, 10], [0.5, 0.4]) }) });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([SCORE.raceBase - 1, 0]));
    await flush();
    expect(advisor.getState().status).toBe('none');
  });
});

describe('NetAdvisor: probes', () => {
  // best move 1 has few visits, favourite 2: probes of both decide
  const lowRoot = root([MIN_VISITS - 1, 100], [-0.9, 0.5]);
  const probeScripts = (childScores: Record<number, number>) =>
    fake({ script: (h) => reply({ score: childScores[h[h.length - 1]!]! }) });

  it('probes best move and favourite on the probe engine with the probe simulations; the value is minus the score of the child', async () => {
    // child of move 1: the opponent (to move there) is at +0.6, so move 1 is worth -0.6 for the mover; child of 2: -0.5 -> +0.5
    const main = fake({ script: () => reply({ root: lowRoot }) });
    const probe = probeScripts({ 1: 0.6, 2: -0.5 });
    const { advisor, created } = advisorOf([main, probe]);
    advisor.request('cpp', [], V, classical([BEST, TOP]));
    await flush();
    expect(created()).toBe(2);
    expect(probe.setups).toEqual([{ model: 'model-a', sims: PROBE_SIMS }]);
    expect(probe.chosen).toEqual([[1], [2]]);
    expect(main.chosen).toEqual([[]]);
    expect(advisor.getState().trap).toEqual({ move: 1, netValue: -0.6, favouriteValue: 0.5, classicalBand: 'much-better' });
    expect(advisor.getState().status).toBe('marks');
  });

  it('the sign: a child that is good for its side to move is bad for the mover (no trap when the signs would flip)', async () => {
    // moves 1 and 2 are both about equal for the mover: child scores -0.4 and -0.3 -> values 0.4 and 0.3
    const main = fake({ script: () => reply({ root: lowRoot }) });
    const probe = probeScripts({ 1: -0.4, 2: -0.3 });
    const { advisor } = advisorOf([main, probe]);
    advisor.request('cpp', [], V, classical([BEST, TOP]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'none', trap: null });
  });

  it('no probe engine is made when the root values suffice', async () => {
    const main = fake({ script: () => reply({ root: root([MIN_VISITS, 100], [-0.9, 0.5]) }) });
    const { advisor, created } = advisorOf([main, fake()]);
    advisor.request('cpp', [], V, classical([BEST, TOP]));
    await flush();
    expect(created()).toBe(1);
    expect(advisor.getState().trap?.netValue).toBe(-0.9);
  });

  it('a finished child needs no search: a win of the mover is +1', async () => {
    const main = fake({ script: () => reply({ root: lowRoot }) });
    // move 2 (the favourite) wins at once for seat 0 (the mover at history []); move 1 is worth -0.5
    const probe = fake({
      script: () => reply({ score: 0.5 }),
      outcome: (h) => (h[h.length - 1] === 2 ? { kind: 'win', winner: 0 } : { kind: 'ongoing' }),
    });
    const { advisor } = advisorOf([main, probe]);
    advisor.request('cpp', [], V, classical([BEST, TOP]));
    await flush();
    expect(probe.chosen).toEqual([[1]]); // no search of the finished child
    expect(advisor.getState().trap).toEqual({ move: 1, netValue: -0.5, favouriteValue: 1, classicalBand: 'much-better' });
  });

  it('a finished child that the mover lost is -1 (the winner is the other seat)', async () => {
    const main = fake({ script: () => reply({ root: lowRoot }) });
    const probe = fake({
      script: () => reply({ score: -0.2 }), // move 2: value 0.2
      outcome: (h) => (h[h.length - 1] === 1 ? { kind: 'win', winner: 1 } : { kind: 'ongoing' }),
    });
    const { advisor } = advisorOf([main, probe]);
    advisor.request('cpp', [], V, classical([BEST, TOP]));
    await flush();
    expect(probe.chosen).toEqual([[2]]);
    expect(advisor.getState().trap).toMatchObject({ move: 1, netValue: -1, favouriteValue: 0.2 });
  });

  it('the mover is the side at the end of the history (seat 1 after one move)', async () => {
    const main = fake({ script: () => reply({ root: lowRoot }) });
    const probe = fake({
      script: () => reply({ score: -0.2 }),
      outcome: (h) => (h[h.length - 1] === 1 ? { kind: 'win', winner: 1 } : { kind: 'ongoing' }),
    });
    const { advisor } = advisorOf([main, probe]);
    advisor.request('cpp', [9], V, classical([BEST, TOP]));
    await flush();
    // seat 1 moves now and wins with move 1: +1 for move 1, so no trap
    expect(advisor.getState()).toMatchObject({ status: 'none', trap: null });
  });

  describe('a child where the net does no search has no value (no 0 that looks like a value)', () => {
    // move 1 is the best classical move with few visits, favourite 2; both probed. Real child values: move 1 -> -0.9, move 2 -> +0.5.
    // A no-search answer for move 1 would read as 0, which is a trap (0 <= 0.5 - 0.25) that the net never said.
    const run = async (noSearch: Partial<Parameters<typeof reply>[0]>, favouriteNoSearch = false) => {
      const main = fake({ script: () => reply({ root: lowRoot }) });
      const probe = fake({
        script: (h) => {
          const mv = h[h.length - 1]!;
          const dead = mv === (favouriteNoSearch ? 2 : 1);
          return dead ? reply({ score: 0, ...noSearch }) : reply({ score: mv === 1 ? 0.9 : -0.5 });
        },
      });
      const { advisor } = advisorOf([main, probe]);
      advisor.request('cpp', [], V, classical([BEST, TOP]));
      await flush();
      return advisor.getState();
    };
    it('one legal move: sims 0 and no root gives no trap on the best move', async () => {
      expect(await run({ sims: 0, root: null })).toMatchObject({ status: 'none', trap: null });
    });
    it('a race move gives no trap', async () => {
      expect(await run({ raceMove: true })).toMatchObject({ status: 'none', trap: null });
    });
    it('a result without root gives no trap', async () => {
      expect(await run({ root: null })).toMatchObject({ status: 'none', trap: null });
    });
    it('sims 0 with a root gives no trap', async () => {
      expect(await run({ sims: 0 })).toMatchObject({ status: 'none', trap: null });
    });
    it('the favourite without a search does not hide a real trap as a value 0 either: still no trap decided', async () => {
      // favourite would read 0, best -0.9: 0 - 0.25 >= -0.9 would be a trap; with no probe for the favourite it is not decided
      expect(await run({ sims: 0, root: null }, true)).toMatchObject({ status: 'none', trap: null });
    });
    it('control: with real searches in both children the trap is found', async () => {
      const main = fake({ script: () => reply({ root: lowRoot }) });
      const probe = probeScripts({ 1: 0.9, 2: -0.5 });
      const { advisor } = advisorOf([main, probe]);
      advisor.request('cpp', [], V, classical([BEST, TOP]));
      await flush();
      expect(advisor.getState().trap).toMatchObject({ move: 1, netValue: -0.9, favouriteValue: 0.5 });
    });
  });

  it('a random backend in a probe is lost', async () => {
    const main = fake({ script: () => reply({ root: lowRoot }) });
    const probe = fake({ script: () => reply({ backend: 'random' }) });
    const { advisor } = advisorOf([main, probe]);
    advisor.request('cpp', [], V, classical([BEST, TOP]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: 'webgpu-lost' });
  });
});

describe('NetAdvisor: the net cannot run', () => {
  it('a random backend is failed and lost for the session; later requests call no engine', async () => {
    const main = fake({ script: () => reply({ backend: 'random' }) });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: 'webgpu-lost' });
    main.chosen.length = 0;
    advisor.request('cpp', [1], V, classical([BEST, FAIR]));
    await flush();
    expect(main.chosen).toEqual([]);
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: 'webgpu-lost' });
  });

  it('the reason of a backend-unavailable warning is the code', async () => {
    const main = fake({
      script: () => reply({ backend: 'wasm', root: specialRoot, warnings: [{ code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm', reason: 'webgpu-no-adapter' }] }),
    });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: 'webgpu-no-adapter' });
  });

  it('a backend-unavailable warning on the GPU backend still counts (a device lost during the move)', async () => {
    const main = fake({
      script: () => reply({ root: specialRoot, warnings: [{ code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm', reason: 'webgpu-lost' }] }),
    });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: 'webgpu-lost' });
  });

  it('warnings right after newGame stop the search before it starts', async () => {
    const main = fake({
      warnings: [{ code: 'backend-unavailable', backend: 'webgpu', fallback: 'random', reason: 'webgpu-device-failed' }, { code: 'fallback-random' }],
      script: () => reply({ root: specialRoot }),
    });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    expect(main.chosen).toEqual([]);
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: 'webgpu-device-failed' });
  });

  it('no model for the variant is a failure of the position, not a lost device, and no engine is made', async () => {
    let created = 0;
    const advisor = new NetAdvisor({ createEngine: () => { created++; return fake().engine; }, modelOf: () => null });
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'failed', lost: null });
    expect(created).toBe(0);
  });
});

describe('NetAdvisor: abort, cache, dispose', () => {
  it('a new request aborts the running search and its late result is ignored', async () => {
    const calls: Call[] = [];
    const main = fake({
      script: (history, signal) => new Promise<ChooseResult>((resolve, reject) => {
        calls.push({ history, signal, resolve, reject });
        signal?.addEventListener('abort', () => reject(abortError()));
      }),
    });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    advisor.request('cpp', [1], V, classical([BEST, FAIR]));
    await flush();
    expect(calls[0]!.signal!.aborted).toBe(true);
    expect(calls).toHaveLength(2);
    calls[0]!.resolve(reply({ root: root([100, 10], [0.5, 0.4]) })); // late and ignored
    calls[1]!.resolve(reply({ root: specialRoot }));
    await flush();
    expect(advisor.getState()).toMatchObject({ status: 'marks', key: expect.stringContaining(':1') });
  });

  it('an aborted search does not become a failure', async () => {
    const main = fake({
      script: (_h, signal) => new Promise<ChooseResult>((_res, reject) => { signal?.addEventListener('abort', () => reject(abortError())); }),
    });
    const { advisor, states } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    advisor.cancel();
    await flush();
    expect(advisor.getState().status).toBe('idle');
    expect(states.some((s) => s.status === 'failed')).toBe(false);
  });

  it('the same position while it runs starts no second search', async () => {
    const calls: Call[] = [];
    const main = fake({ script: (history, signal) => new Promise<ChooseResult>((resolve, reject) => { calls.push({ history, signal, resolve, reject }); }) });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    expect(calls).toHaveLength(1);
    expect(calls[0]!.signal!.aborted).toBe(false);
  });

  it('a cached position answers at once without the engine', async () => {
    const main = fake({ script: () => reply({ root: specialRoot }) });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    advisor.request('cpp', [1], V, classical([BEST, FAIR]));
    await flush();
    main.chosen.length = 0;
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    expect(advisor.getState().status).toBe('marks'); // synchronously
    expect(main.chosen).toEqual([]);
  });

  it('the cache is per classical scope', async () => {
    const main = fake({ script: () => reply({ root: specialRoot }) });
    const { advisor } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    advisor.request('java', [], V, classical([BEST, FAIR]));
    expect(advisor.getState().status).toBe('thinking');
    await flush();
    expect(main.chosen).toHaveLength(2);
  });

  it('the cache drops the least recently used position at its limit', async () => {
    const main = fake({ script: () => reply({ root: specialRoot }) });
    const { advisor } = advisorOf([main], { cacheLimit: 2 });
    for (const h of [[1], [2], [3]]) { advisor.request('cpp', h, V, classical([BEST, FAIR])); await flush(); }
    main.chosen.length = 0;
    advisor.request('cpp', [3], V, classical([BEST, FAIR]));
    await flush();
    expect(main.chosen).toEqual([]);
    advisor.request('cpp', [2], V, classical([BEST, FAIR]));
    await flush();
    expect(main.chosen).toEqual([]);
    advisor.request('cpp', [1], V, classical([BEST, FAIR])); // evicted
    await flush();
    expect(main.chosen).toEqual([[1]]);
  });

  it('a hit refreshes the position: it survives the next insert', async () => {
    const main = fake({ script: () => reply({ root: specialRoot }) });
    const { advisor } = advisorOf([main], { cacheLimit: 2 });
    for (const h of [[1], [2]]) { advisor.request('cpp', h, V, classical([BEST, FAIR])); await flush(); }
    advisor.request('cpp', [1], V, classical([BEST, FAIR])); // hit, [1] is now the newest
    advisor.request('cpp', [3], V, classical([BEST, FAIR]));
    await flush(); // evicts [2]
    main.chosen.length = 0;
    advisor.request('cpp', [1], V, classical([BEST, FAIR]));
    await flush();
    expect(main.chosen).toEqual([]);
  });

  it('dispose aborts, disposes both engines and ends the work', async () => {
    const main = fake({ script: () => reply({ root: root([MIN_VISITS - 1, 100], [-0.9, 0.5]) }) });
    const probe = fake({ script: () => reply({ score: 0 }) });
    const { advisor } = advisorOf([main, probe]);
    advisor.request('cpp', [], V, classical([BEST, TOP]));
    await flush();
    advisor.dispose();
    expect(main.disposed()).toBe(true);
    expect(probe.disposed()).toBe(true);
    const before = advisor.getState();
    advisor.request('cpp', [1], V, classical([BEST, FAIR]));
    await flush();
    expect(advisor.getState()).toBe(before);
  });

  it('dispose while the search runs aborts it', async () => {
    const calls: Call[] = [];
    const main = fake({ script: (history, signal) => new Promise<ChooseResult>((resolve, reject) => { calls.push({ history, signal, resolve, reject }); }) });
    const { advisor, states } = advisorOf([main]);
    advisor.request('cpp', [], V, classical([BEST, FAIR]));
    await flush();
    advisor.dispose();
    expect(calls[0]!.signal!.aborted).toBe(true);
    calls[0]!.resolve(reply({ root: specialRoot }));
    await flush();
    expect(states.at(-1)!.status).toBe('thinking');
  });
});
