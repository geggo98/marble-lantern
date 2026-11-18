import { describe, expect, it } from 'vitest';
import de from './de.json';
import en from './en.json';
import { I18n, STORAGE_KEY, pickLanguage } from './i18n';

const memory = (initial: Record<string, string> = {}) => {
  const data = { ...initial };
  return { data, getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => { data[k] = v; } };
};
const throwing = {
  getItem: () => { throw new Error('blocked'); },
  setItem: () => { throw new Error('blocked'); },
};

describe('pickLanguage', () => {
  it('takes the first supported tag and ignores the region', () => {
    expect(pickLanguage(['fr-FR', 'de-AT', 'en'])).toBe('de');
    expect(pickLanguage(['EN-us'])).toBe('en');
  });
  it('falls back to English', () => {
    expect(pickLanguage([])).toBe('en');
    expect(pickLanguage(['fr', 'ja'])).toBe('en');
  });
});

describe('I18n', () => {
  it('starts with the browser language', () => {
    expect(new I18n({ storage: memory(), navigatorLanguages: ['de-DE'] }).current).toBe('de');
  });

  it('prefers the stored choice over the browser language', () => {
    expect(new I18n({ storage: memory({ [STORAGE_KEY]: 'en' }), navigatorLanguages: ['de'] }).current).toBe('en');
  });

  it('ignores a stored value that is not a supported language', () => {
    expect(new I18n({ storage: memory({ [STORAGE_KEY]: 'xx' }), navigatorLanguages: ['de'] }).current).toBe('de');
  });

  it('stores the switch', () => {
    const storage = memory();
    const i = new I18n({ storage, navigatorLanguages: ['en'] });
    i.setLanguage('de');
    expect(storage.data[STORAGE_KEY]).toBe('de');
    expect(i.t('app.tagline')).toBe(de['app.tagline']);
  });

  it('works when storage throws on read and write', () => {
    const i = new I18n({ storage: throwing, navigatorLanguages: ['de'] });
    expect(i.current).toBe('de');
    expect(() => i.setLanguage('en')).not.toThrow();
    expect(i.current).toBe('en');
  });

  it('works without storage', () => {
    expect(new I18n({ storage: null, navigatorLanguages: ['en'] }).t('mode.offline')).toBe('offline');
  });

  it('notifies subscribers once per switch and stops after unsubscribe', () => {
    const i = new I18n({ storage: null, navigatorLanguages: ['en'] });
    let n = 0;
    const off = i.subscribe(() => n++);
    i.setLanguage('de');
    off();
    i.setLanguage('en');
    expect(n).toBe(1);
  });

  it('fills {name} placeholders and keeps unknown ones', () => {
    const catalog = { ...en, 'app.title': 'Hello {who}, {n} and {missing}' };
    const i = new I18n({ storage: null, navigatorLanguages: ['en'], catalogs: { en: catalog, de: catalog } });
    expect(i.t('app.title', { who: 'you', n: 3 })).toBe('Hello you, 3 and {missing}');
  });

  it('formats numbers in the active language', () => {
    const i = new I18n({ storage: null, navigatorLanguages: ['en'] });
    expect(i.formatNumber(20000)).toBe('20,000');
    i.setLanguage('de');
    expect(i.formatNumber(20000)).toBe('20.000');
  });
});

describe('catalogs', () => {
  it('have the same keys in English and German', () => {
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort());
  });

  it('have no empty text and the same placeholders per key', () => {
    const names = (s: string): string[] => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]!).sort();
    for (const key of Object.keys(en) as Array<keyof typeof en>) {
      expect(en[key].trim(), key).not.toBe('');
      expect(de[key].trim(), key).not.toBe('');
      expect(names(de[key]), key).toEqual(names(en[key]));
    }
  });

  it('have a text for every protocol error code', () => {
    const codes = ['table-checksum', 'table-truncated', 'table-trailing', 'table-magic', 'wasm-gc-unsupported', 'illegal-history', 'rows1-requested', 'aborted', 'internal'];
    for (const code of codes) expect(en, code).toHaveProperty(`error.${code}`);
  });
});
