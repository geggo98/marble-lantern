// net-webgpu with a real WebGPU adapter in Chromium (software Vulkan by default, as in webgpu-backend.spec.ts). A browser without an
// adapter skips the test and says so. WEBGPU_E2E=gpu uses the real GPU of the machine.
import { expect, test } from '@playwright/test';
import { openApp, pickEngine, playOneExchange, watchPageErrors, waitForHumanTurn } from './helpers';

const mode = process.env['WEBGPU_E2E'] ?? 'swiftshader';
const FLAGS: Record<string, string[]> = {
  swiftshader: ['--enable-unsafe-webgpu', '--use-webgpu-adapter=swiftshader', '--enable-features=Vulkan', '--use-vulkan=swiftshader', '--use-angle=swiftshader'],
  gpu: ['--enable-unsafe-webgpu'],
};
test.use({ launchOptions: { channel: 'chromium', args: [...(FLAGS[mode] ?? FLAGS['swiftshader']!), '--headless=new'] } });

test.describe('net-webgpu with a WebGPU adapter', () => {
  test.skip(({ browserName }) => browserName !== 'chromium', 'WebGPU is exercised in Chromium only');

  test('plays one engine move on the GPU', async ({ page }) => {
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
    test.skip(!hasAdapter, 'no WebGPU adapter in this browser');
    await waitForHumanTurn(page);
    await pickEngine(page, 'net-webgpu');
    await expect(page.locator('.ml-engine .ml-card__title')).toContainText('Neural network');
    await playOneExchange(page);
    await expect(page.locator('.ml-telemetry__value').first()).not.toHaveText(/^\s*0\s*$/); // a net search, not a random move
    errors.expectNone();
  });
});
