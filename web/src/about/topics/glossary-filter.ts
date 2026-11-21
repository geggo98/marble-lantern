// The filter of the glossary page: by kind and by a typed text. Pure, so a node test covers it.
import type { TermEntry } from '../i18n/glossary';

/** `ds`: a data-science term (it has the long definition); `eng`: a search or engineering term (short definition only). */
export type TermKind = 'ds' | 'eng';
export type KindFilter = 'all' | TermKind;

export const termKind = (entry: TermEntry): TermKind => (entry.long === undefined ? 'eng' : 'ds');

/** Lower case, without accents, so that "grosse" finds "Grosse" and "uber" finds "Über". */
const fold = (text: string): string => text.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();

/** Entries of `kind` whose name, English term or short definition contains every word of `query`. The order of `terms` stays. */
export function filterTerms(terms: readonly TermEntry[], kind: KindFilter, query: string): TermEntry[] {
  const words = fold(query).split(/\s+/).filter(Boolean);
  return terms.filter((e) => {
    if (kind !== 'all' && termKind(e) !== kind) return false;
    const haystack = fold(`${e.name} ${e.english ?? ''} ${e.short}`);
    return words.every((w) => haystack.includes(w));
  });
}
