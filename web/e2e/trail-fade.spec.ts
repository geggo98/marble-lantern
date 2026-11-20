// The trail of the last move fades out after the time stored for the viewer (docs/web-ui.md, "Fading move trail").
// The test stores 2 s, plays one exchange with the real engine, and expects the trail after the engine's reply and gone shortly after the time.
import { expect, test } from '@playwright/test';
import { openApp, playHumanMove, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

test('the trail of the engine move is visible after the move and gone after the stored time', async ({ page }) => {
  const errors = watchPageErrors(page);
  await page.addInitScript(() => {
    try {
      localStorage.setItem('ml.trail-seconds', '2');
    } catch {
      // the default of 3 s applies; the test still holds
    }
  });
  await openApp(page);
  await waitForHumanTurn(page);
  const before = await plyOf(page);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(before + 2);
  const trail = page.locator('.ml-trail');
  await expect(trail).toHaveCount(1);
  await expect(trail).toHaveAttribute('data-trail-phase', /^(shown|fading)$/);
  await expect(trail).toHaveCount(0, { timeout: 5_000 }); // 2 s visible + 0.6 s fade, with margin
  errors.expectNone();
});

test('"Always show" is a visible choice, is remembered, and keeps the trail on the board after the time of the slider has passed', async ({ page }) => {
  const errors = watchPageErrors(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.addInitScript(() => {
    try {
      if (localStorage.getItem('ml.trail-seconds') === null) localStorage.setItem('ml.trail-seconds', '1');
    } catch {
      // the default of 3 s applies; the test then waits a little longer
    }
  });
  await openApp(page);
  await waitForHumanTurn(page);
  const always = page.locator('[data-trail-mode-input="always"]');
  const timed = page.locator('[data-trail-mode-input="timed"]');
  await expect(timed).toBeChecked();
  await expect(page.locator('[data-trail-setting]')).toBeVisible();
  await expect(page.locator('[data-trail-card] .ml-trail-card__mode', { hasText: /always/i })).toBeVisible(); // named, not a hidden 0 on a slider
  await always.check();
  await expect(page.locator('[data-trail-setting]')).toHaveCount(0);
  await expect(page.locator('[data-trail-note]')).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('ml.trail-seconds'))).toBe('0');
  const before = await plyOf(page);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(before + 2);
  await page.waitForTimeout(2500); // the 1 s that were set are long past
  await expect(page.locator('.ml-trail')).toHaveCount(1);
  await page.reload();
  await page.locator('[data-choice="link-side-to-move"]').click(); // the address holds the game: "Continue this game?"
  await waitForHumanTurn(page);
  await expect(page.locator('[data-trail-mode-input="always"]')).toBeChecked(); // remembered
  // back to "Fade out": the slider returns with the default time
  await page.locator('[data-trail-mode-input="timed"]').check();
  await expect(page.locator('[data-trail-setting]')).toHaveValue('3');
  expect(await page.evaluate(() => localStorage.getItem('ml.trail-seconds'))).toBe('3');
  errors.expectNone();
});
