// The production WebGPU backend (web/src/engines/net/webgpu-backend.ts) in a real Chromium: outputs against the Java reference
// ("golden") files, on the main thread and in a Worker, typed errors, and (WEBGPU_BENCH=1) latency through the production API.
//
// The test starts its own Vite dev server (it needs TypeScript modules, not the built site) and serves the golden files.
// A test that finds no WebGPU adapter SKIPS; a test that finds no golden file SKIPS. Both are reported as skipped.
//
//   WEBGPU_E2E=swiftshader (default)  software Vulkan (SwiftShader): runs on CI machines without a GPU
//   WEBGPU_E2E=gpu                    the real GPU of the machine (Metal on macOS)
//   STERNHALMA_GOLDEN_DIR             directory with the golden files (default <repo>/cpp/golden)
//   STERNHALMA_DATA_DIR               directory with loop3/gen-0013 for the case real-v2 (default <repo>/data)
//   WEBGPU_BENCH=1                    also run the latency test
//
// The golden files come from `./gradlew --no-build-cache :search:goldenExport :nn:goldenExport` (nn-inputs, nn-ref-small-u5,
// nn-weights-small-u5) and from web/bench/webgpu/RealRef.java (real-ref-v2, run by scripts/bench-net-webgpu.zsh).
import fs from 'node:fs';
import type { AddressInfo } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { ViteDevServer } from 'vite';
import type { HarnessCase } from './webgpu/harness';

const webDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = path.resolve(webDir, '..');
const goldenDir = process.env['STERNHALMA_GOLDEN_DIR'] ?? path.join(repoDir, 'cpp', 'golden');
const dataDir = process.env['STERNHALMA_DATA_DIR'] ?? path.join(repoDir, 'data');
const mode = process.env['WEBGPU_E2E'] ?? 'swiftshader';

const FLAGS: Record<string, string[]> = {
  swiftshader: ['--enable-unsafe-webgpu', '--use-webgpu-adapter=swiftshader', '--enable-features=Vulkan', '--use-vulkan=swiftshader', '--use-angle=swiftshader'],
  gpu: ['--enable-unsafe-webgpu'],
};

const golden = (name: string): string => path.join(goldenDir, `${name}.safetensors`);
const realV2 = path.join(dataDir, 'loop3', 'gen-0013', 'candidate.safetensors');

/** The cases whose files exist. weights is a URL below the test server: /weights/<name> or /golden/<name>. */
const CASES: Array<HarnessCase & { files: string[]; weightsFile: string }> = [
  { name: 'u5-v2', weights: '/golden/nn-weights-small-u5', inputs: 'nn-inputs', ref: 'nn-ref-small-u5', weightsFile: golden('nn-weights-small-u5'), files: [golden('nn-inputs'), golden('nn-ref-small-u5')] },
  { name: 'u5-v3', weights: '/golden/nn-weights-small-v3', inputs: 'nn-inputs-v3', ref: 'nn-ref-small-v3', weightsFile: golden('nn-weights-small-v3'), files: [golden('nn-inputs-v3'), golden('nn-ref-small-v3')] },
  { name: 'real-v2', weights: '/weights/real-v2', inputs: 'nn-inputs', ref: 'real-ref-v2', weightsFile: realV2, files: [golden('nn-inputs'), golden('real-ref-v2')] },
  { name: 'real-v3', weights: '/weights/real-v3', inputs: 'nn-inputs-v3', ref: 'real-ref-v3', weightsFile: path.join(dataDir, 'shared', 'gen0-shared', 'gen-0000', 'candidate.safetensors'), files: [golden('nn-inputs-v3'), golden('real-ref-v3')] },
];
const available = CASES.filter((c) => fs.existsSync(c.weightsFile) && c.files.every((f) => fs.existsSync(f)));

// channel "chromium" is the new headless mode of the full browser; the old headless shell has no WebGPU adapter on every platform.
// test.use must be top-level; the flags are ignored by the other projects, whose tests skip.
test.use({ launchOptions: { channel: 'chromium', args: [...(FLAGS[mode] ?? FLAGS['swiftshader']!), '--headless=new'] } });

test.describe('WebGPU backend', () => {
  test.skip(({ browserName }) => browserName !== 'chromium', 'WebGPU is exercised in Chromium only');
  let server: ViteDevServer;
  let base = '';

  test.beforeAll(async () => {
    const { createServer } = await import('vite');
    server = await createServer({
      root: webDir,
      configFile: false,
      optimizeDeps: { entries: ['e2e/webgpu/page.html'] },
      logLevel: 'error',
      // cross-origin isolation: performance.now() then has 5 microsecond resolution instead of 100 (the latency test needs it)
      server: {
        host: '127.0.0.1', port: 0, strictPort: false, fs: { allow: [repoDir] },
        headers: { 'cross-origin-opener-policy': 'same-origin', 'cross-origin-embedder-policy': 'require-corp', 'cross-origin-resource-policy': 'same-origin' },
      },
      plugins: [{
        name: 'webgpu-e2e-files',
        configureServer(s) {
          s.middlewares.use((req, res, next) => {
            const p = new URL(req.url ?? '/', 'http://x').pathname;
            const send = (file: string): void => { res.setHeader('content-type', 'application/octet-stream'); res.end(fs.readFileSync(file)); };
            const g = /^\/golden\/([\w-]+)$/.exec(p);
            if (g && fs.existsSync(golden(g[1]!))) return send(golden(g[1]!));
            const w = /^\/weights\/([\w-]+)$/.exec(p);
            const c = w && CASES.find((x) => x.name === w[1]);
            if (c && fs.existsSync(c.weightsFile)) return send(c.weightsFile);
            next();
          });
        },
      }],
    });
    await server.listen();
    base = `http://127.0.0.1:${(server.httpServer!.address() as AddressInfo).port}`;
  });

  test.afterAll(async () => {
    await server?.close();
  });

  /** Opens the test page; skips the test when this browser has no WebGPU adapter. */
  async function openPage(page: Page): Promise<void> {
    page.on("pageerror", (e) => console.error("[pageerror]", e.message));
    await page.goto(`${base}/e2e/webgpu/page.html`);
    await page.waitForFunction(() => document.getElementById('o')!.textContent === 'ready', null, { timeout: 60_000 });
    const probe = await page.evaluate(() => (window as any).run('probe', [], false)) as { available: boolean; reason?: string };
    test.skip(!probe.available, `no WebGPU adapter: ${probe.reason ?? 'unknown'}`);
  }
  const call = (page: Page, fn: string, args: unknown[], inWorker: boolean): Promise<any> =>
    page.evaluate(([f, a, w]) => (window as any).run(f, a, w), [fn, args, inWorker] as const);

  test('isWebGpuAvailable does a real adapter and device request, is cached, and gives the same answer in a Worker', async ({ page }) => {
    await openPage(page);
    const main = await call(page, 'probe', [], false);
    expect(main.available).toBe(true);
    expect(main.adapterInfo).toBeDefined();
    expect(typeof main.adapterInfo.description).toBe('string');
    expect(await call(page, 'probeTwiceSame', [], false)).toBe(true);
    const worker = await call(page, 'probe', [], true);
    expect(worker.available).toBe(true);
    if (mode === 'swiftshader') expect(main.adapterInfo.isFallbackAdapter || /swiftshader/i.test(JSON.stringify(main.adapterInfo))).toBe(true);
  });

  test('the golden cases exist (a CI gate that finds none passes nothing)', () => {
    test.skip(available.length === 0, `no golden files in ${goldenDir}; run :nn:goldenExport`);
    expect(available.length).toBeGreaterThan(0);
  });

  for (const c of CASES) {
    // main thread with the default batch, Worker with the largest batch: both axes covered without the full product (SwiftShader is slow)
    for (const [where, maxBatch] of [['main', 8], ['worker', 16]] as const) {
      test(`${c.name}: outputs match the Java reference (${where}, maxBatch ${maxBatch})`, async ({ page }) => {
        test.skip(!available.includes(c), `files of ${c.name} are missing (${c.weightsFile})`);
        await openPage(page);
        const r = await call(page, 'check', [{ base, testCase: c, maxBatch }], where === 'worker');
        console.log(`${c.name} ${where} maxBatch ${maxBatch}: maxFrac ${r.maxFrac}, ${r.checked} values, control violations ${r.controlViolations}, load+compile ${Math.round(r.loadMs)} ms`);
        expect(r.checked).toBeGreaterThan(10_000);
        expect(r.violations, `max |fast-ref| / tolerance = ${r.maxFrac}`).toBe(0);
        expect(r.maxFrac).toBeLessThanOrEqual(1);
        // the check can fail: the same numbers compared with the reference of another position violate the tolerance
        expect(r.controlViolations).toBeGreaterThan(0);
        expect(r.planes).toBe(r.goldenPlanes);
        expect(r.valueWidth).toBe(r.goldenPlayers);
        expect(r.pass).toBe(true);
      });
    }
  }

  test('typed errors on a real device', async ({ page }) => {
    const c = available[0];
    test.skip(!c, 'no golden case');
    await openPage(page);
    const e = await call(page, 'errors', [{ base, testCase: c, maxBatch: 2 }], false);
    expect(e).toEqual({
      beforeLoad: 'not-loaded', batchTooBig: 'invalid-input', shortInput: 'invalid-input',
      disposedInFlight: 'disposed', afterDispose: 'disposed', garbage: 'invalid-weights',
    });
  });

  test('latency through the production API (WEBGPU_BENCH=1)', async ({ page }, testInfo) => {
    test.skip(!process.env['WEBGPU_BENCH'], 'set WEBGPU_BENCH=1');
    const c = available.find((x) => x.name === 'real-v2') ?? available[0];
    test.skip(!c, 'no golden case');
    test.setTimeout(600_000);
    await openPage(page);
    const r = await call(page, 'latency', [{ base, testCase: c, maxBatch: 16, batches: [1, 4, 8, 16], calls: 300 }], false);
    const text = JSON.stringify({ mode, case: c!.name, ...r }, null, 1);
    await testInfo.attach('latency.json', { body: text, contentType: 'application/json' });
    if (process.env['WEBGPU_BENCH_OUT']) fs.writeFileSync(process.env['WEBGPU_BENCH_OUT'], text);
    console.log(text);
  });
});
