// Tests of the net engine core with fakes: backend, model loader and facade. The rules of IC4 (warnings and the random fallback)
// live here; the real parts are in core.real.test.ts.
import { describe, expect, it } from 'vitest';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { Variant } from '../../game/variant';
import { BackendError } from './backend';
import type { NetBackend, NetOutputs } from './backend';
import { NetCore } from './core';
import type { BackendKind, NetCoreDeps } from './core';
import type { ModelInfo } from './models';
import type { WebNetModule } from './webnet.d';

const LEGAL = [10, 20, 30, 40];

function model(id: string, variants: Variant[], planeSet: ModelInfo['planeSet'] = 'classic1'): ModelInfo {
  return {
    id, labelKey: `model.${id}`, descriptionKey: `model.${id}.d`, variants, planes: 13, planeSet, formatVersion: 1, players: 2, params: 1,
    rawBytes: 1, packedBytes: 1, sha256Raw: '0'.repeat(64), sha256Packed: '0'.repeat(64), file: `${id}.safetensors.gz`, sourceSha256: '0'.repeat(64), createdFrom: 'test',
  };
}
const ADJ2 = DEFAULT_VARIANT as Variant;
const LONG2: Variant = { jump: 'long', players: 2 };
const EXPERT = model('expert', [ADJ2]);
const SHARED = model('shared', [ADJ2, LONG2], 'classic1v');

/** A scripted facade: `leaves` batches of `batch` positions per move, then best() answers `move`. */
class FakeWebNet {
  calls: string[] = [];
  created: Array<{ jump: string; players: string; sims: number; batch: number; seed: number }> = [];
  weightsInfo: Array<[string, number]> = [];
  evaluated: number[] = [];
  leaves = 2;
  private left = 0;
  beginError: string | null = null;
  move = 30;
  legal = LEGAL;
  create(jump: string, players: string, sims: number, batch: number, seed: number): string {
    this.calls.push('create');
    this.created.push({ jump, players, sims, batch, seed });
    return 'ok';
  }
  setWeightsInfo(planes: string, width: number): string {
    this.calls.push('setWeightsInfo');
    this.weightsInfo.push([planes, width]);
    return 'ok 1586 2';
  }
  setTable(): string { return 'ok 1'; }
  legalMoves(history: string): string {
    return history.split(',').filter(Boolean).length > 500 ? 'error illegal-history too long' : `ok ${this.legal.join(',')}`;
  }
  outcome(): string { return 'ok ongoing'; }
  begin(): string {
    this.calls.push('begin');
    if (this.beginError) return this.beginError;
    this.left = this.leaves;
    return `ok ${this.left > 0 ? 1 : 0}`;
  }
  prepare(): string { return 'ok 1'; }
  inputs(): Float32Array { return new Float32Array(1); }
  apply(): string {
    this.calls.push('apply');
    this.left--;
    return `ok ${this.left > 0 ? 1 : 0}`;
  }
  best(): string { return `ok ${this.move} 0.25 20 21 0 0 ${LEGAL.join(',')} 5,5,5,5`; }
}

class FakeBackend implements NetBackend {
  planes = 13;
  valueWidth = 2;
  loaded: Uint8Array[] = [];
  disposed = false;
  evaluations = 0;
  failEvaluateWith: Error | null = null;
  failLoadWith: Error | null = null;
  constructor(readonly kind: BackendKind) {}
  async load(weights: Uint8Array): Promise<void> {
    if (this.failLoadWith) throw this.failLoadWith;
    this.loaded.push(weights);
  }
  async evaluate(_inputs: Float32Array, batch: number): Promise<NetOutputs> {
    this.evaluations++;
    if (this.failEvaluateWith) throw this.failEvaluateWith;
    return { policyFrom: new Float32Array(batch * 121), policyTo: new Float32Array(batch * 121), values: new Float32Array(batch * 2) };
  }
  dispose(): void { this.disposed = true; }
}

interface Rig {
  web: FakeWebNet;
  backends: FakeBackend[];
  loads: string[];
  probes: boolean[];
  gpu: { available: boolean; reason?: string };
  createFails: Partial<Record<BackendKind, Error>>;
  loadFails: Map<string, Error>;
  deps: NetCoreDeps;
  randoms: number[];
  /** What the next backend of a kind does when created: mutate it (for example to fail evaluate). */
  onCreate: Partial<Record<BackendKind, (b: FakeBackend) => void>>;
}

function rig(models: ModelInfo[] = [EXPERT, SHARED]): Rig {
  const web = new FakeWebNet();
  const r: Rig = { web, backends: [], loads: [], probes: [], gpu: { available: true }, createFails: {}, loadFails: new Map(), randoms: [], onCreate: {}, deps: undefined as unknown as NetCoreDeps };
  r.deps = {
    webnet: web as unknown as WebNetModule,
    models,
    createBackend: async (kind) => {
      const fail = r.createFails[kind];
      if (fail) throw fail;
      const b = new FakeBackend(kind);
      r.onCreate[kind]?.(b);
      r.backends.push(b);
      return b;
    },
    probeWebGpu: async (fresh) => { r.probes.push(fresh); return r.gpu; },
    loadModel: async (info) => {
      r.loads.push(info.id);
      const e = r.loadFails.get(info.id);
      if (e) throw e;
      return new Uint8Array([info.id.length]);
    },
    random: () => r.randoms.shift() ?? 0,
    seed: () => 4242,
  };
  return r;
}

const codes = (w: ReadonlyArray<{ code: string }>): string[] => w.map((x) => x.code);

describe('NetCore: the search loop', () => {
  it('runs begin, then inputs/evaluate/apply until the search is done, and reports the facade move', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    expect(await core.newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 50 } })).toEqual([]);
    const res = await core.choose([1, 2]);
    expect(res.move).toBe(30);
    expect(r.backends).toHaveLength(1);
    expect(r.backends[0]!.evaluations).toBe(2);
    expect(r.web.calls.filter((c) => c === 'begin')).toHaveLength(1);
    expect(r.web.calls.filter((c) => c === 'apply')).toHaveLength(2);
    expect(res).toMatchObject({ score: 0.25, nodes: 21, depth: 0, raceMove: false, rows1Requests: 0, net: { backend: 'wasm', sims: 20, batches: 2 }, warnings: [] });
  });

  it('answers without a network evaluation when the facade has the move already (one legal move)', async () => {
    const r = rig();
    r.web.leaves = 0;
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const res = await core.choose([]);
    expect(res.net.batches).toBe(0);
    expect(r.backends[0]!.evaluations).toBe(0);
  });

  it('configures the facade with variant, simulations, batch and seed, and the plane set of the model', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], variant: LONG2, model: 'shared', netOptions: { sims: 800, batch: 4 } });
    expect(r.web.created).toEqual([{ jump: 'long', players: '2', sims: 800, batch: 4, seed: 4242 }]);
    expect(r.web.weightsInfo).toEqual([['classic1v', 2]]);
  });

  it('defaults: 200 simulations; batch 1 on the CPU, 4 on WebGPU and 8 for 800 simulations', async () => {
    const r = rig();
    const cpu = new NetCore('wasm', r.deps);
    await cpu.newGame({ nodes: [1, 1], model: 'expert' });
    expect(r.web.created.at(-1)).toMatchObject({ sims: 200, batch: 1 });
    const gpu = new NetCore('webgpu', r.deps);
    await gpu.newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 200 } });
    expect(r.web.created.at(-1)).toMatchObject({ sims: 200, batch: 4 });
    await gpu.newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 800 } });
    expect(r.web.created.at(-1)).toMatchObject({ sims: 800, batch: 8 });
  });

  it('clamps the batch to 1..16', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 50, batch: 99 } });
    expect(r.web.created.at(-1)!.batch).toBe(16);
    await core.newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 50, batch: 0 } });
    expect(r.web.created.at(-1)!.batch).toBe(1);
  });

  it('keeps the backend and the weights across games with the same model, and reloads for another model', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'shared' });
    await core.newGame({ nodes: [1, 1], model: 'shared', netOptions: { sims: 50 } });
    expect(r.loads).toEqual(['shared']);
    expect(r.backends).toHaveLength(1);
    expect(r.backends[0]!.loaded).toHaveLength(1);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    expect(r.loads).toEqual(['shared', 'expert']);
    expect(r.backends[0]!.loaded).toHaveLength(2);
  });

  it('stops between batches when the signal fires and rejects with AbortError', async () => {
    const r = rig();
    r.web.leaves = 5;
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const ctl = new AbortController();
    const backend = r.backends[0]!;
    const evaluate = backend.evaluate.bind(backend);
    backend.evaluate = async (i, b) => {
      const out = await evaluate(i, b);
      if (backend.evaluations === 2) ctl.abort();
      return out;
    };
    await expect(core.choose([], ctl.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(backend.evaluations).toBe(2); // not 5: the loop stopped after the second batch
  });

  it('rejects at once for a signal that is already aborted', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const ctl = new AbortController();
    ctl.abort();
    await expect(core.choose([], ctl.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(r.web.calls).not.toContain('begin');
  });

  it('yields to the event loop only after a move has run for a while', async () => {
    const r = rig();
    r.web.leaves = 4;
    let t = 0;
    let yields = 0;
    const core = new NetCore('wasm', { ...r.deps, now: () => (t += 30), yieldNow: async () => { yields++; } });
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    await core.choose([]);
    expect(yields).toBeGreaterThan(0);
    const quick = new NetCore('wasm', { ...r.deps, now: () => 0, yieldNow: async () => { yields = -100; } });
    await quick.newGame({ nodes: [1, 1], model: 'expert' });
    await quick.choose([]);
    expect(yields).toBeGreaterThan(-100);
  });

  it('refuses analysis with the code unsupported, and choose before newGame with internal', async () => {
    const core = new NetCore('wasm', rig().deps);
    expect(() => core.analyse()).toThrowError(expect.objectContaining({ code: 'unsupported' }));
    await expect(core.choose([])).rejects.toMatchObject({ code: 'internal' });
  });

  it('answers legalMoves and outcome without a model and without newGame', () => {
    const core = new NetCore('wasm', rig().deps);
    expect(core.legalMoves([1])).toEqual(LEGAL);
    expect(core.outcome([])).toEqual({ kind: 'ongoing' });
  });

  it('maps an illegal history of the facade to the protocol code', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    r.web.beginError = 'error illegal-history the game is over';
    await expect(core.choose([])).rejects.toMatchObject({ code: 'illegal-history' });
    expect(() => core.legalMoves(Array(600).fill(1))).toThrowError(expect.objectContaining({ code: 'illegal-history' }));
  });
});

describe('NetCore: fallback to random legal moves (IC4)', () => {
  it('no model: no-model and fallback-random, and a random legal move picked by the injected source', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    const w = await core.newGame({ nodes: [1, 1] });
    expect(codes(w)).toEqual(['no-model', 'fallback-random']);
    expect(w[0]).toMatchObject({ variant: ADJ2 });
    r.randoms.push(0.0, 0.26, 0.5, 0.99);
    const picks: number[] = [];
    for (let i = 0; i < 4; i++) picks.push((await core.choose([])).move);
    expect(picks).toEqual([10, 20, 30, 40]); // uniform over the four legal moves
    expect(r.backends).toHaveLength(0);
    expect(r.web.calls).not.toContain('begin');
    const last = await core.choose([]);
    expect(last.net).toEqual({ backend: 'random', sims: 0, batches: 0 });
    expect(codes(last.warnings)).toEqual(['no-model', 'fallback-random']);
  });

  it('an out-of-range random value (1) still gives a legal move', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1] });
    r.randoms.push(1);
    expect((await core.choose([])).move).toBe(40);
  });

  it('a game that is over has no random move: illegal-history', async () => {
    const r = rig();
    r.web.legal = [];
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1] });
    await expect(core.choose([])).rejects.toMatchObject({ code: 'illegal-history' });
  });

  it('unknown model id: model-load-failed with the id', async () => {
    const core = new NetCore('wasm', rig().deps);
    const w = await core.newGame({ nodes: [1, 1], model: 'ghost' });
    expect(codes(w)).toEqual(['model-load-failed', 'fallback-random']);
    expect(w[0]).toMatchObject({ modelId: 'ghost' });
  });

  it('a model that may not play the variant: model-variant-mismatch with model and variant, no download', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    const w = await core.newGame({ nodes: [1, 1], variant: LONG2, model: 'expert' });
    expect(codes(w)).toEqual(['model-variant-mismatch', 'fallback-random']);
    expect(w[0]).toMatchObject({ modelId: 'expert', variant: LONG2 });
    expect(r.loads).toEqual([]);
  });

  it('the model file fails to load: model-load-failed with the reason', async () => {
    const r = rig();
    r.loadFails.set('expert', new Error('sha256 differs'));
    const core = new NetCore('wasm', r.deps);
    const w = await core.newGame({ nodes: [1, 1], model: 'expert' });
    expect(codes(w)).toEqual(['model-load-failed', 'fallback-random']);
    expect(w[0]).toMatchObject({ modelId: 'expert', reason: 'sha256 differs' });
    expect((await core.choose([])).net.backend).toBe('random');
  });

  it('the backend rejects the weights: model-load-failed', async () => {
    const r = rig();
    r.onCreate.wasm = (b) => { b.failLoadWith = new BackendError('invalid-weights', 'not a net'); };
    const core = new NetCore('wasm', r.deps);
    const w = await core.newGame({ nodes: [1, 1], model: 'expert' });
    expect(codes(w)).toEqual(['model-load-failed', 'fallback-random']);
    expect(w[0]!.reason).toContain('not a net');
  });

  it('no CPU backend: backend-unavailable (fallback random) then fallback-random', async () => {
    const r = rig();
    r.createFails.wasm = new BackendError('unavailable', 'no flavour');
    const core = new NetCore('wasm', r.deps);
    const w = await core.newGame({ nodes: [1, 1], model: 'expert' });
    expect(codes(w)).toEqual(['backend-unavailable', 'fallback-random']);
    expect(w[0]).toMatchObject({ backend: 'wasm', fallback: 'random' });
  });

  it('the facade rejects planes or players at the first search: model-variant-mismatch and random moves from then on', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    r.web.beginError = 'error bad-weights-info the network has 3 players, the game 2';
    const res = await core.choose([]);
    expect(res.net.backend).toBe('random');
    expect(codes(res.warnings)).toEqual(['model-variant-mismatch', 'fallback-random']);
    expect(res.warnings[0]!.reason).toContain('3 players');
    r.web.beginError = null;
    expect((await core.choose([])).net.backend).toBe('random'); // stays random until the next newGame
    expect((await core.newGame({ nodes: [1, 1], model: 'expert' })).length).toBe(0); // and clears with it
  });

  it('the facade rejects the plane set at newGame: model-variant-mismatch', async () => {
    const r = rig();
    r.web.setWeightsInfo = () => 'error bad-weights-info value width 2 does not fit planes classic1v';
    const core = new NetCore('wasm', r.deps);
    const w = await core.newGame({ nodes: [1, 1], model: 'shared' });
    expect(codes(w)).toEqual(['model-variant-mismatch', 'fallback-random']);
  });

  it('warnings are copies: changing them does not change the engine', async () => {
    const core = new NetCore('wasm', rig().deps);
    await core.newGame({ nodes: [1, 1] });
    core.warnings[0]!.code = 'model-load-failed';
    expect(core.warnings[0]!.code).toBe('no-model');
  });

  it('a choose with another variant than newGame plans again for it, and the warnings follow', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const res = await core.choose([], undefined, LONG2);
    expect(res.net.backend).toBe('random');
    expect(codes(res.warnings)).toEqual(['model-variant-mismatch', 'fallback-random']);
    expect((await core.choose([], undefined, ADJ2)).net.backend).toBe('wasm');
  });
});

describe('NetCore: WebGPU', () => {
  it('net-webgpu with a valid model and WebGPU uses the GPU backend, batch 4, and no warning', async () => {
    const r = rig();
    const core = new NetCore('webgpu', r.deps);
    expect(await core.newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 200 } })).toEqual([]);
    expect(r.backends.map((b) => b.kind)).toEqual(['webgpu']);
    expect((await core.choose([])).net.backend).toBe('webgpu');
  });

  it('net-webgpu with a valid model but no WebGPU falls back to the CPU net and warns backend-unavailable only', async () => {
    const r = rig();
    r.gpu = { available: false, reason: 'no adapter' };
    const core = new NetCore('webgpu', r.deps);
    const w = await core.newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 200 } });
    expect(codes(w)).toEqual(['backend-unavailable']);
    expect(w[0]).toMatchObject({ backend: 'webgpu', fallback: 'wasm', reason: 'no adapter', modelId: 'expert' });
    expect(r.backends.map((b) => b.kind)).toEqual(['wasm']);
    expect(r.web.created.at(-1)!.batch).toBe(1); // the CPU batch, not the GPU one
    expect((await core.choose([])).net.backend).toBe('wasm');
  });

  it('net-webgpu without a model plays random, with no-model; the missing GPU is not blamed when no net would run anyway', async () => {
    const r = rig();
    r.gpu = { available: false, reason: 'no adapter' };
    const core = new NetCore('webgpu', r.deps);
    const w = await core.newGame({ nodes: [1, 1] });
    expect(codes(w)).toEqual(['no-model', 'fallback-random']);
    expect(r.probes).toEqual([]);
  });

  it('a GPU backend that cannot be created (device request fails) falls back to the CPU with backend-unavailable', async () => {
    const r = rig();
    r.createFails.webgpu = new BackendError('unavailable', 'requestDevice failed');
    const core = new NetCore('webgpu', r.deps);
    const w = await core.newGame({ nodes: [1, 1], model: 'expert' });
    expect(codes(w)).toEqual(['backend-unavailable']);
    expect(w[0]!.reason).toContain('requestDevice failed');
    expect((await core.choose([])).net.backend).toBe('wasm');
  });

  it('a model that does not fit the GPU limits falls back to the CPU', async () => {
    const r = rig();
    r.onCreate.webgpu = (b) => { b.failLoadWith = new BackendError('unsupported-model', 'too many buffers'); };
    const core = new NetCore('webgpu', r.deps);
    const w = await core.newGame({ nodes: [1, 1], model: 'expert' });
    expect(codes(w)).toEqual(['backend-unavailable']);
    expect(r.backends.map((b) => b.kind)).toEqual(['webgpu', 'wasm']);
    expect(r.backends[0]!.disposed).toBe(true);
  });

  it('device loss in the middle of a game: warns, goes on with the CPU backend, and asks the next newGame to probe again', async () => {
    const r = rig();
    r.onCreate.webgpu = (b) => { b.failEvaluateWith = new BackendError('device-lost', 'GPU reset'); };
    const core = new NetCore('webgpu', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const res = await core.choose([]);
    expect(res.net.backend).toBe('wasm');
    expect(res.move).toBe(30);
    expect(codes(res.warnings)).toEqual(['backend-unavailable']);
    expect(res.warnings[0]).toMatchObject({ backend: 'webgpu', fallback: 'wasm', reason: 'GPU reset' });
    expect(r.backends.map((b) => b.kind)).toEqual(['webgpu', 'wasm']);
    expect((await core.choose([])).warnings).toHaveLength(1); // the warning stays for the rest of the game
    r.onCreate.webgpu = () => undefined;
    expect(await core.newGame({ nodes: [1, 1], model: 'expert' })).toEqual([]); // fixed cause: the warning is gone
    expect(r.probes).toEqual([false, true]); // after a loss the probe asks the browser again
  });

  it('device loss without a loadable CPU fallback: random moves with both warnings', async () => {
    const r = rig();
    r.onCreate.webgpu = (b) => { b.failEvaluateWith = new BackendError('device-lost', 'GPU reset'); };
    r.createFails.wasm = new BackendError('unavailable', 'no flavour');
    const core = new NetCore('webgpu', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const res = await core.choose([]);
    expect(res.net.backend).toBe('random');
    expect(codes(res.warnings)).toEqual(['backend-unavailable', 'backend-unavailable', 'fallback-random']);
  });

  it('the CPU backend dying in the middle of a game gives random moves', async () => {
    const r = rig();
    r.onCreate.wasm = (b) => { b.failEvaluateWith = new BackendError('disposed', 'gone'); };
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const res = await core.choose([]);
    expect(res.net.backend).toBe('random');
    expect(codes(res.warnings)).toEqual(['backend-unavailable', 'fallback-random']);
    expect(res.warnings[0]).toMatchObject({ backend: 'wasm', fallback: 'random' });
  });

  it('an unexpected error of the facade is not swallowed as a fallback', async () => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    r.web.beginError = 'error non-finite NaN in the outputs';
    await expect(core.choose([])).rejects.toMatchObject({ code: 'internal' });
  });
});

describe('NetCore: errors of a backend that are not typed backend errors (F1)', () => {
  const trap = (): Error => Object.assign(new Error('unreachable executed'), { name: 'RuntimeError' });
  const lost = (): Error => new DOMException('Device is lost', 'AbortError');

  it('a wasm trap in evaluate degrades to random moves with the warnings, no rejection', async () => {
    const r = rig();
    r.onCreate.wasm = (b) => { b.failEvaluateWith = trap(); };
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const res = await core.choose([], new AbortController().signal);
    expect(res.net.backend).toBe('random');
    expect(LEGAL).toContain(res.move);
    expect(codes(res.warnings)).toEqual(['backend-unavailable', 'fallback-random']);
    expect(res.warnings[0]!.reason).toContain('unreachable executed');
  });

  it('a DOMException AbortError of the GPU while the signal is not aborted falls back to the CPU net', async () => {
    const r = rig();
    r.onCreate.webgpu = (b) => { b.failEvaluateWith = lost(); };
    const core = new NetCore('webgpu', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const res = await core.choose([], new AbortController().signal);
    expect(res.net.backend).toBe('wasm');
    expect(res.move).toBe(30);
    expect(codes(res.warnings)).toEqual(['backend-unavailable']);
    expect(res.warnings[0]).toMatchObject({ backend: 'webgpu', fallback: 'wasm' });
  });

  it('a plain RuntimeError of the GPU backend also falls back to the CPU net', async () => {
    const r = rig();
    r.onCreate.webgpu = (b) => { b.failEvaluateWith = trap(); };
    const core = new NetCore('webgpu', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    expect((await core.choose([])).net.backend).toBe('wasm');
  });

  it('a real abort still rejects with AbortError and does not degrade', async () => {
    const r = rig();
    const ac = new AbortController();
    r.onCreate.webgpu = (b) => {
      b.evaluate = async () => { ac.abort(); throw lost(); };
    };
    const core = new NetCore('webgpu', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    await expect(core.choose([], ac.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(core.warnings).toEqual([]);
    expect(r.backends).toHaveLength(1);
  });

  it('degrading keeps the warning of the plan (no GPU) next to the degradation', async () => {
    const r = rig();
    r.gpu = { available: false, reason: 'no adapter' };
    r.onCreate.wasm = (b) => { b.failEvaluateWith = trap(); };
    const core = new NetCore('webgpu', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert' });
    const res = await core.choose([]);
    expect(codes(res.warnings)).toEqual(['backend-unavailable', 'backend-unavailable', 'fallback-random']);
    expect(res.warnings.map((w) => w.backend)).toEqual(['webgpu', 'wasm', undefined]);
  });

  it('random mode and net mode report a finished game with the same text', async () => {
    const r = rig();
    r.web.legal = [];
    const rand = new NetCore('wasm', r.deps);
    await rand.newGame({ nodes: [1, 1] });
    const e1 = await rand.choose([1, 2, 3]).catch((e: unknown) => e as Error);
    const net = new NetCore('wasm', r.deps);
    await net.newGame({ nodes: [1, 1], model: 'expert' });
    r.web.beginError = 'error illegal-history the game is over after 3 plies';
    const e2 = await net.choose([1, 2, 3]).catch((e: unknown) => e as Error);
    expect((e1 as Error).message).toBe((e2 as Error).message);
  });
});

describe('NetCore: net options are validated (F2)', () => {
  it.each([NaN, 0, -5, Infinity, -Infinity, 2.5, '300' as unknown as number, null as unknown as number])('sims %s becomes the default 200, no throw', async (sims) => {
    const r = rig();
    const core = new NetCore('wasm', r.deps);
    await core.newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims } });
    expect(r.web.created.at(-1)!.sims).toBe(200);
  });

  it('keeps a valid sims value', async () => {
    const r = rig();
    await new NetCore('wasm', r.deps).newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 1 } });
    expect(r.web.created.at(-1)!.sims).toBe(1);
  });

  it.each([NaN, Infinity, -Infinity, '3' as unknown as number])('batch %s becomes the backend default (wasm 1, webgpu 4)', async (batch) => {
    const a = rig();
    await new NetCore('wasm', a.deps).newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 200, batch } });
    expect(a.web.created.at(-1)!.batch).toBe(1);
    const b = rig();
    await new NetCore('webgpu', b.deps).newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 200, batch } });
    expect(b.web.created.at(-1)!.batch).toBe(4);
  });

  it('clamps finite batches to 1..16', async () => {
    for (const [given, want] of [[0, 1], [-5, 1], [99, 16], [7.9, 7]] as const) {
      const r = rig();
      await new NetCore('webgpu', r.deps).newGame({ nodes: [1, 1], model: 'expert', netOptions: { sims: 200, batch: given } });
      expect(r.web.created.at(-1)!.batch).toBe(want);
    }
  });
});
