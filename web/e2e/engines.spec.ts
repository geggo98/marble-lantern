// Each browser engine plays one exchange: the human moves, the engine answers. The ply counter proves that the answer
// is new, and the engine card proves which engine gave it.
import { expect, test } from '@playwright/test';
import type { EngineId } from './helpers';
import { openApp, pickEngine, playOneExchange, watchPageErrors, waitForHumanTurn } from './helpers';

test('cpp-wasm loads and plays one engine move', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await expect(page.locator('.ml-engine .ml-card__title')).toContainText('C++');
  const telemetry = await playOneExchange(page);
  // telemetry holds nodes, time, depth and source of the last engine move
  expect(telemetry).toMatch(/\d/);
  errors.expectNone();
});

test('java-wasmgc loads and plays one move, or reports that the browser has no Wasm GC', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await page.locator('.ml-engine button[aria-haspopup="dialog"]').click();
  const option = page.getByRole('dialog').locator('[data-engine="java-wasmgc"]');
  await expect(option).toBeVisible();
  if (test.info().project.name !== 'webkit') {
    // Chromium and Firefox have Wasm GC: a disabled option there means that detection or the manifest is broken
    await expect(option).not.toHaveAttribute('aria-disabled', 'true');
  } else if ((await option.getAttribute('aria-disabled')) === 'true') {
    // WebKit only: the same answer the engine gives as wasm-gc-unsupported; the picker marks it as unavailable and says why
    await expect(option.locator('.ml-option__reason')).toBeVisible();
    test.info().annotations.push({ type: 'wasm-gc', description: 'unsupported in this browser (reported)' });
    errors.expectNone();
    return;
  }
  await page.keyboard.press('Escape');
  await pickEngine(page, 'java-wasmgc' satisfies EngineId);
  await expect(page.locator('.ml-engine .ml-card__title')).toContainText('WebAssembly GC');
  await playOneExchange(page);
  test.info().annotations.push({ type: 'wasm-gc', description: 'plays a move' });
  errors.expectNone();
});

test('java-js plays one move', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await pickEngine(page, 'java-js');
  await expect(page.locator('.ml-engine .ml-card__title')).toContainText('JavaScript');
  await playOneExchange(page);
  errors.expectNone();
});
