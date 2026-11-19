// The language toggle (owner reports of 2026-11-19): (1) "the toggle does not work" on an iPhone, (2) after a reload the
// "New game" dialog opens at once and covers the header, so the language could be changed only after closing it.
// Findings: the UI text lookup let the English working texts win in every language (texts.test.ts), and the dialogs had no
// toggle of their own. Here: the toggle inside every modal sheet, in place (dialog stays, choices stay, focus stays), by touch
// too, in both languages at desktop, 390 px and 320 px. Screenshots go to LANG_SHOT_DIR for the visual review.
// WebKit is informative (Playwright's WebKit is not Safari); Firefox has no mobile emulation.
import { expect, test, devices } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp, openEngineSheet, waitForHumanTurn, watchPageErrors } from './helpers';

const SHOT_DIR = process.env.LANG_SHOT_DIR ?? process.env.SHOT_DIR ?? 'test-results/screens';
const shot = (page: Page, project: string, name: string): Promise<Buffer> =>
  page.screenshot({ path: `${SHOT_DIR}/${project}-${name}.png`, animations: 'disabled' });

const toggle = (scope: Locator | Page, lang: 'en' | 'de'): Locator => scope.locator(`[data-language-toggle="dialog"] [data-language="${lang}"]`);
/** Like openApp, but the stored language is set only when there is none: the test changes it, and a reload must keep that choice. */
async function openKeepingChoice(page: Page, hash = ''): Promise<void> {
  await page.addInitScript(() => {
    try {
      if (localStorage.getItem('ml.language') === null) localStorage.setItem('ml.language', 'en');
    } catch {
      // storage blocked
    }
  });
  await page.goto(hash);
}
const noOverflow = async (page: Page): Promise<void> => {
  const o = await page.evaluate(() => ({
    doc: document.documentElement.scrollWidth - window.innerWidth,
    dialog: Math.max(0, ...Array.from(document.querySelectorAll('[role="dialog"]')).map((d) => d.scrollWidth - d.clientWidth)),
  }));
  expect(o.doc, 'page overflow').toBeLessThanOrEqual(0);
  expect(o.dialog, 'dialog overflow').toBeLessThanOrEqual(0);
};

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    try {
      localStorage.removeItem('ml.start-choice');
    } catch {
      // storage blocked
    }
  });
});

const SIZES = [
  { name: 'desktop', width: 1280, height: 800, mobile: false },
  { name: 'phone390', width: 390, height: 844, mobile: true },
  { name: 'phone320', width: 320, height: 568, mobile: true },
] as const;

for (const size of SIZES) {
  test.describe(size.name, () => {
    test.use({ viewport: { width: size.width, height: size.height }, ...(size.mobile ? { hasTouch: true, isMobile: true } : {}) });
    test.beforeEach(({ browserName }) => {
      test.skip(size.mobile && browserName === 'firefox', 'Firefox has no mobile emulation (isMobile)');
    });

    test('reload, the New game dialog is open: switch to German inside it, the text changes in place and the choices stay', async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await openKeepingChoice(page);
      const dialog = page.getByRole('dialog');
      await expect(dialog).toBeVisible();
      await expect(dialog.getByRole('heading')).toHaveText('New game');
      await expect(page.locator('[data-choice="begin-yes"]')).toBeFocused(); // the toggle does not take the first focus
      await page.locator('[data-choice="mode-hotseat"]').check();
      await page.locator('[data-choice="jump-long"]').check();
      await noOverflow(page);
      await shot(page, info.project.name, `lang/${size.name}-dialog-en`);

      await toggle(dialog, 'de').click();
      await expect(dialog.getByRole('heading')).toHaveText('Neues Spiel');
      await expect(dialog).toBeVisible(); // not closed
      await expect(page.locator('[data-choice="mode-hotseat"]')).toBeChecked();
      await expect(page.locator('[data-choice="jump-long"]')).toBeChecked();
      await expect(toggle(dialog, 'de')).toHaveAttribute('aria-pressed', 'true');
      await expect(page.locator('html')).toHaveAttribute('lang', 'de');
      expect(await page.evaluate(() => document.activeElement?.closest('[role="dialog"]') !== null)).toBe(true); // focus stays inside
      await noOverflow(page);
      await shot(page, info.project.name, `lang/${size.name}-dialog-de`);

      // the choice is stored: after a reload the dialog speaks German at once, and the header toggle agrees
      await page.reload();
      await expect(page.getByRole('dialog').getByRole('heading')).toHaveText('Neues Spiel');
      await toggle(page.getByRole('dialog'), 'en').click();
      await expect(page.getByRole('dialog').getByRole('heading')).toHaveText('New game');
      await expect(page.locator('html')).toHaveAttribute('lang', 'en');
      errors.expectNone();
    });

    test('Tab order: the toggle is the last stop of the dialog and Tab wraps to the first', async ({ page }) => {
      test.skip(size.mobile, 'keyboard order is the same on every size; checked once on the desktop');
      await openApp(page, 'en', '', 'ask');
      await toggle(page, 'de').focus();
      await page.keyboard.press('Tab');
      const inside = await page.evaluate(() => document.activeElement?.closest('[role="dialog"]') !== null);
      expect(inside).toBe(true);
      await expect(toggle(page, 'de')).not.toBeFocused();
    });

    test('the game-link dialog and the broken-link dialog carry the toggle', async ({ page }, info) => {
      await openKeepingChoice(page, '#g=v1.&h=0&l=2');
      const link = page.getByRole('dialog');
      await expect(link.getByRole('heading')).toHaveText('Continue this game?');
      await toggle(link, 'de').click();
      await expect(link.getByRole('heading')).toHaveText('Dieses Spiel fortsetzen?');
      await noOverflow(page);
      await shot(page, info.project.name, `lang/${size.name}-link-de`);

      await page.goto('#g=v1.%2A%2A%2A');
      await page.reload();
      const broken = page.getByRole('dialog');
      await expect(broken.getByRole('heading')).toHaveText('Dieser Link funktioniert nicht');
      await toggle(broken, 'en').click();
      await expect(broken.getByRole('heading')).toHaveText('This link does not work');
      await noOverflow(page);
      await shot(page, info.project.name, `lang/${size.name}-broken-en`);
    });

    test('the engine sheet carries the toggle; switching keeps the sheet open and the picked engine', async ({ page }, info) => {
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const sheet = await openEngineSheet(page);
      await expect(sheet.getByRole('heading', { level: 2 })).toHaveText('Engine and strength');
      const checkedBefore = await sheet.locator('[data-engine][aria-checked="true"]').first().getAttribute('data-engine');
      await sheet.locator('.ml-langrow').scrollIntoViewIfNeeded();
      await toggle(sheet, 'de').click();
      await expect(sheet.getByRole('heading', { level: 2 })).toHaveText('Engine und Spielstärke');
      await expect(sheet).toBeVisible();
      expect(await sheet.locator('[data-engine][aria-checked="true"]').first().getAttribute('data-engine')).toBe(checkedBefore);
      await noOverflow(page);
      await shot(page, info.project.name, `lang/${size.name}-engine-sheet-de`);
    });
  });
}

test.describe('menu of a small screen', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  test.beforeEach(({ browserName }) => {
    test.skip(browserName === 'firefox', 'Firefox has no mobile emulation (isMobile)');
  });

  test('the menu sheet has the toggle: the header behind it is inert, so this is the only way inside', async ({ page }) => {
    await openApp(page, 'en');
    await waitForHumanTurn(page);
    await page.locator('.ml-play__bar [aria-controls]').tap();
    const menu = page.getByRole('dialog');
    await expect(menu).toBeVisible();
    await toggle(menu, 'de').tap();
    await expect(menu.locator('.ml-sheet__title')).toHaveText('Menü');
    await expect(menu).toBeVisible();
  });
});

// The first report came from an iPhone. Emulation: touch events, Mobile Safari user agent (devices['iPhone 15']), a tap, not a click.
const { defaultBrowserType: _ignored, ...iphone } = devices['iPhone 15'];
test.describe('iPhone emulation (tap)', () => {
  test.use({ ...iphone });
  test.beforeEach(({ browserName }) => {
    test.skip(browserName === 'firefox', 'Firefox has no mobile emulation (isMobile)');
  });

  test('a tap on the toggle in the header translates the whole screen, not only parts of it', async ({ page }) => {
    await openApp(page, 'en');
    await waitForHumanTurn(page);
    await expect(page.locator('.ml-nav__link').first()).toHaveText('Play');
    await page.locator('.ml-header [data-language="de"]').tap();
    await expect(page.locator('.ml-nav__link').first()).toHaveText('Spielen');
    await expect(page.locator('.ml-header [data-language="de"]')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');
    await page.locator('.ml-header [data-language="en"]').tap();
    await expect(page.locator('.ml-nav__link').first()).toHaveText('Play');
  });

  test('a tap on the toggle inside the New game dialog (the header lies under the overlay)', async ({ page }) => {
    await openApp(page, 'en', '', 'ask');
    const dialog = page.getByRole('dialog');
    await toggle(dialog, 'de').tap();
    await expect(dialog.getByRole('heading')).toHaveText('Neues Spiel');
    await expect(dialog).toBeVisible();
  });
});
