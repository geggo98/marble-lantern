// One engine in its own process: loads the engine, plays the warm-up game and the timed self-play games, and prints ONE line
// of JSON (ChildOutput, src/bench/child-protocol.ts) on stdout. Started by run-node.ts; it can also be run by hand:
//
//   node --import ./bench/register.mjs bench/child.ts --engine cpp-wasm [--plies 40] [--nodes 20000] [--runs 5] [--public DIR]
//
// Measured around the timed games only: wall time, process.cpuUsage() (user + system) and the resident set size.
// Peak RSS is process.resourceUsage().maxRSS at the end, so it includes the load of the table (that is why the RSS before and
// after the load are reported too). Progress goes to stderr. SIGINT/SIGTERM stop between games without output (exit 130/143).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import type { EnginesManifest } from '../src/engines/registry';
import { WARMUP_PLIES, parseSpec, toGameStat } from '../src/bench/core';
import type { GameStat } from '../src/bench/core';
import { encodeChild } from '../src/bench/child-protocol';
import { cpuMsFromUsage, maxRssBytes } from '../src/bench/resources';
import { loadEngine } from './load-engine';

const webDir = fileURLToPath(new URL('..', import.meta.url));

let stop: string | null = null;
for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => { stop = s; });

async function main(): Promise<number> {
  const { values } = parseArgs({
    options: {
      engine: { type: 'string' }, plies: { type: 'string', default: '40' }, nodes: { type: 'string', default: '20000' },
      runs: { type: 'string', default: '5' }, public: { type: 'string', default: join(webDir, 'public') },
    },
    strict: true,
  });
  if (!values.engine) throw new Error('--engine is required');
  const spec = parseSpec(values.plies, values.nodes, values.runs);
  const publicDir = values.public as string;
  const manifest = JSON.parse(readFileSync(join(publicDir, 'engines', 'engines.json'), 'utf8')) as EnginesManifest;

  const rssStartBytes = process.memoryUsage.rss();
  const { target, load } = await loadEngine(manifest, publicDir, values.engine);
  const rssAfterLoadBytes = process.memoryUsage.rss();

  await target.bench(WARMUP_PLIES, spec.nodes);

  const games: GameStat[] = [];
  const cpu0 = process.cpuUsage();
  const w0 = performance.now();
  for (let run = 1; run <= spec.runs; run++) {
    if (stop) break;
    const stat = toGameStat(await target.bench(spec.plies, spec.nodes), spec.plies);
    games.push(stat);
    process.stderr.write(`bench: ${target.id} run ${run}/${spec.runs}  ${Math.round(stat.nodesPerSecond)} nodes/s\n`);
  }
  const wallMs = performance.now() - w0;
  const cpuMs = cpuMsFromUsage(cpu0, process.cpuUsage());
  if (stop) return stop === 'SIGINT' ? 130 : 143;

  const peakRssBytes = maxRssBytes(process.resourceUsage().maxRSS);
  process.stdout.write(`${encodeChild({ v: 1, id: target.id, info: target.info, load, games, measure: { wallMs, cpuMs, rssStartBytes, rssAfterLoadBytes, peakRssBytes } })}\n`);
  return 0;
}

main().then(
  (code) => process.exit(code),
  (e: unknown) => {
    process.stderr.write(`bench child: ${(e as Error).stack ?? String(e)}\n`);
    process.exit(1);
  },
);
