import { describe, expect, it } from 'vitest';
import { humanSeatOf, humanSideOf, isHotSeat, variantOf } from '../game/setup';
import type { JumpRule, Player } from '../game/variant';
import { CHOICE_KEY, DEFAULT_CHOICE, loadChoice, parseChoice, saveChoice, setupOfChoice } from './start-choice';
import type { GameMode, StartChoice } from './start-choice';

const choice = (over: Partial<StartChoice>): StartChoice => ({ ...DEFAULT_CHOICE, ...over });
const kinds = (c: StartChoice, begins = true): string => setupOfChoice(c, begins, 2).seats.map((s) => s.kind[0]).join('');

describe('setupOfChoice: every mode, jump rule and seat gives the right game', () => {
  it.each([['adjacent' as JumpRule], ['long' as JumpRule]])('two players, jump %s: "yes" is seat 0, "no" is seat 1', (jump) => {
    const yes = setupOfChoice(choice({ jump }), true, 3);
    const no = setupOfChoice(choice({ jump }), false, 3);
    expect(humanSideOf(yes)).toBe(0);
    expect(humanSideOf(no)).toBe(1);
    expect(variantOf(yes)).toEqual({ jump, players: 2 });
    expect(yes.seats[1]).toEqual({ kind: 'engine', level: 3 });
  });

  it.each([[0 as Player], [1 as Player], [2 as Player]])('three players against two engines, the person on seat %s', (humanSeat) => {
    for (const jump of ['adjacent', 'long'] as const) {
      const setup = setupOfChoice(choice({ mode: 'engines', humanSeat, jump }), false, 1);
      expect(humanSeatOf(setup)).toBe(humanSeat);
      expect(variantOf(setup)).toEqual({ jump, players: 3 });
      expect(setup.seats.filter((s) => s.kind === 'engine')).toHaveLength(2);
    }
  });

  it.each([[0 as Player], [1 as Player], [2 as Player]])('hot-seat with the engine on seat %s', (engineSeat) => {
    for (const jump of ['adjacent', 'long'] as const) {
      const setup = setupOfChoice(choice({ mode: 'hotseat', engineSeat, jump }), true, 1);
      expect(isHotSeat(setup)).toBe(true);
      expect(setup.seats[engineSeat]!.kind).toBe('engine');
      expect(variantOf(setup)).toEqual({ jump, players: 3 });
    }
  });

  it('the begin answer does not matter in the three-player modes', () => {
    expect(kinds(choice({ mode: 'hotseat' }), true)).toBe(kinds(choice({ mode: 'hotseat' }), false));
  });

  it('the default is the classic game, engine on the last seat in hot-seat', () => {
    expect(DEFAULT_CHOICE).toEqual({ jump: 'adjacent', mode: 'two', humanSeat: 0, engineSeat: 2 });
    expect(kinds(choice({ mode: 'hotseat' }))).toBe('hhe');
  });
});

describe('the remembered choice', () => {
  const memory = (initial: Record<string, string> = {}) => {
    const data = { ...initial };
    return { data, getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => { data[k] = v; } };
  };
  const throwing = {
    getItem: (): string => { throw new Error('blocked'); },
    setItem: (): void => { throw new Error('blocked'); },
  };

  it('round-trips through storage', () => {
    const storage = memory();
    const c = choice({ jump: 'long', mode: 'engines', humanSeat: 2, engineSeat: 1 });
    saveChoice(c, storage);
    expect(storage.data[CHOICE_KEY]).toBeDefined();
    expect(loadChoice(storage)).toEqual(c);
  });

  it('gives the default for nothing, for damaged text and for values that are not choices', () => {
    expect(loadChoice(memory())).toEqual(DEFAULT_CHOICE);
    for (const text of ['', '{', 'null', '7', '"x"', '[]', '{"jump":"far","mode":"four","humanSeat":9,"engineSeat":"2"}']) {
      expect(parseChoice(text), text).toEqual(DEFAULT_CHOICE);
    }
  });

  it('keeps the valid fields of a partly valid value', () => {
    expect(parseChoice('{"jump":"long","mode":"x"}')).toEqual({ ...DEFAULT_CHOICE, jump: 'long' });
    const modes: GameMode[] = ['hotseat', 'engines'];
    for (const mode of modes) expect(parseChoice(JSON.stringify({ mode })).mode).toBe(mode);
  });

  it('survives storage that throws, or no storage at all', () => {
    expect(loadChoice(throwing)).toEqual(DEFAULT_CHOICE);
    expect(() => saveChoice(DEFAULT_CHOICE, throwing)).not.toThrow();
    expect(loadChoice(null)).toEqual(DEFAULT_CHOICE);
    expect(() => saveChoice(DEFAULT_CHOICE, null)).not.toThrow();
  });
});
