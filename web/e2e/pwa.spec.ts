// The installable app (docs/web-ui.md, "Installable app and offline cache"): the manifest under the base path, the service worker of the
// production build, and a game without network. The other specs block service workers (playwright.config.ts); this one allows them.
// Chromium only: Playwright's Firefox and WebKit do not let a spec drive service workers and offline mode together reliably.
import { expect, test } from '@playwright/test';
import { openApp, pickEngine, playExchange, waitForHumanTurn, watchPageErrors } from './helpers';

test.use({ serviceWorkers: 'allow' });
test.skip(({ browserName }) => browserName !== 'chromium', 'service worker spec: Chromium only');

interface CacheReport {
  name: string;
  files: number;
  bytes: number;
  urls: string[];
}

/** The caches of this site with their entries and sizes, read in the page. */
async function cacheReport(page: import('@playwright/test').Page): Promise<CacheReport[]> {
  return await page.evaluate(async () => {
    const out: { name: string; files: number; bytes: number; urls: string[] }[] = [];
    for (const name of await caches.keys()) {
      const cache = await caches.open(name);
      let bytes = 0;
      const urls: string[] = [];
      for (const req of await cache.keys()) {
        urls.push(new URL(req.url).pathname);
        bytes += (await (await cache.match(req))!.arrayBuffer()).byteLength;
      }
      out.push({ name, files: urls.length, bytes, urls });
    }
    return out;
  });
}

test('the manifest is linked under the base path and points into it', async ({ page, baseURL }) => {
  await page.goto('');
  const href = await page.locator('link[rel="manifest"]').getAttribute('href');
  const manifestUrl = new URL(href!, baseURL).href;
  expect(manifestUrl).toBe(new URL('manifest.webmanifest', baseURL).href);
  const response = await page.request.get(manifestUrl);
  expect(response.status()).toBe(200);
  const manifest = (await response.json()) as Record<string, unknown> & { icons: { src: string; sizes: string; type: string; purpose: string }[] };
  expect(manifest.name).toBe('Marble Lantern');
  expect(manifest.display).toBe('standalone');
  for (const key of ['start_url', 'scope', 'id']) expect(new URL(manifest[key] as string, manifestUrl).href, key).toBe(baseURL);
  expect(manifest.icons.map((i) => `${i.sizes} ${i.purpose}`)).toEqual(expect.arrayContaining(['192x192 any', '512x512 any', '512x512 maskable']));
  for (const icon of manifest.icons) {
    const r = await page.request.get(new URL(icon.src, manifestUrl).href);
    expect(r.status(), icon.src).toBe(200);
    expect(r.headers()['content-type'], icon.src).toContain(icon.type);
  }
  const touch = await page.locator('link[rel="apple-touch-icon"]').getAttribute('href');
  expect((await page.request.get(new URL(touch!, baseURL).href)).status()).toBe(200);
});

test('after one visit the game starts and plays without network, also with an engine cached on first use', async ({ page, context }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  // the app registers sw.js once the engine is ready; install fills the precache, activate claims the page
  await page.waitForFunction(() => !!navigator.serviceWorker?.controller, undefined, { timeout: 60_000 });
  // a second engine, loaded online once: its files land in the runtime cache
  await pickEngine(page, 'cpp-wasm');
  await playExchange(page);

  const online = await cacheReport(page);
  const ours = online.filter((c) => c.name.startsWith('ml:/marble-lantern/:'));
  expect(ours.map((c) => c.name.replace(/-[0-9a-f]+$/, '')).sort()).toEqual(['ml:/marble-lantern/:precache', 'ml:/marble-lantern/:runtime']);
  const pre = ours.find((c) => c.name.includes(':precache-'))!;
  const run = ours.find((c) => c.name.includes(':runtime-'))!;
  for (const path of ['index.html', 'manifest.webmanifest', 'engines/engines.json', 'engines/java/engine-java.wasm', 'tables/rows2.bin.gz']) {
    expect(pre.urls, path).toContain(`/marble-lantern/${path}`);
  }
  expect(run.urls).toContain('/marble-lantern/engines/cpp/engine-cpp.wasm');
  expect(run.urls).not.toContain('/marble-lantern/engines/java/engine-java.js'); // never used, so not cached
  console.log(`pwa caches: precache ${pre.files} files ${pre.bytes} bytes; runtime ${run.files} files ${run.bytes} bytes`);

  await context.setOffline(true);
  try {
    await openApp(page, 'en'); // a fresh load without a game link: the start dialog, then the default engine
    await expect(page.locator('.ml-badge')).toHaveText(/offline/i);
    await playExchange(page);
    await pickEngine(page, 'cpp-wasm'); // from the runtime cache
    await playExchange(page);
  } finally {
    await context.setOffline(false);
  }
  errors.expectNone();
});

test('an Info topic that was never opened online renders with its figures without network (the topic chunk comes from the precache)', async ({ page, context }) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en');
  await waitForHumanTurn(page);
  await page.waitForFunction(() => !!navigator.serviceWorker?.controller, undefined, { timeout: 60_000 });

  const caches_ = (await cacheReport(page)).filter((c) => c.name.startsWith('ml:/marble-lantern/:'));
  const pre = caches_.find((c) => c.name.includes(':precache-'))!;
  // the lazy chunk of the topic and the shared figure chunk are in the precache, although no page has asked for them yet
  expect(pre.urls.filter((u) => /\/assets\/inference-[\w-]+\.js$/.test(u)), 'inference chunk in the precache').toHaveLength(1);
  expect(pre.urls.filter((u) => /\/assets\/shared-[\w-]+\.js$/.test(u)), 'shared figure chunk in the precache').toHaveLength(1);

  await context.setOffline(true);
  try {
    await page.goto('about:blank'); // a real load: the next goto must not be a mere hash change of the open document
    await openApp(page, 'en', '#/about/inference');
    await expect(page.locator('main[data-topic]')).toHaveAttribute('data-topic', 'inference');
    expect(await page.locator('figure.ml-fig').count(), 'figures of the topic').toBeGreaterThanOrEqual(3);
    await expect(page.locator('figure.ml-fig svg[role="img"]').first()).toBeVisible();
    await expect(page.locator('.ml-alert'), 'no load-error alert').toHaveCount(0);
  } finally {
    await context.setOffline(false);
  }
  errors.expectNone();
});
