// The foreign-corner rule (variant.camps, docs/rules.md "Foreign corners") on the three browser engines (C++ Wasm, TeaVM Wasm GC,
// TeaVM JS) and the TypeScript move generator of the tests (tiny-moves.ts): for seeded random positions under `pass-through` and
// `open`, with both jump rules and two and three players, all four give the same move list. A short engine game checks that the three
// engines also agree on choose() (move, score, nodes) and on the outcome. Skipped when an engine build is missing, like the other
// real-engine tests. The adjacent two-player game needs the race table to start (its players are built with race play, which the
// rule then switches off); those cases are skipped without web/public/tables/rows2.bin.gz.
import { existsSync, readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { goalCells, replay } from '../game/board-state';
import type { ForeignCamps, Variant } from '../game/variant';
import { withCamps } from '../game/variant';
import type { ChooseResult, EngineOutcome } from './engine';
import { CppCore } from './cpp/core';
import type { CppModuleFactory } from './cpp/core';
import { JavaCore } from './java/core';
import { loadJavaModuleInNode } from './java/node';
import { EngineFailure } from './protocol';
import { tinyLegalMoves } from './testing/tiny-moves';

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

interface Core {
  newGame(setup: { nodes: readonly number[]; variant?: Variant }): void;
  legalMoves(history: readonly number[], variant?: Variant): number[];
  choose(history: readonly number[], variant?: Variant): ChooseResult;
  outcome(history: readonly number[], variant?: Variant): EngineOutcome;
}

async function engines(): Promise<{ name: string; core: Core }[]> {
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
  if (haveTable) {
    const bytes = new Uint8Array(gunzipSync(readFileSync(tableGz)));
    for (const { core } of all) (core as unknown as { installTable(b: Uint8Array, verify: boolean): number }).installTable(bytes, false);
  }
  return all;
}

/** The TypeScript move list with the leave-goal filter of the default rules: a piece in its goal corner stays in it. */
function tsMoves(history: readonly number[], variant: Variant): number[] {
  const owner = replay(history, variant);
  const side = history.length % variant.players;
  const goal = new Set(goalCells(variant)[side]);
  return tinyLegalMoves(owner, side, variant).filter((m) => !goal.has(m & 0xff) || goal.has(m >> 8));
}

/** A small seeded generator (the 32-bit mulberry32), so a failure names a reproducible game. */
function random(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const sorted = (moves: readonly number[]): number[] => [...moves].sort((a, b) => a - b);

const RULES: ForeignCamps[] = ['pass-through', 'open'];

describe.skipIf(!present)('foreign corners: the three engines and the TypeScript generator agree', () => {
  for (const camps of RULES) {
    for (const players of [2, 3] as const) {
      for (const jump of ['adjacent', 'long'] as const) {
        const variant = withCamps({ jump, players }, camps);
        it.skipIf(players === 2 && jump === 'adjacent' && !haveTable)(`${camps}, ${players} players, ${jump} jumps: the same move lists over seeded random games`, async () => {
          const all = await engines();
          let foreignEnds = 0;
          for (let game = 0; game < 3; game++) {
            const seed = 1000 * players + 100 * game + (jump === 'long' ? 7 : 0) + (camps === 'open' ? 50 : 0);
            const next = random(seed);
            const history: number[] = [];
            for (let ply = 0; ply < 90; ply++) {
              const outcomes = all.map(({ core }) => core.outcome(history, variant));
              for (const o of outcomes) expect(o, `seed ${seed} ply ${ply}: outcome`).toEqual(outcomes[0]);
              if (outcomes[0]!.kind !== 'ongoing') break;
              const lists = all.map(({ core }) => sorted(core.legalMoves(history, variant)));
              for (const [i, l] of lists.entries()) expect(l, `${all[i]!.name}, seed ${seed} ply ${ply}`).toEqual(lists[0]);
              expect(sorted(tsMoves(history, variant)), `TypeScript, seed ${seed} ply ${ply}`).toEqual(lists[0]);
              const moves = lists[0]!;
              if (moves.length === 0) break;
              const closed = new Set(tsMoves(history, withCamps(variant, 'closed')));
              foreignEnds += moves.filter((m) => !closed.has(m)).length;
              history.push(moves[Math.floor(next() * moves.length)]!);
            }
          }
          // the rule must really add moves, else the comparison proves nothing about it
          expect(foreignEnds).toBeGreaterThan(0);
        }, 120_000);
      }
    }
  }

  it.skipIf(!haveTable)('a short game under open corners: the engines choose the same moves with the same scores and nodes', async () => {
    const all = await engines();
    for (const players of [2, 3] as const) {
      const variant = withCamps({ jump: 'adjacent', players }, 'open');
      for (const { core } of all) core.newGame({ nodes: [300, 300, 300], variant });
      const history: number[] = [];
      for (let ply = 0; ply < 24; ply++) {
        const choices = all.map(({ core }) => core.choose(history, variant));
        for (const c of choices) {
          expect({ move: c.move, score: c.score, nodes: c.nodes }, `${players} players, ply ${ply}`).toEqual({
            move: choices[0]!.move, score: choices[0]!.score, nodes: choices[0]!.nodes,
          });
        }
        expect(all[0]!.core.legalMoves(history, variant)).toContain(choices[0]!.move);
        history.push(choices[0]!.move);
      }
    }
  }, 240_000);

  it.skipIf(!haveTable)('a history that ends on a foreign cell is illegal under the closed rule on every engine', async () => {
    const all = await engines();
    const open = withCamps({ jump: 'adjacent', players: 2 }, 'open');
    const closed = withCamps(open, 'closed');
    const next = random(99);
    const history: number[] = [];
    let found: number[] | null = null;
    for (let ply = 0; ply < 200 && !found; ply++) {
      const moves = all[0]!.core.legalMoves(history, open);
      const foreign = moves.find((m) => !all[0]!.core.legalMoves(history, closed).includes(m));
      if (foreign !== undefined) found = [...history, foreign];
      history.push(moves[Math.floor(next() * moves.length)]!);
    }
    expect(found).not.toBeNull();
    for (const { name, core } of all) {
      let code = 'no error';
      try {
        core.legalMoves(found!, closed);
      } catch (e) {
        if (!(e instanceof EngineFailure)) throw e;
        code = e.code;
      }
      expect(code, name).toBe('illegal-history');
      expect(() => core.legalMoves(found!, open), name).not.toThrow();
    }
  }, 120_000);
});
