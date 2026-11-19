// net-webgpu in a Chromium WITHOUT WebGPU: the engine plays with the CPU net and says so (warning backend-unavailable). The browser is
// started with WebGPU switched off; if it still has an adapter (some machines), the test skips and says so, because then it would
// test the GPU path, which net-webgpu.spec.ts covers. The telemetry (data-net-backend) tells the CPU fallback from GPU play.
import { expect, test } from '@playwright/test';
import { openApp, pickEngine, playOneExchange, watchPageErrors, waitForHumanTurn } from './helpers';

test.use({ launchOptions: { channel: 'chromium', args: ['--disable-gpu', '--disable-features=WebGPU,Vulkan', '--headless=new'] } });

test.describe('net-webgpu without WebGPU', () => {
  test.skip(({ browserName }) => browserName !== 'chromium', 'the flags are Chromium flags');

  test('the CPU net plays, and backend-unavailable is shown', async ({ page }) => {
    const errors = watchPageErrors(page);
    await page.addInitScript(([key, value]) => localStorage.setItem(key!, value!), [
      'ml.net-selection',
      JSON.stringify({ engine: 'net-webgpu', modelId: 'small-2p-adjacent-expert', sims: 50 }),
    ]);
    await openApp(page, 'en');
    const hasAdapter = await page.evaluate(async () => {
      const gpu = (navigator as unknown as { gpu?: { requestAdapter(): Promise<unknown> } }).gpu;
      return gpu ? (await gpu.requestAdapter()) !== null : false;
    });
    test.skip(hasAdapter, 'this Chromium still has a WebGPU adapter with WebGPU switched off');
    await waitForHumanTurn(page);
    await pickEngine(page, 'net-webgpu');
    await playOneExchange(page);
    await expect(page.locator('.ml-telemetry__value').first()).not.toHaveText(/^\s*0\s*$/); // a net search, on the CPU
    await expect(page.locator('.ml-telemetry')).toHaveAttribute('data-net-backend', 'wasm');
    await expect(page.locator('.ml-engine')).toHaveAttribute('data-net-warnings', 'backend-unavailable');
    errors.expectNone();
  });
});
