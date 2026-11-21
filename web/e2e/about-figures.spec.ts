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

// Requirement changed (v5-mid-spec.md, section 4): the icicle tree of Fig. 2.1 is replaced by the overview of all root moves. The checks that
// pinned the tree (glyphs of the tree, board beside the readout) now pin the overview, the close view and the board.
test('Fig. 2.1 at 820: the overview is the widest part, its type is at least 12 px, the board is not larger than 16 rem and stands beside the close view (M5)', async ({ page }) => {
  await open(page, 'classical', 'fig-ab', TABLET);
  const scale = await scaleOf(page, '#fig-ab svg.ml-fig-ab2__overview');
  const overview = await page.locator('#fig-ab svg.ml-fig-ab2__overview').boundingBox();
  const board = await page.locator('#fig-ab .ml-fig-ab2__board svg').first().boundingBox();
  const focus = await page.locator('#fig-ab .ml-fig-ab2__focus').boundingBox();
  expect(overview!.width).toBeGreaterThan(560);
  expect(12 * scale).toBeGreaterThanOrEqual(12);
  expect(board!.width).toBeLessThanOrEqual(16 * 16 + 1);
  expect(overview!.width).toBeGreaterThan(board!.width * 2);
  // wide: the board and the close view stand side by side below the overview; the three readout cells share one row
  expect(Math.abs(board!.y - focus!.y)).toBeLessThan(40);
  expect(board!.x).toBeLessThan(focus!.x);
  const cells = await page.locator('#fig-ab .ml-fig-ab2__read > div').evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().y)));
  expect(cells).toHaveLength(3);
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

// The stable layout of Fig. 2.1 (spec, section 10): while the user steps, the stage and what lies in it keep their size and place. The
// narration may change its lines only by what the kit rule gives; the height of the stage and the offsets inside the stage must not move.
for (const size of [PHONE, TABLET, { width: 1280, height: 900 }]) {
  for (const lang of ['en', 'de'] as const) {
    test(`Fig. 2.1 stage keeps its size and the places of its parts over all steps and both examples at ${size.width} (${lang})`, async ({ page }) => {
      await open(page, 'classical', 'fig-ab', size, lang);
      const fig = page.locator('#fig-ab');
      const scrub = fig.locator('input.ml-fig-ab2__scrub');
      for (const example of ['mid', 'start'] as const) {
        await fig.locator('.ml-fig-seg input[type=radio]').nth(example === 'mid' ? 0 : 1).check({ force: true });
        const last = Number(await scrub.getAttribute('max'));
        const seen = new Map<string, Set<number>>();
        for (let step = 0; step <= last; step++) {
          await scrub.evaluate((el, value) => {
            const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
            set.call(el, String(value));
            el.dispatchEvent(new Event('input', { bubbles: true }));
          }, step);
          const m = await fig.evaluate((el) => {
            const top = el.querySelector('.ml-fig__stage')!.getBoundingClientRect().top;
            const at = (sel: string): number => el.querySelector(sel)!.getBoundingClientRect().top - top;
            const h = (sel: string): number => el.querySelector(sel)!.getBoundingClientRect().height;
            return {
              stageH: h('.ml-fig__stage'),
              readT: at('.ml-fig-ab2__read'),
              overT: at('.ml-fig-ab2__overview'),
              overH: h('.ml-fig-ab2__overview'),
              scrubT: at('input.ml-fig-ab2__scrub'),
              lowerT: at('.ml-fig-ab2__lower'),
              focusH: h('.ml-fig-ab2__focus'),
              boardH: h('.ml-fig-ab2__board'),
            };
          });
          for (const [k, v] of Object.entries(m)) {
            if (!seen.has(k)) seen.set(k, new Set());
            seen.get(k)!.add(Math.round(v * 2) / 2); // 0.5 px tolerance
          }
        }
        for (const [k, values] of seen) expect([...values], `${example}: ${k}`).toHaveLength(1);
      }
    });
  }
}

test('Fig. 2.1: the link in the section "The game tree" selects the start position of the figure', async ({ page }) => {
  await open(page, 'classical', 'fig-ab', TABLET);
  await page.locator('[data-ab-link="start"] a').first().click();
  await expect(page.locator('#fig-ab .ml-fig-seg input[type=radio]').nth(1)).toBeChecked();
  await expect(page.locator('#fig-ab [data-counter]')).toContainText('0 of 210');
});

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

// ===== Fig. 1.1 (V4, source tree): what a row is and what a click does (design spec v4-affordance-spec.md) =====
const FOLDERS = ['tablebase', 'app', 'cpp', 'webEngines', 'scripts'];

async function openTree(page: Page, size: Size, lang: 'en' | 'de'): Promise<void> {
  await open(page, 'architecture', 'fig-tree', size, lang);
  for (const id of FOLDERS) await page.locator(`#fig-tree [data-entry="${id}"] .ml-fig-tree__chev`).click();
  await expect(page.locator('#fig-tree [data-entry]')).toHaveCount(34);
}

for (const lang of ['en', 'de'] as const) {
  for (const size of [PHONE, TABLET]) {
    test.describe(`Fig. 1.1 source tree, ${lang}, ${size.width}`, () => {
      test('every row has the pointer, the role and a 44 px target; the chevron zone is 44 by 44 px', async ({ page }) => {
        await openTree(page, size, lang);
        const rows = await page.locator('#fig-tree [data-entry]').evaluateAll((els) =>
          els.map((el) => {
            const box = el.getBoundingClientRect();
            const chev = el.querySelector<HTMLElement>('[data-toggle]')?.getBoundingClientRect();
            return { id: (el as HTMLElement).dataset.entry!, role: el.getAttribute('role'), selected: el.getAttribute('aria-selected'), cursor: getComputedStyle(el).cursor, h: box.height, chevW: chev?.width, chevH: chev?.height, folder: el.hasAttribute('aria-expanded') };
          }),
        );
        expect(rows).toHaveLength(34);
        for (const r of rows) {
          expect(r.role, r.id).toBe('treeitem');
          expect(r.selected, r.id).toBe('false');
          expect(r.cursor, r.id).toBe('pointer');
          expect(r.h, r.id).toBeGreaterThanOrEqual(43.5);
          if (r.folder) {
            expect(r.chevW, r.id).toBeGreaterThanOrEqual(43.5);
            expect(r.chevH, r.id).toBeGreaterThanOrEqual(43.5);
          }
        }
        expect(rows.filter((r) => r.folder).map((r) => r.id)).toEqual(FOLDERS);
      });

      test('the row types differ by icon, tag and text, not by colour alone; nothing is struck through', async ({ page }) => {
        await openTree(page, size, lang);
        const look = (id: string) =>
          page.locator(`#fig-tree [data-entry="${id}"]`).evaluate((el) => {
            const code = el.querySelector('code')!;
            const tags = [...el.querySelectorAll<HTMLElement>('.ml-fig-tree__tag')].map((t) => ({ border: getComputedStyle(t).borderTopStyle, icon: t.querySelector('svg') !== null, text: t.textContent!.trim() }));
            return {
              kind: el.getAttribute('data-kind'),
              icon: el.querySelector('.ml-fig-tree__kind svg')?.innerHTML ?? '',
              italic: getComputedStyle(code).fontStyle,
              decoration: getComputedStyle(code).textDecorationLine,
              tags,
              name: el.textContent!,
            };
          });
        const [module, plain, file, ignored, lfs] = await Promise.all(['engine', 'webApp', 'settings', 'data', 'tables'].map(look));
        // kind: three different icons for module, directory and file; the module has its own tag with a solid border
        expect(new Set([module!.icon, plain!.icon, file!.icon]).size).toBe(3);
        expect(module!.tags).toHaveLength(1);
        expect(module!.tags[0]!.border).toBe('solid');
        expect(plain!.tags).toHaveLength(0);
        // not in Git: italic path, dashed tag with an icon and a word, no line through the text
        expect(ignored!.italic).toBe('italic');
        expect(ignored!.tags).toHaveLength(1);
        expect(ignored!.tags[0]).toMatchObject({ border: 'dashed', icon: true });
        expect(ignored!.tags[0]!.text.length).toBeGreaterThan(3);
        expect(plain!.italic).toBe('normal');
        // Git LFS: solid tag with an icon and a word, upright path
        expect(lfs!.tags[0]).toMatchObject({ border: 'solid', icon: true });
        expect(lfs!.italic).toBe('normal');
        expect(new Set([ignored!.tags[0]!.text, lfs!.tags[0]!.text]).size).toBe(2);
        // the kind is also in the accessible name (hidden text): a module is told apart from a directory by text
        expect(module!.name).not.toBe(plain!.name);
        for (const l of [module, plain, file, ignored, lfs]) expect(l!.decoration).not.toContain('line-through');
        // the same under a filter, where rows are dimmed
        await page.locator('#fig-tree .ml-fig-seg label').nth(3).click();
        const decorations = await page.locator('#fig-tree [data-entry] *').evaluateAll((els) => els.map((e) => getComputedStyle(e).textDecorationLine));
        expect(decorations.length).toBeGreaterThan(100);
        expect(decorations.filter((d) => d.includes('line-through'))).toEqual([]);
      });

      test('a filter makes a row that does not match one muted line, and the count line says how many match', async ({ page }) => {
        await openTree(page, size, lang);
        const before = await page.locator('#fig-tree [data-entry="selfplay"]').boundingBox();
        await page.locator('#fig-tree .ml-fig-seg label').nth(1).click(); // Browser
        await expect(page.locator('#fig-tree .ml-fig-tree__count b')).toHaveText('19 / 34');
        const match = page.locator('#fig-tree [data-entry="webApp"]');
        const other = page.locator('#fig-tree [data-entry="selfplay"]');
        await expect(match).toHaveAttribute('data-match', 'true');
        await expect(other).toHaveAttribute('data-match', 'false');
        const oHeight = (await other.boundingBox())!.height;
        expect(oHeight).toBeLessThan(before!.height);
        expect(await match.locator('code').evaluate((e) => Number(getComputedStyle(e).fontWeight))).toBeGreaterThan(
          await other.locator('code').evaluate((e) => Number(getComputedStyle(e).fontWeight)),
        );
        await expect(other.locator('.ml-fig-tree__note')).toBeHidden();
        await expect(match.locator('.ml-fig-tree__note')).toBeVisible();
      });

      test('a click on any kind of row selects it and shows what it is; folders open; the link goes to GitHub; a local entry has no link', async ({ page }) => {
        await openTree(page, size, lang);
        const detail = page.locator('#fig-tree .ml-fig__detail');
        const cases: [string, string, string][] = [
          ['webApp', 'web/src/app/', 'tree'],
          ['settings', 'settings.gradle.kts', 'blob'],
          ['engine', 'engine/src/main/java/sternhalma/engine/', 'tree'],
          ['tables', 'tablebase/tables/', 'tree'],
          ['appEntry', 'app/src/main/frontend/views/@index.tsx', 'blob'],
        ];
        for (const [id, path, type] of cases) {
          await page.locator(`#fig-tree [data-entry="${id}"]`).click();
          await expect(page.locator(`#fig-tree [data-entry="${id}"]`)).toHaveAttribute('aria-selected', 'true');
          await expect(page.locator('#fig-tree [aria-selected="true"]')).toHaveCount(1);
          await expect(detail.locator('h4')).toHaveText(path);
          const link = detail.locator('a.ml-fig-tree__gh');
          const href = await link.getAttribute('href');
          expect(href!.endsWith(`/${type}/main/${path.replace(/\/$/, '')}`), id).toBe(true);
          await expect(link).toHaveAttribute('target', '_blank');
          expect((await link.boundingBox())!.height).toBeGreaterThanOrEqual(43.5);
        }
        await page.locator('#fig-tree [data-entry="data"]').click();
        await expect(detail.locator('h4')).toHaveText('data/');
        await expect(detail.locator('a')).toHaveCount(0);
        await expect(detail.locator('.ml-fig-tree__local')).toBeVisible();
        // a second click on the selected row keeps the selection; a click on a folder row does not close the folder
        await page.locator('#fig-tree [data-entry="data"]').click();
        await expect(page.locator('#fig-tree [data-entry="data"]')).toHaveAttribute('aria-selected', 'true');
        await page.locator('#fig-tree [data-entry="cpp"]').click();
        await expect(page.locator('#fig-tree [data-entry="cpp"]')).toHaveAttribute('aria-expanded', 'true');
        // the chevron closes without selecting; the close button clears the selection and gives the focus back to the row
        await page.locator('#fig-tree [data-entry="cpp"] .ml-fig-tree__chev').click();
        await expect(page.locator('#fig-tree [data-entry="cpp"]')).toHaveAttribute('aria-expanded', 'false');
        await expect(page.locator('#fig-tree [data-entry="cpp"]')).toHaveAttribute('aria-selected', 'true'); // the selection is on cpp (the click above)
        await detail.locator('.ml-fig__detail-close').click();
        await expect(detail).toHaveCount(0);
        await expect(page.locator('#fig-tree [data-entry="cpp"]')).toBeFocused();
      });

      test('keyboard: one tab stop, Enter selects, Escape clears and keeps the focus; the page does not overflow horizontally', async ({ page }) => {
        await open(page, 'architecture', 'fig-tree', size, lang);
        await expect(page.locator('#fig-tree [data-entry][tabindex="0"]')).toHaveCount(1);
        await page.locator('#fig-tree [data-entry="settings"]').focus();
        await page.keyboard.press('ArrowDown');
        await expect(page.locator('#fig-tree [data-entry="engine"]')).toBeFocused();
        await page.keyboard.press('Enter');
        await expect(page.locator('#fig-tree [data-entry="engine"]')).toHaveAttribute('aria-selected', 'true');
        // engine and the six modules that depend on it are marked in the graph (the compact layout has no graph)
        await expect(page.locator('#fig-tree [data-module][data-state="active"]')).toHaveCount(size.width >= 640 ? 7 : 0);
        await page.keyboard.press('Escape');
        await expect(page.locator('#fig-tree [aria-selected="true"]')).toHaveCount(0);
        await expect(page.locator('#fig-tree [data-entry="engine"]')).toBeFocused();
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(overflow).toBeLessThanOrEqual(0);
      });
    });
  }
}

test('Fig. 1.1 at 320: an open detail panel sticks to the lower edge of the viewport while the tree is on screen', async ({ page }) => {
  await openTree(page, PHONE, 'en');
  await page.locator('#fig-tree [data-entry="webApp"]').click();
  const detail = page.locator('#fig-tree .ml-fig__detail');
  await expect(detail).toBeVisible();
  expect(await detail.evaluate((el) => getComputedStyle(el).position)).toBe('sticky');
  await page.locator('#fig-tree [data-entry="settings"]').scrollIntoViewIfNeeded();
  const box = (await detail.boundingBox())!;
  expect(box.y + box.height).toBeLessThanOrEqual(page.viewportSize()!.height + 1);
  expect(box.y).toBeGreaterThanOrEqual(0);
});

test('Fig. 1.1 at 820 and 1280: the labels of the module graph are at least 12 px', async ({ page }) => {
  for (const size of [TABLET, { width: 1280, height: 900 }]) {
    await open(page, 'architecture', 'fig-tree', size);
    expect(await scaleOf(page, '#fig-tree svg.ml-fig-dep')).toBeGreaterThanOrEqual(0.99); // 12 units per label, one unit per pixel
  }
});
