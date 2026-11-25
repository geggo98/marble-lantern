// Fig. 4.4 (what the training cost, topic training) in a browser. The stable-layout rule: no state changes the height of the figure or the top of
// its stage (3 views x 2 bases x 8 selected rows, in both languages, at the five viewports of about-stable-layout.spec.ts); no sideways scroll at
// 320 px; every control at least 44 x 44 px; SVG text at least 12 px on the screen. This figure has no stepper, so about-stable-layout.spec.ts does
// not cover it. jsdom checks: src/about/figures/energy.dom.test.tsx.
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp } from './helpers';

type Size = { width: number; height: number };
const SIZES: Size[] = [
  { width: 320, height: 568 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
  { width: 820, height: 1180 },
  { width: 1280, height: 800 },
];
const TOLERANCE = 0.5;

async function open(page: Page, size: Size, lang: 'en' | 'de'): Promise<Locator> {
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
  await openApp(page, lang, '#/about/training');
  const fig = page.locator('figure#fig-energy');
  await fig.scrollIntoViewIfNeeded();
  await page.evaluate(() => document.fonts.ready);
  await expect(fig).toBeVisible();
  return fig;
}

interface Sample {
  state: string;
  height: number;
  stageTop: number;
  parts: Record<string, number>;
}

/** The parts whose height must not change with the state (selectors inside the figure). */
const PARTS: Record<string, string> = {
  head: '.ml-fig__head',
  panel: '.ml-fig__panel',
  status: '.ml-fig__status',
  plot: '[data-part="plot"]',
  legend: '[data-part="legend"]',
  detail: '[data-part="detail"]',
  table: '[data-part="table"]',
  caption: 'figcaption',
};

/** Runs through all 48 states inside the page (a DOM click on the radios and on the table buttons) and measures each. */
function sweep(fig: Locator): Promise<Sample[]> {
  return fig.evaluate(async (el, parts): Promise<Sample[]> => {
    const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
    const groups = el.querySelectorAll<HTMLElement>('.ml-fig-seg');
    const radio = (g: number, i: number): HTMLInputElement => groups[g]!.querySelectorAll<HTMLInputElement>('input[type="radio"]')[i]!;
    const buttons = Array.from(el.querySelectorAll<HTMLButtonElement>('tbody button.ml-fig-energy__pick'));
    const read = (state: string): Sample => {
      const box = el.getBoundingClientRect();
      const stage = el.querySelector('.ml-fig__stage')?.getBoundingClientRect();
      const out: Record<string, number> = {};
      for (const [name, selector] of Object.entries(parts)) {
        const found = el.querySelector(selector);
        if (found) out[name] = found.getBoundingClientRect().height;
      }
      return { state, height: box.height, stageTop: (stage?.top ?? 0) - box.top, parts: out };
    };
    const seen: Sample[] = [];
    for (let v = 0; v < 3; v++) {
      radio(0, v).click();
      await frames();
      for (let b = 0; b < 2; b++) {
        if (v < 2) {
          radio(1, b).click();
          await frames();
        }
        for (const [i, button] of buttons.entries()) {
          button.click();
          await frames();
          seen.push(read(`view ${v} basis ${b} row ${i}`));
        }
      }
    }
    return seen;
  }, PARTS);
}

function expectStable(label: string, samples: Sample[]): void {
  expect(samples.length, `${label}: states seen`).toBeGreaterThanOrEqual(48);
  const first = samples[0]!;
  for (const s of samples) {
    const where = `${label} ${s.state}`;
    expect(Math.abs(s.height - first.height), `${where}: figure height ${s.height} vs ${first.height}`).toBeLessThanOrEqual(TOLERANCE);
    expect(Math.abs(s.stageTop - first.stageTop), `${where}: stage top ${s.stageTop} vs ${first.stageTop}`).toBeLessThanOrEqual(TOLERANCE);
    for (const [name, h] of Object.entries(s.parts)) {
      expect(Math.abs(h - (first.parts[name] ?? h)), `${where}: part ${name} height ${h} vs ${first.parts[name]}`).toBeLessThanOrEqual(TOLERANCE);
    }
  }
}

for (const lang of ['en', 'de'] as const) {
  for (const size of SIZES) {
    test(`energy figure ${lang} ${size.width}x${size.height}: no state changes the height of the figure or of a part`, async ({ page }) => {
      const fig = await open(page, size, lang);
      expectStable(`${lang} ${size.width}`, await sweep(fig));
    });
  }
}

test('energy figure: no sideways scroll at 320 px, in both languages and in every view', async ({ page }) => {
  for (const lang of ['en', 'de'] as const) {
    const fig = await open(page, SIZES[0]!, lang);
    for (let v = 0; v < 3; v++) {
      await fig.locator('.ml-fig-seg').first().locator('input[type="radio"]').nth(v).evaluate((e) => (e as HTMLInputElement).click());
      const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(over, `${lang} view ${v}: horizontal overflow`).toBeLessThanOrEqual(0);
      const figWidth = await fig.evaluate((e) => ({ scroll: e.scrollWidth, client: e.clientWidth }));
      expect(figWidth.scroll, `${lang} view ${v}: figure overflow`).toBeLessThanOrEqual(figWidth.client + 1);
    }
  }
});

test('energy figure: every control is at least 44 x 44 px (at 320 and 1280 px)', async ({ page }) => {
  for (const size of [SIZES[0]!, SIZES[4]!]) {
    const fig = await open(page, size, 'en');
    const small = await fig.evaluate((el) => {
      const targets = [...el.querySelectorAll<HTMLElement>('.ml-fig-seg__opt, button.ml-fig-energy__pick')];
      return targets.map((t) => ({ name: (t.textContent ?? '').trim().slice(0, 24), r: t.getBoundingClientRect() })).filter(({ r }) => r.height < 43.5 || r.width < 43.5).map(({ name, r }) => `${name}: ${r.width.toFixed(1)} x ${r.height.toFixed(1)}`);
    });
    expect(small, `${size.width}: targets below 44 px`).toEqual([]);
  }
});

test('energy figure: SVG text is at least 12 px on the screen in every view, at 320 and 1280 px', async ({ page }) => {
  for (const size of [SIZES[0]!, SIZES[4]!]) {
    const fig = await open(page, size, 'en');
    for (let v = 0; v < 3; v++) {
      await fig.locator('.ml-fig-seg').first().locator('input[type="radio"]').nth(v).evaluate((e) => (e as HTMLInputElement).click());
      const tiny = await fig.locator('svg.ml-fig-plot').evaluate((svg) => {
        const s = (svg as unknown as SVGSVGElement).getScreenCTM()?.a ?? 1;
        return [...svg.querySelectorAll('text')].map((t) => ({ t: (t.textContent ?? '').trim(), px: parseFloat(getComputedStyle(t).fontSize) * s })).filter(({ px }) => px < 11.5).map(({ t, px }) => `${t}: ${px.toFixed(1)}px`);
      });
      expect(tiny, `${size.width} view ${v}: SVG text below 12 px`).toEqual([]);
    }
  }
});

test('energy figure: the keyboard reaches the table buttons and the selection shows a ring', async ({ page }) => {
  const fig = await open(page, SIZES[4]!, 'en');
  await fig.locator('tr[data-row="large-gen0"] button').focus();
  await page.keyboard.press('Enter');
  await expect(fig).toHaveAttribute('data-selected', 'large-gen0');
  await expect(fig.locator('[data-ring]')).toHaveCount(1);
  await fig.locator('tr[data-row="small-gen0"] button').click();
  await expect(fig).toHaveAttribute('data-selected', 'small-gen0');
});
