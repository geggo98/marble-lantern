// Calibration of the skill estimate (docs/web-ui.md, section "Skill estimate"). Run it through scripts/web/calibrate-skill.zsh.
//
// For each node budget of a ladder, the engine at that budget plays whole games against itself. The first OPENING plies of each
// game are random legal moves (seeded: the seed of game i is SEED + i, the same for every budget, so the budgets are measured on
// the same openings). For every engine move after the opening, the script measures the loss exactly as the app does: all legal
// moves of the position before the move are scored with engine.analyse at SKILL_NODES (the same analysis player and budget), and
// loss = scoreLoss(best score, score of the move played). The raw losses go to one file per (budget, game); `--aggregate` turns
// the files into web/src/skill/calibration.json (statistic of stats.ts, isotonic smoothing, notes on monotonicity).
//
//   run mode (default)   --work DIR --rungs 500,1000,...  --games N  [--seed S] [--opening N] [--public DIR]
//   aggregate mode       --aggregate --work DIR --out FILE [--rungs ...]
//
// Resume: a finished (budget, game) has its file in DIR; a rerun skips it. Files are written under a temporary name and renamed.
// Exit: 0 = done or nothing left, 1 = error, 2 = wrong call, 130/143 = stopped (the running game is discarded).
import { readFileSync, readdirSync, renameSync, unlinkSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { parseArgs } from 'node:util';
import { CppCore } from '../../web/src/engines/cpp/core';
import type { CppModuleFactory } from '../../web/src/engines/cpp/core';
import { SKILL_NODES, scoreLoss } from '../../web/src/engines/analysis';
import { isotonicNonIncreasing, ladderViolations, resolvedTop } from '../../web/src/skill/calibration';
import type { Calibration, LadderRung } from '../../web/src/skill/calibration';
import { LOSS_CAP, cappedLoss, lossStat, seededRandom } from '../../web/src/skill/stats';

const webDir = fileURLToPath(new URL('../../web/', import.meta.url));
const USAGE = 'usage: calibrate-skill.mts --work DIR [--rungs a,b,c] [--games N] [--seed S] [--opening N] [--public DIR] | --aggregate --work DIR --out FILE';
const DEFAULT_RUNGS = [500, 1000, 2000, 5000, 10_000, 20_000, 50_000, 100_000, 200_000, 500_000];
// The game rules end a game at 200 plies (MOVE_LIMIT in web/src/game/board-state.ts); the engine refuses to move after that.
const MAX_PLIES = 200;

function die(message: string, code: number): never {
  process.stderr.write(`calibrate-skill: ${message}\n`);
  return process.exit(code);
}

let values: ReturnType<typeof parseArgs>['values'];
try {
  values = parseArgs({
    options: {
      work: { type: 'string' }, rungs: { type: 'string' }, games: { type: 'string', default: '12' }, seed: { type: 'string', default: '1000' },
      opening: { type: 'string', default: '4' }, public: { type: 'string', default: join(webDir, 'public') },
      aggregate: { type: 'boolean', default: false }, out: { type: 'string' },
    },
    strict: true,
  }).values;
} catch (e) {
  die(`${(e as Error).message}\n${USAGE}`, 2);
}
const work = values.work as string | undefined;
if (!work) die(USAGE, 2);
const rungs = values.rungs ? (values.rungs as string).split(',').map(Number) : DEFAULT_RUNGS;
if (rungs.some((r) => !Number.isInteger(r) || r < 1)) die(`bad --rungs\n${USAGE}`, 2);
const games = Number(values.games);
const seedBase = Number(values.seed);
const openingPlies = Number(values.opening);
if (![games, seedBase, openingPlies].every(Number.isInteger) || games < 1) die(USAGE, 2);
mkdirSync(work, { recursive: true });

const unitFile = (nodes: number, game: number): string => join(work, `r${nodes}-g${game}.json`);

interface Unit {
  nodes: number;
  game: number;
  seed: number;
  /** Plies of the finished game, and who won (side of the last mover), or -1 if the ply limit ended it. */
  plies: number;
  winner: number;
  /** For each analysed move: its ply and its raw loss, flattened [ply, loss, ply, loss, ...]. */
  moves: number[];
  ms: number;
  engineVersion: string;
}

const readUnit = (path: string): Unit => JSON.parse(readFileSync(path, 'utf8')) as Unit;

// ---------------------------------------------------------------------------------------------------------------- aggregate
if (values.aggregate) {
  const out = values.out as string | undefined;
  if (!out) die(USAGE, 2);
  const files = readdirSync(work).filter((f) => /^r\d+-g\d+\.json$/.test(f));
  const units = files.map((f) => readUnit(join(work, f)));
  const byRung = new Map<number, Unit[]>();
  for (const u of units) if (rungs.includes(u.nodes) || !values.rungs) byRung.set(u.nodes, [...(byRung.get(u.nodes) ?? []), u]);
  const nodesList = [...byRung.keys()].sort((a, b) => a - b);
  if (nodesList.length === 0) die(`no unit files in ${work}`, 1);
  const raw = nodesList.map((nodes) => {
    const us = byRung.get(nodes)!;
    const losses = us.flatMap((u) => u.moves.filter((_, i) => i % 2 === 1));
    const s = lossStat(losses)!;
    const variance = losses.reduce((a, l) => a + (cappedLoss(l) - s.mean) ** 2, 0) / Math.max(1, s.n - 1);
    return { nodes, games: us.length, moves: s.n, stat: s.mean, median: s.median, se: Math.sqrt(variance / s.n) };
  });
  const smoothed = isotonicNonIncreasing(raw.map((r) => r.stat), raw.map((r) => r.moves));
  const ladder: LadderRung[] = raw.map((r, i) => ({
    nodes: r.nodes, moves: r.moves, games: r.games, stat: round(r.stat), median: round(r.median), se: round(r.se), smoothed: round(smoothed[i]!),
  }));
  const violations = ladderViolations(raw.map((r) => r.stat));
  const versions = [...new Set(units.map((u) => u.engineVersion.replace(/-dirty$/, "")))]; // "dirty": only web/ files differed during the run
  const seeds = [...new Set(units.map((u) => u.seed))].sort((a, b) => a - b);
  const calibration: Calibration = {
    v: 1,
    statistic: { name: 'mean of the per-move losses, each capped at cap points', cap: LOSS_CAP },
    analysisNodes: SKILL_NODES,
    engine: { id: 'cpp-wasm', version: versions.join(',') },
    date: new Date().toISOString().slice(0, 10),
    openingPlies,
    seeds,
    ladder,
    resolvedUpTo: resolvedTop(nodesList, smoothed, raw.map((r) => r.se)),
    notes:
      `Self-play with the engine at each node budget; loss of every move after the random opening, measured by analyse at ${SKILL_NODES} nodes. ` +
      `${violations} of ${raw.length - 1} neighbouring rungs of the raw ladder increased with the budget` +
      (violations > 0 ? ' (noise); the column "smoothed" is the isotonic fit that the app uses.' : '; the raw ladder was already monotone.'),
  };
  const tmp = `${out}.tmp.${process.pid}`;
  writeFileSync(tmp, `${JSON.stringify(calibration, null, 2)}\n`);
  renameSync(tmp, out);
  process.stderr.write(`calibrate-skill: wrote ${out}; ${violations} violation(s) in the raw ladder\n`);
  for (const r of ladder) process.stderr.write(`  ${String(r.nodes).padStart(7)} nodes  ${String(r.moves).padStart(5)} moves  stat ${r.stat}  smoothed ${r.smoothed}  median ${r.median}\n`);
  process.stderr.write(`  resolved up to ${calibration.resolvedUpTo} nodes\n`);
  process.exit(0);
}

function round(x: number): number {
  return Math.round(x * 100) / 100;
}

// ---------------------------------------------------------------------------------------------------------------- run
let stopSignal: string | null = null;
for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => { stopSignal = s; });

for (const f of readdirSync(work)) {
  if (/\.tmp\.\d+$/.test(f)) {
    unlinkSync(join(work, f)); // CLEANUP: leftover of an interrupted run
    process.stderr.write(`calibrate-skill: CLEANUP removed ${f}\n`);
  }
}

const manifest = JSON.parse(readFileSync(join(values.public as string, 'engines', 'engines.json'), 'utf8')) as {
  version: string;
  engines: Record<string, { files: { path: string }[] }>;
};
const entry = manifest.engines['cpp-wasm'] ?? die('engines.json has no cpp-wasm; run devenv tasks run web:engines', 1);
const mainFile = entry.files.find((f) => f.path.endsWith('.js'))?.path ?? die('cpp-wasm has no .js file', 1);
const factory = ((await import(/* @vite-ignore */ pathToFileURL(join(values.public as string, mainFile)).href)) as { default: CppModuleFactory }).default;
const core = await CppCore.create(factory);
core.installTable(new Uint8Array(gunzipSync(readFileSync(join(values.public as string, 'tables', 'rows2.bin.gz')))), true);

const todo: { nodes: number; game: number }[] = [];
let skip = 0;
for (const nodes of rungs) {
  for (let game = 0; game < games; game++) {
    if (existsSync(unitFile(nodes, game))) skip++;
    else todo.push({ nodes, game });
  }
}
const known = new Map<number, number[]>(); // ms per finished unit, by budget, for the ETA
for (const nodes of rungs) {
  for (let game = 0; game < games; game++) {
    if (existsSync(unitFile(nodes, game))) known.set(nodes, [...(known.get(nodes) ?? []), readUnit(unitFile(nodes, game)).ms]);
  }
}
const mean = (a: number[]): number => a.reduce((s, x) => s + x, 0) / a.length;
const allMs = (): number[] => [...known.values()].flat();
const estimate = (nodes: number): number => (known.get(nodes) ? mean(known.get(nodes)!) : allMs().length ? mean(allMs()) * (nodes / 20_000) ** 0.5 : 60_000);

/** Plays one game and measures the loss of every engine move after the opening. Returns null if stopped. */
function playUnit(nodes: number, game: number): Unit | null {
  const seed = seedBase + game;
  const random = seededRandom(seed);
  const t0 = performance.now();
  core.newGame({ nodes: [nodes, nodes] });
  const history: number[] = [];
  const moves: number[] = [];
  let winner = -1;
  for (let ply = 0; ply < MAX_PLIES; ply++) {
    if (stopSignal) return null;
    const legal = core.legalMoves(history);
    if (legal.length === 0) {
      winner = (history.length - 1) % 2; // the side that made the last move won
      break;
    }
    if (ply < openingPlies) {
      history.push(legal[Math.floor(random() * legal.length)]!);
      continue;
    }
    let move: number;
    try {
      move = core.choose(history).move;
    } catch (e) {
      throw new Error(`${(e as Error).message} (ply ${ply}, seed ${seed}, ${legal.length} legal moves, history ${history.join(',')})`);
    }
    const a = core.analyse(history, null, SKILL_NODES);
    const played = a.scores[a.moves.indexOf(move)];
    if (played === undefined) throw new Error(`the engine chose move ${move}, which analyse did not score (ply ${ply}, seed ${seed})`);
    moves.push(ply, scoreLoss(a.position, played));
    history.push(move);
  }
  return { nodes, game, seed, plies: history.length, winner, moves, ms: Math.round(performance.now() - t0), engineVersion: manifest.version };
}

process.stderr.write(`calibrate-skill: ${todo.length} unit(s) to do, ${skip} already done (rungs ${rungs.join(',')}, ${games} game(s) each)\n`);
let ok = 0;
let err = 0;
const tStart = performance.now();
let doneMs = 0;
let lastLine = 0;
for (const [index, u] of todo.entries()) {
  if (stopSignal) break;
  let unit: Unit | null;
  try {
    unit = playUnit(u.nodes, u.game);
  } catch (e) {
    err++;
    process.stderr.write(`calibrate-skill: ERROR r${u.nodes} g${u.game}: ${(e as Error).message}\n`);
    continue;
  }
  if (!unit) break; // stopped: the running game is discarded
  const path = unitFile(u.nodes, u.game);
  const tmp = `${path}.tmp.${process.pid}`;
  writeFileSync(tmp, JSON.stringify(unit));
  renameSync(tmp, path);
  ok++;
  doneMs += unit.ms;
  known.set(u.nodes, [...(known.get(u.nodes) ?? []), unit.ms]);
  const nowMs = performance.now();
  if (nowMs - lastLine >= 5000 || ok + err === todo.length) {
    lastLine = nowMs;
    process.stderr.write(
      `${ok + err}/${todo.length}  r${u.nodes} g${u.game} ${unit.plies} plies ${unit.moves.length / 2} moves ${(unit.ms / 1000).toFixed(0)}s  ` +
        `ETA ${(todo.slice(index + 1).reduce((s, x) => s + estimate(x.nodes), 0) / 1000).toFixed(0)}s  elapsed ${((nowMs - tStart) / 1000).toFixed(0)}s\n`,
    );
  }
}
const left = todo.length - ok - err;
process.stderr.write(
  `calibrate-skill: ${ok} ok, ${skip} skip, ${err} error, ${left} left${stopSignal ? ' - stopped' : left === 0 && err === 0 ? ' - nothing more to do' : ''}\n`,
);
process.exit(stopSignal ? (stopSignal === 'SIGINT' ? 130 : 143) : err ? 1 : 0);
