// The UI text lookup: the catalog of the active language comes before the English working texts of this file.
// Regression of the owner report "the language toggle does nothing" (2026-11-19): the English working texts (`uiEn`) answered
// first in every language, so after the switch to German the navigation, the buttons and the status stayed English.
import { afterEach, describe, expect, it } from 'vitest';
import { catalogs, i18n } from '../i18n/i18n';
import type { MessageKey } from '../i18n/keys';
import { translate, uiEn } from './texts';
import type { UiKey } from './texts';

afterEach(() => i18n.setLanguage('en'));

describe('translate', () => {
  it('shows the German catalog text for a key that both the German catalog and the UI working texts have', () => {
    i18n.setLanguage('de');
    expect(translate('nav_play')).toBe(catalogs.de.nav_play);
    expect(translate('nav_play')).not.toBe(uiEn.nav_play);
    expect(translate('language')).toBe('Sprache');
  });

  it('every key of the UI working texts that the German catalog has is shown in German', () => {
    i18n.setLanguage('de');
    const stale = (Object.keys(uiEn) as UiKey[]).filter((k) => k in catalogs.de && translate(k) !== (catalogs.de as Record<string, string>)[k]);
    expect(stale).toEqual([]);
  });

  it('English keeps its working texts (they win over en.json); a key missing from the catalogs still shows the working text, then the key', () => {
    i18n.setLanguage('en');
    expect(translate('nav_play')).toBe(uiEn.nav_play);
    expect(translate('no.such.key' as MessageKey)).toBe('no.such.key');
  });

  it('keeps {placeholders} working', () => {
    i18n.setLanguage('de');
    expect(translate('link_info', { ply: 3, limit: 9 })).toBe(i18n.t('link_info', { ply: 3, limit: 9 }));
  });
});
