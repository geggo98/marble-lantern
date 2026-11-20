// Move guides in a real browser with the real engine: switch on, select a piece, marked targets; screenshots in light and dark
// and with forced colours; colour-vision deficiencies emulated through Chromium's DevTools protocol, with the marks asserted.
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import en from '../src/i18n/en.json' with { type: 'json' };
import { openApp, waitForHumanTurn, watchPageErrors } from './helpers';

const SHOTS = 'test-results/screens';
const BANDS = ['much-better', 'better', 'equal', 'worse', 'much-worse'] as const;

const MOVE_TEXT = new RegExp(`target, (${BANDS.map((b) => en[`guide_move_${b.replace('-', '_')}` as keyof typeof en]).join('|')})$`);

const board = (page: Page): Locator => page.locator('svg.ml-board__svg');

/** Opens the app, switches the guides on, waits for the badges, selects the first badged piece and waits for its targets. */
async function guidesWithSelection(page: Page): Promise<void> {
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await page.locator('[data-guides-level="eval"]').check();
  await expect(page.locator('svg.ml-board__svg [data-guide="piece"]').first()).toBeAttached();
  await page.locator('[data-cell][aria-label*="best move"]').first().click();
  await expect(page.locator('svg.ml-board__svg [data-guide="target"]').first()).toBeAttached();
}

/** The glyph shapes (path data) of the marks of one kind that are on the board now. */
const glyphs = (page: Page, kind: 'piece' | 'target'): Promise<string[]> =>
  page.locator(`svg.ml-board__svg [data-guide="${kind}"] .ml-guide__glyph`).evaluateAll((els) => els.map((e) => e.getAttribute('d') ?? ''));

test('the guides start at the level "targets" (no marks), and the choice survives a reload', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await expect(page.locator('[data-guides-level="targets"]')).toBeChecked();
  await expect(page.locator('svg.ml-board__svg [data-band]')).toHaveCount(0);
  await page.locator('[data-guides-level="eval"]').check();
  await expect(page.locator('svg.ml-board__svg [data-guide="piece"]').first()).toBeAttached();
  await expect(page.locator('.ml-advantage')).toBeVisible();
  await page.reload();
  await page.locator('[data-choice="link-side-to-move"], [data-choice="begin-yes"]').first().click().catch(() => undefined);
  await expect(page.locator('[data-guides-level="eval"]')).toBeChecked();
  errors.expectNone();
});

test('selecting a piece tints its targets, each with a band, a mark and an accessible name', async ({ page }) => {
  const errors = watchPageErrors(page);
  await guidesWithSelection(page);
  const targets = page.locator('svg.ml-board__svg [data-guide="target"]');
  const count = await targets.count();
  expect(count).toBeGreaterThan(0);
  for (let i = 0; i < count; i++) {
    const band = await targets.nth(i).getAttribute('data-band');
    expect(BANDS).toContain(band);
    const box = await targets.nth(i).locator('.ml-guide__glyph').boundingBox();
    expect(box && box.width > 2 && box.height > 2, `glyph ${i} is drawn`).toBe(true);
  }
  const names = await page.locator('[data-cell][aria-label*="target"]').evaluateAll((els) => els.map((e) => e.getAttribute('aria-label') ?? ''));
  expect(names).toHaveLength(count);
  for (const name of names) expect(name).toMatch(MOVE_TEXT);
  // the selection ring and the legal-move rings are still there next to the marks
  await expect(page.locator('svg.ml-board__svg .ml-select')).toHaveCount(1);
  await expect(page.locator('svg.ml-board__svg .ml-target__ring')).toHaveCount(count);
  errors.expectNone();
});

test('playing a move with the guides on keeps the game going and the bar current', async ({ page }) => {
  const errors = watchPageErrors(page);
  await guidesWithSelection(page);
  const before = await page.locator('.ml-advantage__text').textContent();
  expect(before).toMatch(/Advantage: (Even|You: |Engine: )/);
  await page.locator('[data-cell][aria-label*="target"]').first().click({ force: true });
  await expect.poll(async () => Number(/(\d+)\s*\//.exec((await page.locator('.ml-status__ply').textContent()) ?? '')?.[1] ?? 0)).toBeGreaterThanOrEqual(2);
  await waitForHumanTurn(page);
  await expect(page.locator('svg.ml-board__svg [data-guide="piece"]').first()).toBeAttached();
  await expect(page.locator('.ml-advantage:not(.is-stale)')).toBeVisible();
  errors.expectNone();
});

test('screenshots in light, dark and forced colours', async ({ page }, info) => {
  await guidesWithSelection(page);
  await page.locator('.ml-guides-card__legend summary').click();
  for (const scheme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await page.screenshot({ path: `${SHOTS}/${info.project.name}-guides-${scheme}.png`, fullPage: true, animations: 'disabled' });
    expect((await glyphs(page, 'target')).length).toBeGreaterThan(0);
  }
  await page.emulateMedia({ colorScheme: 'light', forcedColors: 'active' });
  await expect(page.locator('svg.ml-board__svg [data-guide="target"]').first()).toBeAttached();
  const fill = await page.locator('svg.ml-board__svg .ml-guide__glyph').first().evaluate((e) => getComputedStyle(e).fill);
  expect(fill).not.toBe('none');
  expect(fill).not.toBe('rgba(0, 0, 0, 0)');
  await page.screenshot({ path: `${SHOTS}/${info.project.name}-guides-forced-colors.png`, fullPage: true, animations: 'disabled' });
  await page.emulateMedia({ forcedColors: 'none' });
});

test('the legend lists the five bands with five different marks', async ({ page }) => {
  await guidesWithSelection(page);
  await page.locator('.ml-guides-card__legend summary').click();
  const items = page.locator('[data-legend-band]');
  await expect(items).toHaveCount(5);
  const shapes = await items.evaluateAll((els) => els.map((e) => e.querySelector('.ml-guide__glyph')?.getAttribute('d') ?? ''));
  expect(new Set(shapes).size).toBe(5);
  const rings = await items.evaluateAll((els) =>
    els.map((e) => {
      const ring = e.querySelector('.ml-guide__ring');
      return `${ring?.getAttribute('stroke-width')}/${ring?.getAttribute('stroke-dasharray')}`;
    }),
  );
  expect(new Set(rings).size).toBe(5);
});

test('colour-vision deficiencies: the five bands stay apart by their marks', async ({ page, browserName }, info) => {
  test.skip(browserName !== 'chromium', 'Emulation.setEmulatedVisionDeficiency is a Chromium feature');
  await guidesWithSelection(page);
  await page.locator('.ml-guides-card__legend summary').click();
  const cdp = await page.context().newCDPSession(page);
  const deficiencies = ['none', 'protanopia', 'deuteranopia', 'tritanopia', 'achromatopsia'] as const;
  for (const type of deficiencies) {
    await cdp.send('Emulation.setEmulatedVisionDeficiency', { type });
    // the marks are shapes: whatever the colours look like, the five legend marks differ, and every board mark is drawn
    const legend = await page.locator('[data-legend-band]').evaluateAll((els) => els.map((e) => e.querySelector('.ml-guide__glyph')?.getAttribute('d') ?? ''));
    expect(new Set(legend).size, `${type}: five legend glyphs`).toBe(5);
    const onBoard = await glyphs(page, 'target');
    expect(onBoard.length, `${type}: marks on the board`).toBeGreaterThan(0);
    for (const d of onBoard) expect(d, `${type}: a glyph is not empty`).not.toBe('');
    for (const glyph of await page.locator('svg.ml-board__svg [data-guide="target"] .ml-guide__glyph').all()) {
      const box = await glyph.boundingBox();
      expect(box && box.width > 2 && box.height > 2, `${type}: glyph is visible`).toBe(true);
    }
    await page.screenshot({ path: `${SHOTS}/${info.project.name}-guides-cvd-${type}.png`, fullPage: true, animations: 'disabled' });
    await board(page).screenshot({ path: `${SHOTS}/${info.project.name}-guides-cvd-${type}-board.png`, animations: 'disabled' });
  }
  await cdp.send('Emulation.setEmulatedVisionDeficiency', { type: 'none' });
});

// Owner report 2026-11-19: pieces carried "very good" but their targets said "equal". One scale: the band of a piece is the
// best band among its targets, and the best move of the position is top as a target.
test('the band of every piece equals the best band among its targets, with the real engine on the start position', async ({ page }) => {
  const errors = watchPageErrors(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await page.locator('[data-guides-level="eval"]').check();
  await expect(page.locator('svg.ml-board__svg [data-guide="piece"]').first()).toBeAttached();
  const pieces = await page.locator('svg.ml-board__svg [data-guide="piece"]').evaluateAll((els) =>
    els.map((e) => ({ cell: e.getAttribute('data-cell-of')!, band: e.getAttribute('data-band')! })));
  expect(pieces.length).toBeGreaterThan(0);
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
  errors.expectNone();
});
