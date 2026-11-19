// The catalogs of the net UI: every net.* key and every model key of models.json exists in en and de, with the same {placeholders}.
// (The key sets of the whole catalogs are compared at compile time, keys.ts.)
import { describe, expect, it } from 'vitest';
import { listModels } from '../engines/net/model-manifest';
import de from './de.json';
import en from './en.json';

const placeholders = (s: string): string[] => (s.match(/\{\w+\}/g) ?? []).sort();
const ens = en as Record<string, string>;
const des = de as Record<string, string>;
const netKeys = Object.keys(ens).filter((k) => k.startsWith('net.') || k.startsWith('model.'));

describe('net catalogs', () => {
  it('have net keys at all (an empty filter would pass everything)', () => {
    expect(netKeys.length).toBeGreaterThan(30);
  });

  it.each(netKeys)('%s: in both languages, not empty, same placeholders', (key) => {
    expect(des[key], `${key} missing in de`).toBeTypeOf('string');
    expect(ens[key]!.length).toBeGreaterThan(0);
    expect(des[key]!.length).toBeGreaterThan(0);
    expect(placeholders(des[key]!)).toEqual(placeholders(ens[key]!));
  });

  it('every model of models.json has its label and description keys', () => {
    for (const m of listModels()) {
      expect(ens[m.labelKey], m.labelKey).toBeTypeOf('string');
      expect(ens[m.descriptionKey], m.descriptionKey).toBeTypeOf('string');
      expect(des[m.labelKey], m.labelKey).toBeTypeOf('string');
      expect(des[m.descriptionKey], m.descriptionKey).toBeTypeOf('string');
    }
  });
});
