// React glue for the net hints: availability of the level "net", and the advisor's result for the current position as a NetHintsModel
// (net-hints-types.ts). The advisor (net-advisor.ts) does the work; this hook decides when it may run and what the screen shows.
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import type { Engine } from '../engines/engine';
import { listModels } from '../engines/net/model-manifest';
import { bestModel } from '../engines/net/selection';
import { createEngine as createRegistryEngine, describeEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import type { ProbeResult, ReasonCode } from '../engines/warnings';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Variant } from '../game/variant';
import type { GuideView } from './model';
import { NetAdvisor } from './net-advisor';
import type { NetAdvisorState } from './net-advisor';
import type { NetAvailability, NetHintState, NetHintsModel } from './net-hints-types';
import type { GuideLevel } from './preference';

/** The part of NetAdvisor that the hook uses (a test passes a fake). */
export type AdvisorLike = Pick<NetAdvisor, 'getState' | 'subscribe' | 'request' | 'cancel' | 'dispose' | 'keyFor'>;

export interface UseNetHintsInput {
  /** The level that works now (effectiveGuideLevel of the chosen level). The advisor runs at "net" only. */
  level: GuideLevel;
  variant: Variant;
  /** The human is to move and the game is on: the only time the advisor may run. */
  humanTurn: boolean;
  history: readonly number[];
  /** The classical analysis of the current position (useGuides.view); null while it is not ready. */
  classical: GuideView | null;
  /** Identifies the classical engine for the cache (useGuides' scope); null while there is none. */
  scope: string | null;
  /** Makes the net-webgpu engine, not yet initialised (see netEngineFactory); null when this build or browser cannot run it. */
  createEngine: (() => Engine) | null;
  /** The WebGPU probe. Default: isWebGpuAvailable, loaded on first use. */
  probeWebGpu?: () => Promise<ProbeResult>;
  /** Why no model may be used for the variant: a reason code, or null when there is one. Default: from models.json. */
  modelCheck?: (variant: Variant) => ReasonCode | null;
  /** Test hook: replaces the advisor. */
  makeAdvisor?: (createEngine: () => Engine) => AdvisorLike;
}

/** The net-webgpu engine of the app, for `createEngine` of the hook (the same lookup as Session.netSource, but for the GPU engine). */
export function netEngineFactory(manifest: EnginesManifest | null, baseUrl: string, variant: Variant = DEFAULT_VARIANT): (() => Engine) | null {
  if (!manifest) return null;
  const choice = describeEngines(manifest, variant).find((c) => c.info.id === 'net-webgpu');
  return choice?.available ? () => createRegistryEngine(choice, manifest, baseUrl) : null;
}

/** No model for the variant: `no-model-id` when models.json lists none, else `variant-not-supported`. */
export function defaultModelCheck(variant: Variant): ReasonCode | null {
  if (bestModel(variant) !== null) return null;
  return listModels().length === 0 ? 'no-model-id' : 'variant-not-supported';
}

const defaultProbe = (): Promise<ProbeResult> => import('../engines/net/webgpu-backend').then((m) => m.isWebGpuAvailable());
const noSubscribe = (): (() => void) => () => undefined;
const idleState: NetAdvisorState = { status: 'idle', key: null, trap: null, special: null, lost: null };

export function useNetHints(input: UseNetHintsInput): NetHintsModel {
  const { level, variant, humanTurn, history, classical, scope, createEngine } = input;
  const createRef = useRef(createEngine);
  createRef.current = createEngine;
  const makeRef = useRef(input.makeAdvisor);
  makeRef.current = input.makeAdvisor;

  // The advisor lives from mount to unmount. It is made in an effect, so that a remount (strict mode) gets a fresh one after dispose.
  const [advisor, setAdvisor] = useState<AdvisorLike | null>(null);
  useEffect(() => {
    const factory = (): Engine => {
      const make = createRef.current;
      if (!make) throw new Error('no net engine in this build');
      return make();
    };
    const a = makeRef.current ? makeRef.current(factory) : new NetAdvisor({ createEngine: factory });
    setAdvisor(a);
    return () => {
      a.dispose();
      setAdvisor(null);
    };
  }, []);
  const state = useSyncExternalStore(advisor?.subscribe ?? noSubscribe, advisor?.getState ?? (() => idleState));

  // The WebGPU probe, once, when the hook mounts.
  const [probe, setProbe] = useState<ProbeResult | null>(null);
  const probeRef = useRef(input.probeWebGpu);
  probeRef.current = input.probeWebGpu;
  useEffect(() => {
    let live = true;
    const run = probeRef.current ?? defaultProbe;
    run().then(
      (p) => { if (live) setProbe(p); },
      () => { if (live) setProbe({ available: false, reason: 'webgpu-no-api' }); },
    );
    return () => { live = false; };
  }, []);

  const modelCode = (input.modelCheck ?? defaultModelCheck)(variant);
  const lostCode = state.lost;

  const availability: NetAvailability = useMemo(() => {
    const lostInSession = lostCode !== null;
    const no = (reason: NetAvailability['reason']): NetAvailability => ({ available: false, reason, lostInSession });
    if (variant.players !== 2) return no({ kind: 'three-players' });
    if (lostCode !== null) return no({ kind: 'reason', code: lostCode });
    if (probe === null) return no({ kind: 'checking' });
    if (!probe.available) return no({ kind: 'reason', code: probe.reason ?? 'webgpu-device-failed' });
    if (modelCode !== null) return no({ kind: 'reason', code: modelCode });
    if (createEngine === null) return no({ kind: 'reason', code: 'webgpu-no-api' }); // the build ships no net-webgpu engine
    return { available: true, reason: null, lostInSession };
  }, [variant.players, lostCode, probe, modelCode, createEngine]);

  const active = level === 'net' && availability.available && humanTurn && classical !== null && scope !== null;

  useEffect(() => {
    if (!advisor) return;
    if (active && classical && scope !== null) {
      const moves = [...classical.scores.keys()];
      advisor.request(scope, history, variant, { moves, scores: moves.map((m) => classical.scores.get(m)!) });
    } else {
      advisor.cancel();
    }
  }, [advisor, active, classical, scope, history, variant]);

  const currentKey = advisor && scope !== null ? advisor.keyFor(scope, history, variant) : null;
  const here = currentKey !== null && state.key === currentKey;

  let shown: NetHintState;
  if (lostCode !== null && here) shown = 'unavailable'; // until the next position
  else if (level !== 'net' || !availability.available) shown = 'off';
  else if (!humanTurn || classical === null) shown = 'idle';
  else if (!here || state.status === 'idle') shown = 'thinking'; // the request of this position is about to start
  else shown = state.status;

  // `shown` is 'marks' only when the advisor's state belongs to this position (`here`), so stale marks never get out.
  const marks = shown === 'marks';
  return { availability, state: shown, trap: marks ? state.trap : null, special: marks ? state.special : null };
}
