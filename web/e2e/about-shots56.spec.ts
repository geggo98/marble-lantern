// Screenshots of the Info topics for the visual review. Not part of CI: it runs only when SHOT_DIR is set.
//   SHOT_DIR=/some/dir npm exec --no -- playwright test e2e/about-shots.spec.ts --project=chromium
// For each topic, size, theme and language it writes SHOT_DIR/<topic>-<w>x<h>-<theme>-<lang>.png (the whole page), and for every
// figure of the topic SHOT_DIR/<topic>-<w>x<h>-<theme>-<lang>-<figure id>.png (the figure alone). It also checks the one thing a
// screenshot cannot say: no horizontal page scroll. SHOT_TOPICS=tuning,glossary limits the topics.
import { expect, test } from '@playwright/test';
import { openApp } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR;
const TOPICS = (process.env.SHOT_TOPICS ?? 'tuning,glossary').split(',');
const SIZES = [
  { width: 320, height: 568 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
  { width: 820, height: 1180 },
] as const;

test.describe('about screenshots', () => {
  test.skip(!SHOT_DIR, 'set SHOT_DIR to write the screenshots');

  for (const topic of TOPICS) {
    for (const size of SIZES) {
      for (const theme of ['light', 'dark'] as const) {
        for (const language of ['de', 'en'] as const) {
          test(`${topic} ${size.width}x${size.height} ${theme} ${language}`, async ({ page }) => {
            page.on('pageerror', (error) => console.log(`[pageerror] ${error.message}`));
            page.on('console', (message) => message.type() === 'error' && console.log(`[console] ${message.text()}`));
            await page.setViewportSize(size);
            await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
            await openApp(page, language, `#/about/${topic}`);
            await expect(page.locator('h1')).toBeVisible();
            await expect(page.locator('main[data-topic]')).toHaveAttribute('data-topic', topic);
            const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
            expect(overflow, 'horizontal page scroll').toBeLessThanOrEqual(0);
            const name = `${topic}-${size.width}x${size.height}-${theme}-${language}`;
            await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true, animations: 'disabled' });
            for (const figure of await page.locator('figure.ml-fig[id]').all()) {
              const id = (await figure.getAttribute('id')) ?? 'figure';
              const inner = await figure.evaluate((el) => el.scrollWidth - el.clientWidth);
              expect(inner, `${id} overflows its frame`).toBeLessThanOrEqual(0);
              await figure.scrollIntoViewIfNeeded();
              await figure.screenshot({ path: `${SHOT_DIR}/${name}-${id}.png`, animations: 'disabled' });
            }
          });
        }
      }
    }
  }
});
