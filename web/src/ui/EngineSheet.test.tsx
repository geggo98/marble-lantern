// @vitest-environment jsdom
// The size line of the engine sheet: a net engine shows the typical download, the others the sum of their files.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import type { EngineChoice } from '../engines/registry';
import { EngineSheet } from './EngineSheet';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const choice = (id: string, extra: Record<string, number>): EngineChoice =>
  ({
    available: true,
    definition: { id },
    info: { id, labelKey: 'engine.cppWasm', runtime: 'wasm', language: 'C++', version: 'v', binaryBytes: 480_000, binaryGzipBytes: 474_000, ...extra },
  }) as unknown as EngineChoice;

afterEach(() => {
  document.body.innerHTML = '';
});

async function sizeText(c: EngineChoice): Promise<string> {
  const host = document.createElement('div');
  document.body.append(host);
  await act(async () => {
    createRoot(host).render(<EngineSheet choices={[c]} currentId={c.info.id} level={0} onApply={() => undefined} onClose={() => undefined} />);
  });
  return document.body.querySelector('.ml-option__meta')!.textContent ?? '';
}

describe('EngineSheet size', () => {
  it('shows downloadGzipBytes when present', async () => {
    const t = await sizeText(choice('net-wasm', { downloadGzipBytes: 228_000 }));
    expect(t).toMatch(/228\D*KB gzip/);
    expect(t).not.toMatch(/474/);
  });

  it('shows the sum of the files when there is no typical download', async () => {
    expect(await sizeText(choice('cpp-wasm', {}))).toMatch(/474\D*KB gzip/);
  });
});
