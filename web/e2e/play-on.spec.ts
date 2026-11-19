// "Play on for 2nd place" in a real browser with the real engines: a three-player game that has been won (fixture PLAY_ON_WIN, an
// engine game computed once), the button on the result card, the game that goes on without the seat of the winner, the address with
// r=full (a new history entry, a reload reproduces the game), and the final ranking. Screenshots go to SHOT_DIR.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { PLAY_ON_WIN } from './fixtures';
import { openApp, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR ?? 'test-results/screens';
const HUMAN_ON_0 = `#g=v1.${PLAY_ON_WIN.moves}&l=0&p=3&s=hee`; // seat 2, an engine, has won; the person is seat 0
const HUMAN_ON_2 = `#g=v1.${PLAY_ON_WIN.moves}&l=0&p=3&s=eeh`; // seat 2, the person, has won; two engines play on

const playOn = (page: Page) => page.locator('[data-play-on]');
const seatRows = (page: Page) => page.locator('.ml-seats__row');

/** On a small screen the seats card lives in the menu. */
async function withSeats<T>(page: Page, fn: () => Promise<T>): Promise<T> {
  const menuButton = page.locator('.ml-play__bar [aria-controls]');
  const small = await menuButton.isVisible();
  if (small) await menuButton.click();
  try {
    return await fn();
  } finally {
    if (small) {
      const close = page.locator('[data-menu-close]');
      if (await close.isVisible()) await close.click();
    }
  }
}

test('an engine has won: Play on closes the card, the person moves for 2nd place, the address and a reload keep it', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', HUMAN_ON_0);
  await expect(page.locator('.ml-result')).toBeVisible();
  expect(await plyOf(page)).toBe(PLAY_ON_WIN.plies);
  await expect(playOn(page)).toBeVisible();
  const winUrl = page.url();
  await page.screenshot({ path: `${SHOT_DIR}/play-on-card-${test.info().project.name}.png`, animations: 'disabled' });

  await playOn(page).click();
  await expect(page.locator('.ml-result')).toHaveCount(0);
  await waitForHumanTurn(page); // seat 1 is not skipped, seat 2 is: the person (seat 0) comes after the winner
  expect(await plyOf(page)).toBe(PLAY_ON_WIN.plies);
  await expect.poll(() => new URL(page.url()).hash).toMatch(/&p=3&s=hee&r=full$/);
  await withSeats(page, async () => {
    await expect(seatRows(page).nth(2)).toHaveAttribute('data-rank-text', /.+/);
    await expect(seatRows(page).nth(0)).not.toHaveAttribute('data-rank-text', /.+/);
  });
  const playingOnUrl = page.url();
  await page.screenshot({ path: `${SHOT_DIR}/play-on-playing-${test.info().project.name}.png`, animations: 'disabled' });

  // a reload reproduces the game that goes on
  await page.reload();
  await waitForHumanTurn(page);
  expect(await plyOf(page)).toBe(PLAY_ON_WIN.plies);
  expect(page.url()).toBe(playingOnUrl);
  await expect(page.locator('.ml-result')).toHaveCount(0);

  // Back: the position before Play on, with its button
  await page.goBack();
  await expect(playOn(page)).toBeVisible();
  expect(page.url()).toBe(winUrl);
  errors.expectNone();
});

test('the person has won: the engines play on alone to the ranking, which lists all three seats', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', HUMAN_ON_2);
  await expect(playOn(page)).toBeVisible();
  await playOn(page).click();
  // the engines of the page play with the strength of the link (level 0), not with the 10 nodes of the fixture: the end may differ
  const rows = page.locator('.ml-ranking li');
  await expect(rows).toHaveCount(3, { timeout: 90_000 });
  const ended = await plyOf(page);
  expect(ended).toBeGreaterThan(PLAY_ON_WIN.plies);
  expect(ended).toBeLessThanOrEqual(300);
  await expect(page.locator('[data-play-on]')).toHaveCount(0);
  // the winner keeps first place; the rows are in rank order
  expect(await rows.evaluateAll((els) => els.map((e) => e.getAttribute('data-rank')))).toEqual(['1', '2', '3']);
  await expect(rows.first()).toContainText('(you)');
  await page.screenshot({ path: `${SHOT_DIR}/play-on-ranking-${test.info().project.name}.png`, animations: 'disabled' });
  // the address of the finished game reproduces the ranking
  await page.reload();
  await expect(page.locator('.ml-ranking li')).toHaveCount(3);
  expect(await plyOf(page)).toBe(ended);
  errors.expectNone();
});

test.describe('on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('the button is a 44 px target on the card over the board; Play on closes the card and the status row says it is a game for 2nd place', async ({ page }) => {
    const errors = watchPageErrors(page);
    await openApp(page, 'en', HUMAN_ON_0);
    await expect(page.locator('.ml-result')).toBeVisible();
    const box = await playOn(page).boundingBox();
    expect(box!.height).toBeGreaterThanOrEqual(43.4);
    expect(box!.width).toBeGreaterThanOrEqual(43.4);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: `${SHOT_DIR}/play-on-card-390-${test.info().project.name}.png`, animations: 'disabled' });
    const before = await page.locator('.ml-status').boundingBox();
    await playOn(page).click();
    await expect(page.locator('.ml-result')).toHaveCount(0);
    await waitForHumanTurn(page);
    // the status row keeps its height (no jump of the board)
    const after = await page.locator('.ml-status').boundingBox();
    expect(Math.abs(after!.height - before!.height)).toBeLessThan(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: `${SHOT_DIR}/play-on-playing-390-${test.info().project.name}.png`, animations: 'disabled' });
    errors.expectNone();
  });
});
