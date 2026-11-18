import path from 'node:path';
import { UserConfigFn } from 'vite';
import { overrideVaadinConfig } from './vite.generated.ts';

// The shared React code lives in ../web/src and has its own node_modules (or none, in CI). Two React copies break
// hooks, so react and react-dom always resolve from this project.
const repoRoot = path.resolve(__dirname, '..');

const customConfig: UserConfigFn = () => ({
  // Compile-time constant: true only in this (online) build. web/vite.config.ts sets it to false.
  define: { __ONLINE__: 'true' },
  resolve: { dedupe: ['react', 'react-dom'] },
  worker: { format: 'es' },
  server: { fs: { allow: [repoRoot] } },
});

export default overrideVaadinConfig(customConfig);
