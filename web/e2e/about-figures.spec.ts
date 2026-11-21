// Checks of single figures of the Info part that only a browser can make: type size on the screen, what a line crosses, which way a table is laid
// out. Each check belongs to a finding of the design review (web/design/about/ and the review of 2026-11-21); the matrix of about.spec.ts checks
// the page as a whole. Texts are not compared, so the checks run in both languages.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp } from './helpers';

type Size = { width: number; height: number };
const PHONE: Size = { width: 320, height: 568 };
const TABLET: Size = { width: 820, height: 1180 };

async function open(page: Page, topic: string, fig: string, size: Size, lang: 'en' | 'de' = 'en'): Promise<void> {
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
  await openApp(page, lang, `#/about/${topic}`);
  const el = page.locator(`#${fig}`);
  await el.scrollIntoViewIfNeeded();
  await page.evaluate(() => document.fonts.ready);
  await expect(el).toBeVisible();
}

/** Screen pixels per view box unit of an svg. */
const scaleOf = (page: Page, selector: string): Promise<number> =>
  page.evaluate((sel) => {
    const svg = document.querySelector<SVGSVGElement>(sel);
    if (!svg) throw new Error(`no ${sel}`);
    return svg.getBoundingClientRect().width / svg.viewBox.baseVal.width;
  }, selector);

test.describe('plots in a slot narrower than the figure draw one unit per pixel (M4)', () => {
  const cases: [string, string, string, string][] = [
    ['classical', 'fig-eval', '#fig-eval svg.ml-fig-plot', 'sigmoid of Fig. 2.3'],
    ['training', 'fig-loop', '#fig-loop svg.ml-fig-plot', 'plots of Fig. 4.2'],
    ['inference', 'fig-mcts', '#fig-mcts svg.ml-fig-plot', 'inset of Fig. 3.3'],
    ['tuning', 'fig-story-sweeps', '#fig-story-sweeps svg.ml-fig-plot', 'plots of Fig. 5.4'],
  ];
  for (const [topic, fig, selector, name] of cases) {
    for (const size of [TABLET, { width: 1280, height: 900 }]) {
      test(`${name} at ${size.width}`, async ({ page }) => {
        await open(page, topic, fig, size);
        const scales = await page.evaluate((sel) => [...document.querySelectorAll<SVGSVGElement>(sel)].map((s) => s.getBoundingClientRect().width / s.viewBox.baseVal.width), selector);
        expect(scales.length).toBeGreaterThan(0);
        // one unit is one pixel: the type of 12 units is 12 px, not 17 to 20 px
        for (const s of scales) expect(s).toBeGreaterThan(0.9);
        for (const s of scales) expect(s).toBeLessThan(1.1);
      });
    }
  }
});

test('Fig. 2.1 at 820: the tree is the widest part, its glyphs are at least 12 px and the board is not larger than 16 rem (M5)', async ({ page }) => {
  await open(page, 'classical', 'fig-ab', TABLET);
  const scale = await scaleOf(page, '#fig-ab svg.ml-fig-ice');
  const tree = await page.locator('#fig-ab svg.ml-fig-ice').boundingBox();
  const board = await page.locator('#fig-ab .ml-fig-ab__board svg').first().boundingBox();
  const readout = await page.locator('#fig-ab .ml-fig-readout').boundingBox();
  expect(tree!.width).toBeGreaterThan(640);
  expect(12 * scale).toBeGreaterThanOrEqual(12);
  expect(board!.width).toBeLessThanOrEqual(16 * 16 + 1);
  expect(tree!.width).toBeGreaterThan(board!.width * 2);
  // the board and the readout stand side by side above the tree; the readout has no empty gap cell (four cells share the row)
  expect(Math.abs(board!.y - readout!.y)).toBeLessThan(40);
  const cells = await page.locator('#fig-ab .ml-fig-readout__cell').evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().y)));
  expect(new Set(cells).size).toBe(1);
});

for (const size of [PHONE, TABLET, { width: 1280, height: 900 }]) {
  test(`Fig. 2.1 legend entries do not break between mark and word at ${size.width} (m6)`, async ({ page }) => {
    await open(page, 'classical', 'fig-ab', size);
    const items = await page.locator('#fig-ab .ml-fig-legend__item').evaluateAll((els) => els.map((e) => ({ h: e.getBoundingClientRect().height, wrap: getComputedStyle(e).whiteSpace })));
    expect(items.length).toBeGreaterThan(5);
    for (const i of items) expect(i.h).toBeLessThan(26);
    for (const i of items) expect(i.wrap).toBe('nowrap');
  });
}

for (const lang of ['en', 'de'] as const) {
  test(`Fig. 3.3 is an icicle that grows: after 50 simulations 4 root blocks, widths follow the visits, the root is labelled (M6, ${lang})`, async ({ page }) => {
    await open(page, 'inference', 'fig-mcts', TABLET, lang);
    const el = page.locator('#fig-mcts');
    await el.locator('.ml-fig-stepper button').first().focus();
    await page.keyboard.press('End');
    await el.locator('.ml-fig-seg input[type=radio]').last().check({ force: true });
    const info = await page.evaluate(() => {
      const svg = document.querySelector<SVGSVGElement>('#fig-mcts svg.ml-fig-ice')!;
      const cols = [...svg.querySelectorAll<SVGGElement>('g[data-column]')].map((g) => {
        const r = g.querySelector('rect')!;
        return { w: r.getBBox().width, state: g.dataset.state };
      });
      const root = svg.querySelector('.ml-fig-mi__label--root')!.textContent;
      const total = svg.viewBox.baseVal.width;
      const deepest = Math.max(...[...svg.querySelectorAll<SVGGElement>('g[data-depth]')].map((g) => Number(g.dataset.depth)));
      return { cols, root, total, deepest, active: svg.querySelectorAll('g[data-state="active"]').length };
    });
    expect(info.cols).toHaveLength(4);
    expect(info.root).toMatch(/50$/);
    // the widest root block carries 37 of the 50 visits: more than half of the row, not a 54th of it
    expect(Math.max(...info.cols.map((c) => c.w))).toBeGreaterThan(info.total * 0.5);
    expect(info.deepest).toBeGreaterThanOrEqual(6);
    expect(info.active).toBe(1); // the sub-step "back up" of simulation 50: the new leaf is the one current node
  });
}

test('Fig. 3.3 at 320: the root block and the "other moves" block are both inside the picture and the table has no wrapped next tag (M6, m9)', async ({ page }) => {
  await open(page, 'inference', 'fig-mcts', PHONE);
  const fits = await page.evaluate(() => {
    const svg = document.querySelector<SVGSVGElement>('#fig-mcts svg.ml-fig-ice')!;
    // geometry (getBBox, the fill box) in view box units: Firefox adds half the stroke to getBoundingClientRect of a shape (0.75 px of the
    // paper-coloured 1.5 px separator of the root block, which the svg does not clip: overflow is visible), Chromium does not
    const total = svg.viewBox.baseVal.width;
    return [...svg.querySelectorAll('rect')].every((r) => { const b = r.getBBox(); return b.x + b.width <= total + 0.01; });
  });
  expect(fits).toBe(true);
  const heights = await page.locator('#fig-mcts .ml-fig-mcts__table tbody tr').evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().height)));
  expect(new Set(heights).size).toBe(1);
  const headers = await page.locator('#fig-mcts .ml-fig-mcts__table thead th').evaluateAll((els) => els.map((e) => e.getBoundingClientRect().height));
  expect(Math.max(...headers)).toBeLessThan(40); // no header breaks inside a word
});

test('Fig. 3.4: the value labels have a paper halo and the values are one table, not a list with a chip under each row (M8)', async ({ page }) => {
  await open(page, 'inference', 'fig-backends', PHONE);
  const halo = await page.locator('#fig-backends svg text.ml-fig-plot__best').evaluate((e) => getComputedStyle(e).paintOrder);
  expect(halo).toContain('stroke');
  expect(await page.locator('#fig-backends table.ml-fig-backlist').count()).toBe(1);
  const height = await page.locator('#fig-backends table.ml-fig-backlist').evaluate((e) => e.getBoundingClientRect().height);
  expect(height).toBeLessThan(6 * 52); // six rows of one or two lines; the old list took 6 x 73 px
  expect(await page.locator('#fig-backends .ml-fig-chip').count()).toBeGreaterThan(0); // the chips of the foot stay
});

for (const lang of ['en', 'de'] as const) {
  test(`Fig. 5.3 at 320 is one table (not 14 cards) of about 500 px, and the page does not scroll sideways (M9, ${lang})`, async ({ page }) => {
    await open(page, 'tuning', 'fig-story-futility', PHONE, lang);
    const info = await page.evaluate(() => {
      const table = document.querySelector<HTMLTableElement>('#fig-story-futility table')!;
      const wrap = table.parentElement!;
      return {
        display: getComputedStyle(table).display,
        height: table.getBoundingClientRect().height,
        rows: table.querySelectorAll('tbody tr').length,
        overflow: wrap.scrollWidth - wrap.clientWidth,
        page: document.documentElement.scrollWidth - window.innerWidth,
        headerVisible: [...table.querySelectorAll('thead th')].every((th) => th.getBoundingClientRect().height > 8),
        dashes: [...table.querySelectorAll('.ml-fig-story__cell--empty')].length,
      };
    });
    expect(info.display).toBe('table');
    expect(info.rows).toBe(14);
    expect(info.height).toBeLessThan(700); // 14 rows of 32 px and a header; the cards were about 2,400 px
    expect(info.overflow).toBeLessThanOrEqual(1);
    expect(info.page).toBeLessThanOrEqual(0);
    expect(info.headerVisible).toBe(true);
    expect(info.dashes).toBe(10);
  });
}

for (const lang of ['en', 'de'] as const) {
  test(`Fig. 4.3 at 320: no arrow runs through the label of the evaluator (M10, ${lang})`, async ({ page }) => {
    await open(page, 'training', 'fig-pooled', PHONE, lang);
    const crossings = await page.evaluate(() => {
      const svg = document.querySelector<SVGSVGElement>('#fig-pooled svg.ml-fig-pooled__svg')!;
      const label = svg.querySelector<SVGTextElement>('.ml-fig-pooled__box text')!;
      const b = label.getBBox();
      const hits: string[] = [];
      for (const path of svg.querySelectorAll<SVGPathElement>('path.ml-fig-pooled__edge')) {
        const len = path.getTotalLength();
        for (let at = 0; at <= len; at += 1) {
          const p = path.getPointAtLength(at);
          if (p.x >= b.x - 2 && p.x <= b.x + b.width + 2 && p.y >= b.y - 2 && p.y <= b.y + b.height + 2) {
            hits.push(path.dataset.edge ?? '?');
            break;
          }
        }
      }
      return { hits, label: label.textContent, width: b.width };
    });
    expect(crossings.label?.length).toBeGreaterThan(3);
    expect(crossings.hits).toEqual([]);
  });
}

test('Fig. 4.2: narrow, the return runs as a line on the right edge ending in an arrowhead; wide, the six boxes form a ring of 3 x 2 (M13)', async ({ page }) => {
  await open(page, 'training', 'fig-loop', PHONE);
  const narrow = await page.evaluate(() => {
    const items = [...document.querySelectorAll<HTMLElement>('#fig-loop .ml-fig-cycle__item')];
    const lane = (i: number) => getComputedStyle(items[i]!, '::before');
    const head = getComputedStyle(items[0]!.querySelector('.ml-fig-cycle__stage')!, '::after');
    const last = items[5]!.getBoundingClientRect();
    const first = items[0]!.getBoundingClientRect();
    return {
      firstCorner: [lane(0).borderTopWidth, lane(0).borderRightWidth],
      lastCorner: [lane(5).borderBottomWidth, lane(5).borderRightWidth],
      middle: lane(2).borderRightWidth,
      head: [head.content, head.borderRightWidth],
      oneColumn: Math.abs(first.left - last.left) < 1,
    };
  });
  expect(narrow.firstCorner).toEqual(['2px', '2px']);
  expect(narrow.lastCorner).toEqual(['2px', '2px']);
  expect(narrow.middle).toBe('2px');
  expect(narrow.head[1]).toBe('8px');
  expect(narrow.oneColumn).toBe(true);

  await open(page, 'training', 'fig-loop', TABLET);
  const wide = await page.evaluate(() => {
    const r = (pos: number): DOMRect => document.querySelector(`#fig-loop .ml-fig-cycle__item[data-pos="${pos}"]`)!.getBoundingClientRect();
    const arrows = [1, 2, 3, 4, 5, 6].map((pos) => getComputedStyle(document.querySelector(`#fig-loop .ml-fig-cycle__item[data-pos="${pos}"]`)!, '::after').content);
    return { x: [1, 2, 3, 4, 5, 6].map((p) => Math.round(r(p).left)), y: [1, 2, 3, 4, 5, 6].map((p) => Math.round(r(p).top)), w: r(1).width, arrows };
  });
  expect(wide.x[3]).toBe(wide.x[2]); // 4 under 3
  expect(wide.x[5]).toBe(wide.x[0]); // 6 under 1: the ring closes
  expect(wide.y[5]).toBeGreaterThan(wide.y[0]! + 20);
  expect(wide.w).toBeLessThanOrEqual(12 * 16 + 1);
  for (const content of wide.arrows) expect(content).not.toBe('none');
});

test('Fig. 2.3 at 820: the contribution column is a table column under its header, wide enough for a bar (m7)', async ({ page }) => {
  await open(page, 'classical', 'fig-eval', TABLET);
  const info = await page.evaluate(() => {
    const cell = document.querySelector<HTMLElement>('#fig-eval td.ml-fig-feat__contrib')!;
    const head = document.querySelectorAll<HTMLElement>('#fig-eval thead th')[4]!;
    const bar = cell.querySelector('.ml-fig-bar')!.getBoundingClientRect();
    return { display: getComputedStyle(cell).display, head: Math.round(head.getBoundingClientRect().left), cell: Math.round(cell.getBoundingClientRect().left), bar: bar.width, board: document.querySelector('#fig-eval svg.ml-fig-board')!.getBoundingClientRect().width };
  });
  expect(info.display).toBe('table-cell');
  expect(info.head).toBe(info.cell);
  expect(info.bar).toBeGreaterThanOrEqual(100); // the column is at least 8 rem; the bar gets what the number leaves (it was 80 px)
  expect(info.board).toBeLessThanOrEqual(16 * 16 + 1);
});

test('Fig. 3.1: the formula starts its continuation rows under the equals sign, not centred (m11)', async ({ page }) => {
  await open(page, 'inference', 'fig-net', PHONE);
  const lefts = await page.evaluate(() => {
    const cells = [...document.querySelectorAll<HTMLElement>('#fig-net math mtd')].filter((c) => (c.textContent ?? '').trim() !== '' && c.previousElementSibling);
    return cells.map((c) => {
      const range = document.createRange();
      range.selectNodeContents(c);
      return Math.round(range.getBoundingClientRect().left);
    });
  });
  expect(lefts).toHaveLength(3);
  expect(new Set(lefts).size).toBe(1);
});

test('the heat legend stays in one row: low, five discs, high (m10, m15)', async ({ page }) => {
  for (const [topic, fig] of [['inference', 'fig-forward'], ['tuning', 'fig-story-futility']] as const) {
    await open(page, topic, fig, PHONE);
    const ramp = await page.locator(`#${fig} .ml-fig-heatlegend__ramp`).evaluate((e) => ({ h: e.getBoundingClientRect().height, n: e.querySelectorAll('circle').length }));
    expect(ramp.n).toBe(5);
    expect(ramp.h).toBeLessThan(24);
  }
});

test('Fig. 5.1 at 820: the whiskers get half of the row and the detail opens directly under the selected row (m16)', async ({ page }) => {
  await open(page, 'tuning', 'fig-story', TABLET);
  const info = await page.evaluate(() => {
    const row = document.querySelector<HTMLElement>('#fig-story .ml-fig-story__row[aria-pressed="true"]')!;
    const detail = document.querySelector<HTMLElement>('#fig-story .ml-fig__detail')!;
    const svg = document.querySelector<SVGSVGElement>('#fig-story .ml-fig-story__row svg.ml-fig-story__whisker')!;
    const list = document.querySelector('#fig-story .ml-fig-story__list')!.getBoundingClientRect();
    return { gap: detail.getBoundingClientRect().top - row.getBoundingClientRect().bottom, whisker: svg.getBoundingClientRect().width, list: list.width };
  });
  expect(info.gap).toBeLessThan(24);
  expect(info.gap).toBeGreaterThanOrEqual(0);
  expect(info.whisker).toBeGreaterThanOrEqual(info.list * 0.45); // rows are indented by up to 48 px, so 45 % of the list is more than half of the row
});

test('Fig. 5.2 at 820: the label of loop run 4 stands between the two lines and crosses neither (m12)', async ({ page }) => {
  await open(page, 'tuning', 'fig-story-elo', TABLET);
  const info = await page.evaluate(() => {
    const label = document.querySelector<SVGTextElement>('#fig-story-elo .ml-fig-story__reflabel')!;
    const b = label.getBBox();
    const xs = [...document.querySelectorAll<SVGLineElement>('#fig-story-elo [data-saturation] line')].map((l) => Number(l.getAttribute('x1'))).sort((a, c) => a - c);
    return { left: b.x, right: b.x + b.width, lines: xs };
  });
  expect(info.left).toBeGreaterThan(info.lines[0]!);
  expect(info.right).toBeLessThan(info.lines[1]!);
});

test('Fig. 5.5 at 320: the winner label does not touch the frame or another whisker (m13)', async ({ page }) => {
  await open(page, 'tuning', 'fig-story-sweep1', PHONE);
  const info = await page.evaluate(() => {
    const svg = document.querySelector<SVGSVGElement>('#fig-story-sweep1 svg.ml-fig-plot')!;
    const label = svg.querySelector<SVGTextElement>('.ml-fig-plot__best')!.getBBox();
    const frame = svg.querySelector<SVGRectElement>('.ml-fig-plot__frame')!.getBBox();
    const hits = [...svg.querySelectorAll<SVGPathElement>('.ml-fig-plot__whisker')].filter((p) => {
      const w = p.getBBox();
      return w.x < label.x + label.width && w.x + w.width > label.x && w.y < label.y + label.height && w.y + w.height > label.y;
    }).length;
    return { labelBottom: label.y + label.height, frameTop: frame.y, labelTop: label.y, labelRight: label.x + label.width, frameRight: frame.x + frame.width, hits };
  });
  expect(info.labelTop).toBeGreaterThanOrEqual(0);
  expect(info.hits).toBe(0);
  expect(info.labelRight).toBeLessThanOrEqual(info.frameRight - 2); // not over the right edge of the frame
  expect(info.labelBottom <= info.frameTop - 2 || info.labelTop >= info.frameTop + 2).toBe(true); // not on the top edge of the frame
});

test('Fig. 5.4 at 820: no two x tick labels touch (m14)', async ({ page }) => {
  await open(page, 'tuning', 'fig-story-sweeps', TABLET);
  const gaps = await page.evaluate(() => {
    const out: number[] = [];
    for (const svg of document.querySelectorAll<SVGSVGElement>('#fig-story-sweeps svg.ml-fig-plot')) {
      const frame = svg.querySelector<SVGRectElement>('.ml-fig-plot__frame')!.getBBox();
      const labels = [...svg.querySelectorAll<SVGTextElement>('text')]
        .filter((t) => !t.classList.contains('ml-fig-plot__axislabel') && Math.abs(Number(t.getAttribute('y')) - (frame.y + frame.height + 19)) < 1)
        .map((t) => t.getBBox())
        .sort((a, b) => a.x - b.x);
      for (let i = 1; i < labels.length; i++) out.push(labels[i]!.x - (labels[i - 1]!.x + labels[i - 1]!.width));
    }
    return out;
  });
  expect(gaps.length).toBeGreaterThan(3);
  for (const g of gaps) expect(g).toBeGreaterThanOrEqual(2);
});
