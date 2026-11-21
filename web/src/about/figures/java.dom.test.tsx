// @vitest-environment jsdom
// Figure J1 (speed chart of topic 6) and the topic page "java" in jsdom: the switch, the data binding (rows, dots, whisker, numbers), the
// accessible names, both languages, and the place of the figure and of the 14 snippets on the page. Layout at 320 px is in about-figures.spec.ts.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import JavaPage from '../topics/java';
import { JavaSpeedFigure } from './JavaSpeedFigure';
import { javaCatalog } from './java-catalog';
import type { Catalog } from './common';
import { SPEED_ROWS, groupDivider, roundedValue, valueText, visibleRows } from './java-speed-model';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const catalog = javaCatalog as unknown as Catalog;
let host: HTMLElement;
let root: Root;
beforeEach(() => {
  i18n.setLanguage('en');
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  vi.stubGlobal('scrollTo', () => undefined);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
  i18n.setLanguage('en');
});

const render = async (node: React.JSX.Element): Promise<void> => {
  await act(async () => root.render(node));
};
const q = <T extends Element = HTMLElement>(selector: string): T => {
  const el = host.querySelector<T>(selector);
  if (!el) throw new Error(`nothing matches ${selector}`);
  return el;
};
const all = <T extends Element = HTMLElement>(selector: string): T[] => Array.from(host.querySelectorAll<T>(selector));
const text = (el: Element | null | undefined): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const choose = async (label: string): Promise<void> => {
  const radio = all<HTMLInputElement>('input[type="radio"]').find((r) => text(r.closest('label')) === label);
  if (!radio) throw new Error(`no option ${label}`);
  await act(async () => {
    radio.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
};
const rowIds = (): string[] => all('[data-row]').map((e) => e.getAttribute('data-row')!);

describe('java speed model', () => {
  it('rounds to two significant digits for the page and keeps small values whole', () => {
    expect(roundedValue(111409)).toBe(110000);
    expect(roundedValue(3019)).toBe(3000);
    expect(roundedValue(201)).toBe(200);
    expect(roundedValue(9213)).toBe(9200);
    expect(roundedValue(85222)).toBe(85000);
    expect(roundedValue(85582.5)).toBe(86000);
    expect(roundedValue(3100)).toBe(3100);
    expect(roundedValue(99)).toBe(99);
  });

  it('shows Java rows only, or all eight rows, in the order of the data', () => {
    expect(visibleRows('java').map((r) => r.id)).toEqual(['java-scalar-b1', 'java-vec-1t', 'tornado-metal-b256', 'java-vec-avx512', 'tornado-cuda-t4', 'teavm-scalar-b1']);
    expect(visibleRows('all')).toHaveLength(8);
  });

  it('puts the divider after the last Mac row, and none when one group is missing', () => {
    expect(groupDivider(visibleRows('all'))).toBe(2.5);
    expect(groupDivider(visibleRows('all').filter((r) => r.group === 'mac-run'))).toBeUndefined();
    expect(groupDivider(visibleRows('all').filter((r) => r.group === 'other'))).toBeUndefined();
  });

  it('writes a range as lo to hi with an en dash, and a value rounded', () => {
    const f = (v: number): string => String(v);
    expect(valueText(SPEED_ROWS.find((r) => r.id === 'teavm-scalar-b1')!, f)).toBe('126–163');
    expect(valueText(SPEED_ROWS.find((r) => r.id === 'tornado-metal-b256')!, f)).toBe('110000');
  });
});

describe('JavaSpeedFigure', () => {
  it('starts with the Java rows, with one dot per row, a whisker on the TeaVM row and a leader under every dot', async () => {
    await render(<JavaSpeedFigure catalog={catalog} number={[6, 1]} />);
    expect(rowIds()).toEqual(['java-scalar-b1', 'java-vec-1t', 'tornado-metal-b256', 'java-vec-avx512', 'tornado-cuda-t4', 'teavm-scalar-b1']);
    expect(all('svg.ml-fig-plot circle.ml-fig-plot__pt--1')).toHaveLength(6);
    expect(all('svg.ml-fig-plot rect.ml-fig-plot__pt--2')).toHaveLength(0);
    expect(all('.ml-fig-plot__whisker')).toHaveLength(1);
    expect(all('.ml-fig-plot__leader')).toHaveLength(6);
  });

  it('switches to Java and C++: eight rows, the two C++ rows as hollow squares, and back', async () => {
    await render(<JavaSpeedFigure catalog={catalog} number={[6, 1]} />);
    await choose('Java and C++');
    expect(rowIds()).toHaveLength(8);
    expect(all('svg.ml-fig-plot circle.ml-fig-plot__pt--1')).toHaveLength(6);
    expect(all('svg.ml-fig-plot rect.ml-fig-plot__pt--2')).toHaveLength(2);
    expect(all('.ml-fig-plot__leader')).toHaveLength(8);
    await choose('Java only');
    expect(rowIds()).toHaveLength(6);
    expect(all('svg.ml-fig-plot rect.ml-fig-plot__pt--2')).toHaveLength(0);
  });

  it('binds the numbers: rounded values in the list and on the dots, the range as 126–163, and the source line of each row', async () => {
    await render(<JavaSpeedFigure catalog={catalog} number={[6, 1]} initial="all" />);
    const value = (id: string): string => text(q(`[data-row="${id}"] .ml-speed__value`));
    expect(value('java-scalar-b1')).toBe('200');
    expect(value('java-vec-1t')).toBe('3,000');
    expect(value('tornado-metal-b256')).toBe('110,000');
    expect(value('java-vec-avx512')).toBe('9,200');
    expect(value('tornado-cuda-t4')).toBe('85,000');
    expect(value('cpp-cuda-t4')).toBe('86,000');
    expect(value('teavm-scalar-b1')).toBe('126–163');
    expect(value('cpp-wasm-simd')).toBe('3,100');
    expect(text(q('[data-row="java-vec-1t"] .ml-src'))).toBe('docs/tornado.md:438');
    expect(text(q('[data-row="teavm-scalar-b1"] .ml-src'))).toBe('docs/web-ui.md:834');
    // jsdom has no layout, so the plot takes the wide box and prints the value labels
    expect(all('.ml-fig-plot__value').map((e) => text(e))).toContain('110,000');
  });

  it('numbers the list like the x ticks of the plot, continuing across the two groups', async () => {
    await render(<JavaSpeedFigure catalog={catalog} number={[6, 1]} initial="all" />);
    const ticks = all('.ml-fig-plot__catlabel').map((e) => text(e));
    expect(ticks).toEqual(['1', '2', '3', '4', '5', '6', '7', '8']);
    const lists = all<HTMLOListElement>('ol.ml-speed__list');
    expect(lists.map((l) => l.start)).toEqual([1, 4]);
    expect(lists.map((l) => l.children.length)).toEqual([3, 5]);
  });

  it('names the figure, the plot, the switch and every value', async () => {
    await render(<JavaSpeedFigure catalog={catalog} number={[6, 1]} />);
    expect(q('figure').getAttribute('aria-labelledby')).toBe('fig-javaspeed-title');
    expect(text(q('#fig-javaspeed-title'))).toBe('Evaluations per second by technique');
    expect(text(q('.ml-fig__label'))).toBe('Fig. 6.1');
    const svg = q('svg.ml-fig-plot');
    expect(svg.getAttribute('role')).toBe('img');
    const labelled = svg.getAttribute('aria-labelledby')!.split(' ');
    expect(labelled.map((id) => text(host.querySelector(`[id="${id}"]`)))[0]).toBe('Evaluations per second by technique');
    expect(text(host.querySelector(`[id="${labelled[1]}"]`))).toMatch(/^A dot chart with a logarithmic axis/);
    const group = q('[role="radiogroup"]');
    expect(text(host.querySelector(`[id="${group.getAttribute('aria-labelledby')}"]`))).toBe('Show');
    expect(q('[data-row="tornado-metal-b256"] .ml-speed__value').getAttribute('aria-label')).toBe('110,000 evaluations per second');
    expect(q('[data-row="teavm-scalar-b1"] .ml-speed__value').getAttribute('aria-label')).toBe('126–163 evaluations per second');
  });

  it('says in the caption that only the Mac rows come from one run, and names the data file and the four documents', async () => {
    await render(<JavaSpeedFigure catalog={catalog} number={[6, 1]} />);
    expect(text(q('.ml-fig__caption'))).toMatch(/only the three Mac rows come from one run/);
    const chips = all('.ml-fig__foot .ml-fig-chip').map((c) => text(c));
    expect(chips.some((c) => c.includes('java-speed.json'))).toBe(true);
    for (const doc of ['docs/tornado.md', 'docs/cloud.md', 'docs/cpp-comparison.md', 'docs/web-ui.md']) expect(chips.some((c) => c.includes(doc)), doc).toBe(true);
  });

  it('draws the group divider only while both groups show, and uses a log y axis from 100', async () => {
    await render(<JavaSpeedFigure catalog={catalog} number={[6, 1]} />);
    expect(all('.ml-fig-plot__ref')).toHaveLength(1);
    expect(all('svg.ml-fig-plot text').map((e) => text(e))).toEqual(expect.arrayContaining(['100', '1k', '10k', '100k']));
  });

  it('switches every text to German, including the row labels and the switch', async () => {
    await render(<JavaSpeedFigure catalog={catalog} number={[6, 1]} />);
    await act(async () => i18n.setLanguage('de'));
    expect(text(q('#fig-javaspeed-title'))).toBe('Auswertungen pro Sekunde je Technik');
    expect(all('input[type="radio"]').map((r) => text(r.closest('label')))).toEqual(['Nur Java', 'Java und C++']);
    expect(text(q('[data-row="java-scalar-b1"] .ml-speed__label'))).toBe('Java skalar, Batch 1');
    expect(q('[data-row="java-vec-1t"] .ml-speed__value').getAttribute('aria-label')).toBe('3.000 Auswertungen pro Sekunde');
  });
});

describe('the topic page java', () => {
  const show = async (): Promise<void> => {
    await render(<JavaPage topic="java" section={undefined} onJumpSection={() => undefined} />);
  };

  it('has one h1, eight sections in order, the speed figure in overview and 14 snippets', async () => {
    await show();
    expect(all('h1')).toHaveLength(1);
    expect(text(q('h1'))).toBe('Java in practice');
    expect(all('h2.ml-about__h2').map((h) => h.id)).toEqual([
      'about-java-overview', 'about-java-plain-java', 'about-java-vector-api', 'about-java-auto-vectorization', 'about-java-tornadovm', 'about-java-outlook',
      'about-java-more', 'about-java-hilla', 'about-java-reading',
    ]);
    expect(host.querySelectorAll('#about-java-overview ~ figure, section').length).toBeGreaterThan(0);
    expect(q('section[aria-labelledby="about-java-overview"] figure#fig-javaspeed')).toBeTruthy();
    expect(all('figure.ml-code')).toHaveLength(14);
  });

  it('puts the snippets under their sections', async () => {
    await show();
    const inSection = (slug: string): string[] => all(`section[aria-labelledby="about-java-${slug}"] figure.ml-code`).map((f) => f.getAttribute('data-snippet')!);
    expect(inSection('plain-java')).toEqual(['a1', 'a2', 'a3']);
    expect(inSection('vector-api')).toEqual(['d1', 'd2']);
    expect(inSection('auto-vectorization')).toEqual([]);
    expect(inSection('tornadovm')).toEqual(['b1', 'b2']);
    expect(inSection('outlook')).toEqual(['illus']);
    expect(inSection('more')).toEqual(['e1', 'e2', 'e3']);
    expect(inSection('hilla')).toEqual(['h1', 'hgen', 'h3']);
  });

  it('gives each snippet its caption from the catalog, except the generated client', async () => {
    await show();
    const caption = (id: string): string => text(host.querySelector(`figure[data-snippet="${id}"] figcaption`));
    expect(caption('a1')).toMatch(/^A move is one int/);
    expect(caption('illus')).toMatch(/^Illustration, not in the repository/);
    expect(host.querySelector('figure[data-snippet="hgen"] figcaption')).toBeNull();
  });

  it('lists the 13 reading entries with their notes, and links to the lines of the snippets in German too', async () => {
    await show();
    expect(all('.ml-about__reading li')).toHaveLength(13);
    expect(text(q('.ml-about__reading li'))).toContain('TornadoVM documentation');
    await act(async () => i18n.setLanguage('de'));
    expect(text(q('h1'))).toBe('Java in der Praxis');
    expect(q<HTMLAnchorElement>('figure[data-snippet="a1"] a.ml-src').getAttribute('aria-label')).toBe('engine/src/main/java/sternhalma/engine/Move.java, Zeilen 9 bis 11');
    expect(text(q('figure[data-snippet="a1"] button.ml-code__copy'))).toBe('Code kopieren');
  });
});
