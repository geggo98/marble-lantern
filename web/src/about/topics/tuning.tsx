// Topic page "tuning" (topic 5): the texts come from about/i18n/{en,de}/tuning.json. Figure V14 (hyperparameter story and Elo ladder):
// the staircase stands in `what`, its panels in the sections of their experiments: Elo curve in `measuring`, futility grid in `grids`,
// MCTS sweeps in `sweeps`, sweep 1 in `recipe-sweep`.
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { EloFigure, FutilityFigure, StoryFigure, Sweep1Figure, SweepsFigure } from '../figures/tuning';
import { tuningCatalog } from '../figures/tuning-catalog';
import { OUTLINE } from './outline';

export { catalogsMatch } from '../figures/tuning-catalog';

const FIGURES: Record<string, () => React.ReactNode> = {
  what: () => <StoryFigure />,
  measuring: () => <EloFigure />,
  grids: () => <FutilityFigure />,
  sweeps: () => <SweepsFigure />,
  'recipe-sweep': () => <Sweep1Figure />,
};

export const topic = defineTopic({
  id: 'tuning',
  catalog: tuningCatalog,
  sections: OUTLINE.tuning.map((slug) => {
    const figure = FIGURES[slug];
    return figure ? { slug, figure } : { slug };
  }),
  reading: ['4', '17', '18', '19', '20', '21', '22', '16', '6', '29'],
});

export default function TuningPage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
