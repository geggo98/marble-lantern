// @vitest-environment jsdom
// Figure V14 in jsdom: the state machine of the staircase, the sliders of the Elo panel, the tables and marks of the other panels, the
// texts from the catalog in both languages. Layout (compact or wide) has no meaning here: jsdom measures no width, so the wide layout shows.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { i18n } from '../../i18n/i18n';
import { EloFigure, FutilityFigure, StoryFigure, Sweep1Figure, SweepsFigure } from './tuning';
import { tuningCatalog } from './tuning-catalog';
import { eloFromScore, eloPoint } from './tuning-model';

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
  i18n.setLanguage('en');
});

const render = async (node: React.JSX.Element): Promise<void> => {
  await act(async () => root.render(node));
};
const all = <T extends Element = HTMLElement>(s: string): T[] => [...host.querySelectorAll<T>(s)];
const one = <T extends Element = HTMLElement>(s: string): T => {
  const el = host.querySelector<T>(s);
  if (!el) throw new Error(`nothing matches ${s}`);
  return el;
};
const click = async (el: Element): Promise<void> => {
  await act(async () => (el as HTMLElement).click());
};
/** Sets a range input the way a user's key press does: through the native value setter, then an input event. */
const slide = async (input: HTMLInputElement, value: number): Promise<void> => {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, String(value));
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
};
const en = (key: Parameters<typeof tuningCatalog.lookup>[1], values?: Record<string, string | number>): string => tuningCatalog.lookup('en', key, values);
const de = (key: Parameters<typeof tuningCatalog.lookup>[1], values?: Record<string, string | number>): string => tuningCatalog.lookup('de', key, values);

describe('StoryFigure: the staircase', () => {
  it('lists the seven levels as buttons in order, each with its level number, name and example from the catalog', async () => {
    await render(<StoryFigure />);
    const rows = all('.ml-fig-story__row');
    expect(rows.map((r) => r.getAttribute('data-level'))).toEqual(['decisions', 'hand-tuning', 'regression', 'grids', 'sweeps', 'recipe-sweep', 'pre-registered']);
    expect(rows[1]?.textContent).toContain('Level 1');
    expect(rows[1]?.textContent).toContain('Tuned by hand');
    expect(rows[1]?.textContent).toContain('Four stage-4 weights, tuned in the arena');
    expect(one('ol').getAttribute('aria-label')).toBe(en('fig.story.staircase.label'));
  });

  it('selects one level at a time; the narration line and the detail follow the selection', async () => {
    await render(<StoryFigure />);
    const pressed = (): string[] => all('.ml-fig-story__row[aria-pressed="true"]').map((r) => r.getAttribute('data-level') ?? '');
    expect(pressed()).toEqual(['hand-tuning']);
    expect(one('[role="status"]').textContent).toBe(en('fig.story.level.hand-tuning.effect', { elo: '+306', ci: '41' }));
    await click(one('[data-level="regression"]'));
    expect(pressed()).toEqual(['regression']);
    expect(one('[role="status"]').textContent).toBe('+552 Elo, plus or minus 89, against the plain distance evaluation at equal nodes.');
    expect(one('.ml-fig__detail').getAttribute('data-level')).toBe('regression');
    expect(one('.ml-fig__detail a').getAttribute('href')).toBe('#/about/tuning/regression');
  });

  it('shows level 0 with the tag "not measured", no dot, and no link to an experiment', async () => {
    await render(<StoryFigure />);
    await click(one('[data-level="decisions"]'));
    expect(one('[data-level="decisions"] .ml-tag').textContent).toBe(en('fig.story.unmeasured'));
    expect(all('[data-level="decisions"] circle')).toHaveLength(0);
    expect(one('[role="status"]').textContent).toBe(en('fig.story.level.decisions.effect'));
    expect(all('.ml-fig__detail a')).toHaveLength(0);
  });

  it('draws every measured level with a dot at its Elo and a whisker, named by its effect sentence', async () => {
    await render(<StoryFigure />);
    const svgs = all('.ml-fig-story__whisker');
    expect(svgs.map((s) => s.getAttribute('data-elo'))).toEqual(['306', '552', '139', '338', '62', '138']);
    for (const s of svgs) {
      expect(s.getAttribute('role')).toBe('img');
      expect(s.querySelectorAll('circle')).toHaveLength(1);
      expect(s.querySelectorAll('.ml-fig-plot__whisker')).toHaveLength(1);
    }
    expect(svgs[4]?.getAttribute('aria-label')).toBe('+62 Elo, plus or minus 19, against the start network on 400 fresh pairs.');
    // dots stand left to right in the order of their Elo on the one shared axis
    const x = svgs.map((s) => Number(s.querySelector('circle')?.getAttribute('cx')));
    expect(x[1]).toBeGreaterThan(x[0] ?? 0); // 552 right of 306
    expect(x[4]).toBeLessThan(x[2] ?? 0); // 62 left of 139
  });

  it('hides level 0 under "Measured only", keeps the selection valid, and brings it back under "All levels"', async () => {
    await render(<StoryFigure />);
    await click(one('[data-level="decisions"]'));
    const measured = all<HTMLInputElement>('input[type="radio"]')[1];
    await click(measured as HTMLElement);
    expect(all('.ml-fig-story__row')).toHaveLength(6);
    expect(all('[data-level="decisions"]')).toHaveLength(0);
    expect(all('.ml-fig-story__row[aria-pressed="true"]')).toHaveLength(1); // falls back to the first shown level
    await click(all<HTMLInputElement>('input[type="radio"]')[0] as HTMLElement);
    expect(all('.ml-fig-story__row')).toHaveLength(7);
  });

  it('has the figure label, a data chip that names the file, and source chips with real paths', async () => {
    await render(<StoryFigure />);
    expect(one('.ml-fig__label').textContent).toBe('Fig. 5.1');
    expect(one('.ml-fig-chip--data').textContent).toContain('web/src/about/data/tuning-ladder.json');
    expect(all('.ml-fig-chip:not(.ml-fig-chip--data)').map((c) => c.textContent?.trim())).toEqual([
      'Source file: search/src/main/java/sternhalma/search/Elo.java',
      'Source file: search/src/main/java/sternhalma/search/Arena.java',
    ]);
  });

  it('writes every text in German after a language switch, with no loading state', async () => {
    await render(<StoryFigure />);
    await act(async () => i18n.setLanguage('de'));
    expect(one('.ml-fig__title').textContent).toBe(de('fig.story.title'));
    expect(one('[role="status"]').textContent).toBe(de('fig.story.level.hand-tuning.effect', { elo: '+306', ci: '41' }));
    expect(one('.ml-fig__label').textContent).toBe('Abb. 5.1');
  });
});

describe('EloFigure: the Elo curve panel', () => {
  const sliders = (): HTMLInputElement[] => all<HTMLInputElement>('input[type="range"]');

  it('starts at score 0.64 over 200 pairs and reads +100 Elo, plus or minus 29', async () => {
    await render(<EloFigure />);
    expect(sliders().map((s) => s.value)).toEqual(['0.64', '200']);
    const cells = all('.ml-fig-readout__value').map((c) => c.textContent);
    expect(cells).toEqual(['+100', '± 29']);
  });

  it('follows the score slider: at 0.9 the readout is +382 (400 * log10(9) = 381.697), the cursor moves, and the spoken value says so', async () => {
    await render(<EloFigure />);
    const before = one('.ml-fig-plot__cursor').getAttribute('x1');
    await slide(sliders()[0] as HTMLInputElement, 0.9);
    expect(all('.ml-fig-readout__value')[0]?.textContent).toBe('+382');
    expect(one('.ml-fig-plot__cursor').getAttribute('x1')).not.toBe(before);
    const ci = Math.round(eloPoint(0.9, 200).ci);
    expect(sliders()[0]?.getAttribute('aria-valuetext')).toBe(en('fig.story.panel.elo.slider.valuetext', { score: '0.9', pairs: 200, elo: '+382', ci }));
    expect(all('.ml-fig-readout__value')[1]?.textContent).toBe(`± ${ci}`);
  });

  it('narrows the interval when the pairs grow', async () => {
    await render(<EloFigure />);
    const width = (): number => Number((all('.ml-fig-readout__value')[1]?.textContent ?? '').replace(/\D/g, ''));
    const at200 = width();
    await slide(sliders()[1] as HTMLInputElement, 800);
    expect(width()).toBeLessThan(at200);
  });

  it('draws the curve through the clamp: the last point of the line is at 1199.826 Elo, and the lines of loop run 4 stand at 0.82 and 0.91', async () => {
    await render(<EloFigure />);
    expect(one('[data-point-elo]').getAttribute('data-point-elo')).toBe(eloFromScore(0.64).toFixed(3));
    expect(one('[data-saturation]').getAttribute('data-saturation')).toBe('0.82,0.91');
    expect(all('svg.ml-fig-plot .ml-fig-plot__ref')).toHaveLength(3); // zero line and the two saturation lines
    const d = one('.ml-fig-plot__line--1').getAttribute('d') ?? '';
    expect(d.match(/L/g)).toHaveLength(119);
  });

  it('names the plot by title and description from the catalog, without markup characters', async () => {
    await render(<EloFigure />);
    const svg = one('svg.ml-fig-plot');
    expect(svg.querySelector('title')?.textContent).toBe(en('fig.story.panel.elo.title'));
    const desc = svg.querySelector('desc')?.textContent ?? '';
    expect(desc).toContain('a score of 0.64 gives about +100 Elo');
    expect(desc).not.toMatch(/[{}`]/);
  });
});

describe('FutilityFigure: the grid table', () => {
  it('is a table with a caption, five column headers and 14 rows', async () => {
    await render(<FutilityFigure />);
    expect(all('thead th')).toHaveLength(5);
    expect(all('tbody tr')).toHaveLength(14);
    expect(one('caption').textContent).toContain('14 margin settings');
  });

  it('marks exactly the chosen row with a tag and shows its two Elo values', async () => {
    await render(<FutilityFigure />);
    const chosen = all('tr[data-chosen="true"]');
    expect(chosen).toHaveLength(1);
    expect(chosen[0]?.querySelector('.ml-tag')?.textContent).toBe(en('fig.story.panel.futility.chosen'));
    expect(chosen[0]?.textContent).toContain('+177 ± 36');
    expect(chosen[0]?.textContent).toContain('+189 ± 35');
  });

  it('says "not played" for the 10 empty cells and "off" for the 4 settings without fut2', async () => {
    await render(<FutilityFigure />);
    expect(all('.ml-fig-story__cell--empty')).toHaveLength(10);
    expect(all('td').filter((c) => c.textContent === 'off')).toHaveLength(4);
  });

  it('gives the biggest disc to the highest Elo (+230) and the smallest to the lowest (+79)', async () => {
    await render(<FutilityFigure />);
    const radius = (elo: number): number => Number(one(`td[data-elo="${elo}"] circle`).getAttribute('r'));
    expect(radius(230)).toBeGreaterThan(radius(79));
    expect(one('td[data-elo="230"]').getAttribute('data-level')).toBe('5');
    expect(one('td[data-elo="79"]').getAttribute('data-level')).toBe('1');
  });
});

describe('SweepsFigure', () => {
  it('has three small plots, each with the dots of the docs and the best one labelled', async () => {
    await render(<SweepsFigure />);
    const plots = all('[data-sweep]');
    expect(plots.map((p) => p.getAttribute('data-sweep'))).toEqual(['c', 'batch', 'ptemp']);
    expect(plots.map((p) => p.querySelectorAll('circle.ml-fig-plot__pt--1').length)).toEqual([5, 4, 4]);
    expect(plots.map((p) => p.querySelector('.ml-fig-plot__best')?.textContent)).toEqual(['+338', '+542', '+338']);
    for (const p of plots) expect(p.querySelectorAll('.ml-fig-plot__whisker').length).toBeGreaterThan(0);
  });
});

describe('Sweep1Figure', () => {
  it('draws BASE and A to H as first-pass dots, the two confirmations as squares, and a line at 0.5', async () => {
    await render(<Sweep1Figure />);
    expect(all('.ml-fig-plot__catlabel').map((t) => t.textContent)).toEqual(['BASE', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H']);
    expect(all('svg.ml-fig-plot circle.ml-fig-plot__pt--1')).toHaveLength(9);
    expect(all('rect.ml-fig-plot__pt--2').filter((r) => r.closest('svg.ml-fig-plot') !== null)).toHaveLength(2);
    expect(all('svg.ml-fig-plot .ml-fig-plot__ref')).toHaveLength(1);
  });

  it('puts the confirmation square of F next to its first-pass dot: right of F, left of G (the same category band)', async () => {
    await render(<Sweep1Figure />);
    const dots = all('svg.ml-fig-plot circle.ml-fig-plot__pt--1').map((c) => Number(c.getAttribute('cx')));
    const squares = all('svg.ml-fig-plot rect.ml-fig-plot__pt--2').map((r) => Number(r.getAttribute('x')) + 4);
    const [f, g] = [dots[6] ?? 0, dots[7] ?? 0];
    expect(squares[0]).toBeGreaterThan(f);
    expect(squares[0]).toBeLessThan(g);
  });

  it('labels the confirmation of the winner F with its score 0.5887 and marks F in the variant list', async () => {
    await render(<Sweep1Figure />);
    expect(one('.ml-fig-plot__best').textContent).toBe('0.5887');
    expect(one('[data-winner="true"]').getAttribute('data-variant')).toBe('F');
    expect(all('[data-variant]')).toHaveLength(9);
  });

  it('writes the decimals with a comma in German', async () => {
    await act(async () => i18n.setLanguage('de'));
    await render(<Sweep1Figure />);
    expect(one('.ml-fig-plot__best').textContent).toBe('0,5887');
  });
});
