// Topic page "architecture" (topic 1): the sections of the outline, with the figures V4 (modules), V1 (offline), V2 (online) and
// V3 (training pipeline) under their sections. The texts come from about/i18n/{en,de}/architecture.json.
import en from '../i18n/en/architecture.json';
import de from '../i18n/de/architecture.json';
import { makeAboutCatalog } from '../i18n/catalog';
import type { AboutCatalog, SameKeys } from '../i18n/catalog';
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { ArchFigure } from '../figures/ArchFigure';
import { TreeFigure } from '../figures/TreeFigure';
import { OUTLINE } from './outline';

export const catalogsMatch: SameKeys<typeof en, typeof de> = true;

const catalog = makeAboutCatalog({ en, de });
const shared = catalog as unknown as AboutCatalog<string>;

/** The figure of a section, by slug; sections without a figure are plain text. */
const FIGURES: Record<string, () => React.JSX.Element> = {
  modules: () => <TreeFigure catalog={shared} number={[1, 1]} />,
  offline: () => <ArchFigure catalog={shared} kind="offline" number={[1, 2]} />,
  online: () => <ArchFigure catalog={shared} kind="online" number={[1, 3]} />,
  training: () => <ArchFigure catalog={shared} kind="pipeline" number={[1, 4]} />,
};

export const topic = defineTopic({
  id: 'architecture',
  catalog,
  sections: OUTLINE.architecture.map((slug) => {
    const figure = FIGURES[slug];
    return figure ? { slug, figure } : { slug };
  }),
  reading: ['24', '25', '26', '27'],
});

export default function ArchitecturePage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
