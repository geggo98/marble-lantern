// Numerical check of the wasm builds and the TeaVM builds against the golden files of :nn:goldenExport (cpp/golden: nn-weights-small-u5,
// nn-inputs, nn-ref-small-u5): all 1,024 golden positions, batches of 64, bound |fast - ref| <= 1e-4 + 1e-3 |ref| (docs/network.md).
// usage: node golden-check.mjs --root REPO [--golden DIR] [--variants scalar,simd,relaxed] [--teavm js,wasmgc]
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { VARIANT_IMPLS, cppImpls, errFraction, parseSafetensors, teavmImpl } from './bench-driver.mjs';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]?.startsWith('--') ? true : (all[i + 1] ?? true)]] : acc), []),
);
const root = args.root;
const golden = args.golden ?? join(root, 'cpp/golden');
const variants = (args.variants ?? 'scalar,simd,relaxed').split(',').filter(Boolean);
const teavm = (args.teavm ?? 'js,wasmgc').split(',').filter(Boolean);
const netBytes = new Uint8Array(readFileSync(join(golden, 'nn-weights-small-u5.safetensors')));
const inputs = parseSafetensors(readFileSync(join(golden, 'nn-inputs.safetensors'))).tensors.inputs;
const refTensors = parseSafetensors(readFileSync(join(golden, 'nn-ref-small-u5.safetensors'))).tensors;
const total = inputs.shape[0];
const planes = inputs.shape[2];
const players = refTensors.values.shape[1];
const chunk = 64;
let failed = false;

function checkAll(impl) {
  let worst = 0;
  for (let start = 0; start < total; start += chunk) {
    // The impl cycles the golden positions of its own reference object: give it the chunk as the reference.
    const ref = {
      positions: chunk,
      planes,
      players,
      inputs: inputs.data.subarray(start * 122 * planes, (start + chunk) * 122 * planes),
      logits: refTensors.logits.data.subarray(start * 242, (start + chunk) * 242),
      values: refTensors.values.data.subarray(start * players, (start + chunk) * players),
    };
    const prepared = impl.prepareFor(ref, chunk);
    const out = prepared.check();
    worst = Math.max(worst, errFraction(out.logits, out.values, chunk, ref, players));
  }
  const ok = worst <= 1;
  failed ||= !ok;
  console.log(`${impl.name.padEnd(22)} ${total} positions, worst error ${worst.toFixed(4)} of the bound: ${ok ? 'OK' : 'FAIL'}`);
}

for (const variant of variants) {
  const { default: createNb } = await import(pathToFileURL(join(root, 'build/nn-bench', `wasm-${variant}`, 'nn_bench.mjs')).href);
  const mod = await createNb();
  const base = { positions: chunk, planes, players, inputs: inputs.data.subarray(0, chunk * 122 * planes) };
  for (const impl of cppImpls(mod, netBytes, base, `cpp-${variant}`, ['reference', 'fma', 'nofma'].filter((n) => variant !== 'scalar' || n === 'nofma'))) {
    checkAll({ name: impl.name, prepareFor: (ref, batch) => cppImplFor(mod, impl, ref, batch) });
  }
}
function cppImplFor(mod, impl, ref, batch) {
  // cppImpls closes over its reference inputs when it prepares a batch; rebuild it for the chunk.
  const again = cppImpls(mod, netBytes, ref, 'x', [impl.name.split('-').pop()])[0];
  return again.prepare(batch);
}

const gen = join(root, 'teavm-nn-bench/build/generated/teavm');
for (const target of teavm) {
  let exports;
  if (target === 'js') {
    exports = await import(pathToFileURL(join(gen, 'js/nn-bench-java.js')).href);
  } else {
    if (!globalThis.TeaVM?.wasmGC) await import(pathToFileURL(join(gen, 'wasm-gc/nn-bench-java.wasm-runtime.js')).href);
    exports = (await globalThis.TeaVM.wasmGC.load(join(gen, 'wasm-gc/nn-bench-java.wasm'))).exports;
  }
  const name = `teavm-${target === 'js' ? 'js' : 'wasmgc'}`;
  checkAll({
    name,
    prepareFor: (ref, batch) => teavmImpl(exports, netBytes, ref, name).prepare(batch),
  });
}
process.exit(failed ? 1 : 0);
