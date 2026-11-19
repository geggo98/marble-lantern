import { describe, expect, it, vi } from 'vitest';
import type { AnalysisResult } from '../engines/engine';
import { SKILL_NODES } from '../engines/analysis';
import { SkillTracker } from './tracker';
import type { TrackedGame, TrackerState } from './tracker';

// Moves do not need to be legal here: the tracker only passes prefixes of the history to the engine.
interface Call { history: number[]; moves: readonly number[] | null; nodes: number; signal?: AbortSignal; resolve: (r: AnalysisResult) => void; reject: (e: unknown) => void }

function setup(history: number[], opts: { thinking?: boolean; engineTurn?: boolean; human?: 0 | 1 | null; quietMs?: number; legal?: number[] } = {}) {
  const calls: Call[] = [];
  const game = {
    state: { history, thinking: opts.thinking ?? false, outcome: null as unknown, legal: opts.legal ?? [1] },
    engineTurn: opts.engineTurn ?? false,
    listeners: new Set<() => void>(),
    subscribe(l: () => void) { this.listeners.add(l); return () => this.listeners.delete(l); },
    isEngineTurn() { return this.engineTurn; },
    emit() { for (const l of this.listeners) l(); },
  };
  const engine = {
    analyse(h: readonly number[], moves: readonly number[] | null, nodes: number, signal?: AbortSignal): Promise<AnalysisResult> {
      return new Promise((resolve, reject) => {
        calls.push({ history: [...h], moves, nodes, signal, resolve, reject });
        signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
    },
  };
  let ready = true;
  const tracker = new SkillTracker({
    engine, game: game as unknown as TrackedGame, humanSide: () => (opts.human === undefined ? 0 : opts.human), isReady: () => ready, quietMs: opts.quietMs ?? 0,
  });
  return { calls, game, tracker, setReady: (r: boolean) => { ready = r; } };
}

/** Scores for moves [10, 20, 30]: the best is 30 with 100. */
const result = (played: number, score: number): AnalysisResult => ({
  moves: [played, 999], scores: [score, 100], position: 100, nodes: 1, ms: 1,
});
const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 0));
// human side 0 moves at plies 0, 2, 4, ...; history [a, b, c] holds the human moves at ply 0 and 2

describe('SkillTracker', () => {
  it('analyses the human moves one at a time, newest first, at SKILL_NODES, with all legal moves', async () => {
    const { calls, tracker } = setup([1, 2, 3, 4, 5]); // human moved at plies 0, 2, 4
    tracker.pump();
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({ history: [1, 2, 3, 4], moves: null, nodes: SKILL_NODES });
    tracker.pump(); // a second pump does not start a second analysis
    expect(calls).toHaveLength(1);
    calls[0]!.resolve(result(5, 70));
    await flush();
    expect(calls).toHaveLength(2);
    expect(calls[1]!.history).toEqual([1, 2]);
  });

  it('startAt: moves before the swap are not the person\x27s; only later ones count', async () => {
    const { calls, tracker } = setup([1, 2, 3, 4, 5], { human: 1 }); // human side 1: plies 1 and 3
    tracker.startAt(4);
    tracker.pump();
    expect(calls).toHaveLength(0); // no human move at ply >= 4 on side 1
    tracker.startAt(0);
    tracker.pump();
    expect(calls[0]!.history).toEqual([1, 2, 3]); // newest human move first: ply 3
  });

  it('records the loss of the move played against the best score, and reports progress', async () => {
    const { calls, tracker } = setup([1, 2, 3]);
    let state: TrackerState = tracker.getState();
    tracker.subscribe(() => { state = tracker.getState(); });
    tracker.pump();
    expect(state).toMatchObject({ status: 'analysing', done: 0, total: 2 });
    calls[0]!.resolve(result(3, 60)); // best 100, played 60: loss 40
    await flush();
    expect(state.done).toBe(1);
    expect(state.losses).toEqual([40]);
    calls[1]!.resolve(result(1, 100));
    await flush();
    expect(state).toMatchObject({ status: 'idle', done: 2, total: 2, losses: [0, 40] });
  });

  it('does not analyse while the engine thinks or is on turn', () => {
    const a = setup([1, 2, 3], { thinking: true });
    a.tracker.pump();
    expect(a.calls).toHaveLength(0);
    const b = setup([1, 2], { engineTurn: true });
    b.tracker.pump();
    expect(b.calls).toHaveLength(0);
  });

  it('waits until the legal moves of the position are loaded (a request of its own must not queue behind an analysis)', () => {
    const { calls, tracker } = setup([1, 2, 3], { legal: [] });
    tracker.pump();
    expect(calls).toHaveLength(0);
  });

  it('waits for a quiet period after the last change of the game, then starts', () => {
    vi.useFakeTimers();
    try {
      const { calls, game, tracker } = setup([1, 2, 3], { quietMs: 800 });
      tracker.attach();
      game.emit(); // the game changed just now
      expect(calls).toHaveLength(0);
      vi.advanceTimersByTime(799);
      expect(calls).toHaveLength(0);
      game.emit(); // another change restarts the wait
      vi.advanceTimersByTime(799);
      expect(calls).toHaveLength(0);
      vi.advanceTimersByTime(2);
      expect(calls).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('an abort cancels the wait', () => {
    vi.useFakeTimers();
    try {
      const { calls, game, tracker } = setup([1, 2, 3], { quietMs: 800 });
      tracker.attach();
      game.emit();
      tracker.abort();
      vi.advanceTimersByTime(2000);
      expect(calls).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('does nothing before the engine is ready, and without a human side', () => {
    const a = setup([1, 2, 3]);
    a.setReady(false);
    a.tracker.pump();
    expect(a.calls).toHaveLength(0);
    const b = setup([1, 2, 3], { human: null });
    b.tracker.pump();
    expect(b.calls).toHaveLength(0);
  });

  it('aborts a running analysis when the engine starts thinking, and drops its result', async () => {
    const { calls, game, tracker } = setup([1, 2, 3]);
    tracker.attach();
    tracker.pump();
    game.state = { ...game.state, thinking: true };
    game.emit();
    expect(calls[0]!.signal?.aborted).toBe(true);
    await flush();
    expect(tracker.getState().done).toBe(0);
    expect(tracker.getState().status).toBe('idle');
  });

  it('aborts when the history no longer contains the analysed position (undo, new game)', async () => {
    const { calls, game, tracker } = setup([1, 2, 3]);
    tracker.attach();
    tracker.pump();
    game.state = { ...game.state, history: [] };
    game.emit();
    expect(calls[0]!.signal?.aborted).toBe(true);
  });

  it('a result that arrives after an abort is not cached', async () => {
    const { calls, tracker } = setup([1, 2, 3]);
    tracker.pump();
    tracker.abort();
    calls[0]!.resolve(result(3, 0)); // a late reply of an engine that ignores the signal
    await flush();
    expect(tracker.getState().done).toBe(0);
  });

  it('keeps results per position: the same moves are not analysed twice', async () => {
    const { calls, game, tracker } = setup([1, 2, 3]);
    tracker.attach();
    tracker.pump();
    calls[0]!.resolve(result(3, 60));
    await flush();
    calls[1]!.resolve(result(1, 100));
    await flush();
    expect(calls).toHaveLength(2);
    // one more human move: only it is new
    game.state = { ...game.state, history: [1, 2, 3, 4, 5] };
    game.emit();
    expect(calls).toHaveLength(3);
    expect(calls[2]!.history).toEqual([1, 2, 3, 4]);
    expect(tracker.getState()).toMatchObject({ done: 2, total: 3 });
  });

  it('keeps the cache over an abort, so that an engine switch does not repeat finished work', async () => {
    const { calls, tracker } = setup([1, 2, 3]);
    tracker.pump();
    calls[0]!.resolve(result(3, 60));
    await flush();
    tracker.abort(); // aborts the second analysis
    expect(tracker.getState().done).toBe(1);
    tracker.pump();
    expect(calls).toHaveLength(3);
    expect(calls[2]!.history).toEqual([]); // the first move: the one that is still missing
  });

  it('switches itself off for good on an engine that rejects analyse with code unsupported', async () => {
    const { calls, tracker } = setup([1, 2, 3]);
    tracker.pump();
    calls[0]!.reject(Object.assign(new Error('no analysis'), { code: 'unsupported' }));
    await flush();
    expect(tracker.getState().status).toBe('unsupported');
    tracker.pump();
    expect(calls).toHaveLength(1);
    tracker.reset(); // a different engine may support it
    tracker.pump();
    expect(calls).toHaveLength(2);
  });

  it('stops after another failure and reports it, instead of looping', async () => {
    const { calls, tracker } = setup([1, 2, 3]);
    tracker.pump();
    calls[0]!.reject(Object.assign(new Error('boom'), { code: 'internal' }));
    await flush();
    expect(tracker.getState().status).toBe('error');
    expect(calls).toHaveLength(1);
  });
});
