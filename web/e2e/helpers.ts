// Shared helpers of the browser tests. Selectors use class names and data attributes of the UI, not texts, so the tests
// run in both languages.
import { expect } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';

export type EngineId = 'cpp-wasm' | 'java-wasmgc' | 'java-js';

export const LANGUAGE_KEY = 'ml.language';

/** Opens the page in the given language (the UI reads the stored choice before the browser language). */
export async function openApp(page: Page, language: 'en' | 'de' = 'en', hash = ''): Promise<void> {
  await page.addInitScript(
    ([key, value]) => {
      try {
        localStorage.setItem(key!, value!);
      } catch {
        // the page falls back to the browser language
      }
    },
    [LANGUAGE_KEY, language],
  );
  await page.goto(hash);
}

/** Collects uncaught page errors; call `.expectNone()` at the end of a test. */
export function watchPageErrors(page: Page): { expectNone(): void } {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  return { expectNone: () => expect(errors, 'uncaught page errors').toEqual([]) };
}

export const plyOf = async (page: Page): Promise<number> => {
  const text = (await page.locator('.ml-status__ply').textContent()) ?? '';
  const match = /(\d+)\s*\/\s*\d+/.exec(text);
  if (!match) throw new Error(`no ply counter in "${text}"`);
  return Number(match[1]);
};

/** The cell that has the one tab stop of the board is the first movable cell while it is the human's turn. */
export const humanTurnCell = (page: Page): Locator => page.locator('[data-cell][aria-disabled="false"][tabindex="0"]');

/** Waits until the human may move (the engine is ready, and it is not searching). */
export async function waitForHumanTurn(page: Page): Promise<void> {
  await expect(humanTurnCell(page)).toHaveCount(1);
}

/** Plays the first movable piece to its first target. */
export async function playHumanMove(page: Page): Promise<void> {
  await waitForHumanTurn(page);
  await humanTurnCell(page).click();
  const target = page.locator('.ml-target__dot').first();
  await expect(target).toBeAttached();
  await target.click({ force: true });
}

/** Opens the engine dialog, picks an engine and the lowest strength (1,000 nodes), applies. */
export async function pickEngine(page: Page, id: EngineId): Promise<void> {
  await page.locator('.ml-engine button[aria-haspopup="dialog"]').click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await dialog.locator(`[data-engine="${id}"]`).click();
  await dialog.locator('input[type="range"]').fill('0');
  await dialog.locator('.ml-sheet__actions .ml-btn--primary').click();
  await expect(dialog).toBeHidden();
}

/**
 * The game is a race of plies, so a reply of the engine can be told from an older one: the ply counter only grows.
 * Waits for the engine's first move if it opens, then plays a human move and waits for the reply to it.
 * Returns the telemetry text of that reply.
 */
export async function playOneExchange(page: Page): Promise<string> {
  await waitForHumanTurn(page);
  const before = await plyOf(page);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(before + 2);
  await waitForHumanTurn(page);
  return (await page.locator('.ml-telemetry').textContent()) ?? '';
}
