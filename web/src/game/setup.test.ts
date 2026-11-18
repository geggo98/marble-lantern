import { describe, expect, it } from 'vitest';
import { engineVsEngine, humanSideOf, humanVsEngine, isDemo, isSupported, levelOf, withLevel } from './setup';
import type { GameSetup } from './setup';

describe('GameSetup', () => {
  it('puts the human on the seat of its side', () => {
    expect(humanSideOf(humanVsEngine(0, 2))).toBe(0);
    expect(humanSideOf(humanVsEngine(1, 2))).toBe(1);
    expect(humanVsEngine(1, 3).seats[0]).toEqual({ kind: 'engine', level: 3 });
  });

  it('knows a demo: no human seat', () => {
    expect(isDemo(engineVsEngine(1))).toBe(true);
    expect(humanSideOf(engineVsEngine(1))).toBeNull();
    expect(isDemo(humanVsEngine(0, 1))).toBe(false);
  });

  it('changes the level of the engine seats only', () => {
    const next = withLevel(humanVsEngine(0, 2), 4);
    expect(next.seats[0]).toEqual({ kind: 'human' });
    expect(levelOf(next, 2)).toBe(4);
    expect(levelOf({ seats: [{ kind: 'human' }, { kind: 'human' }] }, 2)).toBe(2);
  });

  it('supports human against engine and engine against engine; not two humans, not network seats', () => {
    expect(isSupported(humanVsEngine(0, 2))).toBe(true);
    expect(isSupported(engineVsEngine(2))).toBe(true);
    const twoHumans: GameSetup = { seats: [{ kind: 'human' }, { kind: 'human' }] };
    const network: GameSetup = { seats: [{ kind: 'human' }, { kind: 'network', engine: 'server:net' }] };
    expect(isSupported(twoHumans)).toBe(false);
    expect(isSupported(network)).toBe(false);
  });
});
