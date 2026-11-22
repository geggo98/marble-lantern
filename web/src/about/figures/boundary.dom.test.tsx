// @vitest-environment jsdom
// Figure J7 (TeaVM and the call boundary, topic java-web) in jsdom: the test hooks of the spec, the two walls, the variant switch that keeps the
// step, the counters, the narration and its ghosts, the payload card, the table markers, the keyboard, and both languages. Layout at 320 px,
// 820 px and 1280 px is in e2e/about-boundary.spec.ts.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import JavaWebPage from '../topics/java-web';
import { BoundaryFigure } from './BoundaryFigure';
import en from '../i18n/en/java-web.json';
import de from '../i18n/de/java-web.json';
import { VARIANTS, WALL_KIND } from './boundary-model';
import type { Variant } from './boundary-model';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const texts = { en, de } as Record<'en' | 'de', Record<string, string>>;
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
const fig = (): HTMLElement => q('figure');
const stepButtons = (): HTMLButtonElement[] => all<HTMLButtonElement>('.ml-fig-stepper button');
const click = async (el: Element): Promise<void> => {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
};
const forward = (): Promise<void> => click(stepButtons().at(-1)!);
const goTo = async (step: number): Promise<void> => {
  await click(stepButtons()[0]!);
  for (let i = 0; i < step; i++) await forward();
};
const chooseVariant = async (v: Variant): Promise<void> => click(q(`input[type="radio"][value="${v}"]`));
const key = async (k: string): Promise<void> => {
  await act(async () => {
    q('.ml-fig-stepper').dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
  });
};
const lane = (id: string): HTMLElement => q(`[data-lane="${id}"]`);
const wall = (id: string): HTMLElement => q(`[data-wall="${id}"]`);
const kindOf = (id: string): string | null => wall(id).querySelector('[data-kind]')!.getAttribute('data-kind');
/** The text of the one child of a GhostCell that is not hidden. */
const visible = (cell: Element): string => text([...cell.children].find((c) => !c.hasAttribute('data-off')));

describe('figure J7: hooks and structure', () => {
  it('starts at step 0 with the default variant wasmgc, and sets the hooks on the figure', async () => {
    await render(<BoundaryFigure />);
    expect(fig().getAttribute('data-testid')).toBe('fig-boundary');
    expect([fig().dataset.step, fig().dataset.stepId, fig().dataset.variant]).toEqual(['0', 'idle', 'wasmgc']);
    expect(fig().id).toBe('fig-boundary');
    expect(text(q('.ml-fig__label'))).toBe('Fig. 7.2');
    expect(text(q('.ml-fig__title'))).toBe(texts.en['fig.boundary.title']);
  });

  it('has three lanes in the order of the call, two walls, and no lane state at step 0', async () => {
    await render(<BoundaryFigure />);
    expect(all('[data-lane]').map((l) => l.dataset.lane)).toEqual(['page', 'glue', 'engine']);
    expect(all('[data-wall]').map((w) => w.dataset.wall)).toEqual(['msg', 'call']);
    for (const l of all('[data-lane]')) {
      expect(l.hasAttribute('data-state')).toBe(false);
      expect(l.hasAttribute('aria-current')).toBe(false);
    }
  });

  it('draws the lane diagram as a list with an accessible name, and the region as a group with a name', async () => {
    await render(<BoundaryFigure />);
    const list = q('ol.ml-fig-lanes');
    expect(list.getAttribute('aria-label')).toBe(texts.en['fig.boundary.lanes.aria']);
    const group = q('[role="group"][aria-labelledby]');
    expect(text(document.getElementById(group.getAttribute('aria-labelledby')!))).toBe(texts.en['fig.boundary.group.worker']);
    expect(group.querySelectorAll('[data-lane]')).toHaveLength(2);
  });

  it('has the payload card as a named region, a table with caption and scoped headers, and svgs that are all decorative', async () => {
    await render(<BoundaryFigure />);
    const card = q('[data-testid="boundary-payload"]');
    expect(text(document.getElementById(card.getAttribute('aria-labelledby')!))).toBe(texts.en['fig.boundary.pay.label']);
    const table = q('table[data-testid="boundary-compare"]');
    expect(text(table.querySelector('caption'))).toBe(texts.en['fig.boundary.cmp.caption']);
    expect(all('thead th')).toHaveLength(3);
    expect(all('thead th').every((th) => th.getAttribute('scope') === 'col')).toBe(true);
    expect(all('tbody th').every((th) => th.getAttribute('scope') === 'row')).toBe(true);
    // the svgs of the figure body (lanes, walls, table notes, legend) carry no meaning of their own; the kit's icon svgs are checked by the kit tests
    for (const svg of all('.ml-fig-j7 svg, .ml-fig-legend svg')) expect(svg.closest('[aria-hidden="true"]'), 'decorative svg').not.toBeNull();
  });
});

describe('figure J7: walls and variants', () => {
  it.each(VARIANTS)('wall A keeps the style msg and wall B has the style of the build (%s)', async (v) => {
    await render(<BoundaryFigure initialVariant={v} />);
    expect(kindOf('msg')).toBe('msg');
    expect(kindOf('call')).toBe(WALL_KIND[v]);
  });

  it('changes wall B, the tag, the files and the memory note on a switch, and wall A not at all', async () => {
    await render(<BoundaryFigure />);
    const seen: string[] = [];
    for (const v of VARIANTS) {
      await chooseVariant(v);
      expect(fig().dataset.variant).toBe(v);
      expect(kindOf('msg')).toBe('msg');
      expect(kindOf('call')).toBe(WALL_KIND[v]);
      expect(visible(wall('call').querySelector('.ml-fig-ghostcell')!)).toBe(texts.en[`fig.boundary.wall.call.tag.${v}`]);
      expect(visible(lane('engine').querySelector('.ml-fig-lanes__mem')!)).toBe(texts.en[`fig.boundary.lane.engine.mem.${v}`]);
      seen.push(visible(lane('engine').querySelector('.ml-fig-lanes__code')!));
    }
    expect(seen).toEqual(['engine-java.js', 'engine-java.wasm', 'engine-cpp']);
  });

  it('keeps the step when the variant changes', async () => {
    await render(<BoundaryFigure />);
    await goTo(3);
    for (const v of VARIANTS) {
      await chooseVariant(v);
      expect(fig().dataset.step).toBe('3');
      expect(fig().dataset.stepId).toBe('call');
    }
  });

  it('hides what is not selected: hidden variants are aria-hidden', async () => {
    await render(<BoundaryFigure />);
    const cell = lane('engine').querySelector('.ml-fig-lanes__code')!;
    expect([...cell.children].map((c) => c.hasAttribute('data-off'))).toEqual([true, false, true]);
    expect([...cell.children].map((c) => c.getAttribute('aria-hidden'))).toEqual(['true', null, 'true']);
  });
});

describe('figure J7: stepping', () => {
  it('shows active and dim lanes with aria-current and the direction on the walls, step by step', async () => {
    await render(<BoundaryFigure />);
    const states = (): string => ['page', 'glue', 'engine'].map((id) => `${lane(id).dataset.state ?? '-'}${lane(id).getAttribute('aria-current') === 'step' ? '*' : ''}`).join(' ');
    const dirs = (): string => `${wall('msg').dataset.dir ?? '-'}/${wall('call').dataset.dir ?? '-'}`;
    const seen: string[] = [];
    for (let s = 0; s <= 6; s++) {
      await goTo(s);
      seen.push(`${states()} | ${dirs()}`);
    }
    expect(seen).toEqual([
      '- - - | -/-',
      'active* dim dim | in/-',
      'dim active* dim | -/-',
      'dim active* active* | -/in',
      'dim dim active* | -/-',
      'dim active* active* | -/out',
      'active* dim dim | out/-',
    ]);
  });

  it('counts messages, calls and the worker thread, for TeaVM and for C++', async () => {
    await render(<BoundaryFigure />);
    const row = async (v: Variant): Promise<string[]> => {
      await chooseVariant(v);
      const out: string[] = [];
      for (let s = 0; s <= 6; s++) {
        await goTo(s);
        out.push(`${text(q('[data-r="msgs"]'))},${text(q('[data-r="calls"]'))},${visible(q('[data-r="worker"]'))}`);
      }
      return out;
    };
    expect(await row('wasmgc')).toEqual(['0 / 2,0,idle', '1 / 2,0,idle', '1 / 2,0,busy', '1 / 2,1,busy', '1 / 2,1,busy', '1 / 2,1,busy', '2 / 2,1,idle']);
    expect(await row('cpp')).toEqual(['0 / 2,0,idle', '1 / 2,0,idle', '1 / 2,0,busy', '1 / 2,N + 4,busy', '1 / 2,N + 4,busy', '1 / 2,N + 10,busy', '2 / 2,N + 10,idle']);
  });

  it('gives the pill of each lane the thread state of the step: the page is free while the worker is busy, the glue waits inside the call', async () => {
    await render(<BoundaryFigure />);
    const pill = (id: string): string => visible(q(`[data-lane="${id}"] [data-pill]`));
    await goTo(4);
    expect([pill('page'), pill('glue'), pill('engine')]).toEqual(['free', 'in the call', 'busy']);
    await goTo(6);
    expect([pill('page'), pill('glue'), pill('engine')]).toEqual(['idle', 'idle', 'idle']);
  });

  it('narrates with the live line and holds all seven narrations of the variant as ghosts, step label included', async () => {
    await render(<BoundaryFigure />);
    const live = (): HTMLElement => q('.ml-fig__status-line[role="status"]');
    expect(text(live())).toContain(texts.en['fig.boundary.narr.idle']);
    expect(text(live())).toContain('Step 0 of 6');
    const ghosts = all('.ml-fig__status-line--ghost');
    expect(ghosts).toHaveLength(7);
    expect(ghosts.every((g) => g.getAttribute('aria-hidden') === 'true')).toBe(true);
    await goTo(3);
    expect(text(live())).toContain(texts.en['fig.boundary.narr.call.wasmgc']);
    await chooseVariant('cpp');
    expect(text(live())).toContain(texts.en['fig.boundary.narr.call.cpp']);
    await goTo(5);
    expect(text(live())).toContain(texts.en['fig.boundary.narr.return.cpp']);
    await chooseVariant('js');
    expect(text(live())).toContain(texts.en['fig.boundary.narr.return.teavm']);
    expect(all('.ml-fig__status-line--ghost').map(text).join(' ')).toContain(texts.en['fig.boundary.narr.call.js']);
  });

  it('shows the payload and its tag, and holds the code of all 7 steps and 3 builds (21 blocks) in one cell', async () => {
    await render(<BoundaryFigure />);
    const card = (): HTMLElement => q('[data-testid="boundary-payload"]');
    const tag = (): string => text(card().querySelector('.ml-fig-j7__how > :not([data-off])'));
    expect(all('[data-pay] > *')).toHaveLength(21);
    expect(all('[data-pay] > :not([data-off])')).toHaveLength(1);
    expect(card().dataset.how).toBe('none');
    expect(visible(q('[data-pay]'))).toBe(texts.en['fig.boundary.pay.note.idle']);
    await goTo(1);
    expect(card().dataset.how).toBe('clone');
    expect(tag()).toContain(texts.en['fig.boundary.how.clone']);
    expect(visible(q('[data-pay]'))).toContain("worker.postMessage({ id, type: 'choose'");
    await goTo(3);
    expect(card().dataset.how).toBe('convert');
    expect(visible(q('[data-pay]'))).toContain(`// ${texts.en['fig.boundary.pay.note.args']}`);
    await chooseVariant('js');
    expect(card().dataset.how).toBe('direct');
    await chooseVariant('cpp');
    expect(card().dataset.how).toBe('numbers');
    expect(visible(q('[data-pay]'))).toContain('_shw_play(m1) … _shw_play(mN)');
    await goTo(4);
    expect(visible(q('[data-pay]'))).toContain(`// ${texts.en['fig.boundary.pay.note.cpp']}`);
    expect(visible(q('[data-pay]'))).toContain('int shw_choose(int player_id)');
  });
});

describe('figure J7: the table', () => {
  it('marks the selected column (a class of its own and a hidden word) and follows the switch', async () => {
    await render(<BoundaryFigure />);
    const sel = (): string[] => all('thead th[data-sel]').map((th) => th.dataset.col!);
    expect(sel()).toEqual(['wasmgc']);
    expect(text(q('thead th[data-sel] .ml-visually-hidden'))).toBe(texts.en['fig.boundary.cmp.selected']);
    await chooseVariant('cpp');
    expect(sel()).toEqual(['cpp']);
    expect(all('tbody td[data-sel]').every((td) => td.dataset.col === 'cpp')).toBe(true);
    expect(all('thead .ml-visually-hidden')).toHaveLength(1);
  });

  it('marks the row of the step: call at 3, thread at 4, types at 5, none elsewhere', async () => {
    await render(<BoundaryFigure />);
    const seen: string[][] = [];
    for (let s = 0; s <= 6; s++) {
      await goTo(s);
      seen.push(all('tr[data-on]').map((r) => r.dataset.row!));
    }
    expect(seen).toEqual([[], [], [], ['call'], ['thread'], ['types'], []]);
  });

  it('has five rows, one cell per build, and the thread row as one cell for all three', async () => {
    await render(<BoundaryFigure />);
    expect(all('tbody tr').map((r) => r.dataset.row)).toEqual(['call', 'types', 'bulk', 'memory', 'thread']);
    expect(q('tr[data-row="bulk"]').querySelectorAll('td')).toHaveLength(3);
    expect(q('tr[data-row="thread"] td[data-span="wide"]').getAttribute('colspan')).toBe('3');
    expect(text(q('tr[data-row="thread"] td[data-span="wide"]'))).toBe(texts.en['fig.boundary.cell.thread.all']);
  });

  it('lists the three notes with an icon each, and never says that WebAssembly is immutable', async () => {
    await render(<BoundaryFigure />);
    expect(all('.ml-fig-j7__also li').map((li) => li.dataset.also)).toEqual(['transfer', 'cancel', 'batch']);
    expect(text(q('.ml-fig-j7__also li[data-also="batch"]'))).toContain('4,096');
    expect(text(fig()).toLowerCase()).not.toMatch(/immutable|unveränderlich/);
  });
});

describe('figure J7: keyboard', () => {
  it('Right and Left step forward and back, Home goes to step 1, End to step 6', async () => {
    await render(<BoundaryFigure />);
    await key('ArrowRight');
    await key('ArrowRight');
    expect(fig().dataset.step).toBe('2');
    await key('ArrowLeft');
    expect(fig().dataset.step).toBe('1');
    await key('End');
    expect(fig().dataset.step).toBe('6');
    await key('Home');
    expect(fig().dataset.step).toBe('1');
  });

  it('reaches the variant switch first, then the stepper buttons, then the source chips', async () => {
    await render(<BoundaryFigure />);
    const order = all('input[type="radio"], .ml-fig-stepper button, a[href]');
    const kinds = order.map((e) => (e.matches('input') ? 'radio' : e.matches('button') ? 'button' : 'link'));
    const n = stepButtons().length;
    expect(kinds.slice(0, 3)).toEqual(['radio', 'radio', 'radio']);
    expect(kinds.slice(3, 3 + n)).toEqual(Array(n).fill('button'));
    expect(kinds.slice(3 + n).length).toBeGreaterThan(0);
    expect(kinds.slice(3 + n).every((k) => k === 'link')).toBe(true);
  });
});

describe('figure J7: German and the topic page', () => {
  it('shows the German texts for the title, the narration, the walls and the table', async () => {
    i18n.setLanguage('de');
    await render(<BoundaryFigure />);
    expect(text(q('.ml-fig__title'))).toBe(texts.de['fig.boundary.title']);
    expect(text(q('.ml-fig__status-line[role="status"]'))).toContain(texts.de['fig.boundary.narr.idle']);
    expect(text(wall('msg').querySelector('.ml-fig-lanes__wname > .ml-fig-stablebold > :first-child'))).toBe(texts.de['fig.boundary.wall.msg']);
    expect(text(q('thead th[data-sel]'))).toContain(texts.de['fig.boundary.variant.wasmgc']);
    expect(text(q('.ml-fig-j7__cmp caption'))).toBe(texts.de['fig.boundary.cmp.caption']);
  });

  it('stands in the section teavm of the topic page java-web, before the snippet e2', async () => {
    await render(<JavaWebPage topic="java-web" section={undefined} onJumpSection={() => undefined} />);
    const figure = q('figure#fig-boundary');
    const section = figure.parentElement!.closest('section')!;
    expect(section.querySelector('figure#fig-boundary')).toBe(figure);
    const figures = [...host.querySelectorAll('figure')];
    expect(figures.indexOf(figure)).toBeLessThan(figures.length - 1);
  });
});
