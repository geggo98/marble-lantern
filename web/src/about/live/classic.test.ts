// Which games the live figures explain: the classical figures the classic game on 4 and 5 rows, the net figures on 4 rows only, because every
// shipped network was trained on the 4-row board.
import { describe, expect, it } from 'vitest';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { Variant } from '../../game/variant';
import type { AnalysisSource } from '../../app/switchable-engine';
import { isClassic, isClassicAnySize, liveBlockedReason } from './classic';
import type { LiveInput } from './types';

/** A stand-in source: liveBlockedReason only asks whether a net source exists, it never creates an engine. */
const SOURCE = {} as AnalysisSource;
const input = (variant: Variant, over = false, net: AnalysisSource | null = SOURCE): LiveInput => ({
  snapshot: { id: 1, history: [], variant, ply: 0, over },
  sources: () => ({ classical: SOURCE, net }),
});
const FIVE: Variant = { ...DEFAULT_VARIANT, rows: 5 };

describe('liveBlockedReason', () => {
  it('opens the classical figures for the classic game on 4 and on 5 rows', () => {
    expect(liveBlockedReason(input(DEFAULT_VARIANT), 'classical')).toBeNull();
    expect(liveBlockedReason(input(FIVE), 'classical')).toBeNull();
    expect(liveBlockedReason(input({ ...FIVE, ranking: 'full' }), 'classical')).toBeNull();
  });

  it('keeps the net figures closed on 5 rows with their own reason, and open on 4 rows', () => {
    expect(liveBlockedReason(input(DEFAULT_VARIANT), 'net')).toBeNull();
    expect(liveBlockedReason(input(FIVE), 'net')).toBe('rows5');
    // the 5-row reason comes before "over" and "nonet": it is the cause that holds for every position of the game
    expect(liveBlockedReason(input(FIVE, true, null), 'net')).toBe('rows5');
  });

  it('gives "variant" for every other rule on either board size, before the other reasons', () => {
    for (const rows of [4, 5] as const) {
      for (const variant of [
        { jump: 'long', players: 2, rows },
        { jump: 'adjacent', players: 3, rows },
        { jump: 'adjacent', players: 2, camps: 'open', rows },
        { jump: 'adjacent', players: 2, camps: 'pass-through', rows },
      ] satisfies Variant[]) {
        expect(liveBlockedReason(input(variant, true), 'classical'), JSON.stringify(variant)).toBe('variant');
        expect(liveBlockedReason(input(variant, true), 'net'), JSON.stringify(variant)).toBe('variant');
      }
    }
  });

  it('keeps the other reasons and their order', () => {
    expect(liveBlockedReason(null, 'classical')).toBe('nosnapshot');
    expect(liveBlockedReason(input(FIVE, true), 'classical')).toBe('over');
    expect(liveBlockedReason(input(DEFAULT_VARIANT, true, null), 'net')).toBe('over');
    expect(liveBlockedReason(input(DEFAULT_VARIANT, false, null), 'net')).toBe('nonet');
  });
});

describe('isClassic and isClassicAnySize', () => {
  it('differ in the board size only', () => {
    expect(isClassic(DEFAULT_VARIANT)).toBe(true);
    expect(isClassic(FIVE)).toBe(false);
    expect(isClassicAnySize(FIVE)).toBe(true);
    expect(isClassicAnySize({ ...FIVE, jump: 'long' })).toBe(false);
  });
});
