import { expect, test } from '@playwright/test';
import { openApp, waitForHumanTurn, watchPageErrors } from './helpers';

test('the page loads and shows the offline badge', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await expect(page).toHaveTitle(/Marble Lantern/);
  await expect(page.locator('.ml-badge')).toHaveText(/offline/i);
  await expect(page.locator('.ml-badge--online')).toHaveCount(0);
  // the default engine starts and the game opens
  await waitForHumanTurn(page);
  errors.expectNone();
});

// The UI has no German text catalogue yet (src/ui/texts.ts holds English placeholders, and "offline" is the same word in
// both languages). So the test checks what the stored language changes today: the language switch marks "de" as chosen
// and "en" as not chosen. It must switch to a German text check once the German UI catalogue exists.
test('the stored German language is the chosen one', async ({ page }) => {
  await openApp(page, 'de');
  await expect(page.locator('.ml-badge')).toHaveText(/offline/i);
  await expect(page.locator('.ml-seg button[lang="de"]')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.ml-seg button[lang="en"]')).toHaveAttribute('aria-pressed', 'false');
});

test('the three screens open from the navigation', async ({ page }) => {
  await openApp(page, 'en');
  for (const name of ['bench', 'about', 'play']) {
    await page.locator(`.ml-nav__link[href="#/${name}"]`).click();
    await expect(page.locator(`.ml-nav__link[href="#/${name}"]`)).toHaveAttribute('aria-current', 'page');
  }
});

test('the static build asks for no server endpoint and no other origin', async ({ page, baseURL }) => {
  const origin = new URL(baseURL!).origin;
  const calls: string[] = [];
  page.on('request', (r) => {
    const url = r.url();
    // data: and blob: URLs never leave the browser
    if (/^(data|blob|about):/.test(url)) return;
    if (url.includes('/connect/') || new URL(url).origin !== origin) calls.push(url);
  });
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  expect(calls).toEqual([]);
});
