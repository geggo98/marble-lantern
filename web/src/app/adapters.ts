// Registers the engine adapters of this build and loads engines.json.
//
// Each adapter module registers its definitions with registerEngine() when it is imported:
//   src/engines/cpp/index.ts   (WP7)    src/engines/java/index.ts   (WP6)    src/engines/net/index.ts   (net engines)
// The server adapter (WP8) is loaded only in the online build, from src/engines/server/load.ts, which exports
// registerServerEngines(). Vite removes that branch from the static build (the constant __ONLINE__ is false there).
import { loadEnginesManifest } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';

// Eager: the modules only register definitions; the engine code itself loads in a worker, on demand.
import.meta.glob('../engines/{cpp,java,net}/index.ts', { eager: true });

async function registerServer(): Promise<void> {
  if (!__ONLINE__) return;
  const loaders = import.meta.glob('../engines/server/load.ts');
  for (const load of Object.values(loaders)) {
    const mod = (await load()) as { registerServerEngines?: () => Promise<void> | void };
    await mod.registerServerEngines?.();
  }
}

/** Development only: `?fake` (or `?fake=400` for a 400 ms search) replaces cpp-wasm with the in-process fake engine. */
async function registerFake(): Promise<void> {
  if (!import.meta.env.DEV) return;
  const params = new URLSearchParams(globalThis.location?.search ?? '');
  if (!params.has('fake')) return;
  const delay = Number(params.get('fake')) || 0;
  const { registerFakeEngine } = await import('./dev-fake');
  registerFakeEngine(delay);
}

export async function loadManifest(baseUrl: string): Promise<EnginesManifest> {
  await registerServer();
  await registerFake();
  return loadEnginesManifest(`${baseUrl}engines/engines.json`);
}
