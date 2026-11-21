// Topic page "java" (topic 6): Java in practice. The sections of the outline, with the speed chart J1 in `overview` and the code snippets
// (data/java-snippets.json, cut from the repository) under the sections that explain them. The texts come from about/i18n/{en,de}/java.json.
// Snippet captions are the catalog keys `snip.<id>.caption`, the generated Hilla client (hgen) included: its chip cannot link to GitHub, so the caption says that the file is generated and not in Git.
import { TopicPage, defineTopic } from '../TopicPage';
import type { TopicPageProps } from '../TopicPage';
import { CodeSnippet } from '../kit/CodeSnippet';
import { Markup } from '../kit/markup';
import { JavaSpeedFigure } from '../figures/JavaSpeedFigure';
import { javaCatalog } from '../figures/java-catalog';
import { useFigText } from '../figures/common';
import type { Catalog } from '../figures/common';
import { hasKey } from '../i18n/catalog';
import { OUTLINE } from './outline';

export { catalogsMatch } from '../figures/java-catalog';

const shared = javaCatalog as unknown as Catalog;

/** One snippet with its caption from the catalog and the labels of the kit. */
export function Snippet({ id, elideAfter = false }: { id: string; elideAfter?: boolean }): React.JSX.Element {
  const f = useFigText(shared);
  const key = `snip.${id}.caption`;
  const has = hasKey(shared, key);
  return (
    <CodeSnippet
      id={id}
      {...(has ? { caption: <Markup text={f.t(key)} />, captionText: f.plain(key) } : {})}
      elideAfter={elideAfter}
      labels={{ copy: f.kit('kit.code.copy'), copied: f.kit('kit.code.copied'), copyFailed: f.kit('kit.code.copyfailed') }}
      linesLabel={(from, to) => f.kit('kit.code.lines', { from, to })}
    />
  );
}

const FIGURES: Record<string, () => React.ReactNode> = {
  overview: () => <JavaSpeedFigure catalog={shared} number={[6, 1]} />,
  'plain-java': () => (
    <>
      <Snippet id="a1" />
      <Snippet id="a2" elideAfter />
      <Snippet id="a3" elideAfter />
    </>
  ),
  'vector-api': () => (
    <>
      <Snippet id="d1" elideAfter />
      <Snippet id="d2" />
    </>
  ),
  tornadovm: () => (
    <>
      <Snippet id="b1" elideAfter />
      <Snippet id="b2" />
    </>
  ),
  outlook: () => <Snippet id="illus" />,
  more: () => (
    <>
      <Snippet id="e1" />
      <Snippet id="e2" />
      <Snippet id="e3" />
    </>
  ),
  hilla: () => (
    <>
      <Snippet id="h1" elideAfter />
      <Snippet id="hgen" />
      <Snippet id="h3" />
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
  reading: ['32', '33', '34', '35', '36', '37', '38', '39', '40', '41', '42', '43', '44'],
});

export default function JavaPage(props: TopicPageProps): React.JSX.Element {
  return <TopicPage {...props} def={topic} />;
}
