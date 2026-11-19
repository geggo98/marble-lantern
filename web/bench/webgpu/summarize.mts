// Prints the tables of one or more run.mts JSON files:  node web/bench/webgpu/summarize.mts run1.json [run2.json ...]
import fs from 'node:fs';

const fmt = (x: number, d = 2): string => x.toFixed(d);
for (const file of process.argv.slice(2)) {
  const r = JSON.parse(fs.readFileSync(file, 'utf8'));
  console.log(`\n=== ${file}\nconfig ${r.config}, Chrome ${r.chrome}, load before ${r.load.before.map((x: number) => x.toFixed(1)).join(' ')} after ${r.load.after.map((x: number) => x.toFixed(1)).join(' ')}`);
  console.log('adapter', JSON.stringify(r.gpu.info), 'fallback', r.gpu.isFallbackAdapter, 'features', (r.gpu.features ?? []).join(','));
  for (const ctx of ['main', 'worker']) {
    const c = r[ctx];
    if (!c) continue;
    console.log(`\n--- ${ctx}${c.error ? ` ERROR ${c.error}` : ''}`);
    for (const [k, v] of Object.entries<any>(c.correctness ?? {})) {
      console.log(`correctness ${k}: pass=${v.pass} maxFrac=${v.maxFrac.toExponential(2)} maxAbsPolicy=${v.maxAbsPolicy.toExponential(2)} maxAbsValue=${v.maxAbsValue.toExponential(2)} violations=${v.violations}/${v.checked} control=${v.controlViolations} (${v.positions} positions; ${v.batches})`);
    }
    if (c.floorEmptyRoundTripMs) console.log(`floor (empty submit + mapAsync) p50 ${fmt(c.floorEmptyRoundTripMs.p50, 3)} p95 ${fmt(c.floorEmptyRoundTripMs.p95, 3)} p99 ${fmt(c.floorEmptyRoundTripMs.p99, 3)} ms`);
    if (c.cpuProbeMs) console.log(`cpu probe start ${fmt(c.cpuProbeMs.start, 1)} ms, end ${fmt(c.cpuProbeMs.end, 1)} ms, ratio ${fmt(c.cpuProbeMs.end / c.cpuProbeMs.start, 2)}`);
    for (const [variant, b] of Object.entries<any>(c.bench ?? {})) {
      console.log(`\n${variant}: batch | seq p50 ms | p95 | evals/s (p50) | pipe2 ms/call | evals/s | pipe3 ms/call | evals/s`);
      for (const s of b.sequential) {
        const p2 = b.pipelined.find((p: any) => p.batch === s.batch && p.inflight === 2);
        const p3 = b.pipelined.find((p: any) => p.batch === s.batch && p.inflight === 3);
        console.log(`  ${String(s.batch).padStart(3)} | ${fmt(s.ms.p50, 3)} | ${fmt(s.ms.p95, 3)} | ${fmt(s.evalsPerSecMedian, 0)} | ${fmt(p2.msPerCall, 3)} | ${fmt(p2.evalsPerSec, 0)} | ${fmt(p3.msPerCall, 3)} | ${fmt(p3.evalsPerSec, 0)}`);
      }
      const l = b.latencyB1;
      console.log(`  B=1 latency over ${l.ms.n} runs: p50 ${fmt(l.ms.p50, 3)} p95 ${fmt(l.ms.p95, 3)} p99 ${fmt(l.ms.p99, 3)} min ${fmt(l.ms.min, 3)} max ${fmt(l.ms.max, 3)} mean ${fmt(l.ms.mean, 3)} ms`);
    }
  }
}
