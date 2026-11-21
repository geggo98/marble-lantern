// The live V7 run on the real engines (C++ Wasm and TeaVM Wasm GC in node) reproduces the `browser` rows of ab-budget-mid.json exactly:
// depth, score and move at each of the five levels. The golden comes from a fresh AlphaBetaPlayer per level (ExplainerDataTest); the live
// run replays the history through newGame + choose. Skips without the engine files unless REQUIRE_ENGINE_ASSETS=1 (then it fails).
import { existsSync, readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import evalMid from '../data/eval-mid.json';
import golden from '../data/ab-budget-mid.json';
import { CppCore } from '../../engines/cpp/core';
import type { CppModuleFactory } from '../../engines/cpp/core';
import { JavaCore } from '../../engines/java/core';
import { loadJavaModuleInNode } from '../../engines/java/node';
import { DEFAULT_VARIANT } from '../../game/variant';
import { runBudget } from './budget';
import type { BudgetEngine } from './budget';

const path = (rel: string): string => fileURLToPath(new URL(`../../../public/${rel}`, import.meta.url));
const files = [
  'engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm',
  'engines/java/engine-java.js', 'engines/java/engine-java.wasm', 'engines/java/engine-java.wasm-runtime.js',
  'tables/rows2.bin.gz',
];
const present = files.every((f) => existsSync(path(f)));
const required = process.env.REQUIRE_ENGINE_ASSETS === '1';

const table = (): Uint8Array => new Uint8Array(gunzipSync(readFileSync(path('tables/rows2.bin.gz'))));
const cppFactory: CppModuleFactory = async () =>
  ((await import(/* @vite-ignore */ pathToFileURL(path('engines/cpp/engine-cpp.js')).href)) as { default: CppModuleFactory }).default();

/** The history of the example position, in the engine's move encoding (from | to << 8). */
const HISTORY = (evalMid.history as { from: number; to: number }[]).map((m) => m.from | (m.to << 8));
const LEVELS = golden.meta.levels;

/** The async face of a synchronous core, as the worker client presents it. */
const asEngine = (core: CppCore | JavaCore): BudgetEngine => ({
  newGame: async (setup) => core.newGame(setup),
  choose: async (history, _signal, variant) => core.choose(history, variant),
});

it('has its engine assets (required when REQUIRE_ENGINE_ASSETS=1)', () => {
  if (required) expect(present, 'build the engines and tables first (plan section 8)').toBe(true);
});

describe.skipIf(!present)('live V7 on the example position', () => {
  const make = {
    'C++ Wasm': async (): Promise<CppCore | JavaCore> => {
      const core = await CppCore.create(cppFactory);
      core.installTable(table(), false);
      return core;
    },
    'TeaVM Wasm GC': async (): Promise<CppCore | JavaCore> => {
      const core = JavaCore.create(
        await loadJavaModuleInNode({ target: 'wasm-gc', main: path('engines/java/engine-java.wasm'), runtime: path('engines/java/engine-java.wasm-runtime.js') }),
      );
      core.installTable(table(), false);
      return core;
    },
  };
  for (const [name, create] of Object.entries(make)) {
    it(`${name}: the five levels give the depth, score and move of ab-budget-mid.json`, async () => {
      expect(HISTORY).toHaveLength(30);
      const core = await create();
      const out = await runBudget(asEngine(core), HISTORY, DEFAULT_VARIANT, LEVELS);
      expect(out.race).toBe(false);
      expect(out.rows).toEqual(golden.browser);
      expect(out.rows.map((r) => r.depth)).toEqual([3, 4, 6, 8, 10]);
    }, 120_000);
  }
});
