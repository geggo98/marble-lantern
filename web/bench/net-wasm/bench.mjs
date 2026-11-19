// Benchmark of the three flavours of the WebAssembly net backend, in Node and in headless Chromium (Playwright from web/node_modules,
// browsers from PLAYWRIGHT_BROWSERS_PATH as set by devenv). JSON lines on stdout, progress on stderr.
// usage: node bench.mjs --net FILE.safetensors --golden DIR [--wasm DIR] [--runtime node|chromium|both] [--batches 1,4,8]
//   --net     an F32 safetensors net (default: real net loop3 gen-0013 of the data directory)
//   --golden  directory with nn-inputs.safetensors (the first positions are the benchmark input; planes must match the net)
//   --wasm    directory with engine-net-nn-*.{js,wasm} (default web/public/engines/net)
import { readFileSync, statSync, createReadStream } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { loadavg } from 'node:os';
import { dirname, extname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gzipSync } from 'node:zlib';
import { SCHEDULE, benchFlavour, selfRatio } from './bench-core.mjs';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
);
const here = dirname(new URL(import.meta.url).pathname);
const repo = resolve(here, '../../..');
const wasm = resolve(args.wasm ?? join(repo, 'web/public/engines/net'));
const net = resolve(args.net ?? join(process.env.STERNHALMA_DATA_DIR ?? join(repo, 'data'), 'loop3/gen-0013/candidate.safetensors'));
const golden = resolve(args.golden ?? join(repo, 'cpp/golden'));
const runtime = args.runtime ?? 'both';
const batches = (args.batches ?? '1,4,8').split(',').map(Number);
const flavours = ['scalar', 'simd', 'relaxed'];
if (process.env.NB_QUICK) Object.assign(SCHEDULE, { warmupSeconds: 0.1, rounds: 5, roundSeconds: 0.1 });

// First 16 positions of nn-inputs.safetensors (F32 [1024][122][13]) as raw floats.
function firstInputs() {
  const f = readFileSync(join(golden, 'nn-inputs.safetensors'));
  const headerLength = Number(new DataView(f.buffer, f.byteOffset).getBigUint64(0, true));
  const header = JSON.parse(f.subarray(8, 8 + headerLength).toString('utf8'));
  const [begin] = header.inputs.data_offsets;
  const size = 122 * header.inputs.shape[2];
  return new Uint8Array(f.buffer.slice(f.byteOffset + 8 + headerLength + begin, f.byteOffset + 8 + headerLength + begin + 16 * size * 4));
}
const weights = new Uint8Array(readFileSync(net));
const inputBytes = firstInputs();
const emit = (row) => process.stdout.write(JSON.stringify({ ...row, load1: +loadavg()[0].toFixed(2) }) + '\n');

for (const f of flavours) {
  const w = readFileSync(join(wasm, `engine-net-nn-${f}.wasm`));
  emit({ event: 'size', flavour: f, wasmBytes: w.length, wasmGzipBytes: gzipSync(w, { level: 9 }).length, jsBytes: statSync(join(wasm, `engine-net-nn-${f}.js`)).size });
}

if (runtime === 'node' || runtime === 'both') {
  const inputs = new Float32Array(inputBytes.buffer.slice(inputBytes.byteOffset, inputBytes.byteOffset + inputBytes.length));
  emit({ event: 'host', runtime: `node ${process.version}`, ...selfRatio() });
  for (const f of flavours) {
    const { default: create } = await import(pathToFileURL(join(wasm, `engine-net-nn-${f}.js`)).href);
    process.stderr.write(`node ${f}\n`);
    emit({ runtime: 'node', variant: f, ...(await benchFlavour(create, weights, inputs, batches)) });
  }
}

if (runtime === 'chromium' || runtime === 'both') {
  const types = { '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript', '.wasm': 'application/wasm' };
  const files = new Map([
    ['/page.html', join(here, 'bench-page.html')],
    ['/core.mjs', join(here, 'bench-core.mjs')],
  ]);
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://x').pathname;
    if (url === '/weights' || url === '/inputs') {
      res.writeHead(200, { 'content-type': 'application/octet-stream' }).end(url === '/weights' ? weights : inputBytes);
      return;
    }
    const file = files.get(url) ?? (url.startsWith('/wasm/') ? join(wasm, url.slice(6).replace(/\.\.+/g, '')) : null);
    try {
      statSync(file);
    } catch {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' });
    createReadStream(file).pipe(res);
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const playwright = createRequire(join(repo, 'web/package.json'))('playwright-core');
  const browser = await playwright.chromium.launch({ headless: true });
  const page = await (await browser.newContext()).newPage();
  page.on('pageerror', (e) => process.stderr.write(`pageerror: ${e}\n`));
  await page.goto(`${origin}/page.html`);
  emit({ event: 'host', runtime: `chromium ${browser.version()}`, ...(await page.evaluate(() => window.probe())) });
  for (const f of flavours) {
    process.stderr.write(`chromium ${f}\n`);
    const result = await page.evaluate(([o, v, b, s]) => window.run(o, v, b, s), [origin, f, batches, SCHEDULE]);
    emit({ runtime: `chromium ${browser.version()}`, variant: f, ...result });
  }
  await browser.close();
  server.close();
}
