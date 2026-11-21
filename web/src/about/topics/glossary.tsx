// The glossary page: every term of every topic, alphabetical in the active language. The section of the route is a term id.
import { useRef } from 'react';
import { allTerms } from '../i18n/glossary';
import { sectionId } from '../routes';
import { TopicFrame, useScrollAndFocus } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { useT } from '../../ui/texts';

export default function GlossaryPage({ topic, section }: TopicPageProps): React.JSX.Element {
  const { t, language } = useT();
  const h1 = useRef<HTMLHeadingElement>(null);
  const terms = allTerms(language === 'de' ? 'de' : 'en');
  useScrollAndFocus(topic, terms.some((e) => e.id === section) ? section : undefined, h1);
  return (
    <TopicFrame topic={topic}>
      <header className="ml-about__head">
        <h1 ref={h1} tabIndex={-1} className="ml-page__title">{t('about_learn_glossary_title')}</h1>
        <p className="ml-page__lead">{t('about_learn_glossary_teaser')}</p>
      </header>
      <dl className="ml-about__glossary">
        {terms.map((e) => (
          <div key={e.id} className="ml-about__term">
            <dt id={sectionId(topic, e.id)} tabIndex={-1}>
              {e.name}
              {e.english !== undefined && e.english !== e.name && <span className="ml-term__en"> ({e.english})</span>}
            </dt>
            <dd>
              {e.short}
              {e.long !== undefined && <> {e.long}</>}
            </dd>
          </div>
        ))}
      </dl>
    </TopicFrame>
  );
}
