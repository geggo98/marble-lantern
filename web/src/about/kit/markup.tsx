// The inline markup of catalog strings (plan section 1.6), parsed into React nodes. Never innerHTML.
//   {term:alpha-beta} or {term:alpha-beta|alpha-beta pruning}   a glossary term (id, optional visible text)
//   {src:search/src/main/java/sternhalma/search/AlphaBeta.java} a source chip
//   {sec:classical/alpha-beta|text}                             a link to a section of a topic (the text may hold `code`)
//   {n:20000}                                                   a number in the active language
//   {sci:3e-4}                                                  scientific notation, typeset as MathML (3 · 10⁻⁴); {sci:1.5e-4} keeps its mantissa
//   `code`                                                    inline code
// Nesting is refused: a token body holds no `{`, `}` or backtick, so `{term:a|{n:1}}` is not a term. A malformed or unknown token
// stays visible as text, and a development build warns, so that a typo in a catalog shows up in the first test run.
import { Fragment } from 'react';
import { termEntry } from '../i18n/glossary';
import { useT } from '../../ui/texts';
import { hrefForAbout } from '../../ui/Chrome';
import { isAboutTopic, isSlug } from '../routes';
import type { AboutTopic } from '../routes';
import { formatMantissa, parseSci, sciSpoken, showsMantissa } from './sci';
import { SourceChip } from './SourceChip';
import { TermPopover } from './TermPopover';

export type MarkupNode =
  | { type: 'text'; text: string }
  | { type: 'code'; text: string }
  | { type: 'term'; id: string; text?: string }
  | { type: 'src'; path: string }
  | { type: 'sec'; topic: AboutTopic; section: string; text?: string }
  | { type: 'n'; value: number }
  | { type: 'sci'; mantissa: number; exponent: number };

// A section link may hold `code` in its text ("{sec:inference/mcts|how MCTS uses `c`}"); every other token body holds no backtick.
const TOKEN = /`(?<code>[^`]+)`|\{(?<kind>sec):(?<secBody>[^{}]*)\}|\{(?<other>[A-Za-z]+):(?<body>[^{}`]*)\}/g;

const warnDev = (message: string): void => {
  if (import.meta.env.DEV) console.warn(`[about markup] ${message}`);
};

/** `a|b` gives ['a', 'b']; `a` gives ['a', undefined]. Further bars belong to the text. */
function splitBar(body: string): [string, string | undefined] {
  const i = body.indexOf('|');
  return i < 0 ? [body, undefined] : [body.slice(0, i), body.slice(i + 1)];
}

export function parseMarkup(source: string, warn: (message: string) => void = warnDev): MarkupNode[] {
  const nodes: MarkupNode[] = [];
  const text = (t: string): void => {
    if (t === '') return;
    const last = nodes[nodes.length - 1];
    if (last?.type === 'text') last.text += t;
    else nodes.push({ type: 'text', text: t });
  };
  let at = 0;
  for (const m of source.matchAll(TOKEN)) {
    text(source.slice(at, m.index));
    at = m.index + m[0].length;
    const g = m.groups as { code?: string; kind?: string; secBody?: string; other?: string; body?: string };
    const { code } = g;
    const kind = g.kind ?? g.other;
    const body = g.secBody ?? g.body ?? '';
    if (code !== undefined) {
      nodes.push({ type: 'code', text: code });
      continue;
    }
    const [head, label] = splitBar(body);
    if (kind === 'term' && isSlug(head) && label !== '') {
      nodes.push(label === undefined ? { type: 'term', id: head } : { type: 'term', id: head, text: label });
    } else if (kind === 'src' && head !== '' && label === undefined && !/\s/.test(head)) {
      nodes.push({ type: 'src', path: head });
    } else if (kind === 'sec' && label !== '') {
      const [topic, section, ...rest] = head.split('/');
      if (topic !== undefined && isAboutTopic(topic) && section !== undefined && isSlug(section) && rest.length === 0) {
        nodes.push(label === undefined ? { type: 'sec', topic, section } : { type: 'sec', topic, section, text: label });
      } else {
        warn(`bad section reference "${m[0]}"`);
        text(m[0]);
      }
    } else if (kind === 'n' && label === undefined && /^-?\d+(\.\d+)?$/.test(head)) {
      nodes.push({ type: 'n', value: Number(head) });
    } else if (kind === 'sci' && label === undefined && parseSci(head) !== undefined) {
      nodes.push({ type: 'sci', ...parseSci(head)! });
    } else {
      warn(kind !== undefined && ['term', 'src', 'sec', 'n', 'sci'].includes(kind) ? `malformed token "${m[0]}"` : `unknown token "${m[0]}"`);
      text(m[0]);
    }
  }
  text(source.slice(at));
  return nodes;
}

/** The text of a link: plain words and `code` spans only. */
function LinkText({ text }: { text: string }): React.JSX.Element {
  return (
    <>
      {text.split('`').map((part, i) => (i % 2 === 1 ? <code key={i}>{part}</code> : <Fragment key={i}>{part}</Fragment>))}
    </>
  );
}

export function Markup({ text }: { text: string }): React.JSX.Element {
  const { n, language } = useT();
  const nodes = parseMarkup(text);
  return (
    <>
      {nodes.map((node, i) => {
        switch (node.type) {
          case 'text':
            return <Fragment key={i}>{node.text}</Fragment>;
          case 'code':
            return <code key={i}>{node.text}</code>;
          case 'n':
            return <span key={i} className="ml-about-num">{n(node.value)}</span>;
          case 'sci':
            return (
              <math key={i} className="ml-sci" aria-label={sciSpoken(node, language)}>
                {showsMantissa(node) && (
                  <>
                    <mn>{formatMantissa(node.mantissa, language)}</mn>
                    <mo>·</mo>
                  </>
                )}
                <msup>
                  <mn>10</mn>
                  <mrow>
                    {node.exponent < 0 && <mo>−</mo>}
                    <mn>{Math.abs(node.exponent)}</mn>
                  </mrow>
                </msup>
              </math>
            );
          case 'src':
            return <SourceChip key={i} path={node.path} />;
          case 'sec':
            return (
              <a key={i} className="ml-about-link" href={hrefForAbout(node.topic, node.section)}>
                {node.text === undefined ? `${node.topic}/${node.section}` : <LinkText text={node.text} />}
              </a>
            );
          case 'term':
            if (termEntry(node.id, language === 'de' ? 'de' : 'en') === undefined) {
              warnDev(`unknown glossary term "${node.id}"`);
              return <Fragment key={i}>{node.text ?? node.id}</Fragment>;
            }
            return <TermPopover key={i} id={node.id} {...(node.text === undefined ? {} : { text: node.text })} />;
        }
      })}
    </>
  );
}
