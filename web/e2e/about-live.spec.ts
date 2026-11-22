// Live positions in the Info figures (plan: live-plan.md). One file with four parts; each work package fills only its own describe block.
//   Part 1 (WP0, this file): no extra worker and no download for the net engine on the play screen, on an Info page, and after every
//          "Explain this position" link. The owner's rule: a second engine worker starts only when the reader presses a live switch.
//   Part 2 (WP1): V7 live and the root-scores board.   Part 3 (WP3): V6 live.   Part 4 (WP6): V10 and V9b live.   Part 5: a 5-row game.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
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
  const FIG = '#fig-eval';
  const score = (page: Page) => page.locator(`${FIG} .ml-fig-readout__cell`).filter({ has: page.locator('dt', { hasText: /^Score$/ }) }).locator('dd');
  const values = (page: Page) => page.locator(`${FIG} .ml-fig-feat tbody tr`).locator('td[data-label="Value"]').allTextContents();

  async function explainEvaluation(page: Page): Promise<void> {
    await expect(page.locator('[data-explain-card]')).not.toHaveAttribute('data-disabled', '');
    await page.locator('a[data-explain-link="evaluation"]').click();
    await expect(page.locator(FIG)).toBeVisible();
  }

  test('a game of four plies: the feature table differs from the example and the chip says "Your game, ply 4"', async ({ page }) => {
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

  test('the example history (30 plies) shows the 13 values and the score +179 of eval-mid.json', async ({ page }) => {
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
  /** The game link of the 30 plies of eval-mid.json; keep equal to EXAMPLE_MID_PAYLOAD in src/about/live/mid-link.test.ts. */
  const EXAMPLE_MID = '#g=v1.BkOeYgdzWQCnxNEEZsICz6uCL3hnOxu9pTRzzmdzpQo9bMSNbnOwg-IyCknC8McXpQ5tKaA&h=0&l=2';
  const FIG = '#fig-mcts';
  const status = (page: Page) => page.locator(`${FIG} [role=status]`);
  const pos = (page: Page) => page.locator(`${FIG} .ml-fig-stepper__pos`);
  const mine = (page: Page, fig: string) => page.locator(`${fig} [data-live-switch] label.ml-fig-seg__opt`).filter({ hasText: 'My game' });

  async function explain(page: Page, section: 'mcts' | 'forward-pass'): Promise<void> {
    await openApp(page, 'en', EXAMPLE_MID);
    await page.locator('[data-choice="link-side-to-move"]').click(); // "Continue this game?"
    await waitForHumanTurn(page);
    await page.locator(`a[data-explain-link="${section}"]`).click();
    await expect(page).toHaveURL(new RegExp(`#/about/inference/${section}$`));
  }

  /** The visits of every root move in the table (all rows, not only the first eight). */
  async function visits(page: Page): Promise<number[]> {
    const more = page.locator(`${FIG} .ml-fig-mcts__table ~ button[aria-expanded]`);
    if ((await more.count()) > 0 && (await more.getAttribute('aria-expanded')) === 'false') await more.click();
    return (await page.locator(`${FIG} .ml-fig-mcts__table tbody tr td:nth-child(3)`).allTextContents()).map(Number);
  }

  test('example history: V10 My game finishes with visits that add up to 50 and the choice of mcts-mid.json; the time is measured', async ({ page }, info) => {
    const errors = watchPageErrors(page);
    await explain(page, 'mcts');
    // the example's own final line: the last step in the back-up phase
    await page.locator(`${FIG} .ml-fig-stepper button`).first().focus();
    await page.keyboard.press('End');
    await page.locator(`${FIG} label.ml-fig-seg__opt`).filter({ hasText: 'Back up' }).click();
    const exampleFinal = (await page.locator(`${FIG} .ml-fig-final`).textContent()) ?? '';
    expect(exampleFinal).toContain('of 50 visits');
    await expect(page.locator(`${FIG} [data-origin]`)).toHaveText('Example position, precomputed, ply 30');
    const t0 = Date.now();
    await mine(page, FIG).click();
    await expect(status(page)).toContainText('Computed in your browser.', { timeout: 90_000 });
    const ms = Date.now() - t0;
    info.annotations.push({ type: 'v10-ms', description: String(ms) });
    process.stderr.write(`V10 live, ${info.project.name}: ${ms} ms from the press to the end (engine load, model, one forward pass, 50 searches of 1 to 50 simulations)\n`);
    await expect(pos(page)).toContainText('50 / 50');
    await expect(page.locator(`${FIG} [data-origin]`)).toHaveText('Your game, ply 30, computed in your browser');
    await expect(page.locator(`${FIG} [data-origin-trace]`)).toHaveText('Example position (precomputed)');
    const v = await visits(page);
    expect(v).toHaveLength(54);
    expect(v.reduce((a, b) => a + b, 0)).toBe(50);
    await expect(page.locator(`${FIG} .ml-fig-final`)).toHaveText(exampleFinal);
    errors.expectNone();
  });

  test('Stop during the V10 run ends it within one simulation step, the status says "Stopped", nothing arrives afterwards, the next press runs to the end', async ({ page }) => {
    const errors = watchPageErrors(page);
    await explain(page, 'mcts');
    await expect(page.locator(FIG)).toBeVisible();
    // Stop is clicked from inside the page the moment the status reports three finished steps: a run is faster than a poll
    await page.evaluate(() => {
      const fig = document.querySelector('#fig-mcts')!; // the status line is re-created with the figure, so the whole figure is watched
      (window as unknown as { __stopWatch: MutationObserver }).__stopWatch = new MutationObserver(() => {
        const m = /Computing in your browser: (\d+) of 50 steps done/.exec(fig.querySelector('[role=status]')?.textContent ?? '');
        if (m && Number(m[1]) >= 3) (document.querySelector('#fig-mcts [data-live-stop]') as HTMLButtonElement | null)?.click();
      });
      (window as unknown as { __stopWatch: MutationObserver }).__stopWatch.observe(fig, { childList: true, subtree: true, characterData: true });
    });
    await mine(page, FIG).click();
    await expect(status(page)).toContainText('Stopped.', { timeout: 90_000 });
    await page.evaluate(() => (window as unknown as { __stopWatch: MutationObserver }).__stopWatch.disconnect());
    await expect(page.locator(`${FIG} [data-live-switch] input[value="example"]`)).toBeChecked();
    await expect(page.locator(`${FIG} [data-origin]`)).toHaveText('Example position, precomputed, ply 30');
    const before = await pos(page).textContent();
    await page.waitForTimeout(1500);
    expect(await pos(page).textContent(), 'no step arrives after Stop').toBe(before);
    await expect(status(page)).toContainText('Stopped.');
    // the worker was restarted by the abort: the next press computes again, to the end
    await mine(page, FIG).click();
    await expect(status(page)).toContainText('Computed in your browser.', { timeout: 90_000 });
    await expect(pos(page)).toContainText('50 / 50');
    expect((await visits(page)).reduce((a, b) => a + b, 0)).toBe(50);
    errors.expectNone();
  });

  test('example history: V9b has a fourth position "My game (ply 30)" and shows the planes and the layers of forward-mid.json', async ({ page }) => {
    const errors = watchPageErrors(page);
    await explain(page, 'forward-pass');
    const F = '#fig-forward';
    const position = page.locator(`${F} select.ml-fig-select`).first();
    const planeSelect = page.locator(`${F} select.ml-fig-select`).nth(1);
    await expect(position.locator('option')).toHaveText(['Start position', 'Mid-game, after 30 plies', 'Race, after 106 plies', 'My game (ply 30)']);
    await expect(position).toHaveValue('mid');
    /** The discs of the board with level and place: the whole content of the heat board. */
    const board = () => page.locator(`${F} .ml-fig-forward__board svg [data-level]`).evaluateAll((els) => els.map((e) => `${e.getAttribute('data-level')}@${e.getAttribute('cx')},${e.getAttribute('cy')}`));
    const planes = await planeSelect.locator('option').count();
    expect(planes).toBe(13);
    /** Goes to step n of the stepper (1 = input, 2 to 6 = the trunk layers). */
    const stepTo = async (n: number): Promise<void> => {
      await page.locator(`${F} .ml-fig-stepper button`).first().focus();
      await page.keyboard.press('Home');
      for (let i = 1; i < n; i++) await page.keyboard.press('ArrowRight');
      await expect(page.locator(`${F} .ml-fig-stepper__pos`)).toContainText(`${n} / 8`);
    };
    const collect = async (): Promise<string[][]> => {
      const out: string[][] = [];
      await stepTo(1);
      for (let i = 0; i < planes; i++) {
        await planeSelect.selectOption(String(i));
        out.push(await board());
      }
      for (let step = 2; step <= 6; step++) {
        await stepTo(step);
        out.push(await board());
      }
      return out;
    };
    const example = await collect();
    expect(example.flat().length, 'the example boards are not empty').toBeGreaterThan(100);

    await position.selectOption('mine');
    await expect(page.locator(`${F} [role=status]`)).toContainText('Computed in your browser.', { timeout: 90_000 });
    await expect(page.locator(`${F} [data-origin]`)).toHaveText('Your game, ply 30, computed in your browser');
    await expect(position).toHaveValue('mine');
    const live = await collect();
    const names = ['own', 'next', 'third', 'goal', 'start', 'playable', 'progress', 'distOwn', 'distNext', 'reachOwn', 'reachNext', 'passed', 'eval', 'conv_in', 'block0', 'block1', 'block2', 'block3'];
    live.forEach((b, i) => expect(b, names[i]).toEqual(example[i]));
    errors.expectNone();
  });

  test('a fresh game of four plies: V9b with My game (ply 4) computes and the chip says "Your game, ply 4"', async ({ page }) => {
    const errors = watchPageErrors(page);
    await openApp(page, 'en', '', 'human');
    await playOneExchange(page);
    await playOneExchange(page);
    expect(await plyOf(page)).toBe(4);
    await page.locator('a[data-explain-link="forward-pass"]').click();
    const F = '#fig-forward';
    const position = page.locator(`${F} select.ml-fig-select`).first();
    await position.selectOption('mine');
    await expect(page.locator(`${F} [role=status]`)).toContainText('Computed in your browser.', { timeout: 90_000 });
    await expect(page.locator(`${F} [data-origin]`)).toHaveText('Your game, ply 4, computed in your browser');
    expect(await page.locator(`${F} .ml-fig-policy li`).count()).toBe(5);
    errors.expectNone();
  });
});

// Part 5: a game on 5 rows (the default size of a new game). The classical figures explain it; their numbers are checked against the C++
// engine run in Node.js on the same history. The net figures run the 5-row expert small-2p-adjacent-r5-expert (liveModelId in
// src/about/live/mcts.ts); the 4-row expert is never requested.
test.describe('part 5: a 5-row game', () => {
  const cppJs = fileURLToPath(new URL('../public/engines/cpp/engine-cpp.js', import.meta.url));
  /** The C ABI of the C++ engine that this test calls (cpp/README.md, "Search and the wasm API on 5-row boards"). */
  interface Cpp {
    _malloc(bytes: number): number;
    _shw_game_new5(jump: number, players: number, ranking: number, camps: number, rows: number): number;
    _shw_play(move: number): number;
    _shw_features(values: number, scoreSide: number, distance: number): number;
    _shw_legal_moves(out: number, cap: number): number;
    HEAPU8: Uint8Array;
    HEAP32: Int32Array;
  }
  /**
   * The C++ engine in Node.js on the 5-row classic game after `history`: an independent run of the export the page calls (the Java twin
   * gives the same report, features.cross.test.ts). The raw C ABI keeps the web sources out of this test process.
   */
  async function nodeEngine(history: readonly number[]): Promise<{ features: number[]; score: number; legal: number }> {
    const m = await ((await import(/* @vite-ignore */ pathToFileURL(cppJs).href)) as { default: () => Promise<Cpp> }).default();
    if (m._shw_game_new5(0, 2, 0, 0, 5) !== 0) throw new Error('shw_game_new5');
    for (const move of history) if (m._shw_play(move) !== 0) throw new Error(`illegal move ${move}`);
    const base = m._malloc(13 * 8 + 2 * 4 + 242 * 4);
    if (m._shw_features(base, base + 13 * 8, base + 13 * 8 + 8) !== 0) throw new Error('shw_features');
    const features = Array.from(new Float64Array(m.HEAPU8.buffer, base, 13));
    const score = m.HEAP32[(base + 13 * 8) >> 2]!;
    const out = m._malloc(4 * 2048);
    const legal = m._shw_legal_moves(out, 2048);
    return { features, score, legal };
  }
  const mine = (page: Page, fig: string) => page.locator(`${fig} [data-live-switch] label.ml-fig-seg__opt`).filter({ hasText: 'My game' });
  /**
   * The moves of the running game, read from the address: the page writes every move into the game link (`#g=v1.<payload>`, 14 bits per
   * move, from << 7 | to, base64url; encodeMoves in src/app/game-url.ts). The engine's move is from | to << 8. The link names 5 rows (`t=5`).
   */
  const historyOf = (page: Page): number[] => {
    const hash = new URL(page.url()).hash;
    expect(hash).toMatch(/&t=5(&|$)/);
    const payload = /^#g=v1\.([A-Za-z0-9_-]*)/.exec(hash)?.[1];
    if (payload === undefined) throw new Error(`no game link in ${page.url()}`);
    const bytes = Buffer.from(payload, 'base64url');
    const moves: number[] = [];
    for (let n = 0, bit = 0; n < Math.floor((bytes.length * 8) / 14); n++) {
      let value = 0;
      for (let i = 0; i < 14; i++, bit++) value = (value << 1) | ((bytes[bit >>> 3]! >>> (7 - (bit & 7))) & 1);
      moves.push((value >>> 7) | ((value & 127) << 8));
    }
    return moves;
  };
  const num = (text: string): number => Number(text.replace(/[−–]/g, '-').replace(/[,+\s]/g, ''));
  const NET_NOTE = 'The first time you choose “My game” there, the browser may download the net engine and a model, about 345 kB.';
  const R5_MODEL = '/models/small-2p-adjacent-r5-expert.safetensors.gz';
  const R4_MODEL = '/models/small-2p-adjacent-expert.safetensors.gz';
  const modelRequests = (urls: string[]): string[] => urls.map((u) => new URL(u).pathname).filter((u) => u.includes('/models/'));

  async function fiveRowGame(page: Page): Promise<number[]> {
    await openApp(page, 'en', '', 'human', 5);
    await playOneExchange(page);
    await playOneExchange(page);
    expect(await plyOf(page)).toBe(4);
    return historyOf(page);
  }

  test('the card is enabled; the two net links carry the same download note as on 4 rows', async ({ page }) => {
    const errors = watchPageErrors(page);
    await fiveRowGame(page);
    await expect(page.locator('[data-explain-card]')).not.toHaveAttribute('data-disabled', '');
    await expect(page.locator('a[data-explain-link]')).toHaveCount(5);
    await expect(page.locator('[data-explain-note]')).toHaveText([NET_NOTE, NET_NOTE]);
    errors.expectNone();
  });

  test('Fig. 2.3: My game shows the features and the score of the C++ engine for the 5-row position, with the 5-row note', async ({ page }) => {
    const errors = watchPageErrors(page);
    const history = await fiveRowGame(page);
    const expected = await nodeEngine(history);
    await page.locator('a[data-explain-link="evaluation"]').click();
    const FIG = '#fig-eval';
    await page.locator(`${FIG} [data-live-switch] input[value="mine"]`).check();
    await expect(page.locator(`${FIG} [data-origin]`)).toContainText('Your game, ply 4', { timeout: 30_000 });
    await expect(page.locator(`${FIG} .ml-fig__status`)).toHaveText('Computed in your browser.');
    const score = page.locator(`${FIG} .ml-fig-readout__cell`).filter({ has: page.locator('dt', { hasText: /^Score$/ }) }).locator('dd');
    expect(num((await score.textContent()) ?? '')).toBe(expected.score);
    const shown = (await page.locator(`${FIG} .ml-fig-feat tbody tr td[data-label="Value"]`).allTextContents()).map(num);
    expect(shown).toHaveLength(13);
    shown.forEach((v, i) => expect(Math.abs(v - expected.features[i]!), `feature ${i}`).toBeLessThan(0.01));
    await expect(page.locator(`${FIG} [data-live-note=rows5]`)).toContainText('Your game has 5 rows');
    errors.expectNone();
  });

  test('Fig. 2.2 and Fig. 2.1b compute on the 5-row position: five depth markers, and one table row per legal move of the C++ engine', async ({ page }) => {
    const errors = watchPageErrors(page);
    const history = await fiveRowGame(page);
    const { legal } = await nodeEngine(history);
    await page.locator('a[data-explain-link="budget"]').click();
    await mine(page, '#fig-budget').click();
    await expect(page.locator('#fig-budget [data-origin]')).toHaveText('Your game, ply 4, computed in your browser');
    await expect
      .poll(() => page.locator('#fig-budget .ml-fig-plot__pt--1').evaluateAll((els) => els.filter((e) => !e.closest('.ml-fig-legend')).length))
      .toBe(5);
    await expect(page.locator('#fig-budget [data-live-note=rows5]')).toBeVisible();
    await expect(page.locator('#fig-budget [data-live-note=race]')).toHaveCount(0); // no race table on 5 rows

    await page.locator('.ml-nav__link').first().click(); // Play
    await page.locator('a[data-explain-link="alpha-beta"]').click();
    await mine(page, '#fig-roots').click();
    await expect(page.locator('#fig-roots [data-roots]')).toHaveAttribute('data-roots', 'ready');
    await expect(page.locator('#fig-roots tbody tr')).toHaveCount(legal);
    await expect(page.locator('#fig-roots [data-live-note=rows5]')).toBeVisible();
    errors.expectNone();
  });

  test('Fig. 3.3: My game runs the 5-row expert on the 5-row position: 50 / 50, one row per legal move of the C++ engine, visits add up to 50', async ({ page }) => {
    const errors = watchPageErrors(page);
    const urls: string[] = [];
    page.on('request', (r) => urls.push(r.url()));
    const history = await fiveRowGame(page);
    const { legal } = await nodeEngine(history);
    await page.locator('a[data-explain-link="mcts"]').click();
    const FIG = '#fig-mcts';
    await expect(page.locator(`${FIG} [data-live-reason]`)).toHaveCount(0);
    await mine(page, FIG).click();
    await expect(page.locator(`${FIG} [role=status]`)).toContainText('Computed in your browser.', { timeout: 90_000 });
    await expect(page.locator(`${FIG} .ml-fig-stepper__pos`)).toContainText('50 / 50');
    await expect(page.locator(`${FIG} [data-origin]`)).toHaveText('Your game, ply 4, computed in your browser');
    const more = page.locator(`${FIG} .ml-fig-mcts__table ~ button[aria-expanded]`);
    if ((await more.count()) > 0 && (await more.getAttribute('aria-expanded')) === 'false') await more.click();
    const visits = (await page.locator(`${FIG} .ml-fig-mcts__table tbody tr td:nth-child(3)`).allTextContents()).map(Number);
    expect(visits).toHaveLength(legal);
    expect(visits.reduce((a, b) => a + b, 0)).toBe(50);
    await expect(page.locator(FIG)).toContainText("with the model small-2p-adjacent-r5-expert");
    expect(modelRequests(urls).some((u) => u.endsWith(R5_MODEL)), 'the 5-row expert is loaded').toBe(true);
    expect(modelRequests(urls).filter((u) => u.endsWith(R4_MODEL)), 'the 4-row expert is not loaded').toEqual([]);
    errors.expectNone();
  });

  test('Fig. 3.2: My game on 5 rows computes with the 5-row expert; planes 3 and 4 are the 15-cell houses and the 5-row note is shown', async ({ page }) => {
    const errors = watchPageErrors(page);
    const urls: string[] = [];
    page.on('request', (r) => urls.push(r.url()));
    await fiveRowGame(page);
    await page.locator('a[data-explain-link="forward-pass"]').click();
    const F = '#fig-forward';
    const position = page.locator(`${F} select.ml-fig-select`).first();
    const planeSelect = page.locator(`${F} select.ml-fig-select`).nth(1);
    await expect(position.locator('option')).toHaveText(['Start position', 'Mid-game, after 30 plies', 'Race, after 106 plies', 'My game (ply 4)']);
    await expect(planeSelect.locator('option').nth(3)).toHaveText('Goal corner'); // the examples are 4-row games
    await position.selectOption('mine');
    await expect(page.locator(`${F} [role=status]`)).toContainText('Computed in your browser.', { timeout: 90_000 });
    await expect(page.locator(`${F} [data-origin]`)).toHaveText('Your game, ply 4, computed in your browser');
    await expect(planeSelect.locator('option').nth(3)).toHaveText('Goal house (15 cells)');
    await expect(planeSelect.locator('option').nth(4)).toHaveText('Start house (15 cells)');
    await expect(page.locator(`${F} [data-live-note=forward-rows5]`)).toContainText('Planes 3 and 4 mark the houses of 15 cells');
    expect(await page.locator(`${F} .ml-fig-policy li`).count()).toBe(5);
    expect(modelRequests(urls).some((u) => u.endsWith(R5_MODEL)), 'the 5-row expert is loaded').toBe(true);
    expect(modelRequests(urls).filter((u) => u.endsWith(R4_MODEL)), 'the 4-row expert is not loaded').toEqual([]);
    errors.expectNone();
  });
});
