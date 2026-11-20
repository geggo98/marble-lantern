// The foreign-corner rule (docs/rules.md, "Foreign corners") on the flat C ABI: shw_game_new4(jump, players, ranking, camps) and
// shw_foreign_camps. The older entry points start closed games. Under open corners the browser player (race play on) plays legal moves,
// and a seeded random game reaches a move that ends on a foreign cell, which the closed rule then refuses.
// usage: node camps.mjs --engine DIR --tables DIR
import { createModule, loadRows2, parseArgs, readTable } from './lib.mjs';

const { engine, tables } = parseArgs(process.argv);
const m = await createModule(engine);
const { rc, error } = loadRows2(m, readTable(tables), false);
if (rc !== 0) throw new Error(`load failed, rc=${rc}: ${error}`);

const fail = (text) => { console.error(`camps: ${text}`); process.exit(1); };
const CAP = 1210;
const buf = m._malloc(4 * CAP);
const legal = () => Array.from(m.HEAP32.subarray(buf >> 2, (buf >> 2) + m._shw_legal_moves(buf, CAP)));

for (const camps of [0, 1, 2]) {
  if (m._shw_game_new4(0, 2, 0, camps) !== 0 || m._shw_foreign_camps() !== camps) fail(`shw_game_new4(0, 2, 0, ${camps}) did not take the rule`);
}
if (m._shw_game_new4(0, 2, 0, 3) !== -1) fail('shw_game_new4 with camps 3 is not -1');
if (m._shw_foreign_camps() !== 2) fail('a refused shw_game_new4 changed the game');
if (m._shw_game_new3(0, 3, 0) !== 0 || m._shw_foreign_camps() !== 0) fail('shw_game_new3 does not start a closed game');

// the browser player in an open game: legal moves only
const player = m._shw_player_new(300, 0);
m._shw_game_new4(0, 2, 0, 2);
for (let ply = 0; ply < 30 && m._shw_outcome() < 0; ply++) {
  const moves = legal();
  const move = m._shw_choose(player);
  if (!moves.includes(move)) fail(`ply ${ply}: shw_choose gave ${move}, not a legal move under open corners`);
  if (m._shw_play(move) !== 0) fail(`ply ${ply}: shw_play refused the chosen move`);
}

// a seeded random open game until a move exists that the closed rule does not have
let seed = 7;
const next = (n) => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed % n; };
const history = [];
let found = -1;
m._shw_game_new4(0, 2, 0, 2);
for (let ply = 0; ply < 200 && found < 0; ply++) {
  const open = legal();
  m._shw_game_new4(0, 2, 0, 0);
  let ok = true;
  for (const h of history) ok &&= m._shw_play(h) === 0;
  if (!ok) fail('the closed rule refused a history without foreign moves');
  const closed = new Set(legal());
  found = open.find((mv) => !closed.has(mv)) ?? -1;
  m._shw_game_new4(0, 2, 0, 2);
  for (const h of history) m._shw_play(h);
  if (found < 0) {
    const mv = open[next(open.length)];
    history.push(mv);
    m._shw_play(mv);
  }
}
if (found < 0) fail('no move of the open rule that the closed rule lacks within 200 plies');
m._shw_game_new4(0, 2, 0, 0);
for (const h of history) m._shw_play(h);
if (m._shw_play(found) !== -1) fail('the closed rule accepted a move into a foreign corner');
console.error(`camps: ok (open-only move ${found & 0xff}-${found >> 8} after ${history.length} plies)`);
