// React glue for the move guides: owns the analyser, asks for the position once per human turn, derives what the screen shows.
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { GuideAnalyser, guideKey } from './analyser';
import type { AnalyseFn, GuideStatus } from './analyser';
import { describeAdvantage } from './advantage';
import type { AdvantageInfo } from './advantage';
import type { GuideView } from './model';

export interface UseGuidesInput {
  /** The engine's analyse method (the switchable engine, so that a swap needs no new analyser). */
  analyse: AnalyseFn;
  /** Identifies the engine for the cache; null while no engine is ready. */
  scope: string | null;
  /** The user switched the guides on. */
  enabled: boolean;
  /** The human is to move, the engine is idle and the game is on: the only time an analysis may run. */
  humanTurn: boolean;
  history: readonly number[];
}

export interface GuidesModel {
  /** "off" when switched off; otherwise the state of the analysis for the current position. */
  status: GuideStatus | 'off';
  /** The view of the current position: only while it is the human's turn and the analysis is ready. */
  view: GuideView | null;
  /** The advantage seen from the human, after the latest move; null before the first analysis. */
  advantage: AdvantageInfo | null;
  /** True while the shown advantage is older than the position. */
  advantageStale: boolean;
  /** The human plays `move`: stops a running analysis and keeps the score of that move for the advantage bar. */
  noteMove: (move: number) => void;
}

export function useGuides(input: UseGuidesInput): GuidesModel {
  const { analyse, scope, enabled, humanTurn, history } = input;
  const analyseRef = useRef(analyse);
  analyseRef.current = analyse;
  const analyser = useMemo(() => new GuideAnalyser((...args) => analyseRef.current(...args)), []);
  const state = useSyncExternalStore(analyser.subscribe, analyser.getState);
  // The score of the position as the human sees it, and the position (key) it belongs to.
  const [shown, setShown] = useState<{ score: number; key: string } | null>(null);
  const previousLength = useRef(history.length);

  const active = enabled && humanTurn && scope !== null;
  const key = scope === null ? null : guideKey(scope, history);

  useEffect(() => {
    if (active && scope !== null) analyser.request(scope, history);
    else analyser.cancel();
  }, [analyser, active, scope, history]);

  useEffect(() => () => analyser.cancel(), [analyser]);

  const view = active && state.status === 'ready' && state.key === key ? state.view : null;

  // The advantage follows the analysis: the value of the position at the human's turn.
  useEffect(() => {
    if (view && key) setShown({ score: view.position, key });
  }, [view, key]);

  // A shorter history (undo, new game) or a switched-off feature makes the shown value wrong.
  useEffect(() => {
    if (!enabled || history.length < previousLength.current) setShown(null);
    previousLength.current = history.length;
  }, [enabled, history]);

  const noteMove = (move: number): void => {
    analyser.cancel();
    if (!enabled || scope === null) return;
    const score = analyser.peek(scope, history)?.scores.get(move);
    if (score !== undefined) setShown({ score, key: guideKey(scope, [...history, move]) });
  };

  const advantage = enabled && shown ? describeAdvantage(shown.score) : null;
  return {
    status: !enabled ? 'off' : state.status === 'ready' && !view ? 'idle' : state.status,
    view,
    advantage,
    advantageStale: shown !== null && shown.key !== key,
    noteMove,
  };
}
