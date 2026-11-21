// Topic page "training": placeholder skeleton of WP1. WP4 to WP6 replace the section list with sections that carry figures;
// the texts come from about/i18n/{en,de}/training.json.
import en from '../i18n/en/training.json';
import de from '../i18n/de/training.json';
import { makeAboutCatalog } from '../i18n/catalog';
import type { SameKeys } from '../i18n/catalog';
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { OUTLINE } from './outline';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;

export const topic = defineTopic({
  id: 'training',
  catalog: makeAboutCatalog({ en, de }),
  sections: OUTLINE.training.map((slug) => ({ slug })),
  reading: ['9', '10', '11', '13', '14', '15', '16'],
});

export default function TrainingPage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
