// Owner feedback of 2026-11-21 (1): the text of a box of Fig. 1.2, 1.3 and 1.4 must lie inside its frame in every state, not only at rest. The
// bold, larger label of the highlighted box and the title of a region ran out of their frames. For every box label (the text nodes of the
// button, measured as ranges) the client rects lie inside the content box of the button (border and padding taken off); the title of each region
// lies inside its region. States: idle, keyboard focus on each box, every step of the stepper (active boxes), the open detail of each box.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp } from './helpers';

const SIZES = [
  { width: 320, height: 640 },
  { width: 820, height: 1180 },
  { width: 1280, height: 900 },
];
const FIGS = ['fig-offline', 'fig-online', 'fig-pipeline'];
const TOLERANCE = 0.75; // px; sub-pixel rounding of text rects
const TOLERANCE_Y = 1.5; // px; a text rect is the font's content area, up to about 1.5 px taller than the 1.15 line box

interface Overflow {
  what: string;
  box: string;
  detail: string;
}

/** Texts of boxes and region titles that leave their frame, in the page as it stands. */
async function overflows(page: Page, fig: string): Promise<Overflow[]> {
  return page.evaluate(
    ({ fig, tol, tolY }) => {
      const out: { what: string; box: string; detail: string }[] = [];
      const root = document.querySelector(`#${fig}`)!;
      const textRect = (el: Element): DOMRect[] => {
        // only the glyph boxes of the text nodes: a range over an element also returns the boxes of its child elements
        const out: DOMRect[] = [];
        const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
        for (let n = walker.nextNode(); n; n = walker.nextNode()) {
          if (!n.textContent?.trim()) continue;
          const r = document.createRange();
          r.selectNodeContents(n);
          out.push(...[...r.getClientRects()].filter((q) => q.width > 0 && q.height > 0));
        }
        return out;
      };
      const inside = (what: string, id: string, frame: DOMRect, rects: DOMRect[], inset: { l: number; t: number; r: number; b: number }): void => {
        const l = frame.left + inset.l - tol;
        const t = frame.top + inset.t - tolY;
        const r = frame.right - inset.r + tol;
        const b = frame.bottom - inset.b + tolY;
        for (const q of rects) {
          if (q.left < l || q.right > r || q.top < t || q.bottom > b) out.push({ what, box: id, detail: `text [${q.left.toFixed(1)},${q.top.toFixed(1)},${q.right.toFixed(1)},${q.bottom.toFixed(1)}] frame [${l.toFixed(1)},${t.toFixed(1)},${r.toFixed(1)},${b.toFixed(1)}]` });
        }
      };
      for (const node of root.querySelectorAll<HTMLElement>('[data-box]')) {
        const cs = getComputedStyle(node);
        const inset = {
          l: parseFloat(cs.borderLeftWidth) + parseFloat(cs.paddingLeft),
          t: parseFloat(cs.borderTopWidth) + parseFloat(cs.paddingTop),
          r: parseFloat(cs.borderRightWidth) + parseFloat(cs.paddingRight),
          b: parseFloat(cs.borderBottomWidth) + parseFloat(cs.paddingBottom),
        };
        inside('box', node.dataset.box ?? '?', node.getBoundingClientRect(), textRect(node), inset);
        if (node.scrollWidth > node.clientWidth + 1 || node.scrollHeight > node.clientHeight + 1) out.push({ what: 'scroll', box: node.dataset.box ?? '?', detail: `scroll ${node.scrollWidth}x${node.scrollHeight} client ${node.clientWidth}x${node.clientHeight}` });
      }
      for (const group of root.querySelectorAll<HTMLElement>('[data-group]')) {
        const label = group.querySelector('.ml-fig-box__grouplabel');
        if (!label) continue;
        const rects = textRect(label);
        inside('group', group.dataset.group ?? '?', group.getBoundingClientRect(), rects, { l: 1, t: 1, r: 1, b: 1 });
        // the title does not run into a box of its region, and no line of it is cut off
        const el = label as HTMLElement;
        if (el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1) out.push({ what: 'group-cut', box: group.dataset.group ?? '?', detail: `scroll ${el.scrollWidth}x${el.scrollHeight} client ${el.clientWidth}x${el.clientHeight}` });
        for (const node of root.querySelectorAll<HTMLElement>('[data-box]')) {
          const n = node.getBoundingClientRect();
          for (const q of rects) if (q.left < n.right && n.left < q.right && q.top < n.bottom && n.top < q.bottom - 0.5) out.push({ what: 'group-vs-box', box: `${group.dataset.group}/${node.dataset.box}`, detail: `title bottom ${q.bottom.toFixed(1)} box top ${n.top.toFixed(1)}` });
        }
      }
      return out;
    },
    { fig, tol: TOLERANCE, tolY: TOLERANCE_Y },
  );
}

for (const size of SIZES) {
  for (const lang of ['de', 'en'] as const) {
    for (const theme of ['light', 'dark'] as const) {
      for (const fig of FIGS) {
        test(`${fig} ${size.width} ${lang} ${theme}: box and region texts stay inside their frames in every state`, async ({ page }) => {
          await page.setViewportSize(size);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await page.addInitScript((t) => localStorage.setItem('ml.theme', t), theme);
          await openApp(page, lang, '#/about/architecture');
          const el = page.locator(`#${fig}`);
          await el.scrollIntoViewIfNeeded();
          await page.evaluate(() => document.fonts.ready);
          const bad: string[] = [];
          const check = async (state: string): Promise<void> => {
            for (const o of await overflows(page, fig)) bad.push(`${state}: ${o.what} ${o.box}: ${o.detail}`);
          };
          await check('idle');
          const ids = await el.locator('[data-box]').evaluateAll((nodes) => nodes.map((n) => (n as HTMLElement).dataset.box ?? ''));
          expect(ids.length).toBeGreaterThan(4);
          // keyboard focus: a Tab first, so that the focus ring of the keyboard is on
          await page.keyboard.press('Tab');
          for (const id of ids) {
            await el.locator(`[data-box="${id}"]`).focus();
            await check(`focus ${id}`);
          }
          // active boxes: every step of the stepper
          const forward = el.locator('.ml-fig-stepper button').last(); // reset, back, (play), forward
          for (let step = 1; step < 30 && (await forward.getAttribute('aria-disabled')) !== 'true'; step++) {
            await forward.click();
            await check(`step ${step}`);
          }
          // open detail of each box (the box keeps its state and gets the ring of aria-expanded)
          for (const id of ids) {
            await el.locator(`[data-box="${id}"]`).click();
            await check(`open ${id}`);
          }
          expect(bad, bad.slice(0, 12).join('\n')).toEqual([]);
        });
      }
    }
  }
}
