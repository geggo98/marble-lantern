// shw_analyse on the flat C ABI: return codes, the order of the moves, determinism, and that an analysis leaves the game players alone
// (the benchmark game of game.mjs, with an analysis after every move, still has the fingerprint of the plan).
// usage: node analyse.mjs --engine DIR --tables DIR
import { BENCH, createModule, loadRows2, parseArgs, readTable } from './lib.mjs';

const { engine, tables } = parseArgs(process.argv);
const m = await createModule(engine);
const { rc, error } = loadRows2(m, readTable(tables), false);
if (rc !== 0) throw new Error(`load failed, rc=${rc}: ${error}`);

const fail = (text) => { console.error(`analyse: ${text}`); process.exit(1); };
const CAP = 1815;
const buf = m._malloc(4 * CAP * 3);
const [inP, movesP, scoresP] = [buf, buf + 4 * CAP, buf + 8 * CAP];
const ints = (p, n) => Array.from(m.HEAP32.subarray(p >> 2, (p >> 2) + n));
const legal = () => { const n = m._shw_legal_moves(inP, CAP); return ints(inP, n); };

m._shw_game_new();
const start = legal();
if (start.length < 10) fail(`start position has ${start.length} legal moves`);

// all legal moves: same order as shw_legal_moves
let n = m._shw_analyse(500, 0, 0, movesP, scoresP, CAP);
if (n !== start.length) fail(`returned ${n}, expected ${start.length}`);
const moves = ints(movesP, n);
const scores = ints(scoresP, n);
if (moves.join() !== start.join()) fail('moves are not in the order of shw_legal_moves');
if (m._shw_analysis_nodes() <= n * 100) fail(`nodes ${m._shw_analysis_nodes()}`);

// a subset in another order gives the same scores, a second call gives the same numbers again
m.HEAP32.set([start[4], start[1]], inP >> 2);
n = m._shw_analyse(500, inP, 2, movesP, scoresP, CAP);
if (n !== 2 || ints(movesP, 2).join() !== [start[4], start[1]].join() || ints(scoresP, 2).join() !== [scores[4], scores[1]].join()) fail('subset differs');

// error codes
m.HEAP32.set([start[0], 0], inP >> 2);
if (m._shw_analyse(500, inP, 2, movesP, scoresP, CAP) !== -1) fail('illegal move is not -1');
if (m._shw_analyse(500, 0, 0, movesP, scoresP, 3) !== -3) fail('small capacity is not -3');
if (m._shw_analyse(0, 0, 0, movesP, scoresP, CAP) !== -5) fail('nodes 0 is not -5');

// the benchmark game with an analysis after every move: the fingerprint of the plan
const players = [m._shw_player_new(BENCH.nodes), m._shw_player_new(BENCH.nodes)];
m._shw_game_new();
let checksum = 0n;
let nodes = 0;
for (let ply = 0; ply < BENCH.plies; ply++) {
  const id = players[m._shw_side_to_move()];
  const move = m._shw_choose(id);
  checksum = BigInt.asIntN(64, checksum * 31n + BigInt(move));
  nodes += m._shw_last_nodes(id);
  if (m._shw_play(move) !== 0) fail(`illegal move at ply ${ply}`);
  if (m._shw_analyse(300 + 100 * (ply % 3), 0, 0, movesP, scoresP, CAP) < 1) fail(`analysis at ply ${ply}`);
}
if (checksum !== BENCH.checksum || nodes !== BENCH.totalNodes) fail(`fingerprint changed: checksum=${checksum} nodes=${nodes}`);
if (m._shw_rows1_requests() !== 0) fail('rows1 was requested');

// a finished game: no moves to score
m._shw_game_new();
while (m._shw_outcome() === -1) if (m._shw_play(m._shw_choose(players[m._shw_side_to_move()])) !== 0) fail('illegal move');
if (m._shw_analyse(100, 0, 0, movesP, scoresP, CAP) !== -2) fail('finished game is not -2');
console.error('analyse: ok');
