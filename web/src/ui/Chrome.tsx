// App frame: header (brand, navigation, mode badge, language switch) and footer (note, source link, theme switch).
import { useEffect, useState } from 'react';
import { i18n } from '../i18n/i18n';
import { LANGUAGES } from '../i18n/keys';
import { Icon } from './primitives';
import type { IconName } from './primitives';
import { useT } from './texts';
import type { UiKey } from './texts';

export type Screen = 'play' | 'bench' | 'about';

const NAV: { screen: Screen; key: UiKey }[] = [
  { screen: 'play', key: 'nav_play' },
  { screen: 'bench', key: 'nav_bench' },
  { screen: 'about', key: 'nav_about' },
];

export function hrefFor(screen: Screen): string {
  return `#/${screen}`;
}

export function Header({ screen, online }: { screen: Screen; online: boolean }): React.JSX.Element {
  const { t, language } = useT();
  const base = import.meta.env.BASE_URL;
  return (
    <header className="ml-header">
      <a className="ml-brand" href={hrefFor('play')}>
        <img className="ml-brand__mark" src={`${base}logo.svg`} alt="" width={32} height={32} />
        <span>{t('app.title')}</span>
      </a>
      <nav className="ml-nav" aria-label={t('nav_label')}>
        {NAV.map((item) => (
          <a
            key={item.screen}
            className="ml-nav__link"
            href={hrefFor(item.screen)}
            {...(screen === item.screen ? { 'aria-current': 'page' as const } : {})}
          >
            {t(item.key)}
          </a>
        ))}
      </nav>
      <div className="ml-header__tools">
        <span className={online ? 'ml-badge ml-badge--online' : 'ml-badge'}>
          <Icon name={online ? 'online' : 'offline'} />
          {online ? t('mode.online') : t('mode.offline')}
        </span>
        <div className="ml-seg" role="group" aria-label={t('language')}>
          {LANGUAGES.map((l) => (
            <button
              key={l}
              type="button"
              className="ml-seg__opt"
              aria-pressed={language === l}
              lang={l}
              onClick={() => i18n.setLanguage(l)}
            >
              {l.toUpperCase()}
            </button>
          ))}
        </div>
      </div>
    </header>
  );
}

export type ThemeChoice = 'auto' | 'light' | 'dark';
const THEME_KEY = 'ml.theme';
const THEMES: { id: ThemeChoice; icon: IconName; key: UiKey }[] = [
  { id: 'auto', icon: 'auto', key: 'theme_auto' },
  { id: 'light', icon: 'sun', key: 'theme_light' },
  { id: 'dark', icon: 'moon', key: 'theme_dark' },
];

function readTheme(): ThemeChoice {
  try {
    const v = globalThis.localStorage?.getItem(THEME_KEY);
    return v === 'light' || v === 'dark' ? v : 'auto';
  } catch {
    return 'auto'; // private window or blocked site data: follow the system
  }
}

export function applyTheme(theme: ThemeChoice): void {
  const root = document.documentElement;
  if (theme === 'auto') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', theme);
}

export function Footer(): React.JSX.Element {
  const { t } = useT();
  const [theme, setTheme] = useState<ThemeChoice>(readTheme);
  useEffect(() => applyTheme(theme), [theme]);
  const choose = (next: ThemeChoice): void => {
    setTheme(next);
    try {
      globalThis.localStorage?.setItem(THEME_KEY, next);
    } catch {
      // the choice then lasts for this page view only
    }
  };
  return (
    <footer className="ml-footer">
      <span>
        {t('footer_note')} · <a href={REPO_URL} rel="noreferrer">{t('source_code')}</a>
      </span>
      <div className="ml-seg" role="group" aria-label={t('theme')}>
        {THEMES.map((o) => (
          <button
            key={o.id}
            type="button"
            className="ml-seg__opt"
            aria-pressed={theme === o.id}
            aria-label={t(o.key)}
            onClick={() => choose(o.id)}
          >
            <Icon name={o.icon} />
          </button>
        ))}
      </div>
    </footer>
  );
}

export const REPO_URL = 'https://github.com/geggo98/marble-lantern';
