// @vitest-environment jsdom
// The glossary page: alphabetical per language, filter by kind and by text, deep link to a term (scroll, focus, highlight), and the
// popover of a term in a topic text that links here. Plus the filter function on its own.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import { GLOSSARY_IDS, allTerms, termEntry } from '../i18n/glossary';
import { TermPopover } from '../kit/TermPopover';
import GlossaryPage from './glossary';
import { filterTerms, termKind } from './glossary-filter';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const scrollIntoView = vi.fn();
beforeEach(() => {
  i18n.setLanguage('en');
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  scrollIntoView.mockClear();
  Element.prototype.scrollIntoView = scrollIntoView;
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo;
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  i18n.setLanguage('en');
});

const show = async (section?: string): Promise<void> => {
  await act(async () => root.render(<GlossaryPage topic="glossary" section={section} onJumpSection={() => undefined} />));
};
const names = (): string[] => [...host.querySelectorAll('.ml-about__term dt')].map((e) => e.firstChild?.textContent ?? '');
const click = async (el: Element): Promise<void> => {
  await act(async () => (el as HTMLElement).click());
};
const type = async (input: HTMLInputElement, value: string): Promise<void> => {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
};

describe('the glossary page', () => {
  it('lists every term of the catalog (142), alphabetical in English', async () => {
    await show();
    expect(names()).toHaveLength(GLOSSARY_IDS.length);
    expect(names()).toHaveLength(142);
    expect(names()).toEqual([...names()].sort((a, b) => a.localeCompare(b, 'en', { sensitivity: 'base' })));
  });

  it('re-sorts in German by the German name, with the English term in brackets and lang="en"', async () => {
    await show();
    await act(async () => i18n.setLanguage('de'));
    const de = names();
    expect(de).toEqual([...de].sort((a, b) => a.localeCompare(b, 'de', { sensitivity: 'base' })));
    expect(de).not.toEqual(allTerms('en').map((e) => e.name));
    const dt = host.querySelector('#about-glossary-tablebase');
    expect(dt?.textContent).toContain('(tablebase (endgame database))');
    expect(dt?.querySelector('[lang="en"]')).not.toBeNull();
  });

  it('filters by kind: 78 data-science terms with a long definition, 64 search and engineering terms without', async () => {
    await show();
    const kind = (k: string): HTMLElement => host.querySelector(`[data-kind="${k}"]`) as HTMLElement;
    await click(kind('ds'));
    expect(names()).toHaveLength(78);
    expect([...host.querySelectorAll('.ml-about__term')].every((t) => t.getAttribute('data-kind') === 'ds')).toBe(true);
    expect(host.querySelectorAll('.ml-glossary__long')).toHaveLength(78);
    await click(kind('eng'));
    expect(names()).toHaveLength(64);
    expect(host.querySelectorAll('.ml-glossary__long')).toHaveLength(0);
    await click(kind('all'));
    expect(names()).toHaveLength(142);
    expect(kind('all').getAttribute('aria-pressed')).toBe('true');
  });

  it('filters by text and reports an empty result in the status line', async () => {
    await show();
    const input = host.querySelector('input[type="search"]') as HTMLInputElement;
    await type(input, 'zobrist');
    expect(names()).toEqual(['Zobrist hashing']);
    expect(host.querySelector('[role="status"]')?.textContent).toBe('');
    await type(input, 'qqqqzzzz');
    expect(names()).toHaveLength(0);
    expect(host.querySelector('[role="status"]')?.textContent).toBe('No term matches this filter.');
  });

  it('scrolls to the term of a deep link, focuses it and highlights exactly that term', async () => {
    await show('batch');
    const dt = host.querySelector('#about-glossary-batch') as HTMLElement;
    expect(document.activeElement).toBe(dt);
    expect(scrollIntoView).toHaveBeenCalled();
    expect(host.querySelectorAll('.ml-about__term--target')).toHaveLength(1);
    expect(dt.closest('.ml-about__term')?.classList.contains('ml-about__term--target')).toBe(true);
    expect(dt.getAttribute('aria-current')).toBe('location');
  });

  it('renders the inline markup of the definitions: no raw {n:...} or backticks are left on the page, numbers are formatted', async () => {
    await show();
    const text = host.textContent ?? '';
    expect(text).not.toMatch(/\{(n|term|src|sec):/);
    expect(text).not.toContain('`');
    expect(host.querySelector('#about-glossary-batch')?.closest('.ml-about__term')?.textContent).toContain('1,968');
  });

  it('shows the English term in brackets only where it differs from the name, ignoring case', async () => {
    await act(async () => i18n.setLanguage('de'));
    await show();
    expect(host.querySelector('#about-glossary-batch')?.textContent).toBe('Batch');
  });

  it('goes to the top and the heading for an unknown term id, and highlights nothing', async () => {
    await show('no-such-term');
    expect(document.activeElement).toBe(host.querySelector('h1'));
    expect(host.querySelectorAll('.ml-about__term--target')).toHaveLength(0);
  });
});

describe('the popover of a term links to the glossary entry', () => {
  it('opens on a click and links to #/about/glossary/<term-id> in both languages', async () => {
    await act(async () => root.render(<TermPopover id="elo" />));
    await click(host.querySelector('.ml-term__btn') as Element);
    expect(host.querySelector('.ml-term__pop a')?.getAttribute('href')).toBe('#/about/glossary/elo');
    expect(host.querySelector('.ml-term__pop')?.textContent).toContain(termEntry('elo', 'en')?.short ?? 'missing');
  });
});

describe('filterTerms', () => {
  const terms = allTerms('en');
  it('returns all terms for kind "all" and an empty query, in the given order', () => {
    expect(filterTerms(terms, 'all', '').map((e) => e.id)).toEqual(terms.map((e) => e.id));
  });
  it('keeps only the kind asked for, and kind follows the long definition', () => {
    for (const e of filterTerms(terms, 'eng', '')) expect(e.long).toBeUndefined();
    for (const e of filterTerms(terms, 'ds', '')) expect(termKind(e)).toBe('ds');
  });
  it('requires every word of the query, ignoring case and accents', () => {
    const one = filterTerms(terms, 'all', 'ZOBRIST hashing');
    expect(one.map((e) => e.id)).toEqual(['zobrist-hashing']);
    expect(filterTerms(terms, 'all', 'zobrist qqqq')).toEqual([]);
  });
  it('finds a German term by its English name', () => {
    const de = allTerms('de');
    expect(filterTerms(de, 'all', 'endgame database').map((e) => e.id)).toContain('tablebase');
  });
});
