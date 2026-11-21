// The catalog of topic 6 (java), shared by the topic page and its figure. Kept apart from topics/java.tsx so that the figure can import it
// without importing the page (which imports the figure).
import en from '../i18n/en/java.json';
import de from '../i18n/de/java.json';
import { makeAboutCatalog } from '../i18n/catalog';
import type { SameKeys } from '../i18n/catalog';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;
export const javaCatalog = makeAboutCatalog({ en, de });
export type JavaKey = keyof typeof en;
