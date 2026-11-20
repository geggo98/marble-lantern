// The three levels of the move guides in a real browser: keyboard operation, what each level draws, persistence, migration of the
// old on/off value.
import { expect, test } from '@playwright/test';
import { humanTurnCell, openApp, waitForHumanTurn, watchPageErrors } from './helpers';

test('the three levels: arrow keys move the choice, "targets" marks targets without bands, "none" draws no target at all', async ({ page }) => {
  const errors = watchPageErrors(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  const cellId = await humanTurnCell(page).getAttribute('data-cell'); // a piece that can move
  const own = page.locator(`[data-cell="${cellId}"]`);

  // level "targets" (the default): the targets of a selected piece, no band; the advantage strip is there at every level
  await own.click();
  await expect(page.locator('svg.ml-board__svg .ml-target__dot').first()).toBeAttached();
  await expect(page.locator('svg.ml-board__svg [data-band]')).toHaveCount(0);
  await expect(page.locator('.ml-advantage')).toHaveCount(1);

  // keyboard: focus the checked radio button, ArrowLeft chooses "none"
  await page.locator('[data-guides-level="targets"]').focus();
  await page.keyboard.press('ArrowLeft');
  await expect(page.locator('[data-guides-level="none"]')).toBeChecked();
  // the piece selected above stays selected when the level changes
  await expect(page.locator('[data-cell][aria-label$="selected"]')).toHaveCount(1); // the selection stays visible
  await expect(page.locator('svg.ml-board__svg .ml-target__ring')).toHaveCount(0);
  await expect(page.locator('svg.ml-board__svg .ml-target__dot')).toHaveCount(0);

  await page.reload();
  await page.locator('[data-choice="link-side-to-move"], [data-choice="begin-yes"]').first().click().catch(() => undefined);
  await expect(page.locator('[data-guides-level="none"]')).toBeChecked(); // remembered

  // ArrowRight twice reaches the evaluation
  await page.locator('[data-guides-level="none"]').focus();
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('[data-guides-level="eval"]')).toBeChecked();
  await expect(page.locator('svg.ml-board__svg [data-guide="piece"]').first()).toBeAttached();
  errors.expectNone();
});

test('the old stored switch is migrated: "on" opens at the evaluation', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('ml.guides', 'on'));
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await expect(page.locator('[data-guides-level="eval"]')).toBeChecked();
  expect(await page.evaluate(() => localStorage.getItem('ml.guides'))).toBe('eval');
});
