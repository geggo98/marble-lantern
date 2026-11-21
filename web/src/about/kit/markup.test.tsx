// @vitest-environment jsdom
// The inline markup of catalog strings: every token, nesting refused, an unknown term shown as text plus a development warning.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import { Markup, parseMarkup } from './markup';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const quiet = (): void => undefined;

describe('parseMarkup', () => {
  it('returns plain text unchanged', () => {
    expect(parseMarkup('just words, 50 % of them.', quiet)).toEqual([{ type: 'text', text: 'just words, 50 % of them.' }]);
  });

  it('reads a term with and without visible text', () => {
    expect(parseMarkup('a {term:alpha-beta} b', quiet)).toEqual([
      { type: 'text', text: 'a ' },
      { type: 'term', id: 'alpha-beta' },
      { type: 'text', text: ' b' },
    ]);
    expect(parseMarkup('{term:alpha-beta|alpha-beta pruning}', quiet)).toEqual([{ type: 'term', id: 'alpha-beta', text: 'alpha-beta pruning' }]);
  });

  it('reads a source chip, a section link, a number and code', () => {
    expect(parseMarkup('{src:search/src/main/java/sternhalma/search/AlphaBeta.java}', quiet)).toEqual([
      { type: 'src', path: 'search/src/main/java/sternhalma/search/AlphaBeta.java' },
    ]);
    expect(parseMarkup('{sec:classical/alpha-beta|the pruning}', quiet)).toEqual([{ type: 'sec', topic: 'classical', section: 'alpha-beta', text: 'the pruning' }]);
    expect(parseMarkup('{n:20000} and {n:0.5} and {n:-3}', quiet)).toEqual([
      { type: 'n', value: 20000 },
      { type: 'text', text: ' and ' },
      { type: 'n', value: 0.5 },
      { type: 'text', text: ' and ' },
      { type: 'n', value: -3 },
    ]);
    expect(parseMarkup('call `negamax` now', quiet)).toEqual([
      { type: 'text', text: 'call ' },
      { type: 'code', text: 'negamax' },
      { type: 'text', text: ' now' },
    ]);
  });

  it('refuses nesting: a token inside a token is not a token', () => {
    const nodes = parseMarkup('{term:a|{n:1}}', quiet);
    expect(nodes.some((n) => n.type === 'term')).toBe(false);
    expect(nodes).toEqual([{ type: 'text', text: '{term:a|' }, { type: 'n', value: 1 }, { type: 'text', text: '}' }]);
  });

  it('keeps tokens inside code as text', () => {
    expect(parseMarkup('`{n:5}`', quiet)).toEqual([{ type: 'code', text: '{n:5}' }]);
  });

  it('keeps a malformed or unknown token visible as text and warns about it', () => {
    const warn = vi.fn();
    expect(parseMarkup('x {n:abc} y {foo:bar} z {sec:nope/x|t} {term:Bad_Id} {src:a b}', warn)).toEqual([
      { type: 'text', text: 'x {n:abc} y {foo:bar} z {sec:nope/x|t} {term:Bad_Id} {src:a b}' },
    ]);
    expect(warn).toHaveBeenCalledTimes(5);
    expect(warn.mock.calls[1]![0]).toContain('unknown token');
    expect(warn.mock.calls[0]![0]).toContain('malformed token');
  });

  it('leaves a placeholder without a colon alone (the lookup fills those before the markup runs)', () => {
    const warn = vi.fn();
    expect(parseMarkup('{visited} of {total}', warn)).toEqual([{ type: 'text', text: '{visited} of {total}' }]);
    expect(warn).not.toHaveBeenCalled();
  });

  it('keeps an unbalanced backtick as text', () => {
    expect(parseMarkup('a ` b', quiet)).toEqual([{ type: 'text', text: 'a ` b' }]);
  });
});

describe('Markup', () => {
  let host: HTMLElement;
  let root: Root;
  beforeEach(() => {
    i18n.setLanguage('en');
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });
  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.restoreAllMocks();
    i18n.setLanguage('en');
  });
  const render = async (text: string): Promise<void> => {
    await act(async () => root.render(<Markup text={text} />));
  };

  it('renders every token as the matching element', async () => {
    await render('a {term:tensor} b {src:search/src/main/java/sternhalma/search/AlphaBeta.java} c {sec:classical/alpha-beta|link} d {n:20000} e `x`');
    expect(host.querySelector('button.ml-term__btn')?.textContent).toBe('Tensor');
    const chip = host.querySelector<HTMLAnchorElement>('a.ml-src');
    expect(chip?.getAttribute('href')).toBe('https://github.com/geggo98/marble-lantern/blob/main/search/src/main/java/sternhalma/search/AlphaBeta.java');
    expect(chip?.textContent).toBe('search/src/main/java/sternhalma/search/AlphaBeta.java');
    expect(host.querySelector<HTMLAnchorElement>('a.ml-about-link')?.getAttribute('href')).toBe('#/about/classical/alpha-beta');
    expect(host.querySelector('.ml-about-num')?.textContent).toBe('20,000');
    expect([...host.querySelectorAll('code')].at(-1)?.textContent).toBe('x');
  });

  it('formats a number in the active language', async () => {
    i18n.setLanguage('de');
    await render('{n:20000}');
    expect(host.querySelector('.ml-about-num')?.textContent).toBe('20.000');
  });

  it('links a directory path to the tree view', async () => {
    await render('{src:web/src/about/}');
    expect(host.querySelector('a.ml-src')?.getAttribute('href')).toBe('https://github.com/geggo98/marble-lantern/tree/main/web/src/about');
  });

  it('shows an unknown term as its visible text, with no button, and warns in a development build', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await render('see {term:no-such-term|the thing} here');
    expect(host.textContent).toBe('see the thing here');
    expect(host.querySelector('button')).toBeNull();
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('unknown glossary term "no-such-term"'));
  });

  it('shows an unknown term without visible text as its id', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await render('{term:no-such-term}');
    expect(host.textContent).toBe('no-such-term');
  });

  it('never creates elements from text: markup characters in a catalog string stay text', async () => {
    await render('<img src=x onerror=alert(1)> {term:tensor|<b>t</b>}');
    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('b')).toBeNull();
    expect(host.querySelector('button')?.textContent).toBe('<b>t</b>');
  });
});
