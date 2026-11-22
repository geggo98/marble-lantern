// @vitest-environment jsdom
// Figure J4 (GPU anatomy) in jsdom: the picker, the eight steps and the parts they light up, the detail panel (open, close, Escape, focus, the
// machine changes), the unknown values of the M3 Max and the RTX 5090, the narration and its ghosts in both languages, and the accessible names.
// Layout at 320, 820 and 1280 px is in e2e/about-gpu.spec.ts.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import { kitCatalog } from '../i18n/kit';
import JavaPage from '../topics/java';
import { GpuFigure } from './GpuFigure';
import { javaCatalog } from './java-catalog';
import type { Catalog } from './common';
import { HW_IDS, MACHINES, STEPS, STEP_COUNT, activeParts, partsOf } from './gpu-model';
import type { HwId } from './gpu-model';

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
  vi.stubGlobal('requestAnimationFrame', (cb: () => void) => {
    cb();
    return 0;
  });
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
const mount = (hw?: HwId): Promise<void> => render(<GpuFigure catalog={catalog} number={[6, 4]} {...(hw ? { initialHw: hw } : {})} />);
const q = <T extends Element = HTMLElement>(selector: string): T => {
  const el = host.querySelector<T>(selector);
  if (!el) throw new Error(`nothing matches ${selector}`);
  return el;
};
const all = <T extends Element = HTMLElement>(selector: string): T[] => Array.from(host.querySelectorAll<T>(selector));
const text = (el: Element | null | undefined): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const click = async (el: Element): Promise<void> => {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
};
const choose = async (hw: HwId): Promise<void> => {
  const label = MACHINES[hw].short;
  const radio = all<HTMLInputElement>('input[type="radio"]').find((r) => text(r.closest('label')) === label);
  if (!radio) throw new Error(`no option ${label}`);
  await click(radio);
};
const stepButton = (which: 'forward' | 'back' | 'reset'): HTMLElement => {
  const name = kitCatalog.lookup(i18n.current, `kit.stepper.${which}` as never);
  const b = all('.ml-fig-stepper button').find((x) => x.getAttribute('aria-label') === name);
  if (!b) throw new Error(`no ${which} button`);
  return b;
};
const goTo = async (step: number): Promise<void> => {
  for (let guard = 0; guard < 20 && Number(/^(\d+)/.exec(text(q('.ml-fig-stepper__pos')).replace('Step ', ''))?.[1]) !== step; guard++) {
    await click(stepButton(Number(/(\d+) \//.exec(text(q('.ml-fig-stepper__pos')))?.[1]) < step ? 'forward' : 'back'));
  }
};
const stage = (): HTMLElement => q('.gp-stage');
const partIds = (state: string): string[] => [...new Set(all(`[data-part][data-state="${state}"]`).map((e) => e.getAttribute('data-part')!))].sort();
const narration = (): string => text(q('.ml-fig__status-line:not(.ml-fig__status-line--ghost)'));

describe('figure J4: structure', () => {
  it('starts on the T4 at step 0 with five levels, one picker and eight steps', async () => {
    await mount();
    expect(q('figure').id).toBe('fig-gpu');
    expect(stage().dataset.hw).toBe('t4');
    expect(stage().dataset.step).toBe('idle');
    expect(all('[data-panel]').map((p) => p.getAttribute('data-panel'))).toEqual(['1', '2', '3', '4', '5']);
    expect(all('input[type="radio"]').map((r) => text(r.closest('label')))).toEqual(['M3 Max', 'T4', 'RTX 5090']);
    expect(q<HTMLInputElement>('input[type="radio"]:checked').value).toBe('t4');
    expect(all('.ml-fig-stepper__seg')).toHaveLength(STEP_COUNT);
    expect(text(q('.ml-fig-stepper__pos'))).toContain(`0 / ${STEP_COUNT}`);
    expect(text(q('.ml-fig__label'))).toBe('Fig. 6.4');
  });

  it('is a figure named by its title, with a group named for the levels and a polite live region', async () => {
    await mount();
    const fig = q('figure');
    expect(text(q(`#${fig.getAttribute('aria-labelledby')}`))).toBe(javaCatalog.lookup('en', 'fig.gpu.title'));
    expect(q('.ml-fig-zoom').getAttribute('role')).toBe('group');
    expect(q('.ml-fig-zoom').getAttribute('aria-label')).toBe(javaCatalog.lookup('en', 'fig.gpu.anat.label'));
    expect(all('[role="status"]')).toHaveLength(1);
    for (const p of all('[data-panel]')) {
      expect(p.tagName).toBe('SECTION');
      expect(text(q(`#${p.getAttribute('aria-labelledby')}`)).length).toBeGreaterThan(0);
      expect(q(`#${p.getAttribute('aria-labelledby')}`).tagName).toBe('H4');
    }
  });

  it('makes every part a button with aria-expanded and aria-controls', async () => {
    await mount();
    const parts = all('[data-part]');
    expect(parts.length).toBeGreaterThan(10);
    for (const p of parts) {
      expect(p.tagName).toBe('BUTTON');
      expect(p.getAttribute('aria-expanded')).toBe('false');
      expect(p.getAttribute('aria-controls')).toBeTruthy();
    }
    // every part has an accessible name: the label and, when the part has one, the value
    for (const p of parts) expect(text(p).length).toBeGreaterThan(0);
  });

  it('hides the SVG fields of the parts from the accessibility tree and names the chain picture', async () => {
    await mount();
    for (const svg of all('[data-part] svg')) expect(svg.getAttribute('aria-hidden')).toBe('true');
    const chain = q('svg[role="img"]');
    expect(text(q(`#${chain.getAttribute('aria-labelledby')!.split(' ')[0]}`))).toBe(javaCatalog.lookup('en', 'fig.gpu.grid.svg.title'));
    expect(text(q(`#${chain.getAttribute('aria-labelledby')!.split(' ')[1]}`))).toContain('4 warps of 32 threads');
    expect(q('.gp-code').getAttribute('aria-label')).toBe(javaCatalog.lookup('en', 'fig.gpu.grid.code.label'));
  });

  it('has real tables with a caption and scope on every header cell', async () => {
    await mount();
    for (const table of all('table')) {
      expect(table.querySelector('caption')).not.toBeNull();
      for (const th of Array.from(table.querySelectorAll('th'))) expect(th.getAttribute('scope')).toMatch(/^(col|row)$/);
    }
    expect(all('table')).toHaveLength(2);
    expect(all('.ml-fig-cmp thead th')).toHaveLength(4);
    expect(all('.ml-fig-cmp tbody tr')).toHaveLength(8);
  });
});

describe('figure J4: steps', () => {
  for (const hw of HW_IDS) {
    it(`${hw}: every step lights up the parts of the model, the rest is dim; step 0 draws everything in ink`, async () => {
      await mount(hw);
      expect(partIds('active')).toEqual([]);
      expect(partIds('dim')).toEqual([]);
      for (let k = 1; k <= STEP_COUNT; k++) {
        await click(stepButton('forward'));
        expect(stage().dataset.step, `step ${k}`).toBe(STEPS[k]!.id);
        const expected = [...activeParts(hw, k)].filter((id) => partsOf(hw).has(id)).sort();
        expect(partIds('active'), `${hw} step ${k}`).toEqual(expected);
        expect(new Set([...partIds('active'), ...partIds('dim')]), `${hw} step ${k}: every part is active or dim`).toEqual(new Set(all('[data-part]').map((e) => e.getAttribute('data-part')!)));
      }
    });
  }

  it('marks the current level at steps 1 to 8 and none at step 0', async () => {
    await mount();
    expect(all('[data-cur]')).toHaveLength(0);
    const levels = STEPS.map((s) => s.level);
    for (let k = 1; k <= STEP_COUNT; k++) {
      await click(stepButton('forward'));
      expect(all('[data-panel][data-cur]').map((p) => Number(p.getAttribute('data-panel')))).toEqual([levels[k]]);
    }
  });

  it('marks the rows of the comparison table of each step', async () => {
    await mount();
    await goTo(2);
    expect(all('.ml-fig-cmp tr[data-state="active"]').map((r) => r.getAttribute('data-row'))).toEqual(['membw', 'path']);
    await goTo(3);
    expect(all('.ml-fig-cmp tr[data-state="active"]').map((r) => r.getAttribute('data-row'))).toEqual(['units', 'l2', 'mem']);
    await goTo(7);
    expect(all('.ml-fig-cmp tr[data-state="active"]')).toHaveLength(0);
    await goTo(0);
    expect(all('.ml-fig-cmp tr[data-state="active"]')).toHaveLength(0);
  });

  it('points the pipe to the GPU at step 2 and to the host at step 8, thin on the T4 and thick on the RTX 5090', async () => {
    await mount();
    const pipe = (): SVGRectElement => q<SVGRectElement>('.gp-sys__pipe rect');
    const t4 = Number(pipe().getAttribute('height'));
    await goTo(2);
    expect(q('.gp-sys__pipe').getAttribute('data-state')).toBe('active');
    const headIn = q('.gp-sys__pipe path').getAttribute('d');
    await goTo(8);
    expect(q('.gp-sys__pipe path').getAttribute('d')).not.toBe(headIn);
    await choose('rtx');
    expect(Number(pipe().getAttribute('height'))).toBeGreaterThan(t4);
    await choose('m3');
    expect(all('.gp-sys__pipe')).toHaveLength(0); // no pipe: one chip, one memory
    expect(all('.gp-sys__in')).toHaveLength(1);
  });

  it('keeps the values of the parts when the step changes', async () => {
    await mount();
    const values = (): string[] => all('.ml-fig-part__v').map((e) => text(e));
    const first = values();
    for (let k = 1; k <= STEP_COUNT; k++) {
      await click(stepButton('forward'));
      expect(values(), `step ${k}`).toEqual(first);
    }
  });
});

describe('figure J4: hardware', () => {
  it('switches the machine at once and keeps the step', async () => {
    await mount();
    await goTo(4);
    await choose('m3');
    expect(stage().dataset.hw).toBe('m3');
    expect(stage().dataset.step).toBe('unit');
    expect(all('.ml-fig-cmp th[data-sel], .ml-fig-cmp td[data-sel]')).toHaveLength(1 + 8);
    expect(text(q('.ml-fig-cmp th[data-sel]'))).toBe('M3 Max');
  });

  it('names the levels and parts with the words of the machine', async () => {
    await mount();
    expect(text(q('[data-panel="3"] h4'))).toBe('One SM');
    expect(text(q('[data-panel="4"] h4'))).toBe('One warp');
    await choose('m3');
    expect(text(q('[data-panel="3"] h4'))).toBe('One GPU core');
    expect(text(q('[data-panel="4"] h4'))).toBe('One SIMD-group');
    expect(all('[data-part="sm"]')).toHaveLength(0);
    expect(all('[data-part="core"]')).toHaveLength(1);
    expect(all('[data-part="tgmem"]')).toHaveLength(1);
  });

  it('shows the facts of the data: 170 unit squares on the RTX 5090, 40 on the other machines', async () => {
    await mount();
    expect(all('[data-panel="2"] [data-part] svg rect')).toHaveLength(40);
    await choose('rtx');
    expect(all('[data-panel="2"] [data-part] svg rect')).toHaveLength(170);
    await choose('m3');
    expect(all('[data-panel="2"] [data-part] svg rect')).toHaveLength(40);
  });

  it('never renders a number for a slot whose value is null (M3 Max: caches and registers; RTX 5090: registers and warp limit)', async () => {
    const unknown = javaCatalog.lookup('en', 'fig.gpu.unknown');
    await mount('m3');
    for (const id of ['cache', 'reg']) {
      const part = q(`[data-part="${id}"]`);
      expect(text(part.querySelector('.ml-fig-part__v'))).toBe(unknown);
      expect(part.className).toContain('ml-fig-part--unknown');
    }
    expect(text(q('[data-part="tgmem"] .ml-fig-part__v'))).toBe('32 KB'); // published: Metal feature set tables
    expect(text(q('[data-part="simd"] .ml-fig-part__row span'))).toBe(unknown);
    expect(all('[data-panel="3"] .gp-unit--unk').length).toBe(32 - 1 + 0 - 0); // 31 dashed bars, one marked
    for (const row of ['l1', 'l2']) expect(text(q(`.ml-fig-cmp tr[data-row="${row}"] td[data-sel]`))).toBe('–');
    expect(text(q('.gp-ladder'))).not.toMatch(/\d+ cycles/);
    await choose('rtx');
    for (const id of ['reg']) expect(text(q(`[data-part="${id}"] .ml-fig-part__v`))).toBe(unknown);
    expect(text(q('[data-part="warp"] .ml-fig-part__row span'))).toBe(unknown);
    expect(text(q('.gp-ladder'))).not.toMatch(/\d+ cycles/);
    expect(text(q('.ml-fig-cmp tr[data-row="backend"] td[data-sel]'))).toBe('–');
    expect(text(q('[data-part="link"] .ml-fig-part__v'))).toBe('PCIe Gen5 · at most 64 GB/s');
    await choose('t4');
    expect(text(q('.gp-ladder'))).toContain('30 cycles');
    expect(text(q('.gp-ladder'))).toContain('190 cycles');
    expect(text(q('.gp-ladder'))).toContain('≈300–600 cycles');
  });

  it('keeps four ladder rows on every machine: the M3 Max hides two as aria-hidden reserve', async () => {
    await mount();
    expect(all('.gp-ladder li')).toHaveLength(4);
    await choose('m3');
    expect(all('.gp-ladder li')).toHaveLength(4);
    expect(all('.gp-ladder li[data-off]').map((l) => l.getAttribute('aria-hidden'))).toEqual(['true', 'true']);
  });

  it('shows the counts of the kernel grid for the chosen machine', async () => {
    await mount('m3');
    const cell = (row: string, col: number): string => text(all(`tr[data-row="${row}"] td`)[col]);
    expect([cell('variant', 0), cell('variant', 1)]).toEqual(['NAIVE', 'SIMDGROUP']);
    expect([cell('threads', 0), cell('threads', 1)]).toEqual(['≈7,800', '≈250,000']);
    expect([cell('groups', 0), cell('groups', 1)]).toEqual(['–', '≈2,000']);
    expect([cell('warps', 0), cell('warps', 1)]).toEqual(['≈240', '≈7,800']);
    expect([cell('perunit', 0), cell('perunit', 1)]).toEqual(['≈6', '≈200']);
    await choose('rtx');
    expect([cell('variant', 1), cell('threads', 1), cell('perunit', 0), cell('perunit', 1)]).toEqual(['TILED', '≈125,000', '≈1', '≈20']);
    await choose('t4');
    expect([cell('groups', 1), cell('warps', 1), cell('perunit', 1)]).toEqual(['≈1,000', '≈3,900', '≈100']);
    expect(text(q('[data-part="group"] .ml-fig-part__v'))).toBe('128 = 4 × 32');
  });
});

describe('figure J4: detail panel', () => {
  const openPart = async (id: string): Promise<HTMLElement> => {
    const b = q(`[data-part="${id}"]`);
    await click(b);
    return b;
  };

  it('opens below the levels on click, names the machine, the part and the Java comparison, and closes on a second click', async () => {
    await mount();
    const b = await openPart('l2');
    const panel = q('.ml-fig__detail');
    expect(b.getAttribute('aria-expanded')).toBe('true');
    expect(panel.id).toBe(b.getAttribute('aria-controls'));
    expect(text(panel)).toContain('NVIDIA Tesla T4');
    expect(text(q('.ml-fig__detail-title'))).toBe('L2 cache');
    expect(text(panel)).toContain(javaCatalog.lookup('en', 'fig.gpu.detail.java'));
    expect(text(panel)).toContain('4 MB');
    expect(text(panel)).toContain('190');
    expect(q('.ml-fig-zoom').compareDocumentPosition(panel) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(panel.compareDocumentPosition(q('.gp-below')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    await click(b);
    expect(all('.ml-fig__detail')).toHaveLength(0);
    expect(b.getAttribute('aria-expanded')).toBe('false');
  });

  it('keeps one panel open at a time', async () => {
    await mount();
    await openPart('l2');
    await openPart('sm');
    expect(all('.ml-fig__detail')).toHaveLength(1);
    expect(text(q('.ml-fig__detail-title'))).toBe('SM');
    expect(q('[data-part="l2"]').getAttribute('aria-expanded')).toBe('false');
  });

  it('lists the names of the same thing for a part that has some', async () => {
    await mount();
    await openPart('group');
    expect(text(q('.ml-fig__detail'))).toContain('thread block · threadgroup · work group');
    expect(text(q('.ml-fig__detail'))).toContain('12.5 KiB');
  });

  it('says that the maker publishes no figure when the machine has no fact for the part', async () => {
    await mount('m3');
    await openPart('cache');
    expect(text(q('.ml-fig__detail'))).toContain(javaCatalog.lookup('en', 'fig.gpu.unknown.long'));
    expect(all('.gp-detail__facts')).toHaveLength(0);
  });

  it('closes on Escape and gives the focus back to the part', async () => {
    await mount();
    const b = await openPart('sm');
    b.focus();
    await act(async () => {
      b.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    });
    expect(all('.ml-fig__detail')).toHaveLength(0);
    expect(document.activeElement).toBe(b);
  });

  it('closes on Escape inside the panel and with its close button, and returns the focus', async () => {
    await mount();
    const b = await openPart('sm');
    const close = q('.ml-fig__detail-close');
    close.focus();
    await act(async () => {
      close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    });
    expect(all('.ml-fig__detail')).toHaveLength(0);
    expect(document.activeElement).toBe(b);
    await openPart('sm');
    await click(q('.ml-fig__detail-close'));
    expect(all('.ml-fig__detail')).toHaveLength(0);
    expect(document.activeElement).toBe(b);
  });

  it('stays open when the new machine has the same part and closes when it has not', async () => {
    await mount();
    await openPart('cpu');
    await choose('rtx');
    expect(text(q('.ml-fig__detail-title'))).toBe('CPU');
    expect(text(q('.ml-fig__detail'))).toContain('NVIDIA GeForce RTX 5090');
    await openPart('link');
    await choose('m3');
    expect(all('.ml-fig__detail')).toHaveLength(0);
    await choose('t4');
    expect(all('.ml-fig__detail')).toHaveLength(0);
  });

  it('keeps the detail panel closed while stepping and keeps the step while a part is open', async () => {
    await mount();
    await openPart('thread');
    await click(stepButton('forward'));
    expect(all('.ml-fig__detail')).toHaveLength(1);
    expect(stage().dataset.step).toBe('host');
  });
});

describe('figure J4: narration', () => {
  const ghosts = (): string[] => all('.ml-fig__status-line--ghost').map((g) => text(g));

  it('is the only live region and shows the step label from step 1', async () => {
    await mount();
    expect(q('.ml-fig__status-line:not(.ml-fig__status-line--ghost)').querySelectorAll('.ml-fig__step')).toHaveLength(0);
    await click(stepButton('forward'));
    expect(text(q('.ml-fig__status-line:not(.ml-fig__status-line--ghost) .ml-fig__step'))).toMatch(new RegExp(`^Step 1 of ${STEP_COUNT}`));
  });

  for (const lang of ['en', 'de'] as const) {
    it(`${lang}: no step and no machine produces a text that is not among the ghosts of the status area`, async () => {
      i18n.setLanguage(lang);
      await mount();
      const known = new Set(ghosts());
      expect(known.size).toBeGreaterThanOrEqual(15); // 8 steps, 5 of them with a text per kind of machine, idle: 15 distinct texts at least
      for (const hw of HW_IDS) {
        await choose(hw);
        for (let k = 0; k <= STEP_COUNT; k++) {
          await goTo(k);
          const line = q('.ml-fig__status-line:not(.ml-fig__status-line--ghost)');
          const shown = text(line);
          expect(known.has(shown), `${lang} ${hw} step ${k}: ${shown}`).toBe(true);
          expect(shown).not.toMatch(/\{\w+\}/);
          // the live line shows no more than its ghost: the longest ghost is at least as long
          expect(Math.max(...ghosts().map((g) => g.length))).toBeGreaterThanOrEqual(shown.length);
        }
      }
    });
  }

  it('says in the steps 2 and 8 that the copies are a small part of a call', async () => {
    await mount();
    await goTo(2);
    expect(narration()).toMatch(/small part of a call/);
    await choose('m3');
    expect(narration()).toMatch(/less than 0.01 ms per call/);
  });

  it('writes the narration of the current step for the new kind when the machine changes', async () => {
    await mount();
    await goTo(1);
    const before = narration();
    await choose('m3');
    expect(narration()).not.toBe(before);
    expect(narration()).toContain('share');
  });
});

describe('figure J4: compact layout', () => {
  it('makes the levels that are not current inert when the figure is narrower than 40rem, and none when wide', async () => {
    const width = { v: 288 };
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width: width.v, height: 100, x: 0, y: 0, top: 0, left: 0, right: width.v, bottom: 100, toJSON: () => ({}) }));
    await mount();
    expect(all('[data-panel]').filter((p) => p.hasAttribute('inert')).map((p) => p.getAttribute('data-panel'))).toEqual(['2', '3', '4', '5']);
    await goTo(5);
    expect(all('[data-panel]').filter((p) => p.hasAttribute('inert')).map((p) => p.getAttribute('data-panel'))).toEqual(['1', '2', '3', '5']);
    expect(all('[data-panel]').filter((p) => p.hasAttribute('data-off')).map((p) => p.getAttribute('data-panel'))).toEqual(['1', '2', '3', '5']);
    width.v = 736;
    await act(async () => {
      window.dispatchEvent(new Event('resize'));
    });
  });

  it('has no inert level in jsdom without a measured width (wide layout)', async () => {
    await mount();
    expect(all('[inert]')).toHaveLength(0);
  });
});

describe('figure J4: in topic java', () => {
  it('stands first in the section tornadovm, above its snippets', async () => {
    await render(<JavaPage topic="java" section={undefined} onJumpSection={() => undefined} />);
    const section = q('section[aria-labelledby="about-java-tornadovm"]');
    expect(section.querySelector('figure')).toBe(q('#fig-gpu'));
    expect(all('figure#fig-gpu')).toHaveLength(1);
  });
});
