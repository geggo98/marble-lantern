// Topic page "training": the prose comes from the catalog (about/i18n/{en,de}/training.json), the figures V13, V11 and V12 stand in the
// sections the outline names (plan sections 2.4 and 3).
import en from '../i18n/en/training.json';
import de from '../i18n/de/training.json';
import type { SameKeys } from '../i18n/catalog';
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { trainingCatalog } from '../figures/catalogs';
import { LoopFigure } from '../figures/LoopFigure';
import { LrFigure } from '../figures/LrFigure';
import { PooledFigure } from '../figures/PooledFigure';
import { OUTLINE } from './outline';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;

const FIGURES: Record<string, () => React.ReactNode> = {
  learning: () => <LrFigure />,
  loop: () => <LoopFigure />,
  throughput: () => <PooledFigure />,
};

export const topic = defineTopic({
  id: 'training',
  catalog: trainingCatalog,
  sections: OUTLINE.training.map((slug) => {
    const figure = FIGURES[slug];
    return figure ? { slug, figure } : { slug };
  }),
  reading: ['9', '10', '11', '13', '14', '15', '16'],
});

export default function TrainingPage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
