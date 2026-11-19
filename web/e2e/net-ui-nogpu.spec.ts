// net-webgpu in a Chromium without WebGPU, through the UI: the sheet row says not available with the reason, the CPU net plays, and the
// notice says so. The flags are Chromium flags; where the browser still has an adapter the test skips (net-webgpu.spec.ts covers GPU play).
import { expect, test } from '@playwright/test';
import { openApp, openEngineSheet, playOneExchange, waitForHumanTurn, watchPageErrors } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR ?? 'test-results/screens';

test.use({ launchOptions: { channel: 'chromium', args: ['--disable-gpu', '--disable-features=WebGPU,Vulkan', '--headless=new'] } });

test.describe('net-webgpu without WebGPU, UI', () => {
  test.skip(({ browserName }) => browserName !== 'chromium', 'the flags are Chromium flags');

  test('the row says unavailable, the CPU net plays, the notice names the fallback', async ({ page }, info) => {
    const errors = watchPageErrors(page);
    await openApp(page, 'en');
    const hasAdapter = await page.evaluate(async () => {
      const gpu = (navigator as unknown as { gpu?: { requestAdapter(): Promise<unknown> } }).gpu;
      return gpu ? (await gpu.requestAdapter()) !== null : false;
    });
    test.skip(hasAdapter, 'this Chromium still has a WebGPU adapter with WebGPU switched off');
    await waitForHumanTurn(page);
    const dialog = await openEngineSheet(page);
    await expect(dialog.locator('[data-webgpu-status]'), 'the status shows only once the WebGPU row is selected').toHaveCount(0);
    await dialog.locator('[data-engine="net-webgpu"]').click();
    await expect(dialog.locator('[data-webgpu-status="unavailable"]')).toBeVisible();
    await dialog.locator('[data-model="small-2p-adjacent-expert"] input').check();
    await dialog.locator('[data-sims="50"] input').check();
    await expect(dialog.locator('[data-net-warning="backend-unavailable"]')).toBeVisible();
    await expect(dialog.locator('[data-net-warning="no-model"]')).toHaveCount(0);
    await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-sheet-net-webgpu-unavailable.png`, animations: 'disabled' });
    await dialog.locator('.ml-sheet__actions .ml-btn--primary').click();
    await expect(dialog).toBeHidden();
    await expect(page.locator('.ml-play__netnote')).toHaveAttribute('data-net-notice', 'cpu');
    await playOneExchange(page);
    await expect(page.locator('.ml-telemetry')).toHaveAttribute('data-net-backend', 'wasm');
    await expect(page.locator('.ml-engine')).toHaveAttribute('data-net-warnings', 'backend-unavailable');
    await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-play-notice-cpu.png`, animations: 'disabled' });
    errors.expectNone();
  });
});
