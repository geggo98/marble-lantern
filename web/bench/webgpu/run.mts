// Playwright runner of the WebGPU net bench (called by scripts/bench-net-webgpu.zsh).
//   node web/bench/webgpu/run.mts --config gpu|swiftshader|gpu-headed --golden DIR --net-v2 FILE --net-v3 FILE
//        [--chrome EXE] [--out FILE] [--nets real-v2,real-v3,u5-v2,u5-v3] [--variants naive,tiled]
//        [--batches 1,4,8,16,32,64,128] [--calls 400] [--latency-runs 1000] [--cpt 1|2] [--no-bench] [--worker]
// Serves the page, the TypeScript modules (transpiled on request), the weights and the golden files on 127.0.0.1.
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
import ts from 'typescript';

const here = path.dirname(fileURLToPath(import.meta.url));
const srcNet = path.join(here, "..", "..", "src", "engines", "net");
const args = new Map<string, string>();
for (let i = 2; i < process.argv.length; i++) {
  const a = process.argv[i]!;
  if (!a.startsWith('--')) continue;
  const next = process.argv[i + 1];
  if (next === undefined || next.startsWith('--')) args.set(a.slice(2), 'true');
  else { args.set(a.slice(2), next); i++; }
}
const need = (k: string): string => {
  const v = args.get(k);
  if (!v) { console.error(`missing --${k}`); process.exit(2); }
  return v;
};
const config = args.get('config') ?? 'gpu';
const golden = need('golden');
const files: Record<string, string> = { 'net-real-v2': need('net-v2'), 'net-real-v3': need('net-v3') };
const chromeExe = args.get('chrome') ?? path.join(os.homedir(), 'Library/Caches/ms-playwright/chromium-1208/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing');

let reported: (text: string) => void = () => undefined;
const reportPromise = new Promise<string>((r) => { reported = r; });
const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://x');
  const p = url.pathname;
  if (req.method === 'POST' && p === '/report') {
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => chunks.push(c));
    req.on('end', () => { res.writeHead(200); res.end(); reported(Buffer.concat(chunks).toString('utf8')); });
    return;
  }
  const send = (code: number, type: string, body: Buffer | string): void => { res.writeHead(code, {
      'content-type': type,
      'cache-control': 'no-store',
      // cross-origin isolation: performance.now() then has 5 microsecond resolution instead of 100
      'cross-origin-opener-policy': 'same-origin',
      'cross-origin-embedder-policy': 'require-corp',
      'cross-origin-resource-policy': 'same-origin',
    }); res.end(body); };
  try {
    if (p === '/') return send(200, 'text/html', fs.readFileSync(path.join(here, 'bench.html')));
    if (p.startsWith('/data/')) {
      const name = p.slice(6);
      const f = files[name] ?? path.join(golden, `${name}.safetensors`);
      return send(200, 'application/octet-stream', fs.readFileSync(f));
    }
    if (p.startsWith('/golden/')) return send(200, 'application/octet-stream', fs.readFileSync(path.join(golden, `${path.basename(p)}.safetensors`)));
    const m = /^\/([\w-]+)\.js$/.exec(p);
    const prod = /^\/src\/engines\/net\/([\w-]+)\.js$/.exec(p);
    if (prod && fs.existsSync(path.join(srcNet, `${prod[1]}.ts`))) {
      const js = ts.transpileModule(fs.readFileSync(path.join(srcNet, `${prod[1]}.ts`), "utf8"), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText;
      return send(200, "text/javascript", js);
    }
    if (m && fs.existsSync(path.join(here, `${m[1]}.ts`))) {
      const src = fs.readFileSync(path.join(here, `${m[1]}.ts`), 'utf8');
      const js = ts.transpileModule(src, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText;
      return send(200, 'text/javascript', js);
    }
    send(404, 'text/plain', 'not found');
  } catch (e) {
    send(500, 'text/plain', String(e));
  }
}).listen(0, '127.0.0.1');
await new Promise<void>((r) => server.on('listening', r));
const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;

const list = (k: string, d: string): string[] => (args.get(k) ?? d).split(',');

// Stock Firefox: Playwright needs its own patched build, so start the system Firefox with a temporary profile and let
// the page POST its result (bench-page.ts, ?opts=).
if (config === 'firefox') {
  const { spawn } = await import('node:child_process');
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'ff-webgpu-'));
  fs.writeFileSync(path.join(profile, 'user.js'), [
    'user_pref("dom.webgpu.enabled", true);',
    'user_pref("gfx.webgpu.ignore-blocklist", true);',
    'user_pref("gfx.webgpu.force-enabled", true);',
    'user_pref("browser.shell.checkDefaultBrowser", false);',
  ].join('\n'));
  const fxOpts = {
    base, variants: list('variants', 'naive,tiled'), batches: list('batches', '1,8,32,128').map(Number),
    netNames: list('nets', 'real-v2'), calls: Number(args.get('calls') ?? 100), latencyRuns: Number(args.get('latency-runs') ?? 300),
    skipBench: args.has('no-bench'),
  };
  const query = encodeURIComponent(JSON.stringify(fxOpts));
  const child = spawn(args.get('firefox') ?? 'firefox', ['--headless', '--no-remote', '--profile', profile, `${base}/?opts=${query}`], { stdio: process.env.FF_DEBUG ? 'inherit' : 'ignore' });
  const timer = setTimeout(() => reported(JSON.stringify({ error: 'no report from Firefox within the timeout' })), Number(process.env.FF_TIMEOUT ?? 240_000));
  const text = await reportPromise;
  clearTimeout(timer);
  child.kill();
  const result = JSON.stringify({ config, chrome: 'firefox', load: { before: os.loadavg(), after: os.loadavg() }, ...JSON.parse(text) }, null, 1);
  if (args.has('out')) fs.writeFileSync(args.get('out')!, result); else console.log(result);
  server.close();
  process.exit(0);
}

const chromeArgs: Record<string, string[]> = {
  gpu: ['--enable-unsafe-webgpu'],
  'gpu-headed': ['--enable-unsafe-webgpu'],
  swiftshader: ['--enable-unsafe-webgpu', '--use-webgpu-adapter=swiftshader', '--enable-features=Vulkan', '--use-vulkan=swiftshader', '--use-angle=swiftshader'],
};
const browser = await chromium.launch({
  executablePath: chromeExe,
  headless: config !== 'gpu-headed',
  args: [...(chromeArgs[config] ?? chromeArgs['gpu']!), ...(config === 'gpu-headed' ? [] : ['--headless=new'])],
});
const version = browser.version();
const page = await browser.newPage();
page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') console.error(`[page ${m.type()}]`, m.text()); });
page.on('pageerror', (e) => console.error('[pageerror]', e.message));
await page.goto(base);
await page.waitForFunction(() => document.getElementById('o')!.textContent === 'ready', null, { timeout: 30_000 });

const opts = {
  base,
  variants: list('variants', 'naive,tiled'),
  batches: list('batches', '1,4,8,16,32,64,128').map(Number),
  netNames: list('nets', 'real-v2,real-v3,u5-v2,u5-v3'),
  calls: Number(args.get('calls') ?? 400),
  latencyRuns: Number(args.get('latency-runs') ?? 1000),
  cellsPerThread: args.has('cpt') ? Number(args.get('cpt')) : undefined,
  skipBench: args.has('no-bench'),
};
const result: Record<string, unknown> = { config, chrome: version, load: { before: os.loadavg() }, opts };
result['crossOriginIsolated'] = await page.evaluate(() => (window as any).crossOriginIsolated);
result['gpu'] = await page.evaluate(() => (window as any).benchApi.describeGpu());
const gpu = result['gpu'] as { available: boolean };
if (gpu.available) {
  result['main'] = await page.evaluate((o) => (window as any).benchApi.suite(o), opts);
  if (args.has('worker')) result['worker'] = await page.evaluate((o) => (window as any).benchApi.suiteInWorker(o), opts);
}
(result['load'] as Record<string, unknown>)['after'] = os.loadavg();
await browser.close();
server.close();
const text = JSON.stringify(result, null, 1);
if (args.has('out')) fs.writeFileSync(args.get('out')!, text);
else console.log(text);
