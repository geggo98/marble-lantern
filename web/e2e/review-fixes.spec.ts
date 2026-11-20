// Review fixes of 2026-11-20 on the start dialog, the advantage strip and the board dimming (docs/web-ui.md: "Start dialog", "Rules panel",
// "Advantage strip", "Dimming the board"). Measured in a real browser at 360x640, 390x844, 844x390 (a phone on its side) and 1280x800, in German
// and English, light and dark. Screenshots go to SHOT_DIR for the visual review.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { CHOICE_KEY, humanTurnCell, openApp, openRulesPanel, waitForHumanTurn, watchPageErrors } from './helpers';

const SHOT_DIR = process.env.SHOT_DIR ?? 'test-results/screens';
const SIZES = [
  { name: '360x640', width: 360, height: 640 },
  { name: '390x844', width: 390, height: 844 },
  { name: '844x390', width: 844, height: 390 },
  { name: '1280x800', width: 1280, height: 800 },
] as const;
const LANGS = ['de', 'en'] as const;
const MIN = 44;
const TOLERANCE = 0.5;

/** Every test starts without a stored start choice (a first visit), unless it stores one itself afterwards. */
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    try {
      if (!sessionStorage.getItem('ml-e2e-seen')) {
        sessionStorage.setItem('ml-e2e-seen', '1');
        localStorage.removeItem('ml.start-choice');
      }
    } catch {
      // storage blocked
    }
  });
});

const sheetState = (page: Page): Promise<{ scrollTop: number; titleTop: number; titleBottom: number; vh: number; active: string | null }> =>
  page.evaluate(() => {
    const sheet = document.querySelector<HTMLElement>('[role="dialog"]')!;
    const r = sheet.querySelector('.ml-sheet__title')!.getBoundingClientRect();
    return { scrollTop: sheet.scrollTop, titleTop: r.top, titleBottom: r.bottom, vh: window.innerHeight, active: document.activeElement?.getAttribute('data-choice') ?? null };
  });

for (const size of SIZES) {
  for (const lang of LANGS) {
    test.describe(`start dialog, ${size.name}, ${lang}`, () => {
      test.use({ viewport: { width: size.width, height: size.height } });

      test('opens at the top: the title and the board size are in view although the sticky "Yes" has the focus', async ({ page }, info) => {
        const errors = watchPageErrors(page);
        await openApp(page, lang, '', 'ask', 'fresh');
        await expect(page.getByRole('dialog')).toBeVisible();
        const s = await sheetState(page);
        expect(s.active, 'the first focus is the primary button').toBe('begin-yes');
        expect(s.scrollTop, 'the sheet is scrolled to the top').toBe(0);
        expect(s.titleTop, 'title top in the window').toBeGreaterThanOrEqual(0);
        expect(s.titleBottom, 'title inside the window').toBeLessThanOrEqual(s.vh);
        const rows = await page.locator('[data-choice="rows-5"]').locator('xpath=ancestor::label').boundingBox();
        expect(rows!.y + rows!.height, 'the board size choice is in the window without scrolling').toBeLessThanOrEqual(s.vh);
        await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-dialog-collapsed-${size.name}-${lang}.png`, animations: 'disabled' });
        await page.emulateMedia({ colorScheme: 'dark' });
        await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-dialog-collapsed-${size.name}-${lang}-dark.png`, animations: 'disabled' });
        errors.expectNone();
      });

      test('no option label runs out of its button, also with the warning icon; every option stays 44 px high', async ({ page }, info) => {
        await openApp(page, lang, '', 'ask', 'fresh');
        await page.locator('[data-choice="mode-engines"]').check();
        await openRulesPanel(page);
        // A selected net engine marks the options its model cannot play with the warning icon, and the label is then wider. The page loads
        // the engine of the session, so the marked state is put on every option of the panel here, with the same markup the dialog draws.
        await page.evaluate(() => {
          document.querySelectorAll('[data-rules-panel] label.ml-start__opt').forEach((opt) => {
            opt.setAttribute('data-engine-warn', '');
            const label = opt.querySelector('.ml-start__label')!;
            label.insertAdjacentHTML('afterbegin', '<svg class="ml-icon" viewBox="0 0 24 24" aria-hidden="true"><use href="#i-alert"></use></svg>');
          });
        });
        const bad = await page.evaluate(() => {
          const out: string[] = [];
          document.querySelectorAll<HTMLElement>('label.ml-start__opt').forEach((opt) => {
            const o = opt.getBoundingClientRect();
            if (o.width === 0) return;
            const name = (opt.textContent ?? '').trim().slice(0, 24);
            if (o.height < 44 - 0.5) out.push(`${name}: ${o.height.toFixed(1)} px high`);
            const label = opt.querySelector<HTMLElement>('.ml-start__label')!;
            const text = opt.querySelector<HTMLElement>('.ml-start__text')!;
            for (const el of [label, text]) {
              const r = el.getBoundingClientRect();
              if (r.left < o.left - 0.5 || r.right > o.right + 0.5) out.push(`${name}: ${el.className} sticks out (${r.left.toFixed(1)}..${r.right.toFixed(1)} of ${o.left.toFixed(1)}..${o.right.toFixed(1)})`);
            }
            if (text.scrollWidth > text.clientWidth + 1) out.push(`${name}: the text is wider than its box (${text.scrollWidth} > ${text.clientWidth})`);
          });
          return out;
        });
        expect(bad).toEqual([]);
        const sideways = await page.evaluate(() => {
          const sheet = document.querySelector<HTMLElement>('[role="dialog"]')!;
          return { doc: document.documentElement.scrollWidth - window.innerWidth, sheet: sheet.scrollWidth - sheet.clientWidth };
        });
        expect(sideways.doc).toBeLessThanOrEqual(0);
        expect(sideways.sheet).toBeLessThanOrEqual(0);
        await page.locator('[data-rules-summary]').evaluate((el) => el.scrollIntoView({ block: 'start' }));
        await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-dialog-expanded-${size.name}-${lang}.png`, animations: 'disabled' });
        await page.emulateMedia({ colorScheme: 'dark' });
        await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-dialog-expanded-${size.name}-${lang}-dark.png`, animations: 'disabled' });
      });

      test('the fixed rules are compact rows; the explanation opens by keyboard and takes room only then', async ({ page }) => {
        await openApp(page, lang, '', 'ask', 'fresh');
        await openRulesPanel(page);
        const m = await page.evaluate(() => {
          const rows = [...document.querySelectorAll<HTMLElement>('[data-fixed-rule]')];
          const heights = rows.map((r) => r.getBoundingClientRect().height);
          const body = document.querySelector('.ml-rules__body')!.getBoundingClientRect().height;
          const fixed = [...document.querySelectorAll('.ml-rules__fixed')].reduce((sum, d) => sum + d.getBoundingClientRect().height, 0);
          return { heights, share: fixed / body };
        });
        const mean = m.heights.reduce((a, b) => a + b, 0) / m.heights.length;
        expect(mean, `mean height of a fixed row (${m.heights.map((h) => h.toFixed(0)).join(', ')})`).toBeLessThanOrEqual(42);
        expect(Math.max(...m.heights), 'the tallest fixed row').toBeLessThanOrEqual(60);
        expect(m.share, 'the fixed rules take less than 40 % of the panel body (measured before: 58 % at 360 px in German)').toBeLessThan(0.4);
        const row = page.locator('[data-fixed-rule="limit"]');
        const before = (await row.boundingBox())!.height;
        const toggle = row.locator('button');
        await toggle.focus();
        await expect(toggle).toHaveAttribute('aria-expanded', 'false');
        await page.keyboard.press('Enter');
        await expect(toggle).toHaveAttribute('aria-expanded', 'true');
        await expect(row.locator('small')).toBeVisible();
        expect((await row.boundingBox())!.height, 'the open explanation adds height').toBeGreaterThan(before + 8);
        await page.keyboard.press('Space');
        await expect(row.locator('small')).toBeHidden();
        expect((await row.boundingBox())!.height).toBeCloseTo(before, 0);
        const box = (await toggle.boundingBox())!;
        expect(box.width, 'the toggle is a 44 px target').toBeGreaterThanOrEqual(MIN - TOLERANCE);
        expect(box.height).toBeGreaterThanOrEqual(MIN - TOLERANCE);
      });

      test('the collapsed summary shows all three changed rules, not two lines of them', async ({ page }) => {
        await openApp(page, lang, '', 'ask', 'fresh');
        await page.locator('[data-choice="mode-hotseat"]').check();
        await openRulesPanel(page);
        await page.locator('[data-choice="jump-long"]').check();
        await page.locator('[data-choice="camps-open"]').check();
        await page.locator('[data-choice="ranking-full"]').check();
        await page.locator('[data-rules-summary]').click(); // close the panel: the summary alone must tell
        await expect(page.locator('[data-rules-panel]')).toHaveJSProperty('open', false);
        const now = await page.evaluate(() => {
          const el = document.querySelector<HTMLElement>('[data-rules-now]')!;
          const cs = getComputedStyle(el);
          const lineHeight = parseFloat(cs.lineHeight);
          return { text: el.textContent ?? '', clamp: cs.getPropertyValue('-webkit-line-clamp'), clipped: el.scrollHeight - el.clientHeight, lines: Math.round(el.getBoundingClientRect().height / lineHeight), count: document.querySelector('[data-rules-count]')?.textContent ?? '' };
        });
        const names =
          lang === 'de'
            ? ['Sprungregel', 'Fremde Dreiecke', 'Nach dem ersten Sieg']
            : ['Jump rule', 'Foreign triangles', 'After the first win'];
        for (const n of names) expect(now.text, `the summary names "${n}"`).toContain(n);
        expect(now.clamp, 'no line clamp').toBe('none');
        expect(now.clipped, 'nothing of the text is cut off').toBeLessThanOrEqual(1);
        expect(now.lines, 'it uses as many lines as it needs').toBeGreaterThanOrEqual(1);
        expect(now.count).toMatch(/3/);
        // the start buttons are still reachable (the longer summary pushes the panel, not the footer)
        const yes = (await page.locator('[data-choice="begin-start"]').boundingBox())!;
        expect(yes.y + yes.height).toBeLessThanOrEqual(size.height);
      });
    });
  }

  test.describe(`start footer, ${size.name}`, () => {
    test.use({ viewport: { width: size.width, height: size.height } });
    test('on a short landscape window the footer is one compact row and leaves the panel at least 72 % of the sheet', async ({ page }) => {
      test.skip(size.name !== '844x390', 'only the short landscape window changes the footer');
      await openApp(page, 'en', '', 'ask', 'fresh');
      const m = await page.evaluate(() => {
        const sheet = document.querySelector<HTMLElement>('[role="dialog"]')!;
        const actions = document.querySelector<HTMLElement>('.ml-start__actions')!.getBoundingClientRect();
        const yes = document.querySelector('[data-choice="begin-yes"]')!.getBoundingClientRect();
        const no = document.querySelector('[data-choice="begin-no"]')!.getBoundingClientRect();
        const q = document.querySelector('.ml-start__question')!.getBoundingClientRect();
        return { share: actions.height / sheet.clientHeight, yesTop: yes.top, noTop: no.top, yesH: yes.height, qTop: q.top, qBottom: q.bottom, yesBottom: yes.bottom };
      });
      expect(m.share, 'the footer is below 20 % of the sheet (28 % before)').toBeLessThan(0.2);
      expect(Math.abs(m.yesTop - m.noTop), 'Yes and No stand in one row').toBeLessThan(2);
      expect(m.qBottom, 'the question stands beside the buttons, not above them').toBeGreaterThan(m.yesTop);
      expect(m.yesH, 'the buttons keep 44 px').toBeGreaterThanOrEqual(MIN - TOLERANCE);
    });
  });
}

// ---- stored choice: the version marker ----

test.describe('the stored size (version marker)', () => {
  test('a choice stored without the marker ("rows":4 was only preselected) opens on 5 rows and keeps the other fields; Start writes the marker; a 4 chosen then stays', async ({ page }) => {
    await page.addInitScript(([key]) => {
      if (sessionStorage.getItem('ml-e2e-seeded')) return; // the seed is for the first load only: a reload must see what the app stored
      sessionStorage.setItem('ml-e2e-seeded', '1');
      localStorage.setItem(key!, '{"jump":"long","mode":"two","rows":4}');
    }, [CHOICE_KEY]);
    await openApp(page, 'en', '', 'ask', 'fresh');
    await expect(page.locator('[data-choice="rows-5"]')).toBeChecked();
    await openRulesPanel(page);
    await expect(page.locator('[data-choice="jump-long"]')).toBeChecked(); // the other fields are kept
    await page.locator('[data-choice="rows-4"]').check();
    await page.locator('[data-choice="begin-yes"]').click();
    await waitForHumanTurn(page);
    const stored = JSON.parse((await page.evaluate((key) => localStorage.getItem(key), CHOICE_KEY))!) as Record<string, unknown>;
    expect(stored).toMatchObject({ v: 2, rows: 4, jump: 'long' });
    await page.evaluate(() => history.replaceState(null, '', location.pathname)); // no game in the address: the begin dialog again
    await page.reload();
    await expect(page.locator('[data-choice="rows-4"]')).toBeChecked();
  });
});

// ---- advantage strip ----

const LONG_TEXT = { de: 'Vorteil: Engine: gewinnt garantiert in 250 Halbzügen (wird aktualisiert)', en: 'Advantage: Engine: guaranteed to win in 250 plies (updating)' } as const;

async function startDemo(page: Page, wide: boolean): Promise<void> {
  if (!wide) await page.locator('.ml-play__bar [aria-controls]').click();
  await page.getByRole('button', { name: 'Engine demo' }).click();
  if (!wide) await page.keyboard.press('Escape');
}

for (const size of SIZES) {
  test.describe(`play screen, ${size.name}`, () => {
    test.use({ viewport: { width: size.width, height: size.height } });

    for (const lang of LANGS) {
      test(`${lang}: a long advantage text wraps, the plies and the updating marker are never cut off, the strip keeps its box`, async ({ page }, info) => {
        const errors = watchPageErrors(page);
        await openApp(page, lang);
        await waitForHumanTurn(page);
        await expect(page.locator('.ml-play__adv .ml-advantage')).toHaveAttribute('data-state', 'value');
        const slot = page.locator('.ml-play__adv');
        const before = (await slot.boundingBox())!;
        const m = await page.evaluate((text) => {
          const adv = document.querySelector<HTMLElement>('.ml-play__adv')!;
          const el = adv.querySelector<HTMLElement>('.ml-advantage__text')!;
          const original = el.innerHTML;
          el.textContent = text;
          const lineHeight = parseFloat(getComputedStyle(el).lineHeight);
          const out = {
            lines: Math.round(el.getBoundingClientRect().height / lineHeight),
            textClipped: el.scrollWidth - el.clientWidth,
            slotClipped: adv.scrollHeight - adv.clientHeight,
            ellipsis: getComputedStyle(el).textOverflow,
            nowrap: getComputedStyle(el).whiteSpace,
            gauge: adv.querySelector('.ml-advantage__gauge')!.getBoundingClientRect().bottom <= adv.getBoundingClientRect().bottom + 0.5,
            box: adv.getBoundingClientRect().height,
          };
          el.innerHTML = original;
          return out;
        }, LONG_TEXT[lang]);
        expect(m.nowrap, 'the text may wrap').not.toBe('nowrap');
        expect(m.ellipsis, 'no ellipsis').not.toBe('ellipsis');
        expect(m.lines, 'two lines at most').toBeLessThanOrEqual(2);
        expect(m.textClipped, 'no horizontal clipping').toBeLessThanOrEqual(1);
        expect(m.slotClipped, 'the strip cuts nothing off').toBeLessThanOrEqual(1);
        expect(m.gauge, 'the gauge stays inside the strip').toBe(true);
        expect(m.box, 'the strip keeps its height').toBeCloseTo(before.height, 0);
        expect((await slot.boundingBox())!.y, 'and its place').toBeCloseTo(before.y, 0);
        await page.evaluate((text) => {
          document.querySelector('.ml-play__adv .ml-advantage__text')!.textContent = text; // for the screenshot: the longest text in the strip
        }, LONG_TEXT[lang]);
        await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-play-strip-${size.name}-${lang}.png`, animations: 'disabled' });
        errors.expectNone();
      });
    }

    test('an engine-vs-engine demo (no marbles at the track ends): the track fills the width', async ({ page }) => {
      test.setTimeout(120_000);
      const errors = watchPageErrors(page);
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      await startDemo(page, size.width >= 960);
      await expect(page.locator('.ml-play__adv .ml-advantage')).toHaveAttribute('data-state', 'unavailable');
      const m = await page.evaluate(() => {
        const gauge = document.querySelector('.ml-advantage__gauge')!.getBoundingClientRect();
        const track = document.querySelector('.ml-advantage__track')!.getBoundingClientRect();
        return { gauge: gauge.width, track: track.width, swatches: document.querySelectorAll('.ml-advantage__gauge .ml-swatch').length };
      });
      expect(m.swatches, 'a demo has no marbles').toBe(0);
      expect(m.track, 'the track is not a sliver').toBeGreaterThan(100);
      expect(m.track / m.gauge, 'the track fills the gauge').toBeGreaterThan(0.98);
      errors.expectNone();
    });

    test('with marbles the track still fills the width between them', async ({ page }) => {
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      const m = await page.evaluate(() => {
        const gauge = document.querySelector('.ml-advantage__gauge')!.getBoundingClientRect();
        const track = document.querySelector('.ml-advantage__track')!.getBoundingClientRect();
        const sw = [...document.querySelectorAll('.ml-advantage__gauge .ml-swatch')].map((s) => s.getBoundingClientRect().width);
        return { gauge: gauge.width, track: track.width, sw };
      });
      expect(m.sw).toHaveLength(2);
      expect(m.track).toBeGreaterThan(m.gauge - m.sw[0]! - m.sw[1]! - 24);
    });
  });
}

test.describe('the gauge fill has the colour of the marble it grows towards', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  for (const scheme of ['light', 'dark'] as const) {
    test(`${scheme}: each fill colour is the marble colour of its side, with an outline that holds 3:1 against the track`, async ({ page }, info) => {
      await page.emulateMedia({ colorScheme: scheme });
      await openApp(page, 'en');
      await waitForHumanTurn(page);
      await expect(page.locator('.ml-play__adv .ml-advantage')).toHaveAttribute('data-state', 'value');
      const r = await page.evaluate(() => {
        const track = document.querySelector<HTMLElement>('.ml-advantage__track')!;
        const probe = (style: string, token: string): { fill: string; want: string; shadow: string; edge: string; trackBg: string } => {
          const fill = document.createElement('div');
          fill.className = 'ml-advantage__fill ml-advantage__fill--you';
          fill.dataset.fill = style;
          track.append(fill);
          const want = document.createElement('div');
          want.style.background = `var(--ml-piece-${token})`;
          const edge = document.createElement('div');
          edge.style.background = `var(--ml-piece-${token}-edge)`;
          document.body.append(want, edge);
          const out = {
            fill: getComputedStyle(fill).backgroundColor,
            want: getComputedStyle(want).backgroundColor,
            shadow: getComputedStyle(fill).boxShadow,
            edge: getComputedStyle(edge).backgroundColor,
            trackBg: getComputedStyle(track).backgroundColor,
          };
          fill.remove();
          want.remove();
          edge.remove();
          return out;
        };
        // which marble stands at the right end: the person's; the fill of "you" grows towards it
        const hrefs = [...document.querySelectorAll('.ml-advantage__gauge use')].map((u) => u.getAttribute('href'));
        return { a: probe('a', 'a'), b: probe('b', 'b'), hrefs, you: document.querySelector('.ml-advantage__fill')?.getAttribute('data-fill') ?? null };
      });
      expect(r.a.fill).toBe(r.a.want);
      expect(r.b.fill).toBe(r.b.want);
      expect(r.a.fill, 'the two sides differ').not.toBe(r.b.fill);
      // the real fill (when the analysis has a leader) carries the style of the right-hand marble
      if (r.you !== null) expect(r.hrefs[1]).toBe(`#ml-piece-${r.you}`);
      for (const side of [r.a, r.b]) {
        expect(side.shadow, 'the outline is the edge colour of the marble').toContain(side.edge);
        expect(contrast(side.edge, side.trackBg), 'outline against the track').toBeGreaterThanOrEqual(3);
      }
      await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-play-${scheme}-390.png`, animations: 'disabled' });
    });
  }
});

function luminance(css: string): number {
  const m = css.match(/rgba?\((\d+), (\d+), (\d+)/)!;
  const [r, g, b] = [m[1], m[2], m[3]].map((v) => {
    const c = Number(v) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
}

// ---- trail: the remembered time survives the menu sheet ----

test.describe('the fade time survives the menu sheet of a small screen', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('Fade out -> 7 s -> Always show -> close and reopen the menu -> Fade out shows 7 s again, also after a reload', async ({ page }) => {
    await openApp(page, 'en');
    await waitForHumanTurn(page);
    const open = async (): Promise<void> => {
      await page.locator('.ml-play__bar [aria-controls]').click();
      await expect(page.locator('[data-trail-card]')).toBeVisible();
    };
    await open();
    await page.locator('[data-trail-setting]').fill('7');
    await expect(page.locator('[data-trail-value]')).toHaveText('7 s');
    await page.locator('[data-trail-mode-input="always"]').check();
    await page.keyboard.press('Escape');
    await expect(page.locator('[data-trail-card]')).toBeHidden();
    await open();
    await expect(page.locator('[data-trail-card]')).toHaveAttribute('data-trail-mode', 'always');
    await page.locator('[data-trail-mode-input="timed"]').check();
    await expect(page.locator('[data-trail-value]')).toHaveText('7 s');
    // and a new session: the preference is "always" again, the time is still 7
    await page.locator('[data-trail-mode-input="always"]').check();
    await page.reload();
    await page.locator('[data-choice="link-side-to-move"]').click(); // the address holds the game: "Continue this game?"
    await waitForHumanTurn(page);
    await open();
    await page.locator('[data-trail-mode-input="timed"]').check();
    await expect(page.locator('[data-trail-value]')).toHaveText('7 s');
  });
});

// ---- dimming ----

async function select(page: Page): Promise<void> {
  await waitForHumanTurn(page);
  await humanTurnCell(page).first().click({ force: true }); // at 844x390 the hit circles overlap; the app resolves the tap to the nearest piece
}

for (const size of SIZES) {
  for (const scheme of ['light', 'dark'] as const) {
    test.describe(`dimming, ${size.name}, ${scheme}`, () => {
      test.use({ viewport: { width: size.width, height: size.height }, colorScheme: scheme });

      test('level "with evaluation": the selected piece dims everything else; the targets and the selected piece stay; hit targets do not change', async ({ page }, info) => {
        const errors = watchPageErrors(page);
        await page.addInitScript(() => localStorage.setItem('ml.guides', 'eval'));
        await openApp(page, 'en');
        await waitForHumanTurn(page);
        const cells = (): Promise<string[]> =>
          page.evaluate(() => [...document.querySelectorAll('[data-cell]')].map((c) => `${c.getAttribute('data-cell')}:${c.getAttribute('r')}:${c.getAttribute('cx')}:${c.getAttribute('cy')}`));
        const before = await cells();
        await expect(page.locator('.ml-board__svg')).not.toHaveAttribute('data-dim', /.*/);
        await select(page);
        await expect(page.locator('.ml-board__svg')).toHaveAttribute('data-dim', '');
        const op = (selector: string): Promise<number[]> => page.$$eval(selector, (els) => els.map((e) => Number(getComputedStyle(e).opacity)));
        await expect.poll(async () => (await op('.ml-board__sockets'))[0], { message: 'the sockets fade to 0.4' }).toBeCloseTo(0.4, 1);
        const others = await op('.ml-pieces use:not(.is-selected)');
        expect(others.length).toBeGreaterThan(10);
        for (const o of others) expect(o).toBeCloseTo(0.4, 1);
        expect(await op('.ml-pieces use.is-selected')).toEqual([1]);
        expect((await op('.ml-select'))[0]).toBe(1);
        const targets = await op('.ml-targets .ml-target__ring');
        expect(targets.length).toBeGreaterThan(0);
        for (const o of targets) expect(o).toBe(1);
        expect(await cells(), 'the hit circles are the same').toEqual(before);
        await page.screenshot({ path: `${SHOT_DIR}/${info.project.name}-play-dimmed-${size.name}-${scheme}.png`, animations: 'disabled' });
        // the keyboard still works: Escape clears the selection and the dimming
        await page.keyboard.press('Escape');
        await expect(page.locator('.ml-board__svg')).not.toHaveAttribute('data-dim', /.*/);
        errors.expectNone();
      });
    });
  }
}

test.describe('dimming off and calm', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  for (const level of ['none', 'targets'] as const) {
    test(`level "${level}": nothing is dimmed`, async ({ page }) => {
      await page.addInitScript((l) => localStorage.setItem('ml.guides', l), level);
      await openApp(page, 'en');
      await select(page);
      await expect(page.locator('.ml-board__svg')).not.toHaveAttribute('data-dim', /.*/);
      expect(await page.$$eval('.ml-pieces use', (els) => els.every((e) => getComputedStyle(e).opacity === '1'))).toBe(true);
    });
  }

  test('reduced motion: no fade (the dimming switches at once)', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.addInitScript(() => localStorage.setItem('ml.guides', 'eval'));
    await openApp(page, 'en');
    await select(page);
    expect(await page.$eval('.ml-board__sockets', (e) => getComputedStyle(e).transitionDuration)).toBe('0s');
    expect(await page.$eval('.ml-pieces use', (e) => getComputedStyle(e).transitionDuration)).toBe('0s');
  });

  test('without reduced motion the fade lasts 0.2 s', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.addInitScript(() => localStorage.setItem('ml.guides', 'eval'));
    await openApp(page, 'en');
    await waitForHumanTurn(page);
    expect(await page.$eval('.ml-board__sockets', (e) => getComputedStyle(e).transitionDuration)).toBe('0.2s');
  });
});
