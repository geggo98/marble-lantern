// Topic page "inference": the prose comes from the catalog (about/i18n/{en,de}/inference.json), the figures V9a, V9b and V10 stand in
// the sections the outline names (plan sections 2.3 and 3).
import en from '../i18n/en/inference.json';
import de from '../i18n/de/inference.json';
import type { SameKeys } from '../i18n/catalog';
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { inferenceCatalog } from '../figures/catalogs';
import { BackendsFigure, ForwardFigure } from '../figures/ForwardFigure';
import { McstFigure } from '../figures/McstFigure';
import { NetFigure } from '../figures/NetFigure';
import { OUTLINE } from './outline';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;

const FIGURES: Record<string, () => React.ReactNode> = {
  'hex-conv': () => <NetFigure />,
  'forward-pass': () => <ForwardFigure />,
  mcts: () => <McstFigure />,
  backends: () => <BackendsFigure />,
};

export const topic = defineTopic({
  id: 'inference',
  catalog: inferenceCatalog,
  sections: OUTLINE.inference.map((slug) => {
    const figure = FIGURES[slug];
    return figure ? { slug, figure } : { slug };
  }),
  reading: ['5', '12', '10', '9', '8', '7', '24', '28', '30'],
});

export default function InferencePage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
