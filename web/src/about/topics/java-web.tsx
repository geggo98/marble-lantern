// Topic page "java-web" (topic 7): Java in the browser. Sections: overview, hilla (Java called over the network), teavm (Java compiled for the
// browser), worker (what may cross the boundary). The texts come from about/i18n/{en,de}/java-web.json; the code snippets h1, hgen, h3 and e2
// (data/java-snippets.json) stand under the sections that explain them. Captions are the catalog keys `snip.<id>.caption`.
// Figure slots, to be filled: J6 (Hilla data flow, catalog prefix `fig.hilla`) in `hilla`, J7 (TeaVM call boundary, prefix `fig.boundary`) in `teavm`.
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { Snippet } from '../figures/Snippet';
import { HillaFigure } from '../figures/HillaFigure';
import { BoundaryFigure } from '../figures/BoundaryFigure';
import { javaWebCatalog } from '../figures/java-web-catalog';
import type { Catalog } from '../figures/common';
import { OUTLINE } from './outline';

export { catalogsMatch } from '../figures/java-web-catalog';

const shared = javaWebCatalog as unknown as Catalog;
const Snip = (props: { id: string; elideAfter?: boolean }): React.JSX.Element => <Snippet catalog={shared} {...props} />;

const FIGURES: Record<string, () => React.ReactNode> = {
  hilla: () => (
    <>
      <HillaFigure catalog={shared} number={[7, 1]} />
      <Snip id="h1" elideAfter />
      <Snip id="hgen" />
      <Snip id="h3" />
    </>
  ),
  teavm: () => (
    <>
      <BoundaryFigure />
      <Snip id="e2" />
    </>
  ),
};

export const topic = defineTopic({
  id: 'java-web',
  catalog: javaWebCatalog,
  sections: OUTLINE['java-web'].map((slug) => {
    const figure = FIGURES[slug];
    return figure ? { slug, figure } : { slug };
  }),
  reading: ['25', '26', '42', '43', '44', '60', '61', '62', '63', '64', '65', '66', '67', '68'],
});

export default function JavaWebPage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
