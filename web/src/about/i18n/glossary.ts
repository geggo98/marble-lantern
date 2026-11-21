// The glossary catalog of both languages. Shared by the topic pages (TermPopover) and the glossary page, so Vite puts it in a shared chunk.
// Keys: term.<id>.name, term.<id>.short, term.<id>.long (data-science terms only), term.<id>.en (German file only: the English term).
import en from './en/glossary.json';
import de from './de/glossary.json';
import type { Language } from '../../i18n/keys';

const CATALOGS: Record<Language, Record<string, string>> = { en, de };

export interface TermEntry {
  id: string;
  name: string;
  short: string;
  long?: string;
  /** The English term, in the German catalog only. */
  english?: string;
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
  return { id, name, short, ...(long === undefined ? {} : { long }), ...(english === undefined ? {} : { english }) };
}

/** Every entry of a language, alphabetical by the name in that language. */
export function allTerms(language: Language): TermEntry[] {
  const locale = language;
  return GLOSSARY_IDS.map((id) => termEntry(id, language)!).sort((a, b) => a.name.localeCompare(b.name, locale, { sensitivity: 'base' }));
}
