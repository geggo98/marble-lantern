// The routes of the Info part in a real browser (smoke test of WP1): the hub with its cards, a topic page, a section link, the
// language switch on a topic page, and a running game that survives a trip through two topic pages and Back.
// The topic texts are stubs for now; the checks use the structure (links, ids, focus) and the stub titles of the two languages.
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { openApp, playOneExchange, plyOf, watchPageErrors } from './helpers';

const TOPICS = ['architecture', 'classical', 'inference', 'training', 'tuning', 'glossary'];

const noOverflow = async (page: Page): Promise<void> => {
  const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(over, 'page overflow').toBeLessThanOrEqual(0);
};

test('the hub keeps the About text and shows one card per topic; a card opens its topic with the focus on the h1', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '#/about');
  await expect(page.locator('h1')).toHaveText('About');
  const cards = page.locator('.ml-learn__card a.ml-learn__link');
  await expect(cards).toHaveCount(6);
  expect(await cards.evaluateAll((links) => links.map((a) => a.getAttribute('href')))).toEqual(TOPICS.map((t) => `#/about/${t}`));
  await cards.nth(1).click();
  await expect(page).toHaveURL(/#\/about\/classical$/);
  await expect(page.locator('h1')).toHaveText('The classical algorithm');
  await expect(page.locator('h1')).toBeFocused();
  await expect(page.locator('.ml-nav__link[href="#/about"]')).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('.ml-learn')).toHaveCount(0);
  await noOverflow(page);
  errors.expectNone();
});

test('an unknown topic shows the hub with a notice', async ({ page }) => {
  await openApp(page, 'en', '#/about/nonsense');
  await expect(page.locator('[role="status"]')).toHaveText('That page does not exist. Here is the overview.');
  await expect(page.locator('.ml-learn__card')).toHaveCount(6);
});

test('a section link scrolls to the section and focuses its heading; the contents list replaces the entry', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '#/about/classical/alpha-beta');
  const heading = page.locator('#about-classical-alpha-beta');
  await expect(heading).toBeFocused();
  await expect(heading).toBeInViewport();
  const entries = await page.evaluate(() => history.length);
  await page.locator('nav.ml-about__toc a[href="#/about/classical/minimax"]').click();
  await expect(page).toHaveURL(/#\/about\/classical\/minimax$/);
  await expect(page.locator('#about-classical-minimax')).toBeFocused();
  expect(await page.evaluate(() => history.length), 'a jump in "On this page" adds no history entry').toBe(entries);
  errors.expectNone();
});

test('a glossary term opens a popover with a link into the glossary', async ({ page }) => {
  await openApp(page, 'en', '#/about/classical');
  const term = page.locator('.ml-about__section .ml-term__btn[data-term="game-tree"]');
  await term.click();
  await expect(term).toHaveAttribute('aria-expanded', 'true');
  const link = page.locator('.ml-term__pop a');
  await expect(link).toHaveAttribute('href', '#/about/glossary/game-tree');
  await page.keyboard.press('Escape');
  await expect(page.locator('.ml-term__pop')).toHaveCount(0);
  await expect(term).toBeFocused();
});

test('German and English: the topic page switches its texts in place', async ({ page }) => {
  await openApp(page, 'de', '#/about/classical');
  await expect(page.locator('h1')).toHaveText('Der klassische Algorithmus');
  await expect(page.locator('a[rel="next"]')).toContainText('Inferenz und das Netz');
  await page.locator('[data-language-toggle="header"] [data-language="en"]').click();
  await expect(page.locator('h1')).toHaveText('The classical algorithm');
  await expect(page.locator('a[rel="next"]')).toContainText('Inference and the network');
  await expect(page).toHaveURL(/#\/about\/classical$/);
  await page.locator('[data-language-toggle="header"] [data-language="de"]').click();
  await expect(page.locator('h1')).toHaveText('Der klassische Algorithmus');
});

test('the glossary page lists the terms and focuses the term of the route', async ({ page }) => {
  await openApp(page, 'en', '#/about/glossary/tablebase');
  await expect(page.locator('#about-glossary-tablebase')).toBeFocused();
  expect(await page.locator('.ml-about__term').count()).toBeGreaterThan(3);
});

test('at 320 px the topic page and the hub do not scroll sideways', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await openApp(page, 'de', '#/about');
  await noOverflow(page);
  await openApp(page, 'de', '#/about/inference');
  await expect(page.locator('h1')).toBeVisible();
  await noOverflow(page);
});

test('a running game survives a trip through two topic pages and Back', async ({ page }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await playOneExchange(page);
  const gameUrl = page.url();
  expect(new URL(gameUrl).hash).toMatch(/^#g=/);
  const plies = await plyOf(page);
  await page.evaluate(() => {
    location.hash = '#/about/classical';
  });
  await expect(page.locator('h1')).toHaveText('The classical algorithm');
  await page.evaluate(() => {
    location.hash = '#/about/inference';
  });
  await expect(page.locator('h1')).toHaveText('Inference and the network');
  await page.goBack();
  await expect(page.locator('h1')).toHaveText('The classical algorithm');
  await page.goBack();
  await expect(page.locator('.ml-status__ply')).toBeVisible();
  await expect.poll(() => plyOf(page)).toBe(plies);
  expect(page.url()).toBe(gameUrl);
  errors.expectNone();
});

test('a game survives the way through the header: About, a topic, the next topic, and back to Play', async ({ page }) => {
  await openApp(page, 'en');
  await playOneExchange(page);
  const plies = await plyOf(page);
  await page.locator('.ml-nav__link[href="#/about"]').click();
  await page.locator('.ml-learn__link[href="#/about/training"]').click();
  await expect(page.locator('h1')).toHaveText('How training runs');
  await page.locator('a[rel="next"]').click();
  await expect(page.locator('h1')).toHaveText('How the hyperparameters were found');
  await page.locator('.ml-nav__link[href="#/play"]').click();
  await expect.poll(() => plyOf(page)).toBe(plies);
  expect(new URL(page.url()).hash).toMatch(/^#g=/);
});
