// React glue for the distance of the result: starts the estimate when a result is shown, aborts it when the result goes away
// (a new game, undo, the card is closed, the page unmounts), and never reports an error.
import { useEffect, useRef, useState } from 'react';
import type { DistanceEstimate } from './distance';

export type DistanceState =
  | { status: 'idle' }
  | { status: 'estimating' }
  | { status: 'done'; estimate: DistanceEstimate };

export type DistanceRun = (signal: AbortSignal) => Promise<DistanceEstimate | null>;

/**
 * `key` names the result (null: no result is shown, nothing runs). While the key stays, the estimate runs once; a new key or null
 * aborts it. A failure, an abort and a null answer all leave the state at `idle`: the card then shows no distance and no error.
 */
export function useDistanceEstimate(key: string | null, run: DistanceRun): DistanceState {
  const runRef = useRef(run);
  runRef.current = run;
  const [state, setState] = useState<{ key: string; value: DistanceState } | null>(null);

  useEffect(() => {
    if (key === null) return undefined;
    const controller = new AbortController();
    setState({ key, value: { status: 'estimating' } });
    runRef
      .current(controller.signal)
      .then((estimate) => {
        if (!controller.signal.aborted) setState({ key, value: estimate ? { status: 'done', estimate } : { status: 'idle' } });
      })
      .catch(() => {
        if (!controller.signal.aborted) setState({ key, value: { status: 'idle' } });
      });
    return () => controller.abort();
  }, [key]);

  if (key === null) return { status: 'idle' };
  return state?.key === key ? state.value : { status: 'estimating' }; // the effect has not run yet for this key
}
