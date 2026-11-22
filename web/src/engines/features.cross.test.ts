// The features request (Engine.features, plan section 3.4 of the live Info figures) on the three browser engines, through their request
// handlers as the worker runs them: C++ Wasm (shw_features), TeaVM Wasm GC and TeaVM JS (Web.features). The three give identical
// reports along a played game, the report of the eval-mid.json position equals that golden (written by ExplainerDataTest from the JVM
// evaluator, an independent source), features between moves leave every choose result as it was, and the errors follow the protocol.
// Skipped when an engine build or the table is missing, like the other real-engine tests; under REQUIRE_ENGINE_ASSETS=1 it fails instead.
import { existsSync, readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import evalMid from '../about/data/eval-mid.json';
import { goalHouses } from '../board/cells';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Variant } from '../game/variant';
import type { ChooseResult, FeatureReport } from './engine';
import { makeMove } from './engine';
import { CppCore } from './cpp/core';
import type { CppModule, CppModuleFactory } from './cpp/core';
import { CppRequestHandler } from './cpp/handler';
import { JavaCore } from './java/core';
import type { JavaModule } from './java/core';
import { JavaRequestHandler } from './java/handler';
import { loadJavaModuleInNode } from './java/node';
import type { Request } from './protocol';
import { playGame } from './testing/analysis-suite';

const path = (rel: string): string => fileURLToPath(new URL(`../../public/${rel}`, import.meta.url));
const files = [
  'engines/cpp/engine-cpp.js', 'engines/cpp/engine-cpp.wasm',
  'engines/java/engine-java.js', 'engines/java/engine-java.wasm', 'engines/java/engine-java.wasm-runtime.js',
  'tables/rows2.bin.gz', 'tables/rows2.manifest.json',
];
const present = files.every((f) => existsSync(path(f)));
const required = process.env.REQUIRE_ENGINE_ASSETS === '1';

const table = (): ArrayBuffer => new Uint8Array(gunzipSync(readFileSync(path('tables/rows2.bin.gz')))).buffer;
const cppFactory: CppModuleFactory = async () =>
  ((await import(/* @vite-ignore */ pathToFileURL(path('engines/cpp/engine-cpp.js')).href)) as { default: CppModuleFactory }).default();
/** Serves the table manifest only: the handlers get the table bytes handed over and check them against it. */
const manifestFetch = (async () => new Response(readFileSync(path('tables/rows2.manifest.json'), 'utf8'))) as unknown as typeof fetch;

interface Handler {
  handle(request: Request): Promise<unknown>;
}

/** A request without its id (Omit over each member of the union). */
type Body = Request extends infer R ? (R extends Request ? Omit<R, 'id'> : never) : never;

let nextId = 1;
const send = (h: Handler, request: Body): Promise<unknown> => h.handle({ ...request, id: nextId++ } as Request);
const features = async (h: Handler, history: readonly number[], variant: Variant = DEFAULT_VARIANT): Promise<FeatureReport> =>
  (await send(h, { type: 'features', history: [...history], variant })) as FeatureReport;

async function handlers(): Promise<{ name: string; handler: Handler }[]> {
  const assets = (engine: string[]): Extract<Request, { type: 'init' }>['assets'] =>
    ({ engine, manifest: 'rows2.manifest.json', tableGz: 'rows2.bin.gz' }) as Extract<Request, { type: 'init' }>['assets'];
  const cpp = new CppRequestHandler({ importFactory: async () => cppFactory, fetch: manifestFetch, subtle: globalThis.crypto.subtle });
  await cpp.handle({ id: nextId++, type: 'init', assets: assets([path('engines/cpp/engine-cpp.js')]), table: table() } as Request);
  const java = async (target: 'wasm-gc' | 'js'): Promise<Handler> => {
    const h = new JavaRequestHandler({ loadModule: loadJavaModuleInNode, fetch: manifestFetch, subtle: globalThis.crypto.subtle });
    const urls = target === 'js'
      ? [path('engines/java/engine-java.js')]
      : [path('engines/java/engine-java.wasm'), path('engines/java/engine-java.wasm-runtime.js')];
    await h.handle({ id: nextId++, type: 'init', assets: assets(urls), table: table() } as Request);
    return h;
  };
  return [
    { name: 'cpp-wasm', handler: cpp },
    { name: 'java-wasmgc', handler: await java('wasm-gc') },
    { name: 'java-js', handler: await java('js') },
  ];
}

const midHistory = evalMid.history.map((m) => makeMove(m.from, m.to));

describe('an engine file built before the features export (an older cached file)', () => {
  it('gives unsupported with the hint to reload, not a TypeError', async () => {
    const cpp = await CppCore.create(async () => ({}) as CppModule);
    expect(() => cpp.features([])).toThrow(expect.objectContaining({ code: 'unsupported', message: expect.stringContaining('reload') }));
    const java = JavaCore.create({} as JavaModule);
    expect(() => java.features([])).toThrow(expect.objectContaining({ code: 'unsupported', message: expect.stringContaining('reload') }));
  });
});

it.runIf(required)('the engine files of the features cross test are present (REQUIRE_ENGINE_ASSETS=1)', () => {
  expect(files.filter((f) => !existsSync(path(f)))).toEqual([]);
});

describe.skipIf(!present)('features gives identical reports in C++ Wasm, TeaVM Wasm GC and TeaVM JS', () => {
  it('along a played game, and for the eval-mid.json position equal to that golden', async () => {
    const all = await handlers();
    // the game: the C++ core in node, as in analysis.cross.test.ts
    const core = await CppCore.create(cppFactory);
    core.installTable(new Uint8Array(table()), false);
    const { history, racePly } = playGame(core, 300);
    expect(racePly).toBeGreaterThan(40);
    const positions = [0, 3, 12, 40, racePly, history.length - 1];
    const cases = [...positions.map((ply) => ({ label: `ply ${ply}`, h: history.slice(0, ply) })), { label: 'eval-mid', h: midHistory }];
    const sides = new Set<number>();
    for (const { label, h } of cases) {
      const [first, ...rest] = await Promise.all(all.map(({ handler }) => features(handler, h)));
      for (const [i, other] of rest.entries()) expect(other, `${all[i + 1]!.name} against ${all[0]!.name} at ${label}`).toStrictEqual(first);
      expect(first!.features, label).toHaveLength(13);
      expect(first!.distance[0], label).toHaveLength(121);
      expect(first!.distance[1], label).toHaveLength(121);
      expect(first!.sideToMove, label).toBe(h.length % 2);
      sides.add(first!.sideToMove);
    }
    expect(sides).toEqual(new Set([0, 1]));

    // the golden: 13 values rounded to four decimals, the exact score, the distance of every piece of both sides
    const mid = await features(all[0]!.handler, midHistory);
    expect(evalMid.features.map((f) => f.name)).toEqual([
      'dsum', 'dmax', 'dsq', 'inGoal', 'inStart', 'spread', 'lateral', 'jumps', 'adjacent', 'steps', 'dsumPhase', 'tempo', 'bestGain',
    ]);
    evalMid.features.forEach((f, i) => expect(Math.abs(mid.features[i]! - f.value), f.name).toBeLessThanOrEqual(1e-4));
    expect(mid.score).toBe(evalMid.meta.score);
    expect(mid.score).toBe(179);
    expect(mid.sideToMove).toBe(evalMid.meta.sideToMove);
    for (const side of evalMid.sides) {
      for (const piece of side.pieces) expect(mid.distance[side.player as 0 | 1][piece.cell], `player ${side.player}, cell ${piece.cell}`).toBe(piece.distance);
    }
  }, 180_000);

  it('on 5 rows, along a game of the 5-row player: identical in the three engines, with the distances of the 15-cell houses', async () => {
    const all = await handlers();
    const five: Variant = { ...DEFAULT_VARIANT, rows: 5 };
    const first = all[0]!.handler;
    await send(first, { type: 'newGame', setup: { nodes: [1000, 1000], variant: five } });
    const history: number[] = [];
    for (let ply = 0; ply < 80; ply++) history.push(((await send(first, { type: 'choose', history: [...history], variant: five })) as ChooseResult).move);
    for (const ply of [0, 1, 10, 31, 60, 80]) {
      const h = history.slice(0, ply);
      const [a, ...rest] = await Promise.all(all.map(({ handler }) => features(handler, h, five)));
      for (const [i, other] of rest.entries()) expect(other, `${all[i + 1]!.name} against ${all[0]!.name} at ply ${ply}`).toStrictEqual(a);
      expect(a!.sideToMove).toBe(ply % 2);
    }
    // the 5-row layout: the 5 cells that the 15-cell goal house of player 1 adds to the corner have distance 0 there, and more on 4 rows
    const [r4, r5] = [await features(first, []), await features(first, [], five)];
    const added = goalHouses(2, 5)[1]!.filter((cell) => !goalHouses(2, 4)[1]!.includes(cell));
    expect(added).toHaveLength(5);
    for (const cell of added) {
      expect(r5.distance[1][cell], `cell ${cell} on 5 rows`).toBe(0);
      expect(r4.distance[1][cell], `cell ${cell} on 4 rows`).toBeGreaterThan(0);
    }
  }, 180_000);

  it('between the moves of a game, leaves every choose result as it was', async () => {
    const all = await handlers();
    const play = async (h: Handler, withFeatures: boolean): Promise<ChooseResult[]> => {
      await send(h, { type: 'newGame', setup: { nodes: [2000, 2000] } });
      const history: number[] = [];
      const out: ChooseResult[] = [];
      for (let ply = 0; ply < 16; ply++) {
        if (withFeatures) await features(h, history);
        const c = (await send(h, { type: 'choose', history: [...history] })) as ChooseResult;
        out.push(c);
        history.push(c.move);
      }
      return out;
    };
    const strip = (rs: ChooseResult[]) => rs.map(({ move, score, depth, nodes }) => ({ move, score, depth, nodes }));
    for (const { name, handler } of all) {
      const plain = strip(await play(handler, false));
      expect(strip(await play(handler, true)), name).toEqual(plain);
    }
  }, 180_000);

  it('rejects a game other than the classic one on 4 or 5 rows with unsupported and an illegal history with illegal-history', async () => {
    const others: Variant[] = [
      { ...DEFAULT_VARIANT, jump: 'long' },
      { ...DEFAULT_VARIANT, players: 3 },
      { ...DEFAULT_VARIANT, camps: 'open' },
      { ...DEFAULT_VARIANT, camps: 'pass-through' },
      { ...DEFAULT_VARIANT, rows: 5, jump: 'long' },
      { ...DEFAULT_VARIANT, rows: 5, players: 3 },
      { ...DEFAULT_VARIANT, rows: 5, camps: 'open' },
    ];
    for (const { name, handler } of await handlers()) {
      for (const variant of others) {
        await expect(features(handler, [], variant), `${name} ${JSON.stringify(variant)}`).rejects.toMatchObject({ code: 'unsupported' });
      }
      await expect(features(handler, [midHistory[1]!]), name).rejects.toMatchObject({ code: 'illegal-history' });
      // the ranking rule changes nothing for two players
      expect((await features(handler, midHistory, { ...DEFAULT_VARIANT, ranking: 'full' })).score, name).toBe(179);
    }
  }, 120_000);
});
