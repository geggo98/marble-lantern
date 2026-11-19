// The full-ranking game on the flat C ABI: shw_game_new3(jump, players, ranking), shw_ranking_rule, shw_rank(i), shw_finished_mask. Three engine
// seats play a whole game of each jump rule with full ranking. At every ply: the side to move has not finished, the finished set only grows,
// shw_rank(0) is the first finisher while the game runs; at the end the three ranks are three different seats, shw_outcome is 1 + the first
// rank, and the game has no legal move. A game of ranking 0 ends at the first winner as before, and two players ignore the switch.
// The three-player player reads no race table, so this test loads none.
// usage: node ranking.mjs --engine DIR --tables DIR   (--tables is accepted for the common call and not read)
import { createModule, parseArgs } from './lib.mjs';

const { engine } = parseArgs(process.argv);
const m = await createModule(engine);

const fail = (text) => { console.error(`ranking: ${text}`); process.exit(1); };
const CAP = 1210;
const buf = m._malloc(4 * CAP);
const errorText = () => m.UTF8ToString(m._shw_error());

// ---- starting a game ----
if (m._shw_game_new3(0, 3, 1) !== 0 || m._shw_players() !== 3 || m._shw_ranking_rule() !== 1) fail('shw_game_new3(0, 3, 1) did not start a full-ranking game');
if (m._shw_finished_mask() !== 0 || m._shw_rank(0) !== -1) fail('a new game has finished seats or a rank');
if (m._shw_game_new3(0, 3, 0) !== 0 || m._shw_ranking_rule() !== 0) fail('shw_game_new3(0, 3, 0) did not start a first-winner game');
if (m._shw_game_new3(0, 3, 2) !== -1 || !errorText().includes('ranking must be')) fail('shw_game_new3(0, 3, 2) is not refused');
if (m._shw_ranking_rule() !== 0) fail('a refused shw_game_new3 changed the game');
m._shw_game_new2(0, 3); // the old call is first-winner rules
if (m._shw_ranking_rule() !== 0) fail('shw_game_new2 is not a first-winner game');
if (m._shw_game_new3(1, 2, 1) !== 0 || m._shw_players() !== 2) fail('two players with ranking 1 did not start');

// ---- a whole game per jump rule ----
function playGame(jump) {
  const seats = [100, 150, 200].map((nodes) => m._shw_player_new3(nodes, jump));
  if (seats.includes(-1)) fail('player creation failed: ' + errorText());
  if (m._shw_game_new3(jump, 3, 1) !== 0) fail('game creation failed');
  let mask = 0;
  let firstOut = -1;
  const moves = [];
  for (let ply = 0; m._shw_outcome() === -1; ply++) {
    if (ply >= 300) fail(`jump ${jump}: still running after ${ply} plies`);
    const side = m._shw_side_to_move();
    if ((m._shw_finished_mask() >> side & 1) !== 0) fail(`jump ${jump}: finished seat ${side} is to move at ply ${ply}`);
    const now = m._shw_finished_mask();
    if ((now & mask) !== mask) fail(`jump ${jump}: the finished set shrank at ply ${ply}`);
    mask = now;
    if (mask !== 0 && firstOut < 0) firstOut = ply;
    if (mask !== 0 && (mask >> m._shw_rank(0) & 1) !== 1) fail(`jump ${jump}: rank 0 is not a finished seat at ply ${ply}`);
    const move = m._shw_choose(seats[side]);
    if (move < 0) fail(`jump ${jump}: no move at ply ${ply} (${move})`);
    const n = m._shw_legal_moves(buf, CAP);
    const legal = Array.from(m.HEAP32.subarray(buf >> 2, (buf >> 2) + n));
    if (!legal.includes(move)) fail(`jump ${jump}: illegal move ${move} at ply ${ply}`);
    if (m._shw_owner(move & 0xff) !== side) fail(`jump ${jump}: the piece moved at ply ${ply} is not the mover's`);
    if (m._shw_play(move) !== 0) fail(`jump ${jump}: shw_play refused the engine move at ply ${ply}`);
    moves.push(move);
  }
  const ranks = [0, 1, 2].map((i) => m._shw_rank(i));
  if (new Set(ranks).size !== 3 || ranks.some((r) => r < 0 || r > 2)) fail(`jump ${jump}: ranks ${ranks} are not three different seats`);
  if (m._shw_rank(3) !== -1) fail('shw_rank(3) is not -1');
  const outcome = m._shw_outcome();
  if (outcome !== 1 + ranks[0] && outcome !== 0) fail(`jump ${jump}: outcome ${outcome} does not fit the ranking ${ranks}`);
  if (m._shw_legal_moves(buf, CAP) !== 0) fail(`jump ${jump}: a finished game has legal moves`);
  if (firstOut < 0) fail(`jump ${jump}: no seat finished before the end`);
  return { moves, ranks, firstOut };
}

for (const jump of [0, 1]) {
  const a = playGame(jump);
  const b = playGame(jump);
  if (a.moves.join() !== b.moves.join() || a.ranks.join() !== b.ranks.join()) fail(`jump ${jump}: a second run gave another game`);
  console.log(`ranking: jump ${jump}: ${a.moves.length} plies, ranks ${a.ranks}, first seat out at ply ${a.firstOut}`);
}
if (m._shw_rows1_requests() !== 0) fail('a table was requested');
console.log('ranking: ok');
