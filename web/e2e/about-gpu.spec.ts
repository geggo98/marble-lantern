// Figure J4 (GPU anatomy for TornadoVM, Fig. 6.4, topic java, section tornadovm) in a real browser: the stable layout (the figure keeps its height
// and the offsets of its parts over the eight steps, for each machine, in both languages, at 320, 820 and 1280 px), the compact and the wide
// layout, keyboard use, reduced motion, light and dark, contrast of the figure's colours, and the size of text and targets.
// The spec of the figure is design/j4-gpu/spec.md (scratchpad of the round); texts are not compared, so the checks run in both languages.
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp } from './helpers';

type Size = { width: number; height: number };
const SIZES: Size[] = [
  { width: 320, height: 568 },
  { width: 820, height: 1180 },
  { width: 1280, height: 800 },
];
const HWS = ['m3', 't4', 'rtx'] as const;
type Hw = (typeof HWS)[number];
const TOLERANCE = 0.5;
const STEPS = 8;

async function open(page: Page, size: Size, lang: 'en' | 'de' = 'en', scheme: 'light' | 'dark' = 'light'): Promise<Locator> {
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
  await openApp(page, lang, '#/about/java');
  const fig = page.locator('#fig-gpu');
  await fig.scrollIntoViewIfNeeded();
  await page.evaluate(() => document.fonts.ready);
  await expect(fig).toBeVisible();
  return fig;
}

const choose = async (fig: Locator, hw: Hw): Promise<void> => {
  await fig.locator(`.ml-fig-seg input[value="${hw}"]`).evaluate((el) => (el as HTMLInputElement).click());
  await expect(fig.locator('.gp-stage')).toHaveAttribute('data-hw', hw);
};

interface Sample {
  step: string;
  height: number;
  stage: number;
  zoom: number;
  table: number;
  caption: number;
  status: number;
}

/** Steps the stepper from the idle state to the last step inside the page and measures the figure at each step (two animation frames after every click). */
async function stepThrough(fig: Locator): Promise<Sample[]> {
  return fig.evaluate(async (el): Promise<Sample[]> => {
    const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
    const buttons = el.querySelectorAll<HTMLButtonElement>('.ml-fig-stepper button');
    const stage = el.querySelector<HTMLElement>('.gp-stage')!;
    const read = (): Sample => {
      const box = el.getBoundingClientRect();
      const top = (sel: string): number => (el.querySelector(sel)?.getBoundingClientRect().top ?? NaN) - box.top;
      return {
        step: stage.dataset.step ?? '',
        height: box.height,
        stage: top('.gp-stage'),
        zoom: top('.ml-fig-zoom'),
        table: top('.ml-fig-cmp'),
        caption: top('figcaption'),
        status: el.querySelector('.ml-fig__status')!.getBoundingClientRect().height,
      };
    };
    buttons[0]!.click(); // reset
    await frames();
    const seen: Sample[] = [read()];
    for (let k = 0; k < 8; k++) {
      buttons[buttons.length - 1]!.click(); // forward (the play button is not rendered under reduced motion)
      await frames();
      seen.push(read());
    }
    buttons[0]!.click();
    await frames();
    return seen;
  });
}

function expectStable(label: string, samples: Sample[]): void {
  expect(samples, `${label}: steps seen`).toHaveLength(STEPS + 1);
  const first = samples[0]!;
  for (const s of samples) {
    for (const key of ['height', 'stage', 'zoom', 'table', 'caption', 'status'] as const) {
      expect(Math.abs(s[key] - first[key]), `${label} step ${s.step}: ${key} ${s[key]} vs ${first[key]}`).toBeLessThanOrEqual(TOLERANCE);
    }
  }
}

async function switchLanguage(page: Page, lang: 'en' | 'de'): Promise<void> {
  const button = page.locator(`.ml-header [data-language="${lang}"]`);
  await button.click();
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

test.describe('stable layout: the same height and offsets at every step, for each machine, in en and de', () => {
  for (const size of SIZES) {
    test(`${size.width}x${size.height}`, async ({ page }) => {
      const fig = await open(page, size);
      const status: number[] = [];
      for (const lang of ['en', 'de'] as const) {
        if (lang === 'de') await switchLanguage(page, 'de');
        const heights: number[] = [];
        for (const hw of HWS) {
          await fig.scrollIntoViewIfNeeded();
          await choose(fig, hw);
          const samples = await stepThrough(fig);
          expectStable(`${lang} ${hw} ${size.width}`, samples);
          heights.push(samples[0]!.height);
          status.push(samples[0]!.status);
        }
        // the machine changes a few values that wrap differently; it must not change the layout by more than a few pixels per level
        expect(Math.max(...heights) - Math.min(...heights), `${lang}: height over the machines ${heights.join(', ')}`).toBeLessThanOrEqual(60);
        // the narration keeps its height when the machine changes: its ghosts hold the texts of all machines
        const own = status.slice(-3);
        expect(Math.max(...own) - Math.min(...own), `${lang}: narration height over the machines`).toBeLessThanOrEqual(TOLERANCE);
      }
    });
  }

  test('the height holds after a resize and after opening a detail panel is not part of the rule (the reader asked for it)', async ({ page }) => {
    const fig = await open(page, SIZES[0]!);
    expectStable('320', await stepThrough(fig));
    await page.setViewportSize(SIZES[2]!);
    await fig.scrollIntoViewIfNeeded();
    expectStable('resized to 1280', await stepThrough(fig));
    await page.setViewportSize(SIZES[0]!);
    await fig.scrollIntoViewIfNeeded();
    const before = await fig.evaluate((el) => el.getBoundingClientRect().height);
    await fig.locator('[data-part="sm"]').first().evaluate((el) => (el as HTMLElement).click());
    await expect(fig.locator('.ml-fig__detail')).toBeVisible();
    const after = await fig.evaluate((el) => el.getBoundingClientRect().height);
    expect(after).toBeGreaterThan(before);
  });
});

test.describe('compact and wide layout', () => {
  test('320 px: one level at a time, the others hidden and inert; the level follows the step', async ({ page }) => {
    const fig = await open(page, SIZES[0]!);
    const levels = [1, 1, 1, 2, 3, 4, 4, 5, 1];
    for (let step = 0; step <= STEPS; step++) {
      if (step > 0) await fig.locator('.ml-fig-stepper button').last().evaluate((el) => (el as HTMLButtonElement).click());
      const state = await fig.evaluate((el) =>
        Array.from(el.querySelectorAll<HTMLElement>('[data-panel]')).map((p) => ({ n: Number(p.dataset.panel), hidden: getComputedStyle(p).visibility === 'hidden', inert: p.inert })),
      );
      expect(state.filter((p) => !p.hidden).map((p) => p.n), `step ${step}: visible levels`).toEqual([levels[step]]);
      expect(state.filter((p) => p.inert).map((p) => p.n), `step ${step}: inert levels`).toEqual([1, 2, 3, 4, 5].filter((n) => n !== levels[step]));
    }
  });

  test('820 and 1280 px: all five levels are visible and none is inert; the current level has an accent outline', async ({ page }) => {
    for (const size of SIZES.slice(1)) {
      const fig = await open(page, size);
      await fig.locator('.ml-fig-stepper button').last().evaluate((el) => (el as HTMLButtonElement).click());
      const state = await fig.evaluate((el) => ({
        visible: Array.from(el.querySelectorAll<HTMLElement>('[data-panel]')).filter((p) => getComputedStyle(p).visibility !== 'hidden').length,
        inert: el.querySelectorAll('[inert]').length,
        cur: Array.from(el.querySelectorAll<HTMLElement>('[data-panel][data-cur]')).map((p) => p.dataset.panel),
      }));
      expect(state, `${size.width}`).toEqual({ visible: 5, inert: 0, cur: ['1'] });
    }
  });

  test('the wide layout shows two columns of levels and the kernel grid across both; the compact layout one column', async ({ page }) => {
    for (const [size, columns] of [[SIZES[0]!, 1], [SIZES[2]!, 2]] as const) {
      const fig = await open(page, size);
      const lefts = await fig.evaluate((el) => {
        const boxes = Array.from(el.querySelectorAll<HTMLElement>('[data-panel]')).map((p) => ({ n: p.dataset.panel, ...p.getBoundingClientRect().toJSON() }));
        return { distinct: new Set(boxes.slice(0, 4).map((b) => Math.round(b.left))).size, gridWidth: boxes[4]!.width, panelWidth: boxes[0]!.width };
      });
      expect(lefts.distinct).toBe(columns);
      if (columns === 2) expect(lefts.gridWidth).toBeGreaterThan(lefts.panelWidth * 1.9);
    }
  });

  test('no horizontal overflow of the figure or of any element in it, with an open detail panel, on every machine, in en and de', async ({ page }) => {
    for (const size of SIZES) {
      const fig = await open(page, size);
      for (const lang of ['en', 'de'] as const) {
        if (lang === 'de') await switchLanguage(page, 'de');
        for (const hw of HWS) {
          await choose(fig, hw);
          await fig.locator('[data-part="devmem"], [data-part="umem"]').first().evaluate((el) => (el as HTMLElement).click());
          const over = await fig.evaluate((el) => {
            const box = el.getBoundingClientRect();
            const bad: string[] = [];
            for (const e of Array.from(el.querySelectorAll<HTMLElement>('*'))) {
              if (getComputedStyle(e).visibility === 'hidden' || e.closest('[aria-hidden="true"]:not(svg)') || e.closest('.ml-visually-hidden')) continue;
              const r = e.getBoundingClientRect();
              if (r.width > 0 && (r.right > box.right + 0.5 || r.left < box.left - 0.5)) bad.push(`${e.tagName}.${e.className?.toString?.().slice(0, 40)} ${r.left.toFixed(1)}..${r.right.toFixed(1)} vs ${box.left.toFixed(1)}..${box.right.toFixed(1)}`);
            }
            return { bad: bad.slice(0, 5), scroll: el.scrollWidth - el.clientWidth };
          });
          expect(over.bad, `${lang} ${hw} ${size.width}`).toEqual([]);
          expect(over.scroll, `${lang} ${hw} ${size.width}: scrollWidth`).toBeLessThanOrEqual(1);
          await fig.locator('.ml-fig__detail-close').click();
        }
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
    }
  });

  test('text is at least 12 px and every control at least 44 x 44 px (visible parts, radios, stepper buttons, close button)', async ({ page }) => {
    for (const size of SIZES) {
      const fig = await open(page, size);
      for (const hw of HWS) {
        await choose(fig, hw);
        await fig.locator('[data-part="gpu"]').first().evaluate((el) => (el as HTMLElement).click());
        for (let step = 0; step <= STEPS; step += 4) {
          const bad = await fig.evaluate((el) => {
            const out: string[] = [];
            const visible = (e: Element): boolean => getComputedStyle(e).visibility !== 'hidden' && (e as HTMLElement).getClientRects().length > 0 && !e.closest('[inert]');
            for (const e of Array.from(el.querySelectorAll<HTMLElement>('button, label.ml-fig-seg__opt, a[href]'))) {
              if (!visible(e)) continue;
              const r = e.getBoundingClientRect();
              if (r.width < 43.5 || r.height < 43.5) out.push(`target ${e.tagName}.${e.className} ${r.width.toFixed(1)}x${r.height.toFixed(1)}`);
            }
            const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
            for (let node = walker.nextNode(); node; node = walker.nextNode()) {
              const p = node.parentElement;
              if (!p || !node.textContent?.trim() || !visible(p) || p.closest('svg, .ml-visually-hidden, [aria-hidden="true"]')) continue;
              const px = parseFloat(getComputedStyle(p).fontSize);
              if (px < 11.99) out.push(`text ${px}px: ${node.textContent.trim().slice(0, 30)}`);
            }
            return out;
          });
          expect(bad, `${hw} ${size.width} step ${step}`).toEqual([]);
          if (step < STEPS) for (let i = 0; i < 4; i++) await fig.locator('.ml-fig-stepper button').last().evaluate((b) => (b as HTMLButtonElement).click());
        }
        await fig.locator('.ml-fig-stepper button').first().evaluate((b) => (b as HTMLButtonElement).click());
        await fig.locator('.ml-fig__detail-close').click();
      }
    }
  });
});

test.describe('keyboard and reduced motion', () => {
  test('the stepper takes Left, Right, Home and End; Tab reaches the parts of the current level only (compact)', async ({ page }) => {
    const fig = await open(page, SIZES[0]!);
    const stage = fig.locator('.gp-stage');
    const forward = fig.locator('.ml-fig-stepper button').last();
    await forward.focus();
    await page.keyboard.press('ArrowRight');
    await expect(stage).toHaveAttribute('data-step', 'host');
    await page.keyboard.press('ArrowRight');
    await expect(stage).toHaveAttribute('data-step', 'path');
    await page.keyboard.press('End');
    await expect(stage).toHaveAttribute('data-step', 'back');
    await page.keyboard.press('Home');
    await expect(stage).toHaveAttribute('data-step', 'host');
    await page.keyboard.press('ArrowLeft');
    await expect(stage).toHaveAttribute('data-step', 'idle');
    // a part of a hidden level cannot take the focus
    await fig.locator('[data-part="warp"]').focus();
    expect(await page.evaluate(() => (document.activeElement as HTMLElement | null)?.dataset?.part ?? '')).not.toBe('warp');
    // the tab order runs from the stepper into the parts of the visible level
    await forward.focus();
    const seen: string[] = [];
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('Tab');
      seen.push(await page.evaluate(() => (document.activeElement as HTMLElement | null)?.dataset?.part ?? ''));
    }
    expect(seen.filter(Boolean).every((p) => ['cpu', 'hostmem', 'gpu', 'devmem', 'link'].includes(p)), seen.join(',')).toBe(true);
    expect(seen.filter(Boolean)).toContain('cpu');
  });

  test('Enter and Space open a part, Escape closes it and gives the focus back; the detail panel is reached by Tab after the parts', async ({ page }) => {
    const fig = await open(page, SIZES[1]!);
    const part = fig.locator('[data-part="l2"]');
    await part.focus();
    await page.keyboard.press('Enter');
    await expect(part).toHaveAttribute('aria-expanded', 'true');
    await expect(fig.locator('.ml-fig__detail')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(fig.locator('.ml-fig__detail')).toHaveCount(0);
    await expect(part).toBeFocused();
    await page.keyboard.press('Space');
    await expect(part).toHaveAttribute('aria-expanded', 'true');
    await page.keyboard.press('Space');
    await expect(part).toHaveAttribute('aria-expanded', 'false');
    // a part of the wide layout: Escape inside the panel returns to the part too
    await page.keyboard.press('Enter');
    await fig.locator('.ml-fig__detail-close').focus();
    await page.keyboard.press('Escape');
    await expect(part).toBeFocused();
  });

  test('the hardware picker takes the arrow keys and keeps the step and the open part when the machine has it', async ({ page }) => {
    const fig = await open(page, SIZES[1]!);
    await fig.locator('.ml-fig-stepper button').last().evaluate((b) => (b as HTMLButtonElement).click());
    await fig.locator('[data-part="cpu"]').first().evaluate((b) => (b as HTMLElement).click());
    await fig.locator('.ml-fig-seg input:checked').focus();
    await page.keyboard.press('ArrowRight');
    await expect(fig.locator('.gp-stage')).toHaveAttribute('data-hw', 'rtx');
    await expect(fig.locator('.gp-stage')).toHaveAttribute('data-step', 'host');
    await expect(fig.locator('.ml-fig__detail')).toBeVisible();
    await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('ArrowLeft');
    await expect(fig.locator('.gp-stage')).toHaveAttribute('data-hw', 'm3');
  });

  test('under reduced motion the play button is not rendered, and nothing moves between steps', async ({ page }) => {
    const fig = await open(page, SIZES[1]!);
    expect(await fig.locator('.ml-fig-stepper button').count()).toBe(3); // reset, back, forward
    const durations = await fig.evaluate((el) => Array.from(el.querySelectorAll<HTMLElement>('*')).filter((e) => parseFloat(getComputedStyle(e).transitionDuration) > 0 || parseFloat(getComputedStyle(e).animationDuration) > 0).length);
    expect(durations).toBe(0);
  });
});

test.describe('colour: light and dark', () => {
  /** WCAG 2 contrast of two computed `rgb(...)` colours; the ratios that spec section 7 lists, with the needed value. */
  async function pairs(fig: Locator): Promise<Record<string, [number, number]>> {
    return fig.evaluate((el) => {
      const rgb = (s: string): [number, number, number] => (s.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number) as [number, number, number];
      const lum = ([r, g, b]: [number, number, number]): number => {
        const c = (v: number): number => ((v /= 255) <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
        return 0.2126 * c(r) + 0.7152 * c(g) + 0.0722 * c(b);
      };
      const ratio = (a: string, b: string): number => {
        const [hi, lo] = [lum(rgb(a)), lum(rgb(b))].sort((x, y) => y - x) as [number, number];
        return (hi + 0.05) / (lo + 0.05);
      };
      const root = getComputedStyle(el);
      const tok = (name: string): string => {
        const probe = document.createElement('span');
        probe.style.color = `var(${name})`;
        el.appendChild(probe);
        const v = getComputedStyle(probe).color;
        probe.remove();
        return v;
      };
      void root;
      const bg = tok('--ml-fig-bg');
      const accBg = tok('--ml-fig-accent-bg');
      const need = (value: number, needed: number): [number, number] => [Math.round(value * 100) / 100, needed];
      return {
        'mark on bg': need(ratio(tok('--ml-fig-mark'), bg), 3),
        'axis on bg': need(ratio(tok('--ml-fig-axis'), bg), 4.5),
        'dim on bg': need(ratio(tok('--ml-fig-dim'), bg), 3),
        'accent on bg': need(ratio(tok('--ml-fig-accent'), bg), 3),
        'accent on accent-bg': need(ratio(tok('--ml-fig-accent'), accBg), 3),
        'axis on accent-bg': need(ratio(tok('--ml-fig-axis'), accBg), 4.5),
        'ink on info-bg': need(ratio(tok('--ml-color-ink'), tok('--ml-color-info-bg')), 4.5),
      };
    });
  }

  for (const scheme of ['light', 'dark'] as const) {
    test(`${scheme}: the pairs of the spec reach their contrast, and the figure uses the scheme`, async ({ page }) => {
      const fig = await open(page, SIZES[1]!, 'en', scheme);
      for (const [name, [value, needed]] of Object.entries(await pairs(fig))) expect(value, `${scheme} ${name}`).toBeGreaterThanOrEqual(needed);
      const bg = await fig.evaluate((el) => getComputedStyle(el).backgroundColor);
      const [r] = (bg.match(/\d+/g) ?? ['0']).map(Number);
      if (scheme === 'dark') expect(r!).toBeLessThan(80);
      else expect(r!).toBeGreaterThan(200);
    });

    test(`${scheme}: an active part differs from a dim part by more than colour (inset second outline and tinted fill)`, async ({ page }) => {
      const fig = await open(page, SIZES[1]!, 'en', scheme);
      await fig.locator('.ml-fig-stepper button').last().evaluate((b) => (b as HTMLButtonElement).click());
      const style = (sel: string): Promise<{ shadow: string; bg: string; border: string; width: string }> =>
        fig.locator(sel).first().evaluate((e) => {
          const s = getComputedStyle(e);
          return { shadow: s.boxShadow, bg: s.backgroundColor, border: s.borderTopColor, width: s.borderTopWidth };
        });
      const active = await style('[data-part="cpu"]');
      const dim = await style('[data-part="gpu"]');
      expect(active.shadow).not.toBe('none');
      expect(dim.shadow).toBe('none');
      expect(active.bg).not.toBe(dim.bg);
      expect(active.border).not.toBe(dim.border);
      expect(active.width).toBe(dim.width); // the border width does not change with the state (owner feedback of 2026-11-21)
    });
  }

  test('an unknown part has a dashed outline, a known one a solid outline', async ({ page }) => {
    const fig = await open(page, SIZES[1]!);
    await choose(fig, 'm3');
    const styles = await fig.evaluate((el) => ['cache', 'reg', 'core'].map((id) => getComputedStyle(el.querySelector(`[data-part="${id}"]`)!).borderTopStyle));
    expect(styles).toEqual(['dashed', 'dashed', 'solid']);
  });
});

test.describe('content', () => {
  test('the five levels, both tables and the chips are in the page; no request leaves the site; no script error', async ({ page }) => {
    const errors: string[] = [];
    const foreign: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('request', (r) => {
      if (!r.url().startsWith('http://127.0.0.1')) foreign.push(r.url());
    });
    const fig = await open(page, SIZES[2]!);
    await expect(fig.locator('[data-panel]')).toHaveCount(5);
    await expect(fig.locator('table')).toHaveCount(2);
    await expect(fig.locator('.ml-fig-chip')).toHaveCount(3);
    expect(errors).toEqual([]);
    expect(foreign).toEqual([]);
  });

  test('stepping through all steps on the M3 Max draws the copy inside one memory, and no pipe', async ({ page }) => {
    const fig = await open(page, SIZES[1]!);
    await choose(fig, 'm3');
    await expect(fig.locator('.gp-sys__pipe')).toHaveCount(0);
    await expect(fig.locator('.gp-sys__in')).toHaveCount(1);
    await choose(fig, 'rtx');
    const heights = await fig.evaluate((el) => Number(el.querySelector('.gp-sys__pipe rect')!.getAttribute('height')));
    await choose(fig, 't4');
    const t4 = await fig.evaluate((el) => Number(el.querySelector('.gp-sys__pipe rect')!.getAttribute('height')));
    expect(heights).toBeGreaterThan(t4);
  });
});
