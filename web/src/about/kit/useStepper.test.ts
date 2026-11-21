// The stepper reducer (design notes, section 8 "Keys" and section 9 "Motion"): bounds, Home and End, playback, reduced motion.
import { describe, expect, it } from 'vitest';
import { initialStepper, stepperReducer } from './useStepper';
import type { StepperAction, StepperState } from './useStepper';

const run = (s: StepperState, ...actions: StepperAction[]): StepperState => actions.reduce(stepperReducer, s);
const at = (count: number, step: number, extra: Partial<StepperState> = {}): StepperState => ({ ...initialStepper(count, true, step), ...extra });

describe('stepperReducer', () => {
  it('starts idle at step 0 and stays inside 0..count', () => {
    const s = initialStepper(6);
    expect(s).toEqual({ step: 0, count: 6, playing: false, canPlay: true });
    expect(run(s, { type: 'back' }).step).toBe(0);
    expect(run(at(6, 5), { type: 'forward' }).step).toBe(6);
    expect(run(at(6, 6), { type: 'forward' }).step).toBe(6);
    expect(run(at(6, 1), { type: 'back' }).step).toBe(0);
  });

  it('clamps a step that is set from outside', () => {
    expect(run(at(6, 2), { type: 'set', step: 99 }).step).toBe(6);
    expect(run(at(6, 2), { type: 'set', step: -4 }).step).toBe(0);
  });

  it('Home goes to the first step, End to the last, reset to the idle state', () => {
    expect(run(at(6, 4), { type: 'home' }).step).toBe(1);
    expect(run(at(6, 2), { type: 'end' }).step).toBe(6);
    expect(run(at(6, 4), { type: 'reset' }).step).toBe(0);
    expect(run(at(0, 0), { type: 'home' }).step).toBe(0);
  });

  it('plays: a tick advances, the last step stops the playback and does not loop', () => {
    let s = run(at(3, 0), { type: 'play' });
    expect(s.playing).toBe(true);
    s = run(s, { type: 'tick' });
    expect(s).toMatchObject({ step: 1, playing: true });
    s = run(s, { type: 'tick' }, { type: 'tick' });
    expect(s).toMatchObject({ step: 3, playing: false });
    expect(run(s, { type: 'tick' })).toBe(s);
  });

  it('pressing play at the last step starts again from the idle state', () => {
    expect(run(at(3, 3), { type: 'play' })).toMatchObject({ step: 0, playing: true });
  });

  it('every action that moves the step by hand stops the playback', () => {
    const playing = (step: number): StepperState => at(6, step, { playing: true });
    for (const action of [{ type: 'back' }, { type: 'forward' }, { type: 'home' }, { type: 'end' }, { type: 'reset' }, { type: 'set', step: 2 }] as StepperAction[]) {
      expect(run(playing(3), action).playing, action.type).toBe(false);
    }
  });

  it('toggle plays and pauses', () => {
    const s = run(at(6, 2), { type: 'toggle' });
    expect(s.playing).toBe(true);
    expect(run(s, { type: 'toggle' }).playing).toBe(false);
  });

  it('under reduced motion (canPlay false) play and toggle do nothing, and a running playback stops', () => {
    const still = at(6, 2, { canPlay: false });
    expect(run(still, { type: 'play' })).toBe(still);
    expect(run(still, { type: 'toggle' })).toBe(still);
    expect(run(still, { type: 'forward' }).step).toBe(3); // back and forward still work
    expect(run(at(6, 2, { playing: true }), { type: 'canPlay', value: false })).toMatchObject({ canPlay: false, playing: false });
  });

  it('keeps the step inside a changed count and refuses to play with no steps', () => {
    expect(run(at(10, 8, { playing: true }), { type: 'count', value: 5 })).toMatchObject({ count: 5, step: 5, playing: false });
    expect(run(at(0, 0), { type: 'play' }).playing).toBe(false);
  });
});
