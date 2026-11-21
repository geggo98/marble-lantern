// CodeSnippet: a code block of the Info part. The text is data (data/java-snippets.json, cut verbatim from the repository by
// scripts/web/java-snippets.mjs), never translated. The block scrolls sideways inside its frame, so that the page never does; the source
// chip names the file and the lines; the button copies the code. Without the clipboard API the code is selected and the button says so,
// as CopyLink in ui/Chrome.tsx shows its address for copying by hand.
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import data from '../data/java-snippets.json';
import { Icon } from './icons';
import { SourceChip } from './SourceChip';
import { dedent, tokenize } from './highlight';
import type { CodeLang } from './highlight';
import './code.css';

export interface SnippetEntry {
  id: string;
  lang: CodeLang;
  path?: string;
  startLine?: number;
  endLine?: number;
  text: string;
  /** Written by a build step and not in Git: the chip shows the path and links nowhere. */
  generated?: boolean;
  /** Hypothetical code, not in the repository. */
  illustration?: boolean;
}

export const SNIPPETS: readonly SnippetEntry[] = data.snippets as SnippetEntry[];

export function snippetById(id: string): SnippetEntry {
  const entry = SNIPPETS.find((s) => s.id === id);
  if (!entry) throw new Error(`no snippet "${id}" in java-snippets.json`);
  return entry;
}

export interface CodeSnippetLabels {
  copy: string;
  copied: string;
  copyFailed: string;
}

export interface CodeSnippetProps {
  /** Key into data/java-snippets.json. */
  id: string;
  /** Translated text from the topic catalog; it also names the code region. */
  caption?: ReactNode;
  /** Plain text of the caption for the accessible name (a caption with markup passes its plain form). */
  captionText?: string;
  /** The snippet is cut from a longer block: a dimmed ellipsis line follows the code (not part of what the button copies). */
  elideAfter?: boolean;
  labels: CodeSnippetLabels;
  /** `lines {from} to {to}` filled in (kit.code.lines): the accessible name of the line range of the chip. */
  linesLabel?: (from: number, to: number) => string;
}

type CopyState = 'idle' | 'copied' | 'failed';

/** Select the contents of an element, as the reader would with the mouse. */
function selectContents(el: HTMLElement): void {
  const selection = window.getSelection?.();
  if (!selection) return;
  const range = document.createRange();
  range.selectNodeContents(el);
  selection.removeAllRanges();
  selection.addRange(range);
}

export function CodeSnippet({ id, caption, captionText, elideAfter = false, labels, linesLabel }: CodeSnippetProps): React.JSX.Element {
  const entry = snippetById(id);
  const shown = dedent(entry.text);
  const [state, setState] = useState<CopyState>('idle');
  const code = useRef<HTMLElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  const settle = (next: CopyState): void => {
    setState(next);
    clearTimeout(timer.current);
    // "failed" stays until the next press: the reader may still be copying by hand
    if (next === 'copied') timer.current = setTimeout(() => setState('idle'), 2000);
  };

  const copy = async (): Promise<void> => {
    try {
      await navigator.clipboard.writeText(shown);
      settle('copied');
      return;
    } catch {
      // no clipboard API (an insecure page, a blocked permission): select the code and try the old command
    }
    if (code.current) selectContents(code.current);
    let ok = false;
    try {
      ok = typeof document.execCommand === 'function' && document.execCommand('copy');
    } catch {
      ok = false;
    }
    settle(ok ? 'copied' : 'failed');
  };

  const hasLines = entry.path !== undefined && entry.startLine !== undefined && entry.endLine !== undefined;
  const lines: readonly [number, number] | undefined = hasLines ? [entry.startLine!, entry.endLine!] : undefined;
  const name = captionText ?? entry.path ?? entry.id;
  const status = state === 'copied' ? labels.copied : state === 'failed' ? labels.copyFailed : '';

  return (
    <figure className="ml-code" data-snippet={entry.id} data-lang={entry.lang} {...(entry.illustration ? { 'data-illustration': '' } : {})}>
      <div className="ml-code__bar">
        {entry.path !== undefined && (
          <span className="ml-code__src">
            <SourceChip path={entry.path} {...(lines ? { lines } : {})} {...(lines && linesLabel ? { linesLabel: linesLabel(lines[0], lines[1]) } : {})} noLink={entry.generated === true} />
          </span>
        )}
        <button type="button" className="ml-btn ml-code__copy" onClick={() => void copy()} data-state={state}>
          <Icon name={state === 'copied' ? 'check' : 'copy'} />
          <span>{state === 'copied' ? labels.copied : labels.copy}</span>
        </button>
      </div>
      <pre className="ml-code__pre" tabIndex={0} role="region" aria-label={name}>
        <code ref={code} className={`language-${entry.lang}`}>
          {tokenize(shown, entry.lang).map((token, i) =>
            token.kind === 'plain' ? token.text : (
              <span key={i} className={`ml-code__${token.kind}`}>
                {token.text}
              </span>
            ),
          )}
        </code>
        {elideAfter && (
          <span className="ml-code__elide" aria-hidden="true">
            {'\n…'}
          </span>
        )}
      </pre>
      <p className={state === 'failed' ? 'ml-hint ml-code__status' : 'ml-visually-hidden'} role="status" aria-live="polite">
        {status}
      </p>
      {caption && <figcaption className="ml-code__caption">{caption}</figcaption>}
    </figure>
  );
}
