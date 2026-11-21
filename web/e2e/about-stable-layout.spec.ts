// Owner report of 2026-11-21: in the Info figures the narration wraps differently from step to step, so the figure "jumps" vertically.
// For every figure with a stepper, in both languages, at five viewports, after a language switch and after a resize: the height of the figure
// and the distance from its top to the stage are the same for every step. Texts are not compared.
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
const TOPICS = ['architecture', 'classical', 'inference', 'training', 'tuning'] as const;
const TOLERANCE = 0.5;

interface Sample {
  step: string;
  height: number;
  stageTop: number;
  statusHeight: number;
  /** Heights of the named parts of the V5 figure (empty for the other figures). */
  parts: Record<string, number>;
}

/** The parts of V5 that could change the figure height by themselves: each keeps its height from step to step (selector inside the figure). */
const AB_PARTS: Record<string, string> = {
  readout: '.ml-fig-ab2__read',
  overview: '.ml-fig-ab2__over [data-p="over"]',
  scrubber: '.ml-fig-ab2__scrub',
  counter: '.ml-fig-ab2__count',
  legend: '.ml-fig-legend',
  lower: '.ml-fig-ab2__lower',
  focus: '.ml-fig-ab2__focus',
  focusMove: '.ml-fig-ab2__move',
  gauge: '.ml-fig-ab2__focus [data-p="gauge"]',
  strip: '.ml-fig-ab2__strip',
  tally: '.ml-fig-ab2__tally',
  board: '.ml-fig-ab2__board',
  caption: 'figcaption',
};

/** Steps one stepper figure from step 0 to the last step and measures it at each step. */
async function stepThrough(fig: Locator): Promise<Sample[]> {
  return fig.evaluate(async (el, partSelectors): Promise<Sample[]> => {
    const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
    const buttons = el.querySelectorAll<HTMLButtonElement>('.ml-fig-stepper button');
    const posText = (): string => (el.querySelector('.ml-fig-stepper__pos')?.textContent ?? '').replace(/\s+/g, ' ').trim();
    const read = (): Sample => {
      const box = el.getBoundingClientRect();
      const stage = el.querySelector('.ml-fig__stage')?.getBoundingClientRect();
      const status = el.querySelector('.ml-fig__status')?.getBoundingClientRect();
      const parts: Record<string, number> = {};
      for (const [name, selector] of Object.entries(partSelectors)) {
        const found = el.querySelector(selector);
        if (found) parts[name] = found.getBoundingClientRect().height;
      }
      return { step: posText(), height: box.height, stageTop: (stage?.top ?? 0) - box.top, statusHeight: status?.height ?? 0, parts };
    };
    buttons[0]!.click(); // reset: step 0
    await frames();
    const seen: Sample[] = [read()];
    for (let guard = 0; guard < 80; guard++) {
      const m = /(\d+) \/ (\d+)/.exec(posText());
      if (!m || Number(m[1]) >= Number(m[2])) break;
      buttons[buttons.length - 1]!.click(); // forward
      await frames();
      seen.push(read());
    }
    return seen;
  }, AB_PARTS);
}

/** Steps every stepper figure of the page from step 0 to the last step and measures it at each step. */
async function measure(page: Page): Promise<Record<string, Sample[]>> {
  await page.locator('figure.ml-fig').first().waitFor();
  await page.evaluate(() => document.fonts.ready);
  const ids = await page.locator('figure.ml-fig:has(.ml-fig-stepper)').evaluateAll((els) => els.map((e) => e.id));
  const out: Record<string, Sample[]> = {};
  for (const id of ids) {
    const fig = page.locator(`figure#${id}`);
    await fig.scrollIntoViewIfNeeded();
    // The stepping runs inside the page (a DOM click on the buttons, two animation frames after each), because 50 Playwright clicks per figure
    // in four passes are too slow in Firefox. A DOM click also reaches the aria-disabled reset button at step 0, which Playwright would wait for.
    const samples = await stepThrough(fig);
    out[id] = samples;
  }
  return out;
}

function expectStable(label: string, all: Record<string, Sample[]>): void {
  for (const [id, samples] of Object.entries(all)) {
    expect(samples.length, `${label} ${id}: steps seen`).toBeGreaterThan(2);
    const first = samples[0]!;
    for (const s of samples) {
      const where = `${label} ${id} step ${s.step}`;
      expect(Math.abs(s.height - first.height), `${where}: figure height ${s.height} vs ${first.height}`).toBeLessThanOrEqual(TOLERANCE);
      expect(Math.abs(s.stageTop - first.stageTop), `${where}: stage top ${s.stageTop} vs ${first.stageTop}`).toBeLessThanOrEqual(TOLERANCE);
      for (const [name, h] of Object.entries(s.parts)) {
        expect(Math.abs(h - (first.parts[name] ?? h)), `${where}: part ${name} height ${h} vs ${first.parts[name]}`).toBeLessThanOrEqual(TOLERANCE);
      }
      expect(Math.abs(s.statusHeight - first.statusHeight), `${where}: status height ${s.statusHeight} vs ${first.statusHeight}`).toBeLessThanOrEqual(TOLERANCE);
    }
  }
}

async function switchLanguage(page: Page, lang: 'en' | 'de'): Promise<void> {
  const button = page.locator(`.ml-header [data-language="${lang}"]`);
  await button.click();
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

for (const topic of TOPICS) {
  for (const [i, size] of SIZES.entries()) {
    test(`${topic} ${size.width}x${size.height}: every step has the same height (en, after switching to de, after a resize)`, async ({ page }) => {
      await page.setViewportSize(size);
      await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
      await openApp(page, 'en', `#/about/${topic}`);
      const en = await measure(page);
      if (topic !== 'tuning') expect(Object.keys(en).length, 'the topic has stepper figures').toBeGreaterThan(0);
      expectStable(`en ${size.width}`, en);
      await switchLanguage(page, 'de');
      expectStable(`de ${size.width}`, await measure(page));
      const other = SIZES[(i + 1) % SIZES.length]!;
      await page.setViewportSize(other);
      expectStable(`de resized to ${other.width}`, await measure(page));
      await switchLanguage(page, 'en');
      expectStable(`en resized to ${other.width}`, await measure(page));
    });
  }
}

// V5 (alpha-beta): the whole figure keeps its height from step to step in each example (mid-game and start position), in both languages, at
// five viewports, also after switching the example. The height may differ BETWEEN the examples.
for (const size of SIZES) {
  test(`fig-ab ${size.width}x${size.height}: the figure height is constant over the steps of each example (en, de, after switching)`, async ({ page }) => {
    await page.setViewportSize(size);
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
    await openApp(page, 'en', '#/about/classical');
    const fig = page.locator('figure#fig-ab');
    await fig.waitFor();
    await page.evaluate(() => document.fonts.ready);
    const pick = async (example: 'mid' | 'start'): Promise<void> => {
      await fig.scrollIntoViewIfNeeded();
      await fig.locator(`.ml-fig-seg input[value="${example}"]`).evaluate((el) => (el as HTMLInputElement).click());
      await expect(fig.locator('.ml-fig-ab2')).toHaveAttribute('data-example', example);
    };
    const order: ('mid' | 'start')[] = ['mid', 'start', 'mid', 'start'];
    for (const lang of ['en', 'de'] as const) {
      if (lang === 'de') await switchLanguage(page, 'de');
      for (const example of order) {
        await pick(example);
        await fig.scrollIntoViewIfNeeded();
        const samples = await stepThrough(fig);
        expectStable(`${lang} ${example} ${size.width}`, { 'fig-ab': samples });
        expect(samples.length, `${lang} ${example}: all steps seen`).toBeGreaterThan(14);
        expect(Object.keys(samples[0]!.parts).sort(), "every measured part exists in the figure").toEqual(Object.keys(AB_PARTS).sort());
      }
    }
  });
}
