// Minimal shell page so that the build has an entry. WP9 replaces it with the screens of the design
// (change the import in main.tsx).
import { useSyncExternalStore } from 'react';
import { i18n } from '../i18n/i18n';
import { LANGUAGES } from '../i18n/keys';

export function Shell(): React.JSX.Element {
  useSyncExternalStore(
    (listener) => i18n.subscribe(listener),
    () => i18n.current,
  );
  const t = i18n.t;
  return (
    <main style={{ fontFamily: 'system-ui', maxWidth: '40rem', margin: '2rem auto', padding: '0 1rem' }}>
      <h1>{t('app.title')}</h1>
      <p>{t('app.tagline')}</p>
      <p>
        <span>{__ONLINE__ ? t('mode.online') : t('mode.offline')}</span>
        {' · '}
        {LANGUAGES.map((l) => (
          <button key={l} type="button" aria-pressed={i18n.current === l} onClick={() => i18n.setLanguage(l)}>
            {l}
          </button>
        ))}
      </p>
    </main>
  );
}
