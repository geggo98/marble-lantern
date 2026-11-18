// Loads one engine adapter in node, timed like the worker's LoadReport. Used by the benchmark child process (child.ts).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gunzipSync } from 'node:zlib';
import type { LoadReport } from '../src/engines/engine';
import { CppCore } from '../src/engines/cpp/core';
import type { CppModuleFactory } from '../src/engines/cpp/core';
import { JavaCore } from '../src/engines/java/core';
import { loadJavaModuleInNode } from '../src/engines/java/node';
import type { EnginesManifest } from '../src/engines/registry';
import { parseManifest, verifyTable } from '../src/engines/tables';
import type { BenchTarget } from '../src/bench/core';

const now = (): number => performance.now();

export interface Loaded { target: BenchTarget; load: LoadReport }

export async function loadEngine(manifest: EnginesManifest, publicDir: string, id: string): Promise<Loaded> {
  const entry = manifest.engines[id as keyof typeof manifest.engines];
  if (!entry) throw new Error(`engine ${id} is not in engines.json`);
  const file = (suffix: string): string | undefined => entry.files.find((f) => f.path.endsWith(suffix))?.path;
  const abs = (path: string | undefined): string => {
    if (!path) throw new Error(`engine ${id} lacks a file`);
    return join(publicDir, path);
  };
  const t0 = now();

  // The table like the page gets it: gzip, inflate, length, magic and SHA-256 against the manifest.
  const tablesDir = join(publicDir, 'tables');
  const tFetch = now();
  const tm = parseManifest(readFileSync(join(tablesDir, 'rows2.manifest.json'), 'utf8'));
  const gz = readFileSync(join(tablesDir, tm.file));
  const fetchMs = now() - tFetch;
  const tInflate = now();
  const raw = new Uint8Array(gunzipSync(gz));
  const inflateMs = now() - tInflate;
  const tVerify = now();
  await verifyTable(raw, tm, globalThis.crypto.subtle);
  const verifyMs = now() - tVerify;

  const tInst = now();
  let install: (verify: boolean) => number;
  let play: BenchTarget['bench'];
  if (id === 'cpp-wasm') {
    const factory = ((await import(/* @vite-ignore */ pathToFileURL(abs(file('.js'))).href)) as { default: CppModuleFactory }).default;
    const core = await CppCore.create(factory);
    install = (verify) => core.installTable(raw, verify);
    play = (plies, nodes) => core.bench(plies, nodes);
  } else if (id === 'java-wasmgc' || id === 'java-js') {
    const assets = id === 'java-js'
      ? { target: 'js' as const, main: abs(file('engine-java.js')) }
      : { target: 'wasm-gc' as const, main: abs(file('.wasm')), runtime: abs(file('-runtime.js')) };
    const core = JavaCore.create(await loadJavaModuleInNode(assets));
    install = (verify) => core.installTable(raw, verify);
    play = (plies, nodes) => core.bench(plies, nodes);
  } else {
    throw new Error(`the node runner cannot load engine ${id}`);
  }
  const instantiateMs = now() - tInst;
  const tableInstallMs = install(false); // already verified above, like the page does with crypto.subtle
  const load: LoadReport = { fetchMs, inflateMs, verifyMs, instantiateMs, tableInstallMs, totalMs: now() - t0, tableVerifiedBy: 'subtle-sha256' };
  const runtime = id === 'cpp-wasm' ? 'wasm' : id === 'java-wasmgc' ? 'wasm-gc' : 'js';
  return {
    load,
    target: {
      id,
      info: { runtime, language: id === 'cpp-wasm' ? 'C++' : 'Java', binaryBytes: entry.binaryBytes, binaryGzipBytes: entry.binaryGzipBytes, version: manifest.version },
      bench: play,
    },
  };
}
