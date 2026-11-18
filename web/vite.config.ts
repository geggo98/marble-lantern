import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// GitHub Pages serves a project site under /<repo-name>/; the build sets BASE_PATH=/marble-lantern/.
export default defineConfig({
  base: process.env.BASE_PATH ?? '/',
  plugins: [react()],
  // Compile-time constant: the offline (static) build never contains the Hilla client.
  define: { __ONLINE__: 'false' },
  worker: { format: 'es' },
  build: { target: 'es2022', sourcemap: false },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'bench/**/*.test.ts'],
  },
});
