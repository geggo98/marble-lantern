// The game variants on the three browser engines (C++ Wasm, TeaVM Wasm GC, TeaVM JS): for a long-hop game and a three-player game
// the engines must give the same moves, scores and node counts for the same history and node budget, and the same outcome.
// Long-hop and three-player play need no race table, so the cores here never get one. Skipped when an engine build is missing
// (like the other real-engine tests).
import { existsSync, readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { replay, outcomeOf } from '../game/board-state';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Variant } from '../game/variant';
import type { ChooseResult, EngineOutcome } from './engine';
import { CppCore } from './cpp/core';
import type { CppModuleFactory } from './cpp/core';
import { JavaCore } from './java/core';
import { loadJavaModuleInNode } from './java/node';
import { EngineFailure } from './protocol';

const path = (rel: string): string => fileURLToPath(new URL(`../../public/${rel}`, import.meta.url));
const files = [
  'engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm',
  'engines/java/engine-java.js', 'engines/java/engine-java.wasm', 'engines/java/engine-java.wasm-runtime.js',
];
const present = files.every((f) => existsSync(path(f)));
const tableGz = path('tables/rows2.bin.gz');
const haveTable = existsSync(tableGz);

const cppFactory: CppModuleFactory = async () =>
  ((await import(/* @vite-ignore */ pathToFileURL(path('engines/cpp/engine-cpp.js')).href)) as { default: CppModuleFactory }).default();

/** The part of the three cores that the variant tests use. */
interface Core {
  newGame(setup: { nodes: readonly number[]; variant?: Variant }): void;
  legalMoves(history: readonly number[], variant?: Variant): number[];
  choose(history: readonly number[], variant?: Variant): ChooseResult;
  outcome(history: readonly number[], variant?: Variant): EngineOutcome;
  analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, variant?: Variant): { scores: number[]; moves: number[]; nodes: number };
  bench(plies: number, nodes: number, variant?: Variant): { moves: number[]; nodes: number[] };
}

/** The three engines; with `withTable` they hold the race table, else none. */
async function engines(withTable = false): Promise<{ name: string; core: Core }[]> {
  const java = async (target: 'wasm-gc' | 'js'): Promise<Core> => {
    const assets =
      target === 'js'
        ? { target, main: path('engines/java/engine-java.js') }
        : { target, main: path('engines/java/engine-java.wasm'), runtime: path('engines/java/engine-java.wasm-runtime.js') };
    return JavaCore.create(await loadJavaModuleInNode(assets)) as unknown as Core;
  };
  const all = [
    { name: 'cpp-wasm', core: (await CppCore.create(cppFactory)) as unknown as Core },
    { name: 'java-wasmgc', core: await java('wasm-gc') },
    { name: 'java-js', core: await java('js') },
  ];
  if (withTable) {
    const bytes = new Uint8Array(gunzipSync(readFileSync(tableGz)));
    for (const { core } of all) (core as unknown as { installTable(b: Uint8Array, verify: boolean): number }).installTable(bytes, false);
  }
  return all;
}

const failureCode = (run: () => unknown): string => {
  try {
    run();
  } catch (e) {
    if (e instanceof EngineFailure) return e.code;
    throw e;
  }
  return 'no error';
};

interface Played {
  history: number[];
  outcome: EngineOutcome;
}

/**
 * Plays a whole game on every engine at once, ply by ply, and checks at each ply that all engines agree on the legal moves, the
 * outcome and the whole choose() result (move, score, depth, nodes), and that the board of the app agrees with the engines on
 * the win. Returns the game of the first engine.
 */
async function playTogether(variant: Variant, nodes: number): Promise<Played> {
  const all = await engines();
  for (const { core } of all) core.newGame({ nodes: [nodes, nodes + 100, nodes + 200], variant });
  const history: number[] = [];
  for (let ply = 0; ply < 400; ply++) {
    const verdicts = all.map(({ core }) => core.outcome(history, variant));
    for (const v of verdicts) expect(v, `outcome at ply ${ply}`).toEqual(verdicts[0]);
    const verdict = verdicts[0]!;
    // the board of the app: a win is the same win, a game that goes on has no local outcome
    const local = outcomeOf(replay(history, variant), history, variant);
    if (verdict.kind === 'win') expect(local, `ply ${ply}`).toEqual({ kind: 'win', winner: verdict.winner });
    if (verdict.kind === 'ongoing') expect(local, `ply ${ply}`).toBeNull();
    if (verdict.kind !== 'ongoing') {
      for (const { core } of all) expect(core.legalMoves(history, variant), 'a finished game has no legal move').toEqual([]);
      return { history, outcome: verdict };
    }
    const legal = all.map(({ core }) => core.legalMoves(history, variant));
    for (const l of legal) expect(l, `legal moves at ply ${ply}`).toEqual(legal[0]);
    const choices = all.map(({ core }) => core.choose(history, variant));
    for (const c of choices) {
      expect({ move: c.move, score: c.score, depth: c.depth, nodes: c.nodes, race: c.raceMove }, `choose at ply ${ply}`).toEqual({
        move: choices[0]!.move, score: choices[0]!.score, depth: choices[0]!.depth, nodes: choices[0]!.nodes, race: false,
      });
    }
    expect(legal[0]).toContain(choices[0]!.move);
    history.push(choices[0]!.move);
  }
  throw new Error('the game did not end within 400 plies');
}

describe.skipIf(!present)('long-hop game: the three engines agree', () => {
  const variant: Variant = { jump: 'long', players: 2 };

  it('plays a whole game with the same moves, scores and nodes, and the same outcome, without a race table', async () => {
    const game = await playTogether(variant, 400);
    expect(game.history.length).toBeGreaterThan(20);
    expect(game.outcome.kind === 'win' || game.history.length >= 200).toBe(true);
  }, 120_000);

  it.skipIf(!haveTable)('a long hop is an illegal move under the adjacent rule on every engine (illegal-history)', async () => {
    const all = await engines(true);
    const first = all[0]!.core;
    first.newGame({ nodes: [300, 300], variant });
    // the longest prefix of the long-hop game that is also an adjacent-rule game, plus the move that breaks it
    const history: number[] = [];
    let found: number[] | null = null;
    for (let ply = 0; ply < 120 && !found; ply++) {
      const move = first.choose(history, variant).move;
      if (!first.legalMoves(history, DEFAULT_VARIANT).includes(move)) found = [...history, move];
      history.push(move);
    }
    expect(found, 'a long-hop game contains a move that the adjacent rule forbids').not.toBeNull();
    for (const { name, core } of all) {
      expect(failureCode(() => core.legalMoves(found!, variant)), `${name}: legal under the long rule`).toBe('no error');
      expect(failureCode(() => core.legalMoves(found!, DEFAULT_VARIANT)), `${name}: adjacent rule`).toBe('illegal-history');
      expect(failureCode(() => core.outcome(found!, DEFAULT_VARIANT)), `${name}: adjacent rule, outcome`).toBe('illegal-history');
    }
  }, 120_000);

  it('analyse works for long hops and gives identical scores on the three engines', async () => {
    const all = await engines();
    const game = await playTogether(variant, 100);
    const h = game.history.slice(0, 12);
    const results = all.map(({ core }) => core.analyse(h, null, 600, variant));
    for (const r of results) {
      expect(r.moves).toEqual(results[0]!.moves);
      expect(r.scores).toEqual(results[0]!.scores);
      expect(r.nodes).toBe(results[0]!.nodes);
    }
    expect(results[0]!.scores.length).toBeGreaterThan(10);
  }, 120_000);

  it('a game of the other jump rule cannot be continued: choose with another variant than newGame is an error', async () => {
    const all = await engines();
    for (const { name, core } of all) {
      core.newGame({ nodes: [100, 100], variant });
      expect(failureCode(() => core.choose([], DEFAULT_VARIANT)), name).not.toBe('no error');
    }
  });
});

describe.skipIf(!present)('three-player game: the three engines agree', () => {
  for (const jump of ['adjacent', 'long'] as const) {
    const variant: Variant = { jump, players: 3 };

    it(`${jump} jumps: a whole game with the same moves, scores and nodes per seat, and the same outcome, without a race table`, async () => {
      const game = await playTogether(variant, 200);
      expect(game.history.length).toBeGreaterThan(30);
      // a win names a seat of the game; a draw is the move limit (300 plies), a repetition or a seat without a legal move
      if (game.outcome.kind === 'win') expect(game.outcome.winner).toBeLessThan(3);
      else expect(game.outcome.kind).toBe('draw');
    }, 240_000);

    it(`${jump} jumps: analyse is unsupported for three players on every engine, and bench plays the same moves`, async () => {
      const all = await engines();
      for (const { name, core } of all) {
        expect(failureCode(() => core.analyse([], null, 100, variant)), `${name} analyse`).toBe('unsupported');
      }
      const benches = all.map(({ core }) => core.bench(9, 150, variant));
      for (const b of benches) {
        expect(b.moves).toEqual(benches[0]!.moves);
        expect(b.nodes).toEqual(benches[0]!.nodes);
      }
      expect(benches[0]!.moves).toHaveLength(9);
    }, 120_000);
  }

  it.skipIf(!haveTable)('a history that is not a three-player history is illegal, and a game keeps its player count', async () => {
    const all = await engines(true);
    const three: Variant = { jump: 'adjacent', players: 3 };
    const game = await playTogether(three, 100);
    const h = game.history.slice(0, 4);
    for (const { name, core } of all) {
      // seat 1 of a three-player game moves from a corner that holds no piece in a two-player game
      expect(failureCode(() => core.legalMoves(h, three)), name).toBe('no error');
      expect(failureCode(() => core.legalMoves(h, DEFAULT_VARIANT)), `${name}: as a two-player game`).toBe('illegal-history');
      core.newGame({ nodes: [100, 100, 100], variant: three });
      expect(failureCode(() => core.choose([], DEFAULT_VARIANT)), `${name}: choose with two players`).not.toBe('no error');
    }
  }, 120_000);
});

describe.skipIf(!present)('two-player adjacent play still needs the table and says so', () => {
  it('refuses newGame, legalMoves and outcome of the default variant before a table is installed, but not the other variants', async () => {
    for (const { name, core } of await engines()) {
      expect(failureCode(() => core.newGame({ nodes: [100, 100] })), name).toBe('internal');
      expect(failureCode(() => core.legalMoves([], DEFAULT_VARIANT)), name).toBe('internal');
      expect(failureCode(() => core.outcome([], DEFAULT_VARIANT)), name).toBe('internal');
      expect(failureCode(() => core.legalMoves([], { jump: 'long', players: 2 })), name).toBe('no error');
      expect(failureCode(() => core.legalMoves([], { jump: 'adjacent', players: 3 })), name).toBe('no error');
    }
  });
});
