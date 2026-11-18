// The three browser engines share one search (the benchmark fingerprint proves it for choose()). The analysis API must give them
// the same numbers: identical scores, in the same order, for the same position and node budget. Skipped when an engine build or the
// table is missing, like the other real-engine tests (fails under REQUIRE_ENGINE_ASSETS=1 through their own presence checks).
import { existsSync, readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { CppCore } from './cpp/core';
import type { CppModuleFactory } from './cpp/core';
import { JavaCore } from './java/core';
import { loadJavaModuleInNode } from './java/node';
import { playGame } from './testing/analysis-suite';

const path = (rel: string): string => fileURLToPath(new URL(`../../public/${rel}`, import.meta.url));
const files = [
  'engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm',
  'engines/java/engine-java.js', 'engines/java/engine-java.wasm', 'engines/java/engine-java.wasm-runtime.js',
  'tables/rows2.bin.gz',
];
const present = files.every((f) => existsSync(path(f)));

const table = (): Uint8Array => new Uint8Array(gunzipSync(readFileSync(path('tables/rows2.bin.gz'))));
const cppFactory: CppModuleFactory = async () =>
  ((await import(/* @vite-ignore */ pathToFileURL(path('engines/cpp/engine-cpp.js')).href)) as { default: CppModuleFactory }).default();

describe.skipIf(!present)('analyse gives identical scores in C++ Wasm, TeaVM Wasm GC and TeaVM JS', () => {
  it('for the start position, the middle and the end of a game, and for a subset of moves', async () => {
    const cpp = await CppCore.create(cppFactory);
    cpp.installTable(table(), false);
    const java = await Promise.all(
      (['wasm-gc', 'js'] as const).map(async (target) => {
        const assets =
          target === 'js'
            ? { target, main: path('engines/java/engine-java.js') }
            : { target, main: path('engines/java/engine-java.wasm'), runtime: path('engines/java/engine-java.wasm-runtime.js') };
        const core = JavaCore.create(await loadJavaModuleInNode(assets));
        core.installTable(table(), false);
        return core;
      }),
    );

    const { history, racePly } = playGame(cpp, 300);
    const positions = [0, 3, 12, 40, racePly, history.length - 3, history.length - 1];
    let compared = 0;
    for (const ply of positions) {
      for (const nodes of [400, 3000]) {
        const h = history.slice(0, ply);
        const expected = cpp.analyse(h, null, nodes);
        for (const j of java) {
          const got = j.analyse(h, null, nodes);
          expect(got.moves, `ply ${ply}, ${nodes} nodes`).toEqual(expected.moves);
          expect(got.scores, `ply ${ply}, ${nodes} nodes`).toEqual(expected.scores);
          expect(got.position).toBe(expected.position);
          expect(got.nodes).toBe(expected.nodes);
        }
        compared += expected.scores.length;
      }
    }
    // a subset in another order
    const h = history.slice(0, 20);
    const all = cpp.analyse(h, null, 1000).moves;
    const subset = [all[4]!, all[1]!, all[7]!];
    const expected = cpp.analyse(h, subset, 1000);
    for (const j of java) expect(j.analyse(h, subset, 1000)).toMatchObject({ moves: subset, scores: expected.scores });
    // the comparison saw many scores, not an empty list
    expect(compared).toBeGreaterThan(200);
  }, 120_000);
});
