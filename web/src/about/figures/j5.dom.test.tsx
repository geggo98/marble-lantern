// @vitest-environment jsdom
// Figure J5 (GPU overhead and batching) in jsdom: the 20 states (machine x network x batch) with their hooks, the pressed presets, the empty
// split, the five table rows, the verdict stack, the cards, the accessible names, both languages, a series that lacks a batch size, and the
// place of the figure in the topic page. Layout, keyboard and reduced motion are in e2e/about-j5.spec.ts.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import JavaPage from '../topics/java';
import { J5OverheadFigure } from './J5OverheadFigure';
import { javaCatalog } from './java-catalog';
import type { Catalog } from './common';
import { BATCHES, J5_DATA, callPoint, splitFor } from './j5-model';
import type { J5Data, Machine, Net } from './j5-model';

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
const show = (props: Partial<React.ComponentProps<typeof J5OverheadFigure>> = {}): Promise<void> => render(<J5OverheadFigure catalog={catalog} number={[6, 5]} {...props} />);
const fig = (): HTMLElement => q('figure#fig-j5');

const choose = async (label: string): Promise<void> => {
  const radio = all<HTMLInputElement>('input[type="radio"]').find((r) => text(r.closest('label')).startsWith(label));
  if (!radio) throw new Error(`no option ${label}`);
  await act(async () => {
    radio.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
};
const slide = async (index: number): Promise<void> => {
  const input = q<HTMLInputElement>('input[type="range"]');
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, String(index));
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
};
const preset = async (label: string): Promise<void> => {
  const button = all<HTMLButtonElement>('.ml-fig-presets button').find((b) => text(b) === label);
  if (!button) throw new Error(`no preset ${label}`);
  await act(async () => button.click());
};
const apply = async (machine: Machine, net: Net, batch: number): Promise<void> => {
  await choose(machine === 'mac' ? 'M3 Max' : 'T4');
  await choose(net === 'SMALL' ? 'SMALL' : 'LARGE');
  await slide(BATCHES.indexOf(batch));
};
const cell = (id: string): string => text(q(`[data-cell="${id}"] dd`));

describe('figure J5, first state', () => {
  it('opens at Mac, SMALL, batch 1 with the hooks on the figure and a verdict of "dominates"', async () => {
    await show();
    expect(fig().dataset).toMatchObject({ machine: 'mac', net: 'SMALL', batch: '1', verdict: 'dominates' });
    expect(text(q('.ml-fig__label'))).toBe('Fig. 6.5');
    expect(text(q('#fig-j5-title'))).toBe('What one GPU call costs, and what batching changes');
  });

  it('shows the call of 510 µs as 2 % work and 98 % rest, all values as text in the legend rows', async () => {
    await show();
    const bar = q('[data-part="call"]');
    expect(bar.getAttribute('data-rest-pct')).toBe('98');
    expect(bar.getAttribute('role')).toBe('group');
    expect(bar.getAttribute('aria-label')).toMatch(/^Time of this call, split into/);
    expect(text(q('[data-part="call"] [data-total]'))).toBe('510 µs');
    expect(text(q('[data-part="call"] [data-row="work"]'))).toContain('8.4 µs');
    expect(text(q('[data-part="call"] [data-row="work"]'))).toContain('2 %');
    expect(text(q('[data-part="call"] [data-row="rest"]'))).toContain('98 %');
    expect(q('[data-part="call"] .ml-fig-share__bar').getAttribute('aria-hidden')).toBe('true');
    expect(q('[data-part="call"] [data-seg="work"]').getAttribute('data-fill')).toBe('solid');
    expect(q('[data-part="call"] [data-seg="rest"]').getAttribute('data-fill')).toBe('hatch');
  });

  it('has the four readout cells: per position, positions per second, factor, one CPU thread', async () => {
    await show();
    expect(cell('perpos')).toBe('510 µs');
    expect(cell('rate')).toBe('2,000');
    expect(cell('factor')).toBe('1×');
    expect(cell('cpu')).toBe('340 µs');
  });

  it('has no narration line and no live region', async () => {
    await show();
    expect(all('[role="status"], [aria-live]')).toHaveLength(0);
    expect(all('.ml-fig__status')).toHaveLength(0);
  });

  it('names the figure, describes it, and names every block by its h4', async () => {
    await show();
    expect(fig().getAttribute('aria-labelledby')).toBe('fig-j5-title');
    const described = fig().getAttribute('aria-describedby')!;
    expect(text(document.getElementById(described))).toMatch(/^One forward call of the network on the GPU/);
    const sections = all('section[aria-labelledby]');
    expect(sections).toHaveLength(5);
    for (const s of sections) expect(text(document.getElementById(s.getAttribute('aria-labelledby')!)), s.getAttribute('data-block') ?? '').not.toBe('');
    expect(all('h4.ml-fig-j5__h .ml-tag').map((e) => text(e))).toEqual(['derived', 'schematic', 'measured']);
  });

  it('puts no value into a block heading (the batch stands only in the slider readout)', async () => {
    await show();
    for (const h of all('h4.ml-fig-j5__h')) expect(text(h), text(h)).not.toMatch(/\d/);
  });
});

describe('figure J5, controls', () => {
  it('has a machine switch, a network switch, two presets and one slider over five stops with tick labels', async () => {
    await show();
    expect(all('.ml-fig-seg').map((g) => g.querySelectorAll('input').length)).toEqual([2, 2]);
    expect(all('.ml-fig-presets button').map((b) => text(b))).toEqual(['One search', 'Self-play']);
    const slider = q<HTMLInputElement>('input[type="range"]');
    expect([slider.min, slider.max, slider.step, slider.value]).toEqual(['0', '4', '1', '0']);
    expect(all('.ml-fig-ticks span').map((e) => text(e))).toEqual(['1', '16', '64', '256', '1,024']);
    expect(q('.ml-fig-ticks [aria-current="true"]').textContent).toBe('1');
    expect(text(q('.ml-fig-ctl__out'))).toBe('1');
  });

  it('speaks the slider through aria-valuetext: batch, time per call, time per position', async () => {
    await show();
    await slide(3);
    expect(q('input[type="range"]').getAttribute('aria-valuetext')).toBe('Batch 256: 2.3 ms per call, 9 µs per position');
  });

  it('presses `Self-play` at batch 256 and `One search` at batch 1, and nothing at 16', async () => {
    await show();
    const pressed = (): (string | null)[] => all('.ml-fig-presets button').map((b) => b.getAttribute('aria-pressed'));
    expect(pressed()).toEqual(['true', 'false']);
    await preset('Self-play');
    expect(pressed()).toEqual(['false', 'true']);
    expect(fig().dataset.batch).toBe('256');
    expect(q<HTMLInputElement>('input[type="range"]').value).toBe('3');
    await slide(1);
    expect(pressed()).toEqual(['false', 'false']);
    await preset('One search');
    expect(fig().dataset.batch).toBe('1');
  });

  it('keeps the batch when the machine or the network changes', async () => {
    await show();
    await slide(2);
    await choose('T4');
    expect(fig().dataset).toMatchObject({ machine: 't4', net: 'SMALL', batch: '64' });
    await choose('LARGE');
    expect(fig().dataset).toMatchObject({ machine: 't4', net: 'LARGE', batch: '64' });
  });

  it('changes the kernel count of the loop with the network (11 for SMALL, 15 for LARGE)', async () => {
    await show();
    expect(q('[data-loop]').getAttribute('data-loop')).toBe('11');
    expect(text(q('.ml-fig-j5__looplabel'))).toContain('11');
    await choose('LARGE');
    expect(q('[data-loop]').getAttribute('data-loop')).toBe('15');
    expect(text(q('.ml-fig-j5__looplabel'))).toContain('15');
  });
});

describe('figure J5, all 20 states', () => {
  const states = (['mac', 't4'] as const).flatMap((m) => (['SMALL', 'LARGE'] as const).flatMap((n) => BATCHES.map((b) => [m, n, b] as const)));

  it.each(states)('%s %s batch %d: hooks, bar, table, verdict, split and cards agree with the model', async (m, n, b) => {
    await show();
    await apply(m, n, b);
    const p = callPoint(m, n, b);
    expect(fig().dataset).toMatchObject({ machine: m, net: n, batch: String(b), verdict: p.verdict });
    expect(q('[data-part="call"]').getAttribute('data-rest-pct')).toBe(String(Math.round(p.share * 100)));
    // the rest segment of exactly 0 is not drawn, any other one is
    expect(q('[data-part="call"] [data-seg="rest"]').hasAttribute('data-zero')).toBe(b === 1024);
    expect(q('[data-part="call"] [data-seg="work"]').hasAttribute('data-zero')).toBe(false);
    expect(all('[data-part="call"] [data-row]')).toHaveLength(2);
    // always five rows, exactly one current
    expect(all('[data-part="table"] tbody tr').map((r) => r.getAttribute('data-batch'))).toEqual(['1', '16', '64', '256', '1024']);
    expect(all('[data-part="table"] tr[data-current]').map((r) => r.getAttribute('data-batch'))).toEqual([String(b)]);
    expect(q('[data-part="table"] tr[data-current]').getAttribute('aria-current')).toBe('true');
    // one verdict is visible, two are hidden from the eye and from screen readers
    const visible = all('[data-part="verdict"] p').filter((e) => !e.hasAttribute('data-off'));
    expect(visible.map((e) => e.getAttribute('data-verdict'))).toEqual([p.verdict]);
    expect(all('[data-part="verdict"] p[data-off]').every((e) => e.getAttribute('aria-hidden') === 'true')).toBe(true);
    // the split: a bar with its attribute, or the empty state; the hidden copies carry no hooks
    const view = splitFor(m, n, b);
    if (view) {
      expect(all('[data-part="split"][data-split]').map((e) => e.getAttribute('data-split'))).toEqual([`${m}-${n}-${b}`]);
      expect(all('[data-part="split"][data-empty]')).toHaveLength(0);
      expect(all('[data-part="split"] [data-seg]').map((e) => e.getAttribute('data-seg'))).toEqual(m === 'mac' ? ['gpu', 'outside'] : ['h2d', 'kernel', 'd2h']);
      expect(all('[data-part="split"] [data-row]').map((e) => e.getAttribute('data-row'))).toEqual(m === 'mac' ? ['gpu', 'outside', 'copies'] : ['h2d', 'kernel', 'd2h']);
    } else {
      expect(all('[data-part="split"][data-split]')).toHaveLength(0);
      expect(all('[data-part="split"][data-empty]')).toHaveLength(1);
    }
    // cards: pool from batch 64, one search at batch 1, the tree never
    const active = all('[data-part="where"] [data-use][data-active]').map((e) => e.getAttribute('data-use'));
    expect(active).toEqual([...(b >= 64 ? ['pool'] : []), ...(b === 1 ? ['one'] : [])].sort((x, y) => ['pool', 'tree', 'one'].indexOf(x) - ['pool', 'tree', 'one'].indexOf(y)));
    // the plot: five dots, the chosen one with a ring, the reference line for one CPU thread
    expect(all('[data-part="plot"] [data-dot]').map((e) => e.getAttribute('data-dot'))).toEqual(['1', '16', '64', '256', '1024']);
    expect(all('[data-part="plot"] [data-ring]')).toHaveLength(1);
    expect(all('[data-part="plot"] [data-ref="cpu"]')).toHaveLength(1);
    expect(all('[data-part="plot"] .ml-fig-plot__catlabel[data-current]').map((e) => text(e))).toEqual([BATCHES.indexOf(b) === 4 ? '1,024' : String(b)]);
  });

  it('has an empty split at the start (Mac SMALL has no measured split), with the empty text', async () => {
    await show();
    expect(all('[data-part="split"][data-empty]')).toHaveLength(1);
    expect(text(q('[data-empty]'))).toMatch(/^Not measured here/);
  });
});

describe('figure J5, the split, the plot and the cards', () => {
  it('shows the Mac LARGE split at batch 1 with GPU time, outside time and the copies as a number without share', async () => {
    await show({ initial: { machine: 'mac', net: 'LARGE', batch: 1 } });
    expect(text(q('[data-split] [data-total]'))).toBe('790 µs');
    expect(text(q('[data-split] [data-row="gpu"]'))).toContain('530 µs');
    expect(text(q('[data-split] [data-row="copies"]'))).toContain('5 µs');
    expect(text(q('[data-split] [data-row="copies"]'))).not.toContain('%');
    expect(q('[data-split] [data-row="copies"] .ml-fig-share__key').getAttribute('data-fill')).toBe('none');
  });

  it('shows the T4 split at batch 256 as copy in, kernels and copy out, with the dot pattern for the copies', async () => {
    await show({ initial: { machine: 't4', net: 'SMALL', batch: 256 } });
    expect(all('[data-split] [data-seg]').map((e) => e.getAttribute('data-fill'))).toEqual(['dots', 'solid', 'dots']);
    expect(text(q('[data-split] [data-total]'))).toBe('2.7 ms');
  });

  it('draws the CPU reference line and, wide or not, the legend with its value', async () => {
    await show();
    expect(text(q('.ml-fig-legend'))).toContain('1 thread, Vector API: 340 µs');
    expect(all('.ml-fig-plot text').map((e) => text(e))).toEqual(expect.arrayContaining(['10 µs', '100 µs', '1 ms']));
  });

  it('names the plot with a title and a description that carry the numbers', async () => {
    await show();
    const svg = q('svg.ml-fig-plot');
    expect(svg.getAttribute('role')).toBe('img');
    expect(text(q('svg.ml-fig-plot title'))).toBe('Time per position over the batch size');
    expect(text(q('svg.ml-fig-plot desc'))).toContain('510 µs at batch 1, 8.4 µs at batch 1,024');
    expect(text(q('svg.ml-fig-plot desc'))).toContain('340 µs');
  });

  it('lists the six phases in order, three of them marked "same for every call" (hatch) and four "grows with the batch" (solid)', async () => {
    await show();
    const phases = all('[data-part="phases"] [data-phase]');
    expect(phases.map((e) => e.getAttribute('data-phase'))).toEqual(['prep', 'copyin', 'launch', 'kernel', 'copyout', 'sync']);
    expect(phases.map((e) => e.getAttribute('data-scales'))).toEqual(['batch', 'batch', 'call', 'batch', 'batch', 'call']);
    expect(phases.every((e) => /Same for every call|Grows with the batch/.test(text(e.querySelector('.ml-visually-hidden'))))).toBe(true);
    expect(all('[data-group="execute"] [data-phase]').map((e) => e.getAttribute('data-phase'))).toEqual(['copyin', 'launch', 'kernel', 'copyout']);
    expect(all('[data-loop] [data-phase]').map((e) => e.getAttribute('data-phase'))).toEqual(['launch', 'kernel']);
  });

  it('has three cards with the numbers of the data, the pool link to Fig. 4.3 and the browser tag on the tree card', async () => {
    await show();
    expect(all('[data-part="where"] [data-use]').map((e) => e.getAttribute('data-use'))).toEqual(['pool', 'tree', 'one']);
    const pool = text(q('[data-use="pool"]'));
    expect(pool).toContain('512 games');
    expect(pool).toContain('250 µs');
    expect(pool).toContain('20 ms');
    const link = q<HTMLAnchorElement>('[data-use="pool"] a');
    expect(link.getAttribute('href')).toBe('#/about/training/throughput');
    expect(text(link)).toBe('Steps in Fig. 4.3');
    const tree = text(q('[data-use="tree"]'));
    expect(tree).toContain('0.20 to 0.26');
    expect(text(q('[data-use="tree"] .ml-tag'))).toBe('browser, WebGPU');
  });

  it('puts the source chips and the data chip into the foot', async () => {
    await show();
    const chips = all('.ml-fig__foot .ml-fig-chip').map((c) => text(c));
    for (const needle of ['docs/tornado.md', 'docs/cpp-comparison.md', 'PooledEvaluator.java', 'j5-overhead.json']) expect(chips.some((c) => c.includes(needle)), needle).toBe(true);
    expect(text(q('.ml-fig__caption'))).toMatch(/lower bounds/);
  });
});

describe('figure J5, German and data changes', () => {
  it('switches every text, the number format and the verdict to German', async () => {
    await show({ initial: { machine: 'mac', net: 'SMALL', batch: 1024 } });
    await act(async () => i18n.setLanguage('de'));
    expect(text(q('#fig-j5-title'))).toBe('Was ein GPU-Aufruf kostet und was Batching ändert');
    expect(text(q('.ml-fig-ticks span:last-child'))).toBe('1.024');
    expect(cell('perpos')).toBe('8,4 µs');
    expect(cell('rate')).toBe('120.000');
    expect(text(q('[data-use="tree"]'))).toContain('0,20 bis 0,26');
    expect(text(q('[data-part="call"] [data-row="rest"]'))).toContain('0 %');
    expect(all('.ml-fig-presets button').map((b) => text(b))).toEqual(['Eine Suche', 'Self-Play']);
  });

  it('shows "not measured" for a batch size that a series lacks, and takes the nearest measured size', async () => {
    const data = structuredClone(J5_DATA) as J5Data;
    data.machines.mac.series.SMALL = data.machines.mac.series.SMALL.filter((r) => r.batch !== 64);
    await show({ data, initial: { batch: 64 } });
    expect(all('[data-part="table"] tbody tr')).toHaveLength(5);
    expect(all('[data-part="table"] tr[data-unmeasured]').map((r) => r.getAttribute('data-batch'))).toEqual(['64']);
    expect(text(q('tr[data-unmeasured]'))).toContain('not measured');
    expect(fig().dataset.batch).toBe('16');
    expect(all('[data-part="plot"] [data-dot]')).toHaveLength(4);
  });
});

describe('figure J5 in the topic page java', () => {
  it('stands in the section tornadovm, above the snippets b1 and b2, numbered Fig. 6.5', async () => {
    await render(<JavaPage topic="java" section={undefined} onJumpSection={() => undefined} />);
    const section = q('section[aria-labelledby="about-java-tornadovm"]');
    expect(section.querySelector('figure#fig-j5')).toBeTruthy();
    expect(text(section.querySelector('figure#fig-j5 .ml-fig__label'))).toBe('Fig. 6.5');
    const order = Array.from(section.querySelectorAll('figure')).filter((e) => e.matches('#fig-j5, [data-snippet]')).map((e) => e.id || e.getAttribute('data-snippet'));
    expect(order).toEqual(['fig-j5', 'b1', 'b2']);
  });
});
