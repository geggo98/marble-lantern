// The precache manifest of the build, and the web app manifest and icons in web/public.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { buildSwManifest, classify, engineFiles } from './pwa-plugin';
import type { DistFile } from './pwa-plugin';

const ENGINES = JSON.stringify({
  engines: {
    'java-wasmgc': { files: [{ path: 'engines/java/engine-java.wasm' }, { path: 'engines/java/engine-java.wasm-runtime.js' }] },
    'cpp-wasm': { files: [{ path: 'engines/cpp/engine-cpp.js' }, { path: 'engines/cpp/engine-cpp.wasm' }] },
  },
});
const DEFAULT = engineFiles(ENGINES, ['java-wasmgc']);
const file = (path: string, sha = `${path.length}`.padEnd(64, 'a')): DistFile => ({ path, bytes: path.length, sha256: sha });

const DIST: DistFile[] = [
  'index.html', 'sw.js', 'manifest.webmanifest', 'favicon.svg', 'icons/icon-192.png', 'assets/index-x.js', 'assets/worker-y.js', 'assets/index.js.map',
  'engines/engines.json', 'engines/java/engine-java.wasm', 'engines/java/engine-java.wasm-runtime.js', 'engines/java/engine-java.js',
  'engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm', 'engines/net/engine-net.wasm',
  'tables/rows2.bin.gz', 'tables/rows2.manifest.json', 'models/models.json', 'models/a.safetensors.gz', '.DS_Store', 'icons/.DS_Store',
].map((p) => file(p));

describe('classify', () => {
  it('precaches the shell, code, icons, engines.json, the default engine and the race table; the rest waits for first use', () => {
    const got = Object.fromEntries(DIST.map((f) => [f.path, classify(f.path, DEFAULT)]));
    expect(got).toEqual({
      'index.html': 'precache', 'sw.js': 'skip', 'manifest.webmanifest': 'precache', 'favicon.svg': 'precache', 'icons/icon-192.png': 'precache',
      'assets/index-x.js': 'precache', 'assets/worker-y.js': 'precache', 'assets/index.js.map': 'skip',
      'engines/engines.json': 'precache', 'engines/java/engine-java.wasm': 'precache', 'engines/java/engine-java.wasm-runtime.js': 'precache',
      'engines/java/engine-java.js': 'runtime', 'engines/cpp/engine-cpp.js': 'runtime', 'engines/cpp/engine-cpp.wasm': 'runtime',
      'engines/net/engine-net.wasm': 'runtime', 'tables/rows2.bin.gz': 'precache', 'tables/rows2.manifest.json': 'precache',
      'models/models.json': 'runtime', 'models/a.safetensors.gz': 'runtime', '.DS_Store': 'skip', 'icons/.DS_Store': 'skip',
    });
  });
});

describe('buildSwManifest', () => {
  it('gives the same version for the same files in any order, and another one when one byte changes', () => {
    const a = buildSwManifest(DIST, DEFAULT);
    expect(buildSwManifest([...DIST].reverse(), DEFAULT)).toEqual(a);
    const changed = DIST.map((f) => (f.path === 'engines/cpp/engine-cpp.wasm' ? { ...f, sha256: 'b'.repeat(64) } : f));
    expect(buildSwManifest(changed, DEFAULT).version).not.toBe(a.version);
    expect(a.version).toMatch(/^[0-9a-f]{12}$/);
  });

  it('takes the first 16 hex digits of the SHA-256 as the revision, and keeps the size', () => {
    const m = buildSwManifest([file('index.html', `${'0123456789abcdef'}${'f'.repeat(48)}`)], new Set());
    expect(m.precache).toEqual([{ url: 'index.html', revision: '0123456789abcdef', bytes: 10 }]);
  });

  it('refuses a build without index.html or without a file of the precached engine', () => {
    expect(() => buildSwManifest(DIST.filter((f) => f.path !== 'index.html'), DEFAULT)).toThrow(/no index\.html/);
    expect(() => buildSwManifest(DIST.filter((f) => f.path !== 'engines/java/engine-java.wasm'), DEFAULT)).toThrow(/engine-java\.wasm/);
  });
});

describe('engineFiles', () => {
  it('names the files of the given engines and refuses an id that engines.json does not have', () => {
    expect([...engineFiles(ENGINES, ['cpp-wasm'])]).toEqual(['engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm']);
    expect(() => engineFiles(ENGINES, ['java-wasm-gc'])).toThrow(/java-wasm-gc/);
  });

  it('still names the default engine in the engines.json of this checkout (a renamed engine would precache nothing)', () => {
    const text = (() => {
      try {
        return readFileSync(fileURLToPath(new URL('../public/engines/engines.json', import.meta.url)), 'utf8');
      } catch {
        return null; // web/public/engines is build output; a fresh checkout has none
      }
    })();
    if (text === null) return;
    expect(engineFiles(text, ['java-wasmgc']).size).toBeGreaterThan(0);
  });
});

describe('web app manifest and icons in web/public', () => {
  const pub = (p: string): string => fileURLToPath(new URL(`../public/${p}`, import.meta.url));
  const manifest = JSON.parse(readFileSync(pub('manifest.webmanifest'), 'utf8')) as Record<string, unknown> & {
    icons: { src: string; sizes: string; type: string; purpose: string }[];
  };
  /** Width and height from the IHDR chunk of a PNG. */
  const pngSize = (p: string): string => {
    const b = readFileSync(pub(p));
    expect(b.subarray(0, 8).toString('hex'), p).toBe('89504e470d0a1a0a');
    return `${b.readUInt32BE(16)}x${b.readUInt32BE(20)}`;
  };

  it('stays relative to its own URL, so it works under any base path', () => {
    for (const key of ['id', 'start_url', 'scope']) expect(manifest[key], key).toBe('./');
    for (const icon of manifest.icons) expect(icon.src.startsWith('/'), icon.src).toBe(false);
    expect(new URL(manifest.start_url as string, 'https://example.org/marble-lantern/manifest.webmanifest').href).toBe('https://example.org/marble-lantern/');
  });

  it('has the fields an install needs, with the colours of the light theme', () => {
    expect(manifest).toMatchObject({ name: 'Marble Lantern', display: 'standalone', lang: 'en', theme_color: '#f5f0e6', background_color: '#f5f0e6' });
    expect(readFileSync(fileURLToPath(new URL('../design/tokens.css', import.meta.url)), 'utf8')).toContain('--ml-color-bg: #f5f0e6;');
  });

  it('lists PNG icons of 192 and 512 pixels and a maskable one, each as large as it claims', () => {
    const png = manifest.icons.filter((i) => i.type === 'image/png');
    expect(png.map((i) => `${i.sizes}/${i.purpose}`).sort()).toEqual(['192x192/any', '512x512/any', '512x512/maskable']);
    for (const icon of png) expect(pngSize(icon.src), icon.src).toBe(icon.sizes);
    expect(pngSize('icons/apple-touch-icon.png')).toBe('180x180');
  });
});
