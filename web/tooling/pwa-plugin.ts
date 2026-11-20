// Vite plugin of the installable app (docs/web-ui.md, "Installable app and offline cache"). After `vite build` has written web/dist,
// it hashes every file, sorts them into precache and runtime entries, and bundles src/pwa/sw.ts into dist/sw.js with that manifest
// inlined. No dependency beyond Vite: a hand-written service worker instead of Workbox (docs/supply-chain.md).
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import type { Plugin } from 'vite';
import { REVISION_LENGTH, SHELL } from '../src/pwa/sw-core.ts';
import type { AssetEntry, SwManifest } from '../src/pwa/sw-core.ts';

export interface DistFile {
  /** Relative to the output directory, with forward slashes. */
  path: string;
  bytes: number;
  sha256: string;
}

export interface PwaOptions {
  /** Engines whose files the precache holds, by id of engines.json. The default engine of the app (ADR 0003). */
  precacheEngines: readonly string[];
}

export const SW_FILE = 'sw.js';

/** The engine files of the given ids, from the text of engines.json; an id that is missing is an error (a renamed engine). */
export function engineFiles(enginesJson: string, ids: readonly string[]): Set<string> {
  const parsed = JSON.parse(enginesJson) as { engines?: Record<string, { files?: { path: string }[] }> };
  const out = new Set<string>();
  for (const id of ids) {
    const engine = parsed.engines?.[id];
    if (!engine?.files?.length) throw new Error(`pwa: engines.json has no files for the precached engine ${id}`);
    for (const f of engine.files) out.add(f.path);
  }
  return out;
}

/**
 * Where a file of the build goes. precache: index.html, the bundled code and styles, icons, manifest, engines.json, the files of the
 * precached engines, the race table. runtime: the other engines and the models. skip: the service worker itself, source maps, dot files.
 */
export function classify(path: string, precachedEngineFiles: ReadonlySet<string>): 'precache' | 'runtime' | 'skip' {
  const name = path.split('/').pop() ?? path;
  if (path === SW_FILE || name.startsWith('.') || path.endsWith('.map')) return 'skip';
  if (path === 'engines/engines.json' || precachedEngineFiles.has(path)) return 'precache';
  if (path.startsWith('tables/rows2.')) return 'precache';
  if (path.startsWith('engines/') || path.startsWith('models/') || path.startsWith('tables/')) return 'runtime';
  return 'precache';
}

/** The manifest of a build. Sorted by path, so that the same files give the same version. */
export function buildSwManifest(files: readonly DistFile[], precachedEngineFiles: ReadonlySet<string>): SwManifest {
  const sorted = [...files].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  const precache: AssetEntry[] = [];
  const runtime: AssetEntry[] = [];
  for (const f of sorted) {
    const kind = classify(f.path, precachedEngineFiles);
    if (kind === 'skip') continue;
    (kind === 'precache' ? precache : runtime).push({ url: f.path, revision: f.sha256.slice(0, REVISION_LENGTH), bytes: f.bytes });
  }
  if (!precache.some((e) => e.url === SHELL)) throw new Error(`pwa: the build has no ${SHELL}`);
  for (const path of precachedEngineFiles) {
    if (!precache.some((e) => e.url === path)) throw new Error(`pwa: the precached engine file ${path} is not in the build`);
  }
  const version = createHash('sha256').update(JSON.stringify([precache, runtime])).digest('hex').slice(0, 12);
  return { version, precache, runtime };
}

/** Every file below `dir`, hashed. */
export function scanDist(dir: string): DistFile[] {
  const out: DistFile[] = [];
  const walk = (d: string): void => {
    for (const name of readdirSync(d)) {
      const full = join(d, name);
      if (statSync(full).isDirectory()) walk(full);
      else {
        const bytes = readFileSync(full);
        out.push({ path: relative(dir, full).split(sep).join('/'), bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
      }
    }
  };
  walk(dir);
  return out;
}

const sum = (entries: readonly AssetEntry[]): number => entries.reduce((n, e) => n + e.bytes, 0);

export function pwaPlugin(options: PwaOptions): Plugin {
  let outDir = '';
  let root = '';
  return {
    name: 'marble-lantern-pwa',
    apply: 'build',
    configResolved(config) {
      root = config.root;
      outDir = join(config.root, config.build.outDir);
    },
    async closeBundle() {
      const enginesJson = join(outDir, 'engines', 'engines.json');
      const engines = statSync(enginesJson, { throwIfNoEntry: false })
        ? engineFiles(readFileSync(enginesJson, 'utf8'), options.precacheEngines)
        : (() => {
            // a checkout without engine builds: the site cannot play anyway; the service worker caches what there is
            this.warn('pwa: dist/engines/engines.json is missing; the precache holds no engine');
            return new Set<string>();
          })();
      const manifest = buildSwManifest(scanDist(outDir), engines);
      await build({
        configFile: false,
        root,
        logLevel: 'warn',
        define: { __SW_MANIFEST__: JSON.stringify(manifest) },
        build: {
          outDir,
          emptyOutDir: false,
          copyPublicDir: false,
          target: 'es2022',
          minify: true,
          lib: {
            entry: fileURLToPath(new URL('../src/pwa/sw.ts', import.meta.url)),
            formats: ['iife'],
            name: 'marbleLanternSw',
            fileName: () => SW_FILE,
          },
        },
      });
      console.log(
        `pwa: ${SW_FILE} version ${manifest.version}: precache ${manifest.precache.length} files, ${sum(manifest.precache)} bytes; ` +
          `runtime ${manifest.runtime.length} files, ${sum(manifest.runtime)} bytes`,
      );
    },
  };
}
