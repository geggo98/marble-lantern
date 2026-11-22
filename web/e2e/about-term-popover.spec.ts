// Regression: the popover of a glossary term near the right edge stays inside the viewport, and the term button stays on one line with
// the punctuation that follows it (German "inference" topic: "Größe SMALL, 32 Kanäle ...").
import { expect, test } from "@playwright/test";
import { openApp, watchPageErrors } from "./helpers";

for (const width of [1440, 1024, 390]) {
  test(`term popover of SMALL stays inside the viewport at ${width}px (de)`, async ({
    page,
  }) => {
    const errors = watchPageErrors(page);
    await page.setViewportSize({ width, height: 900 });
    await openApp(page, "de", "#/about/inference");
    await expect(page.locator("h1")).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
    const terms = page.locator(".ml-term__btn[data-term]");
    const count = await terms.count();
    expect(count).toBeGreaterThan(0);
    // every term of the page, so that one at the right edge is certainly among them
    for (let i = 0; i < count; i++) {
      const btn = terms.nth(i);
      await btn.scrollIntoViewIfNeeded();
      const term = (await btn.getAttribute("data-term"))!;
      await btn.click();
      const pop = page.locator(".ml-term__pop");
      await expect(pop).toBeVisible();
      const m = await page.evaluate(() => {
        const p = document
          .querySelector(".ml-term__pop")!
          .getBoundingClientRect();
        return {
          left: p.left,
          right: p.right,
          vw: document.documentElement.clientWidth,
        };
      });
      expect(
        m.left,
        `${term} left ${JSON.stringify(m)}`,
      ).toBeGreaterThanOrEqual(-0.5);
      expect(m.right, `${term} right ${JSON.stringify(m)}`).toBeLessThanOrEqual(
        m.vw + 0.5,
      );
      await page.keyboard.press("Escape");
    }
    errors.expectNone();
  });
}

for (const width of [1440, 1024, 390]) {
  test(`a glossary term stays on one line with the punctuation that follows it at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await openApp(page, "de", "#/about/inference");
    await expect(page.locator("h1")).toBeVisible();
    const bad = await page.evaluate(() => {
      const out: string[] = [];
      for (const btn of document.querySelectorAll(".ml-term__btn")) {
        const next = btn.parentElement!.nextSibling;
        if (
          !next ||
          next.nodeType !== Node.TEXT_NODE ||
          !/^[,.;:!?)]/.test(next.textContent ?? "")
        )
          continue;
        const r = document.createRange();
        r.setStart(next, 0);
        r.setEnd(next, 1);
        const punct = r.getBoundingClientRect();
        const tr = document.createRange();
        tr.selectNodeContents(btn); // the text only: the button box has padding that reaches into the next line
        const b = tr.getBoundingClientRect();
        if (punct.top > b.bottom - 2 || punct.bottom < b.top + 2)
          out.push(
            `${btn.textContent}: punct top ${punct.top} vs text ${b.top}-${b.bottom}`,
          );
      }
      return out;
    });
    expect(bad).toEqual([]);
  });
}
