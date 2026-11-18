// The benchmark page (WP10) plays the benchmark game with every engine and compares the fingerprint with the reference.
// Until the page has a runner, its start button is disabled; then the test says so and stops instead of failing.
import { expect, test } from '@playwright/test';
import { openApp, watchPageErrors } from './helpers';

test('the benchmark page shows "ok" in the fingerprint column for every available engine', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '#/bench');
  const start = page.locator('.ml-bench__actions .ml-btn--primary');
  await expect(start).toBeVisible();
  if (await start.isDisabled()) {
    test.info().annotations.push({ type: 'skipped-reason', description: 'this build has no benchmark runner yet' });
    test.skip(true, 'this build has no benchmark runner (WP10 not merged)');
    return;
  }
  await start.click();
  const rows = page.locator('.ml-table tbody tr');
  await expect(rows.first()).toBeVisible({ timeout: 110_000 });
  const count = await rows.count();
  expect(count).toBeGreaterThan(0);
  for (let i = 0; i < count; i++) {
    // 4th column group: the fingerprint cell carries data-label "Fingerprint"; the text is "ok" when it matches
    await expect(rows.nth(i).locator('.ml-ok')).toHaveText('ok');
  }
  errors.expectNone();
});
