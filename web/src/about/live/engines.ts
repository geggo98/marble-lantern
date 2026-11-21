// The Info engines: the engine workers that My-game mode starts. One holder per mounted topic page. Nothing here runs at page load:
// an engine is created and initialised on the first classical() or net() call, which a figure makes only when the reader presses the live switch.
import type { AnalysisSource } from '../../app/switchable-engine';
import type { Engine, Progress } from '../../engines/engine';
import { EngineFailure } from '../../engines/protocol';
import type { LiveSources } from './types';

/** Why a live run failed, as the suffix of the catalog key `kit.live.error.<code>`. */
export type LiveErrorCode = 'load' | 'offline' | 'model' | 'internal' | 'unsupported';

export class LiveError extends Error {
  readonly code: LiveErrorCode;
  constructor(code: LiveErrorCode, message: string) {
    super(message);
    this.name = 'LiveError';
    this.code = code;
  }
}

type Kind = 'classical' | 'net';

interface Slot {
  engine: Promise<Engine>;
  listeners: Set<(p: Progress) => void>;
}

const isOffline = (): boolean => typeof navigator !== 'undefined' && navigator.onLine === false;

export class LiveEngines {
  private readonly sources: () => LiveSources;
  private slots: Partial<Record<Kind, Slot>> = {};
  /** Counts the dispose() calls: an engine that finishes loading after a dispose is disposed at once. */
  private epoch = 0;
  /** The results of finished runs, keyed `<figure>:<snapshot id>` (useLiveRun). Survives dispose(): it holds data, not workers. */
  readonly results = new Map<string, unknown>();

  constructor(sources: () => LiveSources) {
    this.sources = sources;
  }

  /** Which engines have been requested so far (tests, and the figures' "nothing started yet" checks). */
  started(kind: Kind): boolean {
    return this.slots[kind] !== undefined;
  }

  /** The classical Info engine, created and initialised on the first call; later calls share it. Rejects with a LiveError. */
  classical(onProgress?: (p: Progress) => void): Promise<Engine> {
    return this.get('classical', onProgress);
  }

  /** The net Info engine (net-wasm), likewise. */
  net(onProgress?: (p: Progress) => void): Promise<Engine> {
    return this.get('net', onProgress);
  }

  /** Terminates the workers. The holder stays usable: a later call creates a new engine (React strict mode runs an effect's cleanup once). */
  dispose(): void {
    this.epoch++;
    const slots = this.slots;
    this.slots = {};
    for (const slot of Object.values(slots)) void slot?.engine.then((e) => e.dispose(), () => undefined);
  }

  private get(kind: Kind, onProgress?: (p: Progress) => void): Promise<Engine> {
    const existing = this.slots[kind];
    if (existing) {
      if (onProgress) existing.listeners.add(onProgress);
      return existing.engine;
    }
    const source: AnalysisSource | null = this.sources()[kind];
    if (!source) {
      return Promise.reject(new LiveError('load', `no ${kind} engine is available in this browser`));
    }
    const listeners = new Set<(p: Progress) => void>(onProgress ? [onProgress] : []);
    const mine = this.epoch;
    const slot = { listeners } as Slot;
    slot.engine = (async (): Promise<Engine> => {
      let created: Engine | undefined;
      try {
        created = source.create();
        await created.init((p) => listeners.forEach((l) => l(p)));
      } catch (e) {
        created?.dispose();
        if (this.slots[kind] === slot) delete this.slots[kind]; // a failed start is retried by the next call
        if (e instanceof EngineFailure && e.code === 'unsupported') throw new LiveError('unsupported', e.message);
        throw new LiveError(isOffline() ? 'offline' : 'load', e instanceof Error ? e.message : String(e));
      }
      if (mine !== this.epoch) {
        created.dispose(); // the page went away while the engine loaded
        throw new LiveError('internal', 'the topic page was closed');
      }
      return created;
    })();
    this.slots[kind] = slot;
    slot.engine.catch(() => undefined); // each caller sees the failure; this one only keeps it from being reported as unhandled
    return slot.engine;
  }
}
