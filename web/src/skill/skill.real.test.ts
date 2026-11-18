// The estimate on the real engine (C++ Wasm in node): a player that always plays the best move of the analysis gets the strongest
// estimate, a player that moves at random gets the weakest. Skipped when the engine build or the table is missing (like the
// other real-engine tests); not skipped in CI, where web:ci builds both first.
import { existsSync, readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { CppCore } from '../engines/cpp/core';
import type { CppModuleFactory } from '../engines/cpp/core';
import { SKILL_NODES, scoreLoss } from '../engines/analysis';
import { CALIBRATION } from './data';
import { estimateSkill } from './calibration';
import { seededRandom } from './stats';

const path = (rel: string): string => fileURLToPath(new URL(`../../public/${rel}`, import.meta.url));
const present = ['engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm', 'tables/rows2.bin.gz'].every((f) => existsSync(path(f)));

/** Plays `moves` moves of side 0 with `policy`; side 1 is the engine at 1,000 nodes. Returns the raw loss of each move of side 0. */
function simulate(core: CppCore, policy: 'best' | 'random', moves: number, seed: number): number[] {
  const random = seededRandom(seed);
  core.newGame({ nodes: [1000, 1000] });
  const history: number[] = [];
  const losses: number[] = [];
  for (let i = 0; i < moves; i++) {
    const a = core.analyse(history, null, SKILL_NODES);
    const at = policy === 'best' ? a.scores.indexOf(a.position) : Math.floor(random() * a.moves.length);
    losses.push(scoreLoss(a.position, a.scores[at]!));
    history.push(a.moves[at]!);
    if (core.legalMoves(history).length === 0) break;
    history.push(core.choose(history).move);
    if (core.legalMoves(history).length === 0) break;
  }
  return losses;
}

describe.skipIf(!present)('skill estimate on the real engine', () => {
  it('the best mover gets the strongest end of the ladder, the random mover the weakest, and the order is right', async () => {
    const factory: CppModuleFactory = async () =>
      ((await import(/* @vite-ignore */ pathToFileURL(path('engines/cpp/engine-cpp.js')).href)) as { default: CppModuleFactory }).default();
    const core = await CppCore.create(factory);
    core.installTable(new Uint8Array(gunzipSync(readFileSync(path('tables/rows2.bin.gz')))), false);

    const bestLosses = simulate(core, 'best', 12, 1);
    const randomLosses = simulate(core, 'random', 12, 2);
    expect(bestLosses.length).toBeGreaterThanOrEqual(8);
    expect(randomLosses.length).toBeGreaterThanOrEqual(8);
    expect(Math.max(...bestLosses)).toBe(0); // the best move of the analysis loses nothing, by definition

    const best = estimateSkill(bestLosses, CALIBRATION);
    const random = estimateSkill(randomLosses, CALIBRATION);
    if (!best.enough || !random.enough) throw new Error('expected estimates');
    expect(best.clamp).toBe('strong');
    expect(random.clamp).toBe('weak');
    expect(best.nodes).toBeGreaterThan(random.nodes);
    expect(random.nodes).toBe(CALIBRATION.ladder[0]!.nodes);
  }, 240_000);
});
