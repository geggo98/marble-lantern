// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import type { DistanceEstimate } from './distance';
import { useDistanceEstimate } from './useDistance';
import type { DistanceRun, DistanceState } from './useDistance';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

interface Call {
  signal: AbortSignal;
  resolve: (e: DistanceEstimate | null) => void;
  reject: (e: unknown) => void;
}

let host: HTMLElement;
let root: Root;
let latest: DistanceState;
const calls: Call[] = [];
const run: DistanceRun = (signal) =>
  new Promise((resolve, reject) => {
    calls.push({ signal, resolve, reject });
  });

function Probe({ k }: { k: string | null }): null {
  latest = useDistanceEstimate(k, run);
  return null;
}
async function render(k: string | null): Promise<void> {
  await act(async () => root.render(<Probe k={k} />));
}
const settle = async (fn: () => void): Promise<void> => {
  await act(async () => {
    fn();
    await Promise.resolve();
  });
};
const estimate: DistanceEstimate = { kind: 'solo', plies: 12, capped: false };

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  calls.length = 0;
});
const mount = (): void => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
};

describe('useDistanceEstimate', () => {
  it('runs nothing while no result is shown', async () => {
    mount();
    await render(null);
    expect(latest).toEqual({ status: 'idle' });
    expect(calls).toHaveLength(0);
  });

  it('is "estimating" at once for a result, then done with the estimate', async () => {
    mount();
    await render('win@100');
    expect(latest).toEqual({ status: 'estimating' });
    expect(calls).toHaveLength(1);
    await settle(() => calls[0]!.resolve(estimate));
    expect(latest).toEqual({ status: 'done', estimate });
    expect(calls).toHaveLength(1); // an unchanged key does not start it again
    await render('win@100');
    expect(calls).toHaveLength(1);
  });

  it('aborts the run when the result goes away, and ignores its late answer', async () => {
    mount();
    await render('win@100');
    const first = calls[0]!;
    await render(null); // new game, undo, or the card is closed
    expect(first.signal.aborted).toBe(true);
    await settle(() => first.resolve(estimate));
    expect(latest).toEqual({ status: 'idle' });
  });

  it('aborts the run for another result and shows only the answer of the new one', async () => {
    mount();
    await render('win@100');
    await render('win@120');
    expect(calls).toHaveLength(2);
    expect(calls[0]!.signal.aborted).toBe(true);
    expect(calls[1]!.signal.aborted).toBe(false);
    await settle(() => calls[0]!.resolve({ kind: 'solo', plies: 99, capped: false }));
    expect(latest).toEqual({ status: 'estimating' });
    await settle(() => calls[1]!.resolve(estimate));
    expect(latest).toEqual({ status: 'done', estimate });
  });

  it('aborts when the screen unmounts', async () => {
    mount();
    await render('win@100');
    const first = calls[0]!;
    await act(async () => root.unmount());
    expect(first.signal.aborted).toBe(true);
    root = createRoot(host); // for afterEach
  });

  it('shows nothing and no error when the run fails or has no answer', async () => {
    mount();
    await render('win@100');
    await settle(() => calls[0]!.reject(new Error('engine failed')));
    expect(latest).toEqual({ status: 'idle' });
    await render('win@101');
    await settle(() => calls[1]!.resolve(null));
    expect(latest).toEqual({ status: 'idle' });
  });
});
