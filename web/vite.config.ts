import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { pwaPlugin } from './tooling/pwa-plugin.ts'; // with extension: Vite's native config loader needs it

// GitHub Pages serves a project site under /<repo-name>/; the build sets BASE_PATH=/marble-lantern/.
export default defineConfig({
  base: process.env.BASE_PATH ?? '/',
  // pwaPlugin runs on `vite build` only: it writes dist/sw.js with the precache manifest (docs/web-ui.md, "Installable app and offline cache").
  // java-wasmgc is the default engine (ADR 0003), so its files are precached; the other engines are cached on first use.
  plugins: [react(), pwaPlugin({ precacheEngines: ['java-wasmgc'] })],
  // Compile-time constant: the offline (static) build never contains the Hilla client.
  define: { __ONLINE__: 'false' },
  worker: { format: 'es' },
  build: { target: 'es2022', sourcemap: false },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'bench/**/*.test.ts', 'tooling/**/*.test.ts'],
    testTimeout: 30_000, // a loaded CI runner needs more than the default 5 s for the engine tests that hash the 12 MB table
  },
});
