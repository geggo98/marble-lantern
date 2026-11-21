// V10 live: the root of the reader's own position after k simulations of the net search, for k = 1 to 50.
// One `netForward` call gives the prior P and the value of the root. Then, for each k, `newGame({ model, netOptions: { sims: k, batch: 1 } })`
// and `choose(history)`: with batch 1 and no noise the search is deterministic, so the tree after k simulations equals the search with
// sims = k (the method of ExplainerDataTest). The visits and mean values of the root edges come from `ChooseResult.net.root`.
// Nothing here touches engine code. A net that cannot be used (no model, load failed, fallback) is a LiveError('model'), never a random move.
import type { Engine, NetForwardReport } from '../../engines/engine';
import { EngineFailure } from '../../engines/protocol';
import type { EngineWarning } from '../../engines/warnings';
import { LiveError } from './engines';
import example from '../data/mcts-mid.json';
import type { McstData, RootState } from '../figures/puct';

/** The search settings of the figure: the expert model of the example, batch 1, no noise. */
export const MCTS_SETTINGS = { batch: 1 } as const;
/** The simulations of the full run. */
export const SIMS = 50;

/**
 * The simulation counts that the live run computes (steps of the stepper). The measured time decides how many there are: the sum of the
 * counts is the number of forward passes (see `forwardPasses`). `DEFAULT_KS` is every k from 1 to 50.
 */
export const ALL_KS: readonly number[] = Array.from({ length: SIMS }, (_, i) => i + 1);
export const forwardPasses = (ks: readonly number[]): number => ks.reduce((a, b) => a + b, 0);

/** The root of the reader's position after each finished k. */
export interface McstLive {
  modelId: string;
  /** The side to move at the root (absolute player). */
  mover: 0 | 1;
  /** Root moves in the order of the search (`from | to << 8`). */
  moves: number[];
  /** Prior of each root move (softmax over the legal moves, with the classical prior), from the forward pass. */
  priors: number[];
  /** Value of the root for each absolute player, from the forward pass (with the value prior). */
  rootValues: number[];
  /** The finished simulation counts, ascending. */
  ks: number[];
  /** visits[i][e]: visits of root edge e after ks[i] simulations. */
  visits: number[][];
  /** values[i][e]: mean value of root edge e for the side that plays it after ks[i] simulations; undefined when the engine file is older than the field. */
  values: (number[] | undefined)[];
  /** The move the search chooses at the last finished k. */
  choice: number;
}

/** The part of the engine this adapter calls. */
export type McstEngine = Pick<Engine, 'newGame' | 'choose' | 'netForward' | 'warnings'>;

/** The expert model of the example: the live figures use the same model as the committed data. */
export const MODEL_ID: string = example.meta.model;

const isModelProblem = (warnings: readonly EngineWarning[]): boolean => warnings.some((w) => w.code === 'fallback-random' || w.code === 'model-load-failed' || w.code === 'no-model');

/**
 * newGame with the expert model; a net that cannot be used (the warnings name it: no model, load failed, fallback to random moves) is a
 * LiveError('model'). The net engine never fails newGame for this: it plays random moves and says so in the warnings.
 */
export async function startNet(engine: Pick<Engine, 'newGame' | 'warnings'>, modelId: string, sims: number): Promise<void> {
  await engine.newGame({ nodes: [1, 1], model: modelId, netOptions: { sims, batch: MCTS_SETTINGS.batch } });
  const warnings = engine.warnings ?? [];
  if (isModelProblem(warnings)) throw new LiveError(typeof navigator !== 'undefined' && navigator.onLine === false ? 'offline' : 'model', `the net cannot be used: ${warnings.map((w) => w.code).join(', ')}`);
}

/** A net engine failure of the model ("failed: model: ...", ChooseResult backend random, warnings) becomes the LiveError of the kit. */
export function modelError(e: unknown): unknown {
  if (e instanceof EngineFailure && /failed: model/.test(e.message)) return new LiveError('model', e.message);
  return e;
}

const fail = (message: string): never => {
  throw new LiveError('internal', message);
};

/**
 * Runs the steps. `onStep` gets the finished part after each k, so that the figure can fill the root table while the next k runs. `signal`
 * is passed to every engine call: an abort restarts the worker, so a Stop ends the run inside the current search.
 */
export async function runMcts(
  engine: McstEngine,
  history: readonly number[],
  modelId: string,
  ks: readonly number[],
  signal?: AbortSignal,
  onStep?: (partial: McstLive, done: number, total: number) => void,
): Promise<McstLive> {
  signal?.throwIfAborted();
  await startNet(engine, modelId, ks[0] ?? 1);
  if (!engine.netForward) throw new LiveError('unsupported', 'this engine has no netForward export');
  signal?.throwIfAborted();
  let forward: NetForwardReport;
  try {
    forward = await engine.netForward(history, signal);
  } catch (e) {
    throw modelError(e);
  }
  signal?.throwIfAborted();
  const mover: 0 | 1 = history.length % 2 === 1 ? 1 : 0;
  const out: McstLive = { modelId: forward.modelId, mover, moves: [], priors: [], rootValues: forward.values, ks: [], visits: [], values: [], choice: -1 };
  for (const [i, k] of ks.entries()) {
    signal?.throwIfAborted();
    await startNet(engine, modelId, k);
    signal?.throwIfAborted();
    const r = await engine.choose(history, signal);
    signal?.throwIfAborted();
    if (r.net?.backend === 'random' || r.net === undefined) throw new LiveError('model', 'the engine played a random move, not a search');
    const root = r.net.root ?? fail('the search reported no root');
    if (i === 0) {
      out.moves = [...root.moves];
      out.priors = root.moves.map((m) => forward.policy.find((p) => p.move === m)?.prior ?? fail(`root move ${m} is not a legal move of the forward pass`));
      if (forward.policy.length !== root.moves.length) fail(`${forward.policy.length} legal moves in the forward pass, ${root.moves.length} root moves`);
    } else if (root.moves.length !== out.moves.length || root.moves.some((m, e) => m !== out.moves[e])) {
      fail('the root moves changed between two searches');
    }
    const sum = root.visits.reduce((a, b) => a + b, 0);
    if (sum !== k) fail(`the root visits add up to ${sum}, not to ${k}`);
    out.ks.push(k);
    out.visits.push([...root.visits]);
    out.values.push(root.values ? [...root.values] : undefined);
    out.choice = r.move;
    onStep?.({ ...out, ks: [...out.ks], visits: [...out.visits], values: [...out.values] }, i + 1, ks.length);
  }
  return out;
}

/** What the figure's table functions read: the shape of `McstData` without the trace (`sims` empty). `meta` is the example's settings (fpu, c). */
export function liveTable(live: McstLive, meta: McstData['meta']): McstData {
  return {
    meta: { ...meta, rootMover: live.mover, sims: live.ks[live.ks.length - 1] ?? 0, choiceEdge: Math.max(0, live.moves.indexOf(live.choice)) },
    cells: [],
    root: { moves: live.moves.map((m) => [m & 0xff, m >>> 8] as [number, number]), priors: live.priors, values: live.rootValues },
    sims: [],
    final: [],
  };
}

/** The root state after step index `i` (0-based) of the run (-1: before the first simulation), in the form of `puct.rootState`. Unvisited edges have no sum. */
export function liveRootState(live: McstLive, i: number): RootState {
  const k = i < 0 ? 0 : (live.ks[i] ?? 0);
  const visits = live.visits[i] ?? live.moves.map(() => 0);
  const values = live.values[i];
  const sums = visits.map((v, e) => (values ? v * (values[e] ?? 0) : 0));
  const total = sums.reduce((a, b) => a + b, 0);
  return { k, visits, sums, mean: ((live.rootValues[live.mover] ?? 0) + total) / (1 + k) };
}
