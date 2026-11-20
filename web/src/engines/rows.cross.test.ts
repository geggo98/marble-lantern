// The 5-row board on the real engines (pattern: camps.cross.test.ts): C++ Wasm, TeaVM Wasm GC, TeaVM JS and the net facade give the same
// legal moves and outcomes for seeded random games in the four variants (2 and 3 players, both jump rules), the TypeScript move generator
// of the tests agrees, and the three move-limit tables (variant.ts, shw_move_limit of C++, the limit of the TeaVM engine, seen as the
// ply at which a running game becomes a draw) are equal. Skipped when an engine build is missing or too old (web/public/engines is
// gitignored; rebuild with scripts/web/build-wasm.zsh, build-teavm.zsh and build-teavm-net.zsh).
import { existsSync, readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { goalHouses } from '../board/cells';
import { replay } from '../game/board-state';
import { moveLimit } from '../game/variant';
import type { Variant } from '../game/variant';
import type { EngineOutcome } from './engine';
import { CppCore } from './cpp/core';
import type { CppModule, CppModuleFactory } from './cpp/core';
import { JavaCore } from './java/core';
import { loadJavaModuleInNode } from './java/node';
import { loadWebNetInNode } from './net/node';
import { tinyLegalMoves } from './testing/tiny-moves';
import { rowsArg } from './variant-args';

const publicDir = fileURLToPath(new URL('../../public', import.meta.url));
const path = (rel: string): string => `${publicDir}/${rel}`;
const files = [
  'engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm',
  'engines/java/engine-java.js', 'engines/java/engine-java.wasm', 'engines/java/engine-java.wasm-runtime.js',
  'engines/net/engine-net.js',
];
const present = files.every((f) => existsSync(path(f)));
const tableGz = path('tables/rows2.bin.gz');
// the 4-row adjacent two-player game needs the race table to start; the 5-row board never does
const haveTable = existsSync(tableGz);

const cppFactory: CppModuleFactory = async () =>
  ((await import(/* @vite-ignore */ pathToFileURL(path('engines/cpp/engine-cpp.js')).href)) as { default: CppModuleFactory }).default();

interface Core {
  newGame?(setup: { nodes: readonly number[]; variant?: Variant }): void;
  choose?(history: readonly number[], variant?: Variant): { move: number; score: number; depth: number; nodes: number };
  legalMoves(history: readonly number[], variant?: Variant): number[];
  outcome(history: readonly number[], variant?: Variant): EngineOutcome;
}

async function engines(): Promise<{ name: string; core: Core; cpp?: CppModule }[]> {
  const java = async (target: 'wasm-gc' | 'js'): Promise<Core> => {
    const assets =
      target === 'js'
        ? { target, main: path('engines/java/engine-java.js') }
        : { target, main: path('engines/java/engine-java.wasm'), runtime: path('engines/java/engine-java.wasm-runtime.js') };
    return JavaCore.create(await loadJavaModuleInNode(assets)) as unknown as Core;
  };
  const cpp = await cppFactory();
  const webnet = await loadWebNetInNode(publicDir, 'js');
  // the net facade in its random-play role: legal moves and the outcome, 5 rows included
  const numbers = (r: string): number[] => (r.startsWith('ok ') ? r.slice(3).split(',').filter((x) => x !== '').map(Number) : (() => { throw new Error(r); })());
  const net: Core = {
    legalMoves: (h, v = { jump: 'adjacent', players: 2 }) => numbers(webnet.legalMoves(h.join(','), v.jump, String(v.players), v.camps ?? 'closed', rowsArg(v)).replace(/^ok ([^ ]*).*$/, 'ok $1')),
    outcome: (h, v = { jump: 'adjacent', players: 2 }) => {
      const r = webnet.outcome(h.join(','), v.jump, String(v.players), v.camps ?? 'closed', rowsArg(v));
      const f = r.split(' ');
      if (f[0] !== 'ok') throw new Error(r);
      return f[1] === 'ongoing' ? { kind: 'ongoing' } : f[1] === 'draw' ? { kind: 'draw' } : { kind: 'win', winner: Number(f[2]) as 0 | 1 | 2 };
    },
  };
  const all = [
    { name: 'cpp-wasm', core: (await CppCore.create(async () => cpp)) as unknown as Core, cpp },
    { name: 'java-wasmgc', core: await java('wasm-gc') },
    { name: 'java-js', core: await java('js') },
    { name: 'webnet', core: net },
  ];
  if (haveTable) {
    const bytes = new Uint8Array(gunzipSync(readFileSync(tableGz)));
    for (const { core, name } of all) if (name !== 'webnet') (core as unknown as { installTable(b: Uint8Array, verify: boolean): number }).installTable(bytes, false);
  }
  return all;
}

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

/** The leave-goal filter of the default rules: a piece in its goal house stays in it. */
function tsMoves(history: readonly number[], variant: Variant): number[] {
  const owner = replay(history, variant);
  const side = history.length % variant.players;
  const goal = new Set(goalHouses(variant.players, variant.rows ?? 4)[side]);
  return tinyLegalMoves(owner, side, variant).filter((m) => !goal.has(m & 0xff) || goal.has(m >> 8));
}

const VARIANTS: Variant[] = [];
for (const rows of [4, 5] as const) for (const players of [2, 3] as const) for (const jump of ['adjacent', 'long'] as const) VARIANTS.push({ jump, players, rows });
const label = (v: Variant): string => `${v.players} players, ${v.rows} rows, ${v.jump} jumps`;

describe.skipIf(!present)('5-row board: the engines and the TypeScript generator agree', () => {
  for (const variant of VARIANTS) {
    const skip = !haveTable && variant.rows === 4 && variant.players === 2 && variant.jump === 'adjacent';
    it.skipIf(skip)(`${label(variant)}: the same legal moves and outcomes over a seeded random game`, async () => {
      const all = await engines();
            const next = random(7000 + variant.players * 10 + (variant.jump === 'long' ? 1 : 0) + variant.rows! * 100);
      const history: number[] = [];
      for (let ply = 0; ply < 80; ply++) {
        const lists = all.map(({ core }) => sorted(core.legalMoves(history, variant)));
        for (const [i, l] of lists.entries()) expect(l, `${all[i]!.name}, ply ${ply}`).toEqual(lists[0]);
        expect(sorted(tsMoves(history, variant)), `TypeScript, ply ${ply}`).toEqual(lists[0]);
        const outcomes = all.map(({ core }) => core.outcome(history, variant));
        for (const o of outcomes) expect(o).toEqual({ kind: 'ongoing' });
        history.push(lists[0]![Math.floor(next() * lists[0]!.length)]!);
      }
    }, 120_000);

    it.skipIf(skip)(`${label(variant)}: the move limit of variant.ts, C++ and TeaVM is the same, and a game at the limit is a draw`, async () => {
      const all = await engines();
      const limit = moveLimit(variant);
      const cpp = all[0]!.cpp!;
      cpp._shw_game_new5(variant.jump === 'long' ? 1 : 0, variant.players, 0, 0, variant.rows!);
      expect(cpp._shw_move_limit()).toBe(limit);
      expect(cpp._shw_triangle_rows()).toBe(variant.rows);
      // a seeded random game: no one wins in it, so the limit is what ends it
      const next = random(31 + limit);
      const history: number[] = [];
      while (history.length < limit) {
        const moves = all[0]!.core.legalMoves(history, variant);
        history.push(moves[Math.floor(next() * moves.length)]!);
      }
      expect(history).toHaveLength(limit);
      for (const { name, core } of all) {
        expect(core.outcome(history.slice(0, limit - 1), variant), `${name} one ply before the limit`).toEqual({ kind: 'ongoing' });
        expect(core.outcome(history, variant), `${name} at the limit`).toEqual({ kind: 'draw' });
      }
    }, 120_000);
  }

  // The cores keep players by node budget; a player of one house size must never be handed to a game of the other (shw_choose says -2).
  for (const players of [2, 3] as const) {
    for (const jump of ['adjacent', 'long'] as const) {
      it.skipIf(!haveTable)(`${players} players, ${jump} jumps: C++ and TeaVM choose the same move on 5 rows, also after a 4-row game with the same budget`, async () => {
        const all = (await engines()).filter((e) => e.name !== 'webnet');
        const four: Variant = { jump, players, rows: 4 };
        const five: Variant = { jump, players, rows: 5 };
        const results: { move: number; score: number; depth: number; nodes: number }[][] = [];
        for (const { core } of all) {
          const rows: typeof results[number] = [];
          for (const variant of [four, five, four, five]) {
            core.newGame!({ nodes: [300, 300, 300], variant });
            const history: number[] = [];
            for (let ply = 0; ply < 6; ply++) {
              const c = core.choose!(history, variant);
              history.push(c.move);
              if (variant.rows === 5) rows.push({ move: c.move, score: c.score, depth: c.depth, nodes: c.nodes });
            }
          }
          results.push(rows);
        }
        expect(results[0]).toHaveLength(12);
        for (const [i, r] of results.entries()) expect(r, all[i]!.name).toEqual(results[0]);
      }, 120_000);
    }
  }
});
