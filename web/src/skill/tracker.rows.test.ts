// The skill tracker on the 5-row board: the estimate is on but uncalibrated, and the ledger is separate per (jump rule, house size):
// a loss measured with 10 pieces says nothing about 15 (and the position hash is made from the moves alone, so the same move list
// on the other board would collide without the separate ledger).
import { describe, expect, it } from 'vitest';
import type { AnalysisResult } from '../engines/engine';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Variant } from '../game/variant';
import { skillCalibrated, skillEnabled } from './estimate';
import { LEDGER_MAX, hashKey, listOf, parseLedger, serializeLedger } from './ledger';
import type { LedgerData, LedgerStore } from './ledger';
import { SkillTracker } from './tracker';
import type { TrackedGame } from './tracker';

const FIVE: Variant = { jump: 'adjacent', players: 2, rows: 5 };
const FIVE_LONG: Variant = { jump: 'long', players: 2, rows: 5 };
const FIVE_THREE: Variant = { jump: 'adjacent', players: 3, rows: 5 };

function setup(history: number[], variant: Variant, store: LedgerStore) {
  const calls: { variant: Variant | undefined; resolve: (r: AnalysisResult) => void }[] = [];
  const game = {
    state: { history, thinking: false, outcome: null as unknown, legal: [1], variant },
    listeners: new Set<() => void>(),
    subscribe(l: () => void) { this.listeners.add(l); return () => this.listeners.delete(l); },
    isEngineTurn: () => false,
    emit() { for (const l of this.listeners) l(); },
  };
  const engine = {
    analyse(_h: readonly number[], _m: readonly number[] | null, _n: number, _s?: AbortSignal, v?: Variant): Promise<AnalysisResult> {
      return new Promise((resolve) => void calls.push({ variant: v, resolve }));
    },
  };
  const tracker = new SkillTracker({ engine, game: game as unknown as TrackedGame, humanSide: () => 0, isReady: () => true, quietMs: 0, store });
  tracker.attach();
  return { calls, game, tracker };
}
const memoryStore = (initial?: LedgerData): LedgerStore & { saved: LedgerData[] } => {
  let data: LedgerData = initial ?? { paused: false, entries: [] };
  const saved: LedgerData[] = [];
  return { saved, load: () => data, save: (d) => { data = d; saved.push(d); } };
};
const scores = (played: number, score: number): AnalysisResult => ({ moves: [played, 999], scores: [score, 100], position: 100, nodes: 1, ms: 1 });
const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

describe('skill estimate on 5 rows', () => {
  it('is on for two players, off for three, and uncalibrated on 5 rows only', () => {
    expect(skillEnabled(FIVE)).toBe(true);
    expect(skillEnabled(FIVE_THREE)).toBe(false);
    expect(skillCalibrated(FIVE)).toBe(false);
    expect(skillCalibrated(FIVE_LONG)).toBe(false);
    expect(skillCalibrated(DEFAULT_VARIANT)).toBe(true);
    expect(skillCalibrated({ jump: 'adjacent', players: 2, rows: 4 })).toBe(true);
  });

  it('listOf gives each (jump, rows) its own list', () => {
    const names = [listOf('adjacent', 4), listOf('long', 4), listOf('adjacent', 5), listOf('long', 5)];
    expect(names).toEqual(['entries', 'long', 'five', 'fiveLong']);
    expect(new Set(names).size).toBe(4);
  });

  it('measures under the 5-row variant and keeps the loss in the five ledger only', async () => {
    const store = memoryStore();
    const { calls, tracker } = setup([1, 2, 3], FIVE, store);
    tracker.pump();
    expect(calls[0]!.variant).toEqual(FIVE);
    expect(tracker.getState()).toMatchObject({ enabled: true, calibrated: false, variant: FIVE });
    calls[0]!.resolve(scores(3, 60)); // loss 40
    await flush();
    calls[1]!.resolve(scores(1, 100)); // loss 0
    await flush();
    expect(tracker.getState().ledger).toEqual([40, 0]);
    const last = store.saved.at(-1)!;
    expect(last.entries).toEqual([]);
    expect(last.long).toBeUndefined();
    expect(last.five).toHaveLength(2);
  });

  it('the same move list on the 4-row board is measured again (the hash does not know the board)', async () => {
    const store = memoryStore();
    const five = setup([1, 2, 3], FIVE, store);
    five.tracker.pump();
    five.calls[0]!.resolve(scores(3, 60));
    await flush();
    const four = setup([1, 2, 3], DEFAULT_VARIANT, store);
    expect(four.tracker.getState().ledger).toEqual([]);
    four.tracker.pump();
    expect(four.calls).toHaveLength(1);
  });

  it('reports the new variant when only the house size changes and both ledgers are empty', () => {
    const { game, tracker } = setup([], DEFAULT_VARIANT, memoryStore());
    tracker.pump();
    expect(tracker.getState()).toMatchObject({ variant: DEFAULT_VARIANT, calibrated: true });
    game.state = { ...game.state, variant: FIVE };
    game.emit();
    expect(tracker.getState()).toMatchObject({ variant: FIVE, calibrated: false });
  });

  it('switches the ledger when only the house size changes, and "delete" clears all four', () => {
    const store = memoryStore({
      paused: false, entries: [[hashKey('a'), 1]], long: [[hashKey('b'), 2]], five: [[hashKey('c'), 3]], fiveLong: [[hashKey('d'), 4]],
    });
    const { game, tracker } = setup([], DEFAULT_VARIANT, store);
    expect(tracker.getState().ledger).toEqual([1]);
    for (const [variant, ledger] of [[FIVE, [3]], [FIVE_LONG, [4]], [{ jump: 'long', players: 2 } as Variant, [2]], [DEFAULT_VARIANT, [1]]] as const) {
      game.state = { ...game.state, variant };
      game.emit();
      expect(tracker.getState().ledger, JSON.stringify(variant)).toEqual(ledger);
    }
    tracker.clear();
    expect(store.saved.at(-1)).toEqual({ paused: false, entries: [] });
  });
});

describe('the ledger file with the 5-row lists', () => {
  const h = (s: string): string => hashKey(s);
  it('round trips both lists, writes none when empty, and reads an old file without them', () => {
    const data: LedgerData = { paused: false, entries: [[h('a'), 1]], five: [[h('b'), 2]], fiveLong: [[h('c'), 3]] };
    expect(parseLedger(serializeLedger(data))).toEqual(data);
    expect(serializeLedger({ paused: false, entries: [], five: [] })).not.toContain('five');
    expect(parseLedger(`{"v":1,"paused":false,"entries":[["${h('a')}",1]]}`)).toEqual({ paused: false, entries: [[h('a'), 1]] });
  });
  it('a damaged 5-row list voids the file, like a damaged long list', () => {
    expect(parseLedger(`{"v":1,"paused":false,"entries":[],"five":[["zz",1]]}`)).toEqual({ paused: false, entries: [] });
    expect(parseLedger(`{"v":1,"paused":false,"entries":[],"fiveLong":5}`)).toEqual({ paused: false, entries: [] });
  });
  it('cuts each list to LEDGER_MAX separately', () => {
    const many = Array.from({ length: LEDGER_MAX + 3 }, (_, i): [string, number] => [h(String(i)), i]);
    const back = parseLedger(serializeLedger({ paused: false, entries: many, five: many }));
    expect(back.entries).toHaveLength(LEDGER_MAX);
    expect(back.five).toHaveLength(LEDGER_MAX);
  });
});
