// The start dialog for the game variants and the play screen for three seats, in a real browser with the real engines (they need
// no race table for these games). Screenshots go to SHOT_DIR (default test-results/screens) for the visual review.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { LONG_HOP } from './fixtures';
import { openApp, playHumanMove, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR ?? 'test-results/screens';
const shot = (page: Page, project: string, name: string): Promise<Buffer> =>
  page.screenshot({ path: `${SHOT_DIR}/${project}-${name}.png`, animations: 'disabled' });

const status = (page: Page): Promise<string> => page.locator('.ml-status__who span').first().innerText();
const marble = async (page: Page): Promise<string | null> => await page.locator('.ml-status__who use').getAttribute('href');

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    try {
      localStorage.removeItem('ml.start-choice'); // every test starts from the default choice
    } catch {
      // storage blocked
    }
  });
});

test('the default path is one click: "Yes, I begin" starts the classic game', async ({ page }, info) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask');
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(page.locator('[data-choice="jump-adjacent"]')).toBeChecked();
  await expect(page.locator('[data-choice="mode-two"]')).toBeChecked();
  await shot(page, info.project.name, 'dialog-desktop');
  await page.locator('[data-choice="begin-yes"]').click();
  await expect(dialog).toBeHidden();
  await waitForHumanTurn(page);
  expect(new URL(page.url()).hash).not.toContain('p=3');
  errors.expectNone();
});

test('hot-seat: two people move on different seats, the status shows each seat in its own marble', async ({ page }, info) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask');
  await page.locator('[data-choice="mode-hotseat"]').check();
  await shot(page, info.project.name, 'dialog-hotseat-desktop');
  await page.locator('[data-choice="begin-start"]').click();
  await waitForHumanTurn(page);
  expect(await status(page)).toBe('Player 1 to move');
  expect(await marble(page)).toBe('#ml-piece-a');
  await playHumanMove(page);
  await expect.poll(() => status(page)).toBe('Player 2 to move');
  expect(await marble(page)).toBe('#ml-piece-b');
  await waitForHumanTurn(page); // the tab stop sits on a movable piece of seat 2 although seat 1 used another cell last
  await shot(page, info.project.name, 'board-three-seats-desktop');
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(3); // seat 3, the engine, answered
  await waitForHumanTurn(page);
  expect(await status(page)).toBe('Player 1 to move');
  expect(new URL(page.url()).hash).toMatch(/&p=3&s=hhe$/);
  await expect(page.getByRole('button', { name: 'Swap sides' })).toHaveCount(0);
  errors.expectNone();
});

test('you against two engines on seat 3: both engines move first, then it is the person\'s turn', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask');
  await page.locator('[data-choice="mode-engines"]').check();
  await page.locator('[data-choice="human-seat-3"]').check();
  await page.locator('[data-choice="begin-start"]').click();
  await waitForHumanTurn(page);
  expect(await plyOf(page)).toBe(2);
  expect(await status(page)).toBe('Your move');
  expect(new URL(page.url()).hash).toMatch(/&p=3&s=eeh$/);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(5);
  await waitForHumanTurn(page);
  errors.expectNone();
});

test('the dialog remembers the last choice', async ({ page }) => {
  await openApp(page, 'en', '', 'ask');
  await page.locator('[data-choice="jump-long"]').check();
  await page.locator('[data-choice="mode-engines"]').check();
  await page.locator('[data-choice="human-seat-2"]').check();
  await page.locator('[data-choice="begin-start"]').click();
  await waitForHumanTurn(page);
  await page.getByRole('button', { name: 'New game' }).first().click();
  await expect(page.locator('[data-choice="jump-long"]')).toBeChecked();
  await expect(page.locator('[data-choice="mode-engines"]')).toBeChecked();
  await expect(page.locator('[data-choice="human-seat-2"]')).toBeChecked();
  await page.keyboard.press('Escape'); // keep the running game
  await expect(page.getByRole('dialog')).toBeHidden();
});

test('"New game" opened by mistake: "Keep my current game" and a click beside the dialog go back to the same game', async ({ page }, info) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask');
  const dialog = page.getByRole('dialog');
  // before the first game there is nothing to keep: no button, and a click beside the panel does nothing
  await expect(page.locator('[data-choice="begin-keep"]')).toHaveCount(0);
  await page.mouse.click(4, 4);
  await expect(dialog).toBeVisible();
  await page.locator('[data-choice="begin-yes"]').click();
  await waitForHumanTurn(page);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(2);
  await waitForHumanTurn(page);
  const url = page.url();
  const newGame = page.getByRole('button', { name: 'New game' }).first();

  await newGame.click();
  await expect(page.locator('[data-dialog="begin"]')).toBeVisible();
  await page.locator('[data-choice="mode-hotseat"]').check(); // a changed choice must not reach the running game
  await shot(page, info.project.name, 'dialog-keep-desktop');
  await page.locator('[data-choice="begin-keep"]').click();
  await expect(dialog).toBeHidden();
  expect(await plyOf(page)).toBe(2);
  expect(page.url()).toBe(url);

  await newGame.click();
  await expect(page.locator('[data-dialog="begin"]')).toBeVisible();
  await page.mouse.click(4, 4); // the backdrop, beside the panel
  await expect(dialog).toBeHidden();
  expect(await plyOf(page)).toBe(2);
  expect(page.url()).toBe(url);
  await waitForHumanTurn(page);
  await playHumanMove(page); // the game goes on where it was
  await expect.poll(() => plyOf(page)).toBe(4);
  errors.expectNone();
});

test('long hop: the dialog starts the game, a long hop is played with click and click, the engine answers', async ({ page }) => {
  const errors = watchPageErrors(page);
  const fixture = LONG_HOP;
  const link = `#g=v1.${fixture.moves}&h=${fixture.side}&l=0&j=long`;
  await openApp(page, 'en', link);
  await page.locator('[data-choice="link-side-to-move"]').click();
  await expect(page.getByRole('dialog')).toBeHidden();
  await waitForHumanTurn(page);
  const before = await plyOf(page);
  expect(before).toBe(fixture.plies);
  await page.locator(`[data-cell="${fixture.from}"]`).click({ force: true });
  const target = page.locator(`[data-cell="${fixture.to}"]`);
  await expect(target).toHaveAttribute('aria-label', /target/);
  await target.click({ force: true });
  await expect.poll(() => plyOf(page)).toBe(before + 2); // the long hop, then the engine's answer
  await expect(page.locator(`[data-cell="${fixture.to}"]`)).toHaveAttribute('aria-label', /your piece/);
  errors.expectNone();
});

test('a long-hop link names its jump rule in the dialog', async ({ page }) => {
  await openApp(page, 'en', '#g=v1.&h=0&l=0&j=long');
  await expect(page.locator('[data-testid="link-variant"]')).toContainText('Long hop');
});

test('three engines play a demo to the end: the result card names the winner or ranks the three seats at the move limit', async ({ page }, info) => {
  test.setTimeout(420_000);
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask');
  await page.locator('[data-choice="mode-hotseat"]').check();
  await page.locator('[data-choice="begin-start"]').click();
  await waitForHumanTurn(page);
  await page.getByRole('button', { name: 'Engine demo' }).click();
  const card = page.locator('.ml-result');
  await expect(card).toBeVisible({ timeout: 400_000 });
  expect(await status(page)).toBe('Game over');
  if ((await card.innerText()).includes('No winner')) {
    await expect(card.locator('.ml-score__row')).toHaveCount(3);
    await expect(card).not.toContainText(' wins');
    await expect(card).toContainText('300');
  } else {
    await expect(card).toContainText(/(Amber|Blue|Pearl) wins/);
  }
  await shot(page, info.project.name, 'result-card-three-seats-desktop');
  errors.expectNone();
});

test.describe('the dialog on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
  test.beforeEach(({ browserName }) => {
    test.skip(browserName === 'firefox', 'Firefox has no mobile emulation (isMobile)');
  });

  for (const mode of ['two', 'hotseat', 'engines'] as const) {
    test(`fits the screen without horizontal scroll, every control is 44 px, mode ${mode}`, async ({ page }, info) => {
      await openApp(page, 'en', '', 'ask');
      await page.locator(`[data-choice="mode-${mode}"]`).check({ force: true });
      const sheet = page.locator('.ml-start');
      await expect(sheet).toBeVisible();
      const overflow = await page.evaluate(() => ({
        doc: document.documentElement.scrollWidth - window.innerWidth,
        sheet: (document.querySelector('.ml-start') as HTMLElement).scrollWidth - (document.querySelector('.ml-start') as HTMLElement).clientWidth,
      }));
      expect(overflow.doc, 'document overflow').toBeLessThanOrEqual(0);
      expect(overflow.sheet, 'sheet overflow').toBeLessThanOrEqual(0);
      const box = (await sheet.boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(-1);
      expect(box.x + box.width).toBeLessThanOrEqual(391);
      expect(box.y + box.height).toBeLessThanOrEqual(845);
      const small = await page.evaluate(() =>
        [...document.querySelectorAll('.ml-start .ml-start__opt, .ml-start .ml-btn')].map((el) => {
          const r = el.getBoundingClientRect();
          return { what: (el.textContent ?? '').trim().slice(0, 30), w: r.width, h: r.height };
        }).filter((x) => x.w < 43.4 || x.h < 43.4),
      );
      expect(small, 'targets under 44 px').toEqual([]);
      await shot(page, info.project.name, `dialog-mobile-390-${mode}`);
    });
  }

  test('a small phone (320 x 568): the sheet scrolls inside, the page does not', async ({ page }, info) => {
    await page.setViewportSize({ width: 320, height: 568 });
    await openApp(page, 'en', '', 'ask');
    await page.locator('[data-choice="mode-engines"]').check({ force: true });
    const o = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(o).toBeLessThanOrEqual(0);
    const sheet = (await page.locator('.ml-start').boundingBox())!;
    expect(sheet.y + sheet.height).toBeLessThanOrEqual(569);
    await page.locator('[data-choice="begin-start"]').scrollIntoViewIfNeeded();
    await expect(page.locator('[data-choice="begin-start"]')).toBeInViewport();
    await shot(page, info.project.name, 'dialog-mobile-320-engines');
  });

  test('the result card has a close button on the phone; closing shows the board, the status says "Game over"', async ({ page }, info) => {
    test.setTimeout(240_000);
    await openApp(page, 'en', '', 'ask');
    await page.locator('[data-choice="begin-yes"]').click();
    await waitForHumanTurn(page);
    await page.locator('.ml-play__bar [aria-controls]').click();
    await page.getByRole('button', { name: 'Engine demo' }).click();
    await page.keyboard.press('Escape'); // close the menu; the demo runs on
    const card = page.locator('.ml-result');
    await expect(card).toBeVisible({ timeout: 200_000 });
    await shot(page, info.project.name, 'result-card-mobile-390');
    await page.locator('[data-result-close]').click();
    await expect(card).toBeHidden();
    expect(await status(page)).toBe('Game over');
    await shot(page, info.project.name, 'board-after-result-mobile-390');
  });
});
