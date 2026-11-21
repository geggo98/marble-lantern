// The fixed words of a topic page (On this page, Further reading, Previous, Next, More in the glossary). They live with the topic chunk,
// not in the main catalogs, so that the main bundle grows only by what the hub needs (plan section 1.4).
import en from './en/chrome.json';
import de from './de/chrome.json';
import { useT } from '../../ui/texts';
import type { SameKeys } from './catalog';

export const chromeMatch: SameKeys<typeof en, typeof de> = true;
export type ChromeKey = keyof typeof en;

export function useChrome(): (key: ChromeKey) => string {
  const { language } = useT();
  return (key) => (language === 'de' ? de : en)[key];
}
