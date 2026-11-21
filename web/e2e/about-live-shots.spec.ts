// Screenshots and overflow checks of the live figures at 320 px (V6 Fig. 2.3, root-scores board Fig. 2.1b, V7 budget) in the states
// "Example", "My game" (ready), "running" (as far as it can be caught) and "failed" (engine files blocked).
// Not part of CI: skipped unless SHOT_DIR names a directory.
//   SHOT_DIR=/some/dir npm exec -- playwright test e2e/about-live-shots.spec.ts --project=chromium
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, waitForHumanTurn } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR;
/** The 30-ply example game of eval-mid.json (same link as in about-live.spec.ts). */
const EXAMPLE_MID = '#g=v1.BkOeYgdzWQCnxNEEZsICz6uCL3hnOxu9pTRzzmdzpQo9bMSNbnOwg-IyCknC8McXpQ5tKaA&h=0&l=2';
const FIGURES = [
  ['eval', 'evaluation', 'fig-eval'],
  ['roots', 'alpha-beta', 'fig-roots'],
  ['budget', 'budget', 'fig-budget'],
] as const;

async function overflow(page: Page, id: string): Promise<{ page: number; inside: string[] }> {
  return page.evaluate((figId) => {
    const root = document.scrollingElement!;
    const fig = document.getElementById(figId)!;
    const fr = fig.getBoundingClientRect();
    // anything inside the figure that sticks out of the figure frame sideways, or a frame that scrolls
    const out = Array.from(fig.querySelectorAll<HTMLElement | SVGElement>('*'))
      .filter((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && (r.right > fr.right + 1 || r.left < fr.left - 1);
      })
      .map((el) => `${el.tagName.toLowerCase()}.${(el.getAttribute('class') ?? '').split(' ')[0]}`);
    if (fig.scrollWidth > fig.clientWidth + 1) out.push('figure scrolls');
    return { page: root.scrollWidth - root.clientWidth, inside: [...new Set(out)] };
  }, id);
}

test.describe('live figures at 320 px: screenshots', () => {
  test.skip(!SHOT_DIR, 'set SHOT_DIR to write the screenshots');
  test.setTimeout(45_000);

  for (const [name, section, id] of FIGURES) {
    for (const state of ['example', 'mine', 'running', 'failed'] as const) {
      test(`${name}-${state}`, async ({ page }, info) => {
        const dir = SHOT_DIR!;
        mkdirSync(dir, { recursive: true });
        await page.setViewportSize({ width: 320, height: 568 });
        await openApp(page, 'en', EXAMPLE_MID);
        await page.locator('[data-choice="link-side-to-move"]').click();
        await waitForHumanTurn(page);
        if (state === 'failed') await page.route(/engines\/(cpp|java)\//, (r) => r.abort());
        const menuButton = page.locator(".ml-play__bar [aria-controls]");
        if (await menuButton.isVisible()) await menuButton.click(); // on a phone the Explain card lives in the menu
        await page.locator(`a[data-explain-link="${section}"]`).click();
        const fig = page.locator(`#${id}`);
        await expect(fig).toBeVisible();
        await page.evaluate(() => document.fonts.ready);
        const shot = async (suffix: string): Promise<void> => {
          await fig.scrollIntoViewIfNeeded();
          await fig.screenshot({ path: join(dir, `${info.project.name}-${name}-${suffix}.png`), animations: 'disabled' });
          const o = await overflow(page, id);
          expect(o.page, `${name} ${suffix}: horizontal scroll of the page`).toBeLessThanOrEqual(0);
          expect(o.inside, `${name} ${suffix}: parts outside the figure frame`).toEqual([]);
        };
        if (state === 'example') {
          await shot('example');
          return;
        }
        const press = page.locator(`#${id} [data-live-switch] label.ml-fig-seg__opt`).filter({ hasText: 'My game' });
        await press.click();
        if (state === 'running') {
          await shot('running'); // best effort: the run may already be done
        } else if (state === 'mine') {
          await expect(fig.locator('.ml-fig__status, [role=status]').first()).toContainText('Computed in your browser', { timeout: 60_000 });
          await shot('mine');
        } else {
          await page.waitForTimeout(3000);
          await shot('failed');
        }
      });
    }
  }
});
