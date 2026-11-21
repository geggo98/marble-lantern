// Glossary names as the German glossary shows them: the German name, then the English term in brackets.
// The display rule (owner feedback, 2026-11-21): the English term `term.<id>.en` is shown only when it exists and differs,
// ignoring case, from the name and from every part of it (the name split at its brackets). These tests check that the
// catalogs give a clean result under that rule: no `.en` that the rule would hide, no brackets inside brackets, and no
// English term that only repeats the German name with "and" for "und".
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

type Catalog = Record<string, string>;
const load = (lang: string): Catalog => JSON.parse(readFileSync(fileURLToPath(new URL(`./${lang}/glossary.json`, import.meta.url)), 'utf8')) as Catalog;
const en = load('en');
const de = load('de');
const ids = Object.keys(en)
  .map((k) => /^term\.(?<id>[a-z0-9-]+)\.name$/.exec(k)?.groups?.['id'])
  .filter((id): id is string => id !== undefined);

/** The name and each bracket part of it: "Faltung (Convolution)" gives the whole name, "Faltung" and "Convolution". */
const parts = (name: string): string[] => [name, ...name.split(/[()]/).map((s) => s.trim()).filter(Boolean)];

/** The display rule: show the English term when it exists and differs, ignoring case, from the name and from every part of it. */
export const showsEnglish = (name: string, english: string | undefined): boolean =>
  english !== undefined && !parts(name).some((p) => p.toLowerCase() === english.trim().toLowerCase());

/** The label the German glossary shows. */
const label = (id: string): string => {
  const name = de[`term.${id}.name`]!;
  const english = de[`term.${id}.en`];
  return showsEnglish(name, english) ? `${name} (${english})` : name;
};

/** Letters and digits only, German joining words mapped to English: "F32 und F16" and "F32 and F16" fold alike. */
const loose = (text: string): string =>
  text
    .toLowerCase()
    .replace(/\bund\b/g, 'and')
    .replace(/\bmit\b/g, 'with')
    .replace(/[^a-z0-9]+/g, '');

describe('German glossary names', () => {
  it('sees all terms', () => {
    expect(ids.length).toBe(146);
  });

  it('has no English term that the display rule would hide', () => {
    const hidden = ids.filter((id) => de[`term.${id}.en`] !== undefined && !showsEnglish(de[`term.${id}.name`]!, de[`term.${id}.en`]));
    expect(hidden.map((id) => `${id}: ${de[`term.${id}.name`]} / ${de[`term.${id}.en`]}`)).toEqual([]);
  });

  it('shows at most one pair of brackets per label, never brackets inside brackets', () => {
    const nested = ids.map(label).filter((l) => (l.match(/\(/g) ?? []).length > 1);
    expect(nested).toEqual([]);
  });

  it('shows no English term that only repeats the German name', () => {
    const repeats = ids
      .filter((id) => showsEnglish(de[`term.${id}.name`]!, de[`term.${id}.en`]))
      .filter((id) => loose(de[`term.${id}.name`]!) === loose(de[`term.${id}.en`]!))
      .map(label);
    expect(repeats).toEqual([]);
  });

  it('keeps the English catalog free of .en keys', () => {
    expect(Object.keys(en).filter((k) => k.endsWith('.en'))).toEqual([]);
  });
});

describe('glossary-internal.json', () => {
  // The terms this project coined (no use outside it); the glossary marks them "internal".
  const internal = JSON.parse(readFileSync(fileURLToPath(new URL('../data/glossary-internal.json', import.meta.url)), 'utf8')) as unknown;

  it('is a sorted list of distinct glossary ids', () => {
    expect(Array.isArray(internal)).toBe(true);
    const list = internal as string[];
    expect(list.length).toBeGreaterThan(0);
    expect(list.filter((id) => !ids.includes(id))).toEqual([]);
    expect(new Set(list).size).toBe(list.length);
    expect([...list].sort()).toEqual(list);
  });
});

describe('display rule', () => {
  it('hides a term that equals the name or a bracket part, ignoring case, and shows any other', () => {
    expect(showsEnglish('Faltung (Convolution)', 'convolution')).toBe(false);
    expect(showsEnglish('Batch', 'batch')).toBe(false);
    expect(showsEnglish('Faltung', 'convolution')).toBe(true);
    expect(showsEnglish('Faltung', undefined)).toBe(false);
  });
});
