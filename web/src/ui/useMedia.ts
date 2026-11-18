// A media query as React state. Without matchMedia (tests, old browsers) the answer is the fallback.
import { useSyncExternalStore } from 'react';

/** The breakpoint of the play screen: from 60rem the side column stands beside the board; below it is a small screen. */
export const COMPACT_QUERY = '(max-width: 59.99rem)';

export function useMediaQuery(query: string, fallback: boolean): boolean {
  const subscribe = (listener: () => void): (() => void) => {
    try {
      const list = typeof matchMedia === 'function' ? matchMedia(query) : null;
      list?.addEventListener('change', listener);
      return () => list?.removeEventListener('change', listener);
    } catch {
      return () => undefined;
    }
  };
  const snapshot = (): boolean => {
    try {
      return typeof matchMedia === 'function' ? matchMedia(query).matches : fallback;
    } catch {
      return fallback;
    }
  };
  return useSyncExternalStore(subscribe, snapshot, () => fallback);
}

/** True from 60rem; true as well where the browser cannot tell. */
export const useWide = (): boolean => !useMediaQuery(COMPACT_QUERY, false);
