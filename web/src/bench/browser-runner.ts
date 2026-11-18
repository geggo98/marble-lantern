// The BenchRunner of the benchmark screen: plays the benchmark game on every engine this browser can run, each in its own worker,
// with the same core as the node runner (core.ts). Figures: wall time and nodes/s as in node; memory as far as the browser tells
// (browser-report.ts); CPU time is n/a because no page API gives it.
import { loadEnginesManifest } from '../engines/registry';
import type { LoadReport } from '../engines/engine';
import type { BenchProgress, BenchReportView, BenchRunner } from '../ui/Screens';
import expectedText from '../../bench/expected.json?raw';
import { META, makeWorker } from './browser-engines';
import { browserReport, toBenchRows, userAgentMemory } from './browser-report';
import { parseExpected, runBenchmark, toJson } from './core';
import type { BenchSpec } from './core';
import { WorkerTarget } from './worker-target';

/** Three timed games keep the run short enough for a visitor; the node runner takes five. */
export const SCREEN_SPEC: BenchSpec = { plies: 40, nodes: 20000, runs: 3 };

export function createBrowserBenchRunner(spec: BenchSpec = SCREEN_SPEC): BenchRunner {
  return {
    async run(onProgress: (p: BenchProgress) => void, signal: AbortSignal): Promise<BenchReportView> {
      const baseUrl = import.meta.env.BASE_URL;
      const manifest = await loadEnginesManifest(baseUrl + 'engines/engines.json');
      const ids = Object.keys(META).filter((id) => (manifest.engines[id as keyof typeof manifest.engines]?.files.length ?? 0) > 0 && META[id]!.available());
      if (ids.length === 0) throw new Error('no engine can run in this browser');
      const targets: WorkerTarget[] = [];
      try {
        const loads = new Map<string, LoadReport>();
        for (const id of ids) {
          if (signal.aborted) throw new Error('stopped');
          onProgress({ engineLabel: id, run: 0, runs: spec.runs, ply: 0, plies: spec.plies, fraction: 0 });
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
        const reports = await runBenchmark(targets, spec, parseExpected(expectedText), {
          loads,
          shouldStop: () => signal.aborted,
          onGame: ({ engine, run, runs }) => {
            done++;
            onProgress({ engineLabel: engine, run, runs, ply: spec.plies, plies: spec.plies, fraction: done / total });
          },
        });
        if (!reports) throw new Error('stopped');
        const withResources = reports.map((r) => browserReport(r, r.id === 'java-wasmgc'));
        const page = await userAgentMemory(globalThis as { crossOriginIsolated?: boolean }, performance as unknown as Parameters<typeof userAgentMemory>[1]);
        const cores = navigator.hardwareConcurrency;
        const environment = {
          runner: 'browser', userAgent: navigator.userAgent, hardwareConcurrency: cores, secureContext: window.isSecureContext,
          crossOriginIsolated: globalThis.crossOriginIsolated === true, userAgentMemoryBytes: page.bytes, userAgentMemoryNa: page.na,
          date: new Date().toISOString(),
        };
        return {
          rows: toBenchRows(withResources),
          environment: `${navigator.userAgent}, ${cores} logical cores, ${spec.plies} plies, ${spec.nodes} nodes, ${spec.runs} runs`,
          json: toJson(withResources, spec, environment),
        };
      } finally {
        for (const t of targets) t.dispose();
      }
    },
  };
}
