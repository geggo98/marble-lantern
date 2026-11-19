// Prints the tables of the network benchmark from the JSON lines of the runners.
// usage: node bench-table.mjs <file.jsonl>...   (lines with {"event":...} are load times, the others are cases)
import { readFileSync } from 'node:fs';

const rows = [];
const loads = [];
const errors = [];
for (const file of process.argv.slice(2)) {
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line.startsWith('{')) continue;
    const r = JSON.parse(line);
    if (r.event === 'load') loads.push(r);
    else if (r.event === 'error') errors.push(r);
    else rows.push(r);
  }
}
const hostOf = (r) => r.host ?? (r.runtime === 'jvm' ? 'jvm' : 'native');
const keys = [...new Set(rows.filter((r) => !r.impl.endsWith('-again')).map((r) => `${hostOf(r)}\t${r.impl}`))];
const batches = [1, 8, 32, 128];
const pad = (s, n) => String(s).padEnd(n);
const num = (x) => (x === undefined ? '-' : x >= 100 ? x.toFixed(0) : x.toFixed(1));

console.log('Positions per second, median of 5 rounds (SMALL 32x4, one forward pass of the batch = policy + value)');
console.log([pad('host', 24), pad('path', 24), ...batches.map((b) => pad(`b=${b}`, 8)), pad('spread@32', 10), pad('self-ratio', 11), pad('max err', 8), pad('cpu/wall', 9), 'load1'].join(' '));
for (const key of keys) {
  const [host, impl] = key.split('\t');
  const mine = rows.filter((r) => hostOf(r) === host && (r.impl === impl || r.impl === impl + '-again'));
  const at = (b) => mine.find((r) => r.impl === impl && r.batch === b);
  const again = mine.find((r) => r.impl === impl + '-again');
  const b32 = at(32);
  const spread = b32 ? (b32.max_pos_per_s / b32.min_pos_per_s).toFixed(2) : '-';
  const selfRatio = b32 && again ? (again.pos_per_s / b32.pos_per_s).toFixed(2) : '-';
  const err = Math.max(...mine.map((r) => r.err_fraction));
  const cpus = mine.map((r) => r.cpu_wall).filter((x) => x !== null && x !== undefined);
  const loads1 = mine.map((r) => r.load1).filter((x) => x !== null && x !== undefined);
  console.log(
    [
      pad(host, 24),
      pad(impl, 24),
      ...batches.map((b) => pad(num(at(b)?.pos_per_s), 8)),
      pad(spread, 10),
      pad(selfRatio, 11),
      pad(err.toFixed(3), 8),
      pad(cpus.length ? (cpus.reduce((a, b) => a + b, 0) / cpus.length).toFixed(2) : '-', 9),
      loads1.length ? `${Math.min(...loads1)}..${Math.max(...loads1)}` : '-',
    ].join(' '),
  );
}
console.log('\nMilliseconds per batch (median)');
console.log([pad('host', 24), pad('path', 24), ...batches.map((b) => pad(`b=${b}`, 9))].join(' '));
for (const key of keys) {
  const [host, impl] = key.split('\t');
  const at = (b) => rows.find((r) => hostOf(r) === host && r.impl === impl && r.batch === b);
  console.log([pad(host, 24), pad(impl, 24), ...batches.map((b) => pad(at(b) ? at(b).ms_per_batch.toFixed(3) : '-', 9))].join(' '));
}
console.log('\nLoad (module instantiation, then weights parse and net set-up), ms');
for (const l of loads) console.log([pad(hostOf(l), 24), pad(l.impl, 24), `instantiate ${l.instantiate_ms}`, `load ${l.load_ms}`, l.lanes ? `lanes ${l.lanes}` : ''].join(' '));
for (const e of errors) console.log(`ERROR ${e.host} ${JSON.stringify(e.job)}: ${e.message}`);
console.log('\nerr = largest |fast - ref| as a fraction of the bound 1e-4 + 1e-3 |ref| against the Java scalar Network; <= 1.000 passes.');
console.log('self-ratio = batch 32 measured again at the end of the path divided by the first measurement (1.00 = the machine did not change speed).');
