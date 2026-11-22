// Figure J6 (Hilla data flow, topic java-web) in a real browser: the stable-layout rule (no step changes the height of the figure, the narration, the
// lanes or the wire card, nor the place of any box), compact (320 px) and wide layouts without clipped text or page scroll, the keys of the
// stepper, reduced motion (no play button, no travelling dot), light and dark (contrast of the active box and of the card, computed from the page's
// own colours), and both languages. Unit logic is in hilla-model.test.ts and hilla.dom.test.tsx.
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp, watchPageErrors } from './helpers';

const HASH = '#/about/java-web';
const SIZES = [
  { width: 320, height: 568 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
  { width: 820, height: 1180 },
  { width: 1280, height: 800 },
];
const TOLERANCE = 0.5;
const BOXES = ['call', 'generated', 'client', 'engines', 'endpoint', 'controller'];

interface Sample {
  step: string;
  dataStep: string | null;
  height: number;
  status: number;
  lanes: number;
  wire: number;
  lanesTop: number;
  wireTop: number;
  /** x, y, width, height of each box relative to the figure. */
  boxes: Record<string, number[]>;
  headWidth: number;
}

/** Steps the figure from step 0 to step 8 inside the page (a DOM click on the stepper buttons, two animation frames after each) and measures every step. */
async function stepThrough(fig: Locator): Promise<Sample[]> {
  return fig.evaluate(async (el, ids): Promise<Sample[]> => {
    const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
    const buttons = el.querySelectorAll<HTMLButtonElement>('.ml-fig-stepper button');
    const pos = (): string => (el.querySelector('.ml-fig-stepper__pos')?.textContent ?? '').replace(/\s+/g, ' ').trim();
    const read = (): Sample => {
      const f = el.getBoundingClientRect();
      const rect = (sel: string): DOMRect => el.querySelector(sel)!.getBoundingClientRect();
      const boxes: Record<string, number[]> = {};
      for (const id of ids) {
        const r = rect(`[data-box="${id}"]`);
        boxes[id] = [r.left - f.left, r.top - f.top, r.width, r.height].map((n) => Math.round(n * 10) / 10);
      }
      return {
        step: pos(),
        dataStep: el.getAttribute('data-step'),
        height: f.height,
        status: rect('.ml-fig__status').height,
        lanes: rect('.ml-fig-lanes').height,
        wire: rect('.ml-fig-wire').height,
        lanesTop: rect('.ml-fig-lanes').top - f.top,
        wireTop: rect('.ml-fig-wire').top - f.top,
        boxes,
        headWidth: rect('.ml-fig-wire__head').width,
      };
    };
    buttons[0]!.click();
    await frames();
    const seen: Sample[] = [read()];
    for (let guard = 0; guard < 20; guard++) {
      const m = /(\d+) \/ (\d+)/.exec(pos());
      if (!m || Number(m[1]) >= Number(m[2])) break;
      buttons[buttons.length - 1]!.click();
      await frames();
      seen.push(read());
    }
    return seen;
  }, BOXES);
}

async function open(page: Page, lang: 'en' | 'de', scheme: 'light' | 'dark' = 'light', motion: 'reduce' | 'no-preference' = 'reduce'): Promise<Locator> {
  await page.emulateMedia({ colorScheme: scheme, reducedMotion: motion });
  await openApp(page, lang, HASH);
  const fig = page.locator('figure#fig-hilla');
  await fig.waitFor();
  await page.evaluate(() => document.fonts.ready);
  await fig.scrollIntoViewIfNeeded();
  return fig;
}

for (const lang of ['en', 'de'] as const) {
  for (const size of SIZES) {
    test(`stable layout: nine steps change no height and no box position (${lang}, ${size.width}x${size.height})`, async ({ page }) => {
      const errors = watchPageErrors(page);
      await page.setViewportSize(size);
      const fig = await open(page, lang);
      const samples = await stepThrough(fig);
      expect(samples).toHaveLength(9);
      expect(samples.map((s) => s.dataStep)).toEqual(['0', '1', '2', '3', '4', '5', '6', '7', '8']);
      const first = samples[0]!;
      for (const s of samples) {
        expect(Math.abs(s.height - first.height), `figure height at ${s.step}`).toBeLessThanOrEqual(TOLERANCE);
        expect(Math.abs(s.status - first.status), `narration height at ${s.step}`).toBeLessThanOrEqual(TOLERANCE);
        expect(Math.abs(s.lanes - first.lanes), `lanes height at ${s.step}`).toBeLessThanOrEqual(TOLERANCE);
        expect(Math.abs(s.wire - first.wire), `wire card height at ${s.step}`).toBeLessThanOrEqual(TOLERANCE);
        expect(Math.abs(s.lanesTop - first.lanesTop), `lanes top at ${s.step}`).toBeLessThanOrEqual(TOLERANCE);
        expect(Math.abs(s.wireTop - first.wireTop), `wire top at ${s.step}`).toBeLessThanOrEqual(TOLERANCE);
        expect(Math.abs(s.headWidth - first.headWidth), `card head width at ${s.step}`).toBeLessThanOrEqual(TOLERANCE);
        for (const id of BOXES) expect(s.boxes[id], `box ${id} at ${s.step}`).toEqual(first.boxes[id]);
      }
      // after a language switch on the page the heights are those of the new language: reload in the other one and compare to itself
      const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(over, 'page overflow').toBeLessThanOrEqual(0);
      errors.expectNone();
    });
  }
}

test('no text of a box is clipped and no part of the figure overflows, at 320 px, in every step, in both languages', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  for (const lang of ['en', 'de'] as const) {
    const fig = await open(page, lang);
    const problems = await fig.evaluate(async (el): Promise<string[]> => {
      const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
      const buttons = el.querySelectorAll<HTMLButtonElement>('.ml-fig-stepper button');
      const out: string[] = [];
      buttons[0]!.click();
      await frames();
      for (let step = 0; step <= 8; step++) {
        if (step > 0) {
          buttons[buttons.length - 1]!.click();
          await frames();
        }
        for (const box of el.querySelectorAll<HTMLElement>('[data-box]')) {
          for (const part of box.querySelectorAll<HTMLElement>('.ml-fig-lanes__id, .ml-fig-lanes__role')) {
            if (part.scrollWidth > part.clientWidth + 1) out.push(`step ${step} ${box.dataset.box} ${part.className} wide ${part.scrollWidth}>${part.clientWidth}`);
          }
          // the text must stay inside the box (the folded corner of the generated box stands 2 px outside on purpose)
          const b = box.getBoundingClientRect();
          for (const part of box.querySelectorAll<HTMLElement>('.ml-fig-lanes__id, .ml-fig-lanes__role')) {
            const r = part.getBoundingClientRect();
            if (r.left < b.left - 0.5 || r.right > b.right + 0.5 || r.top < b.top - 0.5 || r.bottom > b.bottom + 0.5) out.push(`step ${step} ${box.dataset.box} text leaves its box`);
          }
        }
        const f = el.getBoundingClientRect();
        for (const e of el.querySelectorAll<HTMLElement>('.ml-fig-lanes, .ml-fig-wire, .ml-fig-wire pre, .ml-fig-legend')) {
          const r = e.getBoundingClientRect();
          if (r.right > f.right + 0.5 || r.left < f.left - 0.5) out.push(`step ${step} ${e.className} leaves the figure`);
        }
        const wait = el.querySelector<HTMLElement>('.ml-fig-lanes__wait');
        if (wait && wait.scrollWidth > wait.clientWidth + 1) out.push(`step ${step} wait tag clipped`);
      }
      return out;
    });
    expect(problems, lang).toEqual([]);
  }
});

test('compact and wide use the layout of the spec: band 1.75rem and no band labels at 320 px, 9rem and labels from 40rem', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  let fig = await open(page, 'en');
  const compact = await fig.evaluate((el) => ({
    band: el.querySelector('.ml-fig-lanes__band')!.getBoundingClientRect().width,
    labelShown: getComputedStyle(el.querySelector('.ml-fig-lanes__edgelabel')!).display !== 'none',
    bandTitle: getComputedStyle(el.querySelector('.ml-fig-lanes__bandlabel')!).visibility,
    sticky: getComputedStyle(el.querySelector('.ml-fig__panel .ml-fig-ctl')!).position,
  }));
  expect(compact.band).toBeCloseTo(28, 0);
  expect(compact.labelShown).toBe(false);
  expect(compact.bandTitle).toBe('hidden');
  expect(compact.sticky).toBe('sticky');
  await page.setViewportSize({ width: 1280, height: 800 });
  fig = page.locator('figure#fig-hilla');
  await fig.scrollIntoViewIfNeeded();
  const wide = await fig.evaluate((el) => ({
    band: el.querySelector('.ml-fig-lanes__band')!.getBoundingClientRect().width,
    labelShown: getComputedStyle(el.querySelector('.ml-fig-lanes__edgelabel')!).display !== 'none',
    bandTitle: getComputedStyle(el.querySelector('.ml-fig-lanes__bandlabel')!).visibility,
    sticky: getComputedStyle(el.querySelector('.ml-fig__panel .ml-fig-ctl')!).position,
  }));
  expect(wide.band).toBeCloseTo(144, 0);
  expect(wide.labelShown).toBe(true);
  expect(wide.bandTitle).toBe('visible');
  expect(wide.sticky).not.toBe('sticky');
});

test('keyboard: Right and Left step, Home goes to step 1, End to step 8, and the figure adds no tab stop', async ({ page }) => {
  const fig = await open(page, 'en');
  const forward = fig.locator('.ml-fig-stepper button').last();
  await forward.focus();
  const step = (): Promise<string | null> => fig.getAttribute('data-step');
  await page.keyboard.press('ArrowRight');
  expect(await step()).toBe('1');
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  expect(await step()).toBe('3');
  await page.keyboard.press('ArrowLeft');
  expect(await step()).toBe('2');
  await page.keyboard.press('End');
  expect(await step()).toBe('8');
  await expect(fig.locator('.ml-fig-wire').first()).toHaveAttribute('data-wire-step', 'resolve');
  await page.keyboard.press('Home');
  expect(await step()).toBe('1');
  await expect(forward).toBeFocused(); // the keys never take the focus away
  // tab stops inside the stage: none
  expect(await fig.locator('.ml-fig__stage').evaluate((el) => el.querySelectorAll('button, a, input, select, textarea, [tabindex]').length)).toBe(0);
  // the active box has aria-current
  await expect(fig.locator('[data-box][aria-current="step"]')).toHaveCount(2);
});

test('the stepper buttons are 44 px', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  const fig = await open(page, 'en');
  const boxes = await fig.locator('.ml-fig-stepper button').evaluateAll((bs) => bs.map((b) => b.getBoundingClientRect()));
  for (const r of boxes) {
    expect(r.width).toBeGreaterThanOrEqual(43.5);
    expect(r.height).toBeGreaterThanOrEqual(43.5);
  }
});

test('reduced motion: no play button and no travelling dot; with motion allowed both exist and the playback stops at step 8', async ({ page }) => {
  let fig = await open(page, 'en', 'light', 'reduce');
  await expect(fig.locator('.ml-fig-stepper button')).toHaveCount(3);
  await expect(fig.locator('.ml-fig-arr__dot')).toHaveCount(0);
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  fig = page.locator('figure#fig-hilla');
  await expect(fig.locator('.ml-fig-stepper button')).toHaveCount(4);
  expect(await fig.locator('.ml-fig-arr__dot').count()).toBeGreaterThan(0);
  const play = fig.locator('.ml-fig-stepper button').nth(2);
  await play.click();
  await expect(fig.locator('[role="status"]')).toHaveAttribute('aria-live', 'off');
  await expect(fig).toHaveAttribute('data-step', '8', { timeout: 20_000 });
  await expect(fig.locator('[role="status"]')).toHaveAttribute('aria-live', 'polite');
  await expect(fig.locator('[data-wait]')).toHaveAttribute('data-wait', 'done');
});

// ---- colour: the figure reads its colours from tokens; this test computes the contrast of what is on screen ----
const toRgb = (css: string): [number, number, number] => {
  const m = /rgba?\(([\d.]+)[, ]+([\d.]+)[, ]+([\d.]+)/.exec(css);
  if (!m) throw new Error(`not a colour: ${css}`);
  return [Number(m[1]), Number(m[2]), Number(m[3])];
};
const lum = ([r, g, b]: [number, number, number]): number => {
  const c = [r, g, b].map((v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * c[0]! + 0.7152 * c[1]! + 0.0722 * c[2]!;
};
const ratio = (a: string, b: string): number => {
  const [x, y] = [lum(toRgb(a)), lum(toRgb(b))].sort((p, q) => q - p);
  return (x! + 0.05) / (y! + 0.05);
};

for (const scheme of ['light', 'dark'] as const) {
  test(`contrast in the ${scheme} theme: text of an active box, dimmed outline, accent arrow and card text`, async ({ page }) => {
    const fig = await open(page, 'en', scheme);
    await fig.locator('.ml-fig-stepper button').last().focus();
    await page.keyboard.press('Home');
    await page.keyboard.press('ArrowRight'); // step 2: call and generated active, the arrow b12:req
    const c = await fig.evaluate((el) => {
      const cs = (sel: string, prop: string): string => getComputedStyle(el.querySelector(sel)!).getPropertyValue(prop);
      const active = '[data-box="call"]';
      const dim = '[data-box="engines"]';
      return {
        activeBg: cs(active, 'background-color'),
        activeId: cs(`${active} .ml-fig-lanes__id`, 'color'),
        activeRole: cs(`${active} .ml-fig-lanes__role`, 'color'),
        activeBorder: cs(active, 'border-top-color'),
        dimBorder: cs(dim, 'border-top-color'),
        dimBg: cs(dim, 'background-color'),
        dimId: cs(`${dim} .ml-fig-lanes__id`, 'color'),
        dimRole: cs(`${dim} .ml-fig-lanes__role`, 'color'),
        arrowOn: cs('[data-arrow="b12:req"] .ml-fig-arr__line', 'border-inline-start-color'),
        arrowOff: cs('[data-arrow="b23:req"] .ml-fig-arr__line', 'border-inline-start-color'),
        lane: cs('[data-lane="browser"]', 'background-color'),
        figBg: getComputedStyle(el).backgroundColor,
        pre: cs('[data-live="body"]', 'color'),
        key: cs('[data-live="body"] .k', 'color'),
        file: cs('[data-live="body"] .f', 'color'),
        activeBorderW: cs(active, 'border-top-width'),
        dimBorderW: cs(dim, 'border-top-width'),
        activeIdWeight: cs(`${active} .ml-fig-lanes__id`, 'font-weight'),
        dimIdWeight: cs(`${dim} .ml-fig-lanes__id`, 'font-weight'),
      };
    });
    expect(ratio(c.activeId, c.activeBg), 'identifier of the active box').toBeGreaterThanOrEqual(4.5);
    expect(ratio(c.activeRole, c.activeBg), 'role of the active box').toBeGreaterThanOrEqual(4.5);
    expect(ratio(c.activeBorder, c.activeBg), 'outline of the active box').toBeGreaterThanOrEqual(3);
    expect(ratio(c.dimBorder, c.dimBg), 'outline of a dimmed box').toBeGreaterThanOrEqual(3);
    expect(ratio(c.dimId, c.dimBg), 'identifier of a dimmed box').toBeGreaterThanOrEqual(4.5);
    expect(ratio(c.dimRole, c.dimBg), 'role of a dimmed box').toBeGreaterThanOrEqual(4.5);
    expect(ratio(c.arrowOn, c.lane), 'active arrow in a lane').toBeGreaterThanOrEqual(3);
    expect(ratio(c.arrowOff, c.lane), 'dimmed arrow in a lane').toBeGreaterThanOrEqual(3);
    expect(ratio(c.pre, c.figBg), 'card text').toBeGreaterThanOrEqual(4.5);
    expect(ratio(c.key, c.figBg), 'changed part of the card').toBeGreaterThanOrEqual(4.5);
    expect(ratio(c.file, c.figBg), 'file name line of the card').toBeGreaterThanOrEqual(4.5);
    // second cues: the colour is never the only difference
    expect(parseFloat(c.activeBorderW)).toBeGreaterThan(parseFloat(c.dimBorderW));
    expect(Number(c.activeIdWeight)).toBeGreaterThan(Number(c.dimIdWeight));
  });
}

test('the three kinds of box differ by outline style, not by colour alone, and the first box of the call is the project code', async ({ page }) => {
  const fig = await open(page, 'en');
  const styles = await fig.evaluate((el) =>
    Object.fromEntries(['call', 'generated', 'client'].map((id) => [id, getComputedStyle(el.querySelector(`[data-box="${id}"]`)!).borderTopStyle])),
  );
  expect(styles).toEqual({ call: 'solid', generated: 'dotted', client: 'dashed' });
});

test('the card shows the form of the data: the tags follow the steps', async ({ page }) => {
  const fig = await open(page, 'en');
  await fig.locator('.ml-fig-stepper button').last().focus();
  const expected: Record<string, [string, string]> = { '1': ['build', 'source'], '2': ['browser', 'ts'], '3': ['browser', 'json'], '4': ['network', 'json'], '5': ['server', 'java'], '6': ['server', 'java'], '7': ['network', 'json'], '8': ['browser', 'ts'] };
  await page.keyboard.press('Home');
  for (let n = 1; n <= 8; n++) {
    if (n > 1) await page.keyboard.press('ArrowRight');
    await expect(fig.locator('[data-live="tags"] [data-wire-where]')).toHaveAttribute('data-wire-where', expected[String(n)]![0]);
    await expect(fig.locator('[data-live="tags"] [data-wire-form]')).toHaveAttribute('data-wire-form', expected[String(n)]![1]);
  }
});

test('the figure sits in the section "hilla" of the page and the step list follows the lanes', async ({ page }) => {
  const errors = watchPageErrors(page);
  const fig = await open(page, 'en');
  expect(await fig.evaluate((el) => el.closest('section')?.getAttribute('aria-labelledby'))).toBe('about-java-web-hilla');
  await expect(fig.locator('ol.ml-visually-hidden li')).toHaveCount(8);
  errors.expectNone();
});
