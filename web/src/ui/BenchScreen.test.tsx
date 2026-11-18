// @vitest-environment jsdom
// The benchmark screen with a fake runner: the new CPU and memory columns, "n/a" with its reason, and the footnotes.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { BenchScreen, benchNotes } from './Screens';
import type { BenchReportView, BenchRow } from './Screens';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const MIB = 1024 * 1024;
const row = (over: Partial<BenchRow>): BenchRow => ({
  engineLabel: 'cpp-wasm', fingerprintOk: true, nodesPerSecond: 1_000_000, msPerMove: 10, spread: 1.01, loadMs: 40, binaryBytes: 277_000, binaryGzipBytes: 105_000, rows1Requests: 0,
  cpuMs: null, cpuNa: 'no-cpu-api', memoryBytes: 32 * MIB, memoryKind: 'wasm-linear-memory', memoryNa: null, ...over,
});
const view = (rows: BenchRow[]): BenchReportView => ({ rows, environment: 'test', json: '{}' });

let host: HTMLElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

async function showResult(rows: BenchRow[]): Promise<void> {
  await act(async () => {
    root.render(<BenchScreen runner={{ run: () => Promise.resolve(view(rows)) }} />);
  });
  await act(async () => {
    host.querySelector<HTMLButtonElement>('.ml-btn--primary')!.click();
  });
}

describe('BenchScreen CPU and memory columns', () => {
  it('shows a number where there is one and n/a with the reason as tooltip where there is none', async () => {
    await showResult([
      row({}),
      row({ engineLabel: 'java-wasmgc', memoryBytes: null, memoryKind: null, memoryNa: 'wasm-gc-heap' }),
    ]);
    const cells = (label: string): HTMLElement[] => [...host.querySelectorAll<HTMLElement>(`td[data-label="${label}"]`)];
    const [cpu0, cpu1] = cells('CPU time (ms)');
    expect(cpu0!.textContent).toBe('n/a');
    expect(cpu0!.querySelector('[title]')!.getAttribute('title')).toMatch(/CPU time of a worker/);
    const [mem0, mem1] = cells('Peak memory (MiB)');
    expect(mem0!.textContent).toBe('32');
    expect(mem1!.textContent).toBe('n/a');
    expect(mem1!.querySelector('[title]')!.getAttribute('title')).toMatch(/GC engine/);
  });

  it('shows measured CPU time as a number, never "n/a"', async () => {
    await showResult([row({ cpuMs: 2198.4, cpuNa: null })]);
    expect(host.querySelector('td[data-label="CPU time (ms)"]')!.textContent).toBe('2,198');
  });

  it('prints a footnote for each reason and memory kind, with the engines they apply to', async () => {
    await showResult([row({}), row({ engineLabel: 'java-js', memoryBytes: null, memoryKind: null, memoryNa: 'no-js-heap-api' })]);
    const notes = [...host.querySelectorAll('.ml-bench__notes li')].map((li) => li.textContent);
    expect(notes).toContain('cpp-wasm, java-js: A web page has no way to read the CPU time of a worker.');
    expect(notes.some((t) => t!.startsWith('cpp-wasm: Size of the WebAssembly memory'))).toBe(true);
    expect(notes.some((t) => t!.startsWith('java-js: A worker has no performance.memory'))).toBe(true);
  });
});

describe('benchNotes', () => {
  it('lists nothing when every figure is measured', () => {
    expect(benchNotes([row({ cpuMs: 1, cpuNa: null, memoryKind: null, memoryBytes: 1 })])).toEqual([]);
  });
});
