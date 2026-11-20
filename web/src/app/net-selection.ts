// The selection of the net engines as state the UI binds to: a framework-free store (like EngineSession) that keeps the last
// selection in localStorage, the availability of the backends in this browser, and the React hook over both.
//
//   const net = useNetSelection(store, variant);
//   net.selection                 // { engine, modelId?, sims }; modelId absent = nothing chosen, null = "None"
//   net.setSelection({ modelId }) // merges, saves, notifies; a chosen model is also kept as the last model
//   net.evaluation                // evaluateSelection(selection, variant, availability, models, lastModel): warnings BEFORE the game starts
//   store.modelFor(variant)       // the model a net engine gets for that variant (resolveModel)
//   net.availability              // { wasm, webgpu: true | false | null (probe pending) }
//   net.probeWebGpu()             // asks the browser once (a real adapter and device request); also done when net-webgpu is selected
import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { DEFAULT_SELECTION, evaluateSelection, loadLastModel, loadSelection, resolveModel, saveLastModel, saveSelection } from '../engines/net/selection';
import { listModels } from '../engines/net/model-manifest';
import type { ModelInfo } from '../engines/net/model-manifest';
import type { NetAvailability, NetSelection, SelectionEvaluation } from '../engines/net/selection';
import type { ProbeResult } from '../engines/warnings';
import type { Variant } from '../game/variant';

export interface NetState {
  selection: NetSelection;
  availability: NetAvailability;
  /** The model chosen last (LAST_MODEL_KEY), if it is still in the manifest. */
  lastModel: string | null;
}

const cpuAvailable = (): boolean => typeof WebAssembly === 'object' && typeof Worker === 'function';

export class NetSelectionStore {
  private state: NetState;
  private listeners = new Set<() => void>();
  private probing: Promise<void> | null = null;
  private readonly storage: Pick<Storage, 'setItem'> | null;
  private readonly probe: () => Promise<ProbeResult>;
  /** The models of the manifest (models.json); a test hook replaces them. */
  readonly models: readonly ModelInfo[];

  /** `initial` and `lastModel` replace what storage holds, `probe` the WebGPU probe, `models` the manifest (all for tests). */
  constructor(options: {
    initial?: NetSelection;
    storage?: (Pick<Storage, 'getItem' | 'setItem'>) | null;
    probe?: NetSelectionStore['probe'];
    wasm?: boolean;
    lastModel?: string | null;
    models?: readonly ModelInfo[];
  } = {}) {
    this.models = options.models ?? listModels();
    const storage = options.storage === undefined ? undefined : options.storage;
    this.storage = storage === undefined ? browserStorage() : storage;
    const selection = options.initial ?? (storage === undefined ? loadSelection(undefined, this.models) : loadSelection(storage, this.models));
    const lastModel = options.lastModel !== undefined ? options.lastModel : loadLastModel(storage === undefined ? undefined : storage, this.models);
    this.state = { selection, availability: { wasm: options.wasm ?? cpuAvailable(), webgpu: null }, lastModel };
    // The probe code (webgpu-backend.ts) is not in the main bundle: it loads on the first probe.
    this.probe = options.probe ?? (() => import('../engines/net/webgpu-backend').then((m) => m.isWebGpuAvailable()));
  }

  getState = (): NetState => this.state;

  get selection(): NetSelection {
    return this.state.selection;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private set(next: NetState): void {
    this.state = next;
    for (const l of this.listeners) l();
  }

  /** Merges the change into the selection, saves it, and probes WebGPU if net-webgpu is now selected. A chosen model becomes the last model. */
  setSelection = (change: Partial<NetSelection>): void => {
    const selection = { ...this.state.selection, ...change };
    const lastModel = typeof change.modelId === 'string' ? change.modelId : this.state.lastModel;
    this.set({ ...this.state, selection, lastModel });
    saveSelection(selection, this.storage);
    if (typeof change.modelId === 'string') saveLastModel(change.modelId, this.storage);
    if (selection.engine === 'net-webgpu') void this.probeWebGpu();
  };

  /** Asks the browser whether WebGPU works, once (the probe caches). Never rejects. */
  probeWebGpu = (): Promise<void> => {
    this.probing ??= this.probe().then(
      (r) => this.set({ ...this.state, availability: { ...this.state.availability, webgpu: r.available, ...(r.reason ? { webgpuReason: r.reason } : {}), ...(r.detail ? { webgpuDetail: r.detail } : {}) } }),
      (e: unknown) => this.set({ ...this.state, availability: { ...this.state.availability, webgpu: false, webgpuReason: 'unknown', webgpuDetail: e instanceof Error ? e.message : String(e) } }),
    );
    return this.probing;
  };

  evaluate(variant: Variant): SelectionEvaluation {
    return evaluateSelection(this.state.selection, variant, this.state.availability, this.models, this.state.lastModel);
  }

  /** The model a net engine gets for a game of this variant (resolveModel): read at every newGame, so a new variant gets its own model. */
  modelFor = (variant: Variant): string | null => resolveModel(this.state.selection, variant, this.models, this.state.lastModel).modelId;
}

function browserStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

export interface UseNetSelection extends NetState {
  setSelection: (change: Partial<NetSelection>) => void;
  probeWebGpu: () => Promise<void>;
  evaluation: SelectionEvaluation;
  /** The models of the store (models.json, or the test hook). */
  models: readonly ModelInfo[];
}

/** The store as React state. When net-webgpu is the selected engine the hook probes WebGPU once. */
export function useNetSelection(store: NetSelectionStore, variant: Variant): UseNetSelection {
  const state = useSyncExternalStore(store.subscribe, store.getState, store.getState);
  useEffect(() => {
    if (state.selection.engine === 'net-webgpu') void store.probeWebGpu();
  }, [store, state.selection.engine]);
  const evaluation = evaluateSelection(state.selection, variant, state.availability, store.models, state.lastModel);
  const setSelection = useCallback((change: Partial<NetSelection>) => store.setSelection(change), [store]);
  return { ...state, setSelection, probeWebGpu: store.probeWebGpu, evaluation, models: store.models };
}

export { DEFAULT_SELECTION };
