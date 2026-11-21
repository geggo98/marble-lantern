// The glossary page: every term of every topic, alphabetical in the active language, a filter by kind and by text, and deep links
// `#/about/glossary/<term-id>` that scroll to the term, give it the focus and highlight it. The popover of a term links here.
import { useMemo, useRef, useState } from 'react';
import { allTerms, englishToShow, glossaryWord } from '../i18n/glossary';
import { sectionId } from '../routes';
import { TopicFrame, useScrollAndFocus } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { filterTerms, termKind } from './glossary-filter';
import type { KindFilter } from './glossary-filter';
import { Markup } from '../kit/markup';
import { InternalTag } from '../kit/InternalTag';
import { useT } from '../../ui/texts';
import './glossary.css';

const KINDS: readonly KindFilter[] = ['all', 'ds', 'eng', 'internal'];

export default function GlossaryPage({ topic, section }: TopicPageProps): React.JSX.Element {
  const { language } = useT();
  const lang = language === 'de' ? 'de' : 'en';
  const word = (key: string): string => glossaryWord(lang, key);
  const h1 = useRef<HTMLHeadingElement>(null);
  const [kind, setKind] = useState<KindFilter>('all');
  const [query, setQuery] = useState('');
  const terms = useMemo(() => allTerms(lang), [lang]);
  const shown = useMemo(() => filterTerms(terms, kind, query), [terms, kind, query]);
  useScrollAndFocus(topic, terms.some((e) => e.id === section) ? section : undefined, h1);

  return (
    <TopicFrame topic={topic}>
      <header className="ml-about__head">
        <h1 ref={h1} tabIndex={-1} className="ml-page__title">{word('title')}</h1>
        <p className="ml-page__lead">{word('lead')}</p>
      </header>
      <div className="ml-glossary__filter">
        <label className="ml-glossary__search">
          <span className="ml-kicker">{word('filter.label')}</span>
          <input
            type="search"
            className="ml-glossary__input"
            value={query}
            placeholder={word('filter.placeholder')}
            onChange={(e) => setQuery(e.currentTarget.value)}
          />
        </label>
        <div className="ml-glossary__kinds" role="group" aria-label={word('filter.label')}>
          {KINDS.map((k) => (
            <button key={k} type="button" className="ml-btn" aria-pressed={kind === k} data-kind={k} onClick={() => setKind(k)}>
              {word(`filter.${k}`)}
            </button>
          ))}
        </div>
      </div>
      <p className={shown.length === 0 ? "ml-hint" : "ml-visually-hidden"} role="status">{shown.length === 0 ? word('empty') : ''}</p>
      <dl className="ml-about__glossary">
        {shown.map((e) => {
          const target = e.id === section;
          return (
            <div key={e.id} className={target ? 'ml-about__term ml-about__term--target' : 'ml-about__term'} data-kind={termKind(e)}>
              <dt id={sectionId(topic, e.id)} tabIndex={-1} {...(target ? { 'aria-current': 'location' as const } : {})}>
                {e.name}
                {(() => {
                  const english = englishToShow(lang, e.name, e.english);
                  return english === undefined ? null : (
                    <span className="ml-term__en" lang="en" title={word('english')}> ({english})</span>
                  );
                })()}
                {e.internal === true && <InternalTag label={word('internal')} />}
              </dt>
              <dd>
                <Markup text={e.short} />
                {e.long !== undefined && (
                  <span className="ml-glossary__long">
                    <Markup text={e.long} />
                  </span>
                )}
                {e.internal === true && <span className="ml-glossary__hint">{word('internal.hint')}</span>}
                <span className="ml-tag ml-glossary__kind">{word(`kind.${termKind(e)}`)}</span>
              </dd>
            </div>
          );
        })}
      </dl>
    </TopicFrame>
  );
}
