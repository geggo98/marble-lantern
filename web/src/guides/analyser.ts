// Runs the guide analysis in the background: one position at a time, abortable, cached by engine and history.
// Framework-free; useGuides.ts subscribes to it. It never throws: every failure becomes a state.
import { GUIDE_NODES } from '../engines/analysis';
import type { AnalysisResult } from '../engines/engine';
import { buildView } from './model';
import type { GuideView } from './model';

export type AnalyseFn = (
  history: readonly number[],
  moves: readonly number[] | null,
  nodes: number,
  signal?: AbortSignal,
) => Promise<AnalysisResult>;

export type GuideStatus = 'idle' | 'analysing' | 'ready' | 'unavailable' | 'failed';

export interface GuideState {
  status: GuideStatus;
  /** The position that `view` or the running call belongs to. */
  key: string | null;
  view: GuideView | null;
}

export const guideKey = (scope: string, history: readonly number[]): string => `${scope}:${history.join(',')}`;

const isAbort = (e: unknown): boolean => (e as { name?: unknown } | null)?.name === 'AbortError';

export class GuideAnalyser {
  private state: GuideState = { status: 'idle', key: null, view: null };
  private readonly cache = new Map<string, GuideView>();
  private readonly failed = new Set<string>();
  /** Engines (scopes) that answered "unsupported". */
  private readonly unsupported = new Set<string>();
  private listeners = new Set<() => void>();
  private running: { key: string; controller: AbortController } | null = null;

  constructor(
    private readonly analyse: AnalyseFn,
    private readonly nodes: number = GUIDE_NODES,
    private readonly cacheLimit: number = 64,
  ) {}

  getState = (): GuideState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /** The cached view of a position, or null. Starts nothing. */
  peek(scope: string, history: readonly number[]): GuideView | null {
    return this.cache.get(guideKey(scope, history)) ?? null;
  }

  /** Wants the view of the position after `history`, for the engine `scope`. Cached, or computed in the background. */
  request(scope: string, history: readonly number[]): void {
    const key = guideKey(scope, history);
    if (this.running?.key === key) return;
    this.abortRunning();
    if (this.unsupported.has(scope)) {
      this.set({ status: 'unavailable', key, view: null });
      return;
    }
    const cached = this.cache.get(key);
    if (cached) {
      this.set({ status: 'ready', key, view: cached });
      return;
    }
    if (this.failed.has(key)) {
      this.set({ status: 'failed', key, view: null });
      return;
    }
    const controller = new AbortController();
    this.running = { key, controller };
    this.set({ status: 'analysing', key, view: null });
    let call: Promise<AnalysisResult>;
    try {
      call = this.analyse([...history], null, this.nodes, controller.signal);
    } catch (e) {
      this.running = null;
      this.fail(scope, key, e);
      return;
    }
    call.then(
      (result) => {
        if (this.running?.key !== key || controller.signal.aborted) return;
        this.running = null;
        try {
          const view = buildView(result);
          this.remember(key, view);
          this.set({ status: 'ready', key, view });
        } catch (e) {
          this.fail(scope, key, e);
        }
      },
      (e: unknown) => {
        if (this.running?.key !== key || controller.signal.aborted || isAbort(e)) return;
        this.running = null;
        this.fail(scope, key, e);
      },
    );
  }

  /** Aborts a running analysis, for example because the user moved or the engine takes over. */
  cancel(): void {
    if (!this.abortRunning()) return;
    this.set({ status: 'idle', key: null, view: null });
  }

  private abortRunning(): boolean {
    const running = this.running;
    if (!running) return false;
    this.running = null;
    running.controller.abort();
    return true;
  }

  private fail(scope: string, key: string, e: unknown): void {
    if ((e as { code?: unknown } | null)?.code === 'unsupported') {
      this.unsupported.add(scope);
      this.set({ status: 'unavailable', key, view: null });
      return;
    }
    this.failed.add(key);
    this.set({ status: 'failed', key, view: null });
  }

  private remember(key: string, view: GuideView): void {
    this.cache.set(key, view);
    while (this.cache.size > this.cacheLimit) {
      const oldest = this.cache.keys().next().value;
      if (oldest === undefined) break;
      this.cache.delete(oldest);
    }
  }

  private set(next: GuideState): void {
    this.state = next;
    for (const l of this.listeners) l();
  }
}
