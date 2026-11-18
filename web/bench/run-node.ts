// The node runner of the benchmark (plan section 8): loads the engine adapters' cores directly, without a worker,
// and plays the benchmark game on each. Run it through `devenv tasks run web:bench -- <options>`.
//
//   --plies N     plies per game               (default 40)
//   --nodes N     node budget per move         (default 20000)
//   --runs N      timed games per engine       (default 5)
//   --engines L   comma list of engine ids     (default: every engine in engines.json)
//   --public DIR  directory with engines/ and tables/ (default: web/public)
//   --expected F  expected fingerprints        (default: web/bench/expected.json)
//   --json F      also write the result as JSON to F
//   --jvm         add a JVM row from BenchReference (runs Gradle, takes a minute)
// Exit: 0 = every fingerprint is ok, 1 = a mismatch, an unchecked fingerprint or an error, 2 = wrong call, 130/143 = stopped.
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { cpus, loadavg } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { parseArgs } from 'node:util';
import type { LoadReport } from '../src/engines/engine';
import { CppCore } from '../src/engines/cpp/core';
import type { CppModuleFactory } from '../src/engines/cpp/core';
import { JavaCore } from '../src/engines/java/core';
import { loadJavaModuleInNode } from '../src/engines/java/node';
import type { EnginesManifest } from '../src/engines/registry';
import { parseManifest, verifyTable } from '../src/engines/tables';
import {
  NOISY_SPREAD, formatLoads, formatTable, fingerprintText, parseExpected, parseSpec, runBenchmark, summarize, toJson, findExpected,
} from '../src/bench/core';
import type { BenchSpec, BenchTarget, EngineReport, Expected, GameStat } from '../src/bench/core';

const webDir = fileURLToPath(new URL('..', import.meta.url));
const repoDir = join(webDir, '..');
const now = (): number => performance.now();

const USAGE = 'usage: run-node.ts [--plies N] [--nodes N] [--runs N] [--engines a,b] [--public DIR] [--expected FILE] [--json FILE] [--jvm]';
function die(message: string, code: number): never {
  process.stderr.write(`bench: ${message}\n`);
  return process.exit(code);
}

let values: ReturnType<typeof parseArgs>['values'];
try {
  values = parseArgs({
    options: {
      plies: { type: 'string', default: '40' }, nodes: { type: 'string', default: '20000' }, runs: { type: 'string', default: '5' },
      engines: { type: 'string' }, public: { type: 'string', default: join(webDir, 'public') },
      expected: { type: 'string', default: join(webDir, 'bench', 'expected.json') }, json: { type: 'string' }, jvm: { type: 'boolean', default: false },
    },
    strict: true,
  }).values;
} catch (e) {
  die(`${(e as Error).message}\n${USAGE}`, 2);
}
let spec: BenchSpec;
try {
  spec = parseSpec(values.plies, values.nodes, values.runs);
} catch (e) {
  die(`${(e as Error).message}\n${USAGE}`, 2);
}
const publicDir = values.public as string;

// ---- stop flag: the handler only sets it, the benchmark looks between games ----
let stopSignal: string | null = null;
for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => { stopSignal = s; });

// ---- inputs ----
const readJson = (path: string): unknown => {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch (e) {
    return die(`cannot read ${path}: ${(e as Error).message}`, 1);
  }
};
const manifest = readJson(join(publicDir, 'engines', 'engines.json')) as EnginesManifest;
if (manifest.v !== 1 || typeof manifest.engines !== 'object') die('engines.json has an unexpected shape; run devenv tasks run web:engines', 1);
let expected: Expected;
try {
  expected = parseExpected(readFileSync(values.expected as string, 'utf8'));
} catch (e) {
  expected = die(`cannot use ${values.expected}: ${(e as Error).message}`, 1);
}
const wanted = values.engines ? (values.engines as string).split(',').filter(Boolean) : Object.keys(manifest.engines);
for (const id of wanted) if (!(id in manifest.engines)) die(`engine ${id} is not in engines.json (has: ${Object.keys(manifest.engines).join(', ')})`, 2);
if (wanted.length === 0 && !values.jvm) die('no engine selected', 2);

// ---- loading, timed like the worker's LoadReport ----
interface Loaded { target: BenchTarget; load: LoadReport }

async function loadEngine(id: string): Promise<Loaded> {
  const entry = manifest.engines[id as keyof typeof manifest.engines]!;
  const file = (suffix: string): string | undefined => entry.files.find((f) => f.path.endsWith(suffix))?.path;
  const abs = (path: string | undefined): string => join(publicDir, path ?? die(`engine ${id} lacks a file`, 1));
  const t0 = now();

  // The table like the page gets it: gzip, inflate, length, magic and SHA-256 against the manifest.
  const tablesDir = join(publicDir, 'tables');
  const tFetch = now();
  const tm = parseManifest(readFileSync(join(tablesDir, 'rows2.manifest.json'), 'utf8'));
  const gz = readFileSync(join(tablesDir, tm.file));
  const fetchMs = now() - tFetch;
  const tInflate = now();
  const raw = new Uint8Array(gunzipSync(gz));
  const inflateMs = now() - tInflate;
  const tVerify = now();
  await verifyTable(raw, tm, globalThis.crypto.subtle);
  const verifyMs = now() - tVerify;

  const tInst = now();
  let install: (verify: boolean) => number;
  let play: BenchTarget['bench'];
  if (id === 'cpp-wasm') {
    const factory = ((await import(/* @vite-ignore */ pathToFileURL(abs(file('.js'))).href)) as { default: CppModuleFactory }).default;
    const core = await CppCore.create(factory);
    install = (verify) => core.installTable(raw, verify);
    play = (plies, nodes) => core.bench(plies, nodes);
  } else if (id === 'java-wasmgc' || id === 'java-js') {
    const assets = id === 'java-js'
      ? { target: 'js' as const, main: abs(file('engine-java.js')) }
      : { target: 'wasm-gc' as const, main: abs(file('.wasm')), runtime: abs(file('-runtime.js')) };
    const core = JavaCore.create(await loadJavaModuleInNode(assets));
    install = (verify) => core.installTable(raw, verify);
    play = (plies, nodes) => core.bench(plies, nodes);
  } else {
    return die(`the node runner cannot load engine ${id}`, 2);
  }
  const instantiateMs = now() - tInst;
  const tableInstallMs = install(false); // already verified above, like the page does with crypto.subtle
  const load: LoadReport = { fetchMs, inflateMs, verifyMs, instantiateMs, tableInstallMs, totalMs: now() - t0, tableVerifiedBy: 'subtle-sha256' };
  const runtime = id === 'cpp-wasm' ? 'wasm' : id === 'java-wasmgc' ? 'wasm-gc' : 'js';
  return {
    load,
    target: {
      id,
      info: { runtime, language: id === 'cpp-wasm' ? 'C++' : 'Java', binaryBytes: entry.binaryBytes, binaryGzipBytes: entry.binaryGzipBytes, version: manifest.version },
      bench: play,
    },
  };
}

// ---- JVM baseline ----
function jvmReport(): EngineReport {
  process.stderr.write(`bench: JVM baseline through Gradle (${spec.plies} plies, ${spec.nodes} nodes)\n`);
  const args = ['--no-build-cache', '-q', ':teavm-web:benchReference', `--args=${join(repoDir, 'tablebase/tables/rows2.bin')} ${spec.plies} ${spec.nodes}`];
  const run = spawnSync('./gradlew', args, { cwd: repoDir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] });
  const m = /checksum=(-?\d+) nodes=(\d+) ms=(\d+) rows1Requests=(\d+)/.exec(run.stdout ?? '');
  if (run.status !== 0 || !m) return die(`the JVM baseline failed (exit ${run.status}): ${(run.stdout ?? '').slice(-300)}`, 1);
  const totalNodes = Number(m[2]);
  const ms = Math.max(Number(m[3]), 1);
  const stat: GameStat = {
    fingerprint: { checksum: BigInt(m[1]!), totalNodes },
    nodesPerSecond: totalNodes / (ms / 1000),
    msPerMove: Array.from({ length: spec.plies }, () => ms / spec.plies), // BenchReference reports the sum only
    rows1Requests: Number(m[4]),
  };
  const target: BenchTarget = { id: 'jvm', info: { runtime: 'jvm', language: 'Java', binaryBytes: 0, binaryGzipBytes: 0, version: manifest.version }, bench: () => { throw new Error('not used'); } };
  return summarize(target, null, [stat], findExpected(expected, spec));
}

// ---- main ----
const stamp = (): string => `${loadavg().map((v) => v.toFixed(2)).join(' ')}`;
const loadBefore = stamp();
const loaded: Loaded[] = [];
for (const id of wanted) {
  process.stderr.write(`bench: loading ${id}\n`);
  loaded.push(await loadEngine(id));
}

const total = loaded.length * spec.runs;
let done = 0;
const t0 = now();
const reports = loaded.length === 0 ? [] : await runBenchmark(loaded.map((l) => l.target), spec, expected, {
  loads: new Map(loaded.map((l) => [l.target.id, l.load])),
  shouldStop: () => stopSignal !== null,
  onGame: ({ engine, run, runs, stat }) => {
    done++;
    const el = (now() - t0) / 1000;
    const eta = done > 0 ? ((total - done) * el) / done : NaN; // every game is real work: no skips in this runner
    process.stderr.write(`${done}/${total}  ${engine} run ${run}/${runs}  ${Math.round(stat.nodesPerSecond)} nodes/s  ETA ${Number.isFinite(eta) ? Math.round(eta) : '?'}s  elapsed ${Math.round(el)}s\n`);
  },
});
if (reports === null) {
  process.stderr.write(`bench: stopped by ${stopSignal}; ${done}/${total} games done, result discarded\n`);
  process.exit(stopSignal === 'SIGINT' ? 130 : 143);
}
const all = [...(reports ?? [])];
if (values.jvm) all.push(jvmReport());
const loadAfter = stamp();

const environment = {
  runner: 'node', node: process.version, platform: `${process.platform} ${process.arch}`, cpu: cpus()[0]?.model ?? 'unknown', cpus: cpus().length,
  loadAverageBefore: loadBefore, loadAverageAfter: loadAfter, date: new Date().toISOString(),
};
process.stdout.write(`${formatTable(all)}\n\n${formatLoads(all)}\n\n`);
process.stdout.write(`${spec.plies} plies, ${spec.nodes} nodes per move, ${spec.runs} timed run(s) after a 2-ply warm-up; ${environment.node} on ${environment.cpu} (${environment.cpus} cores), load average ${loadBefore} before, ${loadAfter} after\n`);
for (const r of all) if (r.status !== "ok") process.stdout.write(`${r.id}: fingerprint ${fingerprintText(r)}\n`);
if (all.some((r) => r.spread !== null && r.spread > NOISY_SPREAD)) {
  process.stdout.write(`note: a spread above ${NOISY_SPREAD} means the machine was busy; repeat before quoting nodes/s (the fingerprint is not affected).\n`);
}

if (values.json) {
  const tmp = `${values.json}.tmp.${process.pid}`;
  writeFileSync(tmp, toJson(all, spec, environment));
  renameSync(tmp, values.json as string);
  process.stderr.write(`bench: wrote ${values.json}\n`);
}
const bad = all.filter((r) => r.status !== 'ok' || r.rows1Requests > 0);
process.stderr.write(`bench: ${all.length - bad.length} ok, ${bad.length} not ok${bad.length ? ' (' + bad.map((r) => r.id).join(', ') + ')' : ''}\n`);
process.exit(bad.length ? 1 : 0);
