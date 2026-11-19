// Integration test of the net engine core with the REAL parts: the TeaVM facade (teavm-net), the C++ WebAssembly backend and the
// shipped models. Needs web/public/engines/net/ (scripts/web/build-teavm-net.zsh and build-wasm.zsh); without it the tests skip
// and say so in their names. Per-move times are printed with SH_NET_TIMES=1.
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { Variant } from '../../game/variant';
import { NetCore } from './core';
import { loadWebNetInNode, mulberry32, nodeDeps } from './node';
import type { FacadeTarget } from './node';
import type { WebNetModule } from './webnet.d';

const repo = resolve(import.meta.dirname, '../../../..');
const publicDir = process.env['STERNHALMA_PUBLIC_DIR'] ?? join(repo, 'web/public');
const modelsDir = join(repo, 'web/models');
const net = join(publicDir, 'engines/net');
const built = ['engine-net.js', 'engine-net.wasm', 'engine-net.wasm-runtime.js', 'engine-net-nn-simd.wasm'].every((f) => existsSync(join(net, f)));
const suite = built ? describe : describe.skip;
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
});
