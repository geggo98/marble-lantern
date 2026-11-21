// The live V10 run on the real net engine in node (scalar C++ flavour, so the visits are exact): for the example history the root visits
// after k simulations equal the search with sims = k, the 50-simulation result equals mcts-mid.json, and a model that cannot be used is
// the 'model' error, never a random move. Skips without the net engine files unless REQUIRE_ENGINE_ASSETS=1 (then it fails).
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import evalMid from '../data/eval-mid.json';
import forward from '../data/forward-mid.json';
import mcts from '../data/mcts-mid.json';
import { NetCore } from '../../engines/net/core';
import { loadWebNetInNode, nodeDeps } from '../../engines/net/node';
import { createWasmBackend, importFlavour } from '../../engines/net/wasm-backend';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { ForwardData } from '../figures/forward';
import { buildForwardData } from './forward';
import { ALL_KS, forwardPasses, runMcts } from './mcts';
import type { McstEngine } from './mcts';

const repo = resolve(import.meta.dirname, '../../../..');
const publicDir = process.env['STERNHALMA_PUBLIC_DIR'] ?? join(repo, 'web/public');
const modelsDir = join(repo, 'web/models');
const net = join(publicDir, 'engines/net');
const built = ['engine-net.js', 'engine-net.wasm', 'engine-net.wasm-runtime.js', 'engine-net-nn-scalar.wasm'].every((f) => existsSync(join(net, f)));
const required = process.env['REQUIRE_ENGINE_ASSETS'] === '1';

const HISTORY = (evalMid.history as { from: number; to: number }[]).map((m) => m.from | (m.to << 8));
const MODEL = mcts.meta.model;

async function engine(flavours: ('scalar' | 'simd')[] = ['scalar']): Promise<NetCore> {
  const web = await loadWebNetInNode(publicDir, 'js');
  return new NetCore(
    'wasm',
    nodeDeps(web, {
      publicDir,
      modelsDir,
      createBackend: async (_kind, maxBatch) =>
        createWasmBackend({ maxBatch, flavours, factory: (f) => importFlavour(pathToFileURL(join(net, `engine-net-nn-${f}.js`)).href) }),
    }),
  );
}

it('has its net engine files (required when REQUIRE_ENGINE_ASSETS=1)', () => {
  if (required) expect(built, 'build the engines first (plan section 8)').toBe(true);
});

describe.skipIf(!built)('live V10 on the example position', () => {
  it('after k simulations the root visits equal the search with sims = k; k = 50 is mcts-mid.json (exact visits, Q within 1e-4, same choice)', async () => {
    const core = await engine();
    const t0 = performance.now();
    const live = await runMcts(core as unknown as McstEngine, HISTORY, MODEL, ALL_KS);
    const ms = performance.now() - t0;
    if (process.env['SH_NET_TIMES']) process.stderr.write(`live V10 scalar node: ${forwardPasses(ALL_KS)} forward passes in ${Math.round(ms)} ms\n`);
    expect(live.ks).toEqual([...ALL_KS]);
    for (const [i, k] of live.ks.entries()) expect(live.visits[i]!.reduce((a, b) => a + b, 0), `k = ${k}`).toBe(k);
    // k = 50 against the committed golden
    const last = live.ks.length - 1;
    for (const f of mcts.final) {
      const e = live.moves.indexOf(f.move[0]! | (f.move[1]! << 8));
      expect(e, `move ${f.move.join('->')}`).toBeGreaterThanOrEqual(0);
      expect(live.visits[last]![e], `visits of ${f.move.join('->')}`).toBe(f.N);
      if (f.Q !== null) expect(Math.abs(live.values[last]![e]! - f.Q)).toBeLessThan(1e-4);
    }
    expect(live.choice).toBe(mcts.meta.choice[0]! | (mcts.meta.choice[1]! << 8));
    // the priors of the forward pass are the priors of the search (mcts-mid.json root, 4 decimals)
    mcts.root.moves.forEach((m, e) => {
      const i = live.moves.indexOf(m[0]! | (m[1]! << 8));
      expect(Math.abs(live.priors[i]! - mcts.root.priors[e]!)).toBeLessThan(2e-3);
    });
    // the visits of every move never decrease with k
    for (let i = 1; i < live.ks.length; i++) live.visits[i]!.forEach((v, e) => expect(v).toBeGreaterThanOrEqual(live.visits[i - 1]![e]!));
    core.dispose();
  }, 600_000);

  it('a model that is not in the catalog is the model error, not a random move', async () => {
    const core = await engine();
    await expect(runMcts(core as unknown as McstEngine, HISTORY, 'no-such-model', [1, 2])).rejects.toMatchObject({ code: 'model' });
    core.dispose();
  });

  it('V9b: the figure data of the example position equals forward-mid.json (planes exactly after rounding to 4 decimals, layers and values within 2e-3)', async () => {
    const g = forward as unknown as ForwardData;
    const core = await engine();
    await core.newGame({ nodes: [1, 1], model: MODEL, netOptions: { sims: 1, batch: 1 } });
    const d = buildForwardData(await core.netForward(HISTORY), HISTORY, DEFAULT_VARIANT);
    expect(d.planes.map((p) => p.map((v) => Math.round(v * 1e4) / 1e4))).toEqual(g.planes);
    expect(d.cells).toEqual(g.cells);
    expect(d.meta.legalMoves).toBe(g.meta.legalMoves);
    d.layers.forEach((l, i) => l.norm.forEach((v, c) => expect(Math.abs(v - g.layers[i]!.norm[c]!)).toBeLessThan(2e-3)));
    d.values.forEach((v, i) => expect(Math.abs(v - g.values[i]!)).toBeLessThan(2e-3));
    core.dispose();
  });
});
