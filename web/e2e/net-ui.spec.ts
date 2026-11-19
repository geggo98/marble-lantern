// The net engines through the UI: the model and simulation groups of the engine sheet, the warnings before and during play, the
// compact notice of the play screen, and the start dialog. Selectors are data attributes, so the tests run in both languages.
// Desktop in all browsers; the same flows at 390 px (Chromium; Firefox cannot emulate a phone: skipped). Screenshots go to SHOT_DIR
// (default test-results/screens). net-webgpu without an adapter is in net-ui-nogpu.spec.ts.
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp, openEngineSheet, pickEngine, playExchange, waitForHumanTurn, watchPageErrors, withCards } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR ?? 'test-results/screens';
const EXPERT = 'small-2p-adjacent-expert';
const SHARED = 'small-shared-all';

const shot = async (page: Page, name: string): Promise<void> => {
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, animations: 'disabled' });
};

const noOverflow = async (page: Page): Promise<void> => {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, 'horizontal page scroll').toBeLessThanOrEqual(0);
};

/** The engine card shows the codes of the session warnings: the card is in the menu on a small screen, so read the attribute. */
const warningCodes = (page: Page): Locator => page.locator('.ml-engine');

for (const size of [
  { name: 'desktop', width: 1280, height: 900 },
  { name: 'phone390', width: 390, height: 844 },
] as const) {
  test.describe(`net engines in the UI, ${size.name}`, () => {
    test.skip(({ browserName }) => size.name === 'phone390' && browserName === 'firefox', 'Firefox cannot emulate a phone');
    test.use({ viewport: { width: size.width, height: size.height } });

    const closeMenu = async (page: Page): Promise<void> => {
      const close = page.locator('[data-menu-close]');
      if (await close.isVisible()) await close.click();
    };

    test('net-wasm without a model: the sheet warns, the notice shows, and the engine still moves (random)', async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const dialog = await openEngineSheet(page);
      await dialog.locator('[data-engine="net-wasm"]').click();
      // None is the default and checked; the warning is live in the sheet
      await expect(dialog.locator('[data-model="none"] input')).toBeChecked();
      await expect(dialog.locator('[data-net-warning="no-model"]')).toBeVisible();
      await expect(dialog.locator('[data-net-group="sims"]')).toBeVisible();
      await expect(dialog.locator('input[type="range"]')).toHaveCount(0);
      await noOverflow(page);
      await shot(page, `${info.project.name}-${size.name}-sheet-net-no-model`);
      // the button moves focus to the model group
      await dialog.locator('[data-warning-action="choose-model"]').click();
      await expect(dialog.locator('[data-model="none"] input')).toBeFocused();
      await dialog.locator('[data-sims="50"] input').check();
      await dialog.locator('.ml-sheet__actions .ml-btn--primary').click();
      await expect(dialog).toBeHidden();
      await closeMenu(page);
      await expect(page.locator('.ml-play__netnote')).toHaveAttribute('data-net-notice', 'random');
      await expect(page.locator('[data-net-notice-open]')).toBeVisible();
      await noOverflow(page);
      await playExchange(page);
      await withCards(page, async () => {
        await expect(page.locator('.ml-telemetry')).toHaveAttribute('data-net-backend', 'random');
        await expect(warningCodes(page)).toHaveAttribute('data-net-warnings', 'no-model,fallback-random');
      });
      await shot(page, `${info.project.name}-${size.name}-play-notice-random`);
      // the notice opens the sheet again
      await page.locator('[data-net-notice-open]').click();
      await expect(page.getByRole('dialog').filter({ has: page.locator('[data-engine]') })).toBeVisible();
      await page.keyboard.press('Escape');
      errors.expectNone();
    });

    test('the expert model: no warning, no notice, the net plays on the CPU', async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      await pickEngine(page, 'net-wasm', EXPERT);
      await closeMenu(page);
      await expect(page.locator('.ml-play__netnote'), 'no notice element while the net plays').toHaveCount(0);
      await playExchange(page);
      await shot(page, `${info.project.name}-${size.name}-play-net-ok`);
      await withCards(page, async () => {
        await expect(page.locator('.ml-telemetry')).toHaveAttribute('data-net-backend', 'wasm');
        await expect(warningCodes(page)).toHaveAttribute('data-net-warnings', '');
        await expect(page.locator('.ml-telemetry .ml-telemetry__cell')).toHaveCount(3);
      });
      // the sheet shows what was applied
      const dialog = await openEngineSheet(page);
      await expect(dialog.locator(`[data-model="${EXPERT}"] input`)).toBeChecked();
      await expect(dialog.locator('[data-sims="50"] input')).toBeChecked();
      await shot(page, `${info.project.name}-${size.name}-sheet-net-expert`);
      errors.expectNone();
    });

    test('three players: the New game dialog warns about the expert model that plays, the sheet offers the shared one only', async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      await pickEngine(page, 'net-wasm', EXPERT);
      await closeMenu(page);
      await page.getByRole('button', { name: 'New game' }).first().click();
      const start = page.locator('[data-dialog="begin"]');
      await expect(start).toBeVisible();
      await expect(start.locator('[data-net-warning]')).toHaveCount(0); // two players, adjacent: the expert fits
      await start.locator('[data-choice="mode-hotseat"]').check();
      await expect(start.locator('[data-net-warning="model-variant-mismatch"]')).toBeVisible();
      await noOverflow(page);
      await shot(page, `${info.project.name}-${size.name}-start-dialog-mismatch`);
      await expect(start.locator('[data-choice="begin-start"]')).toBeEnabled(); // it does not block starting
      await start.locator('[data-choice="begin-start"]').click();
      await expect(start).toBeHidden();
      const dialog = await openEngineSheet(page);
      await dialog.locator('[data-engine="net-wasm"]').click();
      await expect(dialog.locator(`[data-model="${EXPERT}"]`)).toHaveCount(0);
      await expect(dialog.locator(`[data-model="${SHARED}"]`)).toHaveCount(1);
      await expect(dialog.locator('[data-net-hidden-note]')).toBeVisible();
      await expect(dialog.locator('[data-net-warning="model-variant-mismatch"]')).toBeVisible();
      // the shared model of a 3-player game: its 3-player measurement, labelled with the player count, no 2-player line
      await expect(dialog.locator(`[data-model="${SHARED}"] [data-strength-fact][data-players="3"]`)).toHaveCount(2);
      await expect(dialog.locator(`[data-model="${SHARED}"] [data-strength-fact][data-players="2"]`)).toHaveCount(0);
      await shot(page, `${info.project.name}-${size.name}-sheet-net-3p-mismatch`);
      await dialog.locator(`[data-model="${SHARED}"] input`).check();
      await expect(dialog.locator('[data-net-warning]')).toHaveCount(0);
      errors.expectNone();
    });

    test('a stored net engine does not warn at the next visit, a classical engine plays, and the stored model stays (H1)', async ({ page }) => {
      const errors = watchPageErrors(page);
      await page.addInitScript(
        ([key, value]) => {
          if (!sessionStorage.getItem('seeded')) {
            sessionStorage.setItem('seeded', '1');
            localStorage.setItem(key!, value!);
          }
        },
        ['ml.net-selection', JSON.stringify({ engine: 'net-wasm', modelId: null, sims: 800 })],
      );
      await openApp(page, 'en', '', 'ask');
      const start = page.locator('[data-dialog="begin"]');
      await expect(start).toBeVisible();
      await expect(start.locator('[data-net-warning]'), 'no warning for a net engine that does not play').toHaveCount(0);
      await start.locator('[data-choice="begin-yes"]').click();
      await waitForHumanTurn(page);
      await withCards(page, async () => {
        await expect(page.locator('.ml-engine')).not.toHaveAttribute('data-net-warnings', /.*/); // a classical engine: no net warnings at all
      });
      await expect(page.locator('.ml-play__netnote')).toHaveCount(0);
      const stored = (): Promise<{ engine: string; sims: number }> => page.evaluate(() => JSON.parse(localStorage.getItem('ml.net-selection') ?? '{}') as { engine: string; sims: number });
      await expect.poll(async () => (await stored()).engine, 'the store names the engine that was loaded').not.toBe('net-wasm');
      expect((await stored()).sims).toBe(800);
      errors.expectNone();
    });

    test('German: the longer texts do not overflow the sheet or the notice', async ({ page }, info) => {
      await openApp(page, 'de');
      await waitForHumanTurn(page);
      const dialog = await openEngineSheet(page);
      await dialog.locator('[data-engine="net-wasm"]').click();
      await noOverflow(page);
      const sheet = await dialog.boundingBox();
      expect(sheet!.x + sheet!.width).toBeLessThanOrEqual(size.width + 1);
      await shot(page, `${info.project.name}-${size.name}-sheet-net-de`);
      await dialog.locator('.ml-sheet__actions .ml-btn--primary').click();
      await closeMenu(page);
      await expect(page.locator('.ml-play__netnote')).toHaveAttribute('data-net-notice', 'random');
      await noOverflow(page);
      await shot(page, `${info.project.name}-${size.name}-play-notice-de`);
    });

    test('geometry: a net game without a notice has exactly the board box of a classical game; the notice moves it at apply, never during play', async ({ page }, info) => {
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const svg = page.locator('svg.ml-board__svg');
      const box = async (): Promise<{ x: number; y: number; width: number; height: number }> => (await svg.boundingBox())!;
      const classical = await box();
      expect(await page.locator('.ml-play__netnote').count(), 'no notice element in a classical game').toBe(0);
      expect(await page.locator('.ml-play .ml-play__netnote, .ml-play [role="status"][aria-label]').count(), 'no extra live region').toBe(0);
      info.annotations.push({ type: 'board-classical', description: JSON.stringify(classical) });
      // the same grid as before the net UI: on a phone four rows (status, board, advantage, bar), an extra row would take 8 px gap from the board
      const rows = async (): Promise<number> => await page.locator('main.ml-play').evaluate((el) => getComputedStyle(el).gridTemplateRows.split(' ').length);
      if (size.name === 'phone390') expect(await rows(), 'grid rows of a classical game').toBe(4);
      const children = await page.locator('main.ml-play .ml-play__stage > *').count();
      await pickEngine(page, 'net-wasm', EXPERT);
      await closeMenu(page);
      await expect(page.locator('.ml-play__netnote')).toHaveCount(0);
      const working = await box();
      info.annotations.push({ type: 'board-net-ok', description: JSON.stringify(working) });
      for (const key of ['x', 'y', 'width', 'height'] as const) expect(Math.abs(working[key] - classical[key]), `net game without notice, board ${key}`).toBeLessThanOrEqual(1);
      await pickEngine(page, 'net-wasm', null); // random moves: the notice is mounted
      await closeMenu(page);
      await expect(page.locator('.ml-play__netnote')).toHaveCount(1);
      const noticed = await box();
      if (size.name === 'phone390') expect(await rows(), 'grid rows with the notice').toBe(5);
      expect(await page.locator('main.ml-play .ml-play__stage > *').count(), 'the notice is one more child').toBe(children + 1);
      info.annotations.push({ type: 'board-notice', description: JSON.stringify(noticed) });
      await playExchange(page);
      const after = await box();
      for (const key of ['x', 'y', 'width', 'height'] as const) expect(Math.abs(after[key] - noticed[key]), `during play, board ${key}`).toBeLessThanOrEqual(1);
      // back to a classical engine: the notice leaves, the board is where it was
      await pickEngine(page, 'cpp-wasm');
      await closeMenu(page);
      await expect(page.locator('.ml-play__netnote')).toHaveCount(0);
      if (size.name === 'phone390') expect(await rows(), 'grid rows after the notice left').toBe(4);
      expect(await page.locator('main.ml-play .ml-play__stage > *').count()).toBe(children);
      const back = await box();
      for (const key of ['x', 'y', 'width', 'height'] as const) expect(Math.abs(back[key] - classical[key]), `back to classical, board ${key}`).toBeLessThanOrEqual(1);
    });

    test('the engine card button "choose a model" opens the sheet with the focus on the model group (L2)', async ({ page }) => {
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      await pickEngine(page, 'net-wasm', null);
      await closeMenu(page);
      await withCards(page, async () => {
        await page.locator('.ml-engine [data-warning-action="choose-model"]').click();
        const dialog = page.getByRole('dialog').filter({ has: page.locator('[data-engine]') });
        await expect(dialog).toBeVisible();
        await expect(dialog.locator('[data-model="none"] input')).toBeFocused();
        await page.keyboard.press('Escape');
      });
    });
  });
}
