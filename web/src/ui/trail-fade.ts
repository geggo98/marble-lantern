// The trail of the last move fades out after a time the viewer sets (docs/web-ui.md, "Fading move trail").
// The time is remembered in localStorage like the guides switch (guides/preference.ts); every access is guarded.
import { useEffect, useRef, useState } from 'react';
import type { KeyValueStore } from '../guides/preference';
import { prefersReducedMotion } from './primitives';

export const TRAIL_KEY = 'ml.trail-seconds';
/** Seconds the trail stays fully visible; 0 means "always visible" (the choice "Always show"; the slider itself runs from TRAIL_MIN_SECONDS). */
export const TRAIL_DEFAULT_SECONDS = 3;
export const TRAIL_MIN_SECONDS = 1;
export const TRAIL_MAX_SECONDS = 10;
/** Length of the opacity transition in ui.css (.ml-trail), in ms. */
export const TRAIL_FADE_MS = 600;

function defaultStore(): KeyValueStore | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

/** The stored whole number of seconds 0..10; the default for a missing, blocked or unknown value. */
export function loadTrailSeconds(store: KeyValueStore | null = defaultStore()): number {
  try {
    const raw = store?.getItem(TRAIL_KEY);
    if (raw == null || !/^\d{1,2}$/.test(raw)) return TRAIL_DEFAULT_SECONDS;
    const n = Number(raw);
    return n <= TRAIL_MAX_SECONDS ? n : TRAIL_DEFAULT_SECONDS;
  } catch {
    return TRAIL_DEFAULT_SECONDS;
  }
}

export function saveTrailSeconds(seconds: number, store: KeyValueStore | null = defaultStore()): void {
  try {
    store?.setItem(TRAIL_KEY, String(Math.min(TRAIL_MAX_SECONDS, Math.max(0, Math.round(seconds)))));
  } catch {
    // the choice then lasts for this page only
  }
}

/**
 * The last time of "Fade out" (1..10 s), kept apart from TRAIL_KEY: "Always show" stores 0 there, so "Fade out" again would lose the time.
 * A separate key survives the unmounting of the settings card (the menu sheet of a small screen) and a new session.
 */
export const TRAIL_LAST_KEY = 'ml.trail-last-seconds';

/** The remembered time of "Fade out", 1..10; the default for a missing, blocked or unknown value (0 is no time). */
export function loadLastTrailSeconds(store: KeyValueStore | null = defaultStore()): number {
  try {
    const raw = store?.getItem(TRAIL_LAST_KEY);
    if (raw == null || !/^\d{1,2}$/.test(raw)) return TRAIL_DEFAULT_SECONDS;
    const n = Number(raw);
    return n >= TRAIL_MIN_SECONDS && n <= TRAIL_MAX_SECONDS ? n : TRAIL_DEFAULT_SECONDS;
  } catch {
    return TRAIL_DEFAULT_SECONDS;
  }
}

/** Remembers a time of "Fade out"; a value outside 1..10 (0 means "always") is ignored. */
export function saveLastTrailSeconds(seconds: number, store: KeyValueStore | null = defaultStore()): void {
  if (!Number.isInteger(seconds) || seconds < TRAIL_MIN_SECONDS || seconds > TRAIL_MAX_SECONDS) return;
  try {
    store?.setItem(TRAIL_LAST_KEY, String(seconds));
  } catch {
    // the time is then remembered for this page only
  }
}

export type TrailPhase = 'shown' | 'fading' | 'gone';

/**
 * The phase of the trail. A move (plies grows by exactly one) and the first render start it: fully visible for `seconds`,
 * then `fading` for TRAIL_FADE_MS, then `gone`. Every other change of `plies` (undo, new game, navigation) clears it.
 * `seconds` 0 keeps it visible. With reduced motion there is no `fading` phase: the trail disappears after `seconds`.
 * The timers are cancelled when the move changes and on unmount.
 */
export function useTrailPhase(plies: number, seconds: number, reducedMotion: () => boolean = prefersReducedMotion): TrailPhase {
  const [phase, setPhase] = useState<TrailPhase>('shown');
  const previous = useRef<{ plies: number } | null>(null);
  const phaseRef = useRef<TrailPhase>('shown');

  useEffect(() => {
    const before = previous.current;
    previous.current = { plies };
    const timers: ReturnType<typeof setTimeout>[] = [];
    const clear = (): void => timers.forEach(clearTimeout);
    const set = (p: TrailPhase): void => {
      phaseRef.current = p;
      setPhase(p);
    };
    if (seconds === 0) {
      set('shown');
      return clear;
    }
    const moveChanged = before === null || before.plies !== plies;
    const grew = before === null || plies === before.plies + 1;
    if (moveChanged && !grew) {
      set('gone');
      return clear;
    }
    if (!moveChanged && phaseRef.current === 'gone') return clear; // only the setting changed and nothing is shown
    set('shown');
    const reduced = reducedMotion();
    timers.push(
      setTimeout(() => {
        if (reduced) set('gone');
        else {
          set('fading');
          timers.push(setTimeout(() => set('gone'), TRAIL_FADE_MS));
        }
      }, seconds * 1000),
    );
    return clear;
  }, [plies, seconds, reducedMotion]);

  return phase;
}
