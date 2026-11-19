// Dedicated worker: the same suite as the page, to see whether WebGPU works off the main thread.
import { describeGpu, requestDevice, runSuite, type SuiteOptions } from './bench-core.js';

self.onmessage = async (e: MessageEvent<SuiteOptions>) => {
  try {
    const gpu = await describeGpu();
    if (!gpu['available']) {
      (self as unknown as Worker).postMessage({ gpu, error: 'WebGPU not available in the worker' });
      return;
    }
    const device = await requestDevice();
    const result = await runSuite(device, e.data);
    (self as unknown as Worker).postMessage({ gpu, ...result });
  } catch (err) {
    (self as unknown as Worker).postMessage({ error: String(err), stack: (err as Error).stack });
  }
};
