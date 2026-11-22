// The catalog of topic 7 (java-web), shared by the topic page and its figures (J6 Hilla data flow, J7 TeaVM call boundary). Kept apart from
// topics/java-web.tsx so that a figure can import it without importing the page (which imports the figure).
import en from '../i18n/en/java-web.json';
import de from '../i18n/de/java-web.json';
import { makeAboutCatalog } from '../i18n/catalog';
import type { SameKeys } from '../i18n/catalog';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;
export const javaWebCatalog = makeAboutCatalog({ en, de });
export type JavaWebKey = keyof typeof en;
