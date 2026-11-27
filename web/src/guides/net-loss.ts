// The loss of the net on this device, kept for the whole session (page load) outside any React mount. The play screen unmounts and mounts
// again (a new game, a change of the engine); the device does not come back by that, so a new advisor and the hook read the loss from here.
// Written by NetAdvisor when it concludes that the net cannot run; reset only by a reload (and by the tests).
type Listener = () => void;

let reason: string | null = null;
const listeners = new Set<Listener>();

/** The reason code (a key suffix of net.reason.<code>) of the loss, or null while the net has not been lost. */
export const getNetLoss = (): string | null => reason;

/** Records the loss. The first reason stays: a later loss does not replace it. */
export function setNetLoss(next: string): void {
  if (reason !== null) return;
  reason = next;
  for (const l of [...listeners]) l();
}

export function subscribeNetLoss(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** For tests: forgets the loss and the subscribers. */
export function resetNetLoss(): void {
  reason = null;
  listeners.clear();
}
