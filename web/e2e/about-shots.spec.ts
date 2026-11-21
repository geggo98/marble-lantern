// Screenshots of the Info topics for the visual review. Not part of CI: the whole file is skipped unless SHOT_DIR names a directory.
//
//   SHOT_DIR=/some/dir npm exec -- playwright test e2e/about-shots.spec.ts --project=chromium
//
// For every topic of this package (architecture, classical) at 320x568, 390x844, 844x390 and 820x1180, in light and dark, in German and
// English, it writes the whole page to <SHOT_DIR>/<topic>-<w>x<h>-<theme>-<lang>.png and every figure to
// <SHOT_DIR>/<topic>-<w>x<h>-<theme>-<lang>-<figure id>.png. It also checks what a picture cannot show: no horizontal scroll of the
// page and of any figure frame.
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { openApp } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR;

const TOPICS = ['architecture', 'classical'] as const;
const VIEWPORTS = [
  { width: 320, height: 568 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
  { width: 820, height: 1180 },
] as const;

test.describe('about topics: screenshots', () => {
  test.skip(!SHOT_DIR, 'set SHOT_DIR to write the screenshots');
  test.setTimeout(180_000);

  for (const topic of TOPICS) {
    for (const viewport of VIEWPORTS) {
      for (const theme of ['light', 'dark'] as const) {
        for (const language of ['de', 'en'] as const) {
          const name = `${topic}-${viewport.width}x${viewport.height}-${theme}-${language}`;
          test(name, async ({ page }) => {
            const dir = SHOT_DIR!;
            mkdirSync(dir, { recursive: true });
            await page.setViewportSize(viewport);
            await page.emulateMedia({ colorScheme: theme });
            await openApp(page, language, `#/about/${topic}`);
            await expect(page.locator('main h1')).toBeVisible();
            await page.evaluate((value) => document.documentElement.setAttribute('data-theme', value), theme);
            await expect(page.locator('.ml-fig').first()).toBeAttached();
            // Let the figures measure themselves and the fonts settle.
            await page.evaluate(() => document.fonts.ready);
            await page.waitForTimeout(300);

            const overflow = await page.evaluate(() => {
              const root = document.scrollingElement!;
              const wide = Array.from(document.querySelectorAll<HTMLElement>('.ml-fig')).filter((el) => el.scrollWidth > el.clientWidth + 1).map((el) => el.id);
              return { page: root.scrollWidth - root.clientWidth, figures: wide };
            });
            expect(overflow.page, 'horizontal scroll of the page').toBeLessThanOrEqual(0);
            expect(overflow.figures, 'figure frames that scroll sideways').toEqual([]);

            await page.screenshot({ path: join(dir, `${name}.png`), fullPage: true, animations: 'disabled' });
            const figures = await page.locator('figure.ml-fig').all();
            for (const figure of figures) {
              const id = await figure.getAttribute('id');
              await figure.scrollIntoViewIfNeeded();
              await figure.screenshot({ path: join(dir, `${name}-${id}.png`), animations: 'disabled' });
            }
          });
        }
      }
    }
  }
});
