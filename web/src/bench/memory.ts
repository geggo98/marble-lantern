// Memory sizes an engine adapter can read cheaply, for BenchResult.memory. Works in node, in a worker and on a page.
export function sampleMemory(wasmBytes: number | null): { wasmBytes: number | null; jsHeapBytes: number | null } {
  // Chrome only (also in workers); the property is not in the standard lib types.
  const heap = (globalThis.performance as unknown as { memory?: { usedJSHeapSize?: number } }).memory?.usedJSHeapSize;
  return { wasmBytes, jsHeapBytes: typeof heap === 'number' ? heap : null };
}
