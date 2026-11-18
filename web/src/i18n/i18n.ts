// i18n infrastructure: typed keys, language from navigator.language, switch stored in localStorage.
import de from './de.json';
import en from './en.json';
import { LANGUAGES } from './keys';
import type { Catalog, Language, MessageKey } from './keys';

export const STORAGE_KEY = 'ml.language';

export const catalogs: Record<Language, Catalog> = { en, de };

/** First supported language of a list of BCP 47 tags ("de-AT" counts as "de"); "en" if none matches. */
export function pickLanguage(tags: readonly string[]): Language {
  for (const tag of tags) {
    const base = tag.toLowerCase().split('-')[0];
    const hit = LANGUAGES.find((l) => l === base);
    if (hit) return hit;
  }
  return 'en';
}

function readStored(storage: Pick<Storage, 'getItem'> | null): Language | null {
  try {
    const value = storage?.getItem(STORAGE_KEY);
    return LANGUAGES.find((l) => l === value) ?? null;
  } catch {
    return null; // private window, blocked site data, preview: render without it
  }
}

function defaultStorage(): Storage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null; // the accessor itself can throw
  }
}

export interface I18nOptions {
  storage?: Pick<Storage, 'getItem' | 'setItem'> | null;
  navigatorLanguages?: readonly string[];
  catalogs?: Record<Language, Catalog>;
}

export class I18n {
  private language: Language;
  private readonly storage: Pick<Storage, 'getItem' | 'setItem'> | null;
  private readonly catalogs: Record<Language, Catalog>;
  private listeners = new Set<() => void>();

  constructor(options: I18nOptions = {}) {
    this.storage = options.storage === undefined ? defaultStorage() : options.storage;
    this.catalogs = options.catalogs ?? catalogs;
    const tags = options.navigatorLanguages ?? (typeof navigator === 'undefined' ? [] : navigator.languages ?? [navigator.language]);
    this.language = readStored(this.storage) ?? pickLanguage(tags);
  }

  get current(): Language {
    return this.language;
  }

  setLanguage(language: Language): void {
    this.language = language;
    try {
      this.storage?.setItem(STORAGE_KEY, language);
    } catch {
      // the choice then lasts for this page view only
    }
    for (const l of this.listeners) l();
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Looks a key up, with {name} placeholders. A missing text shows the key, never an empty string. */
  t = (key: MessageKey, values?: Record<string, string | number>): string => {
    const text = this.catalogs[this.language][key] ?? this.catalogs.en[key] ?? key;
    if (!values) return text;
    return text.replace(/\{(\w+)\}/g, (whole, name: string) => (name in values ? String(values[name]) : whole));
  };

  /** Numbers in the active language (design-notes.md, section 4). */
  formatNumber = (n: number): string => new Intl.NumberFormat(this.language).format(n);
}

export const i18n = new I18n();
