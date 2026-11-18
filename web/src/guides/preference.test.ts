import { describe, expect, it } from 'vitest';
import { GUIDES_KEY, loadGuidesPreference, saveGuidesPreference } from './preference';

const memory = (initial: Record<string, string> = {}) => {
  const data = { ...initial };
  return { data, getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => { data[k] = v; } };
};
const blocked = {
  getItem: () => { throw new Error('blocked'); },
  setItem: () => { throw new Error('blocked'); },
};

describe('guides preference', () => {
  it('is off by default', () => {
    expect(loadGuidesPreference(memory())).toBe(false);
    expect(loadGuidesPreference(null)).toBe(false);
  });
  it('round-trips', () => {
    const store = memory();
    saveGuidesPreference(true, store);
    expect(store.data[GUIDES_KEY]).toBe('on');
    expect(loadGuidesPreference(store)).toBe(true);
    saveGuidesPreference(false, store);
    expect(loadGuidesPreference(store)).toBe(false);
  });
  it('ignores a stored value it does not know', () => {
    expect(loadGuidesPreference(memory({ [GUIDES_KEY]: 'yes' }))).toBe(false);
  });
  it('survives blocked storage on read and write', () => {
    expect(loadGuidesPreference(blocked)).toBe(false);
    expect(() => saveGuidesPreference(true, blocked)).not.toThrow();
  });
});
