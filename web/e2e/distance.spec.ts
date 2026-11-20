// The distance of the result in a real browser (docs/web-ui.md, "Distance of the result"): a finished three-player game that the real
// engine plays out, and a finished two-player game that the solo race plays out. Only the shape of the line is asserted: the number
// depends on the engine build.
import { expect, test } from '@playwright/test';
import { PLAY_ON_WIN, TWO_PLAYER_WIN } from './fixtures';
import { openApp, watchPageErrors } from './helpers';

const THREE = `#g=v1.${PLAY_ON_WIN.moves}&l=0&p=3&s=hee`; // seat 2, an engine, has won
const TWO = `#g=v1.${TWO_PLAYER_WIN.moves}&h=1&l=2`; // side 0 has won; the person continues as side 1

test('three players: the engine plays the position out and the card shows an estimate for second place', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', THREE);
  await expect(page.locator('.ml-result')).toBeVisible();
  const line = page.locator('[data-result-distance]');
  await expect(line).toHaveAttribute('data-result-distance', 'done', { timeout: 120_000 });
  await expect(line).toHaveText(/^The other two sides would have needed (about|more than) \d+ more plies to decide second place \(estimate\)\.$/);
  errors.expectNone();
});

test('three players: "Play on" while the estimate runs stops it, and no error shows', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', THREE);
  await expect(page.locator('[data-result-distance]')).toBeVisible();
  await page.locator('[data-play-on]').click();
  await expect(page.locator('.ml-result')).toHaveCount(0);
  await expect(page.locator('[data-result-distance]')).toHaveCount(0);
  errors.expectNone();
});

test('two players: the solo race of the losing side gives an estimate', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', TWO);
  await page.locator('[data-choice="link-side-to-move"]').click();
  await expect(page.locator('.ml-result')).toBeVisible();
  const line = page.locator('[data-result-distance]');
  await expect(line).toHaveAttribute('data-result-distance', 'done', { timeout: 30_000 });
  await expect(line).toHaveText(/^The losing side would have needed (about|more than) \d+ more plies of its own to reach its goal \(estimate\)\.$/);
  errors.expectNone();
});
