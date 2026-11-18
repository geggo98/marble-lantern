// Shared helpers of the node tests of the C++ WebAssembly engine (cpp/wasm/test/). The tests load engine-cpp.js as the page does:
// an ES module whose default export creates the module; the table comes as the inflated bytes of rows2.bin.gz.
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { gunzipSync } from 'node:zlib';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// The benchmark game of the plan (section 8): 40 plies, 20,000 nodes per move, expected fingerprint of the full run.
export const BENCH = { plies: 40, nodes: 20000, checksum: -1986550544382302149n, totalNodes: 800000 };

export function parseArgs(argv) {
  const args = {};
  for (let i = 2; i < argv.length; i += 2) {
    if (!argv[i].startsWith('--') || argv[i + 1] === undefined) throw new Error(`usage: <script> --engine DIR --tables DIR (got ${argv.slice(2).join(' ')})`);
    args[argv[i].slice(2)] = argv[i + 1];
  }
  if (!args.engine || !args.tables) throw new Error('usage: <script> --engine DIR --tables DIR');
  return args;
}

// The inflated table, checked against the manifest like the page does (length and SHA-256).
export function readTable(tablesDir) {
  const manifest = JSON.parse(readFileSync(join(tablesDir, 'rows2.manifest.json'), 'utf8'));
  const raw = gunzipSync(readFileSync(join(tablesDir, manifest.file)));
  if (raw.length !== manifest.rawBytes) throw new Error(`rows2: ${raw.length} bytes, manifest says ${manifest.rawBytes}`);
  const sha = createHash('sha256').update(raw).digest('hex');
  if (sha !== manifest.sha256) throw new Error(`rows2: sha256 ${sha}, manifest says ${manifest.sha256}`);
  return raw;
}

export async function createModule(engineDir) {
  const create = (await import(pathToFileURL(join(engineDir, 'engine-cpp.js')).href)).default;
  return create();
}

// Copies bytes into the module heap and calls shw_load_rows2; returns { rc, error }.
export function loadRows2(m, bytes, verify) {
  const ptr = m._malloc(Math.max(bytes.length, 1));
  m.HEAPU8.set(bytes, ptr);
  const rc = m._shw_load_rows2(ptr, bytes.length, verify ? 1 : 0);
  m._free(ptr);
  return { rc, error: rc === 0 ? '' : m.UTF8ToString(m._shw_error()) };
}
