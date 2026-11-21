// The catalog of topic 5 (tuning), shared by the topic page and its figures. Kept apart from topics/tuning.tsx so that the figures
// can import it without importing the page (which imports them).
import en from '../i18n/en/tuning.json';
import de from '../i18n/de/tuning.json';
import { makeAboutCatalog } from '../i18n/catalog';
import type { SameKeys } from '../i18n/catalog';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;
export const tuningCatalog = makeAboutCatalog({ en, de });
export type TuningKey = keyof typeof en;
