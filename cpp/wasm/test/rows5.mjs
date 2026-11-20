// The house size (docs/rules.md, "Board size") on the flat C ABI: shw_game_new5(jump, players, ranking, camps, rows), shw_triangle_rows,
// shw_move_limit, shw_player_new_rows, shw_player_new3_rows. Five rows: 15 pieces per player, each standing in its start house, 250 plies
// (two players) or 375 (three) as the move limit, the engine plays legal moves with both jump rules, and the analysis follows the house
// size. The calls with fewer arguments keep the 4-row board. The module runs with the stack of the build (-sSTACK_SIZE) and shw_analyse
// keeps its move lists in static storage; a game played to a high ply count shows that the 7.3 KB move lists fit.
// usage: node rows5.mjs --engine DIR --tables DIR
import { createModule, loadRows2, parseArgs, readTable } from './lib.mjs';

const { engine, tables } = parseArgs(process.argv);
const m = await createModule(engine);
const { rc, error } = loadRows2(m, readTable(tables), false);
if (rc !== 0) throw new Error(`load failed, rc=${rc}: ${error}`);

const fail = (text) => { console.error(`rows5: ${text}`); process.exit(1); };
const CAP = 1815;
const buf = m._malloc(4 * CAP * 3);
const [inP, movesP, scoresP] = [buf, buf + 4 * CAP, buf + 8 * CAP];
const ints = (p, n) => Array.from(m.HEAP32.subarray(p >> 2, (p >> 2) + n));
const legal = () => ints(inP, m._shw_legal_moves(inP, CAP));
const owners = () => Array.from({ length: m._shw_cell_count() }, (_, c) => m._shw_owner(c));

// the start position: 15 pieces per player (10 on the 4-row board), and the cells owned differ between the two sizes
for (const players of [2, 3]) {
  if (m._shw_game_new5(0, players, 0, 0, 5) !== 0 || m._shw_triangle_rows() !== 5) fail(`shw_game_new5(0, ${players}, 0, 0, 5) did not start a 5-row game`);
  const five = owners();
  for (let p = 0; p < players; p++) {
    const n = five.filter((o) => o === p).length;
    if (n !== 15) fail(`${players} players, rows=5: player ${p} has ${n} pieces`);
  }
  if (m._shw_move_limit() !== (players === 2 ? 250 : 375)) fail(`${players} players, rows=5: move limit ${m._shw_move_limit()}`);
  m._shw_game_new5(0, players, 0, 0, 4);
  const four = owners();
  if (m._shw_triangle_rows() !== 4 || m._shw_move_limit() !== (players === 2 ? 200 : 300)) fail(`${players} players, rows=4: size or move limit wrong`);
  for (let p = 0; p < players; p++) {
    const n = four.filter((o) => o === p).length;
    if (n !== 10) fail(`${players} players, rows=4: player ${p} has ${n} pieces`);
  }
  // every 4-row piece cell is a 5-row piece cell of the same player (the 10-cell corner lies inside the 15-cell house)
  for (let c = 0; c < four.length; c++) if (four[c] >= 0 && five[c] !== four[c]) fail(`${players} players: cell ${c} is owner ${four[c]} on 4 rows and ${five[c]} on 5 rows`);
}
if (m._shw_game_new(0) !== 0 || m._shw_triangle_rows() !== 4) fail('shw_game_new(0) is not the 4-row board');
if (m._shw_game_new4(1, 2, 0, 2) !== 0 || m._shw_triangle_rows() !== 4) fail('shw_game_new4 is not the 4-row board');

// refused sizes leave the game alone
m._shw_game_new5(0, 2, 0, 0, 5);
for (const rows of [0, 3, 6, -1]) {
  if (m._shw_game_new5(1, 3, 0, 0, rows) !== -1) fail(`shw_game_new5 with rows ${rows} is not -1`);
  if (m._shw_triangle_rows() !== 5 || m._shw_players() !== 2) fail(`a refused shw_game_new5 (rows ${rows}) changed the game`);
}
if (m._shw_player_new_rows(100, 0, 6) !== -1 || m._shw_player_new3_rows(100, 0, 6) !== -1) fail('a player of rows 6 was created');
if (!m.UTF8ToString(m._shw_error()).includes('rows must be 4 or 5')) fail('the error text of a refused size: ' + m.UTF8ToString(m._shw_error()));

// the engine plays legal moves with both jump rules and 2 and 3 players, and refuses the other size
for (const jump of [0, 1]) {
  const two = m._shw_player_new_rows(300, jump, 5);
  const three = m._shw_player_new3_rows(300, jump, 5);
  if (two < 0 || three < 0) fail(`player creation failed: ${m.UTF8ToString(m._shw_error())}`);
  for (const [players, player] of [[2, two], [3, three]]) {
    m._shw_game_new5(jump, players, 0, 0, 4);
    if (m._shw_choose(player) !== -2) fail(`jump ${jump}: the 5-row player did not refuse a 4-row game with ${players} players`);
    m._shw_game_new5(jump, players, 0, 0, 5);
    for (let ply = 0; ply < 60 && m._shw_outcome() === -1; ply++) {
      const move = m._shw_choose(player);
      if (move < 0) fail(`jump ${jump}, ${players} players: no move at ply ${ply} (${move})`);
      if (!legal().includes(move)) fail(`jump ${jump}, ${players} players: illegal move ${move} at ply ${ply}`);
      if (m._shw_play(move) !== 0) fail(`jump ${jump}, ${players} players: shw_play refused the engine move at ply ${ply}`);
    }
    if (m._shw_ply() < 30) fail(`jump ${jump}, ${players} players: game stopped at ply ${m._shw_ply()}`);
  }
  if (m._shw_race_moves(two) !== 0) fail(`jump ${jump}: a 5-row player made a race move`);
}

// analysis: all legal moves scored, deterministic, and the cached analysis players follow the size (4-row, 5-row, 4-row with one budget)
for (const players of [2, 3]) {
  m._shw_game_new5(1, players, 0, 0, 4);
  if (m._shw_analyse(100, 0, 0, movesP, scoresP, CAP) < 1) fail(`${players} players: analysis of the 4-row game failed`);
  m._shw_game_new5(1, players, 0, 0, 5);
  const moves = legal();
  const n = m._shw_analyse(100, 0, 0, movesP, scoresP, CAP);
  if (n !== moves.length) fail(`${players} players, rows=5: analysis scored ${n} of ${moves.length} moves`);
  if (ints(movesP, n).join() !== moves.join()) fail(`${players} players, rows=5: analysis moves are not in the order of shw_legal_moves`);
  const scores = ints(scoresP, n);
  if (m._shw_analyse(100, 0, 0, movesP, scoresP, CAP) !== n || ints(scoresP, n).join() !== scores.join()) fail(`${players} players, rows=5: analysis is not deterministic`);
  m._shw_game_new5(1, players, 0, 0, 4);
  if (m._shw_analyse(100, 0, 0, movesP, scoresP, CAP) < 1) fail(`${players} players: analysis of the 4-row game after a 5-row game failed`);
}
m._shw_game_new5(0, 3, 0, 0, 5);
const e = m._malloc(12);
if (m._shw_evaluate3(e) !== 0) fail('shw_evaluate3 on a 5-row game: ' + m.UTF8ToString(m._shw_error()));
const start = ints(e, 3);
if (start[0] !== start[1] || start[1] !== start[2]) fail(`the 5-row start position is not symmetric: ${start}`);

// a longer game with the analysis in between: deep move generation and analysis use the stack of the build
m._shw_game_new5(1, 2, 0, 0, 5);
const player = m._shw_player_new_rows(150, 1, 5);
for (let ply = 0; ply < 120 && m._shw_outcome() === -1; ply++) {
  const move = m._shw_choose(player);
  if (move < 0 || m._shw_play(move) !== 0) fail(`long game: no legal engine move at ply ${ply}`);
  if (ply % 30 === 29 && m._shw_analyse(100, 0, 0, movesP, scoresP, CAP) < 1) fail(`long game: analysis at ply ${ply}`);
}
console.log(`rows5: ok (ply ${m._shw_ply()})`);
