// Live positions in the Info figures (plan: live-plan.md). One file with four parts; each work package fills only its own describe block.
//   Part 1 (WP0, this file): no extra worker and no download for the net engine on the play screen, on an Info page, and after every
//          "Explain this position" link. The owner's rule: a second engine worker starts only when the reader presses a live switch.
//   Part 2 (WP1): V7 live and the root-scores board.   Part 3 (WP3): V6 live.   Part 4 (WP6): V10 and V9b live.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { EVAL_MID_HASH } from './eval-mid-link';
import { openApp, playOneExchange, plyOf, waitForHumanTurn, watchPageErrors } from './helpers';

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
  test.fixme('example history: Explain, "How deep does the engine see?", My game (ply 30) shows the five depth markers 3, 4, 6, 8, 10 of ab-budget-mid.json', async () => {});
  test.fixme('a fresh game with two moves: five markers, and the data chip says "Your game, ply 4"', async () => {});
});

test.describe('part 3 (WP3): V6 live', () => {
  const FIG = '#fig-eval';
  const score = (page: Page) => page.locator(`${FIG} .ml-fig-readout__cell`).filter({ has: page.locator('dt', { hasText: /^Score$/ }) }).locator('dd');
  const values = (page: Page) => page.locator(`${FIG} .ml-fig-feat tbody tr`).locator('td[data-label="Value"]').allTextContents();

  async function explainEvaluation(page: Page): Promise<void> {
    await expect(page.locator('[data-explain-card]')).not.toHaveAttribute('data-disabled', '');
    await page.locator('a[data-explain-link="evaluation"]').click();
    await expect(page.locator(FIG)).toBeVisible();
  }

  // Both tests need the features export of the engines (WP2). Until WP2 is merged and the engines are rebuilt, the engine answers
  // "unsupported" and the figure shows its error line. Remove the fixme after the merge.
  test.fixme('needs WP2 engines: a game of four plies: the feature table differs from the example and the chip says "Your game, ply 4"', async ({ page }) => {
    const errors = watchPageErrors(page);
    await openApp(page, 'en', '', 'human');
    await playOneExchange(page);
    await playOneExchange(page);
    expect(await plyOf(page)).toBe(4);
    await explainEvaluation(page);
    const example = await values(page);
    expect(await score(page).textContent()).toBe('+179');
    await page.locator(`${FIG} [data-live-switch] input[value="mine"]`).check();
    await expect(page.locator(`${FIG} [data-origin]`)).toHaveAttribute('data-origin', 'mine', { timeout: 30_000 });
    await expect(page.locator(`${FIG} [data-origin]`)).toContainText('Your game, ply 4');
    await expect(page.locator(`${FIG} .ml-fig__status`)).toHaveText('Computed in your browser.');
    expect(await values(page)).not.toEqual(example);
    expect(await score(page).textContent()).not.toBe('+179');
    // Example brings the committed numbers back
    await page.locator(`${FIG} [data-live-switch] input[value="example"]`).check();
    expect(await score(page).textContent()).toBe('+179');
    errors.expectNone();
  });

  test.fixme('needs WP2 engines: the example history (30 plies) shows the 13 values and the score +179 of eval-mid.json', async ({ page }) => {
    const errors = watchPageErrors(page);
    await openApp(page, 'en', EVAL_MID_HASH);
    await page.locator('[data-choice="link-side-to-move"]').click();
    await waitForHumanTurn(page);
    await explainEvaluation(page);
    const example = await values(page);
    await page.locator(`${FIG} [data-live-switch] input[value="mine"]`).check();
    await expect(page.locator(`${FIG} [data-origin]`)).toContainText('Your game, ply 30', { timeout: 30_000 });
    expect(await score(page).textContent()).toBe('+179');
    expect(await values(page)).toEqual(example);
    errors.expectNone();
  });
});

test.describe('part 4 (WP6): V10 and V9b live', () => {
  test.fixme('example history: V10 finishes with visits that add up to 50 and the choice of mcts-mid.json; V9b shows the planes of forward-mid.json', async () => {});
  test.fixme('Stop during the V10 run ends it within one simulation step, the status says "Stopped", the next press starts again', async () => {});
});
