// The game variants in a real browser with the real engines: a three-player game against two engines and a long-hop game, both
// opened through a game link (the start dialog for the variants comes later). Neither needs the race table.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, playHumanMove, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

/**
 * Plays any move of the side to move. In hot-seat the pieces of both human seats are on the board, and the board does not say yet
 * which of them can move (its colours and labels are for two sides), so the helper tries the pieces one by one.
 */
async function playAnyMove(page: Page): Promise<void> {
  await waitForHumanTurn(page);
  const pieces = page.locator('[data-cell][aria-label*="piece"]');
  const count = await pieces.count();
  for (let i = 0; i < count; i++) {
    await pieces.nth(i).click({ force: true });
    const dots = page.locator('.ml-target__dot');
    if ((await dots.count()) > 0) {
      await dots.first().click({ force: true });
      return;
    }
  }
  throw new Error('no piece has a legal move');
}

test('three players: the human on seat 0, two engines answer in turn, the address keeps the seats', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '#g=v1.&l=0&p=3&s=hee');
  await expect(page.getByRole('dialog')).toBeHidden(); // seats are in the link: no question
  await waitForHumanTurn(page);
  expect(await plyOf(page)).toBe(0);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(3); // the human, then the engines of seats 1 and 2
  await waitForHumanTurn(page);
  const hash = new URL(page.url()).hash;
  expect(hash).toMatch(/^#g=v1\.[A-Za-z0-9_-]+&l=0&p=3&s=hee$/);
  // move guides for three players (e2e/guides3.spec.ts), but no skill estimate
  await expect(page.locator('[data-guides-toggle]')).toHaveCount(1);
  await expect(page.locator('[data-testid="skill-pause"]')).toHaveCount(0);
  errors.expectNone();
});

test('three players, hot-seat: two humans on one device, no swap button; reload keeps the game', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '#g=v1.&l=0&p=3&s=hhe');
  await waitForHumanTurn(page);
  await playAnyMove(page); // seat 0
  await expect.poll(() => plyOf(page)).toBe(1);
  await waitForHumanTurn(page); // seat 1 is a human too: no engine answers
  await playAnyMove(page);
  await expect.poll(() => plyOf(page)).toBe(3); // seat 2, the engine, answered
  await waitForHumanTurn(page);
  await expect(page.getByRole('button', { name: 'Swap sides' })).toHaveCount(0);
  const url = page.url();
  await page.reload();
  await waitForHumanTurn(page);
  expect(await plyOf(page)).toBe(3);
  expect(page.url()).toBe(url);
  errors.expectNone();
});

test('long hop, two players: the link continues through the dialog, the engine answers, the address says j=long', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '#g=v1.&h=0&l=0&j=long');
  await page.locator('[data-choice="link-side-to-move"]').click();
  await waitForHumanTurn(page);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(2);
  await waitForHumanTurn(page);
  expect(new URL(page.url()).hash).toMatch(/^#g=v1\.[A-Za-z0-9_-]+&h=0&l=0&j=long$/);
  // the long hop has analysis, so move guides and the skill card stay
  await expect(page.locator('[data-guides-toggle]')).toHaveCount(1);
  await expect(page.locator('[data-testid="skill-pause"]')).toHaveCount(1);
  errors.expectNone();
});
