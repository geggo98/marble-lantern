// App frame: header (brand, navigation, mode badge, language switch) and footer (note, source link, theme switch).
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { LanguageToggle } from './LanguageToggle';
import { Icon } from './primitives';
import type { IconName } from './primitives';
import { useT } from './texts';
import type { UiKey } from './texts';
import type { AboutTopic } from '../about/routes';
import './start-dialog.css';

export type Screen = 'play' | 'bench' | 'about';

const NAV: { screen: Screen; key: UiKey }[] = [
  { screen: 'play', key: 'nav_play' },
  { screen: 'bench', key: 'nav_bench' },
  { screen: 'about', key: 'nav_about' },
];

export function hrefFor(screen: Screen): string {
  return `#/${screen}`;
}

/** `#/about`, `#/about/<topic>` or `#/about/<topic>/<section>`. The fragment is the router, so every in-page link uses the full route. */
export function hrefForAbout(topic?: AboutTopic, section?: string): string {
  if (topic === undefined) return hrefFor('about');
  return section === undefined ? `#/about/${topic}` : `#/about/${topic}/${section}`;
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
          <span className="ml-badge__text">{online ? t('mode.online') : t('mode.offline')}</span>
        </span>
        <LanguageToggle />
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

/** --ml-color-bg of web/design/tokens.css: the browser bar and the title bar of the installed app use it (meta theme-color). */
export const THEME_COLORS = { light: '#f5f0e6', dark: '#14110e' } as const;

export function applyTheme(theme: ThemeChoice): void {
  const root = document.documentElement;
  if (theme === 'auto') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', theme);
  // index.html has one meta per colour scheme; a forced theme sets both to its colour, 'auto' restores each one's own.
  for (const meta of document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"][media]')) {
    const own = meta.media.includes('dark') ? THEME_COLORS.dark : THEME_COLORS.light;
    meta.content = theme === 'auto' ? own : THEME_COLORS[theme];
  }
}

// One store for the choice: the footer and the menu on a small screen both show the switch and must agree.
let currentTheme: ThemeChoice | null = null;
const themeListeners = new Set<() => void>();
const themeSnapshot = (): ThemeChoice => (currentTheme ??= readTheme());
const themeSubscribe = (listener: () => void): (() => void) => {
  themeListeners.add(listener);
  return () => themeListeners.delete(listener);
};
function chooseTheme(next: ThemeChoice): void {
  currentTheme = next;
  applyTheme(next);
  try {
    globalThis.localStorage?.setItem(THEME_KEY, next);
  } catch {
    // the choice then lasts for this page view only
  }
  themeListeners.forEach((l) => l());
}

/** The three-way theme switch. */
export function ThemeSwitch(): React.JSX.Element {
  const { t } = useT();
  const theme = useSyncExternalStore(themeSubscribe, themeSnapshot, () => 'auto' as ThemeChoice);
  useEffect(() => applyTheme(theme), [theme]);
  return (
    <div className="ml-seg" role="group" aria-label={t('theme')}>
      {THEMES.map((o) => (
        <button
          key={o.id}
          type="button"
          className="ml-seg__opt"
          aria-pressed={theme === o.id}
          aria-label={t(o.key)}
          onClick={() => chooseTheme(o.id)}
        >
          <Icon name={o.icon} />
        </button>
      ))}
    </div>
  );
}

export function Footer(): React.JSX.Element {
  const { t } = useT();
  return (
    <footer className="ml-footer">
      <span>
        {t('footer_note')} · <a href={REPO_URL} rel="noreferrer">{t('source_code')}</a>
      </span>
      <ThemeSwitch />
    </footer>
  );
}

export const REPO_URL = 'https://github.com/geggo98/marble-lantern';

/**
 * "Copy link": puts the address of the game on the clipboard. Without the clipboard API (an insecure page, a blocked
 * permission) the button shows the address in a read-only field with the text selected, so that it can be copied by hand.
 */
export function CopyLink({ getUrl, disabled = false }: { getUrl: () => string; disabled?: boolean }): React.JSX.Element {
  const { t } = useT();
  const [state, setState] = useState<'idle' | 'copied' | 'manual'>('idle');
  const field = useRef<HTMLInputElement | null>(null);
  const [url, setUrl] = useState('');

  useEffect(() => {
    if (state === 'manual') {
      field.current?.focus();
      field.current?.select();
    }
  }, [state, url]);

  const copy = async (): Promise<void> => {
    const address = getUrl();
    setUrl(address);
    try {
      await navigator.clipboard.writeText(address);
      setState('copied');
    } catch {
      setState('manual'); // no clipboard: the field is the visible fallback
    }
  };

  return (
    <div className="ml-copylink">
      <button type="button" className="ml-btn" disabled={disabled} onClick={() => void copy()}>
        <Icon name="copy" />
        <span>{t('copy_link')}</span>
      </button>
      <p className="ml-visually-hidden" role="status" aria-live="polite">{state === 'copied' ? t('copy_link_done') : ''}</p>
      {state === 'copied' && <p className="ml-hint" aria-hidden="true">{t('copy_link_done')}</p>}
      {state === 'manual' && (
        <label className="ml-copylink__field">
          <span>{t('copy_link_manual')}</span>
          <input ref={field} type="text" readOnly value={url} aria-label={t('copy_link_field')} onFocus={(e) => e.currentTarget.select()} />
        </label>
      )}
    </div>
  );
}
