// Screenshots at 360 px (phone) and 1280 px (desktop) in German and English, written to test-results/screens/.
// They are the input of the visual review against web/design/; the test checks only that each page rendered a board
// and does not overflow horizontally.
import { expect, test } from '@playwright/test';
import { openApp, waitForHumanTurn } from './helpers';

const WIDTHS = [
  { name: '360', width: 360, height: 740 },
  { name: '1280', width: 1280, height: 900 },
] as const;

for (const language of ['de', 'en'] as const) {
  for (const size of WIDTHS) {
    test(`play screen at ${size.name} px in ${language}`, async ({ page }, info) => {
      await page.setViewportSize({ width: size.width, height: size.height });
      await openApp(page, language);
      await waitForHumanTurn(page);
      await expect(page.locator('svg [data-cell]').first()).toBeAttached();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, 'horizontal page scroll').toBeLessThanOrEqual(0);
      await page.screenshot({
        path: `test-results/screens/${info.project.name}-play-${size.name}-${language}.png`,
        fullPage: true,
        animations: 'disabled',
      });
    });
  }
}

test('about screen at 360 px in German', async ({ page }, info) => {
  await page.setViewportSize({ width: 360, height: 740 });
  await openApp(page, 'de', '#/about');
  await expect(page.locator('main')).toBeVisible();
  await page.screenshot({ path: `test-results/screens/${info.project.name}-about-360-de.png`, fullPage: true });
});
