// shw_features on the WebAssembly build: the linear-r3 features of the eval-mid.json position (golden of ExplainerDataTest, from the JVM
// evaluator) with the score 179, the refusal of a game that is not two players, adjacent jumps and closed corners (4 or 5 rows), and that
// features calls leave the game players alone (the benchmark game of game.mjs, with a features call before every move, still has the
// fingerprint of the plan).
// usage: node features.mjs --engine DIR --tables DIR
import { readFileSync } from 'node:fs';
import { BENCH, createModule, loadRows2, parseArgs, readTable } from './lib.mjs';

const { engine, tables } = parseArgs(process.argv);
const m = await createModule(engine);
const { rc, error } = loadRows2(m, readTable(tables), false);
if (rc !== 0) throw new Error(`load failed, rc=${rc}: ${error}`);

const fail = (text) => { console.error(`features: ${text}`); process.exit(1); };
const golden = JSON.parse(readFileSync(new URL('../../../web/src/about/data/eval-mid.json', import.meta.url), 'utf8'));
// 13 doubles, then score and side, then 242 distances; malloc aligns to 8 bytes at least
const base = m._malloc(13 * 8 + 2 * 4 + 242 * 4);
const scoreSide = base + 13 * 8;
const dist = scoreSide + 8;
const read = () => ({
  values: Array.from(new Float64Array(m.HEAPU8.buffer, base, 13)),
  score: m.HEAP32[scoreSide >> 2],
  side: m.HEAP32[(scoreSide >> 2) + 1],
  distance: Array.from(m.HEAP32.subarray(dist >> 2, (dist >> 2) + 242)),
});

// the golden position
if (m._shw_game_new5(0, 2, 0, 0, 4) !== 0) fail('shw_game_new5');
for (const { from, to } of golden.history) if (m._shw_play(from | (to << 8)) !== 0) fail(`illegal golden move ${from}->${to}`);
if (m._shw_features(base, scoreSide, dist) !== 0) fail(`shw_features on the golden position: ${m.UTF8ToString(m._shw_error())}`);
const mid = read();
golden.features.forEach((f, i) => { if (Math.abs(mid.values[i] - f.value) > 1e-4) fail(`${f.name}: ${mid.values[i]}, golden ${f.value}`); });
if (mid.score !== 179 || mid.score !== golden.meta.score) fail(`score ${mid.score}, golden ${golden.meta.score}`);
if (mid.side !== 0) fail(`side to move ${mid.side}`);
for (const s of golden.sides) {
  for (const p of s.pieces) {
    const d = mid.distance[s.player * 121 + p.cell];
    if (d !== p.distance) fail(`distance of player ${s.player} at cell ${p.cell}: ${d}, golden ${p.distance}`);
  }
}

// not the classic game: -1 with a text
for (const [jump, players, camps, rows] of [[1, 2, 0, 4], [0, 3, 0, 4], [0, 2, 2, 4], [0, 2, 1, 4], [1, 2, 0, 5], [0, 3, 0, 5], [0, 2, 2, 5]]) {
  if (m._shw_game_new5(jump, players, 0, camps, rows) !== 0) fail('shw_game_new5');
  if (m._shw_features(base, scoreSide, dist) !== -1) fail(`a non-classic game (${jump} ${players} ${camps} ${rows}) is not -1`);
  if (!m.UTF8ToString(m._shw_error()).includes('classic')) fail('no text for the non-classic game');
}

// the 5-row classic game is explained as well (the values are compared with Java in web/src/engines/features.cross.test.ts)
if (m._shw_game_new5(0, 2, 0, 0, 5) !== 0) fail('shw_game_new5 on 5 rows');
if (m._shw_features(base, scoreSide, dist) !== 0) fail(`features on 5 rows: ${m.UTF8ToString(m._shw_error())}`);
if (read().side !== 0) fail('side to move on the 5-row start');

// the benchmark game with a features call before every move: the fingerprint of the plan
const players = [m._shw_player_new(BENCH.nodes), m._shw_player_new(BENCH.nodes)];
m._shw_game_new();
let checksum = 0n;
let nodes = 0;
for (let ply = 0; ply < BENCH.plies; ply++) {
  if (m._shw_features(base, scoreSide, dist) !== 0) fail(`features at ply ${ply}`);
  if (read().side !== ply % 2) fail(`side to move at ply ${ply}`);
  const id = players[m._shw_side_to_move()];
  const move = m._shw_choose(id);
  checksum = BigInt.asIntN(64, checksum * 31n + BigInt(move));
  nodes += m._shw_last_nodes(id);
  if (m._shw_play(move) !== 0) fail(`illegal move at ply ${ply}`);
}
if (checksum !== BENCH.checksum || nodes !== BENCH.totalNodes) fail(`fingerprint changed: checksum=${checksum} nodes=${nodes}`);
if (m._shw_rows1_requests() !== 0) fail('rows1 was requested');
console.error('features: ok');
