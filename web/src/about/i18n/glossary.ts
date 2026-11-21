// The glossary catalog of both languages. Shared by the topic pages (TermPopover) and the glossary page, so Vite puts it in a shared chunk.
// Keys: term.<id>.name, term.<id>.short, term.<id>.long (data-science terms only), term.<id>.en (German file only: the English term).
import en from './en/glossary.json';
import de from './de/glossary.json';
import internalIds from '../data/glossary-internal.json';
import type { Language } from '../../i18n/keys';

const CATALOGS: Record<Language, Record<string, string>> = { en, de };

export interface TermEntry {
  id: string;
  name: string;
  short: string;
  long?: string;
  /** The English term, in the German catalog only. */
  english?: string;
  /** A name we coined for this project (data/glossary-internal.json). */
  internal?: boolean;
}

/** All term ids, in the order of the English catalog. */
export const GLOSSARY_IDS: readonly string[] = Object.keys(en)
  .map((key) => /^term\.(?<id>[a-z0-9-]+)\.name$/.exec(key)?.groups?.['id'])
  .filter((id): id is string => id !== undefined);

/** The page words of the glossary (`glossary.*` keys: title, filter, kinds, empty message), German falling back to English. */
export function glossaryWord(language: Language, key: string): string {
  return CATALOGS[language][`glossary.${key}`] ?? CATALOGS.en[`glossary.${key}`] ?? key;
}

export function termEntry(id: string, language: Language): TermEntry | undefined {
  const pick = (part: string): string | undefined => CATALOGS[language][`term.${id}.${part}`] ?? CATALOGS.en[`term.${id}.${part}`];
  const name = pick('name');
  const short = pick('short');
  if (name === undefined || short === undefined) return undefined;
  const long = pick('long');
  const english = language === 'de' ? CATALOGS.de[`term.${id}.en`] : undefined;
  return { id, name, short, ...(long === undefined ? {} : { long }), ...(english === undefined ? {} : { english }), ...(isInternal(id) ? { internal: true } : {}) };
}

/** Every entry of a language, alphabetical by the name in that language. */
export function allTerms(language: Language): TermEntry[] {
  const locale = language;
  return GLOSSARY_IDS.map((id) => termEntry(id, language)!).sort((a, b) => a.name.localeCompare(b.name, locale, { sensitivity: 'base' }));
}

/** Lower case, conjunctions dropped, so that "F32 und F16" and "F32 and F16" compare equal. */
const squash = (text: string): string =>
  text.toLowerCase().replace(/\b(und|and|oder|or)\b/g, ' ').replace(/[-_\s]+/g, ' ').trim();

/**
 * The English term to show in brackets behind a German name, or undefined. Shown only in German, only when `english` exists and adds
 * something: not when it equals the name, is contained in it, or contains it (case-insensitive, conjunctions ignored).
 * Origin: owner feedback of 2026-11-21 ("F32 und F16 (F32 and F16)", "Faltung (Convolution) (convolution)", "FLOP (FLOP (floating-point operation))").
 * Limit: a name and an English term that share no words are always shown, even when they mean the same.
 */
export function englishToShow(language: Language, name: string, english: string | undefined): string | undefined {
  if (language !== 'de' || english === undefined) return undefined;
  const a = squash(name);
  const b = squash(english);
  if (b === '' || a === b || a.includes(b) || b.includes(a)) return undefined;
  return english;
}

const INTERNAL: ReadonlySet<string> = new Set(internalIds as string[]);

/** True for a term we coined ourselves (ids in data/glossary-internal.json). */
export const isInternal = (id: string): boolean => INTERNAL.has(id);
