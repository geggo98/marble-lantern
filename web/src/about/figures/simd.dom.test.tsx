// @vitest-environment jsdom
// Figure J3 (SIMD with the Vector API) in jsdom: the grid per lane count, the steps, the slider and the presets, the number line, the ghosts that hold the
// height, reduced motion, both languages. Layout at 320 and 1280 px is in about-simd.spec.ts.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import { SimdFigure } from './SimdFigure';
import { javaCatalog } from './java-catalog';
import type { Catalog } from './common';
import { LANE_STOPS, LAST_STEP, tileCount } from './simd-model';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const catalog = javaCatalog as unknown as Catalog;
let host: HTMLElement;
let root: Root;
const stubMotion = (reduce: boolean): void => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: reduce && query.includes('prefers-reduced-motion'),
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
};
beforeEach(() => {
  i18n.setLanguage('en');
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  stubMotion(false);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
  i18n.setLanguage('en');
});

const render = async (): Promise<void> => {
  await act(async () => root.render(<SimdFigure catalog={catalog} number={[6, 3]} />));
};
const q = <T extends Element = HTMLElement>(selector: string): T => {
  const el = host.querySelector<T>(selector);
  if (!el) throw new Error(`nothing matches ${selector}`);
  return el;
};
const all = <T extends Element = HTMLElement>(selector: string): T[] => Array.from(host.querySelectorAll<T>(selector));
const text = (el: Element | null | undefined): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const fig = (): HTMLElement => q('figure#fig-simd');
const key = (el: Element, k: string): void => {
  act(() => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
  });
};
const stepperButton = (): HTMLElement => q('.ml-fig-stepper button');
const go = (step: number): void => {
  key(stepperButton(), 'Home');
  key(stepperButton(), 'ArrowLeft');
  for (let i = 0; i < step; i++) key(stepperButton(), 'ArrowRight');
};
const setLanes = (lanes: number): void => {
  const slider = q<HTMLInputElement>('input[type="range"]');
  act(() => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(slider, String(LANE_STOPS.indexOf(lanes as 1)));
    slider.dispatchEvent(new Event('input', { bubbles: true }));
  });
};
const live = (): string => text(q('[role="status"]'));
const boxes = (state?: string): SVGElement[] => all<SVGElement>(`rect.lane[data-acc]:not([data-acc=""])${state ? `[data-state="${state}"]` : ''}`);

describe('figure J3 structure', () => {
  it('is a figure named by its title, with the hooks on the figure element', async () => {
    await render();
    expect(fig().getAttribute('aria-labelledby')).toBe('fig-simd-title');
    expect(text(q('#fig-simd-title'))).toBe('One pass of the convolution loop with the Vector API');
    expect(text(q('.ml-fig__label'))).toBe('Fig. 6.3');
    expect(fig().dataset).toMatchObject({ lanes: '4', step: '0', kind: 'idle', layout: 'compact' });
  });
  it('has exactly one live region, and the ghosts of the narration are hidden', async () => {
    await render();
    expect(all('[role="status"]')).toHaveLength(1);
    expect(all('.ml-fig__status-line--ghost')).toHaveLength(5);
    for (const g of all('.ml-fig__status-line--ghost')) expect(g.getAttribute('aria-hidden')).toBe('true');
  });
  it('names the grid and the number line, and gives both a role img', async () => {
    await render();
    expect(q('svg.ml-fig-simd__grid').getAttribute('role')).toBe('img');
    expect(text(q('svg.ml-fig-simd__grid > desc'))).toContain('4 board cells by 32 output channels');
    expect(text(q('svg.ml-fig-simd__grid > desc'))).toContain('Tiles: 4');
    expect(q('.ml-fig-simd__meas svg').getAttribute('role')).toBe('img');
    expect(text(q('.ml-fig-simd__meas svg > desc'))).toContain('Products per instruction at the chosen width: 4');
  });
});

describe('figure J3 grid', () => {
  it.each(LANE_STOPS.map((l) => [l]))('at %i lanes the first tile has 8 x lanes product boxes with an accumulator name, and the tile count of the spec', async (lanes) => {
    await render();
    setLanes(lanes);
    expect(boxes()).toHaveLength(8 * lanes);
    expect(all('svg.ml-fig-simd__grid path.tile')).toHaveLength(tileCount(lanes));
    expect(all('svg.ml-fig-simd__grid path.tile[data-current]')).toHaveLength(1);
    expect(all('svg.ml-fig-simd__grid rect.lane')).toHaveLength(4 * 32);
    expect(fig().dataset.lanes).toBe(String(lanes));
  });
  it.each(LANE_STOPS.map((l) => [l]))('at %i lanes, step 3 + i has exactly `lanes` current boxes and i x lanes done boxes', async (lanes) => {
    await render();
    setLanes(lanes);
    for (let i = 0; i < 8; i++) {
      go(3 + i);
      expect(boxes('current'), `step ${3 + i}`).toHaveLength(lanes);
      expect(boxes('done')).toHaveLength(i * lanes);
      expect(all('svg.ml-fig-simd__grid rect.ring')).toHaveLength(1);
      expect(all('svg.ml-fig-simd__grid path.ptr')).toHaveLength(1);
    }
  });
  it('keeps the step when the lane count changes, and the box count with it', async () => {
    await render();
    go(5);
    setLanes(16);
    expect(fig().dataset.step).toBe('5');
    expect(boxes('current')).toHaveLength(16);
    expect(boxes('done')).toHaveLength(2 * 16);
  });
  it('shows the loaded weights at step 1 and the dots of the broadcast values at step 2', async () => {
    await render();
    expect(all('rect.wt[data-state="empty"]')).toHaveLength(8);
    go(1);
    expect(all('rect.wt[data-state="current"]')).toHaveLength(8);
    expect(q('text.name[data-state="current"]').textContent).toBe('w0');
    go(2);
    expect(all('rect.vbox[data-state="current"]')).toHaveLength(4);
    expect(all('circle.dot[data-state="current"]').length).toBeGreaterThan(0);
  });
  it('ends with all 8 registers of the first tile done and the later tiles hollow', async () => {
    await render();
    go(LAST_STEP);
    expect(boxes('done')).toHaveLength(32);
    expect(all('svg.ml-fig-simd__grid rect.lane[data-state="none"]')).toHaveLength(4 * 32 - 32);
  });
  it('does not change the view box of the grid over lane counts and steps', async () => {
    await render();
    const seen = new Set<string>();
    for (const lanes of LANE_STOPS) {
      setLanes(lanes);
      for (const s of [0, 2, 7, LAST_STEP]) {
        go(s);
        seen.add(q('svg.ml-fig-simd__grid').getAttribute('viewBox')!);
      }
    }
    expect([...seen]).toEqual(['0 0 260 126']);
  });
});

describe('figure J3 controls', () => {
  it('has a slider of four stops with a spoken value and a readout of the register width', async () => {
    await render();
    const slider = q<HTMLInputElement>('input[type="range"]');
    expect([slider.min, slider.max, slider.value]).toEqual(['0', '3', '1']);
    expect(slider.getAttribute('aria-valuetext')).toBe('Lanes: 4, 128 bit');
    expect(text(q('.ml-fig-ctl__out'))).toBe('4 × 32 = 128 bit');
    expect(all('.ml-fig-scale span').map(text)).toEqual(['1', '4', '8', '16']);
    setLanes(16);
    expect(slider.getAttribute('aria-valuetext')).toBe('Lanes: 16, 512 bit');
  });
  it('presses the preset of the current lane count, and none at 1 and 8 lanes', async () => {
    await render();
    const presets = (): (string | null)[] => all('.ml-fig-presets button').map((b) => b.getAttribute('aria-pressed'));
    expect(presets()).toEqual(['true', 'false']);
    setLanes(16);
    expect(presets()).toEqual(['false', 'true']);
    setLanes(8);
    expect(presets()).toEqual(['false', 'false']);
    setLanes(1);
    expect(presets()).toEqual(['false', 'false']);
    act(() => all<HTMLElement>('.ml-fig-presets button')[1]!.click());
    expect(fig().dataset.lanes).toBe('16');
    act(() => all<HTMLElement>('.ml-fig-presets button')[0]!.click());
    expect(fig().dataset.lanes).toBe('4');
  });
  it('steps with Right, Left, End, Home and shows 11 track segments', async () => {
    await render();
    expect(all('.ml-fig-stepper__seg')).toHaveLength(11);
    key(stepperButton(), 'ArrowRight');
    key(stepperButton(), 'ArrowRight');
    expect(fig().dataset.step).toBe('2');
    expect(fig().dataset.kind).toBe('bcast');
    key(stepperButton(), 'End');
    expect(fig().dataset.step).toBe('11');
    expect(fig().dataset.kind).toBe('done');
    key(stepperButton(), 'ArrowLeft');
    expect(fig().dataset.step).toBe('10');
    key(stepperButton(), 'Home');
    expect(fig().dataset.step).toBe('1'); // kit: Home is step 1, the reset button goes to 0
  });
  it('does not write the slider into the live region', async () => {
    await render();
    const before = live();
    setLanes(8);
    expect(live()).toBe(before);
  });
});

describe('figure J3 text', () => {
  it('narrates the current FMA with accumulator, weight, input and lane count', async () => {
    await render();
    go(4);
    expect(live()).toContain('Step 4 of 11');
    expect(live()).toContain('a01');
    expect(live()).toContain('w1 × v0');
    go(LAST_STEP);
    expect(live()).toContain('8 FMAs gave 32 products');
    expect(live()).toContain('224 times');
  });
  it('shows the source lines of the step in the strip: 312 to 319 for the FMAs, with the identifiers marked', async () => {
    await render();
    go(5);
    const code = q('[data-live-code]');
    expect(text(code)).toBe('314 a10 = w0.fma(v1, a10);');
    expect(all('[data-live-code] .op').map(text)).toEqual(['a10', 'w0', 'v1', 'a10']);
    expect(all('.ml-fig-simd__code pre[aria-hidden]')).toHaveLength(5);
  });
  it('reads the readout: register width, FMAs of the pass, FMAs of the row', async () => {
    await render();
    setLanes(8);
    go(6);
    expect(all('.ml-fig-readout__cell').map((c) => text(c.querySelector('dd')))).toEqual(['8 × 32 bit', '4 / 8', '16']);
    expect(all('.ml-fig-simd__sub').map(text)).toEqual(['= 256 bit', '32 / 64 products', '128 products']);
  });
  it('keeps the four notes of the number line in one cell and shows only the one of the lane count', async () => {
    await render();
    const visible = (): string[] => all('[data-note]:not([aria-hidden])').map((e) => e.getAttribute('data-note')!);
    expect(all('[data-note]')).toHaveLength(4);
    expect(visible()).toEqual(['neon']);
    setLanes(1);
    expect(visible()).toEqual(['scalar']);
    setLanes(8);
    expect(visible()).toEqual(['avx2']);
    setLanes(16);
    expect(visible()).toEqual(['avx512']);
  });
  it('moves only the triangle and the tick label of the number line with the slider; dot, whisker and ring stay', async () => {
    await render();
    const fixed = (): string => ['circle.mdot', 'path.whisk', 'circle.home', 'path.link'].map((s) => q(`.ml-fig-simd__meas ${s}`).outerHTML).join('|');
    const marker = (): string => q('path[data-marker="lanes"]').getAttribute('d')!;
    const before = fixed();
    const m4 = marker();
    setLanes(16);
    expect(fixed()).toBe(before);
    expect(marker()).not.toBe(m4);
    expect(all('.ml-fig-simd__meas text.cur').map(text)).toEqual(['16']);
    expect(text(q('.ml-fig-simd__meas text.big'))).toBe('15×');
  });
  it('cites the measurement with a source chip', async () => {
    await render();
    expect(q('.ml-fig-simd__facts a.ml-src').getAttribute('data-src')).toBe('docs/network.md');
    expect(text(q('.ml-fig-simd__facts a.ml-src'))).toBe('docs/network.md:482');
  });
  it('speaks German', async () => {
    i18n.setLanguage('de');
    await render();
    expect(text(q('#fig-simd-title'))).not.toBe('One pass of the convolution loop with the Vector API');
    expect(live()).not.toContain('Step 0 of 11');
    go(6);
    expect(live()).toContain('a11');
  });
});

describe('figure J3 reduced motion', () => {
  it('shows the play button normally and not under reduced motion; the stepper keeps back and forward', async () => {
    await render();
    expect(host.querySelector('.ml-fig-stepper [aria-label="Play"]')).not.toBeNull();
    act(() => root.unmount());
    root = createRoot(host);
    stubMotion(true);
    await render();
    expect(host.querySelector('.ml-fig-stepper [aria-label="Play"]')).toBeNull();
    expect(host.querySelector('.ml-fig-stepper [aria-label="One step forward"]')).not.toBeNull();
  });
  it('plays to the last step and switches the live region off while playing', async () => {
    vi.useFakeTimers();
    try {
      await render();
      act(() => q<HTMLElement>('.ml-fig-stepper [aria-label="Play"]').click());
      expect(q('[role="status"]').getAttribute('aria-live')).toBe('off');
      for (let i = 0; i < LAST_STEP; i++) act(() => void vi.advanceTimersByTime(1200));
      expect(fig().dataset.step).toBe(String(LAST_STEP));
      expect(q('[role="status"]').getAttribute('aria-live')).toBe('polite');
    } finally {
      vi.useRealTimers();
    }
  });
});
