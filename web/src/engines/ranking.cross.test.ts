// The turn order of a full-ranking game in TypeScript (game/board-state.ts, playState) against the real engines: over whole
// seeded three-player games in both jump rules, every ply gives the same seats that are out as Engine.outcome reports, the
// legal moves of the engine are moves of the seat that playState says is to move, and the end gives the same ranking.
// The test needs the built engines (devenv tasks run web:engines); without them it is skipped.
import { existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { applyMove, completeRanking, initialOwners, playState } from '../game/board-state';
import type { Variant } from '../game/variant';
import { moveFrom } from './engine';
import type { EngineOutcome } from './engine';
import { CppCore } from './cpp/core';
import type { CppModuleFactory } from './cpp/core';
import { JavaCore } from './java/core';
import { loadJavaModuleInNode } from './java/node';

const path = (rel: string): string => fileURLToPath(new URL(`../../public/${rel}`, import.meta.url));
const present = ['engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm', 'engines/java/engine-java.js'].every((f) => existsSync(path(f)));
const cppFactory: CppModuleFactory = async () =>
  ((await import(/* @vite-ignore */ pathToFileURL(path('engines/cpp/engine-cpp.js')).href)) as { default: CppModuleFactory }).default();

interface Core {
  newGame(setup: { nodes: readonly number[]; variant?: Variant }): void;
  legalMoves(history: readonly number[], variant?: Variant): number[];
  choose(history: readonly number[], variant?: Variant): { move: number };
  outcome(history: readonly number[], variant?: Variant): EngineOutcome;
}

const maskOf = (finished: readonly number[]): number => finished.reduce((m, p) => m | (1 << p), 0);

/** Plays one game with `core` (node budgets per seat = the "seed") and checks every ply against playState. */
function checkGame(core: Core, variant: Variant, nodes: readonly number[], label: string): { length: number; sawOut: boolean } {
  core.newGame({ nodes, variant });
  const history: number[] = [];
  let sawOut = false;
  for (let ply = 0; ply < 320; ply++) {
    const state = playState(history, variant);
    const verdict = core.outcome(history, variant);
    if (verdict.kind === 'ranking') {
      if (state.finishers.length >= 2) expect(verdict.ranking, `${label}: ranking`).toEqual(completeRanking(state.finishers));
      else expect(verdict.ranking.slice(0, state.finishers.length), `${label}: finished seats keep their ranks`).toEqual(state.finishers);
      return { length: ply, sawOut };
    }
    expect(verdict.kind, `${label}: ply ${ply}`).toBe('ongoing');
    const engineMask = verdict.kind === 'ongoing' ? maskOf(verdict.finished ?? []) : 0;
    expect(maskOf(state.finishers), `${label}: seats out at ply ${ply}`).toBe(engineMask);
    if (engineMask) sawOut = true;
    const legal = core.legalMoves(history, variant);
    const owner = initialOwners(variant);
    for (const m of history) applyMove(owner, m);
    for (const m of legal) expect(owner[moveFrom(m)], `${label}: the engine moves seat ${state.sideToMove} at ply ${ply}`).toBe(state.sideToMove);
    history.push(core.choose(history, variant).move);
  }
  throw new Error(`${label}: no ranking within 320 plies`);
}

describe.skipIf(!present)('playState against the engines: whole three-player games', () => {
  // the seeds are node budgets per seat; every combination is a different game
  const seeds: readonly (readonly number[])[] = [[60, 80, 100], [100, 60, 80], [200, 100, 50]];
  for (const jump of ['adjacent', 'long'] as const) {
    const variant: Variant = { jump, players: 3, ranking: 'full' };
    it(`${jump} jumps, C++: same seat to move and same seats out at every ply, same ranking`, async () => {
      const core = (await CppCore.create(cppFactory)) as unknown as Core;
      let out = 0;
      for (const [i, nodes] of seeds.entries()) {
        if (checkGame(core, variant, nodes, `${jump}#${i}`).sawOut) out++;
      }
      expect(out, 'at least one game went through the continued phase').toBeGreaterThan(0);
    }, 300_000);

    it(`${jump} jumps, Java (JS): the same check`, async () => {
      const module = await loadJavaModuleInNode({ target: 'js', main: path('engines/java/engine-java.js') });
      const core = JavaCore.create(module) as unknown as Core;
      expect(checkGame(core, variant, seeds[0]!, `${jump} java`).sawOut).toBe(true);
    }, 300_000);
  }
});
