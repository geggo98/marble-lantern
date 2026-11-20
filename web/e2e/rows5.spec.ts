// The board size (4 or 5 rows per house) in a real browser with the real engines: a fresh visitor gets 5 rows, the choice is kept, a
// 5-row link continues on 5 rows. 5 rows play without the race table (docs/rules.md, "House size").
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { CHOICE_KEY, openApp, playHumanMove, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

const pieceCount = (page: Page): Promise<number> => page.locator('.ml-pieces use').count();
const storedRows = (page: Page): Promise<unknown> =>
  page.evaluate((key) => (JSON.parse(localStorage.getItem(key) ?? 'null') as { rows?: unknown } | null)?.rows, CHOICE_KEY);

// no addInitScript here: a stored choice has to survive a reload, and a fresh visitor has none
test('a fresh visitor sees 5 rows: the dialog has them checked, the board behind it has 15 pieces per side, "Yes" starts that game', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask', 'fresh');
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.locator('[data-choice="rows-5"]')).toBeChecked();
  await expect.poll(() => pieceCount(page)).toBe(30);
  await page.locator('[data-choice="begin-yes"]').click();
  await expect(page.getByRole('dialog')).toBeHidden();
  await waitForHumanTurn(page);
  expect(await pieceCount(page)).toBe(30);
  expect(new URL(page.url()).hash).toContain('&t=5');
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(2); // the engine answers on 5 rows
  await waitForHumanTurn(page);
  expect(await pieceCount(page)).toBe(30);
  expect(await storedRows(page)).toBe(5);
  errors.expectNone();
});

test('4 rows chosen in the dialog: 10 pieces per side, no t= in the address, and the choice is kept after a reload', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask', 'fresh');
  await page.locator('[data-choice="rows-4"]').check();
  await expect.poll(() => storedRows(page)).toBeUndefined(); // nothing is stored before the game starts
  await page.locator('[data-choice="begin-yes"]').click();
  await waitForHumanTurn(page);
  expect(await pieceCount(page)).toBe(20);
  expect(new URL(page.url()).hash).not.toContain('t=');
  expect(await storedRows(page)).toBe(4);
  await page.goto('');
  await expect(page.locator('[data-choice="rows-4"]')).toBeChecked();
  await expect.poll(() => pieceCount(page)).toBe(20);
  errors.expectNone();
});

test('three players on 5 rows (one person, two engines): 45 pieces, the engines answer in turn', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask', 5);
  await page.locator('[data-choice="mode-engines"]').check();
  await page.locator('[data-choice="begin-start"]').click();
  await waitForHumanTurn(page);
  expect(await pieceCount(page)).toBe(45);
  expect(new URL(page.url()).hash).toContain('t=5');
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(3);
  errors.expectNone();
});

test('a 5-row link keeps its size through "Continue" and a reload', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask', 5);
  await page.locator('[data-choice="begin-yes"]').click();
  await waitForHumanTurn(page);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(2);
  await waitForHumanTurn(page);
  const link = new URL(page.url()).hash;
  expect(link).toContain('&t=5');
  await page.evaluate(() => localStorage.setItem('ml.start-choice', '{"rows":4}')); // another choice must not change the linked game
  await page.goto(link);
  await page.reload();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.locator('[data-testid="link-rows"]')).toBeVisible();
  await page.locator('[data-choice="link-side-to-move"]').click();
  await waitForHumanTurn(page);
  expect(await pieceCount(page)).toBe(30);
  expect(new URL(page.url()).hash).toContain('&t=5');
  errors.expectNone();
});

test('an old 4-row link without t= shows the 4-row board behind the dialog for a fresh visitor (default 5 rows) and continues as the 4-row game', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask', 'fresh'); // no init script that stores a size: the visitor stays fresh across the reload
  await page.locator('[data-choice="rows-4"]').check();
  await page.locator('[data-choice="begin-yes"]').click();
  await waitForHumanTurn(page);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(2);
  await waitForHumanTurn(page);
  const link = new URL(page.url()).hash;
  expect(link).not.toContain('t='); // what the app wrote before the 5-row board existed
  await page.evaluate((key) => localStorage.removeItem(key), CHOICE_KEY); // a fresh visitor: the default choice is 5 rows
  await page.goto(link);
  await page.reload();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.locator('[data-testid="link-rows"]')).toContainText('4'); // a 4-row link names its size, because a new game preselects 5
  await expect.poll(() => pieceCount(page)).toBe(20); // not the 30 pieces of the 5-row default
  await page.locator('[data-choice="link-side-to-move"]').click();
  await waitForHumanTurn(page);
  expect(await pieceCount(page)).toBe(20);
  expect(new URL(page.url()).hash).not.toContain('t=');
  errors.expectNone();
});
