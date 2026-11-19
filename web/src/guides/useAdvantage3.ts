// React glue for the three-player advantage indicator: one static evaluation (Engine.evaluate3, no search, a few milliseconds)
// after every move of every seat. A newer position cancels the answer for an older one (the call itself cannot be aborted, its
// result is dropped). While the shown value belongs to an older position it is marked stale, like the two-player bar.
import { useEffect, useRef, useState } from 'react';
import { seatShares } from './advantage3';
import type { SeatShare } from './advantage3';

export type Evaluate3Fn = (history: readonly number[]) => Promise<number[]>;

export interface UseAdvantage3Input {
  evaluate3: Evaluate3Fn;
  /** Identifies the engine and the variant for the cache; null while no engine is ready. */
  scope: string | null;
  /** The user switched the guides on, in a three-player game that is not a demo. */
  enabled: boolean;
  history: readonly number[];
}

export interface Advantage3Model {
  /** The shares by seat; null before the first answer, when switched off, or when the engine has no static evaluation. */
  shares: SeatShare[] | null;
  /** True while the shown shares are older than the position. */
  stale: boolean;
}

export function useAdvantage3(input: UseAdvantage3Input): Advantage3Model {
  const { evaluate3, scope, enabled, history } = input;
  const evaluateRef = useRef(evaluate3);
  evaluateRef.current = evaluate3;
  const cache = useRef(new Map<string, SeatShare[]>());
  const [shown, setShown] = useState<{ key: string; shares: SeatShare[] } | null>(null);
  const key = scope === null ? null : `${scope}:${history.join(',')}`;

  useEffect(() => {
    if (!enabled || key === null) {
      setShown(null);
      return;
    }
    const cached = cache.current.get(key);
    if (cached) {
      setShown({ key, shares: cached });
      return;
    }
    let current = true; // false once the position or the switch changed: the answer is dropped
    evaluateRef.current(history).then(
      (values) => {
        if (!current) return;
        let shares: SeatShare[];
        try {
          shares = seatShares(values);
        } catch {
          setShown(null);
          return;
        }
        cache.current.set(key, shares);
        if (cache.current.size > 64) cache.current.delete(cache.current.keys().next().value!);
        setShown({ key, shares });
      },
      () => {
        if (current) setShown(null); // no static evaluation (unsupported engine) or a failure: no indicator, the game goes on
      },
    );
    return () => {
      current = false;
    };
    // `history` is covered by `key`
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, key]);

  return { shares: enabled && shown ? shown.shares : null, stale: shown !== null && shown.key !== key };
}
