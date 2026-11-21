// The V10 adapter with a scripted net engine: the calls it makes (one newGame and one choose per k, sims = k, batch 1, the expert model),
// the partial results, the checks that refuse a result, the model error instead of a random move, and Stop.
import { describe, expect, it } from 'vitest';
import type { ChooseResult, GameSetup, NetForwardReport } from '../../engines/engine';
import { EngineFailure } from '../../engines/protocol';
import type { EngineWarning } from '../../engines/warnings';
import { LiveError } from './engines';
import { ALL_KS, MODEL_ID, forwardPasses, liveRootState, runMcts } from './mcts';
import type { McstEngine, McstLive } from './mcts';

/** Three legal moves. */
const MOVES = [10 | (20 << 8), 11 | (21 << 8), 12 | (22 << 8)];

const forward = (): NetForwardReport => ({
  modelId: MODEL_ID,
  planes: [],
  layers: [],
  logitsNetOnly: { from: [], to: [] },
  policy: MOVES.map((move, i) => ({ move, prior: [0.5, 0.3, 0.2][i]!, priorNetOnly: 1 / 3 })),
  values: [0.4, -0.4],
  valuesNetOnly: [0, 0],
  canonicalRotated: false,
});

interface Script {
  /** visits for k simulations; default: everything on the first move except one visit per other move once k >= 3. */
  visits?: (k: number) => number[];
  backend?: 'wasm' | 'random';
  warnings?: EngineWarning[];
  noValues?: boolean;
  forwardError?: Error;
  onChoose?: (k: number) => void;
}

function scripted(script: Script = {}): { engine: McstEngine; setups: GameSetup[]; calls: string[] } {
  const setups: GameSetup[] = [];
  const calls: string[] = [];
  let sims = 0;
  const engine: McstEngine = {
    warnings: script.warnings ?? [],
    newGame: async (setup) => {
      setups.push(setup);
      sims = setup.netOptions?.sims ?? 0;
      calls.push(`newGame ${sims}`);
    },
    netForward: async () => {
      calls.push('netForward');
      if (script.forwardError) throw script.forwardError;
      return forward();
    },
    choose: async (): Promise<ChooseResult> => {
      calls.push(`choose ${sims}`);
      script.onChoose?.(sims);
      const visits = script.visits ? script.visits(sims) : sims >= 3 ? [sims - 2, 1, 1] : [sims, 0, 0];
      return {
        move: MOVES[0]!, score: 0, depth: 0, nodes: sims, ms: 0, raceMove: false, rows1Requests: 0,
        net: { backend: script.backend ?? 'wasm', sims, batches: sims, root: { moves: [...MOVES], visits, ...(script.noValues ? {} : { values: [0.5, 0.2, -0.1] }) } },
      };
    },
  };
  return { engine, setups, calls };
}

describe('runMcts', () => {
  it('one newGame and one choose per k, with sims = k, batch 1 and the expert model; netForward once first', async () => {
    const { engine, setups, calls } = scripted();
    const live = await runMcts(engine, [], MODEL_ID, [1, 2, 3, 5]);
    expect(setups.map((s) => s.netOptions)).toEqual([1, 1, 2, 3, 5].map((sims) => ({ sims, batch: 1 })));
    expect(setups.every((s) => s.model === MODEL_ID)).toBe(true);
    expect(calls).toEqual(['newGame 1', 'netForward', 'newGame 1', 'choose 1', 'newGame 2', 'choose 2', 'newGame 3', 'choose 3', 'newGame 5', 'choose 5']);
    expect(live.ks).toEqual([1, 2, 3, 5]);
    expect(live.visits).toEqual([[1, 0, 0], [2, 0, 0], [1, 1, 1], [3, 1, 1]]);
    expect(live.priors).toEqual([0.5, 0.3, 0.2]);
    expect(live.rootValues).toEqual([0.4, -0.4]);
    expect(live.choice).toBe(MOVES[0]);
  });

  it('the full run is 50 steps and 1,275 forward passes', () => {
    expect(ALL_KS).toHaveLength(50);
    expect(forwardPasses(ALL_KS)).toBe(1275);
  });

  it('reports the finished part after every k, so the table can fill while the next k runs', async () => {
    const { engine } = scripted();
    const seen: { done: number; total: number; ks: number[] }[] = [];
    await runMcts(engine, [], MODEL_ID, [1, 2, 3], undefined, (partial, done, total) => seen.push({ done, total, ks: partial.ks }));
    expect(seen).toEqual([
      { done: 1, total: 3, ks: [1] },
      { done: 2, total: 3, ks: [1, 2] },
      { done: 3, total: 3, ks: [1, 2, 3] },
    ]);
  });

  it('a root whose visits do not add up to k is refused (internal), not shown', async () => {
    const { engine } = scripted({ visits: (k) => [k + 1, 0, 0] });
    await expect(runMcts(engine, [], MODEL_ID, [1, 2])).rejects.toMatchObject({ code: 'internal' });
  });

  it('root moves that are not the legal moves of the forward pass are refused', async () => {
    const { engine } = scripted();
    const orig = engine.choose;
    let n = 0;
    engine.choose = async (...a) => {
      const r = await orig(...a);
      return n++ === 0 ? { ...r, net: { ...r.net!, root: { ...r.net!.root!, moves: [MOVES[0]!, MOVES[1]!, 99] } } } : r;
    };
    await expect(runMcts(engine, [], MODEL_ID, [1])).rejects.toMatchObject({ code: 'internal' });
  });

  it('a net that falls back to random moves is the model error: by the warnings, by the backend, and by a failed forward pass', async () => {
    const fallback: EngineWarning = { code: 'fallback-random', modelId: MODEL_ID };
    await expect(runMcts(scripted({ warnings: [fallback] }).engine, [], MODEL_ID, [1])).rejects.toMatchObject({ code: 'model' });
    const w = scripted({ backend: 'random' });
    await expect(runMcts(w.engine, [], MODEL_ID, [1])).rejects.toMatchObject({ code: 'model' });
    const f = scripted({ forwardError: new EngineFailure('internal', 'internal: failed: model: no usable model (model-load-failed)') });
    const error = await runMcts(f.engine, [], MODEL_ID, [1]).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(LiveError);
    expect((error as LiveError).code).toBe('model');
    expect(f.calls.some((c) => c.startsWith('choose')), 'no search after a failed model').toBe(false);
  });

  it('an engine file without the values field gives values undefined (Q unavailable), not a failure', async () => {
    const { engine } = scripted({ noValues: true });
    const live = await runMcts(engine, [], MODEL_ID, [1, 2]);
    expect(live.values).toEqual([undefined, undefined]);
  });

  it('Stop: an abort between two k ends the run with the abort and starts no further search', async () => {
    const controller = new AbortController();
    const { engine, calls } = scripted({ onChoose: (k) => k === 2 && controller.abort() });
    await expect(runMcts(engine, [], MODEL_ID, [1, 2, 3, 4], controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(calls.filter((c) => c.startsWith('choose'))).toEqual(['choose 1', 'choose 2']);
  });
});

describe('liveRootState', () => {
  const live: McstLive = {
    modelId: MODEL_ID, mover: 0, moves: MOVES, priors: [0.5, 0.3, 0.2], rootValues: [0.4, -0.4],
    ks: [1, 4], visits: [[1, 0, 0], [2, 1, 1]], values: [[0.5, 0.2, -0.1], [0.5, 0.2, -0.1]], choice: MOVES[0]!,
  };
  it('sums the backed-up values per edge and the node mean as (value + all sums) / (1 + k)', () => {
    const s = liveRootState(live, 1);
    expect(s.k).toBe(4);
    expect(s.visits).toEqual([2, 1, 1]);
    expect(s.sums[0]).toBeCloseTo(1.0);
    expect(s.sums[1]).toBeCloseTo(0.2);
    expect(s.sums[2]).toBeCloseTo(-0.1);
    expect(s.mean).toBeCloseTo((0.4 + 1.0 + 0.2 - 0.1) / 5);
  });
  it('before the first simulation: no visits, the mean is the root value', () => {
    const s = liveRootState(live, -1);
    expect(s).toEqual({ k: 0, visits: [0, 0, 0], sums: [0, 0, 0], mean: 0.4 });
  });
});
