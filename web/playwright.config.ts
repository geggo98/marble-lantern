// Browser tests of the offline site: Playwright against `vite preview` with the GitHub Pages base path.
// Chromium and Firefox are gates; WebKit is informative (Playwright's WebKit is not Safari; scripts/web/e2e.zsh runs it apart).
import { defineConfig, devices } from '@playwright/test';

const PORT = Number(process.env.E2E_PORT ?? 4173); // another checkout may hold 4173
const BASE_PATH = '/marble-lantern/';
// E2E_BASE_URL=https://geggo98.github.io/marble-lantern/ runs the specs against the deployed site (a live smoke test) instead of vite preview.
const LIVE = process.env.E2E_BASE_URL;

export default defineConfig({
  testDir: 'e2e',
  outputDir: 'test-results/e2e',
  // A search of 20,000 nodes in the slowest engine (Java as JavaScript) takes under a second on a normal machine;
  // the budget covers a loaded CI runner and the download of the table.
  timeout: 120_000,
  expect: { timeout: 60_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list']],
  use: {
    baseURL: LIVE ?? `http://127.0.0.1:${PORT}${BASE_PATH}`,
    trace: 'retain-on-failure',
    reducedMotion: 'reduce',
    locale: 'en-US',
    // The production build registers a service worker (docs/web-ui.md, "Installable app and offline cache"). It would serve later page
    // loads from its cache and hide what a spec routes or counts, so the specs run without it; e2e/pwa.spec.ts allows it on purpose.
    serviceWorkers: 'block',
  },
  ...(LIVE
    ? {}
    : {
        webServer: {
    // `vite preview` serves web/dist under the base path; build first with `devenv tasks run web:build`.
    command: `npm exec --no -- vite preview --host 127.0.0.1 --port ${PORT} --strictPort`,
    url: `http://127.0.0.1:${PORT}${BASE_PATH}`,
    env: { BASE_PATH },
    reuseExistingServer: false,
    timeout: 60_000,
        },
      }),
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] }, metadata: { informative: true } },
  ],
});
