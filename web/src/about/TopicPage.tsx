// The skeleton of a topic page: breadcrumb, h1, lead, "On this page", sections, reading list, previous and next.
// A topic module (about/topics/<topic>.tsx) hands it its catalogs, the section list and the reading ids; a section may carry a figure.
// This file and everything it imports live in the lazy chunk of the topic, never in the main bundle.
import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { ABOUT_TOPICS, sectionId } from './routes';
import type { AboutTopic } from './routes';
import { hasKey } from './i18n/catalog';
import type { AboutCatalog } from './i18n/catalog';
import { useChrome } from './i18n/chrome';
import { READING } from './reading';
import { Markup } from './kit/markup';
import { prefersReducedMotion } from '../ui/primitives';
import { hrefForAbout } from '../ui/Chrome';
import { useT } from '../ui/texts';
import { useWide } from '../ui/useMedia';
import './kit/topic.css';

export interface SectionSpec {
  slug: string;
  /** A figure under the section's paragraphs (WP4 to WP6). */
  figure?: () => ReactNode;
}

export interface TopicDef {
  id: Exclude<AboutTopic, 'glossary'>;
  catalog: AboutCatalog<string>;
  sections: readonly SectionSpec[];
  /** Ids into READING; the note of each is the catalog key `read.<id>.note`. */
  reading: readonly string[];
}

export interface TopicPageProps {
  topic: AboutTopic;
  section?: string | undefined;
  /** The "On this page" list replaces the history entry, so that Back leaves the topic (plan section 1.2). */
  onJumpSection: (section: string) => void;
}

/** Scroll and focus after a route change (plan section 1.2): the top and the h1, or the section and its heading. */
export function useScrollAndFocus(topic: AboutTopic, section: string | undefined, h1: React.RefObject<HTMLElement | null>): void {
  useEffect(() => {
    const target = section === undefined ? null : document.getElementById(sectionId(topic, section));
    if (target) {
      target.scrollIntoView?.({ block: 'start', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
      target.focus({ preventScroll: true });
      return;
    }
    window.scrollTo?.(0, 0);
    h1.current?.focus({ preventScroll: true });
  }, [topic, section, h1]);
}

/** The previous and next topic around `topic`, in the order of ABOUT_TOPICS. */
export function neighbours(topic: AboutTopic): { prev?: AboutTopic; next?: AboutTopic } {
  const i = ABOUT_TOPICS.indexOf(topic);
  const prev = ABOUT_TOPICS[i - 1];
  const next = ABOUT_TOPICS[i + 1];
  return { ...(prev === undefined ? {} : { prev }), ...(next === undefined ? {} : { next }) };
}

export function TopicNav({ topic }: { topic: AboutTopic }): React.JSX.Element {
  const { t } = useT();
  const c = useChrome();
  const { prev, next } = neighbours(topic);
  return (
    <nav className="ml-about__pn" aria-label={t('about_learn_title')}>
      {prev ? (
        <a className="ml-btn" rel="prev" href={hrefForAbout(prev)}>
          <span className="ml-kicker">{c('prev')}</span>
          <span>{t(`about_learn_${prev}_title`)}</span>
        </a>
      ) : (
        <span />
      )}
      {next && (
        <a className="ml-btn ml-about__next" rel="next" href={hrefForAbout(next)}>
          <span className="ml-kicker">{c('next')}</span>
          <span>{t(`about_learn_${next}_title`)}</span>
        </a>
      )}
    </nav>
  );
}

export function TopicFrame({ topic, children, aside }: { topic: AboutTopic; children: ReactNode; aside?: ReactNode }): React.JSX.Element {
  const { t } = useT();
  return (
    <main className="ml-page ml-about" data-topic={topic}>
      <nav aria-label={t('about_learn_back')}>
        <a className="ml-about__crumb" href={hrefForAbout()}>{`← ${t('about_learn_back')}`}</a>
      </nav>
      <div className={aside ? 'ml-about__layout ml-about__layout--rail' : 'ml-about__layout'}>
        {aside}
        <div className="ml-about__body">
          {children}
          <TopicNav topic={topic} />
        </div>
      </div>
    </main>
  );
}

/** Ties the typed catalog of a topic module to the skeleton, which looks keys up by string. */
export function defineTopic<K extends string>(def: Omit<TopicDef, 'catalog'> & { catalog: AboutCatalog<K> }): TopicDef {
  return { ...def, catalog: def.catalog as unknown as AboutCatalog<string> };
}

export function TopicPage({ topic, section, onJumpSection, def }: TopicPageProps & { def: TopicDef }): React.JSX.Element {
  const c = useChrome();
  const text = def.catalog.useAboutT();
  const wide = useWide();
  const h1 = useRef<HTMLHeadingElement>(null);
  useScrollAndFocus(topic, def.sections.some((s) => s.slug === section) ? section : undefined, h1);

  const list = (
    <ol className="ml-about__toclist">
      {def.sections.map((s) => (
        <li key={s.slug}>
          <a
            href={hrefForAbout(topic, s.slug)}
            onClick={(event) => {
              if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
              event.preventDefault();
              onJumpSection(s.slug);
            }}
          >
            {text(`toc.${s.slug}`)}
          </a>
        </li>
      ))}
    </ol>
  );
  const toc = wide ? (
    <nav className="ml-about__toc ml-about__toc--rail" aria-labelledby="about-toc-title">
      <p id="about-toc-title" className="ml-kicker">{c('toc')}</p>
      {list}
    </nav>
  ) : (
    <nav className="ml-about__toc" aria-label={c('toc')}>
      <details>
        <summary>{c('toc')}</summary>
        {list}
      </details>
    </nav>
  );

  const reading = def.reading.map((id) => READING[id]).filter((e) => e !== undefined);
  return (
    <TopicFrame topic={topic} aside={wide ? toc : undefined}>
      <header className="ml-about__head">
        <h1 ref={h1} tabIndex={-1} className="ml-page__title">{text('title')}</h1>
        <p className="ml-page__lead"><Markup text={text('lead')} /></p>
        {!wide && toc}
      </header>
      {def.sections.map((s) => {
        const paragraphs: string[] = [];
        for (let n = 1; hasKey(def.catalog, `sec.${s.slug}.p${n}`); n++) paragraphs.push(text(`sec.${s.slug}.p${n}`));
        const id = sectionId(topic, s.slug);
        return (
          <section key={s.slug} className="ml-about__section" aria-labelledby={id}>
            <h2 id={id} tabIndex={-1} className="ml-about__h2">{text(`sec.${s.slug}.title`)}</h2>
            <div className="ml-prose">
              {paragraphs.map((p, i) => (
                <p key={i}><Markup text={p} /></p>
              ))}
            </div>
            {s.figure?.()}
          </section>
        );
      })}
      {reading.length > 0 && (
        <section className="ml-about__section" aria-labelledby={`about-${topic}-reading`}>
          <h2 id={`about-${topic}-reading`} className="ml-about__h2">{c('reading')}</h2>
          <ul className="ml-about__reading">
            {reading.map((e) => (
              <li key={e.id}>
                <a className="ml-about-link" href={e.url} rel="noreferrer">{e.title}</a>
                <span className="ml-about__by">{[e.authors, e.year].filter((x) => x !== undefined).join(', ')}</span>
                {hasKey(def.catalog, `read.${e.id}.note`) && <span> <Markup text={text(`read.${e.id}.note`)} /></span>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </TopicFrame>
  );
}
