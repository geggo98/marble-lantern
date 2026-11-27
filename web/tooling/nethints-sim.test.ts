// One simulated game of the net-hints study (scripts/guides/README.md). Skipped unless NETHINTS_SIM=1, so that the normal
// test run never starts it. Run it through scripts/guides/run.py, or directly:
//   cd web && NETHINTS_SIM=1 NETHINTS_VARIANT=r5 NETHINTS_GAME=0 NETHINTS_OUT=/some/dir npx vitest run tooling/nethints-sim.test.ts
// Env: NETHINTS_VARIANT (r5 | r4), NETHINTS_MODE (label, default A; presets A = net 200 sims vs classical 4000 nodes,
// B = net 50 sims vs classical 102400 nodes), NETHINTS_OPPONENT_NODES and NETHINTS_NET_SIMS (override the preset),
// NETHINTS_GAME (int, also the seeds), NETHINTS_OUT (directory), NETHINTS_MAX_PLIES (stop the game early; smoke tests only).
// Writes <out>/<variant>-<mode>-<game>.jsonl atomically; a file whose last line has end:true counts as done and is kept.
import { existsSync, readFileSync, readdirSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { it } from 'vitest';
import { CppCore } from '../src/engines/cpp/core';
import type { CppModuleFactory } from '../src/engines/cpp/core';
import { NetCore } from '../src/engines/net/core';
import { loadWebNetInNode, mulberry32, nodeDeps } from '../src/engines/net/node';
import { scoreBand } from '../src/engines/analysis';
import { rankBand } from '../src/guides/bands';
import type { Variant } from '../src/game/variant';

const WEB = fileURLToPath(new URL('..', import.meta.url));
const pub = join(WEB, 'public');
const VARIANTS: Record<string, { variant: Variant; model: string }> = {
  r5: { variant: { jump: 'adjacent', players: 2, rows: 5 }, model: 'small-2p-adjacent-r5-expert' },
  r4: { variant: { jump: 'adjacent', players: 2 }, model: 'small-2p-adjacent-expert' },
};
const PRESETS: Record<string, { oppNodes: number; netSims: number }> = {
  A: { oppNodes: 4000, netSims: 200 },
  B: { oppNodes: 102400, netSims: 50 },
};
const ANALYSIS_NODES = 4000; // as GUIDE_NODES of the guides
const ADVISOR_SIMS = 800;
const PROBE_SIMS = 100;
const MIN_VISITS = 16;
const PROBE_BAND_LOSS = 90; // classical top and good moves are probed when the net visited them less than MIN_VISITS
const COUNTER_BUDGETS = [3000, 4000, 5000];
const COUNTER_CAP = 300; // plies of one playout
const CANDIDATE_DELTA = 0.15; // trap candidate: lower than the decision delta, so that it can be swept afterwards
const RANDOM_OPENING_PLIES = 4;
const GAME_CAP = 400;

/** True when the file ends with the end line: the only state that counts as done. */
function isVerified(path: string): boolean {
  if (!existsSync(path)) return false;
  const lines = readFileSync(path, 'utf8').split('\n').filter((l) => l.length > 0);
  if (lines.length === 0) return false;
  try {
    return (JSON.parse(lines[lines.length - 1]!) as { end?: boolean }).end === true;
  } catch {
    return false;
  }
}

it.skipIf(process.env['NETHINTS_SIM'] !== '1')('simulate one game', async () => {
  const vk = process.env['NETHINTS_VARIANT'] ?? 'r5';
  const game = Number(process.env['NETHINTS_GAME'] ?? '0');
  const mode = process.env['NETHINTS_MODE'] ?? 'A';
  const outDir = process.env['NETHINTS_OUT'];
  if (!outDir) throw new Error('NETHINTS_OUT is not set');
  const preset = PRESETS[mode] ?? PRESETS['A']!;
  const oppNodes = Number(process.env['NETHINTS_OPPONENT_NODES'] ?? preset.oppNodes);
  const netSims = Number(process.env['NETHINTS_NET_SIMS'] ?? preset.netSims);
  const maxPlies = Number(process.env['NETHINTS_MAX_PLIES'] ?? GAME_CAP);
  const spec = VARIANTS[vk];
  if (!spec) throw new Error(`unknown NETHINTS_VARIANT ${vk}`);
  const { variant, model } = spec;
  const dest = join(outDir, `${vk}-${mode}-${game}.jsonl`);
  if (isVerified(dest)) return;
  for (const f of readdirSync(outDir)) if (f.startsWith(`${vk}-${mode}-${game}.jsonl.tmp.`)) unlinkSync(join(outDir, f)); // CLEANUP

  const factory: CppModuleFactory = async () =>
    ((await import(/* @vite-ignore */ pathToFileURL(join(pub, 'engines/cpp/engine-cpp.js')).href)) as { default: CppModuleFactory }).default();
  const table = new Uint8Array(gunzipSync(readFileSync(join(pub, 'tables/rows2.bin.gz'))));
  const cpp = await CppCore.create(factory);
  cpp.installTable(table, false);
  cpp.newGame({ nodes: [oppNodes, oppNodes], variant });
  // The playouts use their own engine: newGame there must not clear the players of the simulated game.
  const play = await CppCore.create(factory);
  play.installTable(table, false);

  const mk = async (sims: number, seed: number): Promise<NetCore> => {
    const web = await loadWebNetInNode(pub, 'wasm-gc');
    const core = new NetCore('wasm', nodeDeps(web, { publicDir: pub, modelsDir: join(WEB, 'models'), seed }));
    const w = await core.newGame({ nodes: [1, 1], variant, model, netOptions: { sims } });
    if (w.length) throw new Error('warnings: ' + JSON.stringify(w));
    return core;
  };
  const player = await mk(netSims, 100 + game);
  const advisor = await mk(ADVISOR_SIMS, 7);
  const prober = await mk(PROBE_SIMS, 9);

  /** Result of `move` from the position `history`, for the player who makes it: 1 win, 0 loss, 0.5 draw or cap. */
  const playout = (history: readonly number[], move: number, budget: number, maker: number): number => {
    play.newGame({ nodes: [budget, budget], variant });
    const h = [...history, move];
    for (let i = 0; i < COUNTER_CAP; i++) {
      const o = play.outcome(h, variant);
      if (o.kind === 'win') return o.winner === maker ? 1 : 0;
      if (o.kind !== 'ongoing') return 0.5;
      h.push(play.choose(h, variant).move);
    }
    return 0.5;
  };

  const rng = mulberry32(1000 + game);
  const netSeat = game % 2; // alternate colours
  const history: number[] = [];
  const lines: string[] = [
    JSON.stringify({ header: true, version: 1, variant: vk, mode, game, oppNodes, netSims, advisorSims: ADVISOR_SIMS, probeSims: PROBE_SIMS, model,
      analysisNodes: ANALYSIS_NODES, minVisits: MIN_VISITS, counterBudgets: COUNTER_BUDGETS, counterCap: COUNTER_CAP, candidateDelta: CANDIDATE_DELTA, maxPlies }),
  ];
  for (let ply = 0; ply < maxPlies; ply++) {
    if (cpp.outcome(history, variant).kind !== 'ongoing') break;
    const legal = cpp.legalMoves(history, variant);
    if (ply >= RANDOM_OPENING_PLIES && legal.length > 1) {
      const a = cpp.analyse(history, null, ANALYSIS_NODES, variant);
      if (scoreBand(a.position) === 'ordinary') {
        const t0 = performance.now();
        const r = await advisor.choose(history, undefined, variant);
        const ms = performance.now() - t0;
        const root = r.net.root;
        if (r.net.backend === 'wasm' && root?.values) {
          // probe: every classical top/good move with < 16 root visits, plus the net favourite, gets its own 100-sim search
          // from the child position; the mover's value is minus the root value of the opponent's search (two players).
          const best = Math.max(...a.scores);
          const visits = new Map(root.moves.map((m, i) => [m, root.visits[i]!]));
          const values = new Map(root.moves.map((m, i) => [m, root.values![i]!]));
          const fav = root.moves[root.visits.indexOf(Math.max(...root.visits))]!;
          const want = a.moves.filter((m, i) => best - a.scores[i]! <= PROBE_BAND_LOSS && (visits.get(m) ?? 0) < MIN_VISITS);
          const probe: number[][] = [];
          const pt0 = performance.now();
          for (const m of [fav, ...want.filter((m) => m !== fav)]) {
            const child = [...history, m];
            const o = cpp.outcome(child, variant);
            if (o.kind !== 'ongoing') { probe.push([m, o.kind === 'win' && o.winner === ply % 2 ? 1 : -1]); continue; }
            const c = await prober.choose(child, undefined, variant);
            probe.push([m, -c.score]);
          }
          const probeMs = Math.round(performance.now() - pt0);
          lines.push(JSON.stringify({ ply, mover: ply % 2, n: legal.length, h: history, cls: { moves: a.moves, scores: a.scores },
            net: { moves: root.moves, visits: root.visits, values: root.values, value: r.score, ms: Math.round(ms) }, probe, probeMs }));

          // counter-move test: candidate marks at the lowest delta; the rest of the decision is made offline
          const probeMap = new Map(probe.map(([m, q]) => [m!, q!]));
          const val = (m: number): number | undefined => ((visits.get(m) ?? 0) >= MIN_VISITS ? values.get(m) : probeMap.get(m));
          const cbest = a.moves[a.scores.indexOf(best)]!;
          const favBand = rankBand(a.scores[a.moves.indexOf(fav)]!, best);
          const tip = favBand === 'equal' || favBand === 'worse' || favBand === 'much-worse';
          const qc = val(cbest);
          const qf = val(fav);
          const trap = cbest !== fav && qc !== undefined && qf !== undefined && qc <= qf - CANDIDATE_DELTA;
          if (tip || trap) {
            const t1 = performance.now();
            const results: Record<string, Record<string, number>> = {};
            for (const m of [fav, cbest]) {
              const row: Record<string, number> = {};
              for (const b of COUNTER_BUDGETS) row[b] = playout(history, m, b, ply % 2);
              results[m] = row;
            }
            lines.push(JSON.stringify({ counter: true, ply, h: history, fav, cbest, tip, trap, results, counterMs: Math.round(performance.now() - t1) }));
          }
        }
      }
    }
    let move: number;
    if (ply < RANDOM_OPENING_PLIES) move = legal[Math.floor(rng() * legal.length)]!;
    else if (ply % 2 === netSeat) move = (await player.choose(history, undefined, variant)).move;
    else move = cpp.choose(history, variant).move;
    history.push(move);
  }
  lines.push(JSON.stringify({ end: true, plies: history.length, outcome: cpp.outcome(history, variant), netSeat, mode }));
  const tmp = `${dest}.tmp.${process.pid}`;
  writeFileSync(tmp, lines.join('\n') + '\n');
  renameSync(tmp, dest);
}, 3_600_000);
