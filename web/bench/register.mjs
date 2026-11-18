// Lets node run the TypeScript sources of src/ without a build step: node strips the types itself (node 22.18 or newer),
// and this hook resolves the extensionless relative imports ('../engine') to the .ts files, like Vite does.
// Use: node --import ./bench/register.mjs bench/run-node.ts
import { register } from 'node:module';

register('./resolve-hook.mjs', import.meta.url);
