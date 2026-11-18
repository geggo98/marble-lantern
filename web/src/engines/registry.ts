// The adapter registry. Each adapter module (C++ Wasm, TeaVM, server) registers one definition;
// the UI lists the definitions and asks which of them this browser can run.
import type { Engine, EngineId, EngineInfo } from './engine';

/** web/public/engines/engines.json, written by scripts/web/engines-manifest.zsh. */
export interface EngineFile {
  /** Relative to the site root, e.g. "engines/cpp/engine-cpp.wasm". */
  path: string;
  bytes: number;
  gzipBytes: number;
  sha256: string;
}

export interface EnginesManifest {
  v: 1;
  /** git describe of the build. */
  version: string;
  engines: Partial<Record<EngineId, { files: EngineFile[]; binaryBytes: number; binaryGzipBytes: number }>>;
}

export interface EngineContext {
  info: EngineInfo;
  /** Site root with a trailing slash (import.meta.env.BASE_URL), for asset URLs. */
  baseUrl: string;
  files: EngineFile[];
}

export interface EngineDefinition {
  id: EngineId;
  labelKey: string;
  runtime: EngineInfo['runtime'];
  language: EngineInfo['language'];
  /** Feature detection. Must not throw. */
  isAvailable(): boolean;
  create(context: EngineContext): Engine;
}

export interface EngineChoice {
  definition: EngineDefinition;
  info: EngineInfo;
  /** Feature check passed and the build shipped the files. */
  available: boolean;
  reason?: 'unsupported' | 'not-shipped';
}

const definitions = new Map<EngineId, EngineDefinition>();

export function registerEngine(definition: EngineDefinition): void {
  definitions.set(definition.id, definition);
}

/** For tests. */
export function clearEngines(): void {
  definitions.clear();
}

/** The default engine of the offline mode comes first, then the fallback chain (ADR 0003); the rest keep registration order. */
const ORDER: EngineId[] = ['java-wasmgc', 'cpp-wasm', 'java-js'];

export function listDefinitions(): EngineDefinition[] {
  const all = [...definitions.values()];
  const rank = (id: EngineId): number => {
    const i = ORDER.indexOf(id);
    return i < 0 ? ORDER.length : i;
  };
  return all.sort((a, b) => rank(a.id) - rank(b.id));
}

/**
 * True if the browser runs WebAssembly with garbage collection. The probe module declares one struct type,
 * which a Wasm MVP engine rejects.
 */
export function wasmGcSupported(): boolean {
  try {
    // magic, version, then a type section with one entry: struct type with no fields (0x5f 0x00)
    const probe = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x03, 0x01, 0x5f, 0x00]);
    return typeof WebAssembly === 'object' && WebAssembly.validate(probe);
  } catch {
    return false;
  }
}

export async function loadEnginesManifest(url: string, doFetch: typeof fetch = fetch): Promise<EnginesManifest> {
  const response = await doFetch(url);
  if (!response.ok) throw new Error(`engines.json: HTTP ${response.status}`);
  const value = (await response.json()) as Partial<EnginesManifest>;
  if (value.v !== 1 || typeof value.version !== 'string' || typeof value.engines !== 'object' || value.engines === null) {
    throw new Error('engines.json has an unexpected shape');
  }
  return value as EnginesManifest;
}

/** Joins the definitions with the manifest: what can this browser run and what did the build ship. */
export function describeEngines(manifest: EnginesManifest): EngineChoice[] {
  return listDefinitions().map((definition) => {
    const entry = manifest.engines[definition.id];
    const info: EngineInfo = {
      id: definition.id,
      labelKey: definition.labelKey,
      runtime: definition.runtime,
      language: definition.language,
      version: manifest.version,
      binaryBytes: entry?.binaryBytes ?? 0,
      binaryGzipBytes: entry?.binaryGzipBytes ?? 0,
    };
    const shipped = definition.runtime === 'server' || (entry?.files.length ?? 0) > 0;
    if (!shipped) return { definition, info, available: false, reason: 'not-shipped' as const };
    if (!definition.isAvailable()) return { definition, info, available: false, reason: 'unsupported' as const };
    return { definition, info, available: true };
  });
}

export function createEngine(choice: EngineChoice, manifest: EnginesManifest, baseUrl: string): Engine {
  if (!choice.available) throw new Error(`engine ${choice.info.id} is not available (${choice.reason})`);
  const files = manifest.engines[choice.info.id]?.files ?? [];
  return choice.definition.create({ info: choice.info, baseUrl, files });
}
