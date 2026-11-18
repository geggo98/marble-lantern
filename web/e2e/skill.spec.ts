// The skill estimate on the real engine: play some moves, see the estimate (with the number of moves), match the engine to it,
// see the level change to "custom" in the card and the address, and go back.
import { expect, test } from '@playwright/test';
import { openApp, pickEngine, playOneExchange, watchPageErrors } from './helpers';

test('the estimate appears after 8 moves; "Match engine to my strength" sets a custom level; "Reset to level" goes back', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await pickEngine(page, 'cpp-wasm'); // 1,000 nodes per move: the engine answers fast; the analysis always uses its own budget
  const skill = page.locator('.ml-skill');
  await expect(skill).toBeVisible();
  await expect(skill).toContainText('moves analysed');
  await expect(skill.getByRole('button', { name: 'Match engine to my strength' })).toBeDisabled();

  for (let i = 0; i < 9; i++) await playOneExchange(page);

  const estimate = page.locator('[data-testid="skill-estimate"]');
  await expect(estimate).toBeVisible({ timeout: 90_000 });
  await expect(estimate).toHaveText(/level|Level/);
  await expect(page.locator('[data-testid="skill-moves"]')).toHaveText(/^Based on (8|9) of your moves\.$/);
  await expect(skill).toContainText('not a rating');

  const levelBefore = new URL(page.url()).hash.match(/&l=(\d+)$/)?.[1];
  expect(levelBefore).toBe('0');
  await skill.getByRole('button', { name: 'Match engine to my strength' }).click();
  await expect(page.locator('.ml-engine__level')).toContainText('Custom');
  await expect.poll(() => Number(new URL(page.url()).hash.match(/&l=(\d+)$/)?.[1])).toBeGreaterThanOrEqual(10);

  await playOneExchange(page); // the game goes on with the new budget
  await skill.getByRole('button', { name: 'Reset to level' }).click();
  await expect(page.locator('.ml-engine__level')).not.toContainText('Custom');
  await expect.poll(() => new URL(page.url()).hash.match(/&l=(\d+)$/)?.[1]).toBe('0');
  errors.expectNone();
});
