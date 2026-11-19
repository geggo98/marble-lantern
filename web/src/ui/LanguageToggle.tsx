// The language switch (EN / DE). One component for the header and for every modal sheet: a modal sheet covers the header
// (and makes it inert), so a person who meets the "New game" dialog first could not change the language otherwise.
// In a dialog it stands LAST in the tab order, so useDialog's first focus stays on the primary button (data-autofocus).
import { i18n } from '../i18n/i18n';
import { LANGUAGES } from '../i18n/keys';
import { useT } from './texts';

export function LanguageToggle({ inDialog = false }: { inDialog?: boolean }): React.JSX.Element {
  const { t, language } = useT();
  return (
    <div
      className={inDialog ? 'ml-seg ml-seg--touch' : 'ml-seg'}
      role="group"
      aria-label={t('language')}
      data-language-toggle={inDialog ? 'dialog' : 'header'}
    >
      {LANGUAGES.map((l) => (
        <button
          key={l}
          type="button"
          className="ml-seg__opt"
          aria-pressed={language === l}
          lang={l}
          data-language={l}
          onClick={(event) => {
            // Safari does not focus a button that is clicked or tapped; without this the focus would fall to the page behind the dialog.
            event.currentTarget.focus();
            i18n.setLanguage(l);
          }}
        >
          {l.toUpperCase()}
        </button>
      ))}
    </div>
  );
}

/** The row at the end of a dialog that holds the toggle, with its label. */
export function DialogLanguageRow(): React.JSX.Element {
  const { t } = useT();
  return (
    <div className="ml-langrow">
      <span className="ml-langrow__label" aria-hidden="true">{t('language')}</span>
      <LanguageToggle inDialog />
    </div>
  );
}
