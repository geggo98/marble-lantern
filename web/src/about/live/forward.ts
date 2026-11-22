// V9b live: the adapter from the net engine's `NetForwardReport` to the figure's `ForwardData`. One `netForward` call on the net Info engine
// gives the planes, the norm of every trunk layer, the policy and the values of the reader's position. The reference forward pass runs on
// the expert model of the board size (liveModelId); a model that cannot be used is a LiveError('model'), never a random move. Nothing here
// touches engine code.
import { replay } from '../../game/board-state';
import type { Variant } from '../../game/variant';
import type { Engine, NetForwardReport } from '../../engines/engine';
import { moveFrom, moveTo } from '../../engines/engine';
import mid from '../data/forward-mid.json';
import { STEP_IDS } from '../figures/forward';
import type { ForwardData } from '../figures/forward';
import { LiveError } from './engines';
import type { LiveEngines } from './engines';
import { liveModelId, modelError, startNet } from './mcts';
import type { LiveSnapshot } from './types';
import type { RunReport } from './useLiveRun';

const fail = (message: string): never => {
  throw new LiveError('internal', message);
};

const EXAMPLE_META = mid.meta as unknown as ForwardData['meta'];
const TRUNK = STEP_IDS.filter((id) => id !== 'input' && id !== 'policy' && id !== 'value');

/**
 * The figure's data for a position, from the engine's report. The plane names, beta and k are the constants of the model (the same in every
 * forward-<position>.json). Throws LiveError('internal') when the report does not fit the board or the figure (wrong sizes, other layers,
 * priors that do not add up to 1).
 */
export function buildForwardData(report: NetForwardReport, history: readonly number[], variant: Variant): ForwardData {
  const names = EXAMPLE_META.planeNames;
  if (report.planes.length !== names.length) fail(`${report.planes.length} planes instead of ${names.length}`);
  const cells = Array.from(replay(history, variant));
  if (report.planes.some((p) => p.length !== cells.length)) fail('a plane does not have one value per cell');
  const ids = report.layers.map((l) => l.id);
  if (ids.join() !== TRUNK.join()) fail(`layers ${ids.join(',')} instead of ${TRUNK.join(',')}`);
  if (report.layers.some((l) => l.norm.length !== cells.length)) fail('a layer does not have one norm per cell');
  if (report.values.length !== 2 || report.valuesNetOnly.length !== 2) fail('the values are not one per player');
  if (report.policy.length === 0) fail('no legal move in the report');
  for (const key of ['prior', 'priorNetOnly'] as const) {
    const sum = report.policy.reduce((a, m) => a + m[key], 0);
    if (Math.abs(sum - 1) > 1e-3) fail(`the ${key} values add up to ${sum}, not to 1`);
  }
  const mover = history.length % 2;
  return {
    meta: { ...EXAMPLE_META, position: 'mine', ply: history.length, sideToMove: mover, legalMoves: report.policy.length },
    cells,
    planes: report.planes,
    layers: report.layers,
    policy: report.policy.map((m) => ({ from: moveFrom(m.move), to: moveTo(m.move), prior: m.prior, priorNetOnly: m.priorNetOnly })),
    values: report.values,
    valuesNetOnly: report.valuesNetOnly,
  };
}

/** The live run of V9b: one `netForward` call on the net Info engine. */
export async function runForwardLive(engines: LiveEngines, snapshot: LiveSnapshot, signal: AbortSignal, report: RunReport<never>): Promise<ForwardData> {
  const engine: Engine = await engines.net(report.progress);
  signal.throwIfAborted();
  if (!engine.netForward) throw new LiveError('unsupported', 'this engine has no netForward export');
  report.step(0, 1);
  await startNet(engine, liveModelId(snapshot.variant), 1, snapshot.variant);
  signal.throwIfAborted();
  let result: NetForwardReport;
  try {
    result = await engine.netForward(snapshot.history, signal);
  } catch (e) {
    throw modelError(e);
  }
  signal.throwIfAborted();
  const data = buildForwardData(result, snapshot.history, snapshot.variant);
  report.step(1, 1);
  return data;
}
