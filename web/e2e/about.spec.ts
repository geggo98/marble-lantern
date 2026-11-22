// End-to-end matrix of the Info part (plan section 6.5): hub, seven topics and the glossary, in four viewports, two themes and two languages.
// The full matrix is sampled to keep the run short:
//   - every route x all four sizes in en/light (28 cells),
//   - every route in de/dark at 320 x 568 and 844 x 390 (14 cells),
//   - one 'no-preference' run per topic that steps every stepper and plays one animation (6 runs).
// E2E_WIDE_FONT=1 (the repo switch, applied by `openApp`) runs the same cells with a wide fallback font; the de cells at 320 must pass.
// Checks per cell: no horizontal scroll (page and every figure frame), accessible names of all controls, role and name of every figure
// svg, heading order, focus order with a visible ring on every stop, targets of 44 px, no running animation under reduced motion,
// no page errors. The checks run in the page (`probe*` functions) and return plain data; the expectations stand in the tests.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, watchPageErrors } from './helpers';

const TOPICS = ['architecture', 'classical', 'inference', 'training', 'tuning', 'java', 'java-web'] as const;
// Topics whose page has no figure yet (the code snippets are figures of another kind, figure.ml-code).
const FIGURELESS: readonly string[] = ['java-web'];
const ROUTES = [{ id: 'hub', hash: '#/about' }, ...TOPICS.map((t) => ({ id: t, hash: `#/about/${t}` })), { id: 'glossary', hash: '#/about/glossary' }] as const;

const SIZES = [
  { width: 320, height: 568 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
  { width: 820, height: 1180 },
] as const;

const MIN = 44;
const TOLERANCE = 0.6; // as in mobile.spec.ts: sub-pixel rounding of the bounding boxes

type Theme = 'light' | 'dark';
type Language = 'de' | 'en';
interface Cell {
  width: number;
  height: number;
  theme: Theme;
  language: Language;
}

const CELLS: Cell[] = [
  ...SIZES.map((s) => ({ ...s, theme: 'light' as const, language: 'en' as const })),
  ...SIZES.filter((s) => s.width === 320 || s.width === 844).map((s) => ({ ...s, theme: 'dark' as const, language: 'de' as const })),
];

async function openRoute(page: Page, route: (typeof ROUTES)[number], cell: Cell, motion: 'reduce' | 'no-preference'): Promise<void> {
  await page.setViewportSize({ width: cell.width, height: cell.height });
  await page.emulateMedia({ colorScheme: cell.theme, reducedMotion: motion });
  await page.addInitScript((theme) => {
    try {
      localStorage.setItem('ml.theme', theme); // the theme switch's own store; the app sets data-theme on :root from it
    } catch {
      // the system colour scheme (emulateMedia) still applies
    }
  }, cell.theme);
  await openApp(page, cell.language, route.hash);
  await expect(page.locator('h1')).toBeVisible();
  if (route.id === 'hub') {
    await expect(page.locator('main[data-topic]')).toHaveCount(0);
  } else {
    await expect(page.locator('main[data-topic]')).toHaveAttribute('data-topic', route.id);
  }
  // java-web has no figure until J6 and J7 are built in (FIGURELESS); remove the exception then.
  if ((TOPICS as readonly string[]).includes(route.id) && !FIGURELESS.includes(route.id)) await expect(page.locator('figure.ml-fig').first()).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', cell.theme);
}

// ---- probes: run in the page, return data ----

const probeOverflow = (page: Page): Promise<{ page: number; frames: string[] }> =>
  page.evaluate(() => {
    const se = document.scrollingElement!;
    const frames: string[] = [];
    document.querySelectorAll<HTMLElement>('.ml-fig').forEach((f) => {
      if (f.scrollWidth - f.clientWidth > 0) frames.push(`${f.id || 'figure'} scrollWidth ${f.scrollWidth} > clientWidth ${f.clientWidth}`);
    });
    return { page: se.scrollWidth - se.clientWidth, frames };
  });

/** Controls without an accessible name (an approximation of the accname algorithm: aria-labelledby, aria-label, labels, content, title). */
const probeNames = (page: Page): Promise<{ seen: number; unnamed: string[] }> =>
  page.evaluate(() => {
    const visible = (el: Element): boolean => {
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return (r.width > 0 || r.height > 0) && s.visibility !== 'hidden' && !el.closest('[inert]');
    };
    const textOf = (node: Node): string => {
      if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? '';
      if (!(node instanceof Element) || node.getAttribute('aria-hidden') === 'true') return '';
      if (node.tagName === 'svg') return node.querySelector('title')?.textContent ?? '';
      return Array.from(node.childNodes).map(textOf).join('');
    };
    const nameOf = (el: Element): string => {
      const by = el.getAttribute('aria-labelledby');
      if (by) {
        const t = by.split(/\s+/).map((id) => textOf(document.getElementById(id) ?? document.createTextNode(''))).join(' ').trim();
        if (t) return t;
      }
      const label = el.getAttribute('aria-label')?.trim();
      if (label) return label;
      const labels = (el as HTMLInputElement).labels;
      if (labels && labels.length) {
        const t = Array.from(labels).map(textOf).join(' ').trim();
        if (t) return t;
      }
      if (!['INPUT', 'SELECT', 'TEXTAREA'].includes(el.tagName)) {
        const t = textOf(el).trim();
        if (t) return t;
      }
      return el.getAttribute('title')?.trim() ?? '';
    };
    const controls = Array.from(document.querySelectorAll('button, a[href], input:not([type="hidden"]), select, textarea, summary, [role="button"], [role="radio"], [role="slider"]')).filter(visible);
    return {
      seen: controls.length,
      unnamed: controls.filter((c) => nameOf(c) === '').map((c) => `<${c.tagName.toLowerCase()} class="${c.getAttribute('class') ?? ''}"> in ${c.closest('[id]')?.id ?? 'page'}`),
    };
  });

/** Every svg of a figure is role=img with title and desc, or role=group with a resolvable aria-labelledby; decorative svgs are aria-hidden. */
const probeSvgs = (page: Page): Promise<{ seen: number; bad: string[] }> =>
  page.evaluate(() => {
    const bad: string[] = [];
    let seen = 0;
    document.querySelectorAll('.ml-fig svg').forEach((svg) => {
      if (svg.closest('[aria-hidden="true"]') || svg.parentElement?.closest('svg')) return; // decorative, or nested in an svg that is checked itself
      seen++;
      const where = `${svg.closest('.ml-fig')?.id ?? 'figure'} svg.${svg.getAttribute('class') ?? ''}`;
      const role = svg.getAttribute('role');
      const text = (id: string): string => document.getElementById(id)?.textContent?.trim() ?? '';
      if (role === 'img') {
        const title = svg.querySelector(':scope > title')?.textContent?.trim();
        const desc = svg.querySelector(':scope > desc')?.textContent?.trim();
        if (!title || !desc) bad.push(`${where}: role=img without title+desc`);
      } else if (role === 'group') {
        const ids = (svg.getAttribute('aria-labelledby') ?? '').split(/\s+/).filter(Boolean);
        if (ids.length === 0 || ids.some((id) => text(id) === '')) bad.push(`${where}: role=group without resolvable aria-labelledby`);
      } else bad.push(`${where}: role=${role ?? 'none'}`);
    });
    return { seen, bad };
  });

const probeHeadings = (page: Page): Promise<{ levels: number[]; h1: number; skipped: string[] }> =>
  page.evaluate(() => {
    const hs = Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6, [role="heading"]'));
    const levels = hs.map((h) => (h.hasAttribute('aria-level') ? Number(h.getAttribute('aria-level')) : Number(h.tagName.slice(1)) || 2));
    const skipped: string[] = [];
    levels.forEach((l, i) => {
      if (i > 0 && l > levels[i - 1]! + 1) skipped.push(`h${levels[i - 1]} -> h${l} at "${(hs[i]!.textContent ?? '').trim().slice(0, 40)}"`);
    });
    if (levels.length && levels[0] !== 1) skipped.push(`first heading is h${levels[0]}`);
    return { levels, h1: levels.filter((l) => l === 1).length, skipped };
  });

interface TargetBox {
  what: string;
  width: number;
  height: number;
}
const probeTargets = (page: Page): Promise<TargetBox[]> =>
  page.evaluate(() => {
    const out: { what: string; width: number; height: number }[] = [];
    const take = (el: Element, what: string): void => {
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      if (r.width <= 0 || r.height <= 0 || s.visibility === 'hidden' || el.closest('[inert]')) return;
      out.push({ what: `${what} "${(el.textContent ?? '').trim().slice(0, 24) || el.getAttribute('aria-label') || ''}"`, width: r.width, height: r.height });
    };
    // Not checked: the cells of the cell picker board of Fig. 3.1 (class ml-fig-board__cell--picker). 61 cells sit on a board 300 px wide; a 44 px target
    // is geometrically impossible there (the cell pitch is about 34 board units). This is listed as open in the report of WP7.
    document.querySelectorAll('button, summary, select, [role="button"], [role="radio"], input[type="range"], a.ml-btn, .ml-fig-chip, .ml-about__crumb').forEach((el) => !el.classList.contains("ml-fig-board__cell--picker") && take(el, el.tagName.toLowerCase()));
    document.querySelectorAll('input[type="checkbox"], input[type="radio"]').forEach((el) => take(el.closest('label') ?? el, 'label of input'));
    return out;
  });

interface FocusStop {
  what: string;
  ring: boolean;
  top: number;
  bottom: number;
  left: number;
  right: number;
  figure: string;
  inToc: boolean;
}

/** Tab through the page from the breadcrumb; the stops are recorded by a focusin listener (computed styles as of the moment of focus). */
async function tabThrough(page: Page): Promise<{ stops: FocusStop[]; expected: number }> {
  const expected = await page.evaluate(() => {
    // the breadcrumb of a topic page; the hub has none, so the focus starts at its h1 (tabindex -1)
    const start = document.querySelector<HTMLElement>('.ml-about__crumb') ?? document.querySelector<HTMLElement>('h1');
    if (start && start.tabIndex < 0) start.setAttribute('tabindex', '-1'); // the hub's h1 is no focus target; make it a start point only
    start?.focus();
    // the links of a closed <details> (the "On this page" list below 60rem) are no tab stops; its summary is
    const inClosedDetails = (e: HTMLElement): boolean => {
      const d = e.closest('details');
      return d !== null && !d.open && !e.closest('summary');
    };
    const tabbable = Array.from(document.querySelectorAll<HTMLElement>('main a[href], main button, main input, main select, main summary, main [tabindex]')).filter((e) => {
      const r = e.getBoundingClientRect();
      return e.tabIndex >= 0 && (r.width > 0 || r.height > 0) && getComputedStyle(e).visibility !== 'hidden' && !(e as HTMLInputElement).disabled && !e.closest('[inert]') && !inClosedDetails(e);
    });
    // a radio group is one tab stop, however many radios it has
    const radios = new Set<string>();
    return tabbable.filter((e) => {
      if (!(e instanceof HTMLInputElement) || e.type !== 'radio') return true;
      if (radios.has(e.name)) return false;
      radios.add(e.name);
      return true;
    }).length;
  });
  // A script focus does not show the keyboard ring; go one stop back and forth with the keyboard so that :focus-visible applies from here on.
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Tab');
  await page.evaluate(() => {
    const w: Window & { __stops?: unknown[] } = window;
    w.__stops = [];
    const record = (el: HTMLElement): void => {
      const ringOf = (n: Element | null, pseudo?: string): boolean => {
        if (!n) return false;
        const s = getComputedStyle(n, pseudo);
        return s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0;
      };
      const r = el.getBoundingClientRect();
      w.__stops!.push({
        what: `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''} "${(el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 24)}"`,
        // the ring sits on the element, on its label or option (radios, checkboxes), or on its ::after (a card link that covers its card)
        ring: ringOf(el) || ringOf(el.closest('label')) || ringOf(el.closest('.ml-fig-seg__opt')) || ringOf(el, '::after'),
        top: r.top + scrollY,
        bottom: r.bottom + scrollY,
        left: r.left,
        right: r.right,
        figure: el.closest('.ml-fig')?.id ?? '',
        inToc: !!el.closest('.ml-about__toc'),
      });
    };
    document.addEventListener('focusin', (e) => record(e.target as HTMLElement), true);
    record(document.activeElement as HTMLElement); // the start stop
  });
  const limit = Math.min(expected + 5, 400);
  for (let i = 0; i < limit; i++) {
    await page.keyboard.press('Tab');
    const outside = await page.evaluate(() => !document.activeElement?.closest('main'));
    if (outside) break;
  }
  const stops = await page.evaluate(() => (window as unknown as { __stops: FocusStop[] }).__stops);
  return { stops, expected };
}

const probeAnimations = (page: Page): Promise<string[]> =>
  page.evaluate(() =>
    document
      .getAnimations()
      .filter((a) => a.playState === 'running' && Number(a.effect?.getComputedTiming().endTime ?? 0) > 0)
      .map((a) => `${a.constructor.name} ${(a as CSSAnimation).animationName ?? (a as CSSTransition).transitionProperty ?? ''} on ${(a.effect as KeyframeEffect | null)?.target?.tagName?.toLowerCase() ?? '?'}`),
  );

/** Presses "forward" of every stepper to the last step; returns the position texts as "n / n". */
async function stepAll(page: Page): Promise<string[]> {
  const ends: string[] = [];
  const steppers = page.locator('.ml-fig-stepper');
  const n = await steppers.count();
  for (let i = 0; i < n; i++) {
    const s = steppers.nth(i);
    const fwd = s.locator('button').nth((await s.locator('button').count()) - 1);
    for (let k = 0; k < 400 && (await fwd.getAttribute('aria-disabled')) !== 'true'; k++) await fwd.click();
    ends.push(((await s.locator('.ml-fig-stepper__pos').textContent()) ?? '').replace(/\s+/g, ' ').trim());
  }
  return ends;
}

// ---- the matrix ----

test.describe('about matrix (reduced motion)', () => {
  for (const route of ROUTES) {
    for (const cell of CELLS) {
      test(`${route.id} ${cell.width}x${cell.height} ${cell.theme} ${cell.language}`, async ({ page }) => {
        const errors = watchPageErrors(page);
        await openRoute(page, route, cell, 'reduce');

        const o = await probeOverflow(page);
        expect(o.page, 'horizontal scroll of the page').toBeLessThanOrEqual(0);
        expect(o.frames, 'figure frames that scroll horizontally').toEqual([]);

        const raw = await page.evaluate(() => /\{(sec|term|src|n):/.exec(document.body.innerText)?.[0] ?? null);
        expect(raw, 'raw catalog markup in the rendered page').toBeNull();

        const names = await probeNames(page);
        expect(names.seen, 'the probe saw controls').toBeGreaterThan(0);
        expect(names.unnamed, 'controls without an accessible name').toEqual([]);

        const svgs = await probeSvgs(page);
        // The architecture diagrams are HTML boxes below 640 px (BoxDiagram); the other topics draw svgs at every size.
        const hasSvg = (TOPICS as readonly string[]).includes(route.id) && !FIGURELESS.includes(route.id) && !(route.id === 'architecture' && cell.width < 640);
        if (hasSvg) expect(svgs.seen, 'the probe saw figure svgs').toBeGreaterThan(0);
        expect(svgs.bad, 'figure svgs without role and name').toEqual([]);

        const h = await probeHeadings(page);
        expect(h.h1, `h1 count (levels ${h.levels.join('')})`).toBe(1);
        expect(h.skipped, 'skipped heading levels').toEqual([]);

        const targets = await probeTargets(page);
        expect(targets.length, 'the probe saw targets').toBeGreaterThan(0);
        const small = targets.filter((t) => t.width < MIN - TOLERANCE || t.height < MIN - TOLERANCE);
        expect(small.map((t) => `${t.what} ${t.width.toFixed(1)}x${t.height.toFixed(1)}`), 'targets under 44 px').toEqual([]);

        // reduced motion: no play button, and nothing animates after a step
        if (route.id !== 'hub' && route.id !== 'glossary') {
          const perStepper = await page.locator(".ml-fig-stepper").evaluateAll((els) => els.map((e) => e.querySelectorAll("button").length));
          expect(perStepper.filter((n) => n !== 3), "buttons of a stepper under reduced motion (reset, back, forward: no play button)").toEqual([]);
          await stepAll(page);
        }
        expect(await probeAnimations(page), 'running animations under reduced motion').toEqual([]);
        const o2 = await probeOverflow(page);
        expect(o2.page, 'horizontal scroll after the last step').toBeLessThanOrEqual(0);
        expect(o2.frames, 'figure frames that scroll after the last step').toEqual([]);

        const { stops, expected } = await tabThrough(page);
        expect(stops.length, `focus stops (the page has ${expected} tabbable elements in main)`).toBeGreaterThanOrEqual(Math.min(expected, 400));
        expect(stops.filter((s) => !s.ring).map((s) => s.what), 'focused elements without a visible ring').toEqual([]);
        const violations: string[] = [];
        stops.forEach((s, i) => {
          const prev = stops[i - 1];
          // reading order: the next stop may not lie entirely above the previous one unless it lies entirely to its right (a second column)
          if (prev && s.figure === prev.figure && s.bottom < prev.top - 2 && s.left < prev.right) violations.push(`${prev.what} -> ${s.what}`);
        });
        expect(violations, 'focus order against the visual order').toEqual([]);
        const firstFigure = stops.findIndex((s) => s.figure !== '');
        const lastToc = stops.map((s) => s.inToc).lastIndexOf(true);
        if (firstFigure >= 0 && lastToc >= 0) expect(lastToc, 'the On-this-page nav comes before the first figure control').toBeLessThan(firstFigure);

        errors.expectNone();
      });
    }
  }
});

// ---- motion allowed: step every stepper, play one animation ----

test.describe('about topics (motion allowed)', () => {
  for (const topic of TOPICS) {
    test(`${topic} steppers and one animation`, async ({ page }) => {
      const errors = watchPageErrors(page);
      const route = ROUTES.find((r) => r.id === topic)!;
      await openRoute(page, route, { width: 390, height: 844, theme: 'light', language: 'en' }, 'no-preference');
      const steppers = page.locator('.ml-fig-stepper');
      const count = await steppers.count();
      if (topic === 'tuning' || topic === 'java-web') {
        // The tuning and java-web figures have no stepper (tuning: a staircase with a filter, a curve with a phase switch, sweeps): nothing to step or play. Topic java has one since figure J3 (SIMD, 11 steps).
        expect(count, 'tuning or java figures with a stepper').toBe(0);
        errors.expectNone();
        return;
      }
      expect(count, 'steppers on the page').toBeGreaterThan(0);
      for (let i = 0; i < count; i++) {
        await expect(steppers.nth(i).locator('button[aria-label]')).toHaveCount(4); // reset, back, play, forward: the play button exists when motion is allowed
      }
      const ends = await stepAll(page);
      for (const text of ends) {
        if (/Result$/.test(text)) continue; // V5 counts root moves (k / N) and names its last step, the result, instead of numbering it N + 1
        const m = /(\d+) \/ (\d+)$/.exec(text);
        expect(m, `position text "${text}"`).not.toBeNull();
        expect(m![1], `stepper ends at its last step ("${text}")`).toBe(m![2]);
      }
      const o = await probeOverflow(page);
      expect(o.page, 'horizontal scroll of the page at the last steps').toBeLessThanOrEqual(0);
      expect(o.frames, 'figure frames that scroll at the last steps').toEqual([]);

      // one animation: reset the first stepper, press play, a step must come by itself and the button must offer "pause"
      const first = steppers.first();
      const buttons = first.locator('button');
      await buttons.nth(0).click(); // reset
      await expect(first.locator('.ml-fig-stepper__pos')).toContainText(/0 \/ \d+$/);
      const play = buttons.nth(2);
      const playLabel = await play.getAttribute('aria-label');
      await play.click();
      expect(await play.getAttribute('aria-label'), 'the play button turns into pause').not.toBe(playLabel);
      await expect(first.locator('.ml-fig-stepper__pos')).not.toHaveText(/(^|\D)0 \/ \d+$/, { timeout: 5_000 });
      await play.click(); // pause
      expect(await play.getAttribute('aria-label')).toBe(playLabel);
      errors.expectNone();
    });
  }
});
