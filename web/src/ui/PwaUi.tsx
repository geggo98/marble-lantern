// The two visible parts of the installable app: the notice of a waiting update, and the install entry of the menu and side column.
// Both draw nothing without a PwaPort (the online mode, the tests that do not care) and nothing while there is nothing to offer.
import { useSyncExternalStore } from 'react';
import type { PwaPort, PwaState } from '../pwa/pwa';
import { Icon } from './primitives';
import { useT } from './texts';
import './pwa.css';

const NONE: PwaState = { update: false, install: null };
const noop = (): (() => void) => () => undefined;

function usePwa(pwa: PwaPort | undefined): PwaState {
  return useSyncExternalStore(pwa ? pwa.subscribe : noop, pwa ? pwa.getState : () => NONE);
}

/** "A new version is available": small, at the bottom edge; it reloads only when clicked. */
export function UpdateNotice({ pwa }: { pwa?: PwaPort }): React.JSX.Element | null {
  const { t } = useT();
  const state = usePwa(pwa);
  if (!pwa || !state.update) return null;
  return (
    <div className="ml-update" role="status" data-pwa="update">
      <span className="ml-update__text">{t('pwa.update.text')}</span>
      <button type="button" className="ml-btn ml-btn--primary" data-pwa="reload" onClick={pwa.applyUpdate}>
        {t('pwa.update.reload')}
      </button>
      <button type="button" className="ml-btn ml-update__close" data-pwa="later" aria-label={t('pwa.update.later')} onClick={pwa.dismissUpdate}>
        <Icon name="close" />
      </button>
    </div>
  );
}

/** "Install app" where the browser offers it; on Safari for iOS the short hint to the Share menu instead. */
export function InstallEntry({ pwa }: { pwa?: PwaPort }): React.JSX.Element | null {
  const { t } = useT();
  const state = usePwa(pwa);
  if (!pwa || state.install === null) return null;
  if (state.install === 'ios') return <p className="ml-hint ml-install-hint" data-pwa="ios-hint">{t('pwa.install.ios')}</p>;
  return (
    <button type="button" className="ml-btn" data-pwa="install" onClick={() => void pwa.promptInstall()}>
      <Icon name="plus" />
      <span>{t('pwa.install')}</span>
    </button>
  );
}
