// The rules panel of the start dialog (docs/web-ui.md, "Rules panel"): collapsed when the dialog opens, the primary actions stay in view
// while the panel is open, every control is at least 44 px high, nothing scrolls sideways, and the choice reaches the game link.
// Measured in a real browser at 360, 390 and 1280 px. Screenshots go to SHOT_DIR for the visual review.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, openRulesPanel, waitForHumanTurn, watchPageErrors } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR ?? 'test-results/screens';
const SIZES = [
  { name: '360', width: 360, height: 740 },
  { name: '390', width: 390, height: 844 },
  { name: '1280', width: 1280, height: 800 },
] as const;
const MIN = 44;
const TOLERANCE = 0.5;

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    try {
      localStorage.removeItem('ml.start-choice');
    } catch {
      // storage blocked
    }
  });
});

/** Boxes of everything in the dialog that a finger can press: the summary, the options (the label carries the target), the buttons. */
async function targets(page: Page): Promise<{ what: string; width: number; height: number }[]> {
  return await page.evaluate(() => {
    const out: { what: string; width: number; height: number }[] = [];
    const dialog = document.querySelector('[role="dialog"]')!;
    const visible = (el: Element): boolean => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    };
    dialog.querySelectorAll('summary, button, label.ml-start__opt').forEach((el) => {
      if (!visible(el)) return;
      const r = el.getBoundingClientRect();
      out.push({ what: `${el.tagName.toLowerCase()} "${(el.textContent ?? '').trim().slice(0, 30)}"`, width: r.width, height: r.height });
    });
    return out;
  });
}

const sideways = (page: Page): Promise<{ doc: number; sheet: number }> =>
  page.evaluate(() => {
    const sheet = document.querySelector<HTMLElement>('[role="dialog"]')!;
    return { doc: document.documentElement.scrollWidth - window.innerWidth, sheet: sheet.scrollWidth - sheet.clientWidth };
  });

/** The primary button is inside the visible part of the screen. */
async function startInView(page: Page): Promise<{ top: number; bottom: number; vh: number }> {
  return await page.evaluate(() => {
    const r = document.querySelector('[data-choice="begin-yes"]')!.getBoundingClientRect();
    return { top: r.top, bottom: r.bottom, vh: window.innerHeight };
  });
}

for (const size of SIZES) {
  test.describe(`start dialog at ${size.name} px`, () => {
    test.use({ viewport: { width: size.width, height: size.height } });

    test('collapsed: Start is in view without scrolling, the summary says "Standard rules", targets are 44 px, nothing scrolls sideways', async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en', '', 'ask', 'fresh');
      await expect(page.locator('[data-rules-panel]')).toHaveJSProperty('open', false);
      await expect(page.locator('[data-rules-now]')).toHaveText('Standard rules');
      const start = await startInView(page);
      expect(start.top, 'Start button top').toBeGreaterThanOrEqual(0);
      expect(start.bottom, 'Start button inside the window').toBeLessThanOrEqual(start.vh);
      const bad = (await targets(page)).filter((b) => b.height < MIN - TOLERANCE || b.width < MIN - TOLERANCE);
      expect(bad.map((b) => `${b.what} ${b.width.toFixed(1)}x${b.height.toFixed(1)}`), 'targets under 44 px').toEqual([]);
      const o = await sideways(page);
      expect(o.doc, 'document overflow').toBeLessThanOrEqual(0);
      expect(o.sheet, 'dialog overflow').toBeLessThanOrEqual(0);
      await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-rules-collapsed-${size.name}.png`, animations: 'disabled' });
      errors.expectNone();
    });

    test('open: Start stays at the bottom edge of the window while the sheet scrolls, targets are 44 px, nothing scrolls sideways', async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en', '', 'ask', 'fresh');
      await page.locator('[data-choice="mode-engines"]').check(); // the longest panel: the ranking rule shows too
      await openRulesPanel(page);
      await expect(page.locator('[data-choice="ranking-full"]')).toBeVisible();
      const sheetScrolls = await page.evaluate(() => {
        const s = document.querySelector<HTMLElement>('[role="dialog"]')!;
        return s.scrollHeight > s.clientHeight;
      });
      expect(sheetScrolls, 'the open panel makes the sheet scroll (otherwise the check below proves nothing)').toBe(true);
      // scroll the sheet to the end and back: Start is inside the window at both ends
      for (const to of ['bottom', 'top'] as const) {
        await page.evaluate((where) => {
          const s = document.querySelector<HTMLElement>('[role="dialog"]')!;
          s.scrollTop = where === 'bottom' ? s.scrollHeight : 0;
        }, to);
        const r = await page.evaluate(() => {
          const b = document.querySelector('[data-choice="begin-start"]')!.getBoundingClientRect();
          return { top: b.top, bottom: b.bottom, vh: window.innerHeight };
        });
        expect(r.top, `Start top, sheet at ${to}`).toBeGreaterThanOrEqual(0);
        expect(r.bottom, `Start bottom inside the window, sheet at ${to}`).toBeLessThanOrEqual(r.vh);
      }
      await page.evaluate(() => {
        const s = document.querySelector<HTMLElement>('[role="dialog"]')!;
        s.scrollTop = 0;
      });
      const bad = (await targets(page)).filter((b) => b.height < MIN - TOLERANCE || b.width < MIN - TOLERANCE);
      expect(bad.map((b) => `${b.what} ${b.width.toFixed(1)}x${b.height.toFixed(1)}`), 'targets under 44 px').toEqual([]);
      const o = await sideways(page);
      expect(o.doc, 'document overflow').toBeLessThanOrEqual(0);
      expect(o.sheet, 'dialog overflow').toBeLessThanOrEqual(0);
      await page.locator('[data-rules-summary]').evaluate((el) => el.scrollIntoView({ block: 'start' })); // the panel at the top of the sheet, as after a tap
      await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-rules-open-${size.name}.png`, animations: 'disabled' });
      errors.expectNone();
    });
  });
}

test('the rules of the panel reach the game: long hop, open corners and play for 2nd place are in the game link, and the next dialog names them', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '', 'ask', 'fresh');
  await page.locator('[data-choice="mode-hotseat"]').check();
  await openRulesPanel(page);
  await page.locator('[data-choice="jump-long"]').check();
  await page.locator('[data-choice="camps-open"]').check();
  await page.locator('[data-choice="ranking-full"]').check();
  await expect(page.locator('[data-rules-count]')).toHaveText('3 rules changed');
  await page.locator('[data-choice="begin-start"]').click();
  await waitForHumanTurn(page);
  const hash = new URL(page.url()).hash;
  expect(hash).toMatch(/j=long/);
  expect(hash).toMatch(/c=open/);
  expect(hash).toMatch(/r=full/);
  expect(hash).toMatch(/p=3/);
  await page.getByRole('button', { name: 'New game' }).first().click();
  await expect(page.locator('[data-rules-panel]')).toHaveJSProperty('open', false);
  await expect(page.locator('[data-rules-now]')).toHaveText('Jump rule: Long hop · Foreign triangles: Open · After the first win: Play for 2nd place');
  errors.expectNone();
});

test('reset in the panel returns to the standard rules and the next game has none of them', async ({ page }) => {
  await openApp(page, 'en', '', 'ask', 'fresh');
  await openRulesPanel(page);
  await page.locator('[data-choice="jump-long"]').check();
  await page.locator('[data-choice="rules-reset"]').click();
  await expect(page.locator('[data-choice="jump-adjacent"]')).toBeChecked();
  await expect(page.locator('[data-rules-now]')).toHaveText('Standard rules');
  await page.locator('[data-choice="begin-yes"]').click();
  await waitForHumanTurn(page);
  expect(new URL(page.url()).hash).not.toMatch(/j=long/);
});
