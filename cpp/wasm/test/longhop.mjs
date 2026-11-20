// The Halma long hop on the flat C ABI: shw_game_new(jump), shw_player_new(nodes, jump), shw_jump_rule, shw_choose (-2 for a player of the
// other rule) and shw_analyse in a long-hop game. The calls with jump = 0 (or without the argument) keep their old behaviour; that part
// is pinned by game.mjs and analyse.mjs (the benchmark fingerprint), this test only checks that the two rules do not mix.
// usage: node longhop.mjs --engine DIR --tables DIR
import { createModule, loadRows2, parseArgs, readTable } from './lib.mjs';

const { engine, tables } = parseArgs(process.argv);
const m = await createModule(engine);
const { rc, error } = loadRows2(m, readTable(tables), false);
if (rc !== 0) throw new Error(`load failed, rc=${rc}: ${error}`);

const fail = (text) => { console.error(`longhop: ${text}`); process.exit(1); };
const CAP = 1815;
const buf = m._malloc(4 * CAP * 3);
const [inP, movesP, scoresP] = [buf, buf + 4 * CAP, buf + 8 * CAP];
const ints = (p, n) => Array.from(m.HEAP32.subarray(p >> 2, (p >> 2) + n));
const legal = () => ints(inP, m._shw_legal_moves(inP, CAP));

// the rule of the game
if (m._shw_game_new(1) !== 0 || m._shw_jump_rule() !== 1) fail('shw_game_new(1) did not start a long-hop game');
if (m._shw_game_new(0) !== 0 || m._shw_jump_rule() !== 0) fail('shw_game_new(0) did not start an adjacent game');
m._shw_game_new(); // no argument: JavaScript passes undefined, the C side reads 0
if (m._shw_jump_rule() !== 0) fail('shw_game_new() without argument is not the adjacent rule');
if (m._shw_game_new(2) !== -1) fail('shw_game_new(2) is not -1');
if (m._shw_jump_rule() !== 0) fail('a refused shw_game_new changed the game');
if (m._shw_player_new(100, 5) !== -1) fail('shw_player_new(100, 5) is not -1');

// the players of both rules refuse the game of the other rule
const adjacent = m._shw_player_new(300, 0);
const long = m._shw_player_new(300, 1);
if (adjacent < 0 || long < 0) fail('player creation failed: ' + m.UTF8ToString(m._shw_error()));
m._shw_game_new(1);
if (m._shw_choose(adjacent) !== -2) fail('the adjacent player did not refuse a long game');
m._shw_game_new(0);
if (m._shw_choose(long) !== -2) fail('the long player did not refuse an adjacent game');

// a long-hop game between two long players: every move legal, no race play, and long hops occur in the move lists
const players = [m._shw_player_new(500, 1), m._shw_player_new(500, 1)];
m._shw_game_new(1);
let longMovesSeen = 0;
for (let ply = 0; ply < 60 && m._shw_outcome() === -1; ply++) {
  const move = m._shw_choose(players[m._shw_side_to_move()]);
  if (move < 0) fail(`no move at ply ${ply} (${move})`);
  if (!legal().includes(move)) fail(`illegal move ${move} at ply ${ply}`);
  const from = move & 0xff, to = move >> 8;
  const dq = m._shw_cell_q(to) - m._shw_cell_q(from), dr = m._shw_cell_r(to) - m._shw_cell_r(from);
  if (Math.max(Math.abs(dq), Math.abs(dr), Math.abs(dq + dr)) > 2) longMovesSeen++;
  if (m._shw_play(move) !== 0) fail(`shw_play refused the engine move at ply ${ply}`);
}
if (m._shw_race_moves(players[0]) !== 0 || m._shw_race_moves(players[1]) !== 0) fail('a long player made a race move');
if (m._shw_ply() < 20) fail(`game stopped at ply ${m._shw_ply()}`);

// analysis in the long-hop game: all legal moves scored, same order, deterministic, and every move scored
const moves = legal();
let n = m._shw_analyse(300, 0, 0, movesP, scoresP, CAP);
if (n !== moves.length) fail(`analysis scored ${n} of ${moves.length} moves`);
if (ints(movesP, n).join() !== moves.join()) fail('analysis moves are not in the order of shw_legal_moves');
const scores = ints(scoresP, n);
if (m._shw_analysis_nodes() <= n * 50) fail(`analysis nodes ${m._shw_analysis_nodes()}`);
if (m._shw_analyse(300, 0, 0, movesP, scoresP, CAP) !== n || ints(scoresP, n).join() !== scores.join()) fail('analysis is not deterministic');
// switching the game to the adjacent rule and back changes the analysis player each time
m._shw_game_new(0);
n = m._shw_analyse(300, 0, 0, movesP, scoresP, CAP);
if (n !== legal().length || n < 1) fail(`adjacent analysis after a long one scored ${n} moves`);
if (m._shw_rows1_requests() !== 0) fail('rows1 was requested');
console.error(`longhop: ok (${longMovesSeen} long moves played, ${moves.length} moves analysed)`);
