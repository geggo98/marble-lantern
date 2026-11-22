// @vitest-environment jsdom
// Figure J2 (plain Java) in jsdom: the three views, the counts and the walk of seven states bound to the data, the narration with its ghosts, the
// controls (slider, stepper, keys), the accessible names, both languages and the registration on the page. Layout and colour are in about-javaplain.spec.ts.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import JavaPage from '../topics/java';
import { JavaPlainFigure } from './JavaPlainFigure';
import { javaCatalog } from './java-catalog';
import type { Catalog } from './common';
import { LAST, N } from './java-plain-model';
import type { PlainView } from './java-plain-model';

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
  vi.useRealTimers();
  i18n.setLanguage('en');
});

const render = async (view: PlainView = 'move', pick = 1): Promise<void> => {
  await act(async () => root.render(<JavaPlainFigure catalog={catalog} number={[6, 2]} initialView={view} initialPick={pick} />));
};
const q = <T extends Element = HTMLElement>(selector: string): T => {
  const el = host.querySelector<T>(selector);
  if (!el) throw new Error(`nothing matches ${selector}`);
  return el;
};
const all = <T extends Element = HTMLElement>(selector: string): T[] => Array.from(host.querySelectorAll<T>(selector));
const text = (el: Element | null | undefined): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const fig = (): HTMLElement => q('#fig-javaplain');
const lane = (name: 'objects' | 'primitives'): HTMLElement => q(`[data-lane="${name}"]`);
const value = (laneName: 'objects' | 'primitives', key: string): string => text(lane(laneName).querySelector(`[data-value="fig.javaplain.read.${key}"]`));
const click = async (el: Element): Promise<void> => {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
};
const choose = async (label: string): Promise<void> => {
  const radio = all<HTMLInputElement>('input[type="radio"]').find((r) => text(r.closest('label')) === label);
  if (!radio) throw new Error(`no option ${label}`);
  await click(radio);
};
const buttons = (): HTMLButtonElement[] => all<HTMLButtonElement>('.ml-fig-stepper button');
const button = (name: string): HTMLButtonElement => {
  const b = all<HTMLButtonElement>('.ml-fig-stepper button').find((x) => x.getAttribute('aria-label') === name);
  if (!b) throw new Error(`no button ${name}`);
  return b;
};
const forward = (): Promise<void> => click(buttons()[buttons().length - 1]!);
const goTo = async (step: number): Promise<void> => {
  await click(buttons()[0]!);
  for (let i = 0; i < step; i++) await forward();
};
const key = async (target: Element, k: string): Promise<void> => {
  await act(async () => {
    target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
  });
};
const setPick = async (k: number): Promise<void> => {
  const slider = q<HTMLInputElement>('input[type="range"]');
  await act(async () => {
    const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    set.call(slider, String(k));
    slider.dispatchEvent(new Event('input', { bubbles: true }));
  });
};
const narr = (): string => text(q('[data-narr]').lastElementChild);

describe('figure J2: frame', () => {
  it('is Fig. 6.2 with the title, three views, two lanes, a legend and two measured tiles', async () => {
    await render();
    expect(text(q('.ml-fig__label'))).toBe('Fig. 6.2');
    expect(text(q('.ml-fig__title'))).toBe('The same code with objects and with primitives');
    expect(all('input[type="radio"]').map((r) => text(r.closest('label')))).toEqual(['Move', 'Undo data', 'Evaluation']);
    expect(all('section[data-lane]').map((s) => s.getAttribute('data-lane'))).toEqual(['objects', 'primitives']);
    expect(all('.ml-fig-legend__item')).toHaveLength(6);
    expect(all('.ml-fig-jp__measured .ml-fig-readout__cell')).toHaveLength(2);
    expect(fig().dataset).toMatchObject({ view: 'move', step: '0', pick: '1' });
  });

  it('names each lane by its title and its tag', async () => {
    await render();
    const names = all('section[data-lane]').map((s) => {
      const ids = s.getAttribute('aria-labelledby')!.split(' ');
      return ids.map((id) => text(document.getElementById(id))).join(' ');
    });
    expect(names).toEqual(['With objects not in the code', 'With primitives this project']);
    expect(lane('primitives').className).toContain('ml-fig-cmp__lane--ours');
    expect(lane('objects').className).not.toContain('--ours');
  });

  it('shows source chips for the files the numbers come from and the data chip', async () => {
    await render();
    const chips = all('.ml-fig__foot a').map((a) => text(a));
    expect(chips.some((c) => c.includes('Move.java'))).toBe(true);
    expect(chips.some((c) => c.includes('IncrementalLinear.java'))).toBe(true);
    expect(chips.some((c) => c.includes('java-plain.json'))).toBe(true);
  });
});

describe('figure J2: view move', () => {
  it('counts 68 allocations, 1,896 bytes and at least 15 cache lines for objects; 0, 284 and 3 for primitives', async () => {
    await render();
    expect([value('objects', 'alloc'), value('objects', 'bytes'), value('objects', 'lines')]).toEqual(['68', '1,896', '≥ 15']);
    expect([value('primitives', 'alloc'), value('primitives', 'bytes'), value('primitives', 'lines')]).toEqual(['0', '284', '≥ 3']);
  });

  it('shows the first move as 4 + 24 = 28 B and as an int', async () => {
    await render();
    expect(text(lane('objects').querySelector('.ml-fig-jp__eq'))).toContain('4 + 24 = 28 B');
    expect(text(lane('primitives').querySelector('.ml-fig-jp__eq--code'))).toBe('5 | (9 << 8) = 2309 = 0x0905');
    expect(text(Array.from(lane('primitives').querySelectorAll('.ml-fig-jp__eq'))[1])).toBe('One int: 4 B');
  });

  it('names both memory maps with the numbers of the readout', async () => {
    await render();
    const labels = all('svg.ml-fig-mem').map((s) => s.getAttribute('aria-label'));
    expect(labels[0]).toContain('67 references and 67 objects, 1,896 bytes, at least 15 cache lines');
    expect(labels[1]).toContain('67 moves in 284 bytes, at least 3 cache lines');
    expect(all('svg.ml-fig-mem').map((s) => s.getAttribute('role'))).toEqual(['img', 'img']);
  });

  it('draws 15 rows for objects and 4 for primitives (3 cache lines and the dots), one selected word for the int and seven for the object', async () => {
    await render();
    const [a, b] = all('svg.ml-fig-mem');
    expect(a!.getAttribute('data-rows')).toBe('15');
    expect(b!.getAttribute('data-rows')).toBe('4');
    expect(b!.querySelectorAll('.is-sel')).toHaveLength(1);
    expect(a!.querySelectorAll('.is-sel')).toHaveLength(1 + 1 + 6); // reference square and its dot, six object words
    expect(a!.querySelector('.hop')).not.toBeNull();
    expect(b!.querySelector('.hop')).toBeNull();
    expect(b!.querySelectorAll('.more')).toHaveLength(3);
  });

  it('follows the slider: the move, the readout of the slider, the value text and the selected words', async () => {
    await render();
    await setPick(35);
    const slider = q<HTMLInputElement>('input[type="range"]');
    expect(slider.getAttribute('aria-valuetext')).toBe('move 35 of 67, H5»N3');
    expect(text(q('.ml-fig-ctl__out'))).toBe('35 / 67 · H5»N3');
    expect(text(lane('primitives').querySelector('.ml-fig-jp__eq--code'))).toContain('= 28978 = 0x7132');
    expect(fig().dataset.pick).toBe('35');
    expect(narr()).toBe('Move 35 is H5»N3. As an int it is one word in a buffer that already exists. As an object it is a reference plus an object with a header.');
  });

  it('has 1 to 67 on the slider and no step label in the narration', async () => {
    await render();
    const slider = q<HTMLInputElement>('input[type="range"]');
    expect([slider.min, slider.max]).toEqual(['1', String(N)]);
    expect(q('[data-narr]').querySelector('.ml-fig__step')).toBeNull();
  });

  it('keeps the slider out of the live region and the stepper inert', async () => {
    await render();
    expect(q('[data-narr]').getAttribute('aria-live')).toBe('off');
    expect(q('[data-ctl="step"]').hasAttribute('inert')).toBe(true);
    expect(q('[data-ctl="pick"]').hasAttribute('inert')).toBe(false);
  });

  it('shows one measured tile and one "not measured" tile', async () => {
    await render();
    const [engine, own] = all('.ml-fig-jp__measured .ml-fig-readout__cell');
    expect(engine!.getAttribute('data-kind')).toBe('measured');
    expect(text(engine)).toContain('1.6 M');
    expect(own!.getAttribute('data-kind')).toBe('none');
    expect(text(own)).toContain('not measured');
    expect(text(own)).not.toMatch(/\d %/);
  });

  it('has the legend of the memory map', async () => {
    await render();
    expect(all('.ml-fig-legend__item').map((e) => text(e))).toEqual(['Object header', 'Reference', 'Value', 'Padding', 'Free buffer', 'Selected move']);
  });
});

describe('figure J2: view undo', () => {
  const states = (): string[] => all('[data-slot][data-state]').filter((c) => c.getAttribute('data-slot') !== null).slice(0, 3).map((c) => c.getAttribute('data-state')!);
  const rowCells = (name: string): string[] => {
    const row = all('.ml-fig-jp__arr tbody tr').find((r) => text(r.querySelector('th')).startsWith(name))!;
    return Array.from(row.querySelectorAll('td')).map((c) => c.getAttribute('data-state')!);
  };

  it('starts empty at the root, with the pointer on ply 24', async () => {
    await render('undo');
    expect(rowCells('hashes')).toEqual(['empty', 'empty', 'empty']);
    expect(text(q('[data-ply="now"]'))).toBe('24');
    expect(all('.ml-fig-jp__arr thead th').map((t) => text(t))).toEqual(['ply', '24', '25', '26']);
    expect([value('objects', 'alloc'), value('objects', 'bytes.alloc'), value('objects', 'garbage')]).toEqual(['0', '0', '0']);
    expect(narr()).toBe('The position at ply 24. Nothing is saved yet.');
    expect(text(q('[data-narr]').querySelector('.ml-fig__step'))).toBe('Step 1 of 7:');
    expect(text(q('.ml-fig-stepper__pos'))).toContain('1 / 7');
  });

  it('is a table with a caption, column headers and row headers', async () => {
    await render('undo');
    const table = q('table.ml-fig-jp__arr');
    expect(text(table.querySelector('caption'))).toBe('Four arrays, one slot per ply. A make writes the slots of the current ply.');
    expect(all('thead th[scope="col"]')).toHaveLength(4);
    expect(all('tbody th[scope="row"]').map((t) => text(t))).toEqual(['hashes long[]', 'moves int[]', 'movers int[]', 'masksBefore int[]']);
  });

  it('writes slot 0 at the first make: 20 bytes in four arrays, one object of 32 bytes in the other lane', async () => {
    await render('undo');
    await goTo(1);
    expect(rowCells('hashes')).toEqual(['write', 'empty', 'empty']);
    expect(rowCells('moves')).toEqual(['write', 'empty', 'empty']);
    expect(rowCells('movers')).toEqual(['write', 'empty', 'empty']);
    expect(rowCells('masksBefore')).toEqual(['write', 'empty', 'empty']);
    expect(text(all('[data-slot="0"]')[1]!)).toContain('7132');
    expect(all('[data-pile="live"] [data-obj]')).toHaveLength(1);
    expect(text(all('[data-pile="live"] [data-obj]')[0]!)).toBe('Undo #1 · 32 B');
    expect([value('objects', 'alloc'), value('objects', 'bytes.alloc')]).toEqual(['1', '32']);
    expect(value('primitives', 'alloc')).toBe('0');
    expect(narr()).toContain('Make H5»N3 saves four values in slot 24: 20 bytes, no new object.');
  });

  it('reads slot 1 back at the unmake and overwrites it at step 4, where the objects pile up to three', async () => {
    await render('undo');
    await goTo(3);
    expect(rowCells('moves')).toEqual(['live', 'read', 'empty']);
    expect(all('[data-pile="dead"] [data-obj]')).toHaveLength(1);
    expect(text(q('[data-ply="now"]'))).toBe('25');
    expect(narr()).toContain('Unmake reads slot 25 back.');
    await forward();
    expect(rowCells('moves')).toEqual(['live', 'write', 'empty']);
    expect(text(all('[data-slot="1"]')[1]!)).toContain('5c72');
    expect(all('[data-pile="live"] [data-obj]')).toHaveLength(2);
    expect(all('[data-pile="dead"] [data-obj]')).toHaveLength(1);
    expect(value('objects', 'alloc')).toBe('3');
    expect(narr()).toContain('Make N4»L7 overwrites slot 25. The other way creates a third object.');
  });

  it('ends at the root with three garbage objects of 32 bytes and slot 1 no longer used', async () => {
    await render('undo');
    await goTo(LAST);
    expect([value('objects', 'alloc'), value('objects', 'bytes.alloc'), value('objects', 'garbage')]).toEqual(['3', '96', '3']);
    expect(rowCells('hashes')).toEqual(['read', 'stale', 'empty']);
    expect(all('[data-pile="live"] [data-obj]')).toHaveLength(0);
    expect(narr()).toContain('Three makes: no allocation, against three objects (96 bytes)');
    expect(states()).toEqual(['read', 'stale', 'empty']);
  });

  it('puts the state of every cell into its accessible name', async () => {
    await render('undo');
    await goTo(2);
    const cells = all('tbody td');
    expect(text(cells[0]!)).toContain('in use');
    expect(text(cells[1]!)).toContain('written now');
    expect(text(cells[2]!)).toContain('empty');
    await goTo(LAST);
    expect(text(all('tbody td')[1]!)).toContain('no longer used');
    expect(text(all('tbody td')[0]!)).toContain('read now');
  });

  it('shows the legend of the undo table and keeps the not-measured tile', async () => {
    await render('undo');
    expect(all('.ml-fig-legend__item').map((e) => text(e))).toEqual(['Written now', 'Read now', 'In use', 'No longer used']);
    expect(all('.ml-fig-readout__cell[data-kind="none"]')).toHaveLength(1);
  });
});

describe('figure J2: view evaluation', () => {
  const chips = (p: 0 | 1, laneName: 'objects' | 'primitives'): HTMLElement[] =>
    Array.from(lane(laneName).querySelectorAll<HTMLElement>(`[data-side="${p}"] .ml-fig-jp__pc`));
  const frame = (i: number): HTMLElement => q(`[data-frame="${i}"]`);

  it('switches the lane titles and tags to the full against the incremental evaluation', async () => {
    await render('eval');
    expect(text(lane('objects').querySelector('h4'))).toBe('Compute again');
    expect(text(lane('primitives').querySelector('h4'))).toBe('Update per move');
    expect(text(lane('objects').querySelector('.ml-fig-cmp__tag'))).toBe('test reference');
    expect(text(lane('primitives').querySelector('.ml-fig-cmp__tag'))).toBe('used by search');
  });

  it('shows ten chips per side with the distances of the root, and sums 70 and 66 at the root for the incremental lane', async () => {
    await render('eval');
    expect(chips(0, 'primitives')).toHaveLength(10);
    expect(chips(1, 'primitives')).toHaveLength(10);
    expect(chips(0, 'primitives').map((c) => text(c)).join(' ')).toBe('11 10 9 8 7 6 6 5 5 3');
    expect([value('primitives', 'dsum.a'), value('primitives', 'dsum.b'), value('primitives', 'pieces')]).toEqual(['70', '66', '0']);
    expect([value('objects', 'dsum.a'), value('objects', 'dsum.b'), value('objects', 'pieces')]).toEqual(['—', '—', '0']);
    expect(text(lane('primitives').querySelector('.ml-fig-jp__eq'))).toBe('The sums are valid');
    expect(text(lane('objects').querySelector('.ml-fig-jp__eq'))).toBe('Nothing is computed here');
  });

  it('marks the moved piece as changed and writes the update at the first make', async () => {
    await render('eval');
    await goTo(1);
    const changed = chips(0, 'primitives').filter((c) => c.className.includes('is-changed'));
    expect(changed.map((c) => text(c))).toEqual(['0']);
    expect(chips(1, 'primitives').some((c) => c.className.includes('is-changed'))).toBe(false);
    expect(text(lane('primitives').querySelector('.ml-fig-jp__eq'))).toBe('sum[A] += 0 − 6 → 64');
    expect([value('primitives', 'dsum.a'), value('primitives', 'dsum.b'), value('primitives', 'pieces')]).toEqual(['64', '66', '1']);
    expect(chips(0, 'objects').some((c) => c.className.includes('is-read'))).toBe(false);
    expect(narr()).toContain('H5»N3 moves one piece from distance 6 to 0.');
    expect(frame(0).getAttribute('data-state')).toBe('write');
    expect(text(frame(0))).toContain('70 · 66');
  });

  it('reads all 20 pieces at a leaf and shows the two sums there only', async () => {
    await render('eval');
    await goTo(2);
    expect(chips(0, 'objects').concat(chips(1, 'objects')).every((c) => c.className.includes('is-read'))).toBe(true);
    expect([value('objects', 'dsum.a'), value('objects', 'dsum.b'), value('objects', 'pieces')]).toEqual(['64', '62', '20']);
    expect(text(lane('objects').querySelector('.ml-fig-jp__eq'))).toBe('All 20 pieces read: 64 · 62');
    expect(text(lane('primitives').querySelector('.ml-fig-jp__eq'))).toBe('sum[B] += 6 − 10 → 62');
    expect(narr()).toContain('At a leaf the search needs the evaluation.');
    await forward();
    expect(chips(0, 'objects').some((c) => c.className.includes('is-read'))).toBe(false);
    expect(value('objects', 'dsum.a')).toBe('—');
    expect(text(lane('primitives').querySelector('.ml-fig-jp__eq'))).toBe('Frame copied back: 64 · 66');
    expect(narr()).toBe('Unmake copies the saved frame back.');
  });

  it('ends with 40 pieces read against 3 updated and the root sums back', async () => {
    await render('eval');
    await goTo(LAST);
    expect(value('objects', 'pieces')).toBe('40');
    expect(value('primitives', 'pieces')).toBe('3');
    expect([value('primitives', 'dsum.a'), value('primitives', 'dsum.b')]).toEqual(['70', '66']);
    expect(chips(0, 'primitives').map((c) => text(c))).toEqual(chips(0, 'objects').map((c) => text(c)));
    expect(narr()).toBe('Two evaluations: 40 pieces read against 3 pieces updated.');
  });

  it('shows both measured tiles, the second one with +30 %', async () => {
    await render('eval');
    const [engine, own] = all('.ml-fig-jp__measured .ml-fig-readout__cell');
    expect(engine!.getAttribute('data-kind')).toBe('measured');
    expect(own!.getAttribute('data-kind')).toBe('measured');
    expect(text(own)).toContain('+30 %');
    expect(text(engine)).toContain('1.6 M');
    expect(text(own)).not.toContain('1.6');
  });

  it('keeps the three frames in every state', async () => {
    await render('eval');
    for (let s = 0; s <= LAST; s++) {
      await goTo(s);
      expect(all('[data-frame]')).toHaveLength(3);
      expect(all('.ml-fig-jp__side')).toHaveLength(4);
      expect(all('.ml-fig-readout__cell')).toHaveLength(8);
    }
  });
});

describe('figure J2: controls and narration', () => {
  it('switches the view with the segmented control, keeps the step, and swaps the inert control', async () => {
    await render('undo');
    await goTo(4);
    await choose('Evaluation');
    expect(fig().dataset).toMatchObject({ view: 'eval', step: '4' });
    expect(q('[data-ctl="pick"]').hasAttribute('inert')).toBe(true);
    expect(q('[data-ctl="step"]').hasAttribute('inert')).toBe(false);
    expect(narr()).toContain('At a leaf the search needs the evaluation.');
    await choose('Move');
    expect(fig().dataset.view).toBe('move');
    expect(fig().dataset.step).toBe('4');
    await choose('Undo data');
    expect(fig().dataset.step).toBe('4');
  });

  it('steps with the buttons and shows "k / 7"; the bounds use aria-disabled', async () => {
    await render('undo');
    expect(button('Back to the start').getAttribute('aria-disabled')).toBe('true');
    for (let s = 1; s <= LAST; s++) {
      await forward();
      expect(text(q('.ml-fig-stepper__pos'))).toContain(`${s + 1} / 7`);
    }
    expect(button('One step forward').getAttribute('aria-disabled')).toBe('true');
    await forward();
    expect(fig().dataset.step).toBe(String(LAST));
  });

  it('moves with Left and Right, and with End and Home to the first and last state of the walk', async () => {
    await render('undo');
    const group = q('.ml-fig-stepper');
    await key(group, 'ArrowRight');
    await key(group, 'ArrowRight');
    expect(fig().dataset.step).toBe('2');
    await key(group, 'ArrowLeft');
    expect(fig().dataset.step).toBe('1');
    await key(group, 'End');
    expect(fig().dataset.step).toBe(String(LAST));
    await key(group, 'Home');
    expect(fig().dataset.step).toBe('0');
  });

  it('plays one step per 1.2 s, switches the live region off while playing, stops at the last step and starts again from 0', async () => {
    vi.useFakeTimers();
    await render('undo');
    const play = (): HTMLButtonElement => button('Play');
    await click(play());
    expect(q('[data-narr]').getAttribute('aria-live')).toBe('off');
    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    expect(fig().dataset.step).toBe('1');
    await act(async () => {
      vi.advanceTimersByTime(1200 * 10);
    });
    expect(fig().dataset.step).toBe(String(LAST));
    expect(q('[data-narr]').getAttribute('aria-live')).toBe('polite');
    await click(play());
    expect(fig().dataset.step).toBe('0');
  });

  it('stops the playback when the view changes', async () => {
    vi.useFakeTimers();
    await render('undo');
    await click(button('Play'));
    await choose('Evaluation');
    await act(async () => {
      vi.advanceTimersByTime(5000);
    });
    expect(fig().dataset.step).toBe('0');
  });

  it('reserves room for every narration: hidden ghosts in the same grid cell, none of them readable by a screen reader', async () => {
    await render('move');
    const ghosts = all('.ml-fig__status-line--ghost');
    expect(ghosts.length).toBeGreaterThan(14);
    expect(ghosts.every((g) => g.getAttribute('aria-hidden') === 'true')).toBe(true);
    expect(all('.ml-fig__status [role="status"]')).toHaveLength(1);
  });

  it('keeps the same text with the ghosts for every view', async () => {
    for (const v of ['move', 'undo', 'eval'] as const) {
      await render(v);
      expect(all('.ml-fig__status-line--ghost').length).toBeGreaterThan(14);
    }
  });
});

describe('figure J2: German', () => {
  beforeEach(() => i18n.setLanguage('de'));

  it('has German labels, titles and numbers (the decimal comma and the point as thousands separator)', async () => {
    await render();
    expect(text(q('.ml-fig__title'))).toBe(javaCatalog.lookup('de', 'fig.javaplain.title'));
    expect(value('objects', 'bytes')).toBe('1.896');
    expect(text(all('.ml-fig-jp__measured .ml-fig-readout__value')[0]!)).toBe('1,6 M');
    expect(all('input[type="radio"]').map((r) => text(r.closest('label')))).toEqual([
      javaCatalog.lookup('de', 'fig.javaplain.view.move'),
      javaCatalog.lookup('de', 'fig.javaplain.view.undo'),
      javaCatalog.lookup('de', 'fig.javaplain.view.eval'),
    ]);
  });

  it('writes the narration and the cell states in German', async () => {
    await render('undo');
    await goTo(1);
    expect(narr()).toContain('H5»N3');
    expect(narr()).not.toContain('saves four values');
    expect(text(all('[data-slot="0"]')[0]!)).toContain(javaCatalog.lookup('de', 'fig.javaplain.cell.state.write'));
  });
});

describe('figure J2: on the page', () => {
  it('stands in the section plain-java of the topic java, as Fig. 6.2, before the snippets', async () => {
    await act(async () => root.render(<JavaPage topic="java" section={undefined} onJumpSection={() => undefined} />));
    const section = q('section[aria-labelledby="about-java-plain-java"]');
    const f = section.querySelector('#fig-javaplain');
    expect(f).not.toBeNull();
    expect(text(f!.querySelector('.ml-fig__label'))).toBe('Fig. 6.2');
    expect(section.querySelectorAll('figure').length).toBeGreaterThan(1); // the figure and the snippets of the section
    expect(section.querySelector('figure')).toBe(f);
  });
});
