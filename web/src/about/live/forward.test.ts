// The V9b adapter: a report built from the committed golden forward-mid.json must give back that golden (planes, layers, policy, values),
// a report that does not fit the figure is refused (internal), and the run reports a failed model as the model error.
import { describe, expect, it } from 'vitest';
import { makeMove } from '../../engines/engine';
import type { Engine, NetForwardReport } from '../../engines/engine';
import { EngineFailure } from '../../engines/protocol';
import { DEFAULT_VARIANT } from '../../game/variant';
import evalMid from '../data/eval-mid.json';
import golden from '../data/forward-mid.json';
import type { ForwardData } from '../figures/forward';
import { LiveEngines } from './engines';
import { buildForwardData, runForwardLive } from './forward';

const G = golden as unknown as ForwardData;
const HISTORY = (evalMid.history as { from: number; to: number }[]).map((m) => makeMove(m.from, m.to));

const reportOf = (g: ForwardData): NetForwardReport => ({
  modelId: 'small-2p-adjacent-expert',
  planes: g.planes.map((p) => [...p]),
  layers: g.layers.map((l) => ({ id: l.id, norm: [...l.norm] })),
  logitsNetOnly: { from: [], to: [] },
  policy: g.policy.map((m) => ({ move: makeMove(m.from, m.to), prior: m.prior, priorNetOnly: m.priorNetOnly })),
  values: [...g.values],
  valuesNetOnly: [...g.valuesNetOnly],
  canonicalRotated: false,
});

describe('buildForwardData', () => {
  it('gives back the golden of the example position: planes, layers, moves with both priors, values, side to move, number of legal moves', () => {
    const d = buildForwardData(reportOf(G), HISTORY, DEFAULT_VARIANT);
    expect(d.planes).toEqual(G.planes);
    expect(d.layers).toEqual(G.layers);
    expect(d.policy).toEqual(G.policy);
    expect(d.values).toEqual(G.values);
    expect(d.valuesNetOnly).toEqual(G.valuesNetOnly);
    expect(d.cells).toEqual(G.cells);
    expect(d.meta).toMatchObject({ position: 'mine', ply: 30, sideToMove: 0, legalMoves: 54, planeNames: G.meta.planeNames, beta: G.meta.beta, k: G.meta.k });
  });

  it('the side to move follows the ply: after an odd number of plies it is player 1', () => {
    const d = buildForwardData(reportOf(G), HISTORY.slice(0, 29), DEFAULT_VARIANT);
    expect(d.meta.sideToMove).toBe(1);
    expect(d.meta.ply).toBe(29);
  });

  it('refuses a report that does not fit: missing plane, other layer ids, short layer, priors that do not add up, no legal move', () => {
    const bad = (change: (r: NetForwardReport) => void): void => {
      const r = reportOf(G);
      change(r);
      expect(() => buildForwardData(r, HISTORY, DEFAULT_VARIANT)).toThrowError(expect.objectContaining({ code: 'internal' }));
    };
    bad((r) => r.planes.pop());
    bad((r) => (r.layers[1]!.id = 'block9'));
    bad((r) => r.layers[2]!.norm.pop());
    bad((r) => (r.policy[0]!.prior += 0.5));
    bad((r) => (r.policy = []));
    bad((r) => (r.values = [0.1]));
  });
});

describe('runForwardLive', () => {
  const snapshot = { id: 1, history: HISTORY, variant: DEFAULT_VARIANT, ply: 30, over: false };
  const holder = (engine: Partial<Engine>): LiveEngines =>
    new LiveEngines(() => ({ classical: null, net: { id: 'net-wasm', create: () => ({ init: async () => ({}), dispose: () => undefined, warnings: [], newGame: async () => undefined, ...engine }) as unknown as Engine } }));
  const noReport = { progress: () => undefined, step: () => undefined };

  it('one netForward call on the net engine after newGame with the expert model; the result is the figure data', async () => {
    let setup: unknown;
    const engines = holder({ newGame: async (s) => void (setup = s), netForward: async () => reportOf(G) });
    const d = await runForwardLive(engines, snapshot, new AbortController().signal, noReport);
    expect(setup).toMatchObject({ model: 'small-2p-adjacent-expert', netOptions: { batch: 1 } });
    expect(d.meta.ply).toBe(30);
    expect(d.planes).toEqual(G.planes);
  });

  it('a failed model is the model error and a fallback warning too; one legal move (unsupported) stays unsupported', async () => {
    const failed = holder({ netForward: async () => { throw new EngineFailure('internal', 'failed: model: model-load-failed'); } });
    await expect(runForwardLive(failed, snapshot, new AbortController().signal, noReport)).rejects.toMatchObject({ code: 'model' });
    const fallback = holder({ warnings: [{ code: 'fallback-random' }], netForward: async () => reportOf(G) });
    await expect(runForwardLive(fallback, snapshot, new AbortController().signal, noReport)).rejects.toMatchObject({ code: 'model' });
    const one = holder({ netForward: async () => { throw new EngineFailure('unsupported', 'one legal move'); } });
    await expect(runForwardLive(one, snapshot, new AbortController().signal, noReport)).rejects.toMatchObject({ code: 'unsupported' });
  });
});
