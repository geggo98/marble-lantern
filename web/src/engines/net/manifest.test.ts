// The engines.json of a build holds both net groups with every file a net engine can need, the CPU backend included: net-webgpu falls
// back to it when WebGPU is missing. (An e2e run found a net-webgpu group without it: the fallback then had no backend.)
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
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

  it('each group carries downloadGzipBytes: facade (wasm + runtime) and the relaxed flavour, less than all files', () => {
    for (const id of ['net-wasm', 'net-webgpu'] as const) {
      const g = manifest.engines[id]!;
      const gz = (name: string): number => g.files.find((f) => f.path === `engines/net/${name}`)!.gzipBytes;
      const typical = gz('engine-net.wasm') + gz('engine-net.wasm-runtime.js') + gz('engine-net-nn-relaxed.js') + gz('engine-net-nn-relaxed.wasm');
      expect(g.downloadGzipBytes).toBe(typical);
      expect(g.downloadGzipBytes!).toBeLessThan(g.binaryGzipBytes);
    }
  });
});

// The script itself, on a scratch tree with small fake files (it finds its root from its own path).
const script = resolve(import.meta.dirname, '../../../../scripts/web/engines-manifest.zsh');
const NET_FILES = [
  'engine-net.wasm', 'engine-net.wasm-runtime.js', 'engine-net.js',
  ...['scalar', 'simd', 'relaxed'].flatMap((f) => [`engine-net-nn-${f}.js`, `engine-net-nn-${f}.wasm`]),
];
const scratch: string[] = [];
afterEach(() => { for (const d of scratch.splice(0)) rmSync(d, { recursive: true, force: true }); });

function tree(omit: string[] = []): string {
  const root = mkdtempSync(join(tmpdir(), 'engines-manifest-'));
  scratch.push(root);
  mkdirSync(join(root, 'scripts/web'), { recursive: true });
  copyFileSync(script, join(root, 'scripts/web/engines-manifest.zsh'));
  for (const [dir, files] of [['cpp', ['engine-cpp.js', 'engine-cpp.wasm']], ['net', NET_FILES]] as const) {
    mkdirSync(join(root, 'web/public/engines', dir), { recursive: true });
    for (const f of files) if (!omit.includes(f)) writeFileSync(join(root, 'web/public/engines', dir, f), `fake ${f} `.repeat(50));
  }
  return root;
}
const run = (root: string, strict: boolean): { status: number | null; stderr: string; manifest: EnginesManifest | null } => {
  const r = spawnSync('zsh', [join(root, 'scripts/web/engines-manifest.zsh')], { encoding: 'utf8', env: { ...process.env, ENGINES_STRICT: strict ? '1' : '' } });
  const out = join(root, 'web/public/engines/engines.json');
  return { status: r.status, stderr: r.stderr, manifest: existsSync(out) ? (JSON.parse(readFileSync(out, 'utf8')) as EnginesManifest) : null };
};

describe('scripts/web/engines-manifest.zsh and the net groups', () => {
  it('a complete tree gives both net groups with downloadGzipBytes', () => {
    const r = run(tree(), true);
    expect(r.status).toBe(0);
    expect(Object.keys(r.manifest!.engines).sort()).toEqual(['cpp-wasm', 'net-wasm', 'net-webgpu']);
    expect(r.manifest!.engines['net-wasm']!.downloadGzipBytes).toBeGreaterThan(0);
  });

  it('a missing net file is a warning: the net groups are dropped, the other engines stay, exit 0', () => {
    const r = run(tree(['engine-net-nn-simd.wasm']), false);
    expect(r.status).toBe(0);
    expect(r.stderr).toMatch(/warning: net-wasm has 8 file\(s\), expected 9/);
    expect(Object.keys(r.manifest!.engines)).toEqual(['cpp-wasm']);
  });

  it('with ENGINES_STRICT=1 (CI) the same tree fails', () => {
    const r = run(tree(['engine-net-nn-simd.wasm']), true);
    expect(r.status).not.toBe(0);
    expect(r.stderr).toMatch(/net-(wasm|webgpu) has 8 file/);
  });

  it('no net files at all: a warning, and an error in strict mode', () => {
    const none = tree(NET_FILES);
    expect(run(none, false).status).toBe(0);
    expect(run(tree(NET_FILES), true).status).not.toBe(0);
  });
});
