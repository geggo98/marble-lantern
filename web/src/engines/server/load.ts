// The only import of the generated Hilla client. Called by src/app/adapters.ts, and only when __ONLINE__ is true;
// Vite removes the branch from the static build, so the offline bundle contains no endpoint code
// (CI check: `grep -c 'connect/' web/dist/assets/*.js` prints 0 for every file).
import { registerEngine } from '../registry';
import { isDefault } from '../../game/variant';
import { ServerEngineAdapter } from './client';
import type { ServerApi } from './client';

export async function registerServerEngines(): Promise<void> {
  if (!__ONLINE__) return;
  let api: ServerApi;
  try {
    const endpoints = await import('Frontend/generated/endpoints.js');
    api = endpoints.GameEndpoint as unknown as ServerApi;
    const list = (await api.engines()) ?? [];
    for (const entry of list) {
      registerEngine({
        id: `server:${entry.id}`,
        labelKey: entry.labelKey,
        runtime: 'server',
        language: 'Java',
        isAvailable: () => true,
        supportsVariant: isDefault,
        create: () => new ServerEngineAdapter(entry, api),
      });
    }
  } catch (e) {
    // The server does not answer: the page then offers the browser engines only.
    console.warn('server engines are not available:', e);
  }
}
