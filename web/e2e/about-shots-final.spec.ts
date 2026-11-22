// Final screenshots of every Info route for the visual review. Not part of CI: it runs only when SHOT_DIR is set.
//   SHOT_DIR=/some/dir npm exec --no -- playwright test e2e/about-shots-final.spec.ts --project=chromium
// For every route (hub, seven topics, glossary), size, theme and language it writes SHOT_DIR/<route>-<w>x<h>-<theme>-<lang>.png (the
// viewport as a reader sees it first) and, when the page is taller than the viewport, SHOT_DIR/<route>-<w>x<h>-<theme>-<lang>-full.png.
import { expect, test } from '@playwright/test';
import { openApp } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR;
const ROUTES = [
  { id: 'hub', hash: '#/about' },
  { id: 'architecture', hash: '#/about/architecture' },
  { id: 'classical', hash: '#/about/classical' },
  { id: 'inference', hash: '#/about/inference' },
  { id: 'training', hash: '#/about/training' },
  { id: 'tuning', hash: '#/about/tuning' },
  { id: 'java', hash: '#/about/java' },
  { id: 'java-web', hash: '#/about/java-web' },
  { id: 'glossary', hash: '#/about/glossary' },
] as const;
const SIZES = [
  { width: 320, height: 568 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
  { width: 820, height: 1180 },
  { width: 1280, height: 800 },
] as const;

test.describe('about final screenshots', () => {
  test.skip(!SHOT_DIR, 'set SHOT_DIR to write the screenshots');

  for (const route of ROUTES) {
    for (const size of SIZES) {
      for (const theme of ['light', 'dark'] as const) {
        for (const language of ['de', 'en'] as const) {
          test(`${route.id} ${size.width}x${size.height} ${theme} ${language}`, async ({ page }) => {
            await page.setViewportSize(size);
            await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
            await page.addInitScript((t) => localStorage.setItem('ml.theme', t), theme);
            await openApp(page, language, route.hash);
            await expect(page.locator('h1')).toBeVisible();
            if (route.id !== 'hub') await expect(page.locator('main[data-topic]')).toHaveAttribute('data-topic', route.id);
            if (!['hub', 'glossary'].includes(route.id)) await expect(page.locator('figure.ml-fig').first()).toBeVisible();
            await page.evaluate(() => document.fonts.ready);
            const name = `${route.id}-${size.width}x${size.height}-${theme}-${language}`;
            await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, animations: 'disabled' });
            const tall = await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight + 1);
            if (tall) await page.screenshot({ path: `${SHOT_DIR}/${name}-full.png`, fullPage: true, animations: 'disabled' });
          });
        }
      }
    }
  }
});
