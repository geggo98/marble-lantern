// The typed key list. en.json is the source: a key exists when en.json has it.
// de.json must have exactly the same keys; the type check below fails otherwise.
import type en from './en.json';
import type de from './de.json';

export type MessageKey = keyof typeof en;
export type Catalog = Record<MessageKey, string>;

// Compile-time check that both catalogs have the same key set.
type SameKeys<A, B> = [keyof A] extends [keyof B] ? ([keyof B] extends [keyof A] ? true : never) : never;
export const catalogsMatch: SameKeys<typeof en, typeof de> = true;

export const LANGUAGES = ['en', 'de'] as const;
export type Language = (typeof LANGUAGES)[number];
