// Shared helpers of the browser tests. Selectors use class names and data attributes of the UI, not texts, so the tests
// run in both languages.
import { expect } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';

export type EngineId = 'cpp-wasm' | 'java-wasmgc' | 'java-js' | 'net-wasm' | 'net-webgpu';

export const LANGUAGE_KEY = 'ml.language';
export const CHOICE_KEY = 'ml.start-choice';

/**
 * The board size a page starts with. A fresh visitor gets 5 rows (the default of the start dialog); the older specs play the 4-row board,
 * so `openApp` stores 4 rows unless a spec asks for 5, or for 'fresh', which stores nothing and shows the real default.
 */
export type RowsOption = 4 | 5 | 'fresh';

/**
 * Opens the page in the given language (the UI reads the stored choice before the browser language).
 * A page without a game link asks "Do you want to begin?" first; `begin` answers it: 'human' (the default) lets the
 * human move first, 'engine' lets the engine begin, 'ask' leaves the dialog open. Routes (#/bench, #/about) and game
 * links have no such question, so nothing is answered for them. `rows` is the board size the stored start choice holds; a stored
 * choice that has a size already (a spec that saved one) is kept.
 */
export async function openApp(
  page: Page,
  language: 'en' | 'de' = 'en',
  hash = '',
  begin: 'human' | 'engine' | 'ask' = 'human',
  rows: RowsOption = 4,
): Promise<void> {
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
  if (rows !== 'fresh') {
    await page.addInitScript(
      ([key, size]) => {
        try {
          const text = localStorage.getItem(key!);
          const stored = text === null ? {} : (JSON.parse(text) as Record<string, unknown>);
          if (stored && typeof stored === 'object' && !('rows' in stored)) localStorage.setItem(key!, JSON.stringify({ ...stored, rows: Number(size), v: 2 }));
        } catch {
          // the page falls back to the default choice
        }
      },
      [CHOICE_KEY, String(rows)],
    );
  }
  await page.goto(hash);
  if (begin !== 'ask' && !hash.startsWith('#/') && !hash.startsWith('#g=')) {
    await page.locator(`[data-choice="begin-${begin === 'human' ? 'yes' : 'no'}"]`).click();
  }
}

/** True if this browser runs WebAssembly with GC (the same probe as the app's). */
export async function hasWasmGc(page: Page): Promise<boolean> {
  return await page.evaluate(() => {
    const probe = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x03, 0x01, 0x5f, 0x00]);
    return WebAssembly.validate(probe);
  });
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

/** Opens the engine dialog from the engine card; on a small screen the card lives in the menu, which opens first. */
export async function openEngineSheet(page: Page): Promise<Locator> {
  const menuButton = page.locator('.ml-play__bar [aria-controls]');
  if (await menuButton.isVisible()) await menuButton.click();
  await page.locator('.ml-engine button[aria-haspopup="dialog"]').click();
  const dialog = page.getByRole('dialog').filter({ has: page.locator('[data-engine]') });
  await expect(dialog).toBeVisible();
  return dialog;
}

/**
 * Opens the engine dialog, picks an engine and the lowest strength, applies. A classical engine: the slider at 1,000 nodes. A net
 * engine: 50 simulations, and `model` (a model id, null for None) when given; without `model` the model stays as it is.
 */
export async function pickEngine(page: Page, id: EngineId, model?: string | null): Promise<void> {
  const dialog = await openEngineSheet(page);
  await dialog.locator(`[data-engine="${id}"]`).click();
  if (id === 'net-wasm' || id === 'net-webgpu') {
    if (model !== undefined) await dialog.locator(`[data-model="${model ?? 'none'}"] input`).check();
    await dialog.locator('[data-sims="50"] input').check();
  } else {
    await dialog.locator('input[type="range"]').fill('0');
  }
  await dialog.locator('.ml-sheet__actions .ml-btn--primary').click();
  await expect(dialog).toBeHidden();
}

/** Runs `fn` with the side cards (engine card, telemetry) reachable: on a small screen they live in the menu, which is opened and closed around it. */
export async function withCards<T>(page: Page, fn: () => Promise<T>): Promise<T> {
  const menuButton = page.locator('.ml-play__bar [aria-controls]');
  const small = await menuButton.isVisible();
  if (small) await menuButton.click();
  try {
    return await fn();
  } finally {
    if (small) {
      const close = page.locator('[data-menu-close]');
      if (await close.isVisible()) await close.click();
    }
  }
}

/** Plays a human move and waits for the engine's reply (the ply counter); does not read the telemetry, which is in the menu on a phone. */
export async function playExchange(page: Page): Promise<void> {
  await waitForHumanTurn(page);
  const before = await plyOf(page);
  await playHumanMove(page);
  await expect.poll(() => plyOf(page)).toBe(before + 2);
  await waitForHumanTurn(page);
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

/** Opens the rules panel of the start dialog (it is collapsed whenever the dialog opens); a panel that is open stays open. */
export async function openRulesPanel(page: Page): Promise<void> {
  const panel = page.locator('[data-rules-panel]');
  if ((await panel.getAttribute('open')) === null) await page.locator('[data-rules-summary]').click();
  await expect(panel).toHaveJSProperty('open', true);
}
