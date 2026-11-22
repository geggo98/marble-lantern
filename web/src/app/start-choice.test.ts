import { describe, expect, it } from 'vitest';
import { humanSeatOf, humanSideOf, isHotSeat, variantOf } from '../game/setup';
import type { JumpRule, Player } from '../game/variant';
import { CHOICE_KEY, DEFAULT_CHOICE, loadChoice, parseChoice, saveChoice, setupOfChoice } from './start-choice';
import type { GameMode, StartChoice } from './start-choice';

// rows: 4 here, so that the tests of the older rules keep their meaning; the default of 5 rows (2026-11-20) has its own tests below
const choice = (over: Partial<StartChoice>): StartChoice => ({ ...DEFAULT_CHOICE, rows: 4, ...over });
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
    // camps: added on 2026-11-20 (foreign-corner rule); closed is the rule the app has always played
    expect(DEFAULT_CHOICE).toEqual({ jump: 'adjacent', camps: 'closed', rows: 5, ranking: 'first', mode: 'two', humanSeat: 0, engineSeat: 2 });
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
    for (const text of ['', '{', 'null', '7', '"x"', '[]']) {
      expect(parseChoice(text), text).toEqual(DEFAULT_CHOICE);
    }
    // an object is a stored choice; its missing size is "no size chosen", which preselects 5 rows
    expect(parseChoice('{"jump":"far","mode":"four","humanSeat":9,"engineSeat":"2"}')).toEqual({ ...DEFAULT_CHOICE });
  });

  it('reads the foreign-corner rule, and a choice stored before the field existed as closed', () => {
    expect(parseChoice('{"jump":"long","mode":"engines","humanSeat":1,"engineSeat":2}')).toEqual({
      jump: 'long', camps: 'closed', rows: 5, ranking: 'first', mode: 'engines', humanSeat: 1, engineSeat: 2,
    });
    expect(parseChoice('{"camps":"open"}').camps).toBe('open');
    expect(parseChoice('{"camps":"pass-through"}').camps).toBe('pass-through');
    for (const bad of ['"OPEN"', '"pass"', '1', 'null', 'true']) expect(parseChoice(`{"camps":${bad}}`).camps, bad).toBe('closed');
  });

  it('puts the foreign-corner rule into the variant of every mode; closed leaves no field', () => {
    for (const mode of ['two', 'hotseat', 'engines'] as const) {
      expect(variantOf(setupOfChoice(choice({ mode, camps: 'open' }), true, 1)).camps).toBe('open');
      expect(variantOf(setupOfChoice(choice({ mode, camps: 'pass-through', jump: 'long' }), false, 1))).toMatchObject({ jump: 'long', camps: 'pass-through' });
      expect('camps' in variantOf(setupOfChoice(choice({ mode }), true, 1))).toBe(false);
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

describe('the board size of the choice (RuleSet.TriangleRows)', () => {
  it('a fresh visitor gets 5 rows; a stored choice without a size, or with a size that is not 4 or 5, also preselects 5; an explicit 4 stays 4 only with the version marker', () => {
    expect(parseChoice(null).rows).toBe(5);
    expect(parseChoice('{"jump":"long"}').rows).toBe(5);
    expect(parseChoice('{}').rows).toBe(5);
    for (const bad of ['6', '"5"', '"4"', 'null', '0', 'true']) expect(parseChoice(`{"v":3,"rows":${bad}}`).rows, bad).toBe(5);
    expect(parseChoice('{"v":3,"rows":5}').rows).toBe(5);
    expect(parseChoice('{"v":3,"rows":4}').rows).toBe(4);
  });

  it('migration (owner wish 2026-11-20): a stored 4 without the version marker was only preselected and reads as 5; the other fields stay', () => {
    const old = '{"jump":"long","camps":"open","rows":4,"ranking":"full","mode":"engines","humanSeat":1,"engineSeat":0}';
    expect(parseChoice(old)).toEqual({ jump: 'long', camps: 'open', rows: 5, ranking: 'full', mode: 'engines', humanSeat: 1, engineSeat: 0 });
    // a marker of another version does not vouch for the 4
    for (const v of ['1', '2', '4', '"3"', 'null']) expect(parseChoice(`{"v":${v},"rows":4}`).rows, v).toBe(5);
  });

  it('migration (2026-11-22): a 4 stored with version 2 may stem from the link-dialog carry-over bug and reads as 5; the other fields stay', () => {
    expect(parseChoice('{"jump":"adjacent","camps":"closed","rows":4,"v":2}').rows).toBe(5);
    expect(parseChoice('{"jump":"long","rows":4,"v":2}')).toMatchObject({ jump: 'long', rows: 5 });
  });

  it('every save writes the version marker, so that the next Start of a person who chose 4 keeps it', () => {
    const data: Record<string, string> = {};
    saveChoice({ ...DEFAULT_CHOICE, rows: 4 }, { setItem: (k, v) => { data[k] = v; } });
    expect(JSON.parse(data[CHOICE_KEY]!)).toMatchObject({ v: 3, rows: 4 });
    expect(loadChoice({ getItem: (k) => data[k] ?? null }).rows).toBe(4);
  });

  it('round-trips through storage in both sizes', () => {
    for (const rows of [4, 5] as const) {
      const data: Record<string, string> = {};
      saveChoice({ ...DEFAULT_CHOICE, rows }, { setItem: (k, v) => { data[k] = v; } });
      expect(loadChoice({ getItem: (k) => data[k] ?? null }).rows).toBe(rows);
    }
  });

  it('puts the size into the variant of every mode; 4 rows leave no field', () => {
    for (const mode of ['two', 'hotseat', 'engines'] as const) {
      for (const jump of ['adjacent', 'long'] as const) {
        expect(variantOf(setupOfChoice(choice({ mode, jump, rows: 5 }), true, 1))).toEqual({ jump, players: mode === 'two' ? 2 : 3, rows: 5 });
        expect('rows' in variantOf(setupOfChoice(choice({ mode, jump, rows: 4 }), true, 1))).toBe(false);
      }
      expect(variantOf(setupOfChoice(choice({ mode, rows: 5, camps: 'open' }), true, 1))).toMatchObject({ camps: 'open', rows: 5 });
    }
  });
});

describe('the ranking rule of the choice (RuleSet.Ranking)', () => {
  it('reads `full`, and everything else (a missing field, a stored choice from before the field, a wrong value) as `first`', () => {
    expect(parseChoice('{"ranking":"full"}').ranking).toBe('full');
    expect(parseChoice('{"jump":"long"}').ranking).toBe('first');
    expect(parseChoice('{"ranking":"second"}').ranking).toBe('first');
    expect(parseChoice('{"ranking":3}').ranking).toBe('first');
    expect(parseChoice(null).ranking).toBe('first');
  });

  it('round-trips through storage', () => {
    const data: Record<string, string> = {};
    const storage = { getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => { data[k] = v; } };
    saveChoice({ ...DEFAULT_CHOICE, mode: 'engines', ranking: 'full' }, storage);
    expect(loadChoice(storage).ranking).toBe('full');
  });

  it('puts `full` into the variant of the three-player modes only; `first` leaves no field', () => {
    for (const mode of ['hotseat', 'engines'] as const) {
      expect(variantOf(setupOfChoice(choice({ mode, ranking: 'full' }), true, 2))).toEqual({ jump: 'adjacent', players: 3, ranking: 'full' });
      expect(variantOf(setupOfChoice(choice({ mode, ranking: 'first' }), true, 2))).toEqual({ jump: 'adjacent', players: 3 });
    }
    // a stored `full` of an earlier three-player choice must not leak into a two-player game
    expect(variantOf(setupOfChoice(choice({ mode: 'two', ranking: 'full' }), true, 2))).toEqual({ jump: 'adjacent', players: 2 });
  });
});
