// Live positions in the Info figures (plan: live-plan.md). One file with four parts; each work package fills only its own describe block.
//   Part 1 (WP0, this file): no extra worker and no download for the net engine on the play screen, on an Info page, and after every
//          "Explain this position" link. The owner's rule: a second engine worker starts only when the reader presses a live switch.
//   Part 2 (WP1): V7 live and the root-scores board.   Part 3 (WP3): V6 live.   Part 4 (WP6): V10 and V9b live.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { openApp, plyOf, playOneExchange, waitForHumanTurn, watchPageErrors } from './helpers';

const SECTIONS = [
  ['budget', 'classical'],
  ['alpha-beta', 'classical'],
  ['evaluation', 'classical'],
  ['mcts', 'inference'],
  ['forward-pass', 'inference'],
] as const;

/**
 * What the page has started and requested so far. The game's own engine worker is allowed; a second live worker is not. The game may
 * replace its worker (an aborted search restarts it, and the game does that when the play screen comes back), so the check counts the
 * workers that are alive, not the ones that were ever created; a worker of another script (the net engine) would also show in `created`.
 */
function watchStarts(page: Page): { created: () => string[]; urls: () => string[] } {
  const created: string[] = [];
  const urls: string[] = [];
  page.on('worker', (w) => created.push(w.url()));
  page.on('request', (r) => urls.push(r.url()));
  return { created: () => [...created], urls: () => [...urls] };
}

const netRequests = (urls: string[]): string[] => urls.filter((u) => /\/engines\/net\/|\/models\//.test(new URL(u).pathname));

test.describe('part 1: nothing starts before the reader presses a live switch', () => {
  test('play screen, an Info page and every Explain link start no worker beyond the game\'s and request nothing of the net engine', async ({ page }) => {
    const errors = watchPageErrors(page);
    const seen = watchStarts(page);
    await openApp(page, 'en', '', 'human');
    await playOneExchange(page); // the game's engine is loaded and has answered
    const gameScripts = new Set(seen.created());
    const alive = page.workers().length;
    expect(alive, 'the game starts its engine worker').toBeGreaterThanOrEqual(1);
    const nothingElse = async (when: string): Promise<void> => {
      await expect.poll(() => page.workers().length, `workers alive ${when}`).toBe(alive);
      expect(new Set(seen.created()), `worker scripts ${when}`).toEqual(gameScripts);
      expect(netRequests(seen.urls()), `requests ${when}`).toEqual([]);
    };
    await nothingElse('on the play screen');

    // an Info page, opened from the navigation
    await page.locator('.ml-nav__link[href="#/about"]').click();
    await page.locator('.ml-learn__card a[href="#/about/classical"]').click();
    await expect(page.locator('h1')).toHaveText('The classical algorithm');
    await nothingElse('on an Info page');

    // every link of the card: the snapshot is stored, the page navigates, and still nothing starts
    for (const [section, topic] of SECTIONS) {
      await page.locator('.ml-nav__link').first().click(); // Play
      await expect(page.locator('[data-explain-card]')).toBeVisible();
      await expect(page.locator('[data-explain-card]')).not.toHaveAttribute('data-disabled', '');
      const link = page.locator(`a[data-explain-link="${section}"]`);
      await expect(link).toHaveAttribute('href', `#/about/${topic}/${section}`);
      await link.click();
      await expect(page).toHaveURL(new RegExp(`#/about/${topic}/${section}$`));
      await expect(page.locator(`#about-${topic}-${section}`)).toBeFocused();
      await nothingElse(`after the link to ${topic}/${section}`);
    }
    errors.expectNone();
  });
});

// The parts below belong to the work packages named in the titles. They are fixme until the figures exist; the owner of a part replaces
// the fixme with the test of the plan's section 4 and removes the marker.
test.describe('part 2 (WP1): V7 live and the root-scores board', () => {
  /** The game link of the 30 plies of eval-mid.json; keep equal to EXAMPLE_MID_PAYLOAD in src/about/live/mid-link.test.ts. The person (side 0) is to move. */
  const EXAMPLE_MID = '#g=v1.BkOeYgdzWQCnxNEEZsICz6uCL3hnOxu9pTRzzmdzpQo9bMSNbnOwg-IyCknC8McXpQ5tKaA&h=0&l=2';
  const budget = JSON.parse(readFileSync('src/about/data/ab-budget-mid.json', 'utf8')) as {
    meta: { levels: number[] };
    browser: { nodes: number; depth: number; move: { text: string } }[];
  };
  const roots = JSON.parse(readFileSync('src/about/data/root-scores-mid.json', 'utf8')) as { moves: { text: string; score: number }[] };

  /** The plotted points of the browser-engine series (the legend has a sample mark of the same class). */
  const markers = (page: Page) =>
    page.locator('#fig-budget .ml-fig-plot__pt--1').evaluateAll((els) => els.filter((e) => !e.closest('.ml-fig-legend')).length);

  const pressMyGame = async (page: Page, figure: string): Promise<void> => {
    await page.locator(`#${figure} [data-live-switch] label.ml-fig-seg__opt`).filter({ hasText: 'My game' }).click();
  };

  test('example history: Explain, "How deep does the engine see?", My game (ply 30) shows the five depth markers 3, 4, 6, 8, 10 of ab-budget-mid.json', async ({ page }) => {
    const errors = watchPageErrors(page);
    await openApp(page, 'en', EXAMPLE_MID);
    await page.locator('[data-choice="link-side-to-move"]').click(); // "Continue this game?"
    await waitForHumanTurn(page);
    await page.locator('a[data-explain-link="budget"]').click();
    await expect(page).toHaveURL(/#\/about\/classical\/budget$/);
    const chip = page.locator('#fig-budget [data-origin]');
    await expect(chip).toHaveText('Example position, precomputed, ply 30');
    await pressMyGame(page, 'fig-budget');
    await expect(chip).toHaveText('Your game, ply 30, computed in your browser');
    await expect.poll(() => markers(page), { message: 'five depth markers' }).toBe(5);
    await expect(page.locator('#fig-budget [role=status]')).toHaveText('Computed in your browser.');
    // the depths and moves of every level, read from the readout with the slider
    const slider = page.locator('#fig-budget input[type=range]');
    for (const [i, row] of budget.browser.entries()) {
      await slider.fill(String(i + 1));
      const readout = page.locator('#fig-budget .ml-fig-budget__readout');
      await expect(readout, `level ${i + 1} (${row.nodes} nodes)`).toContainText(`${row.nodes.toLocaleString('en-US')} nodes: depth ${row.depth}, move ${row.move.text}`);
    }
    expect(budget.browser.map((r) => r.depth)).toEqual([3, 4, 6, 8, 10]);
    errors.expectNone();
  });

  test('a fresh game with two exchanges: five markers, and the data chip says "Your game, ply 4"', async ({ page }) => {
    const errors = watchPageErrors(page);
    await openApp(page, 'en', '', 'human');
    await playOneExchange(page);
    await playOneExchange(page);
    await expect.poll(() => plyOf(page)).toBe(4);
    await page.locator('a[data-explain-link="budget"]').click();
    await expect(page).toHaveURL(/#\/about\/classical\/budget$/);
    await pressMyGame(page, 'fig-budget');
    await expect(page.locator('#fig-budget [data-origin]')).toHaveText('Your game, ply 4, computed in your browser');
    await expect.poll(() => markers(page), { message: 'five depth markers' }).toBe(5);
    errors.expectNone();
  });

  test('the root-scores board (Fig. 2.1b): with the example history, My game gives the scores of root-scores-mid.json', async ({ page }) => {
    const errors = watchPageErrors(page);
    await openApp(page, 'en', EXAMPLE_MID);
    await page.locator('[data-choice="link-side-to-move"]').click(); // "Continue this game?"
    await waitForHumanTurn(page);
    await page.locator('a[data-explain-link="alpha-beta"]').click();
    await expect(page).toHaveURL(/#\/about\/classical\/alpha-beta$/);
    await expect(page.locator('#fig-roots .ml-fig__label')).toHaveText('Fig. 2.1b');
    const rows = page.locator('#fig-roots tbody tr');
    await expect(rows).toHaveCount(roots.moves.length); // the example
    await pressMyGame(page, 'fig-roots');
    await expect(page.locator('#fig-roots [data-origin]')).toHaveText('Your game, ply 30, computed in your browser');
    await expect(page.locator('#fig-roots [data-roots]')).toHaveAttribute('data-roots', 'ready');
    await expect(rows).toHaveCount(roots.moves.length);
    for (const [i, m] of roots.moves.slice(0, 5).entries()) {
      await expect(rows.nth(i).locator('th')).toHaveText(m.text);
      await expect(rows.nth(i).locator('td').first()).toHaveText(m.score.toLocaleString('en-US'));
    }
    expect(await page.locator('#fig-roots .ml-fig-board__arrow').count()).toBe(3);
    errors.expectNone();
  });
});

test.describe('part 3 (WP3): V6 live', () => {
  test.fixme('two-move game: the feature table differs from the example and the score readout equals the engine\'s; the example history shows 179', async () => {});
});

test.describe('part 4 (WP6): V10 and V9b live', () => {
  test.fixme('example history: V10 finishes with visits that add up to 50 and the choice of mcts-mid.json; V9b shows the planes of forward-mid.json', async () => {});
  test.fixme('Stop during the V10 run ends it within one simulation step, the status says "Stopped", the next press starts again', async () => {});
});
