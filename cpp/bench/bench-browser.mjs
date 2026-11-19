// Browser runner of the network benchmark with Playwright (playwright-core of web/node_modules; browsers from
// PLAYWRIGHT_BROWSERS_PATH, set by devenv). Serves the build output over http on 127.0.0.1, opens bench-page.html in a headless
// browser and runs the schedule of bench-driver.mjs in the page (main thread). JSON lines on stdout, progress on stderr.
// usage: node bench-browser.mjs --browser chromium|firefox --net FILE --ref FILE --root REPO [--variants ...] [--teavm js,wasmgc]
import { createReadStream, readFileSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { loadavg } from 'node:os';
import { extname, join, normalize } from 'node:path';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]?.startsWith('--') ? true : (all[i + 1] ?? true)]] : acc), []),
);
const root = args.root;
const browserName = args.browser ?? 'chromium';
const variants = (args.variants ?? 'scalar,simd,relaxed').split(',').filter(Boolean);
const teavm = (args.teavm ?? 'js,wasmgc').split(',').filter(Boolean);
const quick = !!process.env.NB_QUICK;
const require = createRequire(join(root, 'web/package.json'));
const playwright = require('playwright-core');

const types = { '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript', '.wasm': 'application/wasm', '.safetensors': 'application/octet-stream' };
const routes = [
  ['/net', args.net],
  ['/ref', args.ref],
  ['/driver/', join(root, 'cpp/bench')],
  ['/cpp/', join(root, 'build/nn-bench')],
  ['/teavm/', join(root, 'teavm-nn-bench/build/generated/teavm')],
];
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://x').pathname;
  for (const [prefix, target] of routes) {
    if (url !== prefix && !(prefix.endsWith('/') && url.startsWith(prefix))) continue;
    const file = prefix.endsWith('/') ? join(target, normalize(url.slice(prefix.length)).replace(/^(\.\.[/\\])+/, '')) : target;
    try {
      statSync(file);
    } catch {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' });
    createReadStream(file).pipe(res);
    return;
  }
  res.writeHead(404).end();
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;

const browserType = playwright[browserName];
const browser = await browserType.launch({ headless: true });
const context = await browser.newContext();
const page = await context.newPage();
page.on('pageerror', (e) => process.stderr.write(`pageerror: ${e}\n`));
page.on('console', (m) => process.stderr.write(`console: ${m.text()}\n`));
const version = `${browserName} ${browser.version()}`;
await page.goto(`${origin}/driver/bench-page.html`);

const emit = (row) => {
  process.stdout.write(JSON.stringify({ ...row, host: version, load1: row.load1 ?? +loadavg()[0].toFixed(2) }) + '\n');
  if (row.batch) process.stderr.write(`${row.impl} b${row.batch}: ${row.pos_per_s} pos/s\n`);
};
await page.exposeFunction('nbEmit', emit);
const meta = await page.evaluate(() => ({ ua: navigator.userAgent, cores: navigator.hardwareConcurrency }));
process.stderr.write(`${version}: ${meta.ua}\n`);

const jobs = [
  ...variants.map((v) => ({ kind: 'cpp', variant: v })),
  ...teavm.map((t) => ({ kind: 'teavm', target: t })),
];
for (const job of jobs) {
  try {
    await page.evaluate(([origin, job, quick]) => window.runJob(origin, job, quick), [origin, job, quick]);
  } catch (e) {
    emit({ event: 'error', job, message: String(e).split('\n')[0] });
  }
}
await browser.close();
server.close();
