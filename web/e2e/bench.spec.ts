// The benchmark screen plays the benchmark game with every engine and compares the fingerprint with the reference. Besides
// wall time and nodes/s it shows CPU time and peak memory where the browser can tell, and "n/a" where it cannot.
//
// In Chromium the test also reads what a page cannot read about itself, through the Chrome DevTools Protocol:
// Performance.getMetrics of the page (TaskDuration, JSHeapUsedSize of the main thread) and the CPU time of the browser's
// renderer process (SystemInfo.getProcessInfo on a browser session; workers run in the renderer process). They are attached
// to the test report and, when BENCH_E2E_JSON names a file, written there; they are not part of the UI table.
import { writeFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import type { CDPSession } from '@playwright/test';
import { openApp, watchPageErrors } from './helpers';

interface Metric { name: string; value: number }
const metricsOf = async (session: CDPSession): Promise<Record<string, number>> => {
  const { metrics } = (await session.send('Performance.getMetrics')) as { metrics: Metric[] };
  return Object.fromEntries(metrics.map((m) => [m.name, m.value]));
};
const rendererCpuSeconds = async (browserSession: CDPSession): Promise<number> => {
  const { processInfo } = (await browserSession.send('SystemInfo.getProcessInfo')) as { processInfo: Array<{ type: string; cpuTime: number }> };
  return processInfo.filter((p) => p.type === 'renderer').reduce((sum, p) => sum + p.cpuTime, 0);
};

test('the benchmark screen shows "ok", CPU time and memory (or n/a) for every available engine', async ({ page, browser, browserName }, testInfo) => {
  const errors = watchPageErrors(page);
  await openApp(page, 'en', '#/bench');
  const start = page.locator('.ml-bench__actions .ml-btn--primary');
  await expect(start).toBeVisible();
  await expect(start).toBeEnabled();

  const chromium = browserName === 'chromium';
  let pageSession: CDPSession | null = null;
  let browserSession: CDPSession | null = null;
  let before: Record<string, number> = {};
  let cpuBefore = 0;
  if (chromium) {
    pageSession = await page.context().newCDPSession(page);
    await pageSession.send('Performance.enable');
    browserSession = await browser.newBrowserCDPSession();
    before = await metricsOf(pageSession);
    cpuBefore = await rendererCpuSeconds(browserSession);
  }

  await start.click();
  const rows = page.locator('.ml-table tbody tr');
  await expect(rows.first()).toBeVisible({ timeout: 110_000 });
  const count = await rows.count();
  expect(count).toBeGreaterThan(0);

  const headers = await page.locator('.ml-table thead th').allTextContents();
  expect(headers).toEqual(expect.arrayContaining(['CPU time (ms)', 'Peak memory (MiB)']));

  const cell = (i: number, label: string) => rows.nth(i).locator(`td[data-label="${label}"]`);
  for (let i = 0; i < count; i++) {
    await expect(rows.nth(i).locator('.ml-ok')).toHaveText('ok');
    // CPU time: no browser API, so always n/a here, never a made-up number.
    await expect(cell(i, 'CPU time (ms)')).toHaveText('n/a');
    // Memory is a number or n/a, never 0 or empty.
    await expect(cell(i, 'Peak memory (MiB)')).toHaveText(/^(n\/a|\d[\d,]*(\.\d)?)$/);
  }
  // The footnotes explain every n/a on the screen.
  await expect(page.locator('.ml-bench__notes li').first()).toBeVisible();
  const first = page.locator('.ml-bench__notes li').filter({ hasText: 'CPU time of a worker' });
  await expect(first).toHaveCount(1);

  // The C++ engine's Wasm memory holds at least the 12 MB table, so a real figure is above 12 MiB.
  const cppRow = rows.filter({ hasText: 'cpp-wasm' });
  if ((await cppRow.count()) === 1) {
    const text = (await cppRow.locator('td[data-label="Peak memory (MiB)"]').textContent()) ?? '';
    expect(Number(text.replace(/,/g, ''))).toBeGreaterThan(12);
  }

  if (chromium && pageSession && browserSession) {
    const after = await metricsOf(pageSession);
    const cpuAfter = await rendererCpuSeconds(browserSession);
    const cdp = {
      browser: browserName,
      pageTaskDurationSeconds: after.TaskDuration! - before.TaskDuration!,
      pageJSHeapUsedSizeBytes: after.JSHeapUsedSize,
      rendererProcessCpuSeconds: cpuAfter - cpuBefore,
      table: await rows.evaluateAll((trs) => trs.map((tr) => [...tr.querySelectorAll('td')].map((td) => td.textContent))),
      note: 'Page main thread only (Performance.getMetrics); the renderer CPU time covers page and workers together.',
    };
    expect(cdp.pageTaskDurationSeconds).toBeGreaterThanOrEqual(0);
    expect(cdp.rendererProcessCpuSeconds).toBeGreaterThan(0);
    await testInfo.attach('cdp-metrics.json', { body: JSON.stringify(cdp, null, 2), contentType: 'application/json' });
    if (process.env.BENCH_E2E_JSON) writeFileSync(process.env.BENCH_E2E_JSON, JSON.stringify(cdp, null, 2));
  }
  errors.expectNone();
});
