// Topic page "classical" (topic 2): the sections of the outline, with the figures V5 (alpha-beta), V7 (budget) and V6 (evaluation)
// under their sections. The race table V8 is not built: its data file race.json does not exist. The texts come from
// about/i18n/{en,de}/classical.json.
import en from '../i18n/en/classical.json';
import de from '../i18n/de/classical.json';
import { makeAboutCatalog } from '../i18n/catalog';
import type { AboutCatalog, SameKeys } from '../i18n/catalog';
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { AbStartLink } from '../figures/AbStartLink';
import { AlphaBetaFigure } from '../figures/AlphaBetaFigure';
import { BudgetFigure } from '../figures/BudgetFigure';
import { EvalFigure } from '../figures/EvalFigure';
import { RootScoresFigure } from '../figures/RootScoresFigure';
import { OUTLINE } from './outline';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;

const catalog = makeAboutCatalog({ en, de });
const shared = catalog as unknown as AboutCatalog<string>;

const FIGURES: Record<string, () => React.JSX.Element> = {
  // The two sections before the figure link to it with the start position selected (spec v5, section 9, finding 13).
  'game-tree': () => <AbStartLink catalog={shared} />,
  minimax: () => <AbStartLink catalog={shared} />,
  'alpha-beta': () => (
    <>
      <AlphaBetaFigure catalog={shared} number={[2, 1]} />
      <RootScoresFigure catalog={shared} number={[2, 1]} />
    </>
  ),
  budget: () => <BudgetFigure catalog={shared} number={[2, 2]} />,
  evaluation: () => <EvalFigure catalog={shared} number={[2, 3]} />,
};

export const topic = defineTopic({
  id: 'classical',
  catalog,
  sections: OUTLINE.classical.map((slug) => {
    const figure = FIGURES[slug];
    return figure ? { slug, figure } : { slug };
  }),
  reading: ['1', '2', '3', '4', '23', '19'],
});

export default function ClassicalPage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
