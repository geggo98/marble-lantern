// The three-player game on the flat C ABI: shw_game_new2(jump, players), shw_players, shw_player_new3(nodes, jump), shw_choose for the side to
// move, the outcome codes (-1 running, 0 draw, 1 + winner), the refusals (-2 for a player that does not match the game), shw_analyse for three players and shw_evaluate3.
// Three engine seats (three players with different budgets) play a whole game of each jump rule; every move must be legal, the sides must
// move in the order 0, 1, 2, the game must end within the move limit of 300 plies, and a second run must give the same moves. The
// three-player player reads no race table, so this test loads none; it also checks that no table was requested.
// usage: node threeplayer.mjs --engine DIR --tables DIR   (--tables is accepted for the common call and not read)
import { createModule, parseArgs } from './lib.mjs';

const { engine } = parseArgs(process.argv);
const m = await createModule(engine);

const fail = (text) => { console.error(`threeplayer: ${text}`); process.exit(1); };
const CAP = 1815;
const buf = m._malloc(4 * CAP * 3);
const [inP, movesP, scoresP] = [buf, buf + 4 * CAP, buf + 8 * CAP];
const ints = (p, n) => Array.from(m.HEAP32.subarray(p >> 2, (p >> 2) + n));
const legal = () => ints(inP, m._shw_legal_moves(inP, CAP));
const errorText = () => m.UTF8ToString(m._shw_error());
const MOVE_LIMIT = 300;

// ---- the game: players, jump rule, start position ----
if (m._shw_game_new2(0, 3) !== 0 || m._shw_players() !== 3 || m._shw_jump_rule() !== 0) fail('shw_game_new2(0, 3) did not start a three-player adjacent game');
if (m._shw_game_new2(1, 3) !== 0 || m._shw_players() !== 3 || m._shw_jump_rule() !== 1) fail('shw_game_new2(1, 3) did not start a three-player long-hop game');
const owners = [0, 0, 0];
for (let c = 0; c < m._shw_cell_count(); c++) { const o = m._shw_owner(c); if (o >= 0) owners[o]++; }
if (owners.join() !== '10,10,10') fail(`start position owners ${owners}`);
if (m._shw_side_to_move() !== 0 || m._shw_outcome() !== -1) fail('the start position is not running with side 0');
if (m._shw_game_new2(0, 4) !== -1 || !errorText().includes('players must be 2 or 3')) fail('shw_game_new2(0, 4) is not refused');
if (m._shw_game_new2(5, 3) !== -1 || m._shw_players() !== 3) fail('a refused shw_game_new2 changed the game');
m._shw_game_new(1); // the old call: two players
if (m._shw_players() !== 2 || m._shw_jump_rule() !== 1) fail('shw_game_new(1) is not a two-player long-hop game');
m._shw_game_new(); // no argument: adjacent, two players
if (m._shw_players() !== 2 || m._shw_jump_rule() !== 0) fail('shw_game_new() is not a two-player adjacent game');
if (m._shw_player_new3(100, 3) !== -1) fail('shw_player_new3(100, 3) is not -1');
if (m._shw_player_new3(0, 0) !== -1) fail('shw_player_new3(0, 0) is not -1');

// ---- a whole game with three engine seats, per jump rule; run twice ----
function playGame(jump, budgets) {
  const seats = budgets.map((nodes) => m._shw_player_new3(nodes, jump));
  if (seats.includes(-1)) fail('player creation failed: ' + errorText());
  if (m._shw_game_new2(jump, 3) !== 0) fail('game creation failed');
  const moves = [];
  let longMoves = 0;
  for (let ply = 0; m._shw_outcome() === -1; ply++) {
    if (ply >= MOVE_LIMIT) fail(`jump ${jump}: still running after ${ply} plies`);
    const side = m._shw_side_to_move();
    if (side !== ply % 3) fail(`jump ${jump}: side ${side} to move at ply ${ply}`);
    const id = seats[side];
    const move = m._shw_choose(id);
    if (move < 0) fail(`jump ${jump}: no move at ply ${ply} (${move})`);
    if (!legal().includes(move)) fail(`jump ${jump}: illegal move ${move} at ply ${ply}`);
    if (!m._shw_has_result(id) || m._shw_last_depth(id) < 1 || m._shw_last_nodes(id) < 1) fail(`jump ${jump}: no search result at ply ${ply}`);
    const from = move & 0xff, to = move >> 8;
    const dq = m._shw_cell_q(to) - m._shw_cell_q(from), dr = m._shw_cell_r(to) - m._shw_cell_r(from);
    if (Math.max(Math.abs(dq), Math.abs(dr), Math.abs(dq + dr)) > 2) longMoves++;
    if (m._shw_owner(from) !== side) fail(`jump ${jump}: the piece moved at ply ${ply} is not the mover's`);
    if (m._shw_play(move) !== 0) fail(`jump ${jump}: shw_play refused the engine move at ply ${ply}`);
    moves.push(move);
  }
  const outcome = m._shw_outcome();
  if (outcome < 0 || outcome > 3) fail(`jump ${jump}: outcome ${outcome}`);
  if (m._shw_ply() !== moves.length || moves.length > MOVE_LIMIT) fail(`jump ${jump}: ply ${m._shw_ply()} after ${moves.length} moves`);
  if (outcome === 0 && moves.length !== MOVE_LIMIT) fail(`jump ${jump}: a draw at ply ${moves.length}, before the move limit`);
  const nodes = seats.reduce((s, id) => s + m._shw_total_nodes(id), 0);
  if (nodes < moves.length) fail(`jump ${jump}: ${nodes} nodes in ${moves.length} moves`);
  if (seats.some((id) => m._shw_race_moves(id) !== 0)) fail(`jump ${jump}: a three-player player made a race move`);
  return { moves, outcome, longMoves, nodes };
}

const summary = [];
for (const jump of [0, 1]) {
  const budgets = [200, 300, 400];
  const first = playGame(jump, budgets);
  const second = playGame(jump, budgets);
  if (first.moves.join() !== second.moves.join() || first.outcome !== second.outcome) fail(`jump ${jump}: two runs of the same game differ`);
  if (jump === 1 && first.longMoves === 0) fail('the long-hop game had no long hop');
  summary.push(`jump ${jump}: ${first.moves.length} plies, outcome ${first.outcome}, ${first.longMoves} long moves, ${first.nodes} nodes`);
}

// ---- unmake works in a three-player game ----
{
  m._shw_game_new2(0, 3);
  const start = legal();
  m._shw_play(start[0]); m._shw_play(legal()[0]); m._shw_play(legal()[0]);
  if (m._shw_side_to_move() !== 0 || m._shw_ply() !== 3) fail('three plies did not return to side 0');
  m._shw_unmake(); m._shw_unmake(); m._shw_unmake();
  if (m._shw_side_to_move() !== 0 || m._shw_ply() !== 0 || legal().join() !== start.join()) fail('unmake did not restore the start position');
}

// ---- players and games must match; analysis is two-player only ----
const three = m._shw_player_new3(100, 0);
const threeLong = m._shw_player_new3(100, 1);
const twoLong = m._shw_player_new(100, 1);
m._shw_game_new2(0, 2);
if (m._shw_choose(three) !== -2 || !errorText().includes('2 players')) fail('a three-player player played a two-player game');
m._shw_game_new2(1, 3);
if (m._shw_choose(twoLong) !== -2 || !errorText().includes('3 players')) fail('a two-player player played a three-player game');
if (m._shw_choose(three) !== -2 || !errorText().includes('other jump rule')) fail('the adjacent three-player player played a long-hop game');
if (m._shw_choose(threeLong) < 0) fail('the long three-player player did not play its game');
// ---- analysis and the static vector work for three players ----
for (const jump of [0, 1]) {
  m._shw_game_new2(jump, 3);
  const all = legal();
  const n = m._shw_analyse(300, 0, 0, movesP, scoresP, CAP);
  if (n !== all.length) fail(`jump ${jump}: shw_analyse scored ${n} of ${all.length} moves`);
  const moves = ints(movesP, n), scores = ints(scoresP, n);
  if (moves.join() !== all.join()) fail(`jump ${jump}: the analysed moves are not the legal moves in order`);
  if (new Set(scores).size < 2) fail(`jump ${jump}: all analysis scores are equal`);
  if (!(m._shw_analysis_nodes() > 0)) fail(`jump ${jump}: no nodes counted`);
  if (m._shw_analyse(300, 0, 0, movesP, scoresP, CAP) !== n || ints(scoresP, n).join() !== scores.join()) fail(`jump ${jump}: three-player analysis is not deterministic`);
  m.HEAP32.set([moves[3], moves[1]], inP >> 2);
  m._shw_analyse(100, 0, 0, movesP, scoresP, CAP); // another budget in between
  if (m._shw_analyse(300, inP, 2, movesP, scoresP, CAP) !== 2) fail(`jump ${jump}: a subset of two moves was not scored`);
  if (ints(scoresP, 2).join() !== [scores[3], scores[1]].join()) fail(`jump ${jump}: a subset in another order gives other scores`);
  m.HEAP32.set([moves[0], 0], inP >> 2);
  if (m._shw_analyse(300, inP, 2, movesP, scoresP, CAP) !== -1) fail(`jump ${jump}: an illegal move is not -1`);
  if (m._shw_analyse(300, 0, 0, movesP, scoresP, 3) !== -3) fail(`jump ${jump}: a small capacity is not -3`);
  if (m._shw_analyse(0, 0, 0, movesP, scoresP, CAP) !== -5) fail(`jump ${jump}: nodes 0 is not -5`);
  if (m._shw_evaluate3(movesP) !== 0) fail(`jump ${jump}: shw_evaluate3 failed`);
  const v = ints(movesP, 3);
  if (v[0] !== v[1] || v[1] !== v[2]) fail(`jump ${jump}: the start vector is not symmetric: ${v}`);
  for (let ply = 0; ply < 30; ply++) { const l = legal(); m._shw_play(l[(ply * 7) % l.length]); }
  m._shw_evaluate3(movesP);
  const w = ints(movesP, 3);
  if (w[0] === w[1] && w[1] === w[2]) fail(`jump ${jump}: the vector after 30 plies is still symmetric`);
}
m._shw_game_new2(0, 2);
if (m._shw_evaluate3(movesP) !== -1 || !errorText().includes('three-player')) fail('shw_evaluate3 in a two-player game is not -1');
m._shw_game_new(0);
if (m._shw_rows1_requests() !== 0) fail('rows1 was requested');
console.error(`threeplayer: ok (${summary.join('; ')})`);
