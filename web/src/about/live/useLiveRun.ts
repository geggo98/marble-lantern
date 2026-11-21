// The state machine of a live run: idle -> starting(progress) -> running(done, total) -> done(value) | failed(code) | stopped.
// Stop and unmount fire the AbortSignal (the engine client then restarts that worker). A finished result is kept in the engine holder
// per (figure, snapshot id), so switching Example -> My game -> Example -> My game does not compute again; a new snapshot id computes anew.
import { useCallback, useEffect, useRef, useState } from 'react';
import type { Progress } from '../../engines/engine';
import { EngineFailure } from '../../engines/protocol';
import { useLive } from './context';
import { LiveError } from './engines';
import type { LiveEngines, LiveErrorCode } from './engines';

export type LiveRunState<T, P = T> =
  | { kind: 'idle' }
  | { kind: 'starting'; progress: Progress | null }
  | { kind: 'running'; done: number; total: number; partial?: P }
  | { kind: 'done'; value: T }
  | { kind: 'failed'; code: LiveErrorCode }
  | { kind: 'stopped' };

/** What a run reports while it works. */
export interface RunReport<P> {
  /** Engine load progress (the holder's `classical(report.progress)`). */
  progress: (p: Progress) => void;
  /** `done` of `total` steps are finished; `partial` is what the figure may already draw. */
  step: (done: number, total: number, partial?: P) => void;
}

export type LiveRunFn<T, P> = (engines: LiveEngines, signal: AbortSignal, report: RunReport<P>) => Promise<T>;

export interface UseLiveRun<T, P> {
  state: LiveRunState<T, P>;
  /** Starts the run, or shows the cached result of this snapshot. Does nothing without a snapshot. */
  start: () => void;
  /** Aborts the run; the state becomes `stopped`. */
  stop: () => void;
  /** Back to idle (the figure shows its example again); aborts a run that is going on. */
  reset: () => void;
}

function codeOf(e: unknown): LiveErrorCode {
  if (e instanceof LiveError) return e.code;
  if (e instanceof EngineFailure && e.code === 'unsupported') return 'unsupported';
  return 'internal';
}

export function useLiveRun<T, P = T>(figure: string, run: LiveRunFn<T, P>): UseLiveRun<T, P> {
  const { input, engines } = useLive();
  const snapshotId = input?.snapshot.id ?? null;
  const [stored, setStored] = useState<{ id: number | null; state: LiveRunState<T, P> }>({ id: null, state: { kind: 'idle' } });
  const runRef = useRef(run);
  runRef.current = run;
  /** The run that may still write state: a stop, a reset, a new run and an unmount invalidate it. */
  const current = useRef<{ controller: AbortController } | null>(null);

  const cancel = useCallback((): void => {
    current.current?.controller.abort();
    current.current = null;
  }, []);

  // Unmount, and a new snapshot (the old run belongs to the old position): abort.
  useEffect(() => cancel, [snapshotId, cancel]);

  const start = useCallback((): void => {
    if (!engines || snapshotId === null) return;
    cancel();
    const id = snapshotId;
    const set = (state: LiveRunState<T, P>): void => setStored({ id, state });
    const key = `${figure}:${id}`;
    if (engines.results.has(key)) {
      set({ kind: 'done', value: engines.results.get(key) as T });
      return;
    }
    const controller = new AbortController();
    const mine = { controller };
    current.current = mine;
    const live = (): boolean => current.current === mine && !controller.signal.aborted;
    set({ kind: 'starting', progress: null });
    const report: RunReport<P> = {
      progress: (p) => {
        if (live()) set({ kind: 'starting', progress: p });
      },
      step: (done, total, partial) => {
        if (live()) set({ kind: 'running', done, total, ...(partial === undefined ? {} : { partial }) });
      },
    };
    Promise.resolve()
      .then(() => runRef.current(engines, controller.signal, report))
      .then(
        (value) => {
          if (!live()) return;
          engines.results.set(key, value);
          current.current = null;
          set({ kind: 'done', value });
        },
        (e: unknown) => {
          if (!live()) return; // a stopped or replaced run: its AbortError is not a failure
          current.current = null;
          set({ kind: 'failed', code: codeOf(e) });
        },
      );
  }, [engines, snapshotId, figure, cancel]);

  const stop = useCallback((): void => {
    if (!current.current) return;
    cancel();
    setStored({ id: snapshotId, state: { kind: 'stopped' } });
  }, [cancel, snapshotId]);

  const reset = useCallback((): void => {
    cancel();
    setStored({ id: snapshotId, state: { kind: 'idle' } });
  }, [cancel, snapshotId]);

  const state: LiveRunState<T, P> = stored.id === snapshotId ? stored.state : { kind: 'idle' };
  return { state, start, stop, reset };
}
