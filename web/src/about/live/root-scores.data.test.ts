// Generator and verifier of the golden `about/data/root-scores-mid.json`: the analysis (4,000 nodes per move) of the position after the 30
// plies of eval-mid.json, by the real Java engine (TeaVM Wasm GC) in node. With ABOUT_DATA_WRITE=1 the test writes the file; otherwise it
// checks that the committed file equals what the engine produces now. Regenerate: ABOUT_DATA_WRITE=1 REQUIRE_ENGINE_ASSETS=1 npx vitest run
// src/about/live/root-scores.data.test.ts   (in web/). The C++ engine gives the same scores (engines/analysis.cross.test.ts).
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import evalMid from '../data/eval-mid.json';
import { JavaCore } from '../../engines/java/core';
import { loadJavaModuleInNode } from '../../engines/java/node';
import { GUIDE_NODES } from '../../engines/analysis';
import { DEFAULT_VARIANT } from '../../game/variant';
import { buildRootScores } from './root-scores';

const path = (rel: string): string => fileURLToPath(new URL(`../../../public/${rel}`, import.meta.url));
const GOLDEN = fileURLToPath(new URL('../data/root-scores-mid.json', import.meta.url));
const files = ['engines/java/engine-java.wasm', 'engines/java/engine-java.wasm-runtime.js', 'tables/rows2.bin.gz'];
const present = files.every((f) => existsSync(path(f)));
const write = process.env.ABOUT_DATA_WRITE === '1';

const HISTORY = (evalMid.history as { from: number; to: number }[]).map((m) => m.from | (m.to << 8));

/** The file as written: one line for each top-level key, one line for each move. */
export function formatGolden(file: Record<string, unknown> & { moves: unknown[] }): string {
  const lines = Object.entries(file).map(([k, v]) => {
    if (k !== 'moves') return `  ${JSON.stringify(k)}: ${JSON.stringify(v)}`;
    return `  "moves": [\n${file.moves.map((m) => `    ${JSON.stringify(m)}`).join(',\n')}\n  ]`;
  });
  return `{\n${lines.join(',\n')}\n}\n`;
}

async function produce(): Promise<Record<string, unknown> & { moves: unknown[] }> {
  const core = JavaCore.create(
    await loadJavaModuleInNode({ target: 'wasm-gc', main: path('engines/java/engine-java.wasm'), runtime: path('engines/java/engine-java.wasm-runtime.js') }),
  );
  core.installTable(new Uint8Array(gunzipSync(readFileSync(path('tables/rows2.bin.gz')))), false);
  const analysis = core.analyse(HISTORY, null, GUIDE_NODES, DEFAULT_VARIANT);
  const data = buildRootScores(HISTORY, DEFAULT_VARIANT, analysis, GUIDE_NODES);
  return {
    source: {
      generator: 'root-scores.data.test.ts',
      date: '2026-11-21',
      how: 'Engine.analyse (TeaVM Wasm GC, the browser engine with racelin-r3) on the position after the 30 plies of eval-mid.json, 4000 nodes per move; bands from guides/bands.ts',
      regenerate: 'ABOUT_DATA_WRITE=1 REQUIRE_ENGINE_ASSETS=1 npx vitest run src/about/live/root-scores.data.test.ts (in web/)',
    },
    meta: data.meta,
    history: evalMid.history,
    cells: data.cells,
    moves: data.moves,
  };
}

it('has its engine assets (required when REQUIRE_ENGINE_ASSETS=1)', () => {
  if (process.env.REQUIRE_ENGINE_ASSETS === '1') expect(present, 'build the Java engine and the race table first (plan section 8)').toBe(true);
});

describe.skipIf(!present)('root-scores-mid.json', () => {
  it(write ? 'is written from the real engine' : 'equals what the real engine produces', async () => {
    const produced = await produce();
    const text = formatGolden(produced);
    if (write) {
      writeFileSync(GOLDEN, text);
      return;
    }
    expect(existsSync(GOLDEN), 'the golden file is missing; run with ABOUT_DATA_WRITE=1').toBe(true);
    expect(readFileSync(GOLDEN, 'utf8')).toBe(text);
  }, 60_000);

  it('describes the position of the 30 plies: 54 legal moves, best score first, bands against the best', () => {
    const d = JSON.parse(readFileSync(GOLDEN, 'utf8')) as { meta: { ply: number; legalMoves: number; nodes: number }; moves: { score: number; band: string }[]; cells: number[] };
    expect(d.meta).toMatchObject({ ply: 30, legalMoves: evalMid.meta.legalMoves, nodes: 4000 });
    expect(d.moves).toHaveLength(54);
    expect(d.moves.map((m) => m.score)).toEqual([...d.moves.map((m) => m.score)].sort((a, b) => b - a));
    expect(d.moves[0]!.band).toBe('much-better');
    expect(d.cells).toEqual(evalMid.cells);
  });
});
