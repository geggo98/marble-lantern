// Figure J2 (plain Java, Fig. 6.2 of the topic "java") in a browser: layout at 320 px and 1280 px, the stable-layout rule (narration with ghosts in one
// grid cell, one cell for the slider and the stepper), keyboard use, reduced motion, light and dark colours with contrast measured on the screen.
// Counts and the walk are checked in jsdom (java-plain.dom.test.tsx); texts are not compared here, so the checks run in both languages.
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp } from './helpers';

type Size = { width: number; height: number };
const PHONE: Size = { width: 320, height: 568 };
const WIDE: Size = { width: 1280, height: 800 };
const SIZES: Size[] = [PHONE, { width: 390, height: 844 }, { width: 820, height: 1180 }, WIDE];
const VIEWS = ['move', 'undo', 'eval'] as const;
type View = (typeof VIEWS)[number];
const TOLERANCE = 0.5;

async function open(page: Page, size: Size, opts: { lang?: 'en' | 'de'; scheme?: 'light' | 'dark'; motion?: 'reduce' | 'no-preference' } = {}): Promise<Locator> {
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: opts.scheme ?? 'light', reducedMotion: opts.motion ?? 'reduce' });
  await openApp(page, opts.lang ?? 'en', '#/about/java');
  const fig = page.locator('figure#fig-javaplain');
  await fig.waitFor();
  await fig.scrollIntoViewIfNeeded();
  await page.evaluate(() => document.fonts.ready);
  await expect(fig).toBeVisible();
  return fig;
}

const chooseView = async (fig: Locator, view: View): Promise<void> => {
  await fig.locator(`.ml-fig-seg input[value="${view}"]`).evaluate((el) => (el as HTMLInputElement).click());
  await expect(fig).toHaveAttribute('data-view', view);
};

interface Sample {
  label: string;
  height: number;
  stageTop: number;
  statusHeight: number;
  panelHeight: number;
}

/** Steps through the walk (or the slider positions) inside the page and measures the figure after each change. */
async function walkThrough(fig: Locator, view: View): Promise<Sample[]> {
  return fig.evaluate(async (el, v): Promise<Sample[]> => {
    const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
    const read = (label: string): Sample => {
      const box = el.getBoundingClientRect();
      return {
        label,
        height: box.height,
        stageTop: (el.querySelector('.ml-fig__stage')?.getBoundingClientRect().top ?? 0) - box.top,
        statusHeight: el.querySelector('.ml-fig__status')?.getBoundingClientRect().height ?? 0,
        panelHeight: el.querySelector('.ml-fig__panel')?.getBoundingClientRect().height ?? 0,
      };
    };
    const out: Sample[] = [];
    if (v === 'move') {
      const slider = el.querySelector<HTMLInputElement>('input[type="range"]')!;
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
      for (const k of [1, 2, 34, 67, 1]) {
        set.call(slider, String(k));
        slider.dispatchEvent(new Event('input', { bubbles: true }));
        await frames();
        out.push(read(`pick ${k}`));
      }
      return out;
    }
    const buttons = el.querySelectorAll<HTMLButtonElement>('[data-ctl="step"] .ml-fig-stepper button');
    buttons[0]!.click();
    await frames();
    out.push(read('step 0'));
    for (let i = 1; i <= 6; i++) {
      buttons[buttons.length - 1]!.click();
      await frames();
      out.push(read(`step ${i}`));
    }
    return out;
  }, view);
}

function expectStable(where: string, samples: Sample[]): void {
  expect(samples.length, `${where}: samples`).toBeGreaterThan(4);
  const first = samples[0]!;
  for (const s of samples) {
    expect(Math.abs(s.height - first.height), `${where} ${s.label}: figure height ${s.height} vs ${first.height}`).toBeLessThanOrEqual(TOLERANCE);
    expect(Math.abs(s.stageTop - first.stageTop), `${where} ${s.label}: stage top ${s.stageTop} vs ${first.stageTop}`).toBeLessThanOrEqual(TOLERANCE);
    expect(Math.abs(s.statusHeight - first.statusHeight), `${where} ${s.label}: status ${s.statusHeight} vs ${first.statusHeight}`).toBeLessThanOrEqual(TOLERANCE);
    expect(Math.abs(s.panelHeight - first.panelHeight), `${where} ${s.label}: panel ${s.panelHeight} vs ${first.panelHeight}`).toBeLessThanOrEqual(TOLERANCE);
  }
}

async function switchLanguage(page: Page, lang: 'en' | 'de'): Promise<void> {
  const button = page.locator(`.ml-header [data-language="${lang}"]`);
  await button.click();
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

// ---------- stable layout (the rule of about-stable-layout.spec.ts, for the three views) ----------

for (const size of SIZES) {
  test(`stable layout ${size.width}x${size.height}: every step and every slider position has the same height, and the stage starts at the same place in all views (en, de, after a resize)`, async ({ page }) => {
    const fig = await open(page, size);
    const tops = new Set<number>();
    const check = async (label: string): Promise<void> => {
      const panel = new Set<number>();
      for (const view of VIEWS) {
        await chooseView(fig, view);
        await fig.scrollIntoViewIfNeeded();
        const samples = await walkThrough(fig, view);
        expectStable(`${label} ${view}`, samples);
        tops.add(Math.round(samples[0]!.stageTop));
        panel.add(Math.round(samples[0]!.statusHeight));
      }
      // The narration area is as high in all three views (the ghosts hold every sentence of both views), and so is the stage top.
      expect(panel.size, `${label}: the status area has one height in all views`).toBe(1);
      expect(tops.size, `${label}: the stage starts at one height in all views`).toBe(1);
    };
    await check(`en ${size.width}`);
    tops.clear();
    await switchLanguage(page, 'de');
    await check(`de ${size.width}`);
    tops.clear();
    const other = SIZES[(SIZES.indexOf(size) + 1) % SIZES.length]!;
    await page.setViewportSize(other);
    await check(`de resized to ${other.width}`);
  });
}

// ---------- compact and wide layout ----------

for (const size of [PHONE, WIDE]) {
  test(`layout ${size.width}: no horizontal overflow, lanes ${size.width < 640 ? 'stacked, objects first' : 'side by side'}, every control at least 44 px, in all views and both languages`, async ({ page }) => {
    const fig = await open(page, size);
    for (const lang of ['en', 'de'] as const) {
      if (lang === 'de') await switchLanguage(page, 'de');
      for (const view of VIEWS) {
        await chooseView(fig, view);
        const m = await fig.evaluate((el) => {
          const lanes = [...el.querySelectorAll<HTMLElement>('[data-lane]')].map((l) => l.getBoundingClientRect());
          const small = [...el.querySelectorAll<HTMLElement>('button, input[type="range"], .ml-fig-seg__opt, .ml-fig-chip')]
            .map((c) => ({ name: c.className || c.tagName, h: c.getBoundingClientRect().height, w: c.getBoundingClientRect().width }))
            .filter((c) => c.h < 43.5 || c.w < 43.5);
          const wide = [...el.querySelectorAll<HTMLElement>('*')].filter((e) => e.scrollWidth - e.clientWidth > 1 && getComputedStyle(e).overflowX !== 'visible' && e.closest('svg') === null && !e.classList.contains('ml-visually-hidden')).map((e) => e.className);
          return {
            lanes: lanes.map((r) => ({ top: r.top, bottom: r.bottom, left: r.left, width: r.width })),
            small,
            wide,
            figOverflow: el.scrollWidth - el.clientWidth,
            page: document.documentElement.scrollWidth - document.documentElement.clientWidth,
            maps: [...el.querySelectorAll<SVGSVGElement>('svg.ml-fig-mem')].map((s) => s.getBoundingClientRect().width),
          };
        });
        const where = `${lang} ${view} ${size.width}`;
        expect(m.page, `${where}: page overflow`).toBeLessThanOrEqual(0);
        expect(m.figOverflow, `${where}: figure overflow`).toBeLessThanOrEqual(0);
        expect(m.wide, `${where}: clipped boxes`).toEqual([]);
        expect(m.small, `${where}: controls below 44 px`).toEqual([]);
        expect(m.lanes).toHaveLength(2);
        const [a, b] = m.lanes;
        if (size.width < 640) {
          expect(b!.top, `${where}: second lane below the first`).toBeGreaterThanOrEqual(a!.bottom - 1);
          expect(Math.abs(a!.left - b!.left), `${where}: same left edge`).toBeLessThan(1);
        } else {
          expect(Math.abs(a!.top - b!.top), `${where}: lanes side by side`).toBeLessThan(1);
          expect(Math.abs(a!.width - b!.width), `${where}: equal widths`).toBeLessThan(1);
          expect(b!.left, `${where}: primitives right of objects`).toBeGreaterThan(a!.left);
        }
        if (view === 'move') {
          // one view box unit is at least a pixel at 320 (the map is about 246 px for 256 units) and the maps fill their lane
          for (const w of m.maps) expect(w, `${where}: map width`).toBeGreaterThan(size.width < 640 ? 230 : 280);
        }
      }
    }
  });
}

test('the compact layout puts the lane with objects before the lane with primitives, and the primitives lane carries the solid frame', async ({ page }) => {
  const fig = await open(page, PHONE);
  const order = await fig.locator('[data-lane]').evaluateAll((els) => els.map((e) => [e.getAttribute('data-lane'), getComputedStyle(e).borderTopStyle, getComputedStyle(e).borderInlineStartWidth]));
  expect(order).toEqual([
    ['objects', 'dashed', '1px'],
    ['primitives', 'solid', '4px'],
  ]);
});

// ---------- keyboard ----------

test('keyboard: the view switch with the arrow keys, Tab to the slider or to the stepper (never to the inert one), Left, Right, Home and End', async ({ page }) => {
  const fig = await open(page, WIDE);
  const focusedIn = (selector: string): Promise<boolean> => page.evaluate((s) => !!document.activeElement?.closest(s), selector);
  await fig.locator('.ml-fig-seg input:checked').focus();
  await page.keyboard.press('Tab');
  expect(await focusedIn('[data-ctl="pick"]'), 'Tab from the switch lands on the slider in the view move').toBe(true);
  await page.keyboard.press('ArrowRight');
  await expect(fig).toHaveAttribute('data-pick', '2');
  await page.keyboard.press('End');
  await expect(fig).toHaveAttribute('data-pick', '67');
  await page.keyboard.press('Home');
  await expect(fig).toHaveAttribute('data-pick', '1');
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('ArrowRight'); // the radio group selects the next view
  await expect(fig).toHaveAttribute('data-view', 'undo');
  await page.keyboard.press('Tab');
  expect(await focusedIn('[data-ctl="step"]'), 'Tab from the switch lands on the stepper in the view undo').toBe(true);
  expect(await page.evaluate(() => document.activeElement?.closest('[data-ctl="pick"]') === null)).toBe(true);
  await page.keyboard.press('Tab'); // reset button is aria-disabled but focusable: move to the back button, then forward
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  await expect(fig).toHaveAttribute('data-step', '2');
  await page.keyboard.press('ArrowLeft');
  await expect(fig).toHaveAttribute('data-step', '1');
  await page.keyboard.press('End');
  await expect(fig).toHaveAttribute('data-step', '6');
  await page.keyboard.press('Home');
  await expect(fig).toHaveAttribute('data-step', '0'); // the root is a state of this walk
  await expect(fig.locator('.ml-fig-stepper__pos')).toContainText('1 / 7');
});

test('keyboard: the step is shared by the views undo and evaluation, and a change of the view keeps it', async ({ page }) => {
  const fig = await open(page, WIDE);
  await chooseView(fig, 'undo');
  const group = fig.locator('[data-ctl="step"] .ml-fig-stepper');
  await group.locator('button').last().click();
  await group.locator('button').last().click();
  await group.locator('button').last().click();
  await expect(fig).toHaveAttribute('data-step', '3');
  await chooseView(fig, 'eval');
  await expect(fig).toHaveAttribute('data-step', '3');
  await expect(fig.locator('[data-narr]')).toHaveAttribute('data-narr', 'fig.javaplain.narr.eval.unmake');
  await chooseView(fig, 'move');
  await expect(fig).toHaveAttribute('data-step', '3');
});

test('the control that is not in use is hidden and inert; the one in use is visible', async ({ page }) => {
  const fig = await open(page, PHONE);
  await expect(fig.locator('[data-ctl="pick"]')).toBeVisible();
  await expect(fig.locator('[data-ctl="step"]')).toBeHidden();
  await expect(fig.locator('[data-ctl="step"]')).toHaveAttribute('inert', '');
  await chooseView(fig, 'eval');
  await expect(fig.locator('[data-ctl="pick"]')).toBeHidden();
  await expect(fig.locator('[data-ctl="step"]')).toBeVisible();
});

// ---------- reduced motion ----------

test('reduced motion: the play button is not rendered', async ({ page }) => {
  const fig = await open(page, WIDE, { motion: 'reduce' });
  await chooseView(fig, 'undo');
  await expect(fig.locator('[data-ctl="step"] .ml-fig-stepper button')).toHaveCount(3); // reset, back, forward
});

test('motion allowed: play steps through the walk, switches the live region off while it plays and ends at the last state', async ({ page }) => {
  const fig = await open(page, WIDE, { motion: 'no-preference' });
  await chooseView(fig, 'undo');
  const buttons = fig.locator('[data-ctl="step"] .ml-fig-stepper button');
  await expect(buttons).toHaveCount(4);
  await buttons.nth(2).click(); // play
  await expect(fig.locator('[data-narr]')).toHaveAttribute('aria-live', 'off');
  await expect(fig).toHaveAttribute('data-step', '1', { timeout: 5000 });
  await expect(fig).toHaveAttribute('data-step', '6', { timeout: 15000 });
  await expect(fig.locator('[data-narr]')).toHaveAttribute('aria-live', 'polite');
  // Space on the play button starts it again from the first state
  await buttons.nth(2).focus();
  await page.keyboard.press('Space');
  await expect(fig).toHaveAttribute('data-step', /^[01]$/);
  await page.keyboard.press('Space'); // pause
});

// ---------- light and dark: colours measured on the screen ----------

interface Probe {
  view: View;
  step: number;
  selector: string;
  /** `color` for text, `fill` and `stroke` for marks; border colours for the frames. */
  part: 'color' | 'fill' | 'stroke' | 'border';
  min: number;
}

const PROBES: Probe[] = [
  { view: 'move', step: 0, selector: '.ml-fig-cmp__code', part: 'color', min: 4.5 },
  { view: 'move', step: 0, selector: '.ml-fig-cmp__lane--ours .ml-fig-cmp__tag', part: 'color', min: 4.5 },
  { view: 'move', step: 0, selector: '.ml-fig-cmp__lane:not(.ml-fig-cmp__lane--ours) .ml-fig-cmp__tag', part: 'color', min: 4.5 },
  { view: 'move', step: 0, selector: '.ml-fig-cmp .ml-fig-readout dt', part: 'color', min: 4.5 },
  { view: 'move', step: 0, selector: '.ml-fig-jp__f--val', part: 'color', min: 4.5 },
  { view: 'move', step: 0, selector: '.ml-fig-jp__f--hdr', part: 'color', min: 4.5 },
  { view: 'move', step: 0, selector: '.ml-fig-jp__f--pad', part: 'color', min: 4.5 },
  { view: 'move', step: 0, selector: '.ml-fig-jp__eq b', part: 'color', min: 4.5 },
  { view: 'move', step: 0, selector: '.ml-fig-jp__f--val', part: 'border', min: 3 },
  { view: 'move', step: 0, selector: 'svg.ml-fig-mem .w-val:not(.is-sel)', part: 'fill', min: 3 },
  { view: 'move', step: 0, selector: 'svg.ml-fig-mem .w-hdr:not(.is-sel)', part: 'fill', min: 3 },
  { view: 'move', step: 0, selector: 'svg.ml-fig-mem .w-ref:not(.is-sel)', part: 'stroke', min: 3 },
  { view: 'move', step: 0, selector: 'svg.ml-fig-mem .w-free', part: 'stroke', min: 3 },
  { view: 'move', step: 0, selector: 'svg.ml-fig-mem .is-sel.w-val', part: 'fill', min: 3 },
  { view: 'move', step: 0, selector: 'svg.ml-fig-mem .sel-box', part: 'stroke', min: 3 },
  { view: 'move', step: 0, selector: 'svg.ml-fig-mem .hop', part: 'stroke', min: 3 },
  { view: 'move', step: 0, selector: '.ml-fig-jp__measured .ml-fig-readout__cell[data-kind="none"] .ml-fig-readout__value', part: 'color', min: 4.5 },
  { view: 'move', step: 0, selector: '.ml-fig-jp__measured small', part: 'color', min: 4.5 },
  { view: 'undo', step: 2, selector: '.ml-fig-jp__arr td.is-write', part: 'color', min: 4.5 },
  { view: 'undo', step: 2, selector: '.ml-fig-jp__arr td.is-write', part: 'border', min: 3 },
  { view: 'undo', step: 2, selector: '.ml-fig-jp__arr td.is-live', part: 'color', min: 4.5 },
  { view: 'undo', step: 2, selector: '.ml-fig-jp__arr td[data-state="empty"]', part: 'color', min: 4.5 },
  { view: 'undo', step: 2, selector: '.ml-fig-jp__arr td[data-state="empty"]', part: 'border', min: 3 },
  { view: 'undo', step: 6, selector: '.ml-fig-jp__arr td.is-stale', part: 'color', min: 4.5 },
  { view: 'undo', step: 6, selector: '.ml-fig-jp__arr td.is-stale', part: 'border', min: 3 },
  { view: 'undo', step: 6, selector: '.ml-fig-jp__obj.is-dead', part: 'color', min: 4.5 },
  { view: 'undo', step: 6, selector: '.ml-fig-jp__obj.is-dead', part: 'border', min: 3 },
  { view: 'undo', step: 2, selector: '.ml-fig-jp__obj.is-new', part: 'color', min: 4.5 },
  { view: 'undo', step: 2, selector: '.ml-fig-jp__obj.is-live', part: 'color', min: 4.5 },
  { view: 'undo', step: 2, selector: '.ml-fig-jp__pilehead', part: 'color', min: 4.5 },
  { view: 'eval', step: 1, selector: '.ml-fig-jp__pc.is-changed', part: 'color', min: 4.5 },
  { view: 'eval', step: 1, selector: '.ml-fig-jp__pc.is-changed', part: 'border', min: 3 },
  { view: 'eval', step: 1, selector: '.ml-fig-jp__pc:not(.is-changed)', part: 'color', min: 4.5 },
  { view: 'eval', step: 1, selector: '.ml-fig-jp__pc:not(.is-changed)', part: 'border', min: 3 },
  { view: 'eval', step: 2, selector: '.ml-fig-jp__pc.is-read', part: 'border', min: 3 },
  { view: 'eval', step: 2, selector: '.ml-fig-jp__frame.is-write', part: 'color', min: 4.5 },
  { view: 'eval', step: 2, selector: '.ml-fig-jp__frame.is-live', part: 'color', min: 4.5 },
  { view: 'eval', step: 2, selector: '.ml-fig-jp__frame[data-state="empty"]', part: 'color', min: 4.5 },
  { view: 'eval', step: 2, selector: '.ml-fig-jp__side > span:first-child', part: 'color', min: 4.5 },
  { view: 'eval', step: 2, selector: '.ml-fig-jp__measured .ml-fig-readout__value', part: 'color', min: 4.5 },
];

/** WCAG 2 contrast ratio, computed in the page from the colours the browser resolved. */
const contrastScript = ({ selector, part }: { selector: string; part: Probe['part'] }): { ratio: number; fg: string; bg: string } => {
  const parse = (css: string): [number, number, number, number] => {
    const m = /rgba?\(([^)]+)\)/.exec(css);
    if (!m) throw new Error(`cannot parse ${css}`);
    const p = m[1]!.split(/[ ,/]+/).filter(Boolean).map(Number);
    return [p[0]!, p[1]!, p[2]!, p[3] ?? 1];
  };
  const lum = ([r, g, b]: number[]): number => {
    const c = (v: number): number => {
      const s = v / 255;
      return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * c(r!) + 0.7152 * c(g!) + 0.0722 * c(b!);
  };
  const el = document.querySelector(`#fig-javaplain ${selector}`);
  if (!el) throw new Error(`no ${selector}`);
  const style = getComputedStyle(el);
  const fgCss = part === 'color' ? style.color : part === 'border' ? style.borderTopColor : part === 'fill' ? style.fill : style.stroke;
  // the background: the nearest ancestor (or the element itself, for text on its own fill) with a visible background colour
  let bg: [number, number, number, number] = [255, 255, 255, 1];
  for (let n: Element | null = part === 'color' ? el : el.parentElement; n; n = n.parentElement) {
    const c = parse(getComputedStyle(n).backgroundColor);
    if (c[3] > 0) {
      bg = c;
      break;
    }
  }
  const fg = parse(fgCss);
  const over = (a: number[], b: number[], alpha: number): number[] => [0, 1, 2].map((i) => a[i]! * alpha + b[i]! * (1 - alpha));
  const solid = over(fg, bg, fg[3]);
  const l1 = lum(solid);
  const l2 = lum(bg);
  return { ratio: (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05), fg: fgCss, bg: `rgb(${bg.slice(0, 3).join(',')})` };
};

for (const scheme of ['light', 'dark'] as const) {
  test(`${scheme}: text reaches 4.5:1 and marks and borders 3:1 on their background, in every view`, async ({ page }) => {
    const fig = await open(page, WIDE, { scheme });
    const failures: string[] = [];
    let measured = 0;
    for (const view of VIEWS) {
      await chooseView(fig, view);
      if (view !== 'move') {
        for (const stepTo of [...new Set(PROBES.filter((p) => p.view === view).map((p) => p.step))].sort((a, b) => a - b)) {
          await fig.evaluate(
            async (el, target) => {
              const bs = el.querySelectorAll<HTMLButtonElement>('[data-ctl="step"] .ml-fig-stepper button');
              bs[0]!.click();
              for (let i = 0; i < target; i++) bs[bs.length - 1]!.click();
            },
            stepTo,
          );
          await expect(fig).toHaveAttribute('data-step', String(stepTo));
          for (const p of PROBES.filter((x) => x.view === view && x.step === stepTo)) {
            const r = await page.evaluate(contrastScript, { selector: p.selector, part: p.part });
            measured++;
            if (r.ratio < p.min) failures.push(`${view} step ${stepTo} ${p.selector} ${p.part}: ${r.ratio.toFixed(2)} < ${p.min} (${r.fg} on ${r.bg})`);
          }
        }
      } else {
        for (const p of PROBES.filter((x) => x.view === 'move')) {
          const r = await page.evaluate(contrastScript, { selector: p.selector, part: p.part });
          measured++;
          if (r.ratio < p.min) failures.push(`${view} ${p.selector} ${p.part}: ${r.ratio.toFixed(2)} < ${p.min} (${r.fg} on ${r.bg})`);
        }
      }
    }
    expect(measured, 'every probe ran').toBe(PROBES.length);
    expect(failures).toEqual([]);
  });
}

test('dark: the figure paints on the dark surface, not on the light one', async ({ page }) => {
  const fig = await open(page, PHONE, { scheme: 'dark' });
  const bg = await fig.evaluate((el) => getComputedStyle(el).backgroundColor);
  const [r, g, b] = bg.match(/\d+/g)!.map(Number);
  expect((r! + g! + b!) / 3).toBeLessThan(80);
});
