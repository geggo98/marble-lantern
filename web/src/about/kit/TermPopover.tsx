// A glossary term in running text: a button with a dotted underline that opens a small popover with the short definition and a link
// to the glossary. One code path for every browser (the disclosure of plan section 1.3, without the native `popover` attribute, so
// that jsdom tests see the same behaviour): Escape closes it and returns the focus to the term; a press outside closes it.
import { useEffect, useId, useRef, useState } from 'react';
import { termEntry } from '../i18n/glossary';
import { useT } from '../../ui/texts';
import { hrefForAbout } from '../../ui/Chrome';
import { useChrome } from '../i18n/chrome';

export function TermPopover({ id, text }: { id: string; text?: string }): React.JSX.Element {
  const { language } = useT();
  const c = useChrome();
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  const wrap = useRef<HTMLSpanElement>(null);
  const panelId = useId();
  const entry = termEntry(id, language === 'de' ? 'de' : 'en');

  useEffect(() => {
    if (!open) return;
    const onPress = (event: Event): void => {
      if (wrap.current && event.target instanceof Node && !wrap.current.contains(event.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', onPress);
    return () => document.removeEventListener('pointerdown', onPress);
  }, [open]);

  if (!entry) return <>{text ?? id}</>;
  return (
    <span
      ref={wrap}
      className="ml-term"
      onKeyDown={(event) => {
        if (event.key === 'Escape' && open) {
          event.stopPropagation();
          setOpen(false);
          button.current?.focus();
        }
      }}
    >
      <button
        ref={button}
        type="button"
        className="ml-term__btn"
        aria-expanded={open}
        {...(open ? { 'aria-controls': panelId } : {})}
        data-term={id}
        onClick={() => setOpen((v) => !v)}
      >
        {text ?? entry.name}
      </button>
      {open && (
        <span id={panelId} className="ml-term__pop" role="group" aria-label={entry.name}>
          <strong>{entry.name}</strong>
          {entry.english !== undefined && entry.english !== entry.name && <span className="ml-term__en"> ({entry.english})</span>}
          <span className="ml-term__short">{entry.short}</span>
          <a className="ml-about-link" href={hrefForAbout('glossary', id)}>
            {c('glossary_more')}
          </a>
        </span>
      )}
    </span>
  );
}
