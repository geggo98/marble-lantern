// One code snippet (data/java-snippets.json) with its caption from the catalog of the page that shows it (`snip.<id>.caption`) and the labels of the kit.
// Shared by the topics java and java-web. The generated Hilla client (hgen) has no GitHub chip; its caption says that the file is generated and not in Git.
import { CodeSnippet } from '../kit/CodeSnippet';
import { Markup } from '../kit/markup';
import { useFigText } from './common';
import type { Catalog } from './common';
import { hasKey } from '../i18n/catalog';

export function Snippet({ catalog, id, elideAfter = false }: { catalog: Catalog; id: string; elideAfter?: boolean }): React.JSX.Element {
  const f = useFigText(catalog);
  const key = `snip.${id}.caption`;
  const has = hasKey(catalog, key);
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
