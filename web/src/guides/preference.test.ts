import { describe, expect, it } from 'vitest';
import {
  DEFAULT_GUIDE_LEVEL, GUIDES_KEY, GUIDE_LEVELS, NETHINTS_SEEN_KEY, effectiveGuideLevel, loadGuideLevel, loadNetHintsSeen, parseGuideLevel,
  saveGuideLevel, saveNetHintsSeen,
} from './preference';

const memory = (initial: Record<string, string> = {}) => {
  const data = { ...initial };
  return { data, getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => { data[k] = v; } };
};
const blocked = {
  getItem: () => { throw new Error('blocked'); },
  setItem: () => { throw new Error('blocked'); },
};

describe('guide level preference', () => {
  it('defaults to the legal targets, which is what the old switch showed when it was off', () => {
    expect(DEFAULT_GUIDE_LEVEL).toBe('targets');
    expect(loadGuideLevel(memory())).toBe('targets');
    expect(loadGuideLevel(null)).toBe('targets');
  });
  it('round-trips every level', () => {
    for (const level of GUIDE_LEVELS) {
      const store = memory();
      saveGuideLevel(level, store);
      expect(store.data[GUIDES_KEY]).toBe(level);
      expect(loadGuideLevel(store)).toBe(level);
    }
  });
  it('migrates the old switch: on becomes eval, off becomes targets, and the value is rewritten', () => {
    const on = memory({ [GUIDES_KEY]: 'on' });
    expect(loadGuideLevel(on)).toBe('eval');
    expect(on.data[GUIDES_KEY]).toBe('eval');
    const off = memory({ [GUIDES_KEY]: 'off' });
    expect(loadGuideLevel(off)).toBe('targets');
    expect(off.data[GUIDES_KEY]).toBe('targets');
  });
  it('does not rewrite a value that is already a level', () => {
    const store = { ...memory({ [GUIDES_KEY]: 'none' }), setItem: () => { throw new Error('must not write'); } };
    expect(loadGuideLevel(store)).toBe('none');
  });
  it('ignores a stored value it does not know', () => {
    expect(parseGuideLevel('yes')).toBe('targets');
    expect(parseGuideLevel('')).toBe('targets');
    expect(parseGuideLevel('EVAL')).toBe('targets');
  });
  it('survives blocked storage on read and write', () => {
    expect(loadGuideLevel(blocked)).toBe('targets');
    expect(() => saveGuideLevel('eval', blocked)).not.toThrow();
  });
  it('still answers when the migration write is blocked', () => {
    const store = { getItem: () => 'on', setItem: () => { throw new Error('blocked'); } };
    expect(loadGuideLevel(store)).toBe('eval');
  });
});

describe('level net', () => {
  it('is a level: parsed, stored and loaded as it is', () => {
    expect(GUIDE_LEVELS).toEqual(['none', 'targets', 'eval', 'net']);
    expect(parseGuideLevel('net')).toBe('net');
    const store = memory({ [GUIDES_KEY]: 'net' });
    expect(loadGuideLevel(store)).toBe('net');
    expect(store.data[GUIDES_KEY]).toBe('net');
  });
  it('effectiveGuideLevel: net falls back to eval without the net, every other level stays', () => {
    expect(effectiveGuideLevel('net', true)).toBe('net');
    expect(effectiveGuideLevel('net', false)).toBe('eval');
    for (const level of ['none', 'targets', 'eval'] as const) {
      expect(effectiveGuideLevel(level, true)).toBe(level);
      expect(effectiveGuideLevel(level, false)).toBe(level);
    }
  });
  it('effectiveGuideLevel never touches the stored choice', () => {
    const store = memory({ [GUIDES_KEY]: 'net' });
    effectiveGuideLevel(loadGuideLevel(store), false);
    expect(store.data[GUIDES_KEY]).toBe('net');
  });
});

describe('net hints seen flag', () => {
  it('is false at first, true after saving, under the documented key', () => {
    const store = memory();
    expect(loadNetHintsSeen(store)).toBe(false);
    saveNetHintsSeen(store);
    expect(store.data[NETHINTS_SEEN_KEY]).toBe('1');
    expect(NETHINTS_SEEN_KEY).toBe('ml.nethints.seen');
    expect(loadNetHintsSeen(store)).toBe(true);
  });
  it('only the value 1 counts', () => {
    expect(loadNetHintsSeen(memory({ [NETHINTS_SEEN_KEY]: '0' }))).toBe(false);
  });
  it('survives blocked or missing storage', () => {
    expect(loadNetHintsSeen(blocked)).toBe(false);
    expect(loadNetHintsSeen(null)).toBe(false);
    expect(() => saveNetHintsSeen(blocked)).not.toThrow();
    expect(() => saveNetHintsSeen(null)).not.toThrow();
  });
});
