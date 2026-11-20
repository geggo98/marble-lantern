// Move guides and the advantage indicator for three players, in a real browser with the real engine (hot-seat link: seats 1 and 2
// are people, seat 3 is the engine). Screenshots go to SHOT_DIR (default test-results/screens).
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import en from '../src/i18n/en.json' with { type: 'json' };
import { humanTurnCell, openApp, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR ?? 'test-results/screens';
const HOTSEAT = '#g=v1.&l=0&p=3&s=hhe';
const BANDS = ['much-better', 'better', 'equal', 'worse', 'much-worse'] as const;
const MOVE_TEXT = new RegExp(`target, (${BANDS.map((b) => en[`guide_move_${b.replace('-', '_')}` as keyof typeof en]).join('|')})$`);

const piecesWithBadge = (page: Page) => page.locator('svg.ml-board__svg [data-guide="piece"]');
const indicator = (page: Page) => page.locator('[data-advantage3] .ml-adv3');

/** Plays the first move of the first piece that carries a badge (a piece that can move). */
async function playFirstBadged(page: Page): Promise<void> {
  await expect(piecesWithBadge(page).first()).toBeAttached();
  // by keyboard: on a 320 px screen the hit circles of neighbouring pieces overlap, and the keys are the accessible way anyway
  await humanTurnCell(page).focus();
  await page.keyboard.press('Enter');
  const targets = page.locator('[data-cell][aria-label*="target"]');
  await expect(targets.first()).toBeAttached();
  await targets.first().focus();
  await page.keyboard.press('Enter');
}

/** Opens the hot-seat game with the guides switched on through the stored choice (on a phone the switch is in the menu). */
async function openGuided(page: Page, language: 'en' | 'de' = 'en'): Promise<void> {
  await page.addInitScript(() => {
    try {
      localStorage.setItem('ml.guides', 'on');
    } catch {
      // the guides then stay off and the test fails visibly
    }
  });
  await openApp(page, language, HOTSEAT);
  await waitForHumanTurn(page);
}

test.describe('desktop', () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
  });

  test('hot-seat: badges and target marks for the seat to move, one scale, the guides follow the turn', async ({ page }) => {
    const errors = watchPageErrors(page);
    await openApp(page, 'en', HOTSEAT);
    await waitForHumanTurn(page);
    await expect(page.locator('[data-guides-level="targets"]')).toBeChecked(); // targets only by default, as for two players
    await page.locator('[data-guides-level="eval"]').check();
    await expect(piecesWithBadge(page).first()).toBeAttached();
    // every badge sits on a piece of seat 1 (the seat to move)
    const owners = await piecesWithBadge(page).evaluateAll((els) =>
      els.map((e) => document.querySelector(`[data-cell="${e.getAttribute('data-cell-of')}"]`)?.getAttribute('aria-label') ?? ''));
    expect(owners.length).toBeGreaterThan(0);
    for (const label of owners) expect(label).toMatch(/player 1 piece/);
    // the band of a piece equals the best band among its targets, and the best move of the position is top
    const pieces = await piecesWithBadge(page).evaluateAll((els) => els.map((e) => ({ cell: e.getAttribute('data-cell-of')!, band: e.getAttribute('data-band')! })));
    let tops = 0;
    for (const piece of pieces) {
      await page.locator(`[data-cell="${piece.cell}"]`).click();
      await expect(page.locator('svg.ml-board__svg [data-guide="target"]').first()).toBeAttached();
      const bands = await page.locator('svg.ml-board__svg [data-guide="target"]').evaluateAll((els) => els.map((e) => e.getAttribute('data-band')!));
      const best = BANDS[Math.min(...bands.map((b) => BANDS.indexOf(b as (typeof BANDS)[number])))];
      expect(best, `piece ${piece.cell}`).toBe(piece.band);
      if (piece.band === 'much-better') tops++;
    }
    expect(tops, 'the best move of the position is top as a target').toBeGreaterThan(0);
    const names = await page.locator('[data-cell][aria-label*="target"]').evaluateAll((els) => els.map((e) => e.getAttribute('aria-label') ?? ''));
    expect(names.length).toBeGreaterThan(0);
    for (const name of names) expect(name).toMatch(MOVE_TEXT);

    // play it; seat 2 is a person too: the badges now belong to seat 2
    await page.locator('[data-cell][aria-label*="target"]').first().click({ force: true });
    await expect.poll(() => plyOf(page)).toBe(1);
    await waitForHumanTurn(page);
    await expect(piecesWithBadge(page).first()).toBeAttached();
    await expect.poll(async () => (await piecesWithBadge(page).evaluateAll((els) =>
      els.map((e) => document.querySelector(`[data-cell="${e.getAttribute('data-cell-of')}"]`)?.getAttribute('aria-label') ?? ''))).every((l) => /player 2 piece/.test(l))).toBe(true);
    errors.expectNone();
  });

  test('the indicator has three segments in seat order, an accessible name and updates after a move', async ({ page }) => {
    const errors = watchPageErrors(page);
    await openGuided(page);
    await expect(indicator(page)).toBeVisible();
    const segs = page.locator('.ml-adv3__seg');
    await expect(segs).toHaveCount(3);
    expect(await segs.evaluateAll((els) => els.map((e) => e.getAttribute('data-seat')))).toEqual(['0', '1', '2']);
    const percents = await segs.evaluateAll((els) => els.map((e) => Number(e.getAttribute('data-percent'))));
    expect(percents.reduce((a, b) => a + b, 0)).toBe(100);
    // at the start the three seats are level
    for (const p of percents) expect(p).toBeGreaterThanOrEqual(33);
    const name = await indicator(page).getAttribute('aria-label');
    expect(name).toBe(`Advantage: Player 1 ${percents[0]} %, Player 2 ${percents[1]} %, Player 3 (engine) ${percents[2]} %`);
    // marble, seat number and share are in every segment (not colour alone)
    for (let i = 0; i < 3; i++) {
      await expect(segs.nth(i).locator('.ml-swatch')).toBeVisible();
      await expect(segs.nth(i).locator('.ml-adv3__num')).toHaveText(String(i + 1));
      await expect(segs.nth(i).locator('.ml-adv3__value')).toHaveText(`${percents[i]} %`);
    }
    const marbles = await segs.evaluateAll((els) => els.map((e) => e.querySelector('.ml-swatch use')?.getAttribute('href')));
    expect(new Set(marbles).size).toBe(3);
    // after a move of seat 1 the indicator describes the new position: not stale, still three segments adding up to 100
    await playFirstBadged(page);
    await expect.poll(() => plyOf(page)).toBe(1);
    await expect(page.locator('[data-advantage3]:not(.is-stale)')).toBeVisible();
    const after = await segs.evaluateAll((els) => els.map((e) => Number(e.getAttribute('data-percent'))));
    expect(after.reduce((a, b) => a + b, 0)).toBe(100);
    expect(after[0]!, 'seat 1 moved forward: its share did not fall').toBeGreaterThanOrEqual(percents[0]!);
    errors.expectNone();
  });

  test('the two-player bar is unchanged and has no three-segment indicator', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('ml.guides', 'on'));
    await openApp(page, 'en');
    await waitForHumanTurn(page);
    await expect(page.locator('.ml-advantage__track')).toBeVisible();
    await expect(page.locator('[data-advantage3]')).toHaveCount(0);
  });

  for (const scheme of ['light', 'dark'] as const) {
    for (const language of ['en', 'de'] as const) {
      test(`screenshot desktop ${scheme} ${language}`, async ({ page }, info) => {
        await page.emulateMedia({ colorScheme: scheme });
        await openGuided(page, language);
        await expect(indicator(page)).toBeVisible();
        await page.locator(`[data-cell="${await piecesWithBadge(page).first().getAttribute('data-cell-of')}"]`).click();
        await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-guides3-desktop-${scheme}-${language}.png`, animations: 'disabled' });
      });
    }
  }
});

for (const device of [
  { name: '390', width: 390, height: 844 },
  { name: '320', width: 320, height: 568 },
] as const) {
  test.describe(`phone ${device.name}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: device.width, height: device.height });
    });

    test('the indicator row is visible, fixed in height, inside the viewport, and the page does not scroll sideways', async ({ page }) => {
      const errors = watchPageErrors(page);
      await openGuided(page);
      const slot = page.locator('.ml-play__adv');
      await expect(indicator(page)).toBeVisible();
      const box = (await slot.boundingBox())!;
      const bar = (await indicator(page).boundingBox())!;
      expect(bar.x).toBeGreaterThanOrEqual(0);
      expect(bar.x + bar.width).toBeLessThanOrEqual(device.width + 0.5);
      // the slot keeps its height through a move (no layout shift of the board)
      const board = (await page.locator('svg.ml-board__svg').boundingBox())!;
      await playFirstBadged(page);
      await expect.poll(() => plyOf(page)).toBe(1);
      await expect(page.locator('[data-advantage3]:not(.is-stale)')).toBeVisible();
      const box2 = (await slot.boundingBox())!;
      const board2 = (await page.locator('svg.ml-board__svg').boundingBox())!;
      expect(box2.height).toBeCloseTo(box.height, 0);
      expect(box2.y).toBeCloseTo(box.y, 0);
      expect(board2.height).toBeCloseTo(board.height, 0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      // every segment shows seat number and share, none is cut to nothing
      for (let i = 0; i < 3; i++) {
        const seg = page.locator('.ml-adv3__seg').nth(i);
        await expect(seg.locator('.ml-adv3__num')).toBeVisible();
        const label = (await seg.locator('.ml-adv3__label').boundingBox())!;
        expect(label.width, `segment ${i}`).toBeGreaterThan(40);
      }
      await page.screenshot({ path: `${SHOT_DIR}/${test.info().project.name}-guides3-${device.name}.png`, animations: 'disabled' });
      errors.expectNone();
    });

    for (const scheme of ['light', 'dark'] as const) {
      test(`screenshot ${scheme} de`, async ({ page }, info) => {
        await page.emulateMedia({ colorScheme: scheme });
        await openGuided(page, 'de');
        await expect(indicator(page)).toBeVisible();
        await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-guides3-${device.name}-${scheme}-de.png`, animations: 'disabled' });
      });
    }
  });
}
