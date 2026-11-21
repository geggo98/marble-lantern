// The state of a figure stepper (design notes, section 8 "Keys" and section 9 "Motion").
// Steps run 0 to `count`: 0 is the idle state before the first step, 1 to `count` are the steps. "Home" and "End" go to step 1 and
// step `count`; "reset" goes to 0. Any action that changes the step by hand stops the playback. Playback stops at the last step and does
// not loop. Under reduced motion `canPlay` is false: "play" does nothing and the figure does not render the play button.
import { useEffect, useReducer } from 'react';
import { useMediaQuery } from '../../ui/useMedia';

export interface StepperState {
  readonly step: number;
  readonly count: number;
  readonly playing: boolean;
  readonly canPlay: boolean;
}

export type StepperAction =
  | { type: 'reset' }
  | { type: 'back' }
  | { type: 'forward' }
  | { type: 'home' }
  | { type: 'end' }
  | { type: 'set'; step: number }
  | { type: 'play' }
  | { type: 'pause' }
  | { type: 'toggle' }
  | { type: 'tick' }
  | { type: 'canPlay'; value: boolean }
  | { type: 'count'; value: number };

/** 1.2 s per step (design notes, section 9). */
export const PLAY_INTERVAL_MS = 1200;

export function initialStepper(count: number, canPlay = true, step = 0): StepperState {
  const n = Math.max(0, Math.floor(count));
  return { step: Math.min(n, Math.max(0, Math.floor(step))), count: n, playing: false, canPlay };
}

const stopAt = (s: StepperState, step: number): StepperState => ({ ...s, step: Math.min(s.count, Math.max(0, step)), playing: false });

export function stepperReducer(s: StepperState, action: StepperAction): StepperState {
  switch (action.type) {
    case 'reset':
      return stopAt(s, 0);
    case 'back':
      return stopAt(s, s.step - 1);
    case 'forward':
      return stopAt(s, s.step + 1);
    case 'home':
      return stopAt(s, s.count === 0 ? 0 : 1);
    case 'end':
      return stopAt(s, s.count);
    case 'set':
      return stopAt(s, action.step);
    case 'play':
      if (!s.canPlay || s.count === 0) return s;
      // At the last step "play" starts again from the idle state; it never loops by itself.
      return { ...s, step: s.step >= s.count ? 0 : s.step, playing: true };
    case 'pause':
      return s.playing ? { ...s, playing: false } : s;
    case 'toggle':
      return stepperReducer(s, { type: s.playing ? 'pause' : 'play' });
    case 'tick': {
      if (!s.playing) return s;
      const step = Math.min(s.count, s.step + 1);
      return { ...s, step, playing: step < s.count };
    }
    case 'canPlay':
      return { ...s, canPlay: action.value, playing: action.value ? s.playing : false };
    case 'count': {
      const count = Math.max(0, Math.floor(action.value));
      return count === s.count ? s : { ...s, count, step: Math.min(s.step, count), playing: s.playing && s.step < count };
    }
  }
}

export interface UseStepper {
  state: StepperState;
  dispatch: (action: StepperAction) => void;
}

/** The reducer plus the clock of the playback and the reduced-motion switch. `intervalMs` is a parameter for tests only. */
export function useStepper(count: number, intervalMs = PLAY_INTERVAL_MS): UseStepper {
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)', false);
  const [state, dispatch] = useReducer(stepperReducer, undefined, () => initialStepper(count, !reduced));
  useEffect(() => dispatch({ type: 'canPlay', value: !reduced }), [reduced]);
  useEffect(() => {
    if (!state.playing) return undefined;
    const id = setInterval(() => dispatch({ type: 'tick' }), intervalMs);
    return () => clearInterval(id);
  }, [state.playing, intervalMs]);
  useEffect(() => dispatch({ type: 'count', value: count }), [count]);
  return { state, dispatch };
}
