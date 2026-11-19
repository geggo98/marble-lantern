// Web Worker glue of the net engines: connects the request handler to postMessage. Started as a module worker
// (`new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' })`).
import { toEngineError } from '../protocol';
import type { Request, WorkerOut } from '../protocol';
import { NetRequestHandler } from './handler';

// The page's tsconfig types `self` as a Window; in a worker it is a DedicatedWorkerGlobalScope.
const scope = self as unknown as {
  postMessage(message: WorkerOut, transfer?: Transferable[]): void;
  onmessage: ((event: { data: Request }) => void) | null;
};

const handler = new NetRequestHandler();
// Requests run one after the other, in arrival order.
let queue: Promise<void> = Promise.resolve();

scope.onmessage = (event) => {
  const request = event.data;
  queue = queue.then(async () => {
    try {
      const result = await handler.handle(request, (progress) => scope.postMessage({ id: request.id, type: 'progress', progress }));
      scope.postMessage({ id: request.id, ok: true, result });
    } catch (e) {
      scope.postMessage({ id: request.id, ok: false, error: toEngineError(e) });
    }
  });
};
