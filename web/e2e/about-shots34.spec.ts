// Screenshots of the topics "inference" and "training" for a visual review. Not part of CI: the spec is skipped unless SHOT_DIR is set.
//   SHOT_DIR=/some/dir npx playwright test e2e/about-shots.spec.ts --project=chromium
// Writes SHOT_DIR/<topic>-<w>x<h>-<theme>-<lang>.png (the whole page) and, next to it, one crop per figure:
// SHOT_DIR/<topic>-<w>x<h>-<theme>-<lang>-<figure id>.png. It also checks, as the plan does (section 6.5), that neither the page nor a
// figure frame scrolls sideways. Every combination gets a fresh browser context: the theme and the language are read at page start.
import { mkdirSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { openApp, watchPageErrors } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR;
const TOPICS = (process.env.SHOT_TOPICS ?? 'inference,training').split(',');
const SIZES = [
  { w: 320, h: 568 },
  { w: 390, h: 844 },
  { w: 844, h: 390 },
  { w: 820, h: 1180 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LANGS = ['en', 'de'] as const;

test.describe('about screenshots', () => {
  test.skip(!SHOT_DIR, 'set SHOT_DIR to write the screenshots');
  test.setTimeout(900_000);

  for (const topic of TOPICS) {
    test(`${topic}: every size, theme and language`, async ({ browser }, info) => {
      const baseURL = info.project.use.baseURL;
      mkdirSync(SHOT_DIR as string, { recursive: true });
      for (const theme of THEMES) {
        for (const lang of LANGS) {
          for (const { w, h } of SIZES) {
            const context = await browser.newContext({
              ...(baseURL ? { baseURL } : {}),
              viewport: { width: w, height: h },
              colorScheme: theme,
              reducedMotion: 'reduce',
              serviceWorkers: 'block',
            });
            const page = await context.newPage();
            const errors = watchPageErrors(page);
            await page.addInitScript((t) => localStorage.setItem('ml.theme', t), theme);
            await openApp(page, lang, `#/about/${topic}`);
            await expect(page.locator('h1')).toBeVisible();
            await expect(page.locator('.ml-fig').first()).toBeVisible();
            await page.evaluate(() => document.fonts.ready);
            const base = `${SHOT_DIR}/${topic}-${w}x${h}-${theme}-${lang}`;
            const over = await page.evaluate(() => ({
              page: document.documentElement.scrollWidth - window.innerWidth,
              figures: Array.from(document.querySelectorAll<HTMLElement>('.ml-fig')).map((f) => f.scrollWidth - f.clientWidth),
            }));
            expect(over.page, `${base}: the page scrolls sideways`).toBeLessThanOrEqual(0);
            for (const o of over.figures) expect(o, `${base}: a figure scrolls sideways`).toBeLessThanOrEqual(0);
            await page.screenshot({ path: `${base}.png`, fullPage: true });
            const figures = page.locator('figure.ml-fig');
            for (let i = 0; i < (await figures.count()); i++) {
              const id = (await figures.nth(i).getAttribute('id')) ?? `fig${i}`;
              await figures.nth(i).screenshot({ path: `${base}-${id}.png` });
            }
            errors.expectNone();
            await context.close();
          }
        }
      }
    });
  }
});
