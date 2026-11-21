// Topic page "architecture": placeholder skeleton of WP1. WP4 to WP6 replace the section list with sections that carry figures;
// the texts come from about/i18n/{en,de}/architecture.json.
import en from '../i18n/en/architecture.json';
import de from '../i18n/de/architecture.json';
import { makeAboutCatalog } from '../i18n/catalog';
import type { SameKeys } from '../i18n/catalog';
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { OUTLINE } from './outline';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;

export const topic = defineTopic({
  id: 'architecture',
  catalog: makeAboutCatalog({ en, de }),
  sections: OUTLINE.architecture.map((slug) => ({ slug })),
  reading: ['24', '25', '26', '27'],
});

export default function ArchitecturePage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
