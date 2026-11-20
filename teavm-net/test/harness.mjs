// Node harness of the net-MCTS facade (sternhalma.webnet.WebNet), both TeaVM targets, with a stand-in for the network.
//
//   node teavm-net/test/harness.mjs golden [js|wasm-gc ...]   plays the golden games and compares them with the JVM's
//   node teavm-net/test/harness.mjs game   [js|wasm-gc ...]   plays whole 2-player games to the end, checks every move
//   node teavm-net/test/harness.mjs bench  [js|wasm-gc ...]   Java-side overhead per 200 simulations, batch 1 and 4
//
// The stand-in ("hash backend") is the one of HashBackend.java: integer arithmetic and float rounding only, so the JVM
// and the browser builds compute the same outputs and must choose the same moves. Exit: 0 = ok, 1 = a check failed.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const gen = join(here, '..', 'build', 'generated', 'teavm');

export async function loadTarget(target) {
  if (target === 'js') return await import(pathToFileURL(join(gen, 'js', 'engine-net.js')).href);
  if (!globalThis.TeaVM?.wasmGC) await import(pathToFileURL(join(gen, 'wasm-gc', 'engine-net.wasm-runtime.js')).href);
  return (await globalThis.TeaVM.wasmGC.load(join(gen, 'wasm-gc', 'engine-net.wasm'))).exports;
}

/** The reply of a control call: the fields of "ok ..." or an Error for "error <code> <message>". */
export function fields(reply) {
  if (reply === 'ok') return [];
  if (typeof reply === 'string' && reply.startsWith('ok ')) return reply.slice(3).split(' ');
  throw new Error(`facade: ${reply}`);
}

// ---- the hash backend (see HashBackend.java) ----

function hash(input, offset, size) {
  let h = 0;
  for (let i = 0; i < size; i++) {
    const v = input[offset + i];
    if (v !== 0) {
      h = Math.imul(h ^ (i + 1), 0x9e3779b1);
      h ^= (Math.fround(v * 1024)) | 0;
    }
  }
  return h;
}

function output(h, k, scale) {
  let x = Math.imul((h + Math.imul(k, 0x632be5ab)) | 0, 0x85ebca6b);
  x ^= x >>> 13;
  return Math.fround(((x & 0xffff) / 65536 - 0.5) * scale);
}

export function hashForward(input, inputSize, n, valueWidth) {
  const from = new Float32Array(n * 121);
  const to = new Float32Array(n * 121);
  const values = new Float32Array(n * valueWidth);
  for (let b = 0; b < n; b++) {
    const h = hash(input, b * inputSize, inputSize);
    for (let c = 0; c < 121; c++) {
      from[b * 121 + c] = output(h, 2 * c, 4);
      to[b * 121 + c] = output(h, 2 * c + 1, 4);
    }
    for (let k = 0; k < valueWidth; k++) values[b * valueWidth + k] = output(h, 1000 + k, 1);
  }
  return [from, to, values];
}

/** The cheapest possible backend: uniform policy, zero values. Used to time the Java side alone. */
export function zeroForward(_input, _inputSize, n, valueWidth) {
  return [new Float32Array(n * 121), new Float32Array(n * 121), new Float32Array(n * valueWidth)];
}

/** One move: begin, the evaluation loop, best. Returns the parsed `best` and the number of network calls. */
export function chooseMove(m, history, inputSize, valueWidth, forward) {
  let n = Number(fields(m.begin(history))[0]);
  let calls = 0;
  let positions = 0;
  while (n > 0) {
    const input = m.inputs();
    if (input.length !== n * inputSize) throw new Error(`inputs(): ${input.length} floats for ${n} positions of ${inputSize}`);
    const [from, to, values] = forward(input, inputSize, n, valueWidth);
    calls++;
    positions += n;
    n = Number(fields(m.apply(from, to, values))[0]);
  }
  const f = fields(m.best());
  const list = (s) => (s ? s.split(',').map(Number) : []);
  return { move: Number(f[0]), rootValue: Number(f[1]), sims: Number(f[2]), nodes: Number(f[3]), exact: Number(f[4]), race: f[5] === '1', moves: list(f[6]), visits: list(f[7]), calls, positions };
}

const VARIANTS = [
  { name: '2p-adjacent', jump: 'adjacent', players: '2', planes: 'classic1', width: 2 },
  { name: '2p-long', jump: 'long', players: '2', planes: 'classic1v', width: 3 },
  { name: '3p-adjacent', jump: 'adjacent', players: '3', planes: 'classic1v', width: 3 },
  { name: '3p-long', jump: 'long', players: '3', planes: 'classic1v', width: 3 },
];
const GOLDEN = { plies: 14, sims: 48, batch: 4, seed: 20261119 }; // GoldenGame.java

function setup(m, v, sims, batch, seed, options = '') {
  fields(m.create(v.jump, v.players, sims, batch, seed, options, 'closed'));
  const [inputSize, width] = fields(m.setWeightsInfo(v.planes, v.width)).map(Number);
  if (width !== v.width) throw new Error(`value width ${width}`);
  return inputSize;
}

function golden(m, target) {
  let failures = 0;
  for (const v of VARIANTS) {
    const expected = readFileSync(join(here, 'golden', `${v.name}.txt`), 'utf8').trimEnd().split('\n');
    const inputSize = setup(m, v, GOLDEN.sims, GOLDEN.batch, GOLDEN.seed);
    const history = [];
    for (let ply = 0; ply < GOLDEN.plies; ply++) {
      const b = chooseMove(m, history.join(','), inputSize, v.width, hashForward);
      const line = `${b.move} ${b.sims} ${b.nodes} ${b.exact} ${b.visits.join(',')}`;
      if (line !== expected[ply]) {
        failures++;
        console.log(`FAIL ${target} ${v.name} ply ${ply}\n  expected ${expected[ply]}\n  actual   ${line}`);
        break;
      }
      history.push(b.move);
    }
    if (!failures) console.log(`ok   ${target} ${v.name}: ${GOLDEN.plies} plies identical to the JVM`);
  }
  return failures;
}

function game(m, target) {
  let failures = 0;
  for (const v of VARIANTS.filter((x) => x.players === '2')) {
    const inputSize = setup(m, v, 8, 4, 7);
    const history = [];
    let outcome = 'ongoing';
    while (history.length < 600) {
      outcome = fields(m.outcome(history.join(','), v.jump, v.players, 'closed')).join(' ');
      if (outcome !== 'ongoing') break;
      const legal = fields(m.legalMoves(history.join(','), v.jump, v.players, 'closed'))[0].split(',').map(Number);
      const b = chooseMove(m, history.join(','), inputSize, v.width, hashForward);
      if (!legal.includes(b.move)) { failures++; console.log(`FAIL ${target} ${v.name}: illegal move ${b.move} at ply ${history.length}`); break; }
      history.push(b.move);
    }
    const ended = outcome !== 'ongoing';
    if (!ended) failures++;
    console.log(`${ended ? 'ok  ' : 'FAIL'} ${target} ${v.name}: ${history.length} plies, outcome "${outcome}"`);
  }
  // errors come back as replies, not exceptions
  setup(m, VARIANTS[0], 8, 4, 7); // create drops the finished search
  const bad = m.begin('999999');
  if (!bad.startsWith('error illegal-history')) { failures++; console.log(`FAIL ${target}: begin(illegal) -> ${bad}`); }
  const order = m.best();
  if (!order.startsWith('error not-ready')) { failures++; console.log(`FAIL ${target}: best() before a search -> ${order}`); }
  console.log(`${failures ? 'FAIL' : 'ok  '} ${target}: errors are replies`);
  return failures;
}

function bench(m, target) {
  const v = VARIANTS[0];
  const rows = [];
  for (const batch of [1, 4]) {
    const inputSize = setup(m, v, 200, batch, 1);
    const times = [];
    let calls = 0;
    for (let round = 0; round < 12; round++) {
      // the start position and a few plies in: every round plays the same moves
      const history = [];
      const t0 = performance.now();
      let positions = 0;
      for (let ply = 0; ply < 6; ply++) {
        const b = chooseMove(m, history.join(','), inputSize, v.width, zeroForward);
        history.push(b.move);
        calls += b.calls;
        positions += b.positions;
      }
      if (round >= 2) times.push((performance.now() - t0) / 6); // the first rounds warm the JIT up
    }
    times.sort((a, b) => a - b);
    const median = times[Math.floor(times.length / 2)];
    rows.push({ target, batch, msPer200Sims: +median.toFixed(2), min: +times[0].toFixed(2), max: +times[times.length - 1].toFixed(2), callsPerMove: +(calls / 12 / 6).toFixed(1) });
  }
  for (const r of rows) console.log(JSON.stringify(r));
  return 0;
}

const modes = { golden, game, bench };
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const [mode, ...targets] = process.argv.slice(2);
  if (!modes[mode]) { console.error('usage: harness.mjs golden|game|bench [js|wasm-gc ...]'); process.exit(2); }
  let failures = 0;
  for (const target of targets.length ? targets : ['js', 'wasm-gc']) {
    const m = await loadTarget(target);
    failures += modes[mode](m, target);
  }
  process.exit(failures ? 1 : 0);
}
