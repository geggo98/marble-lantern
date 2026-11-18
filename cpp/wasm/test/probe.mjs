// Error codes of shw_load_rows2 for damaged tables: no crash, one code per kind (LoadError kind + 1). The codes are part of the
// contract with the TypeScript adapter (WP7).
// usage: node probe.mjs --engine DIR --tables DIR
import { createModule, loadRows2, parseArgs, readTable } from './lib.mjs';

const { engine, tables } = parseArgs(process.argv);
const raw = readTable(tables);
const m = await createModule(engine);

const flipped = Buffer.from(raw);
flipped[5_000_000] ^= 1;
const cases = [
  // name, bytes, verify, expected code
  ['flipped byte in the body', flipped, true, 9],
  ['one byte missing', raw.subarray(0, raw.length - 1), true, 7],
  ['one byte extra', Buffer.concat([raw, Buffer.from([0])]), true, 8],
  ['HTML page (a 404)', Buffer.from('<html>404</html>'), true, 4],
  ['empty input', Buffer.alloc(0), true, 4],
  // verify = 0 trusts the caller's SHA-256, but still checks the structure
  ['one byte missing, verify=0', raw.subarray(0, raw.length - 1), false, 7],
  ['one byte extra, verify=0', Buffer.concat([raw, Buffer.from([0])]), false, 8],
];
let failed = 0;
for (const [name, bytes, verify, want] of cases) {
  const { rc, error } = loadRows2(m, bytes, verify);
  const ok = rc === want;
  if (!ok) failed++;
  console.log(`${name}: code ${rc} ${ok ? 'ok' : `FAIL, expected ${want}`}${error ? ` ("${error}")` : ''}`);
}
const good = loadRows2(m, raw, true);
console.log(`good table after the failures: code ${good.rc} ${good.rc === 0 ? 'ok' : 'FAIL'}`);
if (good.rc !== 0) failed++;
if (failed) { console.error(`${failed} probe case(s) failed`); process.exit(1); }
