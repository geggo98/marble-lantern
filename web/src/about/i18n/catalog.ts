// Catalogs of the topic pages: one JSON file per topic and language under about/i18n/{en,de}/. A topic module imports both
// languages, so both load in the topic's chunk and a language switch needs no second fetch (plan section 1.4).
// The key type comes from the English file; SameKeys fails the type check when the German file differs (as web/src/i18n/keys.ts does).
import { useT } from '../../ui/texts';
import type { Language } from '../../i18n/keys';

export type SameKeys<A, B> = [keyof A] extends [keyof B] ? ([keyof B] extends [keyof A] ? true : never) : never;

export type AboutT<K extends string> = (key: K, values?: Record<string, string | number>) => string;

export interface AboutCatalog<K extends string> {
  readonly catalogs: Readonly<Record<Language, Readonly<Record<string, string>>>>;
  /** The typed lookup for the active language. A key missing in German falls back to English, then to the key. */
  useAboutT(): AboutT<K>;
  /** The same lookup for a given language, outside React (tests, the build check). */
  lookup(language: Language, key: K, values?: Record<string, string | number>): string;
}

const fill = (text: string, values?: Record<string, string | number>): string =>
  values ? text.replace(/\{(\w+)\}/g, (whole, name: string) => (name in values ? String(values[name]) : whole)) : text;

export function makeAboutCatalog<C extends Record<string, string>>(catalogs: {
  en: C;
  de: Record<keyof C, string>;
}): AboutCatalog<keyof C & string> {
  type K = keyof C & string;
  const lookup = (language: Language, key: K, values?: Record<string, string | number>): string =>
    fill((catalogs[language] as Record<string, string>)[key] ?? (catalogs.en as Record<string, string>)[key] ?? key, values);
  return {
    catalogs,
    lookup,
    useAboutT(): AboutT<K> {
      const { language } = useT();
      return (key, values) => lookup(language as Language, key, values);
    },
  };
}

/** True when the catalog of the language has the key (used for the optional paragraphs p2, p3 ...). */
export const hasKey = (catalog: AboutCatalog<string>, key: string): boolean => key in catalog.catalogs.en;
