// The node runner of the benchmark (plan section 8). It starts one child process per engine (bench/child.ts), so that the
// peak RSS of a child belongs to one engine, and collects the results: fingerprint, nodes/s, wall time, CPU time, memory.
// Run it through `devenv tasks run web:bench --input args="<options>"`.
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
import { spawn, spawnSync } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { cpus, loadavg } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import type { EnginesManifest } from '../src/engines/registry';
import {
  NOISY_SPREAD, formatLoads, formatTable, fingerprintText, parseExpected, parseSpec, summarize, toJson, findExpected,
} from '../src/bench/core';
import type { BenchSpec, BenchTarget, EngineReport, Expected, GameStat } from '../src/bench/core';
import { decodeChild, reportFromChild } from '../src/bench/child-protocol';
import { formatNotes, loadWarnings, unavailableResources } from '../src/bench/resources';

const webDir = fileURLToPath(new URL('..', import.meta.url));
const repoDir = join(webDir, '..');

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

// ---- stop flag: the handler only sets it and passes the signal on to the running child ----
let stopSignal: string | null = null;
let running: ChildProcess | null = null;
for (const s of ['SIGINT', 'SIGTERM'] as const) process.on(s, () => { stopSignal = s; running?.kill(s); });

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

// ---- one child per engine ----
interface ChildExit { code: number | null; signal: NodeJS.Signals | null; stdout: string }

function runChild(id: string): Promise<ChildExit> {
  const args = [
    '--import', join(webDir, 'bench', 'register.mjs'), join(webDir, 'bench', 'child.ts'),
    '--engine', id, '--plies', String(spec.plies), '--nodes', String(spec.nodes), '--runs', String(spec.runs), '--public', publicDir,
  ];
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, args, { cwd: webDir, stdio: ['ignore', 'pipe', 'inherit'] });
    running = child;
    let stdout = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk: string) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', (code, signal) => { running = null; resolve({ code, signal, stdout }); });
  });
}

/** The result is the last line that starts a JSON object: an engine may print other text on stdout before it. */
function resultLine(stdout: string): string | undefined {
  return stdout.split('\n').reverse().find((l) => l.startsWith('{"v":1'));
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
  return { ...summarize(target, null, [stat], findExpected(expected, spec)), resources: unavailableResources('jvm-via-gradle', 'jvm-via-gradle') };
}

// ---- main ----
const loadBefore = loadavg();
const all: EngineReport[] = [];
const t0 = performance.now();
for (const [i, id] of wanted.entries()) {
  if (stopSignal) break;
  process.stderr.write(`bench: engine ${i + 1}/${wanted.length}: ${id} in its own process (elapsed ${Math.round((performance.now() - t0) / 1000)}s)\n`);
  const exit = await runChild(id);
  if (stopSignal) break;
  const line = resultLine(exit.stdout);
  if (exit.code !== 0 || !line) die(`the child for ${id} failed (exit ${exit.code ?? exit.signal}); see its messages above`, 1);
  all.push(reportFromChild(decodeChild(line), spec, expected));
}
if (stopSignal) {
  process.stderr.write(`bench: stopped by ${stopSignal}; ${all.length}/${wanted.length} engines done, result discarded\n`);
  process.exit(stopSignal === 'SIGINT' ? 130 : 143);
}
if (values.jvm) all.push(jvmReport());
const loadAfter = loadavg();
const fmt = (l: number[]): string => l.map((v) => v.toFixed(2)).join(' ');

const warnings = all.flatMap((r) =>
  loadWarnings({ cores: cpus().length, loadAverage1: Math.max(loadBefore[0]!, loadAfter[0]!), cpuToWall: r.resources?.cpuToWall ?? null, spread: r.spread })
    .map((w) => `${r.id}: ${w}`));
const environment = {
  runner: 'node', node: process.version, platform: `${process.platform} ${process.arch}`, cpu: cpus()[0]?.model ?? 'unknown', cpus: cpus().length,
  loadAverageBefore: fmt(loadBefore), loadAverageAfter: fmt(loadAfter), warnings, processModel: 'one child process per engine', date: new Date().toISOString(),
};
process.stdout.write(`${formatTable(all)}\n\n${formatNotes(all)}\n\n${formatLoads(all)}\n\n`);
process.stdout.write(`${spec.plies} plies, ${spec.nodes} nodes per move, ${spec.runs} timed run(s) after a 2-ply warm-up; ${environment.node} on ${environment.cpu} (${environment.cpus} cores), load average ${environment.loadAverageBefore} before, ${environment.loadAverageAfter} after\n`);
process.stdout.write('cpu ms and cpu/wall: user + system CPU of the engine process over its timed games, wall time measured around the same games; peak MiB: see the notes above.\n');
for (const r of all) if (r.status !== 'ok') process.stdout.write(`${r.id}: fingerprint ${fingerprintText(r)}\n`);
for (const w of warnings) process.stdout.write(`WARNING: ${w}\n`);
if (warnings.length) process.stdout.write('WARNING: the figures above are not quiet-machine figures; the fingerprint is not affected.\n');
else if (all.some((r) => r.spread !== null && r.spread > NOISY_SPREAD)) process.stdout.write('note: noisy spread; repeat before quoting nodes/s.\n');

if (values.json) {
  const tmp = `${values.json}.tmp.${process.pid}`;
  writeFileSync(tmp, toJson(all, spec, environment));
  renameSync(tmp, values.json as string);
  process.stderr.write(`bench: wrote ${values.json}\n`);
}
const bad = all.filter((r) => r.status !== 'ok' || r.rows1Requests > 0);
process.stderr.write(`bench: ${all.length - bad.length} ok, ${bad.length} not ok${bad.length ? ' (' + bad.map((r) => r.id).join(', ') + ')' : ''}\n`);
process.exit(bad.length ? 1 : 0);
