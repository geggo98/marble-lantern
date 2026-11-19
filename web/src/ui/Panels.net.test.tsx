// @vitest-environment jsdom
// The test hooks of the net engines in the DOM: data-net-backend on the telemetry of the last engine move (what really played it) and
// data-net-warnings on the engine card (the codes of the session warnings). The e2e specs read them to tell GPU play from the CPU
// fallback. They carry no secrets and change no layout.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import type { ChooseResult, EngineInfo } from '../engines/engine';
import { EngineCard, Telemetry } from './Panels';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  document.body.innerHTML = '';
});

async function render(node: React.JSX.Element): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  await act(async () => {
    createRoot(host).render(node);
  });
}

const result = (net?: ChooseResult['net']): ChooseResult =>
  ({ move: 1, score: 0, depth: 0, nodes: 10, ms: 5, raceMove: false, rows1Requests: 0, ...(net ? { net } : {}) }) as ChooseResult;
const info = (id: string): EngineInfo => ({ id, labelKey: 'engine.cppWasm', runtime: 'wasm', language: 'C++', version: 'v', binaryBytes: 1, binaryGzipBytes: 1 }) as EngineInfo;

describe('data-net-backend on the telemetry', () => {
  it.each(['wasm', 'webgpu', 'random'] as const)('names the backend %s of the last net move', async (backend) => {
    await render(<Telemetry result={result({ backend, sims: 50, batches: 3 })} stale={false} />);
    expect(document.querySelector('.ml-telemetry')!.getAttribute('data-net-backend')).toBe(backend);
  });

  it('is absent for a classical engine move', async () => {
    await render(<Telemetry result={result()} stale={false} />);
    expect(document.querySelector('.ml-telemetry')!.hasAttribute('data-net-backend')).toBe(false);
  });
});

describe('data-net-warnings on the engine card', () => {
  it('lists the warning codes of a net engine, comma separated; empty string for none', async () => {
    await render(<EngineCard info={info('net-webgpu')} level={0} onChange={() => undefined} warnings={[{ code: 'backend-unavailable' }, { code: 'fallback-random' }]} />);
    expect(document.querySelector('.ml-engine')!.getAttribute('data-net-warnings')).toBe('backend-unavailable,fallback-random');
    document.body.innerHTML = '';
    await render(<EngineCard info={info('net-wasm')} level={0} onChange={() => undefined} warnings={[]} />);
    expect(document.querySelector('.ml-engine')!.getAttribute('data-net-warnings')).toBe('');
  });

  it('is absent for a classical engine', async () => {
    await render(<EngineCard info={info('cpp-wasm')} level={0} onChange={() => undefined} warnings={[]} />);
    expect(document.querySelector('.ml-engine')!.hasAttribute('data-net-warnings')).toBe(false);
  });
});
