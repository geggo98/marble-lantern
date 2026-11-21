// @vitest-environment jsdom
// The six figures of topics 3 and 4 in jsdom: state machines, data binding in the DOM, accessible names, both languages, reduced motion and
// the place of each figure on its topic page. Browser layout is not available here; the screenshot spec and the browser run cover it.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import InferencePage from '../topics/inference';
import TrainingPage from '../topics/training';
import { inferenceCatalog, trainingCatalog } from './catalogs';
import { LoopFigure } from './LoopFigure';
import { LrFigure } from './LrFigure';
import { McstFigure } from './McstFigure';
import { NetFigure } from './NetFigure';
import { PooledFigure } from './PooledFigure';
import { BackendsFigure, ForwardFigure } from './ForwardFigure';
import mcts from '../data/mcts-mid.json';
import { rateAt } from './lr';
import { rateText } from './lr';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

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
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
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
const click = (el: Element): void => {
  act(() => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
};
const key = (el: Element, k: string): void => {
  act(() => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
  });
};
const setValue = (el: HTMLInputElement | HTMLSelectElement, value: string | number): void => {
  const proto = el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
  act(() => {
    Object.getOwnPropertyDescriptor(proto, 'value')?.set?.call(el, String(value));
    el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
};
const text = (el: Element | null | undefined): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
/** The control of a row of the panel by the text of its label. */
const control = <T extends HTMLElement = HTMLInputElement>(label: string): T => {
  const el = all<HTMLElement>('input, select').find((c) => Array.from((c as HTMLInputElement).labels ?? []).some((l) => text(l).startsWith(label)));
  if (!el) throw new Error(`no control labelled ${label}`);
  return el as T;
};
const radio = (name: string): HTMLInputElement => {
  const el = all<HTMLInputElement>('input[type="radio"]').find((r) => text(r.closest('label')) === name);
  if (!el) throw new Error(`no radio ${name}`);
  return el;
};
const button = (label: string): HTMLButtonElement => {
  const el = all<HTMLButtonElement>('button').find((b) => b.getAttribute('aria-label') === label || text(b).startsWith(label));
  if (!el) throw new Error(`no button ${label}`);
  return el;
};
const status = (): string => text(q('[role="status"]'));
const namedBy = (el: Element): string =>
  (el.getAttribute('aria-labelledby') ?? '')
    .split(' ')
    .filter(Boolean)
    .map((id) => text(document.getElementById(id)))
    .join(' ');

const FIGURES: [string, () => React.JSX.Element, 'inference' | 'training'][] = [
  ['net', () => <NetFigure />, 'inference'],
  ['forward', () => <ForwardFigure />, 'inference'],
  ['backends', () => <BackendsFigure />, 'inference'],
  ['mcts', () => <McstFigure />, 'inference'],
  ['lr', () => <LrFigure />, 'training'],
  ['loop', () => <LoopFigure />, 'training'],
  ['pooled', () => <PooledFigure />, 'training'],
];

describe.each(['en', 'de'] as const)('every figure, language %s', (language) => {
  it.each(FIGURES)('%s: no catalog key shows, every control and picture has a name', async (id, make) => {
    i18n.setLanguage(language);
    await render(make());
    const figure = q(`figure#fig-${id}`);
    expect(namedBy(figure).length).toBeGreaterThan(3);
    const visible = [text(figure), ...all('[aria-label], title, desc').map((e) => e.getAttribute('aria-label') ?? text(e))].join(' ');
    expect(visible).not.toMatch(/\b(?:fig|kit)\.[a-z]+[.\w-]*/);
    expect(visible).not.toMatch(/\{[a-z]+\}/); // an unfilled placeholder
    expect(visible).not.toMatch(/\{(?:n|term|src|sec):/); // a markup token that was not rendered
    for (const svg of all<SVGElement>('svg[role]')) {
      const role = svg.getAttribute('role');
      if (role === 'img') {
        expect(text(svg.querySelector('title')).length, 'title of an svg').toBeGreaterThan(2);
        expect(text(svg.querySelector('desc')).length, 'desc of an svg').toBeGreaterThan(10);
      } else expect(namedBy(svg).length || (svg.getAttribute('aria-label') ?? '').length).toBeGreaterThan(0);
    }
    for (const c of all<HTMLElement>('button, input, select')) {
      const name = c.getAttribute('aria-label') ?? (namedBy(c) || Array.from((c as HTMLInputElement).labels ?? []).map(text).join(' ') || text(c));
      expect(name.length, `${c.outerHTML.slice(0, 120)} has no accessible name`).toBeGreaterThan(0);
    }
  });
  it('shows its title in the language of the page', async () => {
    i18n.setLanguage(language);
    await render(<McstFigure />);
    expect(text(q('.ml-fig__title'))).toBe(language === 'en' ? 'MCTS, one simulation at a time' : inferenceCatalog.lookup('de', 'fig.mcts.title'));
    if (language === 'de') expect(text(q('.ml-fig__title'))).not.toBe(inferenceCatalog.lookup('en', 'fig.mcts.title'));
  });
});

describe('V9a, the layers of the network', () => {
  it('starts with the expert model: total 61,734; the shared model has 62,215 and 15 input planes', async () => {
    await render(<NetFigure />);
    expect(text(q('.ml-fig-stack__total'))).toContain('61,734');
    expect(text(q('[data-layer="input"]'))).toContain('[122][13]');
    click(radio('Shared (2 and 3 players)'));
    expect(text(q('.ml-fig-stack__total'))).toContain('62,215');
    expect(text(q('[data-layer="input"]'))).toContain('[122][15]');
    expect(text(q('[data-layer="head_value"]'))).toContain('[3]');
  });
  it('a layer opens its detail (aria-expanded, text with the plane count), Escape closes it and returns the focus', async () => {
    await render(<NetFigure />);
    const layer = q<HTMLButtonElement>('[data-layer="conv_in"]');
    expect(layer.getAttribute('aria-expanded')).toBe('false');
    click(layer);
    expect(layer.getAttribute('aria-expanded')).toBe('true');
    const detail = q('section.ml-fig__detail');
    expect(text(detail)).toContain('13 planes');
    layer.focus();
    key(detail.querySelector('button') as HTMLElement, 'Escape');
    expect(host.querySelector('section.ml-fig__detail')).toBeNull();
    await act(async () => {
      await Promise.resolve();
    });
    expect(document.activeElement).toBe(layer);
  });
  it('the cell picker moves with the arrow keys and the readout follows: a cell at the edge has fewer neighbours', async () => {
    await render(<NetFigure />);
    const readout = (): string => text(q('.ml-fig-readout'));
    expect(readout()).toContain('I5');
    expect(readout()).toContain('6');
    const picked = q<SVGElement>('[data-cell][tabindex="0"]');
    expect(picked.getAttribute('data-cell')).toBe('60');
    // Home goes to the first cell of the row, which lies at the edge of the board
    key(picked, 'Home');
    const now = q<SVGElement>('[data-cell][tabindex="0"]');
    expect(now.getAttribute('data-cell')).not.toBe('60');
    expect(status()).toMatch(/neighbours on the board/);
    expect(Number(text(all('.ml-fig-readout__value')[1]))).toBeLessThan(6);
    expect(text(host)).toContain('Padding row');
  });
  it('a native select reaches every one of the 121 cells (the board cells are far below 44 px on a phone) and moves the same state as the board', async () => {
    await render(<NetFigure />);
    const select = control<HTMLSelectElement>('Pick a cell');
    expect(select.tagName).toBe('SELECT');
    expect(select.options).toHaveLength(121); // the Sternhalma board has 121 holes
    expect(select.value).toBe('60');
    setValue(select, 0);
    expect(q<SVGElement>('[data-cell][tabindex="0"]').getAttribute('data-cell')).toBe('0');
    expect(select.value).toBe('0');
    const readout = text(q('.ml-fig-readout'));
    expect(readout).not.toContain('I5');
    expect(Number(text(all('.ml-fig-readout__value')[1]))).toBeLessThan(6);
  });
  it('the focused cell is a disc of level 5 and its neighbours discs of level 2', async () => {
    await render(<NetFigure />);
    expect(all('svg.ml-fig-board [data-level="5"]')).toHaveLength(1);
    expect(all('svg.ml-fig-board [data-level="2"]')).toHaveLength(6);
  });
  it('the ring marks exactly the neighbours: 6 rings in the middle, 2 at the tip after picking cell 0', async () => {
    await render(<NetFigure />);
    expect(all('svg.ml-fig-board .ml-fig-board__nb')).toHaveLength(6);
    click(q('[data-cell="0"]'));
    expect(all('svg.ml-fig-board .ml-fig-board__nb')).toHaveLength(2);
  });
});

describe('V9b, a forward pass', () => {
  const firstPrior = (): string => text(all('.ml-fig-policy__num')[0]);
  it('the heat legend is the sentence, then one row "low, five discs, high" (review m10)', async () => {
    await render(<ForwardFigure />);
    expect(text(q('.ml-fig-heatlegend__text'))).toBe('Larger disc, larger value');
    const ramp = q('.ml-fig-heatlegend__ramp');
    expect(ramp.children[0]?.textContent).toBe('low');
    expect(ramp.querySelectorAll('circle')).toHaveLength(5);
    expect(ramp.children[2]?.textContent).toBe('high');
  });
  it('starts at the input step on the mid-game position; End goes to the value head; the plane select works only at the input', async () => {
    await render(<ForwardFigure />);
    expect(status()).toContain('Step 1 of 8');
    expect(control<HTMLSelectElement>('Input plane').disabled).toBe(false);
    const stepper = q('.ml-fig-stepper');
    key(stepper.querySelector('button') as HTMLElement, 'ArrowRight');
    expect(status()).toContain('Step 2 of 8');
    expect(control<HTMLSelectElement>('Input plane').disabled).toBe(true);
    key(stepper.querySelector('button') as HTMLElement, 'End');
    expect(status()).toContain('Step 8 of 8');
    expect(status()).toContain('+0.93');
    key(stepper.querySelector('button') as HTMLElement, 'Home');
    expect(status()).toContain('Step 1 of 8');
  });
  it('the input plane changes the board: plane 0 draws 10 discs, plane 3 (goal corner) 10 discs too, plane 6 (progress) all 121', async () => {
    await render(<ForwardFigure />);
    expect(all('[data-level]')).toHaveLength(10);
    setValue(control<HTMLSelectElement>('Input plane'), '6');
    expect(all('[data-level]')).toHaveLength(121);
    expect(status()).toContain('Game progress');
  });
  it('the board after a layer has a disc on every cell; the policy step draws five arrows', async () => {
    await render(<ForwardFigure />);
    const stepper = q('.ml-fig-stepper');
    key(stepper.querySelector('button') as HTMLElement, 'ArrowRight');
    expect(all('[data-level]')).toHaveLength(121);
    key(stepper.querySelector('button') as HTMLElement, 'End');
    key(stepper.querySelector('button') as HTMLElement, 'ArrowLeft');
    expect(status()).toContain('Step 7 of 8');
    expect(all('.ml-fig-board__arrow')).toHaveLength(5);
    expect(all('.ml-fig-board__from')).toHaveLength(5);
  });
  it('the value bars start at the zero line: the positive one to its right, the negative one to its left, with a real minus sign', async () => {
    await render(<ForwardFigure />);
    const [own, other] = all<HTMLElement>('.ml-fig-value__bar');
    expect(own?.style.left).toBe('50%');
    expect(own?.style.width).toBe('46.39%');
    expect(other?.style.left).toBe('3.61%');
    expect(other?.style.width).toBe('46.39%');
    expect(text(all('.ml-fig-value__num')[1])).toBe('\u22120.93');
  });
  it('the priors switch changes the first prior from 0.594 to the network-only 0.520, and back', async () => {
    await render(<ForwardFigure />);
    expect(firstPrior()).toBe('0.594');
    click(radio('Network only'));
    expect(firstPrior()).toBe('0.520');
    expect(text(all('.ml-fig-value__num')[0])).toBe('+0.30');
    click(radio('With residual priors'));
    expect(firstPrior()).toBe('0.594');
    expect(text(all('.ml-fig-value__num')[0])).toBe('+0.93');
  });
  it('the position select switches the data chip and the numbers: start position has 14 legal moves and a first prior of 0.015', async () => {
    await render(<ForwardFigure />);
    expect(text(q('.ml-fig__foot'))).toContain('forward-mid.json');
    setValue(control<HTMLSelectElement>('Position'), 'start');
    expect(text(q('.ml-fig__foot'))).toContain('forward-start.json');
    expect(firstPrior()).toBe('0.423'); // 0.4225 of cell 6 to 15, the highest of the 14 legal moves
    key(q('.ml-fig-stepper button'), 'End');
    key(q('.ml-fig-stepper button'), 'ArrowLeft');
    expect(status()).toContain('14 legal moves');
  });
  it('in German the numbers use a decimal comma', async () => {
    i18n.setLanguage('de');
    await render(<ForwardFigure />);
    expect(firstPrior()).toBe('0,594');
  });
});

describe('V9b, the backends', () => {
  it('draws one dot per backend on a log axis and lists the measured values with their documents', async () => {
    await render(<BackendsFigure />);
    expect(all('[data-backend]')).toHaveLength(6);
    // the values are one compact table: name, value, source (review M8; it was a list with a source chip under every row)
    const listed = all('table.ml-fig-backlist tbody tr').map((tr) => [text(tr.children[0]), text(tr.children[1]), tr.querySelector('a')?.getAttribute('href')?.split('/main/')[1]]);
    expect(listed).toEqual([
      ['Java scalar reference', '115', 'docs/network.md'],
      ['Java Vector API', '1,760', 'docs/network.md'],
      ['TornadoVM on Metal', '1,968', 'docs/tornado.md'],
      ['C++ WebAssembly', '3,100', 'docs/web-ui.md'],
      ['WebGPU', '2,000', 'docs/web-ui.md'],
      ['WebGPU, pipelined (batch 16)', '60,000', 'docs/web-ui.md'],
    ]);
    // a log axis: the x of the dots grows with the logarithm of the value
    const xs = all<SVGCircleElement>('[data-backend] circle').map((c) => Number(c.getAttribute('cx')));
    expect(xs[5]).toBeGreaterThan(xs[1] as number);
    expect(((xs[1] as number) - (xs[0] as number)) / Math.log10(1760 / 115)).toBeCloseTo(((xs[5] as number) - (xs[1] as number)) / Math.log10(60000 / 1760), 5);
  });
});

describe('V10, MCTS', () => {
  const rows = (): { move: string; edge: number; p: number; n: number; q: number | null; puct: number; next: boolean }[] =>
    all('.ml-fig-mcts__table tbody tr').map((tr) => {
      const cells = Array.from(tr.querySelectorAll('td')).map(text);
      return {
        move: text(tr.querySelector('th')).replace(/next$/, '').trim(),
        edge: Number(tr.getAttribute('data-edge')),
        p: Number(cells[0]),
        n: Number(cells[1]),
        q: cells[2] === '–' ? null : Number(cells[2]),
        puct: Number(cells[3]),
        next: tr.hasAttribute('data-next'),
      };
    });
  const showAll = (): void => click(button('Show all'));

  it('starts idle with 0 / 50; the stepper counts simulations; End is the 50th and Home the first', async () => {
    await render(<McstFigure />);
    expect(text(q('.ml-fig-stepper__pos'))).toContain('0 / 50');
    const first = q('.ml-fig-stepper button');
    key(first, 'ArrowRight');
    expect(text(q('.ml-fig-stepper__pos'))).toContain('1 / 50');
    key(first, 'End');
    expect(text(q('.ml-fig-stepper__pos'))).toContain('50 / 50');
    key(first, 'Home');
    expect(text(q('.ml-fig-stepper__pos'))).toContain('1 / 50');
  });
  it('the narration names the four sub-steps of simulation 1: root move B2›C3, one leaf, the value 0.981, the visit count', async () => {
    await render(<McstFigure />);
    key(q('.ml-fig-stepper button'), 'ArrowRight');
    expect(status()).toMatch(/^Step 1 of 50:\s*Simulation 1: selection walks 1 moves down from the root, starting with B2›C3/);
    click(radio('Expand'));
    expect(status()).toContain('The walk ends after B2›C3');
    click(radio('Evaluate'));
    expect(status()).toContain('value +0.981');
    expect(status()).toContain('48 legal moves'); // the new leaf has 48 legal moves (expanded.moves of simulation 1)
    click(radio('Back up'));
    expect(status()).toContain('B2›C3 now has 1 visits and mean value 0.981');
  });
  it('the table shows the state before the backup in the select sub-step and after it in the backup sub-step', async () => {
    await render(<McstFigure />);
    key(q('.ml-fig-stepper button'), 'ArrowRight');
    showAll();
    const before = rows().find((r) => r.move === 'B2›C3');
    expect(before?.n).toBe(0);
    click(radio('Back up'));
    expect(rows().find((r) => r.move === 'B2›C3')?.n).toBe(1);
    expect(rows().find((r) => r.move === 'B2›C3')?.q).toBeCloseTo(0.981, 3);
  });
  it.each([0.5, 1, 2.5])('after 50 simulations the PUCT column at c = %f equals Q + c * P * sqrt(50) / (1 + N) on the table values', async (c) => {
    await render(<McstFigure />);
    key(q('.ml-fig-stepper button'), 'End');
    click(radio('Back up'));
    showAll();
    setValue(control('Exploration constant'), c);
    const table = rows();
    expect(table).toHaveLength(54);
    for (const r of table) {
      if (r.q === null) continue; // unvisited: Q is the node mean, checked through the difference of two rows below
      expect(r.puct).toBeCloseTo(r.q + (c * r.p * Math.sqrt(50)) / (1 + r.n), 2);
    }
    const unvisited = table.filter((r) => r.q === null);
    expect(unvisited.length).toBe(54 - 4); // the stored final table has visits on four edges: 37, 6, 5 and 2
    // two unvisited edges differ only by their priors: score difference = c * sqrt(50) * dP
    const [a, b] = [unvisited[0], unvisited[1]] as [(typeof table)[number], (typeof table)[number]];
    const prior = (edge: number): number => (mcts.root.priors as number[])[edge] as number;
    expect(a.puct - b.puct).toBeCloseTo(c * Math.sqrt(50) * (prior(a.edge) - prior(b.edge)), 2);
  });
  it('the final choice is announced after simulation 50 and marked in the table; the next edge carries the tag', async () => {
    await render(<McstFigure />);
    expect(host.querySelector('.ml-fig-final')).toBeNull();
    key(q('.ml-fig-stepper button'), 'End');
    expect(host.querySelector('.ml-fig-final')).toBeNull(); // sub-step "select": the 50th backup has not run
    click(radio('Back up'));
    expect(text(q('.ml-fig-final'))).toBe('Choice: B2›C3, the most visited move, with 37 of 50 visits');
    expect(q('tr[data-final] th').textContent).toContain('B2›C3');
    expect(all('tr[data-next]')).toHaveLength(1);
  });
  it('the slider moves the "next" tag: at c = 4 an edge with few visits is chosen instead of the most visited one', async () => {
    await render(<McstFigure />);
    key(q('.ml-fig-stepper button'), 'End');
    click(radio('Back up'));
    const nextAt = (): string => text(q('tr[data-next] th')).replace(/next$/, '').trim();
    expect(nextAt()).toBe('B2›C3');
    setValue(control('Exploration constant'), 4);
    expect(nextAt()).not.toBe('B2›C3');
    expect(text(q('input[type="range"]').closest('.ml-fig-ctl'))).toContain('4.00');
    expect(q<HTMLInputElement>('input[type="range"]').getAttribute('aria-valuetext')).toBe('c = 4.00');
  });
  it('the tree is one picture with a title and a description; the table is a real table with a caption', async () => {
    await render(<McstFigure />);
    expect(text(q('table caption'))).toBe('Root moves after simulation 0');
    key(q('.ml-fig-stepper button'), 'End');
    click(radio('Back up'));
    expect(text(q('table caption'))).toBe('Root moves after simulation 50');
    expect(text(q('svg.ml-fig-ice title'))).toBe('Search tree');
    // a real icicle (review M6): after 50 simulations the four root moves that were tried are blocks, the other 50 moves are one block
    expect(all('svg.ml-fig-ice [data-column]')).toHaveLength(4);
    expect(all('svg.ml-fig-ice [data-rest]')).toHaveLength(1);
    expect(text(q('svg.ml-fig-ice .ml-fig-mi__label--root'))).toBe('N = 50');
  });
  it('the table headers are the symbols P, N, Q and PUCT; the long names stand once in the line below the table (review m9)', async () => {
    await render(<McstFigure />);
    expect(all('.ml-fig-mcts__table thead th').map(text)).toEqual(['Move', 'P', 'N', 'Q', 'PUCT']);
    expect(text(q('.ml-fig-mcts__symbols'))).toBe('Prior P · Visits N · Mean value Q · PUCT score');
  });
  it('in a narrow figure the next move is marked with a glyph in front of the move, not with a tag that wraps under it (review m9)', async () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width: 300, height: 100, x: 0, y: 0, top: 0, left: 0, right: 300, bottom: 100, toJSON: () => ({}) }));
    await render(<McstFigure />);
    const row = q('.ml-fig-mcts__table tr[data-next]');
    expect(row.querySelector('.ml-fig-tag')).toBeNull();
    expect(text(row.querySelector('.ml-fig-mcts__mark'))).toBe('▸');
    expect(text(row.querySelector('th'))).toContain('next'); // the word stays for a screen reader
    expect(text(q('.ml-fig-mcts__symbols'))).toContain('▸ next');
    vi.restoreAllMocks();
  });
  it('the disclosure shows all 54 moves and hides them again', async () => {
    await render(<McstFigure />);
    expect(all('.ml-fig-mcts__table tbody tr').length).toBeLessThan(10);
    showAll();
    expect(all('.ml-fig-mcts__table tbody tr')).toHaveLength(54);
    click(button('Show less'));
    expect(all('.ml-fig-mcts__table tbody tr').length).toBeLessThan(10);
  });
  it('the inset has five points with the Elo of the sweep, the best one in bold', async () => {
    await render(<McstFigure />);
    const labels = all('.ml-fig-plot__value, .ml-fig-plot__best').map(text);
    expect(labels).toEqual(['+252', '+338', '+269', '+108', '+33']);
    expect(text(q('.ml-fig-plot__best'))).toBe('+338');
  });
});

describe('V11, the self-play loop', () => {
  const generation = (): HTMLInputElement => control('Generation');
  it('starts at generation 14: rejected with 0.490; generation 1 is promoted with 0.605', async () => {
    await render(<LoopFigure />);
    expect(status()).toBe('Generation 14: gate score 0.490, rejected');
    setValue(generation(), 1);
    expect(status()).toBe('Generation 1: gate score 0.605, promoted');
    expect(text(q('.ml-fig-cycle__item[data-pos="6"]'))).toContain('promoted');
  });
  it.each([
    [1, '0.605', 'promoted'],
    [4, '0.517', 'rejected'],
    [6, '0.508', 'rejected'],
    [8, '0.551', 'promoted'],
    [10, '0.483', 'rejected'],
    [13, '0.524', 'promoted'],
  ])('generation %i: gate score %s, %s', async (gen, score, decision) => {
    await render(<LoopFigure />);
    setValue(generation(), gen);
    expect(status()).toBe(`Generation ${gen}: gate score ${score}, ${decision}`);
    expect(q<HTMLInputElement>('input[type="range"]').getAttribute('aria-valuetext')).toBe(`Generation ${gen}: gate score ${score}, ${decision}`);
    expect(q('.ml-fig-cycle').getAttribute('data-return')).toBe(decision === 'promoted' ? 'data' : 'control');
  });
  it('the old rule marks the rejected generations 4, 6, 7 and 11 with a diamond and names them in a note', async () => {
    await render(<LoopFigure />);
    expect(all('svg.ml-fig-plot .ml-fig-plot__pt--3')).toHaveLength(0);
    expect(host.textContent).not.toContain('would also have promoted');
    click(control('Mark the old rule'));
    expect(all('svg.ml-fig-plot .ml-fig-plot__pt--3')).toHaveLength(4);
    expect(text(q('.ml-fig-note--strong'))).toContain('generations 4, 6, 7, 11');
    // the hollow squares are the rejected generations that remain: 10 and 14
    expect(all('svg.ml-fig-plot .ml-fig-plot__pt--2')).toHaveLength(2);
  });
  it('the timeline has 8 filled and 6 hollow dots, and the ladder four points with whiskers', async () => {
    await render(<LoopFigure />);
    expect(all('svg.ml-fig-plot .ml-fig-plot__pt--1')).toHaveLength(8 + 4);
    expect(all('svg.ml-fig-plot .ml-fig-plot__pt--2')).toHaveLength(6);
    expect(all('.ml-fig-plot__whisker')).toHaveLength(4);
  });
  it('a stage opens its detail with a source chip; Escape closes it', async () => {
    await render(<LoopFigure />);
    const gate = button('5Gate');
    click(gate);
    expect(gate.getAttribute('aria-expanded')).toBe('true');
    expect(text(q('section.ml-fig__detail'))).toContain('200 pairs');
    expect(q('section.ml-fig__detail a').getAttribute('href')).toMatch(/selfplay\/src\/main\/java\/sternhalma\/selfplay\/Gate\.java$/);
    key(q('section.ml-fig__detail button'), 'Escape');
    expect(host.querySelector('section.ml-fig__detail')).toBeNull();
  });
});

describe('V12, many games and one device thread', () => {
  it('steps through the six steps with the narration of the catalog; the active parts change', async () => {
    await render(<PooledFigure />);
    expect(status()).toContain('Step forward to follow one cycle');
    expect(all('[data-edge][data-state="active"]')).toHaveLength(0);
    for (let step = 1; step <= 6; step++) {
      key(q('.ml-fig-stepper button'), 'ArrowRight');
      expect(status()).toBe(`Step ${step} of 6:${pooledStep(step).replace(/`/g, '')}`);
    }
    // step 6: the device computes batch B, cohort A fills
    expect(all('[data-edge][data-state="active"]').map((e) => e.getAttribute('data-edge'))).toEqual(['aIn']);
    expect(text(q('.ml-fig-pooled__box[data-state="active"]'))).toBeTruthy();
    key(q('.ml-fig-stepper button'), 'Home');
    expect(all('[data-edge][data-state="active"]').map((e) => e.getAttribute('data-edge'))).toEqual(['aIn']);
    key(q('.ml-fig-stepper button'), 'ArrowRight');
    expect(all('[data-edge][data-state="active"]').map((e) => e.getAttribute('data-edge'))).toEqual(['aOut']);
    key(q('.ml-fig-stepper button'), 'ArrowRight');
    expect(all('[data-edge][data-state="active"]').map((e) => e.getAttribute('data-edge'))).toEqual(['bIn']);
  });
  it('CPU mode has no evaluator, trays, device or edges, a mark per game and the CPU note; GPU mode brings them back', async () => {
    await render(<PooledFigure />);
    expect(text(q('svg.ml-fig-pooled__svg'))).toContain('Device thread');
    click(radio('CPU: one game per thread'));
    expect(text(q('svg.ml-fig-pooled__svg'))).not.toContain('Device thread');
    expect(all('[data-edge]')).toHaveLength(0);
    expect(all('.ml-fig-pooled__own').length).toBeGreaterThan(0);
    expect(host.querySelector('.ml-fig-stepper')).toBeNull();
    expect(status()).toContain('each game thread runs its own forward pass');
    click(radio('GPU: pooled batches'));
    expect(text(q('svg.ml-fig-pooled__svg'))).toContain('Device thread');
    expect(all('.ml-fig-pooled__own')).toHaveLength(0);
  });
  it('says that the picture is schematic and names the measured throughput through the caption', async () => {
    await render(<PooledFigure />);
    expect(text(q('.ml-tag'))).toBe('schematic');
    expect(text(q('.ml-fig__caption'))).toContain('3.3 games per second');
    expect(text(q('.ml-fig__caption'))).toContain('1.35');
  });
});
const pooledStep = (n: number): string => trainingCatalog.lookup('en', `fig.pooled.step.${n}` as never);

describe('V13, the learning-rate schedule', () => {
  it('starts on the sweep 1 winner: the preset is pressed and the readout names the rate at the middle step', async () => {
    await render(<LrFigure />);
    expect(button('Sweep 1 winner').getAttribute('aria-pressed')).toBe('true');
    expect(button('Generation 0').getAttribute('aria-pressed')).toBe('false');
    const s = { base: 3e-4, final: 1e-5, warmup: 100, steps: 15000 };
    expect(status()).toBe(`Step 7,500: rate ${rateText(rateAt(s, 7500))}`);
    expect(status()).toBe('Step 7,500: rate 1.6e-4');
  });
  it('a preset sets the sliders: generation 0 has base 1e-3, warm-up 200 and 5,800 steps', async () => {
    await render(<LrFigure />);
    click(button('Generation 0'));
    expect(button('Generation 0').getAttribute('aria-pressed')).toBe('true');
    expect(text(control('Base rate').closest('.ml-fig-ctl'))).toContain('1e-3');
    expect(text(control('Final rate').closest('.ml-fig-ctl'))).toContain('1e-4');
    expect(text(control('Warm-up steps').closest('.ml-fig-ctl'))).toContain('200');
    expect(text(control('Total steps').closest('.ml-fig-ctl'))).toContain('5,800');
  });
  it('the constant preset is flat: the rate at the first and the last step after the warm-up is 3e-4', async () => {
    await render(<LrFigure />);
    click(button('Constant, loop run 2'));
    setValue(control('Marked step'), 14999);
    expect(status()).toBe('Step 14,999: rate 3e-4');
    setValue(control('Marked step'), 0);
    expect(status()).toBe('Step 0: rate 3e-6'); // warm-up: 3e-4 * 1 / 100
  });
  it('a slider that changes the schedule unpresses the preset', async () => {
    await render(<LrFigure />);
    setValue(control('Warm-up steps'), 150);
    for (const p of ['Generation 0', 'Constant, loop run 2', 'Sweep 1 winner']) expect(button(p).getAttribute('aria-pressed')).toBe('false');
  });
  it('the run stays longer than its warm-up by two steps', async () => {
    await render(<LrFigure />);
    setValue(control('Total steps'), 500);
    setValue(control('Warm-up steps'), 1000);
    expect(control('Total steps').value).toBe('1002');
  });
  it('at the boundary the run is one step longer than its warm-up plus one: warm-up 499 and 500 steps give 501 steps', async () => {
    await render(<LrFigure />);
    setValue(control('Total steps'), 500);
    setValue(control('Warm-up steps'), 499);
    expect(text(control('Total steps').closest('.ml-fig-ctl'))).toContain('501');
    setValue(control('Warm-up steps'), 498);
    expect(text(control('Total steps').closest('.ml-fig-ctl'))).toContain('501'); // 500 > 498 + 1: left alone
  });
  it('the slider reads the rate in words: "3 times 10 to the power of -4"', async () => {
    await render(<LrFigure />);
    expect(control('Base rate').getAttribute('aria-valuetext')).toBe('3 times 10 to the power of -4');
  });
  it('the log axis has ticks at powers of ten; the linear axis starts at zero', async () => {
    await render(<LrFigure />);
    const ticks = (): string[] => all('.ml-fig-plot text').map(text);
    expect(ticks().filter((t) => t === '0')).toHaveLength(2); // the start of the x axis and of the y axis
    click(control('Logarithmic y-axis'));
    expect(ticks()).toContain('1e-5');
    expect(ticks()).toContain('1e-4');
    expect(ticks().filter((t) => t === '0')).toHaveLength(1); // only the x axis starts at zero
  });
});

describe('reduced motion', () => {
  const stubMotion = (reduce: boolean): void => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: reduce && query.includes('prefers-reduced-motion'),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }));
  };
  it.each([
    ['forward', () => <ForwardFigure />],
    ['mcts', () => <McstFigure />],
    ['pooled', () => <PooledFigure />],
  ] as const)('%s: the play button is there normally and gone under reduced motion, the stepper keeps back and forward', async (_id, make) => {
    stubMotion(false);
    await render(make());
    expect(host.querySelector('.ml-fig-stepper [aria-label="Play"]')).not.toBeNull();
    act(() => root.unmount());
    root = createRoot(host);
    stubMotion(true);
    await render(make());
    expect(host.querySelector('.ml-fig-stepper [aria-label="Play"]')).toBeNull();
    expect(host.querySelector('.ml-fig-stepper [aria-label="One step forward"]')).not.toBeNull();
    expect(host.querySelector('.ml-fig-stepper [aria-label="One step back"]')).not.toBeNull();
  });
});

describe('the topic pages', () => {
  const place = (id: string): string | null => document.querySelector(`#${id}`)?.closest('section.ml-about__section')?.querySelector('h2')?.id ?? null;
  it('inference: V9a under hex-conv, V9b under forward-pass and backends, V10 under mcts, each once', async () => {
    await render(<InferencePage topic="inference" onJumpSection={() => undefined} />);
    expect(place('fig-net')).toBe('about-inference-hex-conv');
    expect(place('fig-forward')).toBe('about-inference-forward-pass');
    expect(place('fig-mcts')).toBe('about-inference-mcts');
    expect(place('fig-backends')).toBe('about-inference-backends');
    expect(all('figure.ml-fig')).toHaveLength(4);
  });
  it('training: V13 under learning, V11 under loop, V12 under throughput', async () => {
    await render(<TrainingPage topic="training" onJumpSection={() => undefined} />);
    expect(place('fig-lr')).toBe('about-training-learning');
    expect(place('fig-loop')).toBe('about-training-loop');
    expect(place('fig-pooled')).toBe('about-training-throughput');
    expect(all('figure.ml-fig')).toHaveLength(3);
  });
  it('the page text comes from the catalog and the figure numbers run 3.1 to 3.4 and 4.1 to 4.3', async () => {
    await render(<InferencePage topic="inference" onJumpSection={() => undefined} />);
    expect(all('.ml-fig__label').map(text)).toEqual(['Fig. 3.1', 'Fig. 3.2', 'Fig. 3.3', 'Fig. 3.4']);
    expect(text(q('h1'))).toBe(inferenceCatalog.lookup('en', 'title'));
    act(() => root.unmount());
    root = createRoot(host);
    await render(<TrainingPage topic="training" onJumpSection={() => undefined} />);
    expect(all('.ml-fig__label').map(text)).toEqual(['Fig. 4.1', 'Fig. 4.2', 'Fig. 4.3']);
  });
});
