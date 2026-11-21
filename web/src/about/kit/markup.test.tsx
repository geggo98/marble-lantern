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

  it('reads a scientific number, with or without a fractional mantissa', () => {
    expect(parseMarkup('lr {sci:3e-4} and {sci:1.5e-4}', quiet)).toEqual([
      { type: 'text', text: 'lr ' },
      { type: 'sci', mantissa: 3, exponent: -4 },
      { type: 'text', text: ' and ' },
      { type: 'sci', mantissa: 1.5, exponent: -4 },
    ]);
  });

  it('keeps a malformed scientific number visible as text and warns', () => {
    const warn = vi.fn();
    const source = '{sci:3e} {sci:abc} {sci:3e-4|x} {sci:}';
    expect(parseMarkup(source, warn)).toEqual([{ type: 'text', text: source }]);
    expect(warn).toHaveBeenCalledTimes(4);
    expect(warn.mock.calls[0]![0]).toContain('malformed token');
    expect(parseMarkup('{foo:3e-4}', quiet)).toEqual([{ type: 'text', text: '{foo:3e-4}' }]);
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

  it('typesets a scientific number as MathML with a spoken alternative', async () => {
    await render('rate {sci:3e-4} here');
    const math = host.querySelector('math.ml-sci');
    expect(math?.getAttribute('aria-label')).toBe('3 times 10 to the power of minus 4');
    expect([...math!.querySelectorAll('mn, mo')].map((e) => e.tagName.toLowerCase() + e.textContent)).toEqual(['mn3', 'mo·', 'mn10', 'mo−', 'mn4']);
    expect(math?.querySelector('msup')?.children).toHaveLength(2);
    expect(host.textContent).not.toContain('{sci:');
  });

  it('leaves out a mantissa of 1 and sets a positive exponent without a sign', async () => {
    await render('{sci:1e-5} {sci:2e3}');
    expect([...host.querySelectorAll('math')].map((m) => m.textContent)).toEqual(['10−5', '2·103']);
  });

  it('uses the decimal comma of the German page in the mantissa and in the spoken text', async () => {
    i18n.setLanguage('de');
    await render('{sci:1.5e-4}');
    const math = host.querySelector('math');
    expect(math?.querySelector('mn')?.textContent).toBe('1,5');
    expect(math?.getAttribute('aria-label')).toBe('1,5 mal 10 hoch minus 4');
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
