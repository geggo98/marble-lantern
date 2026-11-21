// Topic page "classical": placeholder skeleton of WP1. WP4 to WP6 replace the section list with sections that carry figures;
// the texts come from about/i18n/{en,de}/classical.json.
import en from '../i18n/en/classical.json';
import de from '../i18n/de/classical.json';
import { makeAboutCatalog } from '../i18n/catalog';
import type { SameKeys } from '../i18n/catalog';
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { OUTLINE } from './outline';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;

export const topic = defineTopic({
  id: 'classical',
  catalog: makeAboutCatalog({ en, de }),
  sections: OUTLINE.classical.map((slug) => ({ slug })),
  reading: ['1', '2', '3', '4', '23', '19'],
});

export default function ClassicalPage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
