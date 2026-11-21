// The reading list entries that topic pages cite: the 27 verified rows of data/reading.json, keyed by the id as a string.
// A topic catalog holds the note of an entry under `read.<id>.note`; catalogs.test.ts checks that every such id exists here.
import data from './data/reading.json';

export interface ReadingEntry {
  id: string;
  title: string;
  authors?: string;
  year?: number;
  url: string;
  /** `beginner`, `advanced` or `beginner to advanced`. */
  level: string;
  verifiedOn: string;
  paywalled?: boolean;
}

export const READING: Readonly<Record<string, ReadingEntry>> = Object.fromEntries(
  data.items.map((row) => {
    const { id, ...rest } = row as typeof row & { year?: number; paywalled?: boolean };
    return [String(id), { id: String(id), ...rest }] as const;
  }),
);
