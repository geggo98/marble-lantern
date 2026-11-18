// The benchmark game on the C++ WebAssembly engine: 40 plies, BROWSER_SPEC with 20,000 nodes per move, both sides one player each
// for the whole game. Prints the fingerprint line the plan names. Run twice, with the table checked by the engine (verify = 1) and
// not checked (verify = 0, as the page does after its own SHA-256 check); both runs must give the same line.
// usage: node game.mjs --engine DIR --tables DIR
import { performance } from 'node:perf_hooks';
import { BENCH, createModule, loadRows2, parseArgs, readTable } from './lib.mjs';

const { engine, tables } = parseArgs(process.argv);
const raw = readTable(tables);

async function play(verify) {
  const m = await createModule(engine);
  let t = performance.now();
  const { rc, error } = loadRows2(m, raw, verify);
  const loadMs = performance.now() - t;
  if (rc !== 0) throw new Error(`load failed, rc=${rc}: ${error}`);
  const players = [m._shw_player_new(BENCH.nodes), m._shw_player_new(BENCH.nodes)];
  if (players.includes(-1)) throw new Error('player creation failed: ' + m.UTF8ToString(m._shw_error()));
  m._shw_game_new();
  let checksum = 0n;
  let nodes = 0;
  let chooseMs = 0;
  for (let ply = 0; ply < BENCH.plies; ply++) {
    if (m._shw_outcome() !== -1) throw new Error(`game over after ${ply} plies`);
    const id = players[m._shw_side_to_move()];
    t = performance.now();
    const move = m._shw_choose(id);
    chooseMs += performance.now() - t;
    if (move < 0) throw new Error(`no move at ply ${ply}`);
    if (!m._shw_has_result(id)) throw new Error(`no search result at ply ${ply}`);
    checksum = BigInt.asIntN(64, checksum * 31n + BigInt(move));
    nodes += m._shw_last_nodes(id);
    if (m._shw_play(move) !== 0) throw new Error(`illegal engine move ${move} at ply ${ply}`);
  }
  const totalNodes = m._shw_total_nodes(players[0]) + m._shw_total_nodes(players[1]);
  if (totalNodes !== nodes) throw new Error(`nodes: ${nodes} from the results, ${totalNodes} from the players`);
  const rows1Requests = m._shw_rows1_requests();
  console.error(`verify=${verify ? 1 : 0}: load ${loadMs.toFixed(1)} ms, ${BENCH.plies} plies in ${chooseMs.toFixed(0)} ms (${(nodes / (chooseMs / 1000) / 1e6).toFixed(2)} M nodes/s)`);
  return { checksum, nodes, rows1Requests };
}

const lines = [];
for (const verify of [true, false]) {
  const r = await play(verify);
  lines.push(`checksum=${r.checksum} nodes=${r.nodes} rows1Requests=${r.rows1Requests}`);
}
console.log(lines[0]);
const expected = `checksum=${BENCH.checksum} nodes=${BENCH.totalNodes} rows1Requests=0`;
if (lines[1] !== lines[0]) { console.error(`verify=0 differs: ${lines[1]}`); process.exit(1); }
if (lines[0] !== expected) { console.error(`expected ${expected}`); process.exit(1); }
console.error('game: fingerprint ok');
