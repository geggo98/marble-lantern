// Helpers that all figures of topics 3 and 4 share: text lookup (topic catalog, kit catalog, plain text for SVG titles), number
// formats, source and data chips, the compact switch. Text always comes from a catalog; numbers come from the data files.
import { useRef } from 'react';
import type { RefObject } from 'react';
import { useT } from '../../ui/texts';
import { kitCatalog } from '../i18n/kit';
import type { AboutCatalog } from '../i18n/catalog';
import { Chip, PLOT_BREAKPOINT_PX, useMeasuredWidth } from '../kit';
import { parseMarkup } from '../kit/markup';
import { sciPlain } from '../kit/sci';
import { sourceUrl } from '../kit/SourceChip';
import './figures.css';

type Values = Record<string, string | number>;

/** A catalog string without its markup tokens, for the title, desc and aria-label of an SVG (`{n:1.0}` becomes the number). */
export function flattenMarkup(text: string, n: (v: number) => string, language = 'en'): string {
  return parseMarkup(text, () => undefined)
    .map((node) => {
      switch (node.type) {
        case 'text':
        case 'code':
          return node.text;
        case 'n':
          return n(node.value);
        case 'sci':
          return sciPlain(node, language);
        case 'src':
          return node.path;
        case 'sec':
          return node.text ?? `${node.topic}/${node.section}`;
        case 'term':
          return node.text ?? node.id;
      }
    })
    .join('');
}

export interface Fmt {
  /** `0.5940` -> "0.594" / "0,594" with exactly `digits` decimals. */
  fixed: (value: number, digits: number) => string;
  /** With a sign: "+338", "−12" (a real minus sign). */
  signed: (value: number, digits?: number) => string;
  n: (value: number) => string;
}

export function useFmt(): Fmt {
  const { n, language } = useT();
  const fixed = (value: number, digits: number): string =>
    new Intl.NumberFormat(language, { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(value);
  const signed = (value: number, digits = 0): string => {
    const body = fixed(Math.abs(value), digits);
    return value < 0 ? `−${body}` : value > 0 ? `+${body}` : body;
  };
  return { fixed, signed, n };
}

export interface FigText {
  /** Raw catalog string with `{name}` filled in; markup tokens stay (render with `<Markup>`). */
  t: (key: string, values?: Values) => string;
  /** The same without markup tokens. */
  plain: (key: string, values?: Values) => string;
  kit: (key: string, values?: Values) => string;
  /** `key.short` in the compact layout when the catalog has it, else `key`. */
  pick: (key: string, compact: boolean, values?: Values) => string;
  fmt: Fmt;
  language: string;
}

export function useFigText<K extends string>(typed: AboutCatalog<K>): FigText {
  // The figures look keys up by string (`fig.${id}.${part}`); the parity tests of the catalogs and the figure tests check that every key exists.
  const catalog = typed as unknown as AboutCatalog<string>;
  const text = catalog.useAboutT();
  const kitText = kitCatalog.useAboutT();
  const fmt = useFmt();
  const { language } = useT();
  const has = (key: string): boolean => key in catalog.catalogs.en;
  return {
    t: (key, values) => text(key, values),
    plain: (key, values) => flattenMarkup(text(key, values), fmt.n, language),
    kit: (key, values) => (kitText as (k: string, v?: Values) => string)(key, values),
    pick: (key, compact, values) => text(compact && has(`${key}.short`) ? `${key}.short` : key, values),
    fmt,
    language,
  };
}

/** True while the figure is narrower than the figure breakpoint (640 px); attach `ref` to an element inside the figure. */
export function useCompact(): { ref: RefObject<HTMLDivElement | null>; compact: boolean } {
  const ref = useRef<HTMLDivElement>(null);
  const width = useMeasuredWidth(ref, '.ml-fig');
  return { ref, compact: width > 0 && width < PLOT_BREAKPOINT_PX };
}

export const DATA_DIR = 'web/src/about/data';

/** The foot of a figure: source chips (real repository paths) and a data chip that names the JSON file. */
export function FootChips({ fig, sources, data }: { fig: FigText; sources: readonly string[]; data: readonly string[] }): React.JSX.Element {
  return (
    <>
      {sources.map((p) => (
        <Chip key={p} prefix={fig.kit('kit.chip.src')} href={sourceUrl(p)}>
          {p}
        </Chip>
      ))}
      {data.map((f) => (
        <Chip key={f} data prefix={fig.kit('kit.chip.data')} href={sourceUrl(`${DATA_DIR}/${f}`)}>
          {f}
        </Chip>
      ))}
    </>
  );
}

export { parseDocNumber, parseSigned } from './numbers';
