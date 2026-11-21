// Topic page "inference": placeholder skeleton of WP1. WP4 to WP6 replace the section list with sections that carry figures;
// the texts come from about/i18n/{en,de}/inference.json.
import en from '../i18n/en/inference.json';
import de from '../i18n/de/inference.json';
import { makeAboutCatalog } from '../i18n/catalog';
import type { SameKeys } from '../i18n/catalog';
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { OUTLINE } from './outline';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;

export const topic = defineTopic({
  id: 'inference',
  catalog: makeAboutCatalog({ en, de }),
  sections: OUTLINE.inference.map((slug) => ({ slug })),
  reading: ['5', '12', '10', '9', '8', '7', '24'],
});

export default function InferencePage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
