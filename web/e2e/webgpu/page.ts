// Page of web/e2e/webgpu-backend.spec.ts. window.run(fn, args, inWorker) runs a harness function on the main thread or in a Worker.
import { harness, type HarnessCall } from './harness';

function runInWorker(call: HarnessCall): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const w = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
    const timer = setTimeout(() => { w.terminate(); reject(new Error('worker timeout after 300 s')); }, 300_000);
    w.onmessage = (e: MessageEvent<{ result?: unknown; error?: string; stack?: string }>) => {
      clearTimeout(timer);
      w.terminate();
      if (e.data.error !== undefined) reject(new Error(`${e.data.error}\n${e.data.stack ?? ''}`));
      else resolve(e.data.result);
    };
    w.onerror = (e) => { clearTimeout(timer); reject(new Error(`worker error: ${e.message}`)); };
    w.postMessage(call);
  });
}

async function run(fn: HarnessCall['fn'], args: unknown[], inWorker: boolean): Promise<unknown> {
  if (inWorker) return runInWorker({ fn, args });
  return (harness[fn] as (...a: unknown[]) => Promise<unknown>)(...args);
}

(window as unknown as { run: typeof run }).run = run;
document.getElementById('o')!.textContent = 'ready';
