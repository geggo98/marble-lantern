// @vitest-environment jsdom
// Fig. 4.4 in jsdom: default state, row selection and the ring, the basis switch (triangle and front), the disabled switch in the view "both", the
// accessible names, the live region and its ghosts, the detail variants, German, and the numbers of the table against energy.json. Browser layout
// (stable height, targets, text size) is checked by e2e/about-energy.spec.ts.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { i18n } from '../../i18n/i18n';
import { EnergyFigure } from './EnergyFigure';
import { ENERGY_DATA as D, formatHours, formatKwh, groupRows, powerW } from './energy-model';

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

const render = async (node: React.JSX.Element = <EnergyFigure />): Promise<void> => {
  await act(async () => root.render(node));
};
const all = <T extends Element = HTMLElement>(s: string): T[] => Array.from(host.querySelectorAll<T>(s));
const q = <T extends Element = HTMLElement>(s: string): T => {
  const el = host.querySelector<T>(s);
  if (!el) throw new Error(`nothing matches ${s}`);
  return el;
};
const text = (el: Element | null | undefined): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const click = (el: Element): void => {
  act(() => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
};
/** The radio of a segmented switch: group 0 is the view, group 1 the basis. */
const radio = (group: number, index: number): HTMLInputElement => all('.ml-fig-seg')[group]!.querySelectorAll<HTMLInputElement>('input[type="radio"]')[index]!;
const pick = (id: string): void => click(q(`tr[data-row="${id}"] button`));
const figure = (): HTMLElement => q('figure#fig-energy');

describe('default state', () => {
  it('shows the view energy at equal simulations with SMALL generation 9 selected, and the label Fig. 4.4', async () => {
    await render();
    expect(figure().dataset).toMatchObject({ view: 'energy', basis: 'sims', selected: 'small-gen9' });
    expect(radio(0, 0).checked).toBe(true);
    expect(radio(1, 0).checked).toBe(true);
    expect(q('tr[data-row="small-gen9"] button').getAttribute('aria-pressed')).toBe('true');
    expect(all('tr button[aria-pressed="true"]')).toHaveLength(1);
    expect(text(q('.ml-fig__label'))).toBe('Fig. 4.4');
  });

  it('has a table with two groups and eight rows, a caption, and no empty strength cell', async () => {
    await render();
    expect(all('table tbody')).toHaveLength(2);
    expect(all('tbody tr[data-row]')).toHaveLength(8);
    expect(text(q('table caption'))).toContain('5-row networks');
    for (const c of all('td.ml-fig-energy__strength')) expect(text(c), 'strength cell').not.toBe('');
    expect(all('th[scope="rowgroup"]')).toHaveLength(2);
    expect(all('thead th[scope="col"]')).toHaveLength(7);
    expect(all('tbody th[scope="row"]')).toHaveLength(8);
  });

  it('marks the shipped net with a tag and the two CPU estimates with a footnote mark', async () => {
    await render();
    expect(all('.ml-fig-energy__tag')).toHaveLength(1);
    expect(q('tr[data-row="small-gen9"] .ml-fig-energy__tag')).not.toBeNull();
    expect(all('.ml-fig-energy__fn')).toHaveLength(2);
    expect(text(q('.ml-fig-energy__note'))).toContain('not confirmed by a real CPU run');
  });
});

describe('selecting a row', () => {
  it('every table button selects its row, and the ring is drawn once for a row the view draws', async () => {
    await render();
    for (const r of D.rows) {
      pick(r.id);
      expect(figure().dataset['selected']).toBe(r.id);
      expect(q(`tr[data-row="${r.id}"] button`).getAttribute('aria-pressed')).toBe('true');
      const drawn = !r.id.endsWith('steps-gpu');
      expect(all('[data-ring]'), r.id).toHaveLength(drawn ? 1 : 0);
    }
  });

  it('a GPU training-steps row has no ring in a strength view, and the detail says it is part of the run; in the view both it has a ring and the line is hidden', async () => {
    await render();
    pick('small-steps-gpu');
    expect(all('[data-ring]')).toHaveLength(0);
    const line = q('.ml-fig-energy__variant[data-row="small-steps-gpu"] .ml-fig-energy__hidden');
    expect(line.hasAttribute('data-off')).toBe(false);
    expect(text(line)).toContain('S9');
    click(radio(0, 2));
    expect(all('[data-ring]')).toHaveLength(1);
    expect(line.hasAttribute('data-off')).toBe(true);
    expect(line.getAttribute('aria-hidden')).toBe('true');
  });

  it('a click on the hit circle of a point selects the row (the pointer path); the circles are hidden from assistive technology', async () => {
    await render();
    expect(q('.ml-fig-energy__hits').getAttribute('aria-hidden')).toBe('true');
    click(all('.ml-fig-energy__hit')[0]!);
    expect(figure().dataset['selected']).not.toBe('small-gen9');
  });

  it('the selection survives a switch of the view and of the basis', async () => {
    await render();
    pick('large-gen0');
    click(radio(0, 1));
    click(radio(1, 1));
    click(radio(0, 2));
    expect(figure().dataset['selected']).toBe('large-gen0');
  });

  it('the detail panel has eight variants in one cell and only the selected one is not hidden', async () => {
    await render();
    expect(all('.ml-fig-energy__variant')).toHaveLength(8);
    expect(all('.ml-fig-energy__variant:not([data-off])')).toHaveLength(1);
    expect(q('.ml-fig-energy__variant:not([data-off])').dataset['row']).toBe('small-gen9');
    expect(all('.ml-fig-energy__variant[data-off]').every((v) => v.getAttribute('aria-hidden') === 'true')).toBe(true);
    pick('large-gen10');
    expect(q('.ml-fig-energy__variant:not([data-off])').dataset['row']).toBe('large-gen10');
    expect(text(q('.ml-fig-energy__variant:not([data-off])'))).toContain('+131 ± 24 Elo');
    expect(text(q('.ml-fig-energy__variant:not([data-off])'))).toContain('−173 ± 36 Elo');
    expect(q('section[data-part="detail"]').getAttribute('aria-labelledby')).toBe(q('.ml-fig-energy__variant:not([data-off]) h4').id);
  });
});

describe('the basis switch', () => {
  it('changes the triangle and the front status of LARGE generation 10 and LARGE generation 0', async () => {
    await render();
    expect(q('g[data-match]').dataset['match']).toBe('sims');
    expect(all('svg.ml-fig-plot polygon.ml-fig-energy__tri--up')).toHaveLength(1);
    expect(q('tr[data-row="large-gen10"]').dataset['front']).toBe('front');
    expect(q('tr[data-row="large-gen0"]').dataset['front']).toBe('front');
    click(radio(1, 1));
    expect(q('g[data-match]').dataset['match']).toBe('time');
    expect(all('svg.ml-fig-plot polygon.ml-fig-energy__tri--down')).toHaveLength(1);
    expect(all('svg.ml-fig-plot polygon.ml-fig-energy__tri--up')).toHaveLength(0);
    expect(q('tr[data-row="large-gen10"]').dataset['front']).toBe('dominated');
    // at equal time the cheaper SMALL generation 0 (1,000 simulations) beats LARGE generation 0: -239 ± 135 in the rating file
    expect(q('tr[data-row="large-gen0"]').dataset['front']).toBe('dominated');
    expect(q('tr[data-row="small-gen9"]').dataset['front']).toBe('front');
    expect(q('tr[data-row="small-gen0"]').dataset['front']).toBe('front');
  });

  it('keeps the front column of the table in words for both bases, whichever basis is on', async () => {
    await render();
    const before = all('td.ml-fig-energy__front-cell').map(text);
    click(radio(1, 1));
    expect(all('td.ml-fig-energy__front-cell').map(text)).toEqual(before);
    expect(text(q('tr[data-row="small-gen9"] td.ml-fig-energy__front-cell'))).toBe('yes');
    expect(text(q('tr[data-row="large-gen0"] td.ml-fig-energy__front-cell'))).toBe('only at equal simulations');
    expect(text(q('tr[data-row="large-gen10"] td.ml-fig-energy__front-cell'))).toBe('only at equal simulations');
  });

  it('is disabled in the view both, and the options point to the reason line, which is hidden in the other views', async () => {
    await render();
    const reason = q('.ml-fig-energy__reason');
    expect(reason.getAttribute('aria-hidden')).toBe('true');
    expect(radio(1, 0).disabled).toBe(false);
    click(radio(0, 2));
    expect(radio(1, 0).disabled).toBe(true);
    expect(radio(1, 1).disabled).toBe(true);
    expect(radio(1, 0).getAttribute('aria-describedby')).toBe(reason.id);
    expect(reason.hasAttribute('aria-hidden')).toBe(false);
    expect(text(reason)).toBe('This view shows no strength.');
    expect(all('svg.ml-fig-plot polygon')).toHaveLength(0);
    expect(all('[data-part="front"]')).toHaveLength(0);
  });

  it('the strength views draw the CPU rows as lines and the view both as points with the power band', async () => {
    await render();
    expect(all('[data-bound]').map((g) => g.getAttribute('data-bound'))).toEqual(['small-steps-cpu', 'large-steps-cpu']);
    click(radio(0, 1));
    expect(all('[data-bound]')).toHaveLength(2);
    click(radio(0, 2));
    expect(all('[data-bound]')).toHaveLength(0);
    expect(all('g[data-row]')).toHaveLength(8);
    expect(all('[data-part="power-band"]')).toHaveLength(1);
    expect(text(q('[data-part="power-band"]'))).toBe('36 to 49 W');
  });
});

describe('accessible names and the live region', () => {
  it('has a title and a description of the SVG for each view that point to the table, and aria-details to the table id', async () => {
    await render();
    for (const [i, view] of ['energy', 'time', 'both'].entries()) {
      click(radio(0, i));
      const svg = q('svg.ml-fig-plot');
      expect(svg.getAttribute('role')).toBe('img');
      expect(text(svg.querySelector('title'))).toBe('What the training cost');
      expect(text(svg.querySelector('desc')), view).toContain('All values are in the table below.');
      expect(svg.getAttribute('aria-details')).toBe(q('table').id);
    }
  });

  it('has one live region with five ghosts, and the live text follows the view and the basis', async () => {
    await render();
    expect(all('[role="status"]')).toHaveLength(1);
    expect(all('.ml-fig__status-line--ghost')).toHaveLength(5);
    expect(text(q('[role="status"]'))).toContain('equal simulations: in their head-to-head match LARGE generation 10 beats');
    click(radio(1, 1));
    expect(text(q('[role="status"]'))).toContain('equal time per move: in their head-to-head match LARGE generation 10 loses');
    click(radio(0, 1));
    expect(text(q('[role="status"]'))).toContain('Strength against time at equal time per move');
    click(radio(0, 2));
    expect(text(q('[role="status"]'))).toContain('Energy against time');
  });

  it('does not write into the live region when a row is selected', async () => {
    await render();
    const before = text(q('[role="status"]'));
    pick('large-gen10');
    expect(text(q('[role="status"]'))).toBe(before);
  });

  it('keeps every legend entry in all views and dims (and hides) the ones that do not apply', async () => {
    await render();
    expect(all('.ml-fig-energy__legend .ml-fig-legend__item')).toHaveLength(10);
    const off = (): number => all('.ml-fig-energy__legend [data-off]').length;
    const before = off();
    click(radio(0, 2));
    expect(all('.ml-fig-energy__legend .ml-fig-legend__item')).toHaveLength(10);
    expect(off()).not.toBe(before);
    expect(all('.ml-fig-energy__legend [data-off]').every((e) => e.getAttribute('aria-hidden') === 'true')).toBe(true);
  });
});

describe('numbers', () => {
  it('every cell of the time and energy columns is the value of energy.json in the format of the design (≈ for an estimate)', async () => {
    await render();
    const f = (v: number, d: number): string => new Intl.NumberFormat('en', { minimumFractionDigits: d, maximumFractionDigits: d }).format(v);
    for (const r of [...groupRows(D, 'run'), ...groupRows(D, 'steps')]) {
      const tr = q(`tr[data-row="${r.id}"]`);
      const time = `${f(r.hours, formatHours(r.hours))} h`;
      const energy = `${f(r.kwh, formatKwh(r))} kWh`;
      expect(text(tr.querySelector('td.ml-fig-energy__time')), r.id).toContain(r.status === 'estimate' ? `≈ ${time}` : time);
      expect(text(tr.querySelector('td.ml-fig-energy__energy')), r.id).toContain(r.status === 'estimate' ? `≈ ${energy}` : energy);
      // the strength cell holds the display values of the rating at the basis (equal simulations is on), with thousands separators and no sign
      if (r.strength) expect(text(tr.querySelector('td.ml-fig-energy__strength')), r.id).toBe(`${f(r.strength.sims.displayElo, 0)} ± ${f(r.strength.sims.displayHw95, 0)}`);
      else expect(text(tr.querySelector('td.ml-fig-energy__strength')), r.id).not.toMatch(/\d ± /);
    }
    expect(text(q('tr[data-row="small-steps-cpu"] td.ml-fig-energy__energy'))).toContain('(0.9–1.5)');
    expect(text(q('tr[data-row="small-steps-cpu"] td.ml-fig-energy__time'))).toContain('36 times the time');
    expect(text(q('tr[data-row="large-steps-cpu"] td.ml-fig-energy__energy'))).toContain('52 times the energy');
    expect(text(q('tr[data-row="large-gen0"] td.ml-fig-energy__energy'))).toContain('0.050 kWh');
  });

  it('the strength column follows the basis: SMALL generation 9 is 1,840 ± 130 at 200 simulations and 2,150 ± 170 at equal time; CPU rows have no rating', async () => {
    await render();
    const cell = (id: string): string => text(q(`tr[data-row="${id}"] td.ml-fig-energy__strength`));
    expect([cell('small-gen0'), cell('large-gen0'), cell('small-gen9'), cell('large-gen10')]).toEqual(['1,200 ± 100', '1,320 ± 120', '1,840 ± 130', '1,910 ± 140']);
    click(radio(1, 1));
    expect([cell('small-gen0'), cell('large-gen0'), cell('small-gen9'), cell('large-gen10')]).toEqual(['1,560 ± 150', '1,320 ± 120', '2,150 ± 170', '1,910 ± 140']);
    expect(cell('small-steps-cpu')).toBe('no rating: never played');
    expect(cell('large-steps-cpu')).toBe('no rating: never played');
  });

  it('the detail shows the ratings of both bases with their simulations, the method line and the match lines', async () => {
    await render();
    const detail = (): string => text(q('.ml-fig-energy__variant:not([data-off])'));
    expect(detail()).toContain('At 200 simulations: 1,840 ± 130');
    expect(detail()).toContain('At equal time per move, 1,000 simulations: 2,150 ± 170');
    expect(detail()).toContain('Random-effects fit over 53 matches');
    pick('large-gen10');
    expect(detail()).toContain('At 200 simulations: 1,910 ± 140');
    expect(detail()).not.toContain('At equal time per move'); // the same config at both bases: said once
  });

  it('the Elo axis runs from 1,000 to 2,400 and its label names the scale; the old 0 to 1,200 ticks are gone', async () => {
    await render();
    const labels = all('svg.ml-fig-plot text').map(text);
    for (const tick of ['1,000', '1,200', '1,400', '1,600', '1,800', '2,000', '2,200', '2,400']) expect(labels, tick).toContain(tick);
    expect(labels).not.toContain('0');
    expect(labels.some((l) => l.includes('level 3') && l.includes('= 1000'))).toBe(true);
  });

  it('draws one 95 % bar per complete run, from its rating minus to plus its half-width, and a second one for the match', async () => {
    await render();
    expect(all('svg.ml-fig-plot g[data-row] path.ml-fig-plot__whisker')).toHaveLength(4 + 1);
    expect(all('svg.ml-fig-plot g[data-match] path.ml-fig-plot__whisker')).toHaveLength(1);
  });

  it('the mean power in the detail is kWh per hour of the row', async () => {
    await render();
    for (const r of D.rows) {
      pick(r.id);
      expect(text(q('.ml-fig-energy__variant:not([data-off])'))).toContain(`${Math.round(powerW(r))} W`);
    }
  });

  it('the detail shows days from 24 h on and the energy above idle only where the document has it', async () => {
    await render();
    pick('large-steps-cpu');
    expect(text(q('.ml-fig-energy__variant:not([data-off])'))).toContain('175 h (7.3 days)');
    expect(text(q('.ml-fig-energy__variant:not([data-off])'))).not.toContain('Above idle');
    pick('small-gen9');
    expect(text(q('.ml-fig-energy__variant:not([data-off])'))).toContain('0.119 kWh');
  });
});

describe('German', () => {
  it('uses decimal commas, leaves no key and no { in any text, and keeps the same structure', async () => {
    i18n.setLanguage('de');
    await render();
    const t = text(figure());
    expect(t).not.toContain('{');
    expect(t).not.toMatch(/fig\.energy\./);
    expect(text(q('tr[data-row="small-gen9"] td.ml-fig-energy__energy'))).toContain('0,177 kWh');
    expect(text(q('tr[data-row="small-steps-cpu"] td.ml-fig-energy__energy'))).toContain('≈ 1,2 kWh (0,9–1,5)');
    expect(text(q('tr[data-row="small-steps-gpu"] td.ml-fig-energy__time'))).toContain('≈ 0,71 h');
    expect(text(q('tr[data-row="small-gen9"] td.ml-fig-energy__strength'))).toBe('1.840 ± 130');
    expect(all('.ml-fig-energy__tick, svg.ml-fig-plot text').some((e) => text(e) === '0,01')).toBe(true);
    expect(all('tbody tr[data-row]')).toHaveLength(8);
    expect(all('.ml-fig__status-line--ghost')).toHaveLength(5);
    expect(text(q('table caption'))).toContain('Netze für 5 Reihen');
    click(radio(0, 2));
    expect(text(q('[data-part="power-band"]'))).toBe('36 bis 49 W');
  });

  it('writes a minus sign in the equal-time match line of the detail', async () => {
    i18n.setLanguage('de');
    await render();
    pick('large-gen10');
    expect(text(q('.ml-fig-energy__variant:not([data-off])'))).toContain('−173 ± 36 Elo');
  });
});
