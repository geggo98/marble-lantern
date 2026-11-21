// @vitest-environment jsdom
// CodeSnippet (kit): what it renders, the copy flows (clipboard, no clipboard, old copy command), the accessible names, the line chip,
// and the tokenizer behind the colours. Layout (the sideways scroll inside the frame at 320 px) is in the browser spec about-figures.spec.ts.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CodeSnippet, snippetById } from './CodeSnippet';
import { SourceChip, sourceUrl } from './SourceChip';
import { dedent, tokenize } from './highlight';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const labels = { copy: 'Copy code', copied: 'Copied', copyFailed: 'Could not copy' };

let host: HTMLElement;
let root: Root;
beforeEach(() => {
  vi.useFakeTimers();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  Reflect.deleteProperty(document, 'execCommand');
});

const render = async (node: React.JSX.Element): Promise<void> => {
  await act(async () => root.render(node));
};
const q = <T extends Element = HTMLElement>(selector: string): T => {
  const el = host.querySelector<T>(selector);
  if (!el) throw new Error(`nothing matches ${selector}`);
  return el;
};
const press = async (): Promise<void> => {
  await act(async () => {
    q('button.ml-code__copy').dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
};
const stubClipboard = (writeText: (text: string) => Promise<void>): void => {
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
};
afterEach(() => {
  Reflect.deleteProperty(navigator, 'clipboard');
});

describe('CodeSnippet', () => {
  it('renders the code of the entry in a pre with the code inside, and the caption under it', async () => {
    await render(<CodeSnippet id="a1" caption="A move is one int." labels={labels} />);
    const pre = q('pre.ml-code__pre');
    expect(pre.querySelector('code')?.textContent).toBe(dedent(snippetById('a1').text));
    expect(pre.textContent).toContain('return from | (to << 8);');
    expect(q('figcaption').textContent).toBe('A move is one int.');
  });

  it('names the scrollable code region by the caption text, else by the path, and makes it a tab stop', async () => {
    await render(<CodeSnippet id="a1" captionText="Move as int" labels={labels} />);
    const pre = q('pre');
    expect(pre.getAttribute('aria-label')).toBe('Move as int');
    expect(pre.getAttribute('role')).toBe('region');
    expect(pre.getAttribute('tabindex')).toBe('0');
    await render(<CodeSnippet id="a1" labels={labels} />);
    expect(q('pre').getAttribute('aria-label')).toBe('engine/src/main/java/sternhalma/engine/Move.java');
  });

  it('shows the source chip with the line range, linking to the lines on GitHub, with a spoken name', async () => {
    await render(<CodeSnippet id="a2" labels={labels} linesLabel={(from, to) => `lines ${from} to ${to}`} />);
    const chip = q<HTMLAnchorElement>('a.ml-src');
    expect(chip.textContent).toBe('engine/src/main/java/sternhalma/engine/Position.java:388-397');
    expect(chip.getAttribute('href')).toBe(`${sourceUrl('engine/src/main/java/sternhalma/engine/Position.java')}#L388-L397`);
    expect(chip.getAttribute('aria-label')).toBe('engine/src/main/java/sternhalma/engine/Position.java, lines 388 to 397');
  });

  it('shows the chip of the generated client without a link, and shows no chip for the illustration', async () => {
    await render(<CodeSnippet id="hgen" labels={labels} />);
    expect(host.querySelector('a.ml-src')).toBeNull();
    expect(q('span.ml-src').textContent).toContain('generated/GameEndpoint.ts:5');
    await render(<CodeSnippet id="illus" labels={labels} />);
    expect(host.querySelector('.ml-src')).toBeNull();
    expect(q('figure').hasAttribute('data-illustration')).toBe(true);
  });

  it('puts a dimmed ellipsis after the code when elideAfter is set, outside of the code element', async () => {
    await render(<CodeSnippet id="a2" labels={labels} elideAfter />);
    expect(q('.ml-code__elide').textContent).toContain('…');
    expect(q('.ml-code__elide').getAttribute('aria-hidden')).toBe('true');
    expect(q('pre code').textContent).not.toContain('…');
    await render(<CodeSnippet id="a2" labels={labels} />);
    expect(host.querySelector('.ml-code__elide')).toBeNull();
  });

  it('colours keywords, comments and strings with their own classes', async () => {
    await render(<CodeSnippet id="e1" labels={labels} />);
    expect([...host.querySelectorAll('.ml-code__kw')].map((e) => e.textContent)).toContain('static');
    expect([...host.querySelectorAll('.ml-code__string')].map((e) => e.textContent)).toContain('"game-"');
    await render(<CodeSnippet id="illus" labels={labels} />);
    expect(q('.ml-code__comment').textContent).toMatch(/^\/\/ ILLUSTRATION/);
  });

  it('has a copy button of at least 44 px in the style sheet contract and an own text, not only an icon', async () => {
    await render(<CodeSnippet id="a1" labels={labels} />);
    const button = q('button.ml-code__copy');
    expect(button.textContent).toBe('Copy code');
    expect(button.getAttribute('type')).toBe('button');
  });
});

describe('CodeSnippet: copy', () => {
  it('writes the displayed text to the clipboard, says Copied, and returns to the copy label after two seconds', async () => {
    const writeText = vi.fn(async () => undefined);
    stubClipboard(writeText);
    await render(<CodeSnippet id="a1" labels={labels} />);
    await press();
    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith(dedent(snippetById('a1').text));
    expect(q('button.ml-code__copy').textContent).toBe('Copied');
    expect(q('[role="status"]').textContent).toBe('Copied');
    await act(async () => {
      vi.advanceTimersByTime(2100);
    });
    expect(q('button.ml-code__copy').textContent).toBe('Copy code');
    expect(q('[role="status"]').textContent).toBe('');
  });

  it('copies the code without the ellipsis line', async () => {
    const writeText = vi.fn(async () => undefined);
    stubClipboard(writeText);
    await render(<CodeSnippet id="d1" labels={labels} elideAfter />);
    await press();
    expect((writeText.mock.calls[0] as unknown as [string])[0]).not.toContain('…');
  });

  it('without the clipboard API selects the code and tries the old copy command: Copied when it works', async () => {
    const exec = vi.fn(() => true);
    Object.defineProperty(document, 'execCommand', { value: exec, configurable: true });
    await render(<CodeSnippet id="a1" labels={labels} />);
    await press();
    expect(exec).toHaveBeenCalledWith('copy');
    expect(window.getSelection()?.toString()).toBe(q('pre code').textContent);
    expect(q('button.ml-code__copy').textContent).toBe('Copied');
  });

  it('says that copying failed when the clipboard rejects and the old command fails, keeps the code selected, and keeps the message', async () => {
    stubClipboard(async () => {
      throw new Error('denied');
    });
    Object.defineProperty(document, 'execCommand', { value: () => false, configurable: true });
    await render(<CodeSnippet id="a1" labels={labels} />);
    await press();
    expect(q('[role="status"]').textContent).toBe('Could not copy');
    expect(window.getSelection()?.toString()).toBe(q('pre code').textContent);
    expect(q('button.ml-code__copy').textContent).toBe('Copy code');
    await act(async () => {
      vi.advanceTimersByTime(5000);
    });
    expect(q('[role="status"]').textContent).toBe('Could not copy');
  });

  it('fails the same way when neither the clipboard API nor the old command exists', async () => {
    await render(<CodeSnippet id="a1" labels={labels} />);
    await press();
    expect(q('[role="status"]').textContent).toBe('Could not copy');
  });
});

describe('SourceChip with lines', () => {
  it('appends #L<start>-L<end> to the URL and :start-end to the text; one line gives :n', async () => {
    await render(<SourceChip path="a/b.java" lines={[3, 9]} />);
    expect(q<HTMLAnchorElement>('a').getAttribute('href')).toMatch(/\/blob\/main\/a\/b\.java#L3-L9$/);
    expect(q('a').textContent).toBe('a/b.java:3-9');
    await render(<SourceChip path="a/b.java" lines={[7, 7]} />);
    expect(q('a').textContent).toBe('a/b.java:7');
    expect(q<HTMLAnchorElement>('a').getAttribute('href')).toMatch(/#L7-L7$/);
  });

  it('keeps the old chip without lines: no fragment, no suffix', async () => {
    await render(<SourceChip path="a/b.java" />);
    expect(q<HTMLAnchorElement>('a').getAttribute('href')).toMatch(/\/blob\/main\/a\/b\.java$/);
    expect(q('a').textContent).toBe('a/b.java');
  });
});

describe('tokenize and dedent', () => {
  it('splits Java into keywords, comments, strings and plain text, and joins to the same text', () => {
    const text = 'public static int of(int a) { // note\n  return "x\\"y" + 1; /* c */ }';
    const tokens = tokenize(text, 'java');
    expect(tokens.map((t) => t.text).join('')).toBe(text);
    expect(tokens.filter((t) => t.kind === 'kw').map((t) => t.text)).toEqual(expect.arrayContaining(['public', 'static', 'int', 'return']));
    expect(tokens.filter((t) => t.kind === 'comment').map((t) => t.text)).toEqual(['// note', '/* c */']);
    expect(tokens.filter((t) => t.kind === 'string').map((t) => t.text)).toEqual(['"x\\"y"']);
  });

  it('does not colour a keyword inside a longer word, a string or a comment', () => {
    const tokens = tokenize('intValue = "int"; // int', 'java');
    expect(tokens.filter((t) => t.kind === 'kw')).toEqual([]);
  });

  it('knows the keywords of TypeScript and treats an annotation as a keyword', () => {
    expect(tokenize('const x = await import("a");', 'ts').filter((t) => t.kind === 'kw').map((t) => t.text)).toEqual(['const', 'await', 'import']);
    expect(tokenize('@BrowserCallable class A {}', 'java').filter((t) => t.kind === 'kw').map((t) => t.text)).toEqual(['@BrowserCallable', 'class']);
  });

  it('copes with an unterminated block comment and an unterminated string', () => {
    expect(tokenize('a /* open', 'java').map((t) => t.kind)).toEqual(['plain', 'comment']);
    expect(tokenize('a "open', 'java').map((t) => t.kind)).toEqual(['plain', 'string']);
  });

  it('removes the common indentation only', () => {
    expect(dedent('    a\n      b\n\n    c')).toBe('a\n  b\n\nc');
    expect(dedent('x')).toBe('x');
  });
});
