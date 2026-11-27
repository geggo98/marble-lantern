// The net hints (marks Falle and Spezialzug) in the real app, Chromium only. The first case runs the real net-webgpu engine on software
// Vulkan (SwiftShader, as in net-webgpu.spec.ts): a 5-row game at the level "net" uses the r5 expert model; the chip must leave
// "loading" and "thinking" and end in a state that is no failure. Whether a mark appears depends on the position, so the case does not
// demand one. The second case switches WebGPU off and expects the option disabled with its reason. The marks themselves, their cells and
// their names are covered with a fake net engine in src/app/App.nethints.flow.test.tsx.
import { chromium, expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import en from '../src/i18n/en.json' with { type: 'json' };
import { openApp, waitForHumanTurn, watchPageErrors } from './helpers';

const mode = process.env['WEBGPU_E2E'] ?? 'swiftshader';
const FLAGS: Record<string, string[]> = {
  swiftshader: ['--enable-unsafe-webgpu', '--use-webgpu-adapter=swiftshader', '--enable-features=Vulkan', '--use-vulkan=swiftshader', '--use-angle=swiftshader'],
  gpu: ['--enable-unsafe-webgpu'],
};
const SHOT_DIR = process.env.SHOT_DIR ?? 'test-results/screens';

const hasAdapter = (page: Page): Promise<boolean> =>
  page.evaluate(async () => {
    const gpu = (navigator as unknown as { gpu?: { requestAdapter(): Promise<unknown> } }).gpu;
    return gpu ? (await gpu.requestAdapter()) !== null : false;
  });

// launchOptions are per file (Playwright forbids them in a describe group); the case without WebGPU starts its own browser.
test.use({ launchOptions: { channel: 'chromium', args: [...(FLAGS[mode] ?? FLAGS['swiftshader']!), '--headless=new'] }, viewport: { width: 1280, height: 900 } });

test.describe('net hints with a WebGPU adapter', () => {
  test.skip(({ browserName }) => browserName !== 'chromium', 'WebGPU is exercised in Chromium only');

  test('level net in a 5-row game: the chip leaves loading and thinking and does not fail', async ({ page }, info) => {
    test.setTimeout(420_000);
    const errors = watchPageErrors(page);
    await openApp(page, 'en', '', 'human', 5);
    test.skip(!(await hasAdapter(page)), 'no WebGPU adapter in this browser');
    await waitForHumanTurn(page);
    const net = page.locator('[data-guides-level="net"]');
    await expect(net, 'the net option can be chosen once the device has been probed').not.toHaveAttribute('aria-disabled', 'true');
    await net.check();
    const chip = page.locator('[data-nethint-chip]');
    await expect(chip).toBeAttached();
    // the first search loads the model and runs 800 simulations on software Vulkan: minutes at worst
    await expect
      .poll(async () => await chip.getAttribute('data-state'), { timeout: 360_000, intervals: [1000, 2000, 5000] })
      .not.toMatch(/^(loading|thinking|idle)$/);
    const state = await chip.getAttribute('data-state');
    expect(['marks', 'none', 'skipped']).toContain(state);
    if (state === 'marks') {
      await expect(page.locator('[data-nethint][data-nethint-at="piece"]').first()).toBeAttached();
    } else {
      await expect(page.locator('[data-nethint]')).toHaveCount(0);
    }
    // the stored level is still net and the radio says so
    await expect(net).toBeChecked();
    await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-net-hints-${state}.png`, animations: 'disabled' });
    errors.expectNone();
  });
});

test.describe('net hints without WebGPU', () => {
  test.skip(({ browserName }) => browserName !== 'chromium', 'the flags are Chromium flags');

  test('the net option is disabled with its reason, eval is checked, and no chip shows', async ({ baseURL }) => {
    const browser = await chromium.launch({ channel: 'chromium', args: ['--disable-gpu', '--disable-features=WebGPU,Vulkan', '--headless=new'] });
    const context = await browser.newContext({ baseURL: baseURL ?? '', viewport: { width: 1280, height: 900 }, locale: 'en-US', reducedMotion: 'reduce', serviceWorkers: 'block' });
    const page = await context.newPage();
    try {
      await noGpuCase(page);
    } finally {
      await browser.close();
    }
  });
});

async function noGpuCase(page: Page): Promise<void> {
  const errors = watchPageErrors(page);
  // the person chose the net earlier (stored level net); this browser cannot run it, so the card shows eval
  await page.addInitScript(() => localStorage.setItem('ml.guides', 'net'));
  await openApp(page, 'en', '', 'human', 5);
  test.skip(await hasAdapter(page), 'this Chromium still has a WebGPU adapter with WebGPU switched off');
  await waitForHumanTurn(page);
  const net = page.locator('[data-guides-level="net"]');
  await expect(net).toHaveAttribute('aria-disabled', 'true');
  // which code the browser reports depends on how it lacks WebGPU
  const reasons = ['webgpu-no-api', 'webgpu-no-adapter', 'webgpu-device-failed'].map((c) => en[`net.reason.${c}` as keyof typeof en]);
  const shown = (await page.locator('[data-guides-net-off]').textContent()) ?? '';
  expect(reasons.some((r) => shown.includes(r)), shown).toBe(true);
  // choosing it changes nothing
  await net.click({ force: true });
  await expect(net).not.toBeChecked();
  await expect(page.locator('[data-guides-level="eval"]')).toBeChecked();
  await expect(page.locator('[data-nethint-chip]')).toHaveCount(0);
  await expect(page.locator('[data-nethint]')).toHaveCount(0);
  errors.expectNone();
}
