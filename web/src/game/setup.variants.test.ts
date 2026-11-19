import { describe, expect, it } from 'vitest';
import {
  controllerSeats, engineSeatsOf, hotSeatVsEngine, humanSeatOf, humanSeatsOf, humanSideOf, humanVsEngine, humanVsTwoEngines,
  isHotSeat, isSupported, levelOf, variantOf, withLevel,
} from './setup';
import { DEFAULT_VARIANT } from './variant';

describe('GameSetup for three players', () => {
  it('hot-seat: two humans and one engine, the engine on the last seat by default', () => {
    const s = hotSeatVsEngine(3);
    expect(s.variant).toEqual({ jump: 'adjacent', players: 3 });
    expect(s.seats.map((x) => x.kind)).toEqual(['human', 'human', 'engine']);
    expect(s.seats[2]).toEqual({ kind: 'engine', level: 3 });
    expect(isHotSeat(s)).toBe(true);
    expect(humanSeatsOf(s)).toEqual([0, 1]);
    expect(engineSeatsOf(s)).toEqual([2]);
    expect(humanSeatOf(s)).toBeNull();
    expect(humanSideOf(s)).toBeNull();
  });

  it('hot-seat: the engine seat can be chosen', () => {
    expect(hotSeatVsEngine(2, 0).seats.map((x) => x.kind)).toEqual(['engine', 'human', 'human']);
    expect(hotSeatVsEngine(2, 1, 'long').variant).toEqual({ jump: 'long', players: 3 });
  });

  it('one human and two engines: one level for both engine seats', () => {
    const s = humanVsTwoEngines(1, 4);
    expect(s.seats.map((x) => x.kind)).toEqual(['engine', 'human', 'engine']);
    expect(humanSeatOf(s)).toBe(1);
    expect(humanSideOf(s)).toBeNull(); // the two-player side does not exist for three players
    expect(isHotSeat(s)).toBe(false);
    const next = withLevel(s, 0);
    expect(next.seats.map((x) => x.level)).toEqual([0, undefined, 0]);
    expect(next.variant).toEqual(s.variant);
    expect(levelOf(next, 2)).toBe(0);
  });

  it('keeps the long jump rule of two-player games and defaults to the standard variant', () => {
    expect(humanVsEngine(0, 2, 'long').variant).toEqual({ jump: 'long', players: 2 });
    expect(variantOf(humanVsEngine(0, 2))).toEqual(DEFAULT_VARIANT);
    expect(variantOf({ seats: [{ kind: 'human' }, { kind: 'engine' }] })).toEqual(DEFAULT_VARIANT);
  });

  it('is supported with up to two humans, not with a seat count that does not fit the variant', () => {
    expect(isSupported(hotSeatVsEngine(2))).toBe(true);
    expect(isSupported(humanVsTwoEngines(0, 2))).toBe(true);
    const three = { jump: 'adjacent', players: 3 } as const;
    expect(isSupported({ variant: three, seats: [{ kind: 'human' }, { kind: 'engine' }] })).toBe(false);
    expect(isSupported({ variant: three, seats: [{ kind: 'human' }, { kind: 'human' }, { kind: 'human' }] })).toBe(false);
    expect(isSupported({ variant: three, seats: [{ kind: 'human' }, { kind: 'network' }, { kind: 'engine' }] })).toBe(false);
  });

  it('turns seats into controller seats and refuses network seats', () => {
    expect(controllerSeats(hotSeatVsEngine(2))).toEqual(['human', 'human', 'engine']);
    expect(() => controllerSeats({ seats: [{ kind: 'human' }, { kind: 'network' }] })).toThrow(/network/);
  });
});
