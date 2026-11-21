// Tests of the TypeScript reference forward pass (reference-forward.ts).
//
// 1. Against the forward goldens of ExplainerDataTest.java (Java scalar reference Network with the shipped expert model):
//    web/src/about/data/forward-{start,mid,race}.json (player 0 to move) and testdata/forward-mover1.json (player 1 to move,
//    the board rotated for the network). The input is rebuilt from the golden planes; these tests need no built engine.
// 2. With the built net engine (web/public/engines/net/): the facade encodes the positions of the goldens that carry a history,
//    and its planes must equal the golden planes; then the heads must equal the C++ scalar backend on 20 positions of a game.
//    Without the files these tests skip and say so, unless REQUIRE_ENGINE_ASSETS=1 is set: then they fail.
// 3. The time of one forward pass of the SMALL net in node, printed to stderr (for the live Info figures).
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import { widenSafetensors } from './models';
import { loadWebNetInNode } from './node';
import { referenceForward, toAbsolute } from './reference-forward';
import type { ReferenceOutputs } from './reference-forward';
import { WasmNetBackend } from './wasm-backend';
import type { NetModuleFactory } from './wasm-backend';

const repo = resolve(import.meta.dirname, '../../../..');
const publicDir = process.env['STERNHALMA_PUBLIC_DIR'] ?? join(repo, 'web/public');
const netDir = join(publicDir, 'engines/net');
const EXPERT = 'small-2p-adjacent-expert';
/** The goldens are written with 4 decimals (ExplainerDataTest.Json); 5e-4 leaves room for the rounded input planes. */
const TOL = 5e-4;
const CELLS = 121;
const ROWS = 122;
const PLANES = 13;

const weights = widenSafetensors(new Uint8Array(gunzipSync(readFileSync(join(repo, 'web/models', `${EXPERT}.safetensors.gz`)))));

interface Golden {
  meta: { position: string; model: string; ply: number; sideToMove: 0 | 1; canonicalRotated: boolean; legalMoves: number };
  planes: number[][];
  layers: { id: string; norm: number[] }[];
  policyLogits: { from: number[]; to: number[] };
  policy: { from: number; to: number; prior: number; priorNetOnly: number }[];
  values: number[];
  valuesNetOnly: number[];
  history?: { from: number; to: number }[];
}

const readGolden = (path: string): Golden => JSON.parse(readFileSync(path, 'utf8')) as Golden;
const GOLDENS: Array<{ name: string; golden: Golden }> = [
  ...['start', 'mid', 'race'].map((p) => ({ name: `forward-${p}.json`, golden: readGolden(join(repo, `web/src/about/data/forward-${p}.json`)) })),
  { name: 'testdata/forward-mover1.json', golden: readGolden(join(import.meta.dirname, 'testdata/forward-mover1.json')) },
];
/** eval-mid.json carries the history of the mid position (forward-mid.json does not). */
const MID_HISTORY = (JSON.parse(readFileSync(join(repo, 'web/src/about/data/eval-mid.json'), 'utf8')) as { history: { from: number; to: number }[] }).history;
const code = (m: { from: number; to: number }): number => m.from | (m.to << 8);

/** The canonical input of a golden, rebuilt from its planes (board cells). */
function inputOf(g: Golden): Float32Array {
  const input = new Float32Array(ROWS * PLANES);
  for (let pl = 0; pl < PLANES; pl++) {
    for (let canonical = 0; canonical < CELLS; canonical++) input[canonical * PLANES + pl] = g.planes[pl]![toAbsolute(canonical, g.meta.sideToMove)]!;
  }
  return input;
}

/** Softmax over the legal moves of the logit from[from] + to[to] (Java Evaluator.decode); moves in board cells. */
function priors(logits: Float32Array, moves: Golden['policy'], mover: 0 | 1): number[] {
  const canon = (cell: number): number => toAbsolute(cell, mover); // the rotation is its own inverse
  const raw = moves.map((m) => logits[canon(m.from) * 2]! + logits[canon(m.to) * 2 + 1]!);
  const max = Math.max(...raw);
  const e = raw.map((v) => Math.exp(v - max));
  const sum = e.reduce((a, b) => a + b, 0);
  return e.map((v) => v / sum);
}

/** The worst |actual - expected| with the index where it occurs, for a message that names the place. */
function worst(actual: ArrayLike<number>, expected: ArrayLike<number>): { diff: number; at: number } {
  let diff = 0;
  let at = -1;
  for (let i = 0; i < expected.length; i++) {
    const d = Math.abs(actual[i]! - expected[i]!);
    if (!(d <= diff)) (diff = d), (at = i);
  }
  return { diff, at };
}

function expectClose(actual: ArrayLike<number>, expected: ArrayLike<number>, tol: number, what: string): void {
  expect(actual.length, `${what}: length`).toBe(expected.length);
  const w = worst(actual, expected);
  expect(w.diff, `${what}: worst difference ${w.diff} at index ${w.at} (actual ${actual[w.at]}, expected ${expected[w.at]})`).toBeLessThanOrEqual(tol);
}

/** Checks every head and layer of `out` against the golden; all golden arrays are indexed by board cell. */
function checkAgainstGolden(out: ReferenceOutputs, g: Golden, name: string): void {
  const mover = g.meta.sideToMove;
  const abs = (canonicalArray: ArrayLike<number>, stride = 1, offset = 0): number[] => {
    const a = new Array<number>(CELLS);
    for (let c = 0; c < CELLS; c++) a[toAbsolute(c, mover)] = canonicalArray[c * stride + offset]!;
    return a;
  };
  expect(out.norms.map((_, i) => (i === 0 ? 'conv_in' : `block${i - 1}`)), `${name}: layer ids`).toEqual(g.layers.map((l) => l.id));
  out.norms.forEach((n, i) => expectClose(abs(n), g.layers[i]!.norm, TOL, `${name}: norm of ${g.layers[i]!.id}`));
  expectClose(abs(out.policyNetOnly, 2, 0), g.policyLogits.from, TOL, `${name}: from logits without the prior`);
  expectClose(abs(out.policyNetOnly, 2, 1), g.policyLogits.to, TOL, `${name}: to logits without the prior`);
  expect(g.policy.length, `${name}: legal moves`).toBe(g.meta.legalMoves);
  expectClose(priors(out.policy, g.policy, mover), g.policy.map((m) => m.prior), TOL, `${name}: priors with the classical prior`);
  expectClose(priors(out.policyNetOnly, g.policy, mover), g.policy.map((m) => m.priorNetOnly), TOL, `${name}: priors without the classical prior`);
  // values[a] of the golden is absolute player a; the net gives relative players, mover first.
  const absValues = (rel: Float32Array): number[] => [0, 1].map((a) => rel[(a - mover + 2) % 2]!);
  expectClose(absValues(out.values), g.values, TOL, `${name}: values`);
  expectClose(absValues(out.valuesNetOnly), g.valuesNetOnly, TOL, `${name}: values without the prior`);
}

describe('toAbsolute', () => {
  it('is the identity for mover 0 and the rotation by 180 degrees for mover 1, its own inverse', () => {
    const seen = new Set<number>();
    for (let c = 0; c < CELLS; c++) {
      expect(toAbsolute(c, 0)).toBe(c);
      const r = toAbsolute(c, 1);
      expect(toAbsolute(r, 1)).toBe(c);
      seen.add(r);
    }
    expect(seen.size).toBe(CELLS);
    // The top corner tip (cell 0, the start of player 0) maps to the bottom tip (cell 120): the ids are ordered by r, then q.
    expect(toAbsolute(0, 1)).toBe(120);
    expect(toAbsolute(60, 1)).toBe(60); // the centre stays
  });
});

describe('referenceForward against the Java forward goldens (expert model)', () => {
  it('covers both movers', () => {
    expect(GOLDENS.map((g) => g.golden.meta.sideToMove)).toEqual([0, 0, 0, 1]);
    expect(GOLDENS[3]!.golden.meta.canonicalRotated).toBe(true);
  });

  for (const { name, golden } of GOLDENS) {
    it(`${name}: layer norms, logits, priors and values within ${TOL}`, () => {
      expect(golden.meta.model).toBe(EXPERT);
      checkAgainstGolden(referenceForward(weights, inputOf(golden)), golden, name);
    });
  }

  it('rejects an input of the wrong length and a padding row that is not zero', () => {
    expect(() => referenceForward(weights, new Float32Array(ROWS * PLANES - 1))).toThrow(/expected 122 x 13 = 1586 input floats, got 1585/);
    const dirty = inputOf(GOLDENS[0]!.golden);
    dirty[CELLS * PLANES + 3] = 1;
    expect(() => referenceForward(weights, dirty)).toThrow(/padding row of the input is not zero in channel 3/);
  });
});

// ---- with the built net engine ----

const built = ['engine-net.js', 'engine-net-nn-scalar.js', 'engine-net-nn-scalar.wasm'].every((f) => existsSync(join(netDir, f)));
const required = process.env['REQUIRE_ENGINE_ASSETS'] === '1';

it('has the net engine assets (required when REQUIRE_ENGINE_ASSETS=1)', () => {
  if (required) expect(built, 'run scripts/web/build-teavm-net.zsh and scripts/web/build-wasm.zsh first').toBe(true);
});

/** Encodes the position after `history` with the real facade: the first pending position of a search is the root. */
async function encoder(): Promise<(history: readonly number[]) => Float32Array> {
  const web = await loadWebNetInNode(publicDir, 'js');
  const ok = (r: string): string => {
    if (!r.startsWith('ok')) throw new Error(`facade: ${r}`);
    return r;
  };
  ok(web.create('adjacent', '2', 1, 1, 1, '', 'closed', '4'));
  ok(web.setWeightsInfo('classic1', 2));
  return (history) => {
    const n = Number(ok(web.begin(history.join(','))).split(' ')[1]);
    if (n !== 1) throw new Error(`facade: begin gave ${n} pending positions, expected 1`);
    return web.inputs().slice();
  };
}

/** HALF_UP to 4 decimals, as ExplainerDataTest.Json writes the planes. */
const round4 = (v: number): number => Math.round(v * 1e4) / 1e4;

describe.skipIf(!built)('referenceForward with the real net facade and the C++ scalar backend', () => {
  const withHistory: Array<{ name: string; golden: Golden; history: number[] }> = [
    { name: 'forward-start.json', golden: GOLDENS[0]!.golden, history: [] },
    { name: 'forward-mid.json', golden: GOLDENS[1]!.golden, history: MID_HISTORY.map(code) },
    { name: 'testdata/forward-mover1.json', golden: GOLDENS[3]!.golden, history: GOLDENS[3]!.golden.history!.map(code) },
  ];

  it('the facade encodes the goldens with a history to their planes, and the heads of the exact input match the golden', async () => {
    const encode = await encoder();
    for (const { name, golden, history } of withHistory) {
      expect(history.length, `${name}: ply`).toBe(golden.meta.ply);
      const input = encode(history);
      const mover = golden.meta.sideToMove;
      for (let pl = 0; pl < PLANES; pl++) {
        const plane = new Array<number>(CELLS);
        for (let c = 0; c < CELLS; c++) plane[toAbsolute(c, mover)] = round4(input[c * PLANES + pl]!);
        expect(plane, `${name}: plane ${pl}`).toEqual(golden.planes[pl]);
      }
      for (let pl = 0; pl < PLANES; pl++) expect(input[CELLS * PLANES + pl], `${name}: padding row, plane ${pl}`).toBe(0);
      checkAgainstGolden(referenceForward(weights, input), golden, `${name} (facade input)`);
    }
  });

  it('equals the C++ scalar backend within 1e-5 on 20 positions of a game, and records the time of one pass', async () => {
    const encode = await encoder();
    // The self-play game of the goldens (ab:eval=distance,nodes=2000): plies 12 to 31, both movers.
    const game = GOLDENS[3]!.golden.history!.map(code);
    const inputs: Float32Array[] = [];
    for (let ply = 12; ply <= 31; ply++) inputs.push(encode(game.slice(0, ply)));
    expect(inputs.length).toBe(20);
    const factory = (): Promise<NetModuleFactory> =>
      import(/* @vite-ignore */ pathToFileURL(join(netDir, 'engine-net-nn-scalar.js')).href).then((m: { default: NetModuleFactory }) => m.default);
    const backend = await WasmNetBackend.create({ flavours: ['scalar'], factory, maxBatch: 1 });
    await backend.load(weights);
    let worstLogit = 0;
    let worstValue = 0;
    for (const [i, input] of inputs.entries()) {
      const ref = referenceForward(weights, input);
      const cpp = await backend.evaluate(input, 1);
      const what = `ply ${12 + i}`;
      const from = Array.from({ length: CELLS }, (_, c) => ref.policy[c * 2]!);
      const to = Array.from({ length: CELLS }, (_, c) => ref.policy[c * 2 + 1]!);
      expectClose(from, cpp.policyFrom, 1e-5, `${what}: from logits`);
      expectClose(to, cpp.policyTo, 1e-5, `${what}: to logits`);
      expectClose(ref.values, cpp.values, 1e-5, `${what}: values`);
      worstLogit = Math.max(worstLogit, worst(from, cpp.policyFrom).diff, worst(to, cpp.policyTo).diff);
      worstValue = Math.max(worstValue, worst(ref.values, cpp.values).diff);
    }
    backend.dispose();

    // Speed: warm up, then the median of 20 passes.
    for (const input of inputs.slice(0, 5)) referenceForward(weights, input);
    const times = inputs.map((input) => {
      const t0 = performance.now();
      referenceForward(weights, input);
      return performance.now() - t0;
    });
    times.sort((a, b) => a - b);
    process.stderr.write(
      `reference forward (${EXPERT}, SMALL net, node ${process.version}): median ${times[10]!.toFixed(2)} ms, max ${times[19]!.toFixed(2)} ms per pass; ` +
        `worst difference to the C++ scalar backend: logits ${worstLogit}, values ${worstValue}\n`,
    );
    expect(times[10]).toBeGreaterThan(0);
  }, 60_000);
});
