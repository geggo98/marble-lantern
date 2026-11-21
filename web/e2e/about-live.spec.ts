// Live positions in the Info figures (plan: live-plan.md). One file with four parts; each work package fills only its own describe block.
//   Part 1 (WP0, this file): no extra worker and no download for the net engine on the play screen, on an Info page, and after every
//          "Explain this position" link. The owner's rule: a second engine worker starts only when the reader presses a live switch.
//   Part 2 (WP1): V7 live and the root-scores board.   Part 3 (WP3): V6 live.   Part 4 (WP6): V10 and V9b live.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, playOneExchange, watchPageErrors } from './helpers';

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
  test.fixme('two-move game: the feature table differs from the example and the score readout equals the engine\'s; the example history shows 179', async () => {});
});

test.describe('part 4 (WP6): V10 and V9b live', () => {
  test.fixme('example history: V10 finishes with visits that add up to 50 and the choice of mcts-mid.json; V9b shows the planes of forward-mid.json', async () => {});
  test.fixme('Stop during the V10 run ends it within one simulation step, the status says "Stopped", the next press starts again', async () => {});
});
