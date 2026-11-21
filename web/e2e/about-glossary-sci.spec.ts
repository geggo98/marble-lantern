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
    // The cleaned German catalog (merge 080d8e2) names 'Faltung' with the English term 'convolution' and spells out FLOP once.
    await expect(dt('convolution')).toHaveText('Faltung (convolution)');
    await expect(dt('flop')).toHaveText('FLOP (floating-point operation)');
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
      // The learning-rate entry uses {sci:3e-4} in its short and its long definition (glossary.json); a missing formula is a failure.
      expect(await math.count()).toBeGreaterThan(0);
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

// Every page that uses {sci:} at 320 px: no raw token, no plain "3e-4" left in the prose, every formula inside its paragraph and on the
// baseline, no horizontal page scroll. The topics come from the catalogs (training, tuning, inference, glossary).
test.describe('scientific notation on the topic pages', () => {
  const pages = ['#/about/training', '#/about/tuning', '#/about/inference', '#/about/glossary'];
  for (const language of ['en', 'de'] as const) {
    for (const hash of pages) {
      test(`${hash} at 320 px (${language}): formulas fit and sit on the baseline`, async ({ page }, info) => {
        const errors = watchPageErrors(page);
        await page.setViewportSize({ width: 320, height: 800 });
        await openApp(page, language, hash);
        await expect(page.locator('h1')).toBeVisible();
        await page.evaluate(() => document.fonts.ready);
        const r = await page.evaluate(() => {
          const maths = [...document.querySelectorAll<HTMLElement>('main math.ml-sci')];
          const bad = maths.flatMap((el) => {
            const box = el.getBoundingClientRect();
            // the nearest block ancestor: a paragraph, a list item, a dd or a figure caption
            let block = el.parentElement!;
            while (getComputedStyle(block).display.startsWith('inline')) block = block.parentElement!;
            const p = block.getBoundingClientRect();
            // A zero-size probe on the baseline, once before and once after the formula: when the formula starts a new line, the probe before
            // it stays at the end of the previous line, so the probe on the same line as the formula (nearest to its bottom) counts.
            const probeAt = (before: Node | null): number => {
              const probe = document.createElement('span');
              probe.style.cssText = 'display:inline-block;width:0;height:0;vertical-align:baseline';
              el.parentElement!.insertBefore(probe, before);
              const y = probe.getBoundingClientRect().bottom;
              probe.remove();
              return y;
            };
            const base = [probeAt(el), probeAt(el.nextSibling)].sort((a, b) => Math.abs(a - box.bottom) - Math.abs(b - box.bottom))[0]!;
            const problems: string[] = [];
            if (box.left < p.left - 1 || box.right > p.right + 1) problems.push(`outside its block (${Math.round(box.left)}..${Math.round(box.right)} vs ${Math.round(p.left)}..${Math.round(p.right)})`);
            if (!(base > box.top && base <= box.bottom + 1)) problems.push(`baseline ${base} not in ${box.top}..${box.bottom}`);
            if (box.height > 40) problems.push(`taller than a line: ${box.height}`);
            return problems.length ? [`${el.textContent}: ${problems.join(', ')}`] : [];
          });
          const text = document.querySelector('main')?.textContent ?? '';
          return {
            count: maths.length,
            bad,
            raw: /\{sci:/.test(text),
            plain: text.match(/\b\d(?:\.\d+)?e-\d+\b/g) ?? [],
            overflow: document.documentElement.scrollWidth - window.innerWidth,
          };
        });
        expect(r.count).toBeGreaterThan(0);
        expect(r.bad).toEqual([]);
        expect(r.raw).toBe(false);
        expect(r.plain).toEqual([]);
        expect(r.overflow).toBeLessThanOrEqual(0);
        await page.locator('main math.ml-sci').first().scrollIntoViewIfNeeded();
        await page.screenshot({ path: info.outputPath(`sci-page-${hash.split('/').pop()}-${language}.png`) });
        errors.expectNone();
      });
    }
  }
});

// Fig. 4.1 writes its rates as 3 · 10⁻⁴ (plain text with superscripts). At 320 px Firefox broke the readout after the superscript minus
// ("1,6 · 10⁻" / "⁴"); a rate stays on one line and inside its cell, and the linear y axis names the shared power of ten.
test.describe('learning-rate figure at 320 px', () => {
  for (const language of ['en', 'de'] as const) {
    test(`rates stay on one line (${language})`, async ({ page }, info) => {
      const errors = watchPageErrors(page);
      await page.setViewportSize({ width: 320, height: 800 });
      await openApp(page, language, '#/about/training');
      const fig = page.locator('#fig-lr');
      await fig.scrollIntoViewIfNeeded();
      await page.evaluate(() => document.fonts.ready);
      const values = fig.locator('.ml-fig-readout__value, .ml-fig-ctl__out');
      expect(await values.count()).toBeGreaterThan(2);
      const lines = await values.evaluateAll((els) =>
        els.map((el) => {
          const style = getComputedStyle(el);
          const lh = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.2;
          const r = el.getBoundingClientRect();
          const cell = el.parentElement!.getBoundingClientRect();
          return { text: el.textContent, oneLine: r.height < lh * 1.6, inside: r.right <= cell.right + 1 };
        }),
      );
      expect(lines.filter((l) => /10/.test(l.text ?? '')).length).toBeGreaterThan(1);
      expect(lines.filter((l) => /10/.test(l.text ?? '') && !(l.oneLine && l.inside))).toEqual([]);
      await expect(fig.locator('text.ml-fig-plot__axislabel').last()).toContainText('10⁻⁴');
      await fig.screenshot({ path: info.outputPath(`lr-${language}.png`) });
      errors.expectNone();
    });
  }
});
