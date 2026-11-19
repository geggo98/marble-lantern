// Node runner of the network benchmark: C++ WebAssembly builds and the TeaVM builds, JSON lines on stdout, progress on stderr.
// usage: node bench-node.mjs --net FILE --ref FILE --root REPO [--variants scalar,simd,relaxed] [--teavm js,wasmgc] [--batches-only]
// Output: one JSON line per case (see bench-driver.mjs). A line {"event":"load",...} gives the load time of each implementation.
import { readFileSync } from 'node:fs';
import { loadavg } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { SCHEDULE, VARIANT_IMPLS, cppImpls, loadReference, runImpl, teavmImpl } from './bench-driver.mjs';

if (process.env.NB_QUICK) Object.assign(SCHEDULE, { warmupSeconds: 0.1, warmupCalls: 2, roundSeconds: 0.1, rounds: 3 }); // smoke test only
const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]?.startsWith('--') ? true : (all[i + 1] ?? true)]] : acc), []),
);
const root = args.root;
const netBytes = new Uint8Array(readFileSync(args.net));
const ref = loadReference(readFileSync(args.ref));
const variants = (args.variants ?? 'scalar,simd,relaxed').split(',').filter(Boolean);
const teavm = (args.teavm ?? 'js,wasmgc').split(',').filter(Boolean);
const env = { cpuUsage: () => { const u = process.cpuUsage(); return u.user + u.system; }, loadavg: () => +loadavg()[0].toFixed(2) };
const emit = (row) => { process.stdout.write(JSON.stringify({ ...row, host: 'node ' + process.version }) + '\n'); process.stderr.write(`${row.impl} b${row.batch}: ${row.pos_per_s} pos/s\n`); };

for (const variant of variants) {
  const t0 = performance.now();
  const { default: createNb } = await import(pathToFileURL(join(root, 'build/nn-bench', `wasm-${variant}`, 'nn_bench.mjs')).href);
  const mod = await createNb();
  const t1 = performance.now();
  const impls = cppImpls(mod, netBytes, ref, `cpp-${variant}`, VARIANT_IMPLS[variant]);
  console.log(JSON.stringify({ event: 'load', impl: `cpp-${variant}`, instantiate_ms: +(t1 - t0).toFixed(1), load_ms: +(performance.now() - t1).toFixed(1), lanes: mod._nb_lanes(), host: 'node ' + process.version }));
  for (const impl of impls) runImpl(impl, ref, env, emit);
}

const gen = join(root, 'teavm-nn-bench/build/generated/teavm');
for (const target of teavm) {
  const t0 = performance.now();
  let exports;
  if (target === 'js') {
    exports = await import(pathToFileURL(join(gen, 'js/nn-bench-java.js')).href);
  } else {
    if (!globalThis.TeaVM?.wasmGC) await import(pathToFileURL(join(gen, 'wasm-gc/nn-bench-java.wasm-runtime.js')).href);
    exports = (await globalThis.TeaVM.wasmGC.load(join(gen, 'wasm-gc/nn-bench-java.wasm'))).exports;
  }
  const t1 = performance.now();
  const impl = teavmImpl(exports, netBytes, ref, `teavm-${target === 'js' ? 'js' : 'wasmgc'}`);
  console.log(JSON.stringify({ event: 'load', impl: impl.name, instantiate_ms: +(t1 - t0).toFixed(1), load_ms: +(performance.now() - t1).toFixed(1), host: 'node ' + process.version }));
  runImpl(impl, ref, env, emit);
}
