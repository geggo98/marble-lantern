// The words of a live run for the figure's status line, and which side of the live switch the run state means. Shared by the live figures.
import type { Lookup } from '../figures/common';
import type { LiveRunState } from './useLiveRun';

/** The text of the status line (`kit.live.*`); empty while nothing runs. */
export function liveStatusText(kit: Lookup, state: LiveRunState<unknown, unknown>): string {
  switch (state.kind) {
    case 'idle':
      return '';
    case 'starting':
      return kit('kit.live.status.loading');
    case 'running':
      return kit('kit.live.status.running', { done: state.done, total: state.total });
    case 'done':
      return kit('kit.live.status.done');
    case 'stopped':
      return kit('kit.live.status.stopped');
    case 'failed':
      return kit(`kit.live.error.${state.code}`);
  }
}

/** My game is shown while a run starts, goes on or has finished; after Stop or a failure the figure is back at the example. */
export const liveValue = (state: LiveRunState<unknown, unknown>): 'example' | 'mine' =>
  state.kind === 'starting' || state.kind === 'running' || state.kind === 'done' ? 'mine' : 'example';
