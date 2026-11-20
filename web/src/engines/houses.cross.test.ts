// The house sets of the web (board/cells.ts) against the C++ engine: the start position of shw_game_new5 for every player count and
// house size owns exactly the cells of initialOwners(variant). Skipped when the engine build is missing or too old to know the house
// size (web/public/engines is gitignored; rebuild it with scripts/web/build-wasm.zsh).
import { existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { goalHouses } from '../board/cells';
import { initialOwners } from '../game/board-state';
import type { BoardRows } from '../board/cells';
import type { Variant } from '../game/variant';

const path = (rel: string): string => fileURLToPath(new URL(`../../public/${rel}`, import.meta.url));
const present = ['engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm'].every((f) => existsSync(path(f)));

interface RawModule {
  _shw_game_new5(jump: number, players: number, ranking: number, camps: number, rows: number): number;
  _shw_triangle_rows(): number;
  _shw_owner(cell: number): number;
  _shw_cell_count(): number;
}

async function load(): Promise<RawModule | null> {
  const factory = ((await import(/* @vite-ignore */ pathToFileURL(path('engines/cpp/engine-cpp.js')).href)) as { default: () => Promise<RawModule> }).default;
  const m = await factory();
  return typeof m._shw_game_new5 === 'function' ? m : null;
}

describe.skipIf(!present)('house sets against the C++ engine', async () => {
  const m = present ? await load() : null;
  const cases: { players: 2 | 3; rows: BoardRows; jump: 'adjacent' | 'long' }[] = [];
  for (const players of [2, 3] as const) for (const rows of [4, 5] as const) for (const jump of ['adjacent', 'long'] as const) cases.push({ players, rows, jump });

  it.skipIf(m === null)('has the engine of this build', () => {
    expect(m!._shw_cell_count()).toBe(121);
  });

  for (const { players, rows, jump } of cases) {
    it.skipIf(m === null)(`start owners equal initialOwners: ${players} players, ${rows} rows, ${jump} hop`, () => {
      const variant: Variant = { jump, players, rows };
      expect(m!._shw_game_new5(jump === 'long' ? 1 : 0, players, 0, 0, rows)).toBe(0);
      expect(m!._shw_triangle_rows()).toBe(rows);
      const engine = Array.from({ length: 121 }, (_, c) => m!._shw_owner(c));
      expect(engine).toEqual(Array.from(initialOwners(variant)));
      // 15 (10) pieces per seat, so the TS houses have the engine's piece count
      for (let seat = 0; seat < players; seat++) expect(engine.filter((o) => o === seat)).toHaveLength(rows === 5 ? 15 : 10);
    });
  }

  it.skipIf(m === null)('goal houses are the start houses of the opposite seat in two players', () => {
    for (const rows of [4, 5] as const) {
      m!._shw_game_new5(0, 2, 0, 0, rows);
      const owner = Array.from({ length: 121 }, (_, c) => m!._shw_owner(c));
      expect(goalHouses(2, rows)[0]!.every((c) => owner[c] === 1)).toBe(true);
      expect(goalHouses(2, rows)[1]!.every((c) => owner[c] === 0)).toBe(true);
    }
  });
});
