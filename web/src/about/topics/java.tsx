// Topic page "java" (topic 6): high-speed Java. The sections of the outline, with the speed chart J1 in `overview` and the code snippets
// (data/java-snippets.json, cut from the repository) under the sections that explain them. The texts come from about/i18n/{en,de}/java.json.
// Snippet captions are the catalog keys `snip.<id>.caption`, the generated Hilla client (hgen) included: its chip cannot link to GitHub, so the caption says that the file is generated and not in Git.
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { Snippet } from '../figures/Snippet';
import { JavaSpeedFigure } from '../figures/JavaSpeedFigure';
import { JavaPlainFigure } from '../figures/JavaPlainFigure';
import { SimdFigure } from '../figures/SimdFigure';
import { javaCatalog } from '../figures/java-catalog';
import type { Catalog } from '../figures/common';
import { OUTLINE } from './outline';

export { catalogsMatch } from '../figures/java-catalog';

const shared = javaCatalog as unknown as Catalog;

const Snip = (props: { id: string; elideAfter?: boolean }): React.JSX.Element => <Snippet catalog={shared} {...props} />;

const FIGURES: Record<string, () => React.ReactNode> = {
  overview: () => <JavaSpeedFigure catalog={shared} number={[6, 1]} />,
  // Figure slots, to be filled (catalog prefixes in figures/java-catalog keys): J2 `fig.javaplain` in plain-java, J3 `fig.simd` in vector-api,
  // J4 `fig.gpu` and J5 `fig.j5` in tornadovm. Each figure goes above or between the snippets of its section.
  'plain-java': () => (
    <>
      <JavaPlainFigure catalog={shared} number={[6, 2]} />
      <Snip id="a1" />
      <Snip id="a2" elideAfter />
      <Snip id="a3" elideAfter />
    </>
  ),
  'vector-api': () => (
    <>
      <SimdFigure catalog={shared} number={[6, 3]} />
      <Snip id="d1" elideAfter />
      <Snip id="d2" />
    </>
  ),
  tornadovm: () => (
    <>
      <Snip id="b1" elideAfter />
      <Snip id="b2" />
    </>
  ),
  outlook: () => <Snip id="illus" />,
  more: () => (
    <>
      <Snip id="e1" />
      <Snip id="e3" />
    </>
  ),
};

export const topic = defineTopic({
  id: 'java',
  catalog: javaCatalog,
  sections: OUTLINE.java.map((slug) => {
    const figure = FIGURES[slug];
    return figure ? { slug, figure } : { slug };
  }),
  reading: ['32', '33', '34', '35', '36', '37', '38', '39', '40', '41', '45', '46', '47', '48', '49', '50', '51', '52'],
});

export default function JavaPage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
