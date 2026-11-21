// Shared parts of the figures of topics 1 and 2: the lookups of the topic catalog and the kit catalog, plain text from catalog markup,
// the foot of a figure with its source chips and its data chip.
import { Chip } from '../kit';
import './figures.css';
import { parseMarkup } from '../kit/markup';
import { sourceUrl } from '../kit/SourceChip';
import { kitCatalog } from '../i18n/kit';
import type { AboutCatalog, AboutT } from '../i18n/catalog';
import { useT } from '../../ui/texts';
import type { KitT } from '../kit';

export type Catalog = AboutCatalog<string>;
export type Lookup = AboutT<string>;

/** Catalog markup as plain text, for the places that take no React nodes: an SVG title, a status line, an aria label. */
export function plainText(text: string, n: (value: number) => string): string {
  return parseMarkup(text, () => undefined)
    .map((node) => {
      switch (node.type) {
        case 'text':
        case 'code':
          return node.text;
        case 'n':
          return n(node.value);
        case 'term':
          return node.text ?? node.id;
        case 'src':
          return node.path;
        case 'sec':
          return node.text ?? `${node.topic}/${node.section}`;
      }
    })
    .join('');
}

export interface FigText {
  /** The topic catalog. */
  t: Lookup;
  /** The shared kit catalog (`kit.*`). */
  kit: Lookup;
  /** The kit catalog as the typed lookup of the kit helpers (stepperLabels, boxDiagramLabels). */
  kitT: KitT;
  n: (value: number) => string;
  /** A word of the app's own texts (side_a, side_b). */
  ui: (key: string) => string;
  /** A topic key as plain text (markup resolved). */
  plain: (key: string, values?: Record<string, string | number>) => string;
  language: string;
}

export function useFigText(catalog: Catalog): FigText {
  const t = catalog.useAboutT();
  const kit = (kitCatalog as unknown as Catalog).useAboutT();
  const { n, language, t: ui } = useT();
  return {
    t,
    kit,
    kitT: (key, values) => kit(key, values),
    n,
    ui: (key) => ui(key as never),
    plain: (key, values) => plainText(t(key, values), n),
    language,
  };
}

export const DATA_DIR = 'web/src/about/data';

/** Source chips (real paths of the repository) and the data chip that names the JSON file of the figure. */
export function FigFoot({ kit, paths, data }: { kit: Lookup; paths: readonly string[]; data: string }): React.JSX.Element {
  return (
    <>
      {paths.map((p) => (
        <Chip key={p} href={sourceUrl(p)} prefix={kit('kit.chip.src')}>
          {p}
        </Chip>
      ))}
      <Chip data href={sourceUrl(`${DATA_DIR}/${data}`)} prefix={kit('kit.chip.data')}>
        {data}
      </Chip>
    </>
  );
}

/** "Fig. 1.2": the label word of the catalog and the number of the topic and of the figure in it. */
export const figLabel = (kit: Lookup, topicNo: number, figNo: number): string => `${kit('kit.fig.label')} ${topicNo}.${figNo}`;

export function useReducedMotion(): boolean {
  // The kit's own hook (useStepper) reads the same media query; the figures only need the answer for static marks.
  return typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)').matches : false;
}
