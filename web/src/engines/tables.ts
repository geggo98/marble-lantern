// Loading and checking the race table package (rows2.bin.gz plus rows2.manifest.json), plan section 3.4.
// Used by both browser engines and by the benchmark.
import type { LoadReport, Progress } from './engine';
import { EngineFailure } from './protocol';

/** Content of rows2.manifest.json (written by scripts/offline/pack-tables.py). */
export interface TableManifest {
  v: 1;
  alg: 'gzip';
  rawBytes: number;
  sha256: string;
  gzBytes: number;
  file: string;
}

export type VerifiedBy = LoadReport['tableVerifiedBy'];

export interface LoadedTable {
  /** The inflated table file. */
  bytes: ArrayBuffer;
  manifest: TableManifest;
  verifiedBy: VerifiedBy;
  fetchMs: number;
  inflateMs: number;
  verifyMs: number;
}

export interface LoadTableOptions {
  manifestUrl: string;
  tableUrl: string;
  /**
   * True if the engine can compute SHA-256 itself (C++). Used only when crypto.subtle is missing:
   * the result is then "engine" instead of "length-only" (TeaVM has no MessageDigest).
   */
  engineChecksum: boolean;
  onProgress?: (p: Progress) => void;
  /** For tests. Default: the global fetch. */
  fetch?: typeof fetch;
  /** For tests. Default: crypto.subtle; pass null to model an insecure context. */
  subtle?: SubtleCrypto | null;
}

const GZIP_MAGIC = [0x1f, 0x8b];
/** First four bytes of every table file (TableFile.MAGIC, "SHRT"). */
const TABLE_MAGIC = [0x53, 0x48, 0x52, 0x54];

const now = (): number => performance.now();

export function parseManifest(text: string): TableManifest {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new EngineFailure('table-truncated', 'table manifest is not valid JSON (cut short?)');
  }
  const m = value as Partial<TableManifest> | null;
  if (
    !m || m.v !== 1 || m.alg !== 'gzip' ||
    typeof m.rawBytes !== 'number' || typeof m.gzBytes !== 'number' ||
    typeof m.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(m.sha256) ||
    typeof m.file !== 'string'
  ) {
    throw new EngineFailure('internal', 'table manifest has an unexpected shape');
  }
  return m as TableManifest;
}

export async function sha256Hex(subtle: SubtleCrypto, bytes: ArrayBuffer): Promise<string> {
  const digest = new Uint8Array(await subtle.digest('SHA-256', bytes));
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('');
}

async function readBody(
  response: Response,
  onProgress: ((p: Progress) => void) | undefined,
): Promise<Uint8Array> {
  const total = Number(response.headers.get('content-length')) || 0;
  if (!response.body) return new Uint8Array(await response.arrayBuffer());
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    loaded += value.length;
    onProgress?.({ phase: 'fetch', loaded, total });
  }
  const out = new Uint8Array(loaded);
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.length;
  }
  return out;
}

async function inflateGzip(bytes: Uint8Array): Promise<Uint8Array> {
  try {
    const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  } catch (e) {
    // gzip carries CRC32 and the length; a cut-short download fails here, loudly.
    throw new EngineFailure('table-truncated', `gzip stream is damaged or cut short: ${String(e)}`);
  }
}

const startsWith = (bytes: Uint8Array, prefix: number[]): boolean =>
  bytes.length >= prefix.length && prefix.every((b, i) => bytes[i] === b);

/**
 * Checks, cheap before expensive, and throws EngineFailure with the protocol code at the first difference:
 * length (truncated, trailing), magic, SHA-256 (when a SubtleCrypto is given).
 */
export async function verifyTable(
  raw: Uint8Array,
  manifest: TableManifest,
  subtle: SubtleCrypto | null,
): Promise<void> {
  if (raw.length < manifest.rawBytes) {
    throw new EngineFailure('table-truncated', `table has ${raw.length} of ${manifest.rawBytes} bytes`);
  }
  if (raw.length > manifest.rawBytes) {
    throw new EngineFailure('table-trailing', `table has ${raw.length - manifest.rawBytes} bytes more than ${manifest.rawBytes}`);
  }
  if (!startsWith(raw, TABLE_MAGIC)) {
    throw new EngineFailure('table-magic', 'table does not start with the magic "SHRT"');
  }
  if (subtle) {
    const copy = raw.buffer.slice(raw.byteOffset, raw.byteOffset + raw.byteLength) as ArrayBuffer;
    const actual = await sha256Hex(subtle, copy);
    if (actual !== manifest.sha256) {
      throw new EngineFailure('table-checksum', `table SHA-256 is ${actual}, the manifest says ${manifest.sha256}`);
    }
  }
}

export async function loadTable(options: LoadTableOptions): Promise<LoadedTable> {
  const doFetch = options.fetch ?? fetch;
  const subtle = options.subtle === undefined ? (globalThis.crypto?.subtle ?? null) : options.subtle;

  const t0 = now();
  const manifestResponse = await doFetch(options.manifestUrl);
  if (!manifestResponse.ok) {
    throw new EngineFailure('internal', `manifest: HTTP ${manifestResponse.status}`);
  }
  const manifest = parseManifest(await manifestResponse.text());
  const tableResponse = await doFetch(options.tableUrl);
  if (!tableResponse.ok) {
    throw new EngineFailure('internal', `table: HTTP ${tableResponse.status}`);
  }
  const downloaded = await readBody(tableResponse, options.onProgress);
  const fetchMs = now() - t0;

  // The server may already have inflated the body (Content-Encoding: gzip on a .gz file): then there is no gzip magic.
  const t1 = now();
  let raw = downloaded;
  if (startsWith(downloaded, GZIP_MAGIC)) {
    if (downloaded.length !== manifest.gzBytes) {
      throw new EngineFailure('table-truncated', `download has ${downloaded.length} of ${manifest.gzBytes} bytes`);
    }
    options.onProgress?.({ phase: 'inflate', loaded: 0, total: manifest.rawBytes });
    raw = await inflateGzip(downloaded);
    options.onProgress?.({ phase: 'inflate', loaded: raw.length, total: manifest.rawBytes });
  }
  const inflateMs = now() - t1;

  const t2 = now();
  options.onProgress?.({ phase: 'verify', loaded: 0, total: manifest.rawBytes });
  await verifyTable(raw, manifest, subtle);
  const verifyMs = now() - t2;

  const verifiedBy: VerifiedBy = subtle ? 'subtle-sha256' : options.engineChecksum ? 'engine' : 'length-only';
  const bytes = raw.buffer.slice(raw.byteOffset, raw.byteOffset + raw.byteLength) as ArrayBuffer;
  return { bytes, manifest, verifiedBy, fetchMs, inflateMs, verifyMs };
}
