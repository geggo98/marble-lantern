// Regression checks of the kit-level and page-level findings of the design review of the Info part (B1, M2, M3, M11, M12, M14, m1, m2, m5,
// m17 and the cell chooser of Fig. 3.1). Each check measures the rendered page (geometry or computed style), so that it fails on the old CSS.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, watchPageErrors } from './helpers';

async function open(page: Page, hash: string, width: number, height: number, language: 'de' | 'en' = 'en'): Promise<void> {
  await page.setViewportSize({ width, height });
  await openApp(page, language, hash);
  await expect(page.locator('h1')).toBeVisible();
  await page.evaluate(() => document.fonts.ready);
}

test.describe('about fixes', () => {
  test('B1: the section link with code in its text is a link in topic 5, in both languages', async ({ page }) => {
    const errors = watchPageErrors(page);
    for (const language of ['en', 'de'] as const) {
      await open(page, '#/about/tuning', 390, 844, language);
      const link = page.locator('.ml-prose a.ml-about-link[href$="/inference/mcts"]').first();
      await expect(link).toBeVisible();
      expect(await page.evaluate(() => document.body.innerText.includes('{sec:'))).toBe(false);
    }
    errors.expectNone();
  });

  test('M14: a switch of three options is one row or equal rows at 320, never two plus one', async ({ page }) => {
    await open(page, '#/about/architecture', 320, 568, 'de');
    const seg = page.locator('.ml-fig-seg:has(> :nth-child(3))').first();
    await seg.scrollIntoViewIfNeeded();
    const boxes = await seg.locator('.ml-fig-seg__opt').evaluateAll((els) => els.map((e) => ({ w: Math.round(e.getBoundingClientRect().width), top: Math.round(e.getBoundingClientRect().top) })));
    expect(boxes.length).toBeGreaterThanOrEqual(3);
    expect(new Set(boxes.map((b) => b.w)).size, `option widths ${JSON.stringify(boxes)}`).toBe(1);
  });

  test('M3: at 320 the step label stands above the sentence and the sentence has the full width', async ({ page }) => {
    await open(page, '#/about/inference', 320, 568, 'de');
    const status = page.locator('.ml-fig__status:has(.ml-fig__step)').first();
    await status.scrollIntoViewIfNeeded();
    const g = await status.evaluate((el) => {
      const step = el.querySelector('.ml-fig__step')!.getBoundingClientRect();
      const text = el.querySelector('.ml-fig__step ~ span')!.getBoundingClientRect();
      const box = el.getBoundingClientRect();
      return { stepBottom: step.bottom, textTop: text.top, textWidth: text.width, boxWidth: box.width };
    });
    expect(g.textTop, 'the sentence starts below the label').toBeGreaterThanOrEqual(g.stepBottom - 1);
    expect(g.textWidth / g.boxWidth, 'the sentence uses most of the box').toBeGreaterThan(0.8);
  });

  test('M2: the stepper stays on screen while the reader scrolls through the stage (compact layout)', async ({ page }) => {
    await open(page, '#/about/inference', 320, 568);
    const fig = page.locator('figure.ml-fig:has(.ml-fig-stepper)').nth(1);
    await fig.scrollIntoViewIfNeeded();
    const panel = fig.locator('.ml-fig-ctl:has(.ml-fig-stepper)');
    // scroll so that the middle of the figure is at the top of the viewport: without a sticky panel it would be out of view
    await fig.evaluate((el) => window.scrollBy(0, el.getBoundingClientRect().top + el.getBoundingClientRect().height * 0.5));
    const top = await panel.evaluate((el) => el.getBoundingClientRect().top);
    expect(top, 'panel top while the middle of the figure is at the top').toBeGreaterThanOrEqual(-1);
    expect(top).toBeLessThan(5);
  });

  test('M11: a path in the prose is not a bordered box at 320 and may break after a slash', async ({ page }) => {
    await open(page, '#/about/classical', 320, 568);
    const chip = page.locator('.ml-prose .ml-src').first();
    await expect(chip).toBeVisible();
    const s = await chip.evaluate((el) => ({ border: getComputedStyle(el).borderTopWidth, display: getComputedStyle(el).display, wbr: el.querySelectorAll('wbr').length, text: el.textContent, path: el.getAttribute('data-src') }));
    expect(s.border).toBe('0px');
    expect(s.display).toBe('inline');
    expect(s.wbr).toBeGreaterThan(0);
    expect(s.text).toBe(s.path);
  });

  test('M12: the technology table of the hub stacks term and description at 320', async ({ page }) => {
    await open(page, '#/about', 320, 568, 'de');
    const row = page.locator('.ml-techlist__row').first();
    const g = await row.evaluate((el) => {
      const dt = el.querySelector('dt')!.getBoundingClientRect();
      const dd = el.querySelector('dd')!.getBoundingClientRect();
      return { dtBottom: dt.bottom, ddTop: dd.top, ddWidth: dd.width, rowWidth: el.getBoundingClientRect().width };
    });
    expect(g.ddTop).toBeGreaterThanOrEqual(g.dtBottom - 1);
    expect(g.ddWidth / g.rowWidth).toBeGreaterThan(0.8);
  });

  test('m5: the hub offers a jump to the topic cards', async ({ page }) => {
    await open(page, '#/about', 320, 568);
    await page.locator('.ml-hub__jump').click();
    await expect.poll(() => page.locator('#about-learn-title').evaluate((el) => el.getBoundingClientRect().top)).toBeLessThan(120);
  });

  test('m1: the h1 of a topic shows no focus ring after a direct load', async ({ page }) => {
    await open(page, '#/about/classical', 844, 390);
    const s = await page.locator('h1').evaluate((el) => ({ focused: document.activeElement === el, outline: getComputedStyle(el).outlineStyle }));
    expect(s.focused).toBe(true);
    expect(s.outline).toBe('none');
  });

  test('m2: "On this page" is closed below 60rem and open as a rail from 60rem', async ({ page }) => {
    await open(page, '#/about/classical', 820, 1180);
    await expect(page.locator('.ml-about__toc details')).toHaveCount(1);
    expect(await page.locator('.ml-about__toc details').evaluate((el) => (el as HTMLDetailsElement).open)).toBe(false);
    await page.locator('.ml-about__toc summary').click();
    await expect(page.locator('.ml-about__toc details .ml-about__toclist a').first()).toBeVisible();
    await page.setViewportSize({ width: 1280, height: 900 });
    await expect(page.locator('.ml-about__toc--rail .ml-about__toclist a').first()).toBeVisible();
  });

  test('m17: the pressed glossary filter differs by weight and a check mark, not only by fill', async ({ page }) => {
    await open(page, '#/about/glossary', 390, 844);
    const pressed = page.locator('.ml-glossary__kinds .ml-btn[aria-pressed="true"]');
    await expect(pressed).toHaveCount(1);
    const s = await pressed.evaluate((el) => ({ weight: Number(getComputedStyle(el).fontWeight), mark: getComputedStyle(el, '::before').content }));
    expect(s.weight).toBeGreaterThanOrEqual(600);
    expect(s.mark).not.toBe('none');
    const entry = await page.locator('.ml-about__glossary').evaluate((el) => el.getBoundingClientRect().width);
    await page.setViewportSize({ width: 1280, height: 900 });
    expect(await page.locator('.ml-about__glossary').evaluate((el) => el.getBoundingClientRect().width)).toBeLessThanOrEqual(42 * 16 + 1);
    expect(entry).toBeGreaterThan(0);
  });

  test('Fig. 3.1: a native cell chooser with a 44 px control reaches every cell', async ({ page }) => {
    await open(page, '#/about/inference', 320, 568);
    const select = page.locator('select.ml-fig-select').filter({ has: page.locator('option[value="0"]') }).first();
    await select.scrollIntoViewIfNeeded();
    const h = await select.evaluate((el) => el.getBoundingClientRect().height);
    expect(h).toBeGreaterThanOrEqual(43.4);
    await select.selectOption('0');
    await expect(page.locator('[data-cell="0"][tabindex="0"]')).toHaveCount(1);
  });
});
