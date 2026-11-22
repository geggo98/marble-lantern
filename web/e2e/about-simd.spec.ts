// Figure J3 of topic 6 (java), SIMD with the Vector API, in a browser: the stable layout (the figure keeps its height, and so do narration, code strip,
// grid and readout, over 4 lane counts x 12 steps), the compact (320 px) and wide (1280 px) layouts, keyboard use, reduced motion, light and dark.
// Texts are not compared, so the checks run in both languages. Method of the stable-layout check as in about-stable-layout.spec.ts.
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp } from './helpers';

type Size = { width: number; height: number };
const PHONE: Size = { width: 320, height: 568 };
const SIZES: Size[] = [PHONE, { width: 390, height: 844 }, { width: 820, height: 1180 }, { width: 1280, height: 800 }];
const LANES = [1, 4, 8, 16] as const;
const TOLERANCE = 0.5;
const FIG = '#fig-simd';

async function open(page: Page, size: Size, lang: 'en' | 'de' = 'en', scheme: 'light' | 'dark' = 'light', motion: 'reduce' | 'no-preference' = 'reduce'): Promise<Locator> {
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: scheme, reducedMotion: motion });
  await openApp(page, lang, '#/about/java');
  const fig = page.locator(FIG);
  await fig.scrollIntoViewIfNeeded();
  await page.evaluate(() => document.fonts.ready);
  await expect(fig).toBeVisible();
  return fig;
}

/** Sets the slider to a lane count the way a user does: the native range input fires `input`. */
const setLanes = (fig: Locator, lanes: number): Promise<void> =>
  fig.locator('input[type="range"]').evaluate((el, index) => {
    const input = el as HTMLInputElement;
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, String(index));
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }, LANES.indexOf(lanes as 1));

interface Sample {
  state: string;
  height: number;
  /** Offsets from the top of the figure to the named parts, and their heights. */
  top: Record<string, number>;
  size: Record<string, number>;
}
const PARTS: Record<string, string> = {
  status: '.ml-fig__status',
  stage: '.ml-fig__stage',
  code: '.ml-fig-simd__code',
  grid: 'svg.ml-fig-simd__grid',
  legend: '.ml-fig-legend',
  readout: '.ml-fig-readout',
  meas: '.ml-fig-simd__meas',
  caption: '.ml-fig__caption',
  panel: '.ml-fig__panel',
};

/** Walks 12 steps at each of the 4 lane counts inside the page (a DOM click per step, two frames after each) and measures the figure. */
function walk(fig: Locator): Promise<Sample[]> {
  return fig.evaluate(async (el, args): Promise<Sample[]> => {
    const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
    const slider = el.querySelector<HTMLInputElement>('input[type="range"]')!;
    const buttons = el.querySelectorAll<HTMLButtonElement>('.ml-fig-stepper button');
    const out: Sample[] = [];
    const read = (state: string): void => {
      const box = el.getBoundingClientRect();
      const top: Record<string, number> = {};
      const size: Record<string, number> = {};
      for (const [name, selector] of Object.entries(args.parts)) {
        const found = el.querySelector(selector);
        if (!found) continue;
        const r = found.getBoundingClientRect();
        top[name] = r.top - box.top;
        size[name] = r.height;
      }
      out.push({ state, height: box.height, top, size });
    };
    for (const index of [1, 0, 2, 3]) {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(slider, String(index));
      slider.dispatchEvent(new Event('input', { bubbles: true }));
      buttons[0]!.click(); // reset: step 0
      await frames();
      read(`lanes index ${index}, step 0`);
      for (let step = 1; step <= 11; step++) {
        buttons[buttons.length - 1]!.click(); // forward
        await frames();
        read(`lanes index ${index}, step ${step}`);
      }
    }
    return out;
  }, { parts: PARTS });
}

function expectStable(label: string, samples: Sample[]): void {
  expect(samples, `${label}: states seen`).toHaveLength(48);
  const first = samples[0]!;
  for (const s of samples) {
    // All differences of a state are collected, so that a failure names every part that moved, not only the first.
    const diffs: string[] = [];
    if (Math.abs(s.height - first.height) > TOLERANCE) diffs.push(`figure height ${s.height} vs ${first.height}`);
    for (const name of Object.keys(PARTS)) {
      if (Math.abs((s.top[name] ?? 0) - (first.top[name] ?? 0)) > TOLERANCE) diffs.push(`offset of ${name} ${s.top[name]} vs ${first.top[name]}`);
      if (Math.abs((s.size[name] ?? 0) - (first.size[name] ?? 0)) > TOLERANCE) diffs.push(`height of ${name} ${s.size[name]} vs ${first.size[name]}`);
    }
    expect(diffs, `${label} ${s.state} (compared with ${first.state})`).toEqual([]);
  }
}

for (const size of SIZES) {
  test(`J3 stable layout at ${size.width} x ${size.height}: one height for narration, code strip, grid, readout, number line and figure over 4 lane counts x 12 steps (en, de)`, async ({ page }) => {
    const fig = await open(page, size, 'en');
    expectStable(`en ${size.width}`, await walk(fig));
    await page.locator('.ml-header [data-language="de"]').click();
    await expect(page.locator('.ml-header [data-language="de"]')).toHaveAttribute('aria-pressed', 'true');
    await fig.scrollIntoViewIfNeeded();
    expectStable(`de ${size.width}`, await walk(fig));
  });
}

test('J3 stable layout: the narration is one grid cell with ghosts, the code strip and the note of the number line too', async ({ page }) => {
  const fig = await open(page, PHONE);
  const cells = await fig.evaluate((el) => {
    const cell = (sel: string): string[] => [...el.querySelectorAll(sel)].map((e) => getComputedStyle(e).gridArea);
    return {
      status: cell('.ml-fig__status > .ml-fig__status-line'),
      code: cell('.ml-fig-simd__code > pre'),
      notes: cell('.ml-fig-simd__slot > *'),
      statusGhostsHidden: [...el.querySelectorAll('.ml-fig__status-line--ghost')].every((e) => e.getAttribute('aria-hidden') === 'true' && getComputedStyle(e).visibility === 'hidden'),
      codeGhostsHidden: [...el.querySelectorAll('.ml-fig-simd__code > pre[aria-hidden]')].every((e) => getComputedStyle(e).visibility === 'hidden'),
      notesHidden: [...el.querySelectorAll('.ml-fig-simd__slot > [aria-hidden]')].length,
    };
  });
  expect(new Set(cells.status).size, 'narration lines share one grid cell').toBe(1);
  expect(cells.status).toHaveLength(6);
  expect(new Set(cells.code).size, 'code strips share one grid cell').toBe(1);
  expect(cells.code).toHaveLength(6);
  expect(new Set(cells.notes).size, 'notes share one grid cell').toBe(1);
  expect(cells.notes).toHaveLength(4);
  expect(cells.statusGhostsHidden && cells.codeGhostsHidden).toBe(true);
  expect(cells.notesHidden, 'three of the four notes are hidden ghosts').toBe(3);
});

test('J3 at 320: compact layout, no sideways scroll, SVG text of at least 12 px, 44 px targets, the whole grid inside the frame', async ({ page }) => {
  const fig = await open(page, PHONE);
  await expect(fig).toHaveAttribute('data-layout', 'compact');
  const m = await page.evaluate(() => {
    const f = document.querySelector<HTMLElement>('#fig-simd')!;
    const se = document.scrollingElement!;
    const grid = f.querySelector<SVGSVGElement>('svg.ml-fig-simd__grid')!;
    const meas = f.querySelector<SVGSVGElement>('.ml-fig-simd__meas svg')!;
    const px = (svg: SVGSVGElement): number => svg.getBoundingClientRect().width / svg.viewBox.baseVal.width;
    const texts = (svg: SVGSVGElement): number[] => [...svg.querySelectorAll('text')].map((t) => parseFloat(getComputedStyle(t).fontSize) * px(svg));
    const targets = [...f.querySelectorAll<HTMLElement>('.ml-fig-stepper button, .ml-fig-presets button, input[type="range"], .ml-fig-chip')].map((e) => e.getBoundingClientRect().height);
    const frame = f.getBoundingClientRect();
    const gr = grid.getBoundingClientRect();
    return {
      page: se.scrollWidth - se.clientWidth,
      frame: f.scrollWidth - f.clientWidth,
      viewBox: grid.getAttribute('viewBox'),
      gridInside: gr.left >= frame.left - 0.5 && gr.right <= frame.right + 0.5,
      gridTexts: texts(grid),
      measTexts: texts(meas),
      targets,
      presetsRows: new Set([...f.querySelectorAll('.ml-fig-presets button')].map((b) => Math.round(b.getBoundingClientRect().top))).size,
      labelHidden: getComputedStyle(f.querySelector('.ml-fig-ctl:has(.ml-fig-stepper) > .ml-fig-ctl__label')!).clipPath,
      codeWidth: (f.querySelector('.ml-fig-simd__code') as HTMLElement).scrollWidth - (f.querySelector('.ml-fig-simd__code') as HTMLElement).clientWidth,
    };
  });
  expect(m.page, 'page scroll').toBeLessThanOrEqual(0);
  expect(m.frame, 'figure frame scroll').toBeLessThanOrEqual(0);
  expect(m.viewBox).toBe('0 0 260 126');
  expect(m.gridInside).toBe(true);
  for (const s of [...m.gridTexts, ...m.measTexts]) expect(s, 'SVG text at 320 px').toBeGreaterThanOrEqual(11.95);
  expect(m.gridTexts.length).toBeGreaterThan(0);
  for (const h of m.targets) expect(h).toBeGreaterThanOrEqual(43.4);
  expect(m.presetsRows, 'the two presets stand in one row').toBe(1);
  expect(m.labelHidden, 'the stepper label is visually hidden in the compact layout').not.toBe('none');
  expect(m.codeWidth, 'the code strip wraps, it does not scroll').toBeLessThanOrEqual(0);
});

test('J3 at 1280: wide layout with the larger view box, readout in one row, number line beside its text rows', async ({ page }) => {
  const fig = await open(page, { width: 1280, height: 800 });
  await expect(fig).toHaveAttribute('data-layout', 'wide');
  const m = await fig.evaluate((el) => {
    const top = (sel: string, i = 0): number => el.querySelectorAll(sel)[i]!.getBoundingClientRect().top;
    const left = (sel: string): number => el.querySelector(sel)!.getBoundingClientRect().left;
    return {
      viewBox: el.querySelector('svg.ml-fig-simd__grid')!.getAttribute('viewBox'),
      cells: [0, 1, 2].map((i) => Math.round(top('.ml-fig-readout__cell', i))),
      measRow: [Math.round(top('.ml-fig-simd__meas > svg')), Math.round(top('.ml-fig-simd__facts'))],
      measLeft: [left('.ml-fig-simd__meas > svg'), left('.ml-fig-simd__facts')],
      labelHidden: getComputedStyle(el.querySelector('.ml-fig-ctl:has(.ml-fig-stepper) > .ml-fig-ctl__label')!).position,
      page: document.scrollingElement!.scrollWidth - document.scrollingElement!.clientWidth,
    };
  });
  expect(m.viewBox).toBe('0 0 640 147');
  expect(new Set(m.cells).size, 'the three readout cells stand in one row').toBe(1);
  expect(m.measRow[0], 'number line and text rows start at the same height').toBe(m.measRow[1]);
  expect(m.measLeft[1]).toBeGreaterThan(m.measLeft[0]!);
  expect(m.labelHidden, 'the stepper label is visible in the wide layout').not.toBe('absolute');
  expect(m.page).toBeLessThanOrEqual(0);
});

test('J3 keyboard: slider and presets and stepper work with the keys of the spec, and Tab visits them in order', async ({ page }) => {
  const fig = await open(page, { width: 1280, height: 800 });
  const slider = fig.locator('input[type="range"]');
  await slider.focus();
  await page.keyboard.press('End');
  await expect(fig).toHaveAttribute('data-lanes', '16');
  await expect(slider).toHaveAttribute('aria-valuetext', /16/);
  await page.keyboard.press('ArrowLeft');
  await expect(fig).toHaveAttribute('data-lanes', '8');
  await expect(fig.locator('.ml-fig-presets button[aria-pressed="true"]')).toHaveCount(0);
  await page.keyboard.press('Home');
  await expect(fig).toHaveAttribute('data-lanes', '1');
  // Tab order: slider, the two presets, the stepper buttons
  await page.keyboard.press('Tab');
  await expect(fig.locator('.ml-fig-presets button').nth(0)).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(fig.locator('.ml-fig-presets button').nth(1)).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(fig).toHaveAttribute('data-lanes', '16');
  await expect(fig.locator('.ml-fig-presets button').nth(1)).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('Tab');
  await expect(fig.locator('.ml-fig-stepper button').first()).toBeFocused();
  // the stepper: Right, Right, End, Home, Left
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  await expect(fig).toHaveAttribute('data-step', '2');
  await expect(fig).toHaveAttribute('data-kind', 'bcast');
  await page.keyboard.press('End');
  await expect(fig).toHaveAttribute('data-step', '11');
  await expect(fig).toHaveAttribute('data-kind', 'done');
  await page.keyboard.press('ArrowLeft');
  await expect(fig).toHaveAttribute('data-step', '10');
  await page.keyboard.press('Home');
  await expect(fig).toHaveAttribute('data-step', '1');
  // a lane change keeps the step
  await setLanes(fig, 8);
  await expect(fig).toHaveAttribute('data-step', '1');
  // a click on a preset sets its lane count
  await fig.locator('.ml-fig-presets button').nth(0).click();
  await expect(fig).toHaveAttribute('data-lanes', '4');
});

test('J3 grid: the first tile holds 8 x lanes named boxes, FMA i fills `lanes` boxes in teal and earlier ones grey', async ({ page }) => {
  const fig = await open(page, { width: 1280, height: 800 });
  for (const lanes of LANES) {
    await setLanes(fig, lanes);
    await expect(fig.locator('svg.ml-fig-simd__grid rect.lane[data-acc]:not([data-acc=""])')).toHaveCount(8 * lanes);
    await expect(fig.locator('svg.ml-fig-simd__grid path.tile')).toHaveCount(32 / (2 * lanes));
    for (const i of [0, 3, 7]) {
      // Home goes to step 1, the aria-disabled reset button at step 0 cannot be clicked by Playwright: reset by DOM click.
      await fig.locator('.ml-fig-stepper button').first().evaluate((b) => (b as HTMLElement).click());
      for (let s = 0; s < 3 + i; s++) await fig.locator('.ml-fig-stepper button').last().click();
      await expect(fig.locator('rect.lane[data-state="current"][data-acc]')).toHaveCount(lanes);
      await expect(fig.locator('rect.lane[data-state="done"][data-acc]')).toHaveCount(i * lanes);
    }
  }
});

test('J3 number line: the triangle follows the slider, the measured dot and its link to the tick 4 stay, the note changes', async ({ page }) => {
  const fig = await open(page, { width: 1280, height: 800 });
  const geometry = (): Promise<{ marker: number; dot: number; ring: number }> =>
    fig.evaluate((el) => {
      const cx = (sel: string): number => {
        const r = el.querySelector(sel)!.getBoundingClientRect();
        return r.left + r.width / 2;
      };
      return { marker: cx('path[data-marker="lanes"]'), dot: cx('.ml-fig-simd__meas circle.mdot'), ring: cx('.ml-fig-simd__meas circle.home') };
    });
  const at4 = await geometry();
  expect(Math.abs(at4.marker - at4.ring), 'at 4 lanes the triangle stands under the ring on the tick 4').toBeLessThan(1);
  await setLanes(fig, 16);
  const at16 = await geometry();
  expect(at16.marker).toBeGreaterThan(at4.marker + 20);
  expect(at16.dot).toBe(at4.dot);
  expect(at16.ring).toBe(at4.ring);
  await expect(fig.locator('[data-note="avx512"]')).not.toHaveAttribute('aria-hidden', 'true');
  await expect(fig.locator('[data-note="neon"]')).toHaveAttribute('aria-hidden', 'true');
});

test('J3 reduced motion: no play button, no transition on the boxes; without the preference the play button runs to step 11 and stops', async ({ page }) => {
  let fig = await open(page, PHONE, 'en', 'light', 'reduce');
  await expect(fig.locator('.ml-fig-stepper button')).toHaveCount(3);
  const transition = await fig.locator('rect.lane').first().evaluate((el) => getComputedStyle(el).transitionDuration);
  expect(transition).toMatch(/^0s(, 0s)*$/);
  fig = await open(page, PHONE, 'en', 'light', 'no-preference');
  await expect(fig.locator('.ml-fig-stepper button')).toHaveCount(4);
  const live = fig.locator('[role="status"]');
  await fig.locator('.ml-fig-stepper button').nth(2).click();
  await expect(live).toHaveAttribute('aria-live', 'off');
  await expect(fig).toHaveAttribute('data-step', '11', { timeout: 30_000 });
  await expect(live).toHaveAttribute('aria-live', 'polite');
});

for (const scheme of ['light', 'dark'] as const) {
  test(`J3 colours in the ${scheme} theme: hollow boxes of the first tile use the axis colour, never the dim one; the current FMA is teal; no page error`, async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    const fig = await open(page, { width: 1280, height: 800 }, 'en', scheme);
    const colours = (): Promise<Record<string, string>> =>
      fig.evaluate((el) => {
        const probe = (token: string): string => {
          const p = document.createElement('i');
          p.style.color = `var(${token})`;
          el.appendChild(p);
          const c = getComputedStyle(p).color;
          p.remove();
          return c;
        };
        const stroke = (sel: string): string => {
          const e = el.querySelector(sel);
          return e ? getComputedStyle(e).stroke : 'none';
        };
        const fill = (sel: string): string => {
          const e = el.querySelector(sel);
          return e ? getComputedStyle(e).fill : 'none';
        };
        return {
          axis: probe('--ml-fig-axis'), dim: probe('--ml-fig-dim'), accent: probe('--ml-fig-accent'),
          emptyWeight: stroke('rect.wt[data-state="empty"]'), pendingLane: stroke('rect.lane[data-acc="a00"]'),
          laterLane: stroke('rect.lane[data-state="none"]'), laterBracket: stroke('path.tile:not([data-current])'),
          currentFill: fill('rect.lane[data-state="current"]'),
        };
      });
    const idle = await colours();
    expect(idle.emptyWeight, 'hollow weight boxes in the band').toBe(idle.axis);
    expect(idle.pendingLane, 'hollow product boxes in the band').toBe(idle.axis);
    expect(idle.laterLane, 'later tiles are dim').toBe(idle.dim);
    expect(idle.laterBracket).toBe(idle.dim);
    expect(idle.axis).not.toBe(idle.dim);
    await fig.locator('.ml-fig-stepper button').last().click();
    await fig.locator('.ml-fig-stepper button').last().click();
    await fig.locator('.ml-fig-stepper button').last().click();
    const fma = await colours();
    expect(fma.currentFill, 'the current FMA is teal').toBe(fma.accent);
    expect(errors).toEqual([]);
  });
}
