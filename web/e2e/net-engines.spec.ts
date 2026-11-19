// The net engines in the app: net-wasm (CPU) and net-webgpu play a move through the normal engine dialog. The model comes from the stored
// selection (key ml.net-selection), because the model picker is not part of the UI yet. Without a model the engine plays a random legal
// move, and the game goes on. net-webgpu falls back to the CPU net where the browser has no WebGPU (Firefox, headless Chromium without
// an adapter); the WebGPU path with a real adapter is in net-webgpu.spec.ts.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { EngineId } from './helpers';
import { openApp, pickEngine, playOneExchange, watchPageErrors, waitForHumanTurn } from './helpers';

const SELECTION_KEY = 'ml.net-selection';

async function storeSelection(page: Page, selection: { engine: string; modelId: string | null; sims: number }): Promise<void> {
  await page.addInitScript(
    ([key, value]) => {
      try {
        localStorage.setItem(key!, value!);
      } catch {
        // the app starts with the default selection
      }
    },
    [SELECTION_KEY, JSON.stringify(selection)],
  );
}

for (const engine of ['net-wasm', 'net-webgpu'] as const satisfies readonly EngineId[]) {
  test(`${engine} with the expert model plays one engine move`, async ({ page }) => {
    const errors = watchPageErrors(page);
    await storeSelection(page, { engine, modelId: 'small-2p-adjacent-expert', sims: 50 });
    await openApp(page, 'en');
    await waitForHumanTurn(page);
    await pickEngine(page, engine);
    await expect(page.locator('.ml-engine .ml-card__title')).toContainText('Neural network');
    await playOneExchange(page);
    // The first telemetry cell is the size of the search tree: a net search has one, a random move has none.
    await expect(page.locator('.ml-telemetry__value').first()).not.toHaveText(/^\s*0\s*$/);
    if (engine === 'net-wasm') {
      // Not the GPU, not random: the CPU backend played, and nothing is wrong. (net-webgpu may play on either backend here:
      // net-webgpu.spec.ts pins it down.)
      await expect(page.locator('.ml-telemetry')).toHaveAttribute('data-net-backend', 'wasm');
      await expect(page.locator('.ml-engine')).toHaveAttribute('data-net-warnings', '');
    } else {
      await expect(page.locator('.ml-telemetry')).toHaveAttribute('data-net-backend', /^(wasm|webgpu)$/);
    }
    errors.expectNone();
  });
}

test('net-wasm without a model still answers: it plays a random legal move', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await pickEngine(page, 'net-wasm');
  await playOneExchange(page);
  await expect(page.locator('.ml-telemetry__value').first()).toHaveText(/^\s*0\s*$/);
  await expect(page.locator('.ml-telemetry')).toHaveAttribute('data-net-backend', 'random');
  await expect(page.locator('.ml-engine')).toHaveAttribute('data-net-warnings', 'no-model,fallback-random');
  errors.expectNone();
});
