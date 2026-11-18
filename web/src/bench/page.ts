// The browser page of the benchmark (web/bench/index.html): runs the benchmark of plan section 8 on every engine this
// browser can run, each in its own worker, and shows the same table as the node runner. Plain DOM, no React: the page
// must not depend on the app's UI code. The texts here are developer-facing English; they are not part of the i18n files.
import '../../design/tokens.css';
import expectedText from '../../bench/expected.json?raw';
import type { LoadReport } from '../engines/engine';
import { loadEnginesManifest, wasmGcSupported } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { formatLoads, formatTable, parseExpected, parseSpec, runBenchmark, toJson } from './core';
import type { EngineReport } from './core';
import { WorkerTarget } from './worker-target';

const baseUrl = import.meta.env.BASE_URL;
const expected = parseExpected(expectedText);

// Literal `new Worker(new URL(...))` calls: Vite only bundles workers it can see in this form.
const makeWorker: Record<string, () => Worker> = {
  'cpp-wasm': () => new Worker(new URL('../engines/cpp/worker.ts', import.meta.url), { type: 'module' }),
  'java-wasmgc': () => new Worker(new URL('../engines/java/worker.ts', import.meta.url), { type: 'module' }),
  'java-js': () => new Worker(new URL('../engines/java/worker.ts', import.meta.url), { type: 'module' }),
};
const META: Record<string, { runtime: string; language: string; available: () => boolean }> = {
  'cpp-wasm': { runtime: 'wasm', language: 'C++', available: () => typeof WebAssembly === 'object' },
  'java-wasmgc': { runtime: 'wasm-gc', language: 'Java', available: wasmGcSupported },
  'java-js': { runtime: 'js', language: 'Java', available: () => true },
};

const root = document.getElementById('bench')!;
root.style.cssText = 'max-width:60rem;margin:0 auto;padding:1rem 16px 3rem;font:16px/1.5 var(--ml-font-ui);color:var(--ml-color-ink);';
document.body.style.cssText = 'margin:0;background:var(--ml-color-bg);';
root.innerHTML = `
  <h1 style="font-size:1.5rem;margin:0 0 .25rem">Benchmark</h1>
  <p style="margin:0 0 1rem;color:var(--ml-color-ink-muted)">One self-play game per run. Every engine plays the same game; the fingerprint says whether they computed the same search.</p>
  <form id="form" style="display:flex;flex-wrap:wrap;gap:.75rem;align-items:end;margin-bottom:1rem">
    <label>Plies<br><input name="plies" type="number" min="1" value="40" style="width:6rem;font:inherit;padding:.25rem"></label>
    <label>Nodes per move<br><input name="nodes" type="number" min="1" value="20000" style="width:8rem;font:inherit;padding:.25rem"></label>
    <label>Runs<br><input name="runs" type="number" min="1" value="5" style="width:5rem;font:inherit;padding:.25rem"></label>
    <fieldset id="engines" style="border:1px solid var(--ml-color-line-strong);margin:0"><legend>Engines</legend></fieldset>
    <button id="run" type="submit" style="font:inherit;padding:.5rem 1rem;background:var(--ml-color-primary);color:var(--ml-color-on-primary);border:0;border-radius:6px">Run</button>
  </form>
  <p id="status" role="status" aria-live="polite" data-state="idle" style="min-height:1.5rem">Loading the engine list.</p>
  <pre id="table" style="overflow-x:auto;background:var(--ml-color-surface);padding:.75rem;border:1px solid var(--ml-color-line)" hidden></pre>
  <pre id="loads" style="overflow-x:auto;white-space:pre-wrap;font-size:.85rem" hidden></pre>
  <p><button id="copy" type="button" hidden style="font:inherit;padding:.4rem .8rem">Copy result as JSON</button></p>`;

const $ = <T extends HTMLElement>(id: string): T => root.querySelector<T>('#' + id)!;
const status = $<HTMLElement>('status');
const setStatus = (text: string, state: string): void => { status.textContent = text; status.dataset.state = state; };

let manifest: EnginesManifest;
let lastJson = '';

async function main(): Promise<void> {
  manifest = await loadEnginesManifest(baseUrl + 'engines/engines.json');
  const box = $<HTMLElement>('engines');
  for (const id of Object.keys(META)) {
    const shipped = (manifest.engines[id as keyof typeof manifest.engines]?.files.length ?? 0) > 0;
    const usable = shipped && META[id]!.available();
    const label = document.createElement('label');
    label.style.marginRight = '1rem';
    label.innerHTML = `<input type="checkbox" name="engine" value="${id}" ${usable ? 'checked' : 'disabled'}> ${id}${usable ? '' : shipped ? ' (not supported here)' : ' (not shipped)'}`;
    box.append(label);
  }
  setStatus('Ready.', 'idle');
}

$<HTMLFormElement>('form').addEventListener('submit', (event) => {
  event.preventDefault();
  void run(new FormData(event.target as HTMLFormElement)).catch((e: unknown) => setStatus(`Failed: ${(e as Error).message}`, 'error'));
});
$<HTMLButtonElement>('copy').addEventListener('click', () => { void navigator.clipboard.writeText(lastJson); });

async function run(form: FormData): Promise<void> {
  const spec = parseSpec(form.get('plies'), form.get('nodes'), form.get('runs'));
  const ids = form.getAll('engine') as string[];
  if (ids.length === 0) throw new Error('select at least one engine');
  const button = $<HTMLButtonElement>('run');
  button.disabled = true;
  const targets: WorkerTarget[] = [];
  try {
    const loads = new Map<string, LoadReport>();
    for (const id of ids) {
      setStatus(`Loading ${id}.`, 'loading');
      const entry = manifest.engines[id as keyof typeof manifest.engines]!;
      const target = new WorkerTarget({
        id, worker: makeWorker[id]!(), files: entry.files, baseUrl,
        info: { runtime: META[id]!.runtime, language: META[id]!.language, binaryBytes: entry.binaryBytes, binaryGzipBytes: entry.binaryGzipBytes, version: manifest.version },
      });
      targets.push(target);
      loads.set(id, await target.init());
    }
    const total = ids.length * spec.runs;
    let done = 0;
    const reports: EngineReport[] | null = await runBenchmark(targets, spec, expected, {
      loads,
      onGame: ({ engine, run: n, runs }) => setStatus(`Game ${++done} of ${total} (${engine}, run ${n} of ${runs}).`, 'running'),
    });
    if (!reports) return;
    const environment = { runner: 'browser', userAgent: navigator.userAgent, hardwareConcurrency: navigator.hardwareConcurrency, secureContext: window.isSecureContext, date: new Date().toISOString() };
    $<HTMLElement>('table').textContent = formatTable(reports);
    $<HTMLElement>('loads').textContent = `${formatLoads(reports)}\n\n${environment.userAgent}, ${environment.hardwareConcurrency} logical cores`;
    for (const id of ['table', 'loads', 'copy']) $<HTMLElement>(id).hidden = false;
    lastJson = toJson(reports, spec, environment);
    const bad = reports.filter((r) => r.status !== 'ok' || r.rows1Requests > 0);
    setStatus(bad.length === 0 ? `fingerprint ok for ${reports.length} engine(s)` : `fingerprint NOT ok for ${bad.map((r) => r.id).join(', ')}`, bad.length === 0 ? 'ok' : 'error');
  } finally {
    for (const t of targets) t.dispose();
    button.disabled = false;
  }
}

main().catch((e: unknown) => setStatus(`Failed: ${(e as Error).message}`, 'error'));
