// The live context of a topic page: the snapshot and sources the app handed over, and the engine holder. TopicPage provides it, the live figures read it.
import { createContext, useContext, useEffect, useMemo, useRef } from 'react';
import type { ReactNode } from 'react';
import { LiveEngines } from './engines';
import type { LiveInput } from './types';

export interface LiveContextValue {
  /** Null when no "Explain this position" link was pressed. */
  input: LiveInput | null;
  /** Null outside a topic page (a figure rendered alone, a test without a provider). */
  engines: LiveEngines | null;
}

const NONE: LiveContextValue = { input: null, engines: null };

const LiveContext = createContext<LiveContextValue>(NONE);

export const useLive = (): LiveContextValue => useContext(LiveContext);

/** One engine holder for the life of the mounted topic page; unmounting terminates its workers. The holder reads the newest `input` when it needs an engine. */
export function LiveProvider({ input, children }: { input: LiveInput | null; children: ReactNode }): React.JSX.Element {
  const latest = useRef(input);
  latest.current = input;
  const engines = useMemo(() => new LiveEngines(() => latest.current?.sources() ?? { classical: null, net: null }), []);
  useEffect(() => () => engines.dispose(), [engines]);
  const value = useMemo(() => ({ input, engines }), [input, engines]);
  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}
