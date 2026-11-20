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
