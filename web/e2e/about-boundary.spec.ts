// Figure J7 (TeaVM and the call boundary, topic java-web, #fig-boundary) in the browser. The jsdom test (boundary.dom.test.tsx) pins the state and
// the texts; this spec pins what only a browser shows: the stable layout (the narration is one grid cell with ghosts, every part keeps its place
// and size from step 0 to step 6 in each build), the compact (320 px) and wide (1280 px) layouts, the wall styles as drawn, keyboard use,
// reduced motion, light and dark, and the contrast of the text in the lanes. Notes: web/design/about/about-notes.md, "Stable layout".
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { openApp } from './helpers';

type Size = { width: number; height: number };
const SIZES: Size[] = [
  { width: 320, height: 568 },
  { width: 390, height: 844 },
  { width: 820, height: 1180 },
  { width: 1280, height: 800 },
];
const VARIANTS = ['js', 'wasmgc', 'cpp'] as const;
type Variant = (typeof VARIANTS)[number];
const TOLERANCE = 0.5;
const FIG = 'figure#fig-boundary';

/** The parts whose place and size must not change from step to step (selector inside the figure). */
const PARTS: Record<string, string> = {
  status: '.ml-fig__status',
  stage: '.ml-fig__stage',
  readout: '.ml-fig-readout',
  lanes: '.ml-fig-lanes',
  page: '[data-lane="page"]',
  glue: '[data-lane="glue"]',
  engine: '[data-lane="engine"]',
  wallA: '[data-wall="msg"]',
  wallB: '[data-wall="call"]',
  legend: '.ml-fig-legend',
  payload: '[data-testid="boundary-payload"]',
  table: '[data-testid="boundary-compare"]',
  also: '.ml-fig-j7__also',
  caption: 'figcaption',
};

interface Box {
  top: number;
  left: number;
  width: number;
  height: number;
}
interface Sample {
  step: string;
  figure: Box;
  parts: Record<string, Box>;
  /** Rects of the live narration line and of its seven ghosts: one grid cell, so all have the same place and size. */
  narration: Box[];
}

async function stepThrough(fig: Locator): Promise<Sample[]> {
  return fig.evaluate(async (el, parts): Promise<Sample[]> => {
    const frames = (): Promise<void> => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
    const buttons = el.querySelectorAll<HTMLButtonElement>('.ml-fig-stepper button');
    const posText = (): string => (el.querySelector('.ml-fig-stepper__pos')?.textContent ?? '').replace(/\s+/g, ' ').trim();
    const fb = (): DOMRect => el.getBoundingClientRect();
    const box = (r: DOMRect): Box => ({ top: r.top - fb().top, left: r.left - fb().left, width: r.width, height: r.height });
    const read = (): Sample => {
      const found: Record<string, Box> = {};
      for (const [name, selector] of Object.entries(parts)) {
        const node = el.querySelector(selector);
        if (node) found[name] = box(node.getBoundingClientRect());
      }
      return {
        step: posText(),
        figure: { top: 0, left: 0, width: fb().width, height: fb().height },
        parts: found,
        narration: [...el.querySelectorAll('.ml-fig__status-line')].map((n) => box(n.getBoundingClientRect())),
      };
    };
    buttons[0]!.click();
    await frames();
    const seen: Sample[] = [read()];
    for (let guard = 0; guard < 20; guard++) {
      const m = /(\d+) \/ (\d+)/.exec(posText());
      if (!m || Number(m[1]) >= Number(m[2])) break;
      buttons[buttons.length - 1]!.click();
      await frames();
      seen.push(read());
    }
    return seen;
  }, PARTS);
}

function expectStable(label: string, samples: Sample[]): void {
  expect(samples.length, `${label}: seven steps seen`).toBe(7);
  const first = samples[0]!;
  expect(Object.keys(first.parts).sort(), `${label}: every part exists`).toEqual(Object.keys(PARTS).sort());
  for (const s of samples) {
    const where = `${label} step ${s.step}`;
    expect(Math.abs(s.figure.height - first.figure.height), `${where}: figure height ${s.figure.height} vs ${first.figure.height}`).toBeLessThanOrEqual(TOLERANCE);
    for (const [name, b] of Object.entries(s.parts)) {
      const a = first.parts[name]!;
      for (const k of ['top', 'left', 'width', 'height'] as const) {
        expect(Math.abs(b[k] - a[k]), `${where}: ${name}.${k} ${b[k]} vs ${a[k]}`).toBeLessThanOrEqual(TOLERANCE);
      }
    }
    // the narration is one grid cell: the live line and the seven ghosts share one rect
    expect(s.narration, `${where}: the live line and seven ghosts`).toHaveLength(8);
    for (const n of s.narration) {
      for (const k of ['top', 'left', 'width', 'height'] as const) expect(Math.abs(n[k] - s.narration[0]![k]), `${where}: narration ${k}`).toBeLessThanOrEqual(TOLERANCE);
    }
  }
}

async function open(page: Page, size: Size, lang: 'en' | 'de' = 'en', scheme: 'light' | 'dark' = 'light'): Promise<Locator> {
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
  await openApp(page, lang, '#/about/java-web');
  const fig = page.locator(FIG);
  await fig.waitFor();
  await page.evaluate(() => document.fonts.ready);
  await fig.scrollIntoViewIfNeeded();
  return fig;
}

async function pickVariant(fig: Locator, variant: Variant): Promise<void> {
  await fig.locator(`.ml-fig-seg input[value="${variant}"]`).evaluate((el) => (el as HTMLInputElement).click());
  await expect(fig).toHaveAttribute('data-variant', variant);
}

async function goTo(fig: Locator, step: number): Promise<void> {
  await fig.evaluate((el, n) => {
    const buttons = el.querySelectorAll<HTMLButtonElement>('.ml-fig-stepper button');
    buttons[0]!.click();
    for (let i = 0; i < n; i++) buttons[buttons.length - 1]!.click();
  }, step);
  await expect(fig).toHaveAttribute('data-step', String(step));
}

async function switchLanguage(page: Page, lang: 'en' | 'de'): Promise<void> {
  const button = page.locator(`.ml-header [data-language="${lang}"]`);
  await button.click();
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

// ---- stable layout ----

for (const size of SIZES) {
  test(`J7 ${size.width}x${size.height}: every part keeps its place and size over steps 0 to 6, in each build, en and de (narration in one grid cell)`, async ({ page }) => {
    const fig = await open(page, size);
    for (const lang of ['en', 'de'] as const) {
      if (lang === 'de') await switchLanguage(page, 'de');
      for (const variant of VARIANTS) {
        await fig.scrollIntoViewIfNeeded();
        await pickVariant(fig, variant);
        expectStable(`${lang} ${variant} ${size.width}`, await stepThrough(fig));
      }
    }
    // a resize keeps the rule
    const other = SIZES[(SIZES.indexOf(size) + 1) % SIZES.length]!;
    await page.setViewportSize(other);
    await fig.scrollIntoViewIfNeeded();
    expectStable(`de resized to ${other.width}`, await stepThrough(fig));
  });
}

test('J7: a switch of the build in the wide layout keeps the figure height and the place of the stage (the only variant-dependent height is the compact table)', async ({ page }) => {
  const fig = await open(page, { width: 1280, height: 800 });
  const heights = new Set<number>();
  for (const variant of VARIANTS) {
    await pickVariant(fig, variant);
    await goTo(fig, 3);
    heights.add(Math.round((await fig.boundingBox())!.height * 2) / 2);
  }
  expect([...heights], 'one height for the three builds at 1280 px').toHaveLength(1);
});

// ---- compact and wide ----

test('J7 compact (320 px): one column, the selected table column only, no horizontal scroll, no text under 12 px, targets of 44 px', async ({ page }) => {
  const fig = await open(page, { width: 320, height: 568 });
  for (const variant of VARIANTS) {
    await pickVariant(fig, variant);
    for (const step of [0, 3, 5]) {
      await goTo(fig, step);
      const probe = await fig.evaluate((el) => {
        const rect = (s: string): DOMRect => el.querySelector(s)!.getBoundingClientRect();
        const figBox = el.getBoundingClientRect();
        const over: string[] = [];
        el.querySelectorAll('*').forEach((n) => {
          const r = n.getBoundingClientRect();
          const cs = getComputedStyle(n);
          if (!r.width || !r.height || cs.visibility === 'hidden' || n.closest('svg')) return;
          if (r.right > figBox.right + 0.5 || r.left < figBox.left - 0.5) over.push(`${n.tagName}.${(n as HTMLElement).className} ${Math.round(r.left - figBox.left)}..${Math.round(r.right - figBox.left)}`);
        });
        const small: string[] = [];
        el.querySelectorAll('*').forEach((n) => {
          const cs = getComputedStyle(n);
          if (cs.visibility === 'hidden' || n.closest('svg') || !n.getBoundingClientRect().width) return;
          if ([...n.childNodes].some((c) => c.nodeType === 3 && c.textContent!.trim()) && parseFloat(cs.fontSize) < 12) small.push(`${n.tagName} ${cs.fontSize}`);
        });
        const targets = [...el.querySelectorAll('.ml-fig-seg__opt, .ml-fig-stepper button')].map((n) => {
          const r = n.getBoundingClientRect();
          return `${n.tagName} ${Math.round(r.width)}x${Math.round(r.height)}`;
        });
        const col = (s: string): DOMRect => rect(s);
        return {
          overflow: over,
          small,
          targets,
          pageScroll: document.scrollingElement!.scrollWidth - document.scrollingElement!.clientWidth,
          visibleCols: [...el.querySelectorAll('thead th[data-col]')].filter((n) => getComputedStyle(n).display !== 'none').map((n) => (n as HTMLElement).dataset.col),
          threadCells: [...el.querySelectorAll('tr[data-row="thread"] td')].filter((n) => getComputedStyle(n).display !== 'none').length,
          pageAboveGlue: col('[data-lane="page"]').bottom <= col('[data-lane="glue"]').top + 0.5,
          glueAboveEngine: col('[data-lane="glue"]').bottom <= col('[data-lane="engine"]').top + 0.5,
          sameColumn: Math.abs(col('[data-lane="page"]').left - col('[data-lane="engine"]').left) < 40,
        };
      });
      const where = `${variant} step ${step}`;
      expect(probe.overflow, `${where}: elements beyond the figure`).toEqual([]);
      expect(probe.pageScroll, `${where}: page scroll`).toBeLessThanOrEqual(0);
      expect(probe.small, `${where}: text under 12 px`).toEqual([]);
      for (const t of probe.targets) {
        const [w, h] = t.split(' ')[1]!.split('x').map(Number);
        expect(Math.min(w!, h!), `${where}: target ${t}`).toBeGreaterThanOrEqual(43.5);
      }
      expect(probe.visibleCols, `${where}: table columns`).toEqual([variant]);
      expect(probe.threadCells, `${where}: the thread row has one cell`).toBe(1);
      expect([probe.pageAboveGlue, probe.glueAboveEngine], `${where}: lanes stand one below the other`).toEqual([true, true]);
    }
  }
});

test('J7 wide (1280 px): one row of lanes in the order of the call, three table columns, the thread row as one cell across them', async ({ page }) => {
  const fig = await open(page, { width: 1280, height: 800 });
  const probe = await fig.evaluate((el) => {
    const r = (s: string): DOMRect => el.querySelector(s)!.getBoundingClientRect();
    const cols = [...el.querySelectorAll('thead th[data-col]')].filter((n) => getComputedStyle(n).display !== 'none').length;
    const thread = [...el.querySelectorAll('tr[data-row="thread"] td')].filter((n) => getComputedStyle(n).display !== 'none');
    const table = r('[data-testid="boundary-compare"]');
    return {
      order: r('[data-lane="page"]').right <= r('[data-wall="msg"]').right && r('[data-wall="msg"]').right <= r('[data-lane="glue"]').left + 1 && r('[data-lane="glue"]').right <= r('[data-lane="engine"]').left + 1,
      sameRow: Math.abs(r('[data-lane="glue"]').top - r('[data-lane="engine"]').top) < 1,
      cols,
      threadCells: thread.length,
      threadSpan: thread[0] ? (thread[0] as HTMLTableCellElement).colSpan : 0,
      threadWidth: thread[0]?.getBoundingClientRect().width ?? 0,
      tableWidth: table.width,
      noScroll: document.scrollingElement!.scrollWidth <= document.scrollingElement!.clientWidth,
    };
  });
  expect(probe.order, 'page, wall A, glue, engine from left to right').toBe(true);
  expect(probe.sameRow, 'glue and engine in one row').toBe(true);
  expect(probe.cols).toBe(3);
  expect([probe.threadCells, probe.threadSpan]).toEqual([1, 3]);
  expect(probe.threadWidth, 'the thread cell is wider than a single column').toBeGreaterThan(probe.tableWidth * 0.5);
  expect(probe.noScroll).toBe(true);
});

// ---- the walls as drawn ----

test('J7: wall A is the same dashed double line in every build, wall B is dotted, double or thick (vertical lines in the wide layout)', async ({ page }) => {
  const style = (fig: Locator, wall: 'msg' | 'call'): Promise<{ top: string; left: string; topW: number; leftW: number }> =>
    fig.locator(`[data-wall="${wall}"] .ml-fig-lanes__line`).evaluate((el) => {
      const cs = getComputedStyle(el);
      return { top: cs.borderTopStyle, left: cs.borderLeftStyle, topW: parseFloat(cs.borderTopWidth), leftW: parseFloat(cs.borderLeftWidth) };
    });
  for (const [size, axis] of [
    [{ width: 320, height: 568 }, 'top'],
    [{ width: 1280, height: 800 }, 'left'],
  ] as const) {
    const fig = await open(page, size);
    const widthKey = axis === 'top' ? 'topW' : 'leftW';
    const expected: Record<Variant, [string, number]> = { js: ['dotted', 2], wasmgc: ['solid', 2], cpp: ['solid', 6] };
    for (const variant of VARIANTS) {
      await pickVariant(fig, variant);
      const a = await style(fig, 'msg');
      expect([a[axis], a[widthKey]], `wall A at ${size.width}`).toEqual(['dashed', 2]);
      const b = await style(fig, 'call');
      expect([b[axis], b[widthKey]], `wall B ${variant} at ${size.width}`).toEqual(expected[variant]);
    }
  }
});

test('J7: the direction badge shows on wall A at steps 1 and 6 and on wall B at steps 3 and 5, and its arrow turns with the layout', async ({ page }) => {
  for (const [size, rot] of [
    [{ width: 320, height: 568 }, { in: 90, out: -90 }],
    [{ width: 1280, height: 800 }, { in: 0, out: 180 }],
  ] as const) {
    const fig = await open(page, size);
    const badge = async (wall: 'msg' | 'call'): Promise<{ visible: boolean; angle: number }> =>
      fig.locator(`[data-wall="${wall}"] .ml-fig-lanes__dir`).evaluate((el) => {
        const m = new DOMMatrix(getComputedStyle(el.querySelector('svg')!).transform);
        return { visible: getComputedStyle(el).visibility === 'visible', angle: Math.round((Math.atan2(m.b, m.a) * 180) / Math.PI) };
      });
    const seen: string[] = [];
    for (let step = 0; step <= 6; step++) {
      await goTo(fig, step);
      const [a, b] = [await badge('msg'), await badge('call')];
      seen.push(`${a.visible ? a.angle : '-'}/${b.visible ? b.angle : '-'}`);
    }
    const norm = (n: number): number => (n === -180 ? 180 : n);
    expect(seen.map((s) => s.split('/').map((x) => (x === '-' ? x : String(norm(Number(x))))).join('/')), `badges at ${size.width}`).toEqual([
      '-/-',
      `${rot.in}/-`,
      '-/-',
      `-/${rot.in}`,
      '-/-',
      `-/${rot.out}`,
      `${rot.out}/-`,
    ]);
  }
});

// ---- keyboard ----

test('J7: keyboard use. Arrow keys on the stepper step, Home and End jump, an arrow key on the variant switch changes the build and keeps the step', async ({ page }) => {
  const fig = await open(page, { width: 820, height: 1180 });
  await fig.locator('.ml-fig-stepper button').last().focus();
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  await expect(fig).toHaveAttribute('data-step', '2');
  await page.keyboard.press('End');
  await expect(fig).toHaveAttribute('data-step', '6');
  await page.keyboard.press('Home');
  await expect(fig).toHaveAttribute('data-step', '1');
  await page.keyboard.press('ArrowLeft');
  await expect(fig).toHaveAttribute('data-step', '0');
  await goTo(fig, 3);
  await fig.locator('.ml-fig-seg input:checked').focus();
  await page.keyboard.press('ArrowRight');
  await expect(fig).toHaveAttribute('data-variant', 'cpp');
  await expect(fig).toHaveAttribute('data-step', '3');
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('ArrowLeft');
  await expect(fig).toHaveAttribute('data-variant', 'js');
  await expect(fig).toHaveAttribute('data-step', '3');
});

test('J7: the tab order is the variant switch, the stepper, then the source chips; the lanes and walls are no tab stops', async ({ page }) => {
  const fig = await open(page, { width: 820, height: 1180 });
  const stops = await fig.evaluate((el) => {
    const tabbable = [...el.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])')].filter((n) => getComputedStyle(n).visibility !== 'hidden');
    return tabbable.map((n) => (n.matches('input[type="radio"]') ? 'radio' : n.matches('button') ? 'button' : n.matches('a') ? 'link' : n.tagName));
  });
  const firstButton = stops.indexOf('button');
  expect(stops.slice(0, firstButton).every((s) => s === 'radio')).toBe(true);
  expect(stops.slice(firstButton).filter((s) => s !== 'button' && s !== 'link')).toEqual([]);
  expect(stops.lastIndexOf('button')).toBeLessThan(stops.indexOf('link'));
  expect(await fig.locator('.ml-fig-lanes [tabindex]').count()).toBe(0);
});

// ---- motion ----

test('J7: reduced motion has no play button and no transition; with motion allowed the play button runs the seven states in about seven seconds and stops at step 6', async ({ page }) => {
  const fig = await open(page, { width: 1280, height: 800 });
  expect(await fig.locator('.ml-fig-stepper button').count(), 'reset, back, forward').toBe(3);
  const duration = (): Promise<string> => fig.locator('[data-lane="page"]').evaluate((el) => getComputedStyle(el).transitionDuration);
  expect(await duration()).toBe('0s');
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await expect(fig.locator('.ml-fig-stepper button'), 'reset, back, play, forward').toHaveCount(4);
  expect(await duration()).toMatch(/^0\.2s/);
  await fig.locator('.ml-fig-stepper button').nth(2).click();
  await expect(fig).toHaveAttribute('data-step', '6', { timeout: 15_000 });
  await expect(fig.locator('.ml-fig-stepper button').nth(2)).toBeVisible();
});

// ---- light, dark, contrast ----

for (const scheme of ['light', 'dark'] as const) {
  test(`J7 ${scheme}: text in the lanes, the region label, the wall tags and the selected table column reach their contrast (4.5), the lane outlines 3`, async ({ page }) => {
    const fig = await open(page, { width: 1280, height: 800 }, 'en', scheme);
    await goTo(fig, 3);
    const result = await fig.evaluate(() => {
      const parse = (c: string): [number, number, number, number] => {
        const m = /rgba?\(([^)]+)\)/.exec(c);
        const p = (m ? m[1]! : '0,0,0,0').split(/[ ,/]+/).filter(Boolean).map(Number);
        return [p[0] ?? 0, p[1] ?? 0, p[2] ?? 0, p[3] ?? 1];
      };
      const lum = ([r, g, b]: number[]): number => {
        const f = (v: number): number => ((v / 255) <= 0.03928 ? v / 255 / 12.92 : (((v / 255) + 0.055) / 1.055) ** 2.4);
        return 0.2126 * f(r!) + 0.7152 * f(g!) + 0.0722 * f(b!);
      };
      const ratio = (a: number[], b: number[]): number => {
        const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
        return (hi! + 0.05) / (lo! + 0.05);
      };
      /** The first opaque background up the tree. */
      const bgOf = (el: Element): number[] => {
        for (let n: Element | null = el; n; n = n.parentElement) {
          const c = parse(getComputedStyle(n).backgroundColor);
          if (c[3]! > 0.99) return c;
        }
        return parse(getComputedStyle(document.body).backgroundColor);
      };
      const textRatio = (s: string): { s: string; ratio: number } => {
        const el = document.querySelector(s)!;
        return { s, ratio: ratio(parse(getComputedStyle(el).color), bgOf(el)) };
      };
      const edge = (s: string, prop: 'borderTopColor' | 'borderLeftColor'): { s: string; ratio: number } => {
        const el = document.querySelector(s)!;
        return { s, ratio: ratio(parse(getComputedStyle(el)[prop]), bgOf(el.parentElement!)) };
      };
      return {
        text: [
          textRatio('[data-lane="glue"][data-state="active"] .ml-fig-lanes__name'),
          textRatio('[data-lane="glue"][data-state="active"] .ml-fig-lanes__code > :not([data-off])'),
          textRatio('[data-lane="glue"][data-state="active"] .ml-fig-lanes__mem'),
          textRatio('[data-lane="glue"][data-state="active"] [data-pill] > :not([data-off])'),
          textRatio('[data-lane="page"][data-state="dim"] .ml-fig-lanes__name'),
          textRatio('[data-lane="page"][data-state="dim"] .ml-fig-lanes__code'),
          textRatio('.ml-fig-lanes__glabel'),
          textRatio('[data-wall="msg"] .ml-fig-lanes__wtag'),
          textRatio('[data-wall="call"] .ml-fig-lanes__wname'),
          textRatio('[data-wall="call"] .ml-fig-lanes__wtag'),
          textRatio('thead th[data-sel]'),
          textRatio('tbody td[data-sel] > span'),
          textRatio('tbody th'),
          textRatio('.ml-fig-j7__how > :not([data-off]) > span'),
          textRatio('.ml-fig-j7__code'),
        ],
        lines: [
          edge('[data-lane="glue"][data-state="active"]', 'borderTopColor'),
          edge('[data-lane="page"][data-state="dim"]', 'borderTopColor'),
          edge('[data-wall="msg"] .ml-fig-lanes__line', 'borderLeftColor'),
          edge('[data-wall="call"] .ml-fig-lanes__line', 'borderLeftColor'),
        ],
      };
    });
    for (const t of result.text) expect(t.ratio, `${scheme} text ${t.s}: ${t.ratio.toFixed(2)}`).toBeGreaterThanOrEqual(4.5);
    for (const l of result.lines) expect(l.ratio, `${scheme} line ${l.s}: ${l.ratio.toFixed(2)}`).toBeGreaterThanOrEqual(3);
  });
}

test('J7: the active lane is teal in light and dark, the dim lane is not, and a state is also a mark and a word (not colour alone)', async ({ page }) => {
  const fig = await open(page, { width: 1280, height: 800 });
  await goTo(fig, 3);
  const probe = (): Promise<{ active: string; dim: string; outline: string; dimOutline: string; pill: string }> =>
    fig.evaluate((el) => {
      const a = el.querySelector('[data-lane="glue"]')!;
      const d = el.querySelector('[data-lane="page"]')!;
      return {
        active: getComputedStyle(a).backgroundColor,
        dim: getComputedStyle(d).backgroundColor,
        outline: getComputedStyle(a).outlineWidth,
        dimOutline: getComputedStyle(d).outlineWidth,
        pill: (a.querySelector('[data-pill] > :not([data-off])')!.textContent ?? '').trim(),
      };
    });
  const light = await probe();
  await page.emulateMedia({ colorScheme: 'dark' });
  const dark = await probe();
  expect(light.active).not.toBe(light.dim);
  expect(dark.active).not.toBe(dark.dim);
  expect(light.active).not.toBe(dark.active);
  expect(parseFloat(light.outline), 'the active lane has a thicker outline').toBeGreaterThan(parseFloat(light.dimOutline));
  expect(light.pill.length, 'the state pill has a word').toBeGreaterThan(0);
});

// ---- content ----

test('J7: the counters show N + 4 and N + 10 for C++ and 1 for TeaVM, and wall A is crossed twice in every build', async ({ page }) => {
  const fig = await open(page, { width: 1280, height: 800 });
  const calls = (): Promise<string> => fig.locator('[data-r="calls"]').innerText();
  const msgs = (): Promise<string> => fig.locator('[data-r="msgs"]').innerText();
  for (const [variant, at3, at5] of [
    ['js', '1', '1'],
    ['wasmgc', '1', '1'],
    ['cpp', 'N + 4', 'N + 10'],
  ] as const) {
    await pickVariant(fig, variant);
    await goTo(fig, 3);
    expect(await calls(), `${variant} at step 3`).toBe(at3);
    await goTo(fig, 5);
    expect(await calls(), `${variant} at step 5`).toBe(at5);
    await goTo(fig, 6);
    expect(await msgs()).toBe('2 / 2');
  }
});

test('J7: the narration, the payload and the table follow the build at step 3 and say what the spec names for it', async ({ page }) => {
  const fig = await open(page, { width: 820, height: 1180 });
  await goTo(fig, 3);
  const visiblePay = (): Promise<string> => fig.locator('[data-pay] > :not([data-off])').innerText();
  await pickVariant(fig, 'cpp');
  expect(await visiblePay()).toContain('_shw_play(m1)');
  await expect(fig.locator('[data-testid="boundary-payload"]')).toHaveAttribute('data-how', 'numbers');
  await pickVariant(fig, 'js');
  expect(await visiblePay()).toContain('m.choose(');
  await expect(fig.locator('[data-testid="boundary-payload"]')).toHaveAttribute('data-how', 'direct');
  await expect(fig.locator('tr[data-row="call"]')).toHaveAttribute('data-on', '');
  await expect(fig.locator('thead th[data-sel]')).toHaveAttribute('data-col', 'js');
});
