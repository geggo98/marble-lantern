// Engine measurements on the 5-row board (board-rows design, package V1). Run it through scripts/web/measure-rows5.zsh.
//
// Seeded classical self-play of the browser engines in Node: the C++ engine (web/public/engines/cpp, the wasm build) plays every game,
// and the TeaVM engine (web/public/engines/java, Wasm GC) chooses a move for the same history at each ply of the first --java-plies
// plies. Both engines play the browser player of the board size (BROWSER_ROWS5_SPEC and its 3-player and long-hop forms on 5 rows,
// BROWSER_SPEC and BROWSER_3P_SPEC on 4 rows) with the same node budget for every seat. The game always goes on with the C++ move.
// The first --opening plies are random legal moves, seeded per game (seed = --seed + game), so that the deterministic search plays
// different games.
//
// What one game (a "unit") records:
//   - plies and outcome (win, draw by the move limit or no move): the game length against the move limit;
//   - the score of every engine move: the largest |score| below the race band (RACE_BASE 5000), and the count of race-band and mate
//     scores;
//   - wall time and CPU time of every C++ move and every TeaVM move;
//   - Java-vs-C++ agreement: move, score, depth and nodes of both engines at each compared ply;
//   - after a win, the distance of the result as the web computes it (web/src/result/distance.ts): two players, a solo race of the
//     loser (beam width 60, the beam of web/src/result/solo.ts, on the 15-cell houses); three players, a playout under full ranking
//     with the C++ analysis at 500 nodes per candidate move until the second seat has finished. Both up to --distance-cap.
//
// Modes:
//   run (default)  --work DIR [--tag T] [--rows 4,5] [--players 2,3] [--jump adjacent,long] [--camps closed,pass-through,open]
//                  [--levels 1000,5000,...] [--games N] [--seed S] [--opening N] [--max-plies N] [--java-plies N] [--distance 0|1]
//                  [--distance-cap N] [--public DIR]
//   aggregate      --aggregate --work DIR [--tag T]   prints the summary tables to stdout
//
// Resume: a finished unit has its file DIR/<tag>/<unit>.json; a rerun skips it. Files are written under a temporary name and renamed;
// leftovers of an interrupted run are removed at the start (CLEANUP). The lock and the status file are in the zsh wrapper.
// Exit: 0 = done or nothing left, 1 = error, 2 = wrong call, 130/143 = stopped (the running game is discarded).
import { readFileSync, readdirSync, renameSync, unlinkSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { parseArgs } from 'node:util';

const webDir = fileURLToPath(new URL('../../web/', import.meta.url));
const USAGE = 'usage: measure-rows5.mjs --work DIR [--tag T] [--rows 4,5] [--players 2,3] [--jump adjacent,long] [--camps closed,pass-through,open] ' +
  '[--levels a,b] [--games N] [--seed S] [--opening N] [--max-plies N] [--java-plies N] [--distance 0|1] [--distance-cap N] [--public DIR] | --aggregate --work DIR [--tag T]';
const RACE_BASE = 5000; // search/src/main/java/sternhalma/search/Scores.java
const MATE_BOUND = 1_000_000 - 1000; // AlphaBeta.MATE_BOUND
const PLAYOUT_NODES = 500; // web/src/result/distance.ts
const SOLO_WIDTH = 60; // web/src/result/solo.ts
const CAMPS = { closed: 0, 'pass-through': 1, open: 2 };
const JUMPS = { adjacent: 0, long: 1 };
const CAP = 1815; // the largest move list: 15 pieces x 121 cells

function die(message, code) {
  process.stderr.write(`measure-rows5: ${message}\n`);
  process.exit(code);
}

let values;
try {
  values = parseArgs({
    options: {
      work: { type: 'string' }, tag: { type: 'string', default: 'main' }, rows: { type: 'string', default: '5' },
      players: { type: 'string', default: '2,3' }, jump: { type: 'string', default: 'adjacent,long' },
      camps: { type: 'string', default: 'closed,pass-through,open' }, levels: { type: 'string', default: '1000,5000,20000' },
      games: { type: 'string', default: '4' }, seed: { type: 'string', default: '5000' }, opening: { type: 'string', default: '4' },
      'max-plies': { type: 'string', default: '0' }, 'java-plies': { type: 'string', default: '0' },
      distance: { type: 'string', default: '1' }, 'distance-cap': { type: 'string', default: '400' },
      public: { type: 'string', default: join(webDir, 'public') }, aggregate: { type: 'boolean', default: false },
    },
    strict: true,
  }).values;
} catch (e) {
  die(`${e.message}\n${USAGE}`, 2);
}
const work = values.work ?? die(USAGE, 2);
const tagDir = join(work, values.tag);
const list = (s) => s.split(',').filter((x) => x !== '');
const ints = (s, name) => {
  const v = list(s).map(Number);
  if (v.length === 0 || v.some((x) => !Number.isInteger(x) || x < 1)) die(`bad --${name}: ${s}\n${USAGE}`, 2);
  return v;
};
const int = (s, name) => {
  const v = Number(s);
  if (!Number.isInteger(v) || v < 0) die(`bad --${name}: ${s}\n${USAGE}`, 2);
  return v;
};

// ---------------------------------------------------------------------------------------------------------------- aggregate
if (values.aggregate) {
  aggregate();
  process.exit(0);
}

const rowsList = ints(values.rows, 'rows');
const playersList = ints(values.players, 'players');
const jumpList = list(values.jump);
const campsList = list(values.camps);
const levels = ints(values.levels, 'levels');
const games = int(values.games, 'games');
const seedBase = int(values.seed, 'seed');
const openingPlies = int(values.opening, 'opening');
const maxPlies = int(values['max-plies'], 'max-plies');
const javaPlies = int(values['java-plies'], 'java-plies');
const withDistance = values.distance === '1';
const distanceCap = int(values['distance-cap'], 'distance-cap');
if (rowsList.some((r) => r !== 4 && r !== 5) || playersList.some((p) => p !== 2 && p !== 3)) die(`rows must be 4 or 5, players 2 or 3\n${USAGE}`, 2);
if (jumpList.some((j) => !(j in JUMPS)) || campsList.some((c) => !(c in CAMPS))) die(`bad --jump or --camps\n${USAGE}`, 2);
mkdirSync(tagDir, { recursive: true });

let stopSignal = null;
for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => { stopSignal = s; });

let cleanup = 0;
for (const f of readdirSync(tagDir)) {
  if (/\.tmp\.\d+$/.test(f)) {
    unlinkSync(join(tagDir, f)); // CLEANUP: leftover of an interrupted run
    cleanup++;
  }
}

// ---------------------------------------------------------------------------------------------------------------- engines
const pub = values.public;
const table = (() => {
  const manifest = JSON.parse(readFileSync(join(pub, 'tables', 'rows2.manifest.json'), 'utf8'));
  const raw = gunzipSync(readFileSync(join(pub, 'tables', manifest.file)));
  if (raw.length !== manifest.rawBytes) die(`rows2: ${raw.length} bytes, manifest says ${manifest.rawBytes}`, 1);
  if (createHash('sha256').update(raw).digest('hex') !== manifest.sha256) die('rows2: sha256 differs from the manifest', 1);
  return raw;
})();
const engineVersion = (() => {
  const files = ['engines/cpp/engine-cpp.wasm', 'engines/java/engine-java.wasm'];
  const h = createHash('sha256');
  for (const f of files) h.update(readFileSync(join(pub, f)));
  return h.digest('hex').slice(0, 16);
})();

const cpp = await (await import(pathToFileURL(join(pub, 'engines', 'cpp', 'engine-cpp.js')).href)).default();
{
  const ptr = cpp._malloc(table.length);
  cpp.HEAPU8.set(table, ptr);
  const rc = cpp._shw_load_rows2(ptr, table.length, 0);
  cpp._free(ptr);
  if (rc !== 0) die(`C++ table load: ${rc} ${cpp.UTF8ToString(cpp._shw_error())}`, 1);
}
const buf = cpp._malloc(4 * CAP * 3);
const [inP, movesP, scoresP] = [buf, buf + 4 * CAP, buf + 8 * CAP];
const heap = (p, n) => Array.from(cpp.HEAP32.subarray(p >> 2, (p >> 2) + n));
const cppLegal = () => heap(inP, cpp._shw_legal_moves(inP, CAP));

let java = null;
if (javaPlies > 0) {
  await import(pathToFileURL(join(pub, 'engines', 'java', 'engine-java.wasm-runtime.js')).href);
  java = (await globalThis.TeaVM.wasmGC.load(join(pub, 'engines', 'java', 'engine-java.wasm'))).exports;
  const reply = java.setTable(new Int8Array(table.buffer, table.byteOffset, table.length), false);
  if (!reply.startsWith('ok')) die(`TeaVM setTable: ${reply}`, 1);
}
const javaFields = (reply) => {
  if (reply === 'ok') return [];
  if (reply.startsWith('ok ')) return reply.slice(3).split(' ');
  throw new Error(`TeaVM: ${reply}`);
};

// One C++ player per seat and per (rows, players, jump, nodes); cleared at the start of each game (as CppCore.acquire does).
const playerCache = new Map();
function cppPlayers(rows, players, jump, nodes) {
  const key = `${rows}:${players}:${jump}:${nodes}`;
  let ids = playerCache.get(key);
  if (!ids) {
    ids = [];
    for (let s = 0; s < players; s++) {
      const id = players === 2 ? cpp._shw_player_new_rows(nodes, JUMPS[jump], rows) : cpp._shw_player_new3_rows(nodes, JUMPS[jump], rows);
      if (id < 0) throw new Error(`player creation: ${cpp.UTF8ToString(cpp._shw_error())}`);
      ids.push(id);
    }
    playerCache.set(key, ids);
  }
  for (const id of ids) cpp._shw_player_clear(id);
  return ids;
}

const cpuMs = () => {
  const u = process.cpuUsage();
  return (u.user + u.system) / 1000;
};

// ---------------------------------------------------------------------------------------------------------------- geometry
// From the engine's own cell table: axial q, r of each cell; the start house of a seat = its pieces at the start; its goal house =
// the point mirror of the start house (the opposite corner). Neighbour directions as in web/src/board/cells.ts.
const cellCount = cpp._shw_cell_count();
const Q = Array.from({ length: cellCount }, (_, c) => cpp._shw_cell_q(c));
const R = Array.from({ length: cellCount }, (_, c) => cpp._shw_cell_r(c));
const cellAt = new Map(Q.map((q, c) => [`${q},${R[c]}`, c]));
const DIRS = [[1, 0], [1, -1], [0, -1], [-1, 0], [-1, 1], [0, 1]];
const neighbor = (c, d) => cellAt.get(`${Q[c] + DIRS[d][0]},${R[c] + DIRS[d][1]}`) ?? -1;
const mirror = (c) => cellAt.get(`${-Q[c]},${-R[c]}`);
const hexDistance = (a, b) => (Math.abs(Q[a] - Q[b]) + Math.abs(R[a] - R[b]) + Math.abs(Q[a] + R[a] - Q[b] - R[b])) / 2;
const inCentre = (c) => Math.abs(Q[c]) <= 4 && Math.abs(R[c]) <= 4 && Math.abs(Q[c] + R[c]) <= 4;
const owners = () => Int8Array.from({ length: cellCount }, (_, c) => cpp._shw_owner(c));

/** Start and goal houses of each seat of the current game, read before the first move. */
function houses(players) {
  const o = owners();
  const start = Array.from({ length: players }, (_, p) => [...o.keys()].filter((c) => o[c] === p));
  return { start, goal: start.map((cells) => cells.map(mirror)) };
}

const hasWon = (o, side, goal) => goal.every((c) => o[c] !== -1) && goal.some((c) => o[c] === side);

/** The legal solo moves of solo.ts (soloMoves) on the given houses. */
function soloMoves(o, side, start, goal, longHop) {
  const goalSet = new Set(goal);
  const stand = new Set([...start, ...goal]);
  const allowed = (c) => inCentre(c) || stand.has(c);
  const moves = new Set();
  for (let from = 0; from < cellCount; from++) {
    if (o[from] !== side) continue;
    const ok = (to) => allowed(to) && !(goalSet.has(from) && !goalSet.has(to));
    const empty = (c) => o[c] === -1 || c === from;
    for (let d = 0; d < 6; d++) {
      const step = neighbor(from, d);
      if (step >= 0 && o[step] === -1 && ok(step)) moves.add(from | (step << 8));
    }
    const seen = new Set([from]);
    const queue = [from];
    for (let head = 0; head < queue.length; head++) {
      const at = queue[head];
      for (let d = 0; d < 6; d++) {
        let land = -1;
        if (longHop) {
          let k = 1;
          let p = neighbor(at, d);
          while (p >= 0 && empty(p)) { p = neighbor(p, d); k++; }
          if (p >= 0 && p !== from) {
            let l = p;
            for (let i = 0; i < k && l >= 0; i++) { l = neighbor(l, d); if (l >= 0 && !empty(l)) l = -1; }
            land = l;
          }
        } else {
          const over = neighbor(at, d);
          if (over >= 0 && over !== from && !empty(over)) {
            const l = neighbor(over, d);
            land = l >= 0 && empty(l) ? l : -1;
          }
        }
        if (land < 0 || seen.has(land) || !allowed(land)) continue;
        seen.add(land);
        queue.push(land);
        if (ok(land)) moves.add(from | (land << 8));
      }
    }
  }
  return [...moves];
}

/** soloRace of solo.ts: moves of `side` alone until hasWon, beam width 60 by the distance sum to the goal tip. */
function soloRace(owner, side, start, goal, longHop, cap) {
  const tip = goal.reduce((best, c) => (Math.abs(R[c]) > Math.abs(R[best]) ? c : best), goal[0]);
  const inGoal = new Set(goal);
  const potential = (o) => {
    let sum = 0;
    for (let c = 0; c < cellCount; c++) if (o[c] === side) sum += hexDistance(c, tip) * (inGoal.has(c) ? 0.1 : 1);
    return sum;
  };
  const keyOf = (o) => {
    let key = '';
    for (let c = 0; c < cellCount; c++) if (o[c] === side) key += String.fromCharCode(c);
    return key;
  };
  if (hasWon(owner, side, goal)) return { moves: 0, capped: false };
  let beam = [owner.slice()];
  for (let ply = 1; ply <= cap; ply++) {
    const next = new Map();
    for (const o of beam) {
      for (const move of soloMoves(o, side, start, goal, longHop)) {
        const child = o.slice();
        child[move & 0xff] = -1;
        child[move >>> 8] = side;
        if (hasWon(child, side, goal)) return { moves: ply, capped: false };
        const key = keyOf(child);
        if (!next.has(key)) next.set(key, { o: child, p: potential(child) });
      }
    }
    if (next.size === 0) throw new Error('solo race: the side has no legal move');
    beam = [...next.values()].sort((a, b) => a.p - b.p).slice(0, SOLO_WIDTH).map((e) => e.o);
  }
  return { moves: cap, capped: true };
}

/** playOutThree of distance.ts: full ranking, analysis at 500 nodes per candidate, best score (first of equal) until two seats finished. */
function playOutThree(history, rows, jump, camps, cap) {
  if (cpp._shw_game_new5(JUMPS[jump], 3, 1, CAMPS[camps], rows) !== 0) throw new Error(cpp.UTF8ToString(cpp._shw_error()));
  for (const m of history) if (cpp._shw_play(m) !== 0) throw new Error(`playout: replay refused move ${m}`);
  const limit = cpp._shw_move_limit();
  const start = history.length;
  const bits = (x) => (x & 1) + ((x >> 1) & 1) + ((x >> 2) & 1);
  while (bits(cpp._shw_finished_mask()) < 2) {
    if (cpp._shw_ply() - start >= cap || cpp._shw_ply() >= limit) return { plies: cpp._shw_ply() - start, capped: true };
    const n = cpp._shw_analyse(PLAYOUT_NODES, 0, 0, movesP, scoresP, CAP);
    if (n < 1) return { plies: cpp._shw_ply() - start, capped: true, stuck: n };
    const moves = heap(movesP, n);
    const scores = heap(scoresP, n);
    let best = 0;
    for (let i = 1; i < n; i++) if (scores[i] > scores[best]) best = i;
    if (cpp._shw_play(moves[best]) !== 0) throw new Error('playout: the analysis move was refused');
  }
  return { plies: cpp._shw_ply() - start, capped: false };
}

function seededRandom(seed) { // web/src/skill/stats.ts
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------------------------------------------------------------------------------------------------------- one game
const unitName = (u) => `r${u.rows}-p${u.players}-${u.jump}-${u.camps}-n${u.nodes}-s${u.seed}`;

async function playUnit(u) {
  const t0 = performance.now();
  const { rows, players, jump, camps, nodes, seed } = u;
  const ids = cppPlayers(rows, players, jump, nodes);
  if (cpp._shw_game_new5(JUMPS[jump], players, 0, CAMPS[camps], rows) !== 0) throw new Error(cpp.UTF8ToString(cpp._shw_error()));
  const moveLimit = cpp._shw_move_limit();
  const { start, goal } = houses(players);
  const jargs = [jump, String(players), 'first', camps, String(rows)];
  if (java) javaFields(java.newGame(nodes, nodes, jump, String(players), players === 3 ? nodes : 0, 'first', camps, String(rows)));
  const random = seededRandom(seed);
  const history = [];
  const scores = []; // C++ score of each engine move
  const scorePlies = []; // the ply of each of them
  const cppMs = [];
  const cppCpu = [];
  const cppNodes = [];
  const depths = [];
  const javaMs = [];
  const javaCpu = [];
  const disagree = [];
  let compared = 0;
  let outcome = -1;
  while ((outcome = cpp._shw_outcome()) === -1) {
    await yieldToSignals(); // the signal handlers run only on the event loop
    if (stopSignal) return null;
    const ply = history.length;
    if (maxPlies > 0 && ply >= maxPlies) break;
    let move;
    if (ply < openingPlies) {
      const legal = cppLegal();
      move = legal[Math.floor(random() * legal.length)];
    } else {
      const id = ids[cpp._shw_side_to_move()];
      const w0 = performance.now();
      const c0 = cpuMs();
      move = cpp._shw_choose(id);
      cppCpu.push(cpuMs() - c0);
      cppMs.push(performance.now() - w0);
      if (move < 0) throw new Error(`C++ choose returned ${move} at ply ${ply}: ${cpp.UTF8ToString(cpp._shw_error())}`);
      const score = cpp._shw_last_score(id);
      scores.push(score);
      scorePlies.push(ply);
      depths.push(cpp._shw_last_depth(id));
      cppNodes.push(cpp._shw_last_nodes(id));
      if (java && ply < javaPlies) {
        const w1 = performance.now();
        const c1 = cpuMs();
        const f = javaFields(java.choose(history.join(','), ...jargs)).map(Number);
        javaCpu.push(cpuMs() - c1);
        javaMs.push(performance.now() - w1);
        compared++;
        const c = [move, score, cpp._shw_last_depth(id), cpp._shw_last_nodes(id)];
        if (f[0] !== c[0] || f[1] !== c[1] || f[2] !== c[2] || f[3] !== c[3]) {
          disagree.push({ ply, cpp: c, java: f.slice(0, 4) });
        }
      }
    }
    if (cpp._shw_play(move) !== 0) throw new Error(`shw_play refused ${move} at ply ${ply}`);
    history.push(move);
  }
  const plies = history.length;
  const ended = outcome === -1 ? 'cut' : outcome === 0 ? (plies >= moveLimit ? 'limit' : 'nomove') : 'win';
  const winner = outcome > 0 ? outcome - 1 : -1;
  let distance = null;
  if (withDistance && ended === 'win') {
    const d0 = performance.now();
    if (players === 2) {
      const loser = 1 - winner;
      const cap = Math.min(distanceCap, moveLimit - plies);
      const r = soloRace(owners(), loser, start[loser], goal[loser], jump === 'long', cap);
      distance = { kind: 'solo', plies: r.moves, capped: r.capped, ms: Math.round(performance.now() - d0) };
    } else {
      const r = playOutThree(history, rows, jump, camps, distanceCap);
      distance = { kind: 'second', ...r, ms: Math.round(performance.now() - d0) };
    }
  }
  return {
    ...u, engineVersion, moveLimit, plies, ended, winner, opening: openingPlies, history,
    scores, scorePlies, depths, cppNodes, cppMs: cppMs.map(round2), cppCpu: cppCpu.map(round2), javaMs: javaMs.map(round2), javaCpu: javaCpu.map(round2),
    compared, disagree, distance, ms: Math.round(performance.now() - t0),
  };
}

const yieldToSignals = () => new Promise((resolve) => setImmediate(resolve));

function round2(x) {
  return Math.round(x * 100) / 100;
}

// ---------------------------------------------------------------------------------------------------------------- run
const todo = [];
let skip = 0;
for (const nodes of levels) {
  for (const rows of rowsList) {
    for (const players of playersList) {
      for (const jump of jumpList) {
        for (const camps of campsList) {
          for (let g = 0; g < games; g++) {
            const u = { rows, players, jump, camps, nodes, seed: seedBase + g };
            if (existsSync(join(tagDir, `${unitName(u)}.json`))) skip++;
            else todo.push(u);
          }
        }
      }
    }
  }
}
process.stderr.write(`measure-rows5: ${todo.length} game(s) to play, ${skip} skip (done), ${cleanup} cleanup; tag ${values.tag}, engines ${engineVersion}\n`);
let ok = 0;
let err = 0;
const tStart = performance.now();
let workMs = 0; // time of the games played in this run (skips do not count for the rate)
let lastLine = 0;
const weight = (u) => u.nodes * u.players; // rough cost of a game, for the ETA
let doneWeight = 0;
for (const [index, u] of todo.entries()) {
  if (stopSignal) break;
  let unit;
  try {
    unit = await playUnit(u);
  } catch (e) {
    err++;
    process.stderr.write(`measure-rows5: ERROR ${unitName(u)}: ${e.message}\n`);
    continue;
  }
  if (!unit) break; // stopped: the running game is discarded
  const path = join(tagDir, `${unitName(u)}.json`);
  const tmp = `${path}.tmp.${process.pid}`;
  writeFileSync(tmp, JSON.stringify(unit));
  renameSync(tmp, path);
  ok++;
  workMs += unit.ms;
  doneWeight += weight(u);
  const nowMs = performance.now();
  if (nowMs - lastLine >= 5000 || index === todo.length - 1) {
    lastLine = nowMs;
    const leftWeight = todo.slice(index + 1).reduce((s, x) => s + weight(x), 0);
    const eta = doneWeight > 0 ? `${((leftWeight * workMs) / doneWeight / 1000).toFixed(0)}s` : '?';
    process.stderr.write(
      `${index + 1}/${todo.length}  ${unitName(u)} ${unit.plies} plies ${unit.ended}` +
        `${unit.distance ? ` dist ${unit.distance.plies}${unit.distance.capped ? '+' : ''}` : ''}` +
        `${unit.compared ? ` java ${unit.compared - unit.disagree.length}/${unit.compared}` : ''} ${(unit.ms / 1000).toFixed(0)}s  ` +
        `ETA ${eta}  elapsed ${((nowMs - tStart) / 1000).toFixed(0)}s\n`,
    );
  }
}
const left = todo.length - ok - err;
process.stderr.write(`measure-rows5: ${ok} ok, ${skip} skip, ${err} error, ${left} left` +
  `${stopSignal ? ' - stopped' : left === 0 && err === 0 ? ' - nothing more to do' : ''}\n`);
process.exit(stopSignal ? (stopSignal === 'SIGINT' ? 130 : 143) : err ? 1 : 0);

// ---------------------------------------------------------------------------------------------------------------- aggregate
function aggregate() {
  if (!existsSync(tagDir)) die(`no directory ${tagDir}`, 1);
  const units = readdirSync(tagDir).filter((f) => f.endsWith('.json')).map((f) => JSON.parse(readFileSync(join(tagDir, f), 'utf8')));
  if (units.length === 0) die(`no unit files in ${tagDir}`, 1);
  const q = (a, p) => {
    if (a.length === 0) return NaN;
    const s = [...a].sort((x, y) => x - y);
    return s[Math.min(s.length - 1, Math.floor(p * s.length))];
  };
  const mean = (a) => (a.length ? a.reduce((s, x) => s + x, 0) / a.length : NaN);
  const f1 = (x) => (Number.isNaN(x) ? '-' : x.toFixed(1));
  const group = (key) => {
    const m = new Map();
    for (const u of units) m.set(key(u), [...(m.get(key(u)) ?? []), u]);
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b, 'en', { numeric: true }));
  };
  const out = [];
  out.push(`# tag ${values.tag}: ${units.length} games, engines ${[...new Set(units.map((u) => u.engineVersion))].join(',')}`);
  out.push('');
  out.push('## Game length (plies, random opening included) and how games ended');
  out.push('rows\tplayers\tjump\tcamps\tnodes\tgames\tmin\tmedian\tp90\tmax\tlimit\twin\tnomove\tcut\tmoveLimit');
  for (const [, us] of group((u) => `${u.rows}\t${u.players}\t${u.jump}\t${u.camps}\t${String(u.nodes).padStart(7)}`)) {
    const u0 = us[0];
    const p = us.map((u) => u.plies);
    const n = (e) => us.filter((u) => u.ended === e).length;
    out.push([u0.rows, u0.players, u0.jump, u0.camps, u0.nodes, us.length, q(p, 0), q(p, 0.5), q(p, 0.9), q(p, 1), n('limit'), n('win'), n('nomove'), n('cut'), u0.moveLimit].join('\t'));
  }
  out.push('');
  out.push('## Game length by (rows, players, jump), all camps and levels');
  out.push('rows\tplayers\tjump\tgames\tmedian\tp95\tmax\tlimit\tshareLimit');
  for (const [, us] of group((u) => `${u.rows}\t${u.players}\t${u.jump}`)) {
    const p = us.map((u) => u.plies);
    const lim = us.filter((u) => u.ended === 'limit').length;
    out.push([us[0].rows, us[0].players, us[0].jump, us.length, q(p, 0.5), q(p, 0.95), q(p, 1), lim, `${((100 * lim) / us.length).toFixed(1)}%`].join('\t'));
  }
  out.push('');
  out.push('## Scores of the engine moves (C++): largest |score| below RACE_BASE 5000; race-band and mate scores counted');
  out.push('"free" = moves whose search horizon stays clear of the move limit (ply + depth + 4 < moveLimit); near the limit the');
  out.push('search scores the unfinished game (AlphaBeta.END_SCALE 10000 x value), which is not the evaluator scale.');
  out.push('rows\tplayers\tjump\tnodes\tmoves\tmaxOrdinary\tfreeMoves\tmaxFree\tp99Free\trace\tmate');
  for (const [, us] of group((u) => `${u.rows}\t${u.players}\t${u.jump}\t${String(u.nodes).padStart(7)}`)) {
    const s = us.flatMap((u) => u.scores);
    const free = us.flatMap((u) => u.scores.filter((_, i) => u.scorePlies[i] + u.depths[i] + 4 < u.moveLimit));
    const ord = s.filter((x) => Math.abs(x) < RACE_BASE).map(Math.abs);
    const freeOrd = free.filter((x) => Math.abs(x) < RACE_BASE).map(Math.abs);
    const race = s.filter((x) => Math.abs(x) >= RACE_BASE && Math.abs(x) < MATE_BOUND).length;
    const mate = s.filter((x) => Math.abs(x) >= MATE_BOUND).length;
    out.push([us[0].rows, us[0].players, us[0].jump, us[0].nodes, s.length, q(ord, 1), free.length, q(freeOrd, 1), q(freeOrd, 0.99), race, mate].join('\t'));
  }
  out.push('');
  out.push('## Time per engine move (ms; wall and CPU of the Node process; C++ = wasm, Java = TeaVM Wasm GC)');
  out.push('rows\tplayers\tjump\tnodes\tcppMoves\tcppMean\tcppP95\tcppCpuMean\tcppNodes\tdepth\tjavaMoves\tjavaMean\tjavaP95\tjavaCpuMean\tjava/cpp');
  for (const [, us] of group((u) => `${u.rows}\t${u.players}\t${u.jump}\t${String(u.nodes).padStart(7)}`)) {
    const c = us.flatMap((u) => u.cppMs);
    const cc = us.flatMap((u) => u.cppCpu);
    const j = us.flatMap((u) => u.javaMs);
    const jc = us.flatMap((u) => u.javaCpu);
    // the ratio over the same plies: the first javaMs.length C++ moves of each game
    const cSame = us.flatMap((u) => u.cppMs.slice(0, u.javaMs.length));
    out.push([us[0].rows, us[0].players, us[0].jump, us[0].nodes, c.length, f1(mean(c)), f1(q(c, 0.95)), f1(mean(cc)),
      f1(mean(us.flatMap((u) => u.cppNodes))), f1(mean(us.flatMap((u) => u.depths))),
      j.length, f1(mean(j)), f1(q(j, 0.95)), f1(mean(jc)), j.length ? (mean(j) / mean(cSame)).toFixed(2) : '-'].join('\t'));
  }
  out.push('');
  out.push('## Java (TeaVM) vs C++ (wasm): move, score, depth and nodes at each compared ply');
  const compared = units.reduce((s, u) => s + u.compared, 0);
  const dis = units.flatMap((u) => u.disagree.map((d) => ({ unit: unitName(u), ...d })));
  out.push(`compared ${compared} moves in ${units.filter((u) => u.compared > 0).length} games; ${dis.length} differ`);
  for (const d of dis.slice(0, 20)) out.push(`  ${d.unit} ply ${d.ply}: cpp ${d.cpp.join(' ')} java ${d.java.join(' ')}`);
  out.push('');
  out.push('## Distance of the result after a win (2 players: solo race moves; 3 players: playout plies to second place)');
  out.push('rows\tplayers\tjump\tkind\twins\tmin\tmedian\tp90\tp95\tmax\tcapped\tmeanMs\tmaxMs');
  for (const [, us] of group((u) => `${u.rows}\t${u.players}\t${u.jump}`)) {
    const d = us.filter((u) => u.distance).map((u) => u.distance);
    if (d.length === 0) continue;
    const p = d.map((x) => x.plies);
    out.push([us[0].rows, us[0].players, us[0].jump, d[0].kind, d.length, q(p, 0), q(p, 0.5), q(p, 0.9), q(p, 0.95), q(p, 1),
      d.filter((x) => x.capped).length, f1(mean(d.map((x) => x.ms))), q(d.map((x) => x.ms), 1)].join('\t'));
  }
  process.stdout.write(`${out.join('\n')}\n`);
}
