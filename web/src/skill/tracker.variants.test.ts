// The skill tracker per game variant: the ledger is kept per jump rule, three players are not measured, and the estimate for the
// long hop is flagged as not calibrated. Old ledgers (`ml.skill.v1` written before the variants) stay readable as adjacent.
import { describe, expect, it } from 'vitest';
import type { AnalysisResult } from '../engines/engine';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Variant } from '../game/variant';
import { CALIBRATION } from './data';
import { estimateForVariant, skillCalibrated, skillEnabled } from './estimate';
import { LEDGER_KEY, browserLedgerStore, hashKey, parseLedger, serializeLedger } from './ledger';
import type { LedgerData, LedgerStore } from './ledger';
import { SkillTracker } from './tracker';
import type { TrackedGame } from './tracker';

const LONG: Variant = { jump: 'long', players: 2 };
const THREE: Variant = { jump: 'adjacent', players: 3 };

interface Call { history: number[]; variant: Variant | undefined; resolve: (r: AnalysisResult) => void }

function setup(history: number[], variant: Variant | undefined, store?: LedgerStore) {
  const calls: Call[] = [];
  const game = {
    state: { history, thinking: false, outcome: null as unknown, legal: [1], ...(variant ? { variant } : {}) },
    listeners: new Set<() => void>(),
    subscribe(l: () => void) { this.listeners.add(l); return () => this.listeners.delete(l); },
    isEngineTurn: () => false,
    emit() { for (const l of this.listeners) l(); },
  };
  const engine = {
    analyse(h: readonly number[], _moves: readonly number[] | null, _nodes: number, signal?: AbortSignal, v?: Variant): Promise<AnalysisResult> {
      return new Promise((resolve, reject) => {
        calls.push({ history: [...h], variant: v, resolve });
        signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
    },
  };
  const tracker = new SkillTracker({
    engine, game: game as unknown as TrackedGame, humanSide: () => (variant?.players === 3 ? null : 0), isReady: () => true, quietMs: 0,
    ...(store ? { store } : {}),
  });
  tracker.attach();
  return { calls, game, tracker };
}
const scores = (played: number, score: number): AnalysisResult => ({ moves: [played, 999], scores: [score, 100], position: 100, nodes: 1, ms: 1 });
const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

const memoryStore = (initial?: LedgerData): LedgerStore & { saved: LedgerData[] } => {
  let data: LedgerData = initial ?? { paused: false, entries: [] };
  const saved: LedgerData[] = [];
  return { saved, load: () => data, save: (d) => { data = d; saved.push(d); } };
};

describe('SkillTracker and the game variant', () => {
  it('passes the variant to analyse: the long hop is analysed under the long rule', async () => {
    const { calls, tracker } = setup([1, 2, 3], LONG);
    tracker.pump();
    expect(calls).toHaveLength(1);
    expect(calls[0]!.variant).toEqual(LONG);
    expect(tracker.getState()).toMatchObject({ enabled: true, calibrated: false, variant: LONG });
  });

  it('the default variant is enabled and calibrated, with or without a variant in the game state', () => {
    for (const v of [undefined, DEFAULT_VARIANT]) {
      const { tracker } = setup([1, 2, 3], v);
      tracker.pump();
      expect(tracker.getState()).toMatchObject({ enabled: true, calibrated: true });
    }
  });

  it('three players: no analysis request, no total, no ledger, not enabled', async () => {
    const { calls, tracker } = setup([1, 2, 3, 4, 5, 6], THREE);
    tracker.pump();
    expect(calls).toHaveLength(0);
    expect(tracker.getState()).toMatchObject({ enabled: false, calibrated: false, total: 0, ledger: [], status: 'idle' });
  });

  it('keeps the losses per jump rule: a long-hop loss is not in the adjacent ledger and the other way round', async () => {
    const store = memoryStore();
    const long = setup([1, 2, 3], LONG, store);
    long.tracker.pump();
    long.calls[0]!.resolve(scores(3, 60)); // loss 40
    await flush();
    long.calls[1]!.resolve(scores(1, 100)); // loss 0
    await flush();
    expect(long.tracker.getState().ledger).toEqual([40, 0]); // the newest move is analysed first
    expect(store.saved.at(-1)).toMatchObject({ entries: [], long: [[expect.any(String), 40], [expect.any(String), 0]] });

    // another visit, the adjacent game: nothing is known for it
    const adjacent = setup([1, 2, 3], DEFAULT_VARIANT, store);
    expect(adjacent.tracker.getState().ledger).toEqual([]); // the constructor shows the adjacent ledger: empty
    adjacent.tracker.pump();
    expect(adjacent.calls).toHaveLength(1); // the same history is measured again, because its ledger is separate
    adjacent.calls[0]!.resolve(scores(3, 90)); // loss 10
    await flush();
    expect(adjacent.tracker.getState().ledger).toEqual([10]);
    // the long ledger is untouched
    expect(store.saved.at(-1)!.long).toHaveLength(2);
    expect(store.saved.at(-1)!.entries).toHaveLength(1);
  });

  it('switches the ledger with the variant of the game', async () => {
    const store = memoryStore({ paused: false, entries: [[hashKey('a'), 5]], long: [[hashKey('b'), 70], [hashKey('c'), 80]] });
    const { game, tracker } = setup([], DEFAULT_VARIANT, store);
    expect(tracker.getState().ledger).toEqual([5]);
    game.state = { ...game.state, variant: LONG };
    game.emit();
    expect(tracker.getState()).toMatchObject({ ledger: [70, 80], calibrated: false, variant: LONG });
    game.state = { ...game.state, variant: THREE };
    game.emit();
    expect(tracker.getState()).toMatchObject({ ledger: [], enabled: false });
    game.state = { ...game.state, variant: DEFAULT_VARIANT };
    game.emit();
    expect(tracker.getState()).toMatchObject({ ledger: [5], enabled: true, calibrated: true });
  });

  it('"delete measurements" clears every ledger, not only the one of the current variant', () => {
    const store = memoryStore({ paused: false, entries: [[hashKey('a'), 5]], long: [[hashKey('b'), 70]] });
    const { tracker } = setup([], LONG, store);
    tracker.clear();
    expect(store.saved.at(-1)).toEqual({ paused: false, entries: [] });
    expect(tracker.getState().ledger).toEqual([]);
  });

  it('the move of a long-hop game goes into the long ledger even if the variant changes while it is analysed', async () => {
    const store = memoryStore();
    const { calls, game, tracker } = setup([1, 2, 3], LONG, store);
    tracker.pump();
    game.state = { ...game.state, history: [], variant: DEFAULT_VARIANT };
    game.emit(); // the history no longer holds the position: the analysis is dropped
    calls[0]!.resolve(scores(3, 60));
    await flush();
    expect(store.saved.at(-1)?.long ?? []).toEqual([]);
    expect(tracker.getState().ledger).toEqual([]);
  });
});

describe('the ledger file', () => {
  it('reads a ledger written before the variants (no long list) as adjacent, and writes no long list when there is none', () => {
    const old = JSON.stringify({ v: 1, paused: false, entries: [[hashKey('a'), 5]] });
    expect(parseLedger(old)).toEqual({ paused: false, entries: [[hashKey('a'), 5]] });
    expect(serializeLedger({ paused: false, entries: [[hashKey('a'), 5]] })).toBe(old);
    expect(serializeLedger({ paused: false, entries: [[hashKey('a'), 5]], long: [] })).toBe(old);
  });

  it('round trips a long list, and an unreadable long list voids the file like any other damage', () => {
    const data: LedgerData = { paused: true, entries: [[hashKey('a'), 5]], long: [[hashKey('b'), 7.5]] };
    expect(parseLedger(serializeLedger(data))).toEqual(data);
    const bad = (long: unknown): string => JSON.stringify({ v: 1, paused: false, entries: [], long });
    for (const long of ['x', [['zz', 1]], [[hashKey('a'), -1]], [[hashKey('a')]], 5]) {
      expect(parseLedger(bad(long)), JSON.stringify(long)).toEqual({ paused: false, entries: [] });
    }
  });

  it('the browser store keeps the long list over a reload', () => {
    const m = new Map<string, string>();
    const storage = { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) } as unknown as Storage;
    browserLedgerStore(() => storage).save({ paused: false, entries: [], long: [[hashKey('b'), 7]] });
    expect(m.get(LEDGER_KEY)).toContain('"long"');
    expect(browserLedgerStore(() => storage).load()).toEqual({ paused: false, entries: [], long: [[hashKey('b'), 7]] });
  });
});

describe('estimateForVariant', () => {
  const losses = Array.from({ length: 20 }, (_, i) => (i % 5) * 4);

  it('is calibrated for the default variant, uncalibrated for the long hop, and absent for three players', () => {
    expect(skillEnabled(DEFAULT_VARIANT) && skillCalibrated(DEFAULT_VARIANT)).toBe(true);
    expect(skillEnabled(LONG)).toBe(true);
    expect(skillCalibrated(LONG)).toBe(false);
    expect(skillEnabled(THREE)).toBe(false);
    expect(skillEnabled({ jump: 'long', players: 3 })).toBe(false);
    const adjacent = estimateForVariant(losses, CALIBRATION, DEFAULT_VARIANT)!;
    const long = estimateForVariant(losses, CALIBRATION, LONG)!;
    expect(adjacent.calibrated).toBe(true);
    expect(long.calibrated).toBe(false);
    expect(adjacent.enough && long.enough).toBe(true);
    // the long-hop estimate uses the adjacent calibration for lack of a better one: the same numbers, flagged
    expect({ ...long, calibrated: true }).toEqual(adjacent);
    expect(estimateForVariant(losses, CALIBRATION, THREE)).toBeNull();
  });

  it('with too few moves there is no estimate for any variant, and still a flag', () => {
    const few = estimateForVariant([1, 2], CALIBRATION, LONG)!;
    expect(few).toMatchObject({ enough: false, calibrated: false });
  });
});
