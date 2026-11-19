// Dedicated worker of web/e2e/webgpu-backend.spec.ts: runs a harness function and posts its result or its error.
import { harness, type HarnessCall } from './harness';

self.onmessage = async (e: MessageEvent<HarnessCall>) => {
  try {
    const fn = harness[e.data.fn] as (...a: unknown[]) => Promise<unknown>;
    (self as unknown as Worker).postMessage({ result: await fn(...e.data.args) });
  } catch (err) {
    (self as unknown as Worker).postMessage({ error: String(err), stack: (err as Error).stack });
  }
};
