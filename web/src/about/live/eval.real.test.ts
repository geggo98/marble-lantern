// V6 live against the real C++ Wasm engine: the report of the real `features` export must pass the consistency checks of the adapter, for
// both movers. TO RUN AFTER THE MERGE OF WP2 (the features export): while the engine has no such export, the tests skip with a message.
// A skip is not allowed to hide a broken export: once `features` answers at all, every test runs, and under REQUIRE_ENGINE_ASSETS=1
// missing engine files fail the first test.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import type { ChooseResult, FeatureReport } from '../../engines/engine';
import { CppRequestHandler } from '../../engines/cpp/handler';
import type { CppModuleFactory } from '../../engines/cpp/core';
import { EngineFailure } from '../../engines/protocol';
import { makeMove } from '../../engines/engine';
import { DEFAULT_VARIANT } from '../../game/variant';
import golden from '../data/eval-mid.json';
import { buildEvalData } from './eval';

const publicDir = fileURLToPath(new URL('../../../public/', import.meta.url));
const enginePath = publicDir + 'engines/cpp/engine-cpp.js';
const tableGzPath = publicDir + 'tables/rows2.bin.gz';
const manifestPath = publicDir + 'tables/rows2.manifest.json';
const present = [enginePath, publicDir + 'engines/cpp/engine-cpp.wasm', tableGzPath, manifestPath].every(existsSync);
const required = process.env.REQUIRE_ENGINE_ASSETS === '1';

const factory: CppModuleFactory = async () => ((await import(/* @vite-ignore */ pathToFileURL(enginePath).href)) as { default: CppModuleFactory }).default();
const handler = new CppRequestHandler({
  importFactory: async () => factory,
  subtle: globalThis.crypto.subtle,
  fetch: (async (url: string) => (url === 'file://m' ? new Response(readFileSync(manifestPath)) : new Response(readFileSync(tableGzPath)))) as unknown as typeof fetch,
});
const assets = { engine: ['file://engine-cpp.js'], manifest: 'file://m', tableGz: 'file://t' };

const features = (history: readonly number[]): Promise<FeatureReport> => handler.handle({ id: 0, type: 'features', history: [...history], variant: DEFAULT_VARIANT }) as Promise<FeatureReport>;
const legalCount = async (history: readonly number[]): Promise<number> => ((await handler.handle({ id: 0, type: 'legalMoves', history: [...history] })) as number[]).length;

let exported = false;
const SKIP = 'skipped: the engine has no `features` export yet (WP2); run this test after the WP2 merge, with fresh engine builds';

/** A short game of the engine against itself: the history at plies 0 to `plies`. */
async function play(plies: number): Promise<number[]> {
  await handler.handle({ id: 0, type: 'newGame', setup: { nodes: [300, 300] } });
  const history: number[] = [];
  for (let i = 0; i < plies; i++) history.push(((await handler.handle({ id: 0, type: 'choose', history: [...history] })) as ChooseResult).move);
  return history;
}

beforeAll(async () => {
  if (!present) return;
  await handler.handle({ id: 0, type: 'init', assets, table: null });
  try {
    await features([]);
    exported = true;
  } catch (e) {
    if (!(e instanceof EngineFailure && e.code === 'unsupported')) throw e;
  }
}, 60_000);

describe('the V6 adapter on the real engine', () => {
  it('has its engine assets (required when REQUIRE_ENGINE_ASSETS=1)', () => {
    if (required) expect(present, 'build the engines first: scripts/web/build-wasm.zsh and scripts/web/tables.zsh').toBe(true);
  });

  const guarded = (name: string, body: () => Promise<void>): void => {
    it.skipIf(!present)(name, async (ctx) => {
      if (!exported) {
        console.warn(SKIP);
        ctx.skip(SKIP);
        return;
      }
      await body();
    }, 120_000);
  };

  guarded('the mover-1 positions (odd plies) pass the checks: features are the difference of the sides, the score is the weighted sum', async () => {
    for (const ply of [1, 3, 7]) {
      const history = await play(ply);
      const report = await features(history);
      expect(report.sideToMove, `ply ${ply}`).toBe(1);
      const data = buildEvalData(report, history, DEFAULT_VARIANT, await legalCount(history));
      expect(data.sides[0]!.player).toBe(1);
      expect(data.meta.score).toBe(report.score);
    }
  });

  guarded('the mover-0 positions of the same game pass as well', async () => {
    for (const ply of [0, 2, 8]) {
      const history = await play(ply);
      const report = await features(history);
      expect(report.sideToMove).toBe(0);
      buildEvalData(report, history, DEFAULT_VARIANT, await legalCount(history));
    }
  });

  guarded('the history of eval-mid.json gives the committed sums, marks and the score 179', async () => {
    const g = golden as unknown as { history: { from: number; to: number }[]; sides: unknown; meta: { score: number } };
    const history = g.history.map((m) => makeMove(m.from, m.to));
    const data = buildEvalData(await features(history), history, DEFAULT_VARIANT, await legalCount(history));
    expect(data.meta.score).toBe(179);
    expect(data.sides).toEqual(g.sides);
  });
});
