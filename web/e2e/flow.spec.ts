// The flow around the game in a real browser: the start dialog, the game in the address, reload, Back and Forward,
// a link that does not work, and "copy link".
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, playOneExchange, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

const dialog = (page: Page) => page.getByRole('dialog');

/** The pieces on the board as text: what a player sees, independent of the engine. */
const position = async (page: Page): Promise<string> =>
  (await page.locator('[data-cell]').evaluateAll((cells) =>
    cells.map((c) => `${c.getAttribute('data-cell')}:${c.getAttribute('aria-label')}`).join('|'),
  ));

test('a page without a game link asks "Do you want to begin?" and starts nothing before the answer', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask');
  await expect(dialog(page)).toBeVisible();
  await expect(dialog(page)).toHaveAttribute('aria-modal', 'true');
  await expect(dialog(page).locator('[data-choice="begin-yes"]')).toBeFocused();
  expect(new URL(page.url()).hash).toBe('');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  await expect(dialog(page).locator(':focus')).toHaveCount(1); // focus stays inside (a radio or a button)
  await page.keyboard.press('Escape');
  await expect(dialog(page)).toBeVisible(); // there is no game to go back to
  await dialog(page).locator('[data-choice="begin-yes"]').click();
  await expect(dialog(page)).toBeHidden();
  await waitForHumanTurn(page);
  expect(await plyOf(page)).toBe(0); // "Yes": the human begins
  expect(new URL(page.url()).hash).toMatch(/^#g=v1\.&h=0&l=\d$/);
  errors.expectNone();
});

test('"No" lets the engine begin', async ({ page }) => {
  await openApp(page, 'en', '', 'engine');
  await expect.poll(() => plyOf(page)).toBe(1);
  await waitForHumanTurn(page);
});

test('play a move, reload, and land in the same position', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await playOneExchange(page);
  const before = await position(page);
  const plies = await plyOf(page);
  expect(plies).toBe(2);
  const url = page.url();
  expect(new URL(url).hash).toMatch(/^#g=v1\.[A-Za-z0-9_-]+&h=0&l=\d$/);
  await page.reload();
  await expect(dialog(page)).toBeVisible(); // a page opened with a game link asks how to continue
  await expect(dialog(page)).toContainText('ply 2 of 200');
  await dialog(page).locator('[data-choice="link-side-to-move"]').click();
  await waitForHumanTurn(page);
  expect(await plyOf(page)).toBe(2);
  expect(await position(page)).toBe(before);
  expect(page.url()).toBe(url);
  errors.expectNone();
});

test('the same link in a fresh tab opens the same position', async ({ page, context }) => {
  await openApp(page, 'en');
  await playOneExchange(page);
  const before = await position(page);
  const second = await context.newPage();
  await second.goto(page.url());
  await expect(dialog(second)).toBeVisible();
  await dialog(second).locator('[data-choice="link-side-to-move"]').click();
  await waitForHumanTurn(second);
  expect(await position(second)).toBe(before);
});

test('Back takes back one full turn, Forward replays it', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await playOneExchange(page);
  await playOneExchange(page);
  expect(await plyOf(page)).toBe(4);
  const at4 = await position(page);
  await page.goBack();
  await expect.poll(() => plyOf(page)).toBe(2);
  await waitForHumanTurn(page);
  await expect(dialog(page)).toBeHidden(); // Back through the entries of this page asks nothing
  await page.goBack();
  await expect.poll(() => plyOf(page)).toBe(0);
  await waitForHumanTurn(page);
  await page.goForward();
  await expect.poll(() => plyOf(page)).toBe(2);
  await page.goForward();
  await expect.poll(() => plyOf(page)).toBe(4);
  await waitForHumanTurn(page);
  expect(await position(page)).toBe(at4);
  errors.expectNone();
});

test('the routes still work and the play screen shows the game again afterwards', async ({ page }) => {
  await openApp(page, 'en');
  await playOneExchange(page);
  const url = page.url();
  await page.locator('.ml-nav__link[href="#/about"]').click();
  await expect(page.locator('.ml-nav__link[href="#/about"]')).toHaveAttribute('aria-current', 'page');
  await page.locator('.ml-nav__link[href="#/play"]').click();
  await expect(dialog(page)).toBeHidden();
  expect(await plyOf(page)).toBe(2);
  expect(page.url()).toBe(url);
});

test('a link that does not work gives a plain message and a way to a new game', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '#g=v1.%2A%2A%2A');
  await expect(dialog(page)).toContainText('This link does not work');
  await expect(dialog(page).locator('[data-choice="broken-new"]')).toBeFocused();
  await dialog(page).locator('[data-choice="broken-new"]').click();
  await expect(dialog(page)).toContainText('Do you want to begin?');
  await dialog(page).locator('[data-choice="begin-yes"]').click();
  await waitForHumanTurn(page);
  errors.expectNone();
});

test('copy link: the clipboard gets the address, or a field shows it', async ({ page, context, browserName }) => {
  if (browserName === 'chromium') await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await page.getByRole('button', { name: 'Copy link' }).click();
  const field = page.locator('.ml-copylink input');
  if (await field.count()) {
    await expect(field).toHaveValue(page.url()); // no clipboard in this browser: the visible fallback
  } else {
    await expect(page.locator('.ml-copylink [role="status"]')).toHaveText('Link copied');
    if (browserName === 'chromium') expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(page.url());
  }
});
