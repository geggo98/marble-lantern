// The advantage strip is always on the play screen, at the bottom edge, inside the viewport without scrolling (docs/web-ui.md, "Advantage strip").
// Measured in a real browser at 360, 390 and 1280 px: a phone-sized strip above the action bar, a wide-screen strip at the bottom of the sticky stage.
// The guide level stays at its default ("targets"): the strip must not depend on the marks (a stored level would only add marks).
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { TWO_PLAYER_WIN } from './fixtures';
import { openApp, waitForHumanTurn, watchPageErrors } from './helpers';

const SIZES = [
  { name: '360', width: 360, height: 740 },
  { name: '390', width: 390, height: 844 },
  { name: '1280', width: 1280, height: 800 },
] as const;
const HOTSEAT = '#g=v1.&l=0&p=3&s=hhe';

/** The box of the strip, and the viewport, in viewport coordinates. */
async function strip(page: Page): Promise<{ top: number; bottom: number; left: number; right: number; height: number; vw: number; vh: number }> {
  return await page.evaluate(() => {
    const r = document.querySelector('.ml-play__adv')!.getBoundingClientRect();
    return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, height: r.height, vw: window.innerWidth, vh: window.innerHeight };
  });
}

function expectInside(b: Awaited<ReturnType<typeof strip>>, what: string): void {
  expect(b.height, `${what}: the strip has a height`).toBeGreaterThan(20);
  expect(b.top, `${what}: top edge`).toBeGreaterThanOrEqual(0);
  expect(b.bottom, `${what}: bottom edge inside the viewport`).toBeLessThanOrEqual(b.vh);
  expect(b.left, `${what}: left edge`).toBeGreaterThanOrEqual(0);
  expect(b.right, `${what}: right edge`).toBeLessThanOrEqual(b.vw);
}

for (const size of SIZES) {
  test.describe(`strip at ${size.name} px`, () => {
    test.use({ viewport: { width: size.width, height: size.height } });

    test('two players, default guide level: the strip is visible inside the viewport without scrolling, and the page does not scroll sideways', async ({ page }) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const slot = page.locator('.ml-play__adv .ml-advantage');
      await expect(slot).toBeVisible();
      expect(await page.evaluate(() => window.scrollY), 'nothing was scrolled').toBe(0);
      expectInside(await strip(page), 'after load');
      // the value arrives from the analysis of the human's turn; the strip keeps its box
      const before = await strip(page);
      await expect(slot).toHaveAttribute('data-state', 'value');
      const after = await strip(page);
      expect(after.top, 'the strip does not move when the value arrives').toBeCloseTo(before.top, 0);
      expect(after.height).toBeCloseTo(before.height, 0);
      expectInside(after, 'with a value');
      // the strip does not cover the board
      const board = (await page.locator('svg.ml-board__svg').boundingBox())!;
      expect(board.y + board.height, 'the board ends above the strip').toBeLessThanOrEqual(after.top + 1);
      expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
      errors.expectNone();
    });

    test('three players: the three-segment bar is inside the viewport as well', async ({ page }) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en', HOTSEAT);
      await waitForHumanTurn(page);
      await expect(page.locator('[data-advantage3] .ml-adv3')).toBeVisible();
      const bar = (await page.locator('[data-advantage3] .ml-adv3').boundingBox())!;
      const vh = size.height;
      expect(bar.y).toBeGreaterThanOrEqual(0);
      expect(bar.y + bar.height).toBeLessThanOrEqual(vh);
      expectInside(await strip(page), 'three players');
      errors.expectNone();
    });
  });
}

test.describe('strip on a wide screen after scrolling', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('the sticky stage keeps the strip inside the viewport while the page scrolls', async ({ page }) => {
    await openApp(page, 'en');
    await waitForHumanTurn(page);
    await expect(page.locator('.ml-play__adv .ml-advantage')).toBeVisible();
    const scrollable = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);
    await page.evaluate(() => window.scrollTo(0, 400));
    const y = await page.evaluate(() => window.scrollY);
    expect(y, `the page scrolled (${scrollable}px of room)`).toBeGreaterThan(0);
    const b = await strip(page);
    expectInside(b, `scrolled by ${y}px`);
    // not merely inside: it stays at the bottom edge of the window (sticky stage), 16 px from it at scroll 0 and about 80 px after the page scrolled
    expect(b.vh - b.bottom, `distance of the strip from the bottom edge after scrolling by ${y}px`).toBeLessThanOrEqual(120);
  });
});

test('a finished two-player game: the strip shows the result at once (full for the winner, no "updating"), and the board has no progress cursor', async ({ page }) => {
  const errors = watchPageErrors(page);
  await page.addInitScript(() => localStorage.setItem('ml.guides', 'eval')); // "With evaluation", as in the report
  await openApp(page, 'en', `#g=v1.${TWO_PLAYER_WIN.moves}&h=1&l=2`); // side 0 has won; the person is side 1
  await page.locator('[data-choice="link-side-to-move"]').click();
  await expect(page.locator('.ml-result')).toBeVisible();
  const adv = page.locator('.ml-play__adv .ml-advantage');
  await expect(adv).toHaveAttribute('data-advantage', 'engine');
  await expect(adv).toHaveAttribute('data-level', 'over');
  await expect(adv).toContainText('Engine: won');
  await expect(adv).not.toContainText('updating');
  await expect(adv).not.toHaveClass(/is-stale/);
  await page.waitForTimeout(1500); // a late analysis answer must not bring an old value back
  await expect(adv).toHaveAttribute('data-level', 'over');
  expect(await page.locator('svg.ml-board__svg').evaluate((e) => getComputedStyle(e).cursor)).not.toBe('progress');
  errors.expectNone();
});

test.describe('strip on a low wide window', () => {
  test.use({ viewport: { width: 1280, height: 500 } });

  test('below 36rem of height the stage is not sticky: the strip follows the board in normal flow and is reachable', async ({ page }) => {
    await openApp(page, 'en');
    await waitForHumanTurn(page);
    const slot = page.locator('.ml-play__adv');
    await slot.scrollIntoViewIfNeeded();
    expectInside(await strip(page), 'after scrolling to it');
    const board = (await page.locator('svg.ml-board__svg').boundingBox())!;
    expect(board.height, 'the board keeps its minimum size').toBeGreaterThan(200);
  });
});
