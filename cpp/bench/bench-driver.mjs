// Driver of the network benchmark in JavaScript, shared by Node (bench-node.mjs) and the browser pages (bench-browser.mjs).
// No Node API here: the module runs in a page as well. Schedule (the same in JvmNetBench and the native driver of
// nn_bench_api.cpp): per case a check of the outputs against the Java reference, a warm-up of at least 1 s and 10 calls, then
// 5 rounds of at least 0.8 s; positions/s is the median round. After the batches, batch 32 is measured once more
// ("-again" rows): the ratio of the two is the self-ratio of the run (near 1.00 on a machine that did not change speed).

export const SCHEDULE = { warmupSeconds: 1, warmupCalls: 10, roundSeconds: 0.8, rounds: 5 };
export const BATCHES = [1, 8, 32, 128];
export const SELF_RATIO_BATCH = 32;

const clock = () => performance.now();

/** Reads a safetensors file (F32 only): {metadata, tensors: {name: {shape, data: Float32Array}}}. */
export function parseSafetensors(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  const headerLength = Number(dv.getBigUint64(0, true));
  const header = JSON.parse(new TextDecoder().decode(u8.subarray(8, 8 + headerLength)));
  const base = 8 + headerLength;
  const tensors = {};
  for (const [name, v] of Object.entries(header)) {
    if (name === '__metadata__') continue;
    const [begin, end] = v.data_offsets;
    tensors[name] = { shape: v.shape, data: new Float32Array(Uint8Array.prototype.slice.call(u8, base + begin, base + end).buffer) };
  }
  return { metadata: header.__metadata__ ?? {}, tensors };
}

/** Base64 of bytes, in chunks (String.fromCharCode(...u8) overflows the argument stack above about 100 KiB). */
export function toBase64(u8) {
  let s = '';
  const CH = 0x8000;
  for (let i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
  return btoa(s);
}

export function fromBase64Floats(b64) {
  const s = atob(b64);
  const u8 = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
  return new Float32Array(u8.buffer);
}

/** Largest |fast - ref| as a fraction of the bound 1e-4 + 1e-3 |ref| (docs/network.md); <= 1 passes. NaN counts as infinite. */
export function errFraction(logits, values, batch, ref, players) {
  let worst = 0;
  const f = (fast, r) => {
    const x = Math.abs(fast - r) / (1e-4 + 1e-3 * Math.abs(r));
    return Number.isNaN(x) ? Infinity : x;
  };
  for (let b = 0; b < batch; b++) {
    const p = b % ref.positions;
    for (let i = 0; i < 242; i++) worst = Math.max(worst, f(logits[b * 242 + i], ref.logits[p * 242 + i]));
    for (let i = 0; i < players; i++) worst = Math.max(worst, f(values[b * players + i], ref.values[p * players + i]));
  }
  return worst;
}

/** The reference file of WasmBenchExport: inputs [N][122][planes], logits [N][242], values [N][players]. */
export function loadReference(bytes) {
  const t = parseSafetensors(bytes).tensors;
  return { positions: t.inputs.shape[0], planes: t.inputs.shape[2], players: t.values.shape[1], inputs: t.inputs.data, logits: t.logits.data, values: t.values.data };
}

function median(sorted) {
  return sorted[Math.floor(sorted.length / 2)];
}

/**
 * Runs the schedule for one implementation. impl: {name, runtime, prepare(batch) -> {call(), check() -> {logits, values}}}.
 * env: {cpuUsage?: () => microseconds of CPU time of this process, loadavg?: () => number} (Node only).
 */
export function runImpl(impl, ref, env, onRow) {
  const rows = [];
  const batches = [...BATCHES, SELF_RATIO_BATCH];
  batches.forEach((batch, i) => {
    const again = i === batches.length - 1;
    const prepared = impl.prepare(batch);
    const out = prepared.check();
    const err = errFraction(out.logits, out.values, batch, ref, ref.players);
    const cpu0 = env.cpuUsage?.();
    const wall0 = clock();
    const load1 = env.loadavg?.() ?? null;
    let calls = 0;
    const w0 = clock();
    while (calls < SCHEDULE.warmupCalls || clock() - w0 < SCHEDULE.warmupSeconds * 1000) {
      prepared.call();
      calls++;
    }
    const msPerCall = [];
    for (let r = 0; r < SCHEDULE.rounds; r++) {
      const t0 = clock();
      let n = 0;
      let t;
      do {
        prepared.call();
        n++;
        t = clock() - t0;
      } while (t < SCHEDULE.roundSeconds * 1000);
      msPerCall.push(t / n);
    }
    const cpu = cpu0 === undefined ? null : (env.cpuUsage() - cpu0) / 1000;
    const wall = clock() - wall0;
    const sorted = [...msPerCall].sort((a, b) => a - b);
    const posPerS = (ms) => (batch * 1000) / ms;
    const row = {
      impl: impl.name + (again ? '-again' : ''),
      runtime: impl.runtime,
      batch,
      pos_per_s: +posPerS(median(sorted)).toFixed(1),
      ms_per_batch: +median(sorted).toFixed(4),
      min_pos_per_s: +posPerS(sorted[sorted.length - 1]).toFixed(1),
      max_pos_per_s: +posPerS(sorted[0]).toFixed(1),
      rounds_pos_per_s: msPerCall.map((m) => +posPerS(m).toFixed(1)),
      err_fraction: +err.toFixed(4),
      cpu_wall: cpu === null ? null : +(cpu / wall).toFixed(3),
      load1,
    };
    rows.push(row);
    onRow?.(row);
  });
  return rows;
}

/**
 * The C++ module (Emscripten, nn_bench_api.cpp) as implementations. `mod` is the instantiated module; impls: names from
 * 'reference' | 'fma' | 'nofma'. prefix: for example 'cpp-relaxed'.
 */
export function cppImpls(mod, netBytes, ref, prefix, impls) {
  const net = mod._malloc(netBytes.length);
  mod.HEAPU8.set(netBytes, net);
  if (mod._nb_load(net, netBytes.length) !== 0) throw new Error('nb_load: ' + mod.UTF8ToString(mod._nb_error()));
  mod._free(net);
  const planes = mod._nb_planes();
  const players = mod._nb_players();
  const size = 122 * planes;
  const ids = { reference: 0, fma: 1, nofma: 2 };
  return impls.map((name) => ({
    name: `${prefix}-${name}`,
    runtime: prefix.startsWith('native') ? 'native' : 'wasm',
    prepare(batch) {
      const inPtr = mod._malloc(batch * size * 4);
      const loPtr = mod._malloc(batch * 242 * 4);
      const vaPtr = mod._malloc(batch * players * 4);
      // The memory may have grown since the module was created: take the views after the allocations.
      const input = new Float32Array(mod.HEAPU8.buffer, inPtr, batch * size);
      for (let b = 0; b < batch; b++) input.set(ref.inputs.subarray((b % ref.positions) * size, (b % ref.positions + 1) * size), b * size);
      const run = () => {
        if (mod._nb_forward(ids[name], inPtr, batch, loPtr, vaPtr) !== 0) throw new Error('nb_forward: ' + mod.UTF8ToString(mod._nb_error()));
      };
      return {
        call: run,
        check() {
          run();
          return {
            logits: new Float32Array(mod.HEAPU8.buffer.slice(loPtr, loPtr + batch * 242 * 4)),
            values: new Float32Array(mod.HEAPU8.buffer.slice(vaPtr, vaPtr + batch * players * 4)),
          };
        },
      };
    },
  }));
}

/** The TeaVM facade (sternhalma.nnbench.NetBenchWeb) as one implementation. `exports`: the exports of the module. */
export function teavmImpl(exports, netBytes, ref, name) {
  const must = (reply) => {
    if (!reply.startsWith('ok')) throw new Error(reply);
    return reply;
  };
  must(exports.load(toBase64(netBytes)));
  must(exports.setInputs(toBase64(new Uint8Array(ref.inputs.buffer, ref.inputs.byteOffset, ref.inputs.byteLength)), ref.positions));
  return {
    name,
    runtime: name.includes('wasmgc') ? 'wasm-gc' : 'js',
    prepare(batch) {
      return {
        call: () => must(exports.run(batch, 1)),
        check() {
          const all = fromBase64Floats(must(exports.check(batch)).slice(3));
          return { logits: all.subarray(0, batch * 242), values: all.subarray(batch * 242) };
        },
      };
    },
  };
}

export const VARIANT_IMPLS = {
  scalar: ['nofma'],
  simd: ['reference', 'nofma'],
  relaxed: ['fma', 'nofma'],
};
