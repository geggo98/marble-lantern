// The engines.json of a build holds both net groups with every file a net engine can need, the CPU backend included: net-webgpu falls
// back to it when WebGPU is missing. (An e2e run found a net-webgpu group without it: the fallback then had no backend.)
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { EnginesManifest } from '../registry';
import { pickNetAssets } from './handler';

const path = join(process.env['STERNHALMA_PUBLIC_DIR'] ?? resolve(import.meta.dirname, '../../../public'), 'engines/engines.json');
const built = existsSync(path);
const suite = built ? describe : describe.skip;

suite(built ? 'engines.json net groups' : 'engines.json net groups (SKIPPED: web/public/engines/engines.json is not built)', () => {
  const manifest = JSON.parse(readFileSync(path, 'utf8')) as EnginesManifest;
  const files = (id: 'net-wasm' | 'net-webgpu'): string[] => (manifest.engines[id]?.files ?? []).map((f) => f.path).sort();

  it('net-wasm and net-webgpu list the same nine files', () => {
    expect(files('net-wasm')).toHaveLength(9);
    expect(files('net-webgpu')).toEqual(files('net-wasm'));
  });

  it('each group gives the handler a facade and all three CPU flavours', () => {
    for (const id of ['net-wasm', 'net-webgpu'] as const) {
      const assets = pickNetAssets(files(id));
      expect(assets.facade.target).toBe('wasm-gc');
      expect(assets.facadeJs).toBeDefined();
      expect(Object.keys(assets.flavours).sort()).toEqual(['relaxed', 'scalar', 'simd']);
    }
  });
});
