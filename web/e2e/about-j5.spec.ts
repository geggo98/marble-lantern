// Figure J5 (GPU overhead and batching, topic java, Fig. 6.5) in a browser: the stable-layout rule (no state of the 20 changes the height of the
// figure or of any part, in both languages), the compact (320 px) and wide (1280 px) layouts, keyboard use, reduced motion, light and dark
// with the contrast of the marks that carry the meaning, and the size of the targets. Texts are not compared, so the checks run in both
// languages. The design is in the design spec of figure J5 (sections 8 to 10); the jsdom checks are in src/about/figures/j5.dom.test.tsx.
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp } from './helpers';

type Size = { width: number; height: number };
const COMPACT: Size = { width: 320, height: 568 };
const PHONE: Size = { width: 390, height: 844 };
const TABLET: Size = { width: 820, height: 1180 };
const WIDE: Size = { width: 1280, height: 800 };
const TOLERANCE = 0.5;

async function open(page: Page, size: Size, opts: { lang?: 'en' | 'de'; scheme?: 'light' | 'dark'; motion?: 'reduce' | 'no-preference' } = {}): Promise<Locator> {
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: opts.scheme ?? 'light', reducedMotion: opts.motion ?? 'reduce' });
  await openApp(page, opts.lang ?? 'en', '#/about/java');
  const fig = page.locator('figure#fig-j5');
  await fig.scrollIntoViewIfNeeded();
  await page.evaluate(() => document.fonts.ready);
  await expect(fig).toBeVisible();
  return fig;
}

async function switchLanguage(page: Page, lang: 'en' | 'de'): Promise<void> {
  const button = page.locator(`.ml-header [data-language="${lang}"]`);
  await button.click();
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

interface Sample {
  state: string;
  height: number;
  stageTop: number;
  parts: Record<string, number>;
}

/** The parts of J5 whose height must not change with the state (selectors inside the figure). */
const PARTS: Record<string, string> = {
  head: '.ml-fig__head',
  panel: '.ml-fig__panel',
  callBar: '[data-part="call"]',
  readout: '[data-part="readout"]',
  verdict: '[data-part="verdict"]',
  plot: '[data-part="plot"]',
  table: '[data-part="table"]',
  phases: '[data-part="phases"]',
  splitCell: '[data-block="split"] .ml-fig-j5__stack',
  where: '[data-part="where"]',
  caption: 'figcaption',
};

/** Runs through all 20 states inside the page (a DOM click on the radios, a native value set on the slider) and measures each. */
function sweep(fig: Locator): Promise<Sample[]> {
  return fig.evaluate(async (el, parts): Promise<Sample[]> => {
    const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
    const groups = el.querySelectorAll<HTMLElement>('.ml-fig-seg');
    const radio = (group: number, index: number): HTMLInputElement => groups[group]!.querySelectorAll<HTMLInputElement>('input[type="radio"]')[index]!;
    const slider = el.querySelector<HTMLInputElement>('input[type="range"]')!;
    const setSlider = (i: number): void => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(slider, String(i));
      slider.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const out: Sample[] = [];
    for (let m = 0; m < 2; m++) {
      for (let n = 0; n < 2; n++) {
        for (let b = 0; b < 5; b++) {
          radio(0, m).click();
          radio(1, n).click();
          setSlider(b);
          await frames();
          const box = el.getBoundingClientRect();
          const stage = el.querySelector('.ml-fig__stage')!.getBoundingClientRect();
          const measured: Record<string, number> = {};
          for (const [name, selector] of Object.entries(parts)) {
            const found = el.querySelector(selector);
            if (found) measured[name] = found.getBoundingClientRect().height;
          }
          const d = (el as HTMLElement).dataset;
          out.push({ state: `${d.machine}-${d.net}-${d.batch}`, height: box.height, stageTop: stage.top - box.top, parts: measured });
        }
      }
    }
    return out;
  }, PARTS);
}

function expectStable(label: string, samples: Sample[]): void {
  expect(samples, `${label}: 20 states seen`).toHaveLength(20);
  expect(new Set(samples.map((s) => s.state)).size, `${label}: 20 different states`).toBe(20);
  const first = samples[0]!;
  for (const s of samples) {
    const where = `${label} ${s.state}`;
    for (const [name, h] of Object.entries(s.parts)) {
      expect(Math.abs(h - (first.parts[name] ?? h)), `${where}: part ${name} height ${h} vs ${first.parts[name]}`).toBeLessThanOrEqual(TOLERANCE);
    }
    expect(Math.abs(s.height - first.height), `${where}: figure height ${s.height} vs ${first.height}`).toBeLessThanOrEqual(TOLERANCE);
    expect(Math.abs(s.stageTop - first.stageTop), `${where}: stage top ${s.stageTop} vs ${first.stageTop}`).toBeLessThanOrEqual(TOLERANCE);
  }
}

for (const size of [COMPACT, PHONE, TABLET, WIDE]) {
  test.describe(`${size.width}x${size.height}`, () => {
    test('has no horizontal overflow, in light and dark, and no part wider than the figure', async ({ page }) => {
      for (const scheme of ['light', 'dark'] as const) {
        const fig = await open(page, size, { scheme });
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(overflow, `${scheme}: page overflow`).toBeLessThanOrEqual(0);
        const wide = await fig.evaluate((el) => {
          const box = el.getBoundingClientRect();
          return [...el.querySelectorAll<HTMLElement>('*')].filter((e) => e.getBoundingClientRect().right > box.right + 1 && !e.closest('.ml-visually-hidden')).map((e) => e.className || e.tagName);
        });
        expect(wide, `${scheme}: elements that leave the figure`).toEqual([]);
      }
    });

    test('every state has the same height, in English and after switching to German, and after a resize', async ({ page }) => {
      const fig = await open(page, size);
      expectStable(`${size.width} en`, await sweep(fig));
      await switchLanguage(page, 'de');
      expectStable(`${size.width} de`, await sweep(fig));
      const other = size.width > 600 ? COMPACT : WIDE;
      await page.setViewportSize(other);
      await page.waitForTimeout(100);
      expectStable(`${size.width} -> ${other.width} de`, await sweep(fig));
    });
  });
}

test.describe('compact and wide', () => {
  test('compact: no value labels in the plot, readout in 2 columns, blocks C and D stacked', async ({ page }) => {
    const fig = await open(page, COMPACT);
    await expect(fig.locator('svg.ml-fig-plot .ml-fig-plot__value')).toHaveCount(0);
    const g = await fig.evaluate((el) => {
      const cells = [...el.querySelectorAll<HTMLElement>('[data-cell]')].map((c) => c.getBoundingClientRect());
      const phases = el.querySelector('[data-part="phases"]')!.getBoundingClientRect();
      const split = el.querySelector('[data-block="split"]')!.getBoundingClientRect();
      const cards = [...el.querySelectorAll<HTMLElement>('[data-use]')].map((c) => c.getBoundingClientRect());
      return { cellTops: cells.map((c) => Math.round(c.top)), cellLefts: cells.map((c) => Math.round(c.left)), phasesBottom: phases.bottom, splitTop: split.top, cardTops: cards.map((c) => Math.round(c.top)) };
    });
    expect(new Set(g.cellTops).size, 'two rows of readout cells').toBe(2);
    expect(new Set(g.cellLefts).size, 'two columns of readout cells').toBe(2);
    expect(g.splitTop, 'split under the phases').toBeGreaterThanOrEqual(g.phasesBottom);
    expect(new Set(g.cardTops).size, 'cards stacked').toBe(3);
  });

  test('wide: value labels on all five dots, readout in one row, phases beside the split, three cards in a row', async ({ page }) => {
    const fig = await open(page, WIDE);
    await expect(fig.locator('svg.ml-fig-plot .ml-fig-plot__value')).toHaveCount(5);
    const g = await fig.evaluate((el) => {
      const cells = [...el.querySelectorAll<HTMLElement>('[data-cell]')].map((c) => Math.round(c.getBoundingClientRect().top));
      const phases = el.querySelector('[data-block="phases"]')!.getBoundingClientRect();
      const split = el.querySelector('[data-block="split"]')!.getBoundingClientRect();
      const cards = [...el.querySelectorAll<HTMLElement>('[data-use]')].map((c) => Math.round(c.getBoundingClientRect().top));
      const svg = el.querySelector<SVGSVGElement>('svg.ml-fig-plot')!;
      return { cells, phasesTop: phases.top, phasesRight: phases.right, splitTop: split.top, splitLeft: split.left, cards, scale: svg.getBoundingClientRect().width / svg.viewBox.baseVal.width };
    });
    expect(new Set(g.cells).size, 'one row of readout cells').toBe(1);
    expect(Math.abs(g.phasesTop - g.splitTop), 'phases and split side by side').toBeLessThanOrEqual(1);
    expect(g.splitLeft).toBeGreaterThanOrEqual(g.phasesRight - 1);
    expect(new Set(g.cards).size, 'cards in one row').toBe(1);
    // the wide view box (640 units) is drawn at one unit per pixel or larger: its 12 unit type stays readable
    expect(g.scale * 12).toBeGreaterThanOrEqual(11);
  });

  test('the value labels of the wide plot do not overlap each other, in all 20 states', async ({ page }) => {
    const fig = await open(page, WIDE);
    const overlaps = await fig.evaluate(async (el) => {
      const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
      const groups = el.querySelectorAll<HTMLElement>('.ml-fig-seg');
      const slider = el.querySelector<HTMLInputElement>('input[type="range"]')!;
      const found: string[] = [];
      for (let m = 0; m < 2; m++)
        for (let n = 0; n < 2; n++)
          for (let b = 0; b < 5; b++) {
            groups[0]!.querySelectorAll<HTMLInputElement>('input')[m]!.click();
            groups[1]!.querySelectorAll<HTMLInputElement>('input')[n]!.click();
            Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(slider, String(b));
            slider.dispatchEvent(new Event('input', { bubbles: true }));
            await frames();
            const boxes = [...el.querySelectorAll<SVGTextElement>('svg.ml-fig-plot .ml-fig-plot__value')].map((t) => t.getBoundingClientRect());
            for (let i = 0; i < boxes.length; i++)
              for (let j = i + 1; j < boxes.length; j++) {
                const a = boxes[i]!;
                const c = boxes[j]!;
                if (a.left < c.right && c.left < a.right && a.top < c.bottom && c.top < a.bottom) found.push(`m${m} n${n} b${b}: label ${i} meets ${j}`);
              }
          }
      return found;
    });
    expect(overlaps).toEqual([]);
  });
});

test.describe('keyboard', () => {
  const describeFocus = (page: Page): Promise<string> =>
    page.evaluate(() => {
      const a = document.activeElement as HTMLElement | null;
      if (!a) return 'none';
      if (a instanceof HTMLInputElement && a.type === 'radio') return `radio:${a.value}`;
      if (a instanceof HTMLInputElement) return a.type;
      if (a instanceof HTMLAnchorElement) return `a:${a.getAttribute('href')}`;
      return a.tagName.toLowerCase();
    });

  test('the tab order is machine, network, presets, slider, the link of the pool card', async ({ page }) => {
    const fig = await open(page, WIDE);
    await fig.locator('input[type="radio"]').first().focus();
    expect(await describeFocus(page)).toBe('radio:mac');
    const seen: string[] = [];
    for (let i = 0; i < 5; i++) {
      await page.keyboard.press('Tab');
      seen.push(await describeFocus(page));
    }
    expect(seen).toEqual(['radio:SMALL', 'button', 'button', 'range', 'a:#/about/training/throughput']);
  });

  test('the slider: Right and Left move one batch size, End and Home go to 1,024 and 1', async ({ page }) => {
    const fig = await open(page, WIDE);
    const batch = (): Promise<string | null> => fig.getAttribute('data-batch');
    await fig.locator('input[type="range"]').focus();
    expect(await batch()).toBe('1');
    await page.keyboard.press('ArrowRight');
    expect(await batch()).toBe('16');
    await page.keyboard.press('ArrowRight');
    expect(await batch()).toBe('64');
    await page.keyboard.press('ArrowLeft');
    expect(await batch()).toBe('16');
    await page.keyboard.press('End');
    expect(await batch()).toBe('1024');
    await page.keyboard.press('Home');
    expect(await batch()).toBe('1');
    await expect(fig.locator('input[type="range"]')).toHaveAttribute('aria-valuetext', /.+/);
  });

  test('the machine switch takes the arrow keys and keeps the batch; the presets are buttons that take Enter and Space', async ({ page }) => {
    const fig = await open(page, WIDE);
    const presets = fig.locator('.ml-fig-presets button');
    await presets.nth(1).focus();
    await page.keyboard.press('Enter');
    await expect(fig).toHaveAttribute('data-batch', '256');
    await expect(presets.nth(1)).toHaveAttribute('aria-pressed', 'true');
    await expect(presets.nth(0)).toHaveAttribute('aria-pressed', 'false');
    await fig.locator('input[type="radio"]').first().focus();
    await page.keyboard.press('ArrowRight');
    await expect(fig).toHaveAttribute('data-machine', 't4');
    await expect(fig).toHaveAttribute('data-batch', '256');
    await presets.nth(0).focus();
    await page.keyboard.press('Space');
    await expect(fig).toHaveAttribute('data-batch', '1');
    await expect(presets.nth(0)).toHaveAttribute('aria-pressed', 'true');
  });

  test('nothing in the stage takes the focus except the link of the pool card', async ({ page }) => {
    const fig = await open(page, WIDE);
    const stops = await fig.locator('.ml-fig__stage').evaluate((el) =>
      [...el.querySelectorAll<HTMLElement>('a[href], button, input, select, textarea, [tabindex]')].filter((e) => !e.closest('[aria-hidden="true"]')).map((e) => e.getAttribute('href') ?? e.tagName),
    );
    expect(stops).toEqual(['#/about/training/throughput']);
  });

  test('the link of the pool card opens the training topic at its throughput section, where Fig. 4.3 stands', async ({ page }) => {
    const fig = await open(page, WIDE);
    await fig.locator('[data-use="pool"] a').click();
    await expect(page).toHaveURL(/#\/about\/training\/throughput$/);
    await expect(page.locator('#fig-pooled')).toBeVisible();
  });
});

test.describe('motion', () => {
  const duration = (fig: Locator): Promise<number> =>
    fig.locator('[data-part="call"] [data-seg="work"]').evaluate((el) => {
      const d = getComputedStyle(el).transitionDuration.split(',')[0]!.trim();
      return d.endsWith('ms') ? parseFloat(d) / 1000 : parseFloat(d);
    });

  test('with reduced motion the segments change at once; without it they take the base duration', async ({ page }) => {
    const reduced = await open(page, WIDE, { motion: 'reduce' });
    expect(await duration(reduced)).toBe(0);
    const normal = await open(page, WIDE, { motion: 'no-preference' });
    expect(await duration(normal)).toBeGreaterThan(0);
  });

  test('with reduced motion a change of the batch is in the DOM and on the screen after one frame', async ({ page }) => {
    const fig = await open(page, WIDE, { motion: 'reduce' });
    const width = (seg: string): Promise<number> => fig.locator(`[data-part="call"] [data-seg="${seg}"]`).evaluate((el) => el.getBoundingClientRect().width);
    const before = await width('work');
    await fig.locator('.ml-fig-presets button').nth(1).click();
    await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
    expect(await width('work')).toBeGreaterThan(before * 5);
  });
});

test.describe('colour and contrast', () => {
  for (const scheme of ['light', 'dark'] as const) {
    test(`${scheme}: the marks that carry the meaning reach 3:1, the text 4.5:1, and the patterns stay`, async ({ page }) => {
      const fig = await open(page, WIDE, { scheme });
      const r = await fig.evaluate((el) => {
        const rgb = (css: string): [number, number, number] => {
          const probe = document.createElement('span');
          probe.style.color = css;
          el.appendChild(probe);
          const m = /rgba?\((\d+)[ ,]+(\d+)[ ,]+(\d+)/.exec(getComputedStyle(probe).color)!;
          probe.remove();
          return [Number(m[1]), Number(m[2]), Number(m[3])];
        };
        const lum = ([r, g, b]: [number, number, number]): number => {
          const c = (v: number): number => ((v / 255) <= 0.03928 ? v / 255 / 12.92 : (((v / 255) + 0.055) / 1.055) ** 2.4);
          return 0.2126 * c(r) + 0.7152 * c(g) + 0.0722 * c(b);
        };
        const ratio = (a: string, b: string): number => {
          const [x, y] = [lum(rgb(a)), lum(rgb(b))];
          return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
        };
        const cs = (selector: string): CSSStyleDeclaration => getComputedStyle(el.querySelector(selector)!);
        const bg = cs('.ml-fig-share__bar').backgroundColor;
        return {
          hatchOnGround: ratio('var(--ml-fig-accent)', 'var(--ml-fig-accent-bg)'),
          dotsOnGround: ratio('var(--ml-fig-alt)', 'var(--ml-fig-bg)'),
          ringOnGround: ratio(cs('.ml-fig-j5__ring').stroke, 'var(--ml-fig-bg)'),
          barOutline: ratio(cs('.ml-fig-share__bar').borderTopColor, bg),
          inkOnVerdict: ratio('var(--ml-color-ink)', 'var(--ml-fig-panel-bg)'),
          inkOnCurrentRow: ratio('var(--ml-color-ink)', 'var(--ml-fig-accent-bg)'),
          workFill: cs('[data-part="call"] [data-seg="work"]').backgroundColor,
          restImage: cs('[data-part="call"] [data-seg="rest"]').backgroundImage,
          bg,
        };
      });
      expect(r.hatchOnGround).toBeGreaterThanOrEqual(3);
      expect(r.dotsOnGround).toBeGreaterThanOrEqual(3);
      expect(r.ringOnGround).toBeGreaterThanOrEqual(3);
      expect(r.barOutline).toBeGreaterThanOrEqual(3);
      expect(r.inkOnVerdict).toBeGreaterThanOrEqual(4.5);
      expect(r.inkOnCurrentRow).toBeGreaterThanOrEqual(4.5);
      // second cue besides colour: work is a solid fill, rest a diagonal hatch
      expect(r.workFill).not.toBe(r.bg);
      expect(r.restImage).toContain('repeating-linear-gradient');
    });
  }
});

test.describe('targets and structure', () => {
  for (const size of [COMPACT, WIDE]) {
    test(`${size.width}: every control and the link are at least 44 x 44 px`, async ({ page }) => {
      const fig = await open(page, size);
      const small = await fig.evaluate((el) => {
        const items = [
          ...el.querySelectorAll<HTMLElement>('.ml-fig-seg__opt'),
          ...el.querySelectorAll<HTMLElement>('.ml-fig-presets button'),
          ...el.querySelectorAll<HTMLElement>('input[type="range"]'),
          ...el.querySelectorAll<HTMLElement>('[data-use] a'),
        ];
        return items.map((e) => ({ what: e.className || e.tagName, w: e.getBoundingClientRect().width, h: e.getBoundingClientRect().height })).filter((s) => s.w < 43.5 || s.h < 43.5);
      });
      expect(small).toEqual([]);
    });
  }

  test('the hidden variants stay out of the accessibility tree and carry no hooks of their own', async ({ page }) => {
    const fig = await open(page, WIDE);
    expect(await fig.locator('[data-part="verdict"] p:not([aria-hidden="true"])').count()).toBe(1);
    expect(await fig.locator('[data-part="split"]').count()).toBe(1);
    expect(await fig.locator('[data-block="split"] .ml-fig-j5__stack > [aria-hidden="true"]').count()).toBeGreaterThanOrEqual(2);
    expect(await fig.locator('[role="status"], [aria-live]').count()).toBe(0);
  });

  test('the data of the first state: 510 µs call, 98 % rest, empty split, batch 1 and the one-search card marked', async ({ page }) => {
    const fig = await open(page, WIDE);
    await expect(fig).toHaveAttribute('data-verdict', 'dominates');
    await expect(fig.locator('[data-part="call"]')).toHaveAttribute('data-rest-pct', '98');
    await expect(fig.locator('[data-part="split"]')).toHaveAttribute('data-empty', '');
    await expect(fig.locator('[data-use="one"]')).toHaveAttribute('data-active', '');
    await expect(fig.locator('[data-use="pool"]')).not.toHaveAttribute('data-active', '');
    await expect(fig.locator('[data-part="table"] tbody tr')).toHaveCount(5);
  });
});
