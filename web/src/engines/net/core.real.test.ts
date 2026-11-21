// Integration test of the net engine core with the REAL parts: the TeaVM facade (teavm-net), the C++ WebAssembly backend and the
// shipped models. Needs web/public/engines/net/ (scripts/web/build-teavm-net.zsh and build-wasm.zsh); without it the tests skip
// and say so in their names. Per-move times are printed with SH_NET_TIMES=1.
import { existsSync, readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { Variant } from '../../game/variant';
import { NetCore } from './core';
import { loadWebNetInNode, mulberry32, nodeDeps } from './node';
import type { FacadeTarget } from './node';
import type { WebNetModule } from './webnet.d';
import { createWasmBackend, importFlavour } from './wasm-backend';

const repo = resolve(import.meta.dirname, '../../../..');
const publicDir = process.env['STERNHALMA_PUBLIC_DIR'] ?? join(repo, 'web/public');
const modelsDir = join(repo, 'web/models');
const net = join(publicDir, 'engines/net');
const built = ['engine-net.js', 'engine-net.wasm', 'engine-net.wasm-runtime.js', 'engine-net-nn-simd.wasm'].every((f) => existsSync(join(net, f)));
const suite = built ? describe : describe.skip;
const NET_REQUIRED = process.env['REQUIRE_ENGINE_ASSETS'] === '1';
const NAME = built ? 'net core with the real facade, backend and models' : 'net core with the real facade, backend and models (SKIPPED: web/public/engines/net is not built)';

const EXPERT = 'small-2p-adjacent-expert';
const SHARED = 'small-shared-all';
const LONG3: Variant = { jump: 'long', players: 3 };
const ADJ3: Variant = { jump: 'adjacent', players: 3 };

async function engine(target: FacadeTarget = 'js', seed = 7): Promise<{ core: NetCore; web: WebNetModule }> {
  const web = await loadWebNetInNode(publicDir, target);
  return { core: new NetCore('wasm', nodeDeps(web, { publicDir, modelsDir, seed })), web };
}

interface Played {
  history: number[];
  netMoves: Array<{ backend: string; ms: number }>;
  outcome: string;
}

/** Plays seat 0 with the net and the other seats with a seeded random mover; every move must be legal. */
async function play(core: NetCore, variant: Variant, rngSeed: number, maxPlies: number): Promise<Played> {
  const rng = mulberry32(rngSeed);
  const history: number[] = [];
  const netMoves: Played['netMoves'] = [];
  let outcome = 'ongoing';
  for (let ply = 0; ply < maxPlies; ply++) {
    const o = core.outcome(history, variant);
    if (o.kind !== 'ongoing') {
      outcome = o.kind === 'win' ? `win ${o.winner}` : 'draw';
      break;
    }
    const legal = core.legalMoves(history, variant);
    expect(legal.length).toBeGreaterThan(0);
    let move: number;
    if (ply % variant.players === 0) {
      const r = await core.choose(history, undefined, variant);
      move = r.move;
      netMoves.push({ backend: r.net.backend, ms: r.ms });
    } else {
      move = legal[Math.floor(rng() * legal.length)] as number;
    }
    expect(legal, `ply ${ply}: move ${move} must be legal`).toContain(move);
    history.push(move);
  }
  return { history, netMoves, outcome };
}

suite(NAME, () => {
  for (const target of ['js', 'wasm-gc'] as const) {
    it(`plays a whole two-player game against a random mover with legal moves only, same seed same game (${target})`, async () => {
      const a = await engine(target);
      expect(await a.core.newGame({ nodes: [1, 1], model: EXPERT, netOptions: { sims: 20 } })).toEqual([]);
      const g1 = await play(a.core, DEFAULT_VARIANT, 99, 200);
      const b = await engine(target);
      expect(await b.core.newGame({ nodes: [1, 1], model: EXPERT, netOptions: { sims: 20 } })).toEqual([]);
      const g2 = await play(b.core, DEFAULT_VARIANT, 99, 200);
      expect(g2.history).toEqual(g1.history);
      expect(g1.netMoves.length).toBeGreaterThan(5);
      expect(g1.outcome).toBe('win 0'); // the net beats a random mover
      expect(g1.netMoves.every((m) => m.backend === 'wasm')).toBe(true); // the net moved, not the fallback
      expect(a.core.warnings).toEqual([]);
      if (process.env['SH_NET_TIMES']) process.stderr.write(`net-core ${target}: ${g1.history.length} plies, ${g1.outcome}\n`);
      a.core.dispose();
      b.core.dispose();
    });
  }

  it('plays a three-player long-hop game with the shared model, legal moves only', async () => {
    const { core } = await engine();
    expect(await core.newGame({ nodes: [1, 1, 1], variant: LONG3, model: SHARED, netOptions: { sims: 20 } })).toEqual([]);
    const g = await play(core, LONG3, 5, 90);
    expect(g.netMoves.length).toBeGreaterThanOrEqual(15);
    expect(g.netMoves.every((m) => m.backend === 'wasm')).toBe(true);
    core.dispose();
  });

  it('plays the adjacent three-player game with the shared model', async () => {
    const { core } = await engine();
    await core.newGame({ nodes: [1, 1, 1], variant: ADJ3, model: SHARED, netOptions: { sims: 20 } });
    const g = await play(core, ADJ3, 6, 60);
    expect(g.netMoves.every((m) => m.backend === 'wasm')).toBe(true);
  });

  it('plays random legal moves and warns when no model is selected', async () => {
    const { core } = await engine();
    const warnings = await core.newGame({ nodes: [1, 1], model: null });
    expect(warnings.map((w) => w.code)).toEqual(['no-model', 'fallback-random']);
    const g = await play(core, DEFAULT_VARIANT, 3, 30);
    expect(g.netMoves.length).toBe(15);
    expect(g.netMoves.every((m) => m.backend === 'random')).toBe(true);
  });

  it('plays random legal moves and names model and variant when the expert model meets a three-player game', async () => {
    const { core } = await engine();
    const warnings = await core.newGame({ nodes: [1, 1, 1], variant: ADJ3, model: EXPERT, netOptions: { sims: 20 } });
    expect(warnings.map((w) => w.code)).toEqual(['model-variant-mismatch', 'fallback-random']);
    expect(warnings[0]).toMatchObject({ modelId: EXPERT, variant: ADJ3 });
    const g = await play(core, ADJ3, 4, 30);
    expect(g.netMoves.every((m) => m.backend === 'random')).toBe(true);
  });

  it('clears the warning when the model changes to one that fits', async () => {
    const { core } = await engine();
    expect((await core.newGame({ nodes: [1, 1], model: null })).length).toBe(2);
    expect(await core.newGame({ nodes: [1, 1], model: EXPERT, netOptions: { sims: 10 } })).toEqual([]);
    const r = await core.choose([]);
    expect(r.net.backend).toBe('wasm');
    expect(r.warnings).toEqual([]);
  });

  it('measures the time per move for 50, 200 and 800 simulations (expert model, wasm backend)', async () => {
    const { core } = await engine();
    const rows: string[] = [];
    for (const sims of [50, 200, 800]) {
      await core.newGame({ nodes: [1, 1], model: EXPERT, netOptions: { sims } });
      const first = await core.choose([]); // warm-up: the first search allocates
      const times: number[] = [];
      const history: number[] = [];
      for (let i = 0; i < 6; i++) {
        const r = await core.choose(history);
        times.push(r.ms);
        expect(r.net.sims).toBe(sims);
        history.push(r.move);
        const legal = core.legalMoves(history);
        history.push(legal[0] as number); // any reply: the position only has to be legal
      }
      times.sort((x, y) => x - y);
      rows.push(`sims ${sims}: median ${times[times.length >> 1]!.toFixed(0)} ms, max ${times[times.length - 1]!.toFixed(0)} ms, first ${first.ms.toFixed(0)} ms`);
    }
    if (process.env['SH_NET_TIMES']) process.stderr.write(`net-core times (wasm backend, node, expert model)\n${rows.join('\n')}\n`);
    expect(rows.length).toBe(3);
  }, 120_000);
  it('has the engine assets when REQUIRE_ENGINE_ASSETS=1 (so the tests of this file did not skip silently)', () => {
    if (NET_REQUIRED) expect(built).toBe(true);
  });

  describe('the explain API (scalar C++ flavour)', () => {
    interface Mcts { meta: { sims: number; batch: number; choice: number[] }; final: { move: number[]; N: number; Q: number | null }[] }
    const mcts = JSON.parse(readFileSync(join(repo, 'web/src/about/data/mcts-mid.json'), 'utf8')) as Mcts;
    const history = (JSON.parse(readFileSync(join(repo, 'web/src/about/data/eval-mid.json'), 'utf8')) as { history: { from: number; to: number }[] }).history.map((m) => m.from | (m.to << 8));

    async function scalarEngine(): Promise<NetCore> {
      const web = await loadWebNetInNode(publicDir, 'js');
      const deps = nodeDeps(web, {
        publicDir,
        modelsDir,
        createBackend: async (_kind, maxBatch) =>
          createWasmBackend({ maxBatch, flavours: ['scalar'], factory: (f) => importFlavour(pathToFileURL(join(net, `engine-net-nn-${f}.js`)).href) }),
      });
      return new NetCore('wasm', deps);
    }

    it('root.visits equal final[].N exactly and root.values equal final[].Q within 1e-4 for mcts-mid.json at 50 simulations', async () => {
      const core = await scalarEngine();
      expect(await core.newGame({ nodes: [1, 1], model: EXPERT, netOptions: { sims: mcts.meta.sims, batch: mcts.meta.batch } })).toEqual([]);
      const r = await core.choose(history);
      const root = r.net.root!;
      expect(root, 'a search ran, so the root is reported').toBeDefined();
      expect(root.moves.length).toBe(mcts.final.length);
      expect(root.visits.reduce((a, b) => a + b, 0)).toBe(mcts.meta.sims);
      expect(root.values).toHaveLength(root.moves.length);
      for (const f of mcts.final) {
        const i = root.moves.indexOf(f.move[0]! | (f.move[1]! << 8));
        expect(i, `move ${f.move.join('->')} is a root move`).toBeGreaterThanOrEqual(0);
        expect(root.visits[i], `visits of ${f.move.join('->')}`).toBe(f.N);
        if (f.Q !== null) expect(Math.abs(root.values![i]! - f.Q), `Q of ${f.move.join('->')}`).toBeLessThan(1e-4);
      }
      expect(r.move).toBe(mcts.meta.choice[0]! | (mcts.meta.choice[1]! << 8));
      core.dispose();
    });

    it('netForward reproduces forward-mid.json (planes exactly after rounding, priors and values within 2e-3) and a move afterwards still plays', async () => {
      const g = JSON.parse(readFileSync(join(repo, 'web/src/about/data/forward-mid.json'), 'utf8')) as {
        planes: number[][]; layers: { id: string; norm: number[] }[]; values: number[]; valuesNetOnly: number[]; policy: { from: number; to: number; prior: number; priorNetOnly: number }[];
      };
      const core = await scalarEngine();
      await core.newGame({ nodes: [1, 1], model: EXPERT, netOptions: { sims: 20, batch: 1 } });
      const f = await core.netForward(history);
      expect(f.modelId).toBe(EXPERT);
      expect(f.canonicalRotated).toBe(false);
      expect(f.planes.map((p) => p.map((v) => Math.round(v * 1e4) / 1e4))).toEqual(g.planes);
      expect(f.layers.map((l) => l.id)).toEqual(g.layers.map((l) => l.id));
      for (const [i, l] of f.layers.entries()) l.norm.forEach((v, c) => expect(Math.abs(v - g.layers[i]!.norm[c]!)).toBeLessThan(2e-3));
      expect(f.policy).toHaveLength(g.policy.length);
      for (const p of g.policy) {
        const m = f.policy.find((q) => q.move === (p.from | (p.to << 8)))!;
        expect(m, `legal move ${p.from}->${p.to}`).toBeDefined();
        expect(Math.abs(m.prior - p.prior)).toBeLessThan(2e-3);
        expect(Math.abs(m.priorNetOnly - p.priorNetOnly)).toBeLessThan(2e-3);
      }
      f.values.forEach((v, i) => expect(Math.abs(v - g.values[i]!)).toBeLessThan(2e-3));
      f.valuesNetOnly.forEach((v, i) => expect(Math.abs(v - g.valuesNetOnly[i]!)).toBeLessThan(2e-3));
      expect(f.logitsNetOnly.from).toHaveLength(121);
      // The facade is ready for the next move: a search after the forward pass gives the same answer as one without it.
      const after = await core.choose(history);
      const fresh = await scalarEngine();
      await fresh.newGame({ nodes: [1, 1], model: EXPERT, netOptions: { sims: 20, batch: 1 } });
      expect((await fresh.choose(history)).move).toBe(after.move);
      core.dispose();
      fresh.dispose();
    });

    it('netForward of a mover-1 position reports the rotation, and an aborted call rejects', async () => {
      const core = await scalarEngine();
      await core.newGame({ nodes: [1, 1], model: EXPERT, netOptions: { sims: 20, batch: 1 } });
      const f = await core.netForward(history.slice(0, 29));
      expect(f.canonicalRotated).toBe(true);
      expect(f.policy.reduce((a, p) => a + p.prior, 0)).toBeCloseTo(1, 6);
      const ac = new AbortController();
      ac.abort();
      await expect(core.netForward(history, ac.signal)).rejects.toMatchObject({ name: 'AbortError' });
      core.dispose();
    });
  });
});
