// The selection of the net engines as state the UI binds to: a framework-free store (like EngineSession) that keeps the last
// selection in localStorage, the availability of the backends in this browser, and the React hook over both.
//
//   const net = useNetSelection(store, variant);
//   net.selection                 // { engine, modelId, sims }
//   net.setSelection({ modelId }) // merges, saves, notifies
//   net.evaluation                // evaluateSelection(selection, variant, availability): warnings BEFORE the game starts
//   net.availability              // { wasm, webgpu: true | false | null (probe pending) }
//   net.probeWebGpu()             // asks the browser once (a real adapter and device request); also done when net-webgpu is selected
import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { DEFAULT_SELECTION, evaluateSelection, loadSelection, saveSelection } from '../engines/net/selection';
import type { NetAvailability, NetSelection, SelectionEvaluation } from '../engines/net/selection';
import type { Variant } from '../game/variant';

export interface NetState {
  selection: NetSelection;
  availability: NetAvailability;
}

const cpuAvailable = (): boolean => typeof WebAssembly === 'object' && typeof Worker === 'function';

export class NetSelectionStore {
  private state: NetState;
  private listeners = new Set<() => void>();
  private probing: Promise<void> | null = null;
  private readonly storage: Pick<Storage, 'setItem'> | null;
  private readonly probe: () => Promise<{ available: boolean; reason?: string }>;

  /** `initial` replaces what storage holds (tests); `probe` replaces the WebGPU probe (tests). */
  constructor(options: { initial?: NetSelection; storage?: (Pick<Storage, 'getItem' | 'setItem'>) | null; probe?: NetSelectionStore['probe']; wasm?: boolean } = {}) {
    const storage = options.storage === undefined ? undefined : options.storage;
    this.storage = storage === undefined ? browserStorage() : storage;
    const selection = options.initial ?? (storage === undefined ? loadSelection() : loadSelection(storage));
    this.state = { selection, availability: { wasm: options.wasm ?? cpuAvailable(), webgpu: null } };
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

  /** Merges the change into the selection, saves it, and probes WebGPU if net-webgpu is now selected. */
  setSelection = (change: Partial<NetSelection>): void => {
    const selection = { ...this.state.selection, ...change };
    this.set({ ...this.state, selection });
    saveSelection(selection, this.storage);
    if (selection.engine === 'net-webgpu') void this.probeWebGpu();
  };

  /** Asks the browser whether WebGPU works, once (the probe caches). Never rejects. */
  probeWebGpu = (): Promise<void> => {
    this.probing ??= this.probe().then(
      (r) => this.set({ ...this.state, availability: { ...this.state.availability, webgpu: r.available, ...(r.reason ? { webgpuReason: r.reason } : {}) } }),
      (e: unknown) => this.set({ ...this.state, availability: { ...this.state.availability, webgpu: false, webgpuReason: e instanceof Error ? e.message : String(e) } }),
    );
    return this.probing;
  };

  evaluate(variant: Variant): SelectionEvaluation {
    return evaluateSelection(this.state.selection, variant, this.state.availability);
  }
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
}

/** The store as React state. When net-webgpu is the selected engine the hook probes WebGPU once. */
export function useNetSelection(store: NetSelectionStore, variant: Variant): UseNetSelection {
  const state = useSyncExternalStore(store.subscribe, store.getState, store.getState);
  useEffect(() => {
    if (state.selection.engine === 'net-webgpu') void store.probeWebGpu();
  }, [store, state.selection.engine]);
  const evaluation = evaluateSelection(state.selection, variant, state.availability);
  const setSelection = useCallback((change: Partial<NetSelection>) => store.setSelection(change), [store]);
  return { ...state, setSelection, probeWebGpu: store.probeWebGpu, evaluation };
}

export { DEFAULT_SELECTION };
