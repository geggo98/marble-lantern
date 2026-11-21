// Screenshots of the live states of V10 (Fig. 3.3) and V9b (Fig. 3.2) for the visual review. Not part of CI: it runs only when SHOT_DIR is set.
//   SHOT_DIR=/some/dir npm exec --no -- playwright test e2e/about-shots-live.spec.ts --project=chromium
// For each size, theme and live state it writes SHOT_DIR/<figure>-<w>x<h>-<theme>-<state>.png (the figure alone) and checks that neither the page nor
// the figure scrolls sideways. States: loading (the model download is held back), done, stopped (Stop pressed during the run), model error (the model
// download fails), and for V9b the done state with the second plane.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, waitForHumanTurn } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR;
/** The 30 plies of eval-mid.json; keep equal to EXAMPLE_MID in about-live.spec.ts. */
const EXAMPLE_MID = '#g=v1.BkOeYgdzWQCnxNEEZsICz6uCL3hnOxu9pTRzzmdzpQo9bMSNbnOwg-IyCknC8McXpQ5tKaA&h=0&l=2';
const SIZES = [
  { width: 320, height: 568 },
  { width: 390, height: 844 },
  { width: 820, height: 1180 },
] as const;
const FIGURES = [
  { id: 'fig-mcts', section: 'mcts' },
  { id: 'fig-forward', section: 'forward-pass' },
] as const;
type State = 'loading' | 'done' | 'stopped' | 'model-error';

async function openFigure(page: Page, section: string): Promise<void> {
  await openApp(page, 'en', EXAMPLE_MID);
  await page.locator('[data-choice="link-side-to-move"]').click();
  await waitForHumanTurn(page);
  const menu = page.locator('.ml-play__bar [aria-controls]'); // on a phone the cards are in the menu
  if (await menu.isVisible()) await menu.click();
  await page.locator(`a[data-explain-link="${section}"]`).click();
}

async function press(page: Page, id: string): Promise<void> {
  if (id === 'fig-mcts') await page.locator('#fig-mcts [data-live-switch] label.ml-fig-seg__opt').filter({ hasText: 'My game' }).click();
  else await page.locator('#fig-forward select.ml-fig-select').first().selectOption('mine');
}

test.describe('live figure screenshots', () => {
  test.skip(!SHOT_DIR, 'set SHOT_DIR to write the screenshots');

  for (const figure of FIGURES) {
    for (const size of SIZES) {
      for (const theme of ['light', 'dark'] as const) {
        for (const state of ['loading', 'done', 'stopped', 'model-error'] as State[]) {
          test(`${figure.id} ${size.width}x${size.height} ${theme} ${state}`, async ({ page }) => {
            page.on('pageerror', (error) => console.log(`[pageerror] ${error.message}`));
            await page.setViewportSize(size);
            await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
            if (state === 'loading') await page.route('**/models/**', async (route) => { await new Promise((r) => setTimeout(r, 4000)); await route.continue(); });
            if (state === 'model-error') await page.route('**/models/**', (route) => route.abort());
            await openFigure(page, figure.section);
            const fig = page.locator(`#${figure.id}`);
            await expect(fig).toBeVisible();
            if (state === 'stopped') {
              // Stop at the third finished step (V10); for V9b the single call is stopped while the model loads
              await page.route('**/models/**', async (route) => { await new Promise((r) => setTimeout(r, 1500)); await route.continue(); });
            }
            await press(page, figure.id);
            const status = fig.locator('[role=status]');
            if (state === 'loading') await expect(status).toContainText(/Loading the engine|Computing/);
            if (state === 'done') await expect(status).toContainText('Computed in your browser.', { timeout: 90_000 });
            if (state === 'model-error') await expect(status).toContainText('The model could not be loaded.', { timeout: 90_000 });
            if (state === 'stopped') {
              await fig.locator('[data-live-stop]').click();
              await expect(status).toContainText('Stopped.');
            }
            const page_overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
            expect(page_overflow, 'horizontal page scroll').toBeLessThanOrEqual(0);
            expect(await fig.evaluate((el) => el.scrollWidth - el.clientWidth), `${figure.id} overflows its frame`).toBeLessThanOrEqual(0);
            // no element of the figure reaches beyond the viewport
            const wide = await fig.evaluate((el) => {
              const limit = document.documentElement.clientWidth + 0.5;
              return [...el.querySelectorAll<HTMLElement>('*')]
                .filter((e) => e.getBoundingClientRect().right > limit && !e.closest('[aria-hidden="true"], svg, .ml-visually-hidden'))
                .slice(0, 5)
                .map((e) => `${e.tagName}.${e.className}`);
            });
            expect(wide, 'elements beyond the viewport').toEqual([]);
            await fig.scrollIntoViewIfNeeded();
            await fig.screenshot({ path: `${SHOT_DIR}/${figure.id}-${size.width}x${size.height}-${theme}-${state}.png`, animations: 'disabled' });
          });
        }
      }
    }
  }
});
