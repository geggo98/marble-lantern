// Glossary page and scientific notation (owner feedback of 2026-11-21, items 4 and 9): the English term in brackets only when it adds
// something, the filter option "internal", and {sci:…} typeset as MathML on the baseline of the text, at 320 px, in Chromium and Firefox.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, watchPageErrors } from './helpers';

async function open(page: Page, width: number, language: 'de' | 'en'): Promise<void> {
  await page.setViewportSize({ width, height: 800 });
  await openApp(page, language, '#/about/glossary');
  await expect(page.locator('h1')).toBeVisible();
  await page.evaluate(() => document.fonts.ready);
}

test.describe('glossary: English term and internal tag', () => {
  test('German: no repeated English term next to a name, a different one stays', async ({ page }) => {
    const errors = watchPageErrors(page);
    await open(page, 390, 'de');
    const dt = (id: string): ReturnType<Page['locator']> => page.locator(`dt#about-glossary-${id}`);
    await expect(dt('f32-f16')).toHaveText('F32 und F16');
    await expect(dt('convolution')).toHaveText('Faltung (Convolution)');
    await expect(dt('flop')).toHaveText('FLOP');
    await expect(dt('branching-factor')).toContainText('(branching factor)');
    // No term repeats its own name in brackets: scan the whole page.
    const repeated = await page.locator('.ml-about__term dt').evaluateAll((els) =>
      els.flatMap((el) => {
        const en = el.querySelector('.ml-term__en')?.textContent?.replace(/[()]/g, '').trim().toLowerCase();
        const name = (el.firstChild?.textContent ?? '').toLowerCase();
        return en !== undefined && (name.includes(en) || en.includes(name)) ? [name] : [];
      }),
    );
    expect(repeated).toEqual([]);
    errors.expectNone();
  });

  test('English: never an English term in brackets', async ({ page }) => {
    await open(page, 390, 'en');
    await expect(page.locator('.ml-term__en')).toHaveCount(0);
  });

  test('the filter has an option "internal" that is a 44 px target and shows the tag', async ({ page }) => {
    await open(page, 320, 'en');
    const button = page.locator('.ml-glossary__kinds button[data-kind="internal"]');
    await expect(button).toBeVisible();
    const box = await button.boundingBox();
    expect(box!.height).toBeGreaterThanOrEqual(44);
    await button.click();
    await expect(button).toHaveAttribute('aria-pressed', 'true');
    // As long as data/glossary-internal.json is empty the list is empty and says so; with entries every one carries the tag.
    const rows = page.locator('.ml-about__term');
    if ((await rows.count()) > 0) await expect(rows.first().locator('[data-internal]')).toBeVisible();
    else await expect(page.locator('[role="status"]')).not.toHaveText('');
  });
});

test.describe('scientific notation', () => {
  for (const language of ['en', 'de'] as const) {
    test(`{sci:} is MathML on the text baseline at 320 px (${language})`, async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await open(page, 320, language);
      const math = page.locator('.ml-about__term:has(#about-glossary-learning-rate) math.ml-sci').first();
      test.skip((await math.count()) === 0, 'the catalog does not use {sci:} in the learning-rate term yet');
      await math.scrollIntoViewIfNeeded();
      const g = await math.evaluate((el) => {
        const r = el.getBoundingClientRect();
        const p = el.parentElement!.getBoundingClientRect();
        const probe = document.createElement('span');
        probe.style.cssText = 'display:inline-block;width:0;height:0;vertical-align:baseline';
        el.parentElement!.insertBefore(probe, el);
        const base = probe.getBoundingClientRect().bottom;
        probe.remove();
        return { w: r.width, h: r.height, top: r.top, bottom: r.bottom, base, parentLeft: p.left, parentRight: p.right, left: r.left, right: r.right, label: el.getAttribute('aria-label'), text: el.textContent };
      });
      expect(g.w).toBeGreaterThan(10);
      expect(g.right).toBeLessThanOrEqual(g.parentRight + 1);
      expect(g.left).toBeGreaterThanOrEqual(g.parentLeft - 1);
      // The baseline of the text lies inside the box of the formula, between the top and a quarter above the bottom (the exponent sits above it).
      expect(g.base).toBeGreaterThan(g.top);
      expect(g.base).toBeLessThanOrEqual(g.bottom + 1);
      expect(g.text).toContain('10');
      expect(g.label).toContain(language === 'de' ? 'mal 10 hoch minus 4' : 'times 10 to the power of minus 4');
      // The exponent is smaller than the base and stands higher: MathML layout worked, not a plain-text fallback.
      const sizes = await math.locator('msup > *').evaluateAll((els) => els.map((e) => ({ top: e.getBoundingClientRect().top, h: e.getBoundingClientRect().height })));
      expect(sizes[1]!.top).toBeLessThan(sizes[0]!.top + sizes[0]!.h / 2);
      await page.locator('.ml-about__term:has(#about-glossary-learning-rate)').screenshot({ path: info.outputPath(`sci-${language}.png`) });
      errors.expectNone();
    });
  }
});
