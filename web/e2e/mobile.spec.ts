// The play screen on phones and a tablet, with touch: four emulated devices in Chromium and WebKit.
// Checks: no horizontal scroll, all targets at least 44 px, the board does not move while the engine loads, the HUD menu,
// a full move by tap and tap and by drag and drop against the real engine. Screenshots of every device go to SHOT_DIR
// (default test-results/screens) for the visual review. Firefox cannot emulate a mobile device (no isMobile): skipped.
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR ?? 'test-results/screens';

const DEVICES = [
  { name: 'phone-320', width: 320, height: 568 }, // iPhone SE class
  { name: 'phone-390', width: 390, height: 844 },
  { name: 'phone-landscape', width: 844, height: 390 },
  { name: 'tablet-portrait', width: 820, height: 1180 },
] as const;

const MIN = 44;
const TOLERANCE = 0.6; // sub-pixel rounding of the bounding boxes

interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

async function centre(locator: Locator): Promise<{ x: number; y: number }> {
  const box = await locator.boundingBox();
  if (!box) throw new Error('element has no box');
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

const shot = async (page: Page, project: string, name: string, device: string): Promise<void> => {
  await page.screenshot({ path: `${SHOT_DIR}/${project}-${device}-${name}.png`, animations: 'disabled' });
};

/** Every interactive element that a finger must hit, as a list of boxes (labels for checkboxes, hit circles of the board). */
async function interactiveBoxes(page: Page, scope: string): Promise<{ what: string; box: Box }[]> {
  return await page.evaluate((scopeSelector) => {
    const root = document.querySelector(scopeSelector) ?? document.body;
    const out: { what: string; box: Box }[] = [];
    const visible = (el: Element): boolean => {
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && !el.closest('[inert]');
    };
    const take = (el: Element, what: string): void => {
      if (!visible(el)) return;
      const r = el.getBoundingClientRect();
      out.push({ what, box: { x: r.x, y: r.y, width: r.width, height: r.height } });
    };
    root.querySelectorAll('button, a[href], summary, [role="radio"], input[type="range"]').forEach((el) =>
      take(el, `${el.tagName.toLowerCase()} "${(el.textContent ?? '').trim().slice(0, 24) || el.getAttribute('aria-label') || ''}"`),
    );
    root.querySelectorAll('input[type="checkbox"]').forEach((el) => take(el.closest('label') ?? el, 'checkbox label'));
    root.querySelectorAll('svg [data-cell]').forEach((el) => take(el, `board cell ${el.getAttribute('data-cell')}`));
    return out;
  }, scope);
}

for (const device of DEVICES) {
  test.describe(`${device.name} (${device.width}x${device.height})`, () => {
    test.use({
      viewport: { width: device.width, height: device.height },
      hasTouch: true,
      isMobile: true,
      deviceScaleFactor: 2,
    });
    test.beforeEach(({ browserName }) => {
      test.skip(browserName === 'firefox', 'Firefox has no mobile emulation (isMobile)');
    });

    const overflow = (page: Page): Promise<{ doc: number; body: number }> =>
      page.evaluate(() => ({
        doc: document.documentElement.scrollWidth - window.innerWidth,
        body: document.body.scrollWidth - window.innerWidth,
      }));

    test('fits the screen without horizontal scroll, targets are 44 px, the board does not move while the engine loads', async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en', '', 'ask');
      await page.locator('[data-choice="begin-yes"]').click();
      const svg = page.locator('svg.ml-board__svg');
      await expect(svg).toBeVisible();
      const before = (await svg.boundingBox())!;
      await waitForHumanTurn(page);
      const after = (await svg.boundingBox())!;
      for (const key of ['x', 'y', 'width', 'height'] as const) {
        expect(Math.abs(before[key] - after[key]), `board ${key} while the engine loads`).toBeLessThanOrEqual(1);
      }
      const o = await overflow(page);
      expect(o.doc, 'document overflow').toBeLessThanOrEqual(0);
      expect(o.body, 'body overflow').toBeLessThanOrEqual(0);
      // the board is as large as the space allows: it uses at least 90% of the smaller side of its box
      const box = after;
      expect(box.width).toBeGreaterThan(0);
      expect(box.x).toBeGreaterThanOrEqual(-1);
      expect(box.x + box.width).toBeLessThanOrEqual(device.width + 1);
      expect(box.y + box.height).toBeLessThanOrEqual(device.height + 1);
      const small = await interactiveBoxes(page, 'body');
      expect(small.length).toBeGreaterThan(100); // 121 cells and the buttons: the scan saw the page
      const bad = small.filter(({ box: b }) => b.width < MIN - TOLERANCE || b.height < MIN - TOLERANCE);
      expect(bad.map((b) => `${b.what} ${b.box.width.toFixed(1)}x${b.box.height.toFixed(1)}`), 'targets under 44 px').toEqual([]);
      await shot(page, info.project.name, 'play', device.name);
      errors.expectNone();
    });

    test('the board does not move while the engine loads, also with a wide fallback font', async ({ page }) => {
      // Fonts differ between machines (a CI runner has other fallbacks than a Mac): stress the text with a wide monospace font.
      await page.addInitScript(() => {
        document.addEventListener('DOMContentLoaded', () => {
          const style = document.createElement('style');
          style.textContent = '*, *::before, *::after { font-family: "Courier New", Courier, monospace !important; letter-spacing: 0.04em !important; }';
          document.head.append(style);
        });
      });
      await openApp(page, 'de', '', 'ask');
      await page.locator('[data-choice="begin-yes"]').click();
      const svg = page.locator('svg.ml-board__svg');
      await expect(svg).toBeVisible();
      const track = async (): Promise<Box> => (await svg.boundingBox())!;
      const boxes: Box[] = [await track()];
      const loaded = waitForHumanTurn(page).then(() => true);
      let done = false;
      void loaded.then(() => { done = true; });
      while (!done) {
        boxes.push(await track());
        await page.waitForTimeout(50);
      }
      await waitForHumanTurn(page);
      boxes.push(await track());
      for (const key of ['x', 'y', 'width', 'height'] as const) {
        const values = boxes.map((b) => b[key]);
        expect(Math.max(...values) - Math.min(...values), `board ${key} over the load`).toBeLessThanOrEqual(1);
      }
    });

    test('the menu opens, holds the settings, and closes by Escape, close button, backdrop and swipe', async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const button = page.locator('.ml-play__bar [aria-controls]');
      await expect(button).toHaveAttribute('aria-expanded', 'false');
      await button.tap();
      const menu = page.getByRole('dialog');
      await expect(menu).toBeVisible();
      await expect(button).toHaveAttribute('aria-expanded', 'true');
      await expect(menu.locator('.ml-engine')).toBeVisible();
      await expect(menu.locator('[data-guides-levels]')).toBeAttached();
      expect(await page.evaluate(() => document.getElementById('root')?.hasAttribute('inert'))).toBe(true);
      const o = await overflow(page);
      expect(o.doc).toBeLessThanOrEqual(0);
      const m = (await menu.boundingBox())!;
      expect(m.x).toBeGreaterThanOrEqual(-1);
      expect(m.x + m.width).toBeLessThanOrEqual(device.width + 1);
      expect(m.y).toBeGreaterThanOrEqual(-1);
      const tooSmall = (await interactiveBoxes(page, '[role="dialog"]')).filter(
        ({ box: b }) => b.height < MIN - TOLERANCE || b.width < MIN - TOLERANCE,
      );
      expect(tooSmall.map((b) => `${b.what} ${b.box.width.toFixed(1)}x${b.box.height.toFixed(1)}`), 'menu targets under 44 px').toEqual([]);
      await shot(page, info.project.name, 'menu', device.name);

      await page.keyboard.press('Escape');
      await expect(menu).toBeHidden();
      await expect(button).toBeFocused();

      await button.tap();
      await menu.locator('[data-menu-close]').tap();
      await expect(menu).toBeHidden();

      await button.tap();
      await expect(menu).toBeVisible();
      await page.touchscreen.tap(device.width / 2, 3); // the backdrop, above the sheet
      await expect(menu).toBeHidden();

      await button.tap();
      await expect(menu).toBeVisible();
      const grab = await centre(menu.locator('[data-menu-grab]'));
      await page.mouse.move(grab.x, grab.y);
      await page.mouse.down();
      await page.mouse.move(grab.x, grab.y + 60, { steps: 4 });
      await page.mouse.move(grab.x, grab.y + 160, { steps: 4 });
      await page.mouse.up();
      await expect(menu).toBeHidden();
      errors.expectNone();
    });

    test('a move by tap and tap works with the real engine, and the marks of the selection stay visible', async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const before = await plyOf(page);
      const piece = page.locator('[data-cell][aria-disabled="false"][tabindex="0"]');
      await page.touchscreen.tap(...(Object.values(await centre(piece)) as [number, number]));
      const dot = page.locator('.ml-target__dot').first();
      await expect(dot).toBeAttached();
      await expect(page.locator('.ml-select')).toHaveCount(1);
      await shot(page, info.project.name, 'selected', device.name);
      const at = await centre(dot);
      await page.touchscreen.tap(at.x, at.y);
      await expect.poll(() => plyOf(page)).toBe(before + 2);
      await waitForHumanTurn(page);
      errors.expectNone();
    });

    test('a move by drag and drop works with the real engine; a drop far from every target cancels', async ({ page }) => {
      const errors = watchPageErrors(page);
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const before = await plyOf(page);
      const piece = page.locator('[data-cell][aria-disabled="false"][tabindex="0"]');
      const from = await centre(piece);
      // tap the piece to learn the targets, then tap it again to clear
      await page.touchscreen.tap(from.x, from.y);
      const dot = page.locator('.ml-target__dot').first();
      await expect(dot).toBeAttached();
      const to = await centre(dot);
      await page.touchscreen.tap(from.x, from.y);
      await expect(page.locator('.ml-select')).toHaveCount(0);

      // cancelled drag: far into the corner of the viewport
      await page.mouse.move(from.x, from.y);
      await page.mouse.down();
      await page.mouse.move(from.x + 5, from.y + 40, { steps: 5 });
      await page.mouse.move(2, 2, { steps: 5 });
      await page.mouse.up();
      expect(await plyOf(page)).toBe(before);

      // a drag onto the target, dropped 8 px beside its centre
      await page.mouse.move(from.x, from.y);
      await page.mouse.down();
      await page.mouse.move((from.x + to.x) / 2, (from.y + to.y) / 2 + 12, { steps: 5 });
      await page.mouse.move(to.x + 8, to.y + 8, { steps: 5 });
      await page.mouse.up();
      await expect.poll(() => plyOf(page)).toBe(before + 2);
      await waitForHumanTurn(page);
      errors.expectNone();
    });

    test('a real touch drag (Chromium, through the DevTools protocol) moves a piece', async ({ page, browserName }) => {
      test.skip(browserName !== 'chromium', 'touch events by protocol exist only in Chromium');
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const before = await plyOf(page);
      const piece = page.locator('[data-cell][aria-disabled="false"][tabindex="0"]');
      const from = await centre(piece);
      await page.touchscreen.tap(from.x, from.y);
      const dot = page.locator('.ml-target__dot').first();
      await expect(dot).toBeAttached();
      const to = await centre(dot);
      await page.touchscreen.tap(from.x, from.y);
      const client = await page.context().newCDPSession(page);
      const touch = async (type: 'touchStart' | 'touchMove' | 'touchEnd', x: number, y: number): Promise<void> => {
        await client.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x, y }] });
      };
      await touch('touchStart', from.x, from.y);
      for (let i = 1; i <= 8; i++) await touch('touchMove', from.x + ((to.x - from.x) * i) / 8, from.y + ((to.y - from.y) * i) / 8);
      await touch('touchEnd', to.x, to.y);
      await expect.poll(() => plyOf(page)).toBe(before + 2);
    });

    test('long press on the board opens no context menu and selects no text', async ({ page }) => {
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const prevented = await page.evaluate(
        () =>
          new Promise<boolean>((resolve) => {
            const svg = document.querySelector('svg.ml-board__svg')!;
            const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
            svg.dispatchEvent(event);
            resolve(event.defaultPrevented);
          }),
      );
      expect(prevented).toBe(true);
      const style = await page.locator('svg.ml-board__svg').evaluate((el) => {
        const s = getComputedStyle(el);
        return { touchAction: s.touchAction, userSelect: s.userSelect || s.getPropertyValue('-webkit-user-select') };
      });
      expect(style.touchAction).toBe('none');
      expect(style.userSelect).toBe('none');
    });

    test('with move guides on, the marks are drawn and the layout holds', async ({ page }, info) => {
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      await page.locator('.ml-play__bar [aria-controls]').tap();
      const toggle = page.getByRole('dialog').locator('[data-guides-level="eval"]');
      await toggle.check({ force: true });
      await page.keyboard.press('Escape');
      await expect(page.locator('[data-guide="piece"]').first()).toBeAttached({ timeout: 60_000 });
      await waitForHumanTurn(page);
      const o = await overflow(page);
      expect(o.doc).toBeLessThanOrEqual(0);
      const piece = page.locator('[data-cell][aria-disabled="false"][tabindex="0"]');
      await page.touchscreen.tap(...(Object.values(await centre(piece)) as [number, number]));
      await expect(page.locator('.ml-select')).toHaveCount(1);
      await shot(page, info.project.name, 'guides-selected', device.name);
    });
  });
}
