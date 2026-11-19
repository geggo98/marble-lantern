// Entry of bench.html. The Playwright runner calls window.benchApi.* and prints the JSON.
import { describeGpu, requestDevice, runSuite, type SuiteOptions } from './bench-core.js';

const api = {
  describeGpu,
  /** Runs the suite on the main thread of the page. */
  async suite(opts: SuiteOptions): Promise<unknown> {
    const device = await requestDevice();
    return runSuite(device, opts);
  },
  /** Runs the suite in a dedicated Web Worker; resolves with the worker's JSON or its error. */
  suiteInWorker(opts: SuiteOptions): Promise<unknown> {
    return new Promise((resolve) => {
      const w = new Worker('./bench-worker.js', { type: 'module' });
      const timer = setTimeout(() => resolve({ error: 'worker timeout after 300 s' }), 300_000);
      w.onmessage = (e) => { clearTimeout(timer); resolve(e.data); w.terminate(); };
      w.onerror = (e) => { clearTimeout(timer); resolve({ error: `worker error: ${e.message}` }); };
      w.postMessage(opts);
    });
  },
};

(window as unknown as { benchApi: typeof api }).benchApi = api;

// Browsers that Playwright cannot drive (stock Firefox): ?opts=<json> runs everything and POSTs the JSON to /report.
const auto = new URLSearchParams(location.search).get("opts");
if (auto) {
  void (async () => {
    const opts = JSON.parse(auto) as SuiteOptions;
    const result: Record<string, unknown> = { userAgent: navigator.userAgent, crossOriginIsolated: (window as any).crossOriginIsolated };
    try {
      result["gpu"] = await describeGpu();
      if ((result["gpu"] as { available: boolean }).available) {
        result["main"] = await api.suite(opts);
        result["worker"] = await api.suiteInWorker(opts);
      }
    } catch (e) {
      result["error"] = String(e);
    }
    await fetch("/report", { method: "POST", body: JSON.stringify(result) });
  })();
}
document.getElementById('o')!.textContent = 'ready';
