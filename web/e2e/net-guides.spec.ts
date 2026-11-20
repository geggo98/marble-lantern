// Move guides while a net engine plays (docs/web-ui.md, "Analysis for the net engines"): the net engine cannot analyse, so a
// classical engine in its own worker computes the marks and the advantage bar. Real engines, the expert model, desktop.
import { expect, test } from '@playwright/test';
import en from '../src/i18n/en.json' with { type: 'json' };
import { openApp, pickEngine, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

const EXPERT = 'small-2p-adjacent-expert';

test('net-wasm with the expert model and guides on: badges, the advantage bar and a note that names the analysis engine', async ({ page }) => {
  const errors = watchPageErrors(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await pickEngine(page, 'net-wasm', EXPERT);
  await waitForHumanTurn(page);
  await page.locator('[data-guides-toggle]').check();
  await expect(page.locator('svg.ml-board__svg [data-guide="piece"]').first()).toBeAttached();
  await expect(page.locator('.ml-advantage')).toBeVisible();
  await expect(page.locator('[data-guides-status]')).not.toHaveAttribute('data-guides-status', 'unavailable');
  // the default classical engine of this browser: java-wasmgc where Wasm GC runs, else cpp-wasm
  const named = ['engine.javaWasmGc', 'engine.cppWasm', 'engine.javaJs'].map((k) => en.guides_note_net.replace('{engine}', en[k as keyof typeof en]));
  await expect(page.locator('[data-guides-note-net]')).toBeVisible();
  expect(named).toContain(await page.locator('[data-guides-note-net]').textContent());
  // the human plays the move the guides rate best, the net engine answers, and the guides follow the next position
  await page.locator('[data-cell][aria-label*="best move"]').first().click();
  await expect(page.locator('svg.ml-board__svg [data-guide="target"]').first()).toBeAttached();
  await page.locator(`[data-cell][aria-label$="target, ${en.guide_move_much_better}"]`).first().click();
  await expect.poll(() => plyOf(page)).toBe(2);
  await waitForHumanTurn(page);
  await expect(page.locator('svg.ml-board__svg [data-guide="piece"]').first()).toBeAttached();
  await expect(page.locator('.ml-telemetry')).toHaveAttribute('data-net-backend', 'wasm');
  errors.expectNone();
});
