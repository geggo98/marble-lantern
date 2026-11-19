// Timing core of the WebAssembly net backend benchmark, shared by Node (bench.mjs) and the browser page (bench-page.html).
// It calls the raw C API of one flavour (net_evaluate), so that the numbers compare with the earlier cpp/bench measurements;
// the copy of the inputs into the module is part of the timed loop (as in the TypeScript backend), the copy of the outputs is not.
export const SCHEDULE = { warmupSeconds: 0.5, rounds: 5, roundSeconds: 0.6 };

/**
 * @param create    the factory (default export of engine-net-nn-<flavour>.js)
 * @param weights   Uint8Array, F32 safetensors
 * @param inputs    Float32Array, at least `maxBatch` encoded positions of this net
 * @returns one row per batch size: median, min and max positions per second of 5 rounds
 */
export async function benchFlavour(create, weights, inputs, batches, now = () => performance.now()) {
  const t0 = now();
  const m = await create();
  const instantiateMs = now() - t0;
  const t1 = now();
  const ptr = m._malloc(weights.length);
  m.HEAPU8.set(weights, ptr);
  const rc = m._net_load(ptr, weights.length);
  m._free(ptr);
  if (rc !== 0) throw new Error(`net_load: ${m.UTF8ToString(m._net_error())}`);
  const loadMs = now() - t1;
  const planes = m._net_planes();
  const size = 122 * planes;
  const maxBatch = Math.max(...batches);
  const inPtr = m._net_input_ptr(maxBatch);
  const rows = [];
  for (const batch of batches) {
    const slice = inputs.subarray(0, batch * size);
    const run = () => {
      m.HEAPF32.set(slice, inPtr >> 2);
      if (m._net_evaluate(batch) !== 0) throw new Error(`net_evaluate: ${m.UTF8ToString(m._net_error())}`);
    };
    const w0 = now();
    while (now() - w0 < SCHEDULE.warmupSeconds * 1000) run();
    const rounds = [];
    for (let r = 0; r < SCHEDULE.rounds; r++) {
      const s = now();
      let calls = 0;
      do {
        run();
        calls++;
      } while (now() - s < SCHEDULE.roundSeconds * 1000);
      rounds.push((calls * batch * 1000) / (now() - s));
    }
    rounds.sort((a, b) => a - b);
    rows.push({ batch, posPerS: +rounds[2].toFixed(1), minPosPerS: +rounds[0].toFixed(1), maxPosPerS: +rounds[4].toFixed(1) });
  }
  return { flavour: m._net_flavour(), instantiateMs: +instantiateMs.toFixed(1), loadMs: +loadMs.toFixed(1), rows };
}

/** Calibration of the host: a fixed scalar loop, so that two runs can be compared (a throttled laptop shows here). */
export function selfRatio(now = () => performance.now()) {
  const a = new Float32Array(4096).fill(1.0001);
  const t = now();
  let s = 0;
  for (let k = 0; k < 20000; k++) for (let i = 0; i < a.length; i++) s += a[i] * 0.5;
  return { probeMs: +(now() - t).toFixed(1), sink: s > 0 };
}
