// Net models: the manifest web/models/models.json (written by scripts/models/pack-model.py), which model may play which
// variant, and the loader (fetch, gunzip, verify, widen F16 to F32). The bytes loadModel returns are an F32 safetensors
// file for NetBackend.load (IC1).
import manifestJson from '../../../models/models.json';
import type { Variant } from '../../game/variant';
import { sameVariant } from '../../game/variant';
import { sha256Hex } from '../tables';

export type ModelErrorCode =
  | 'manifest-truncated' // the manifest text is not valid JSON
  | 'manifest-invalid' // valid JSON with a wrong shape or inconsistent values
  | 'model-http' // the packed file could not be fetched
  | 'model-truncated' // fewer bytes than the manifest says (packed or inflated)
  | 'model-trailing' // more bytes than the manifest says
  | 'model-corrupt' // gunzip failed
  | 'model-checksum' // sha256 differs
  | 'model-header' // the safetensors header is damaged or not what the loader supports
  | 'model-mismatch'; // header metadata contradicts the manifest entry

/** A typed error of this module. */
export class ModelError extends Error {
  readonly code: ModelErrorCode;
  constructor(code: ModelErrorCode, message: string) {
    super(message);
    this.name = 'ModelError';
    this.code = code;
  }
}

export type PlaneSet = 'plain' | 'classic1' | 'classic1v';

/** Input planes per plane set (docs/network.md). */
export const PLANES_OF: Readonly<Record<PlaneSet, number>> = { plain: 7, classic1: 13, classic1v: 15 };

export interface ModelInfo {
  id: string;
  labelKey: string;
  descriptionKey: string;
  /** The variants the model may be used for. Authoritative. */
  variants: Variant[];
  /** Number of input planes. */
  planes: number;
  planeSet: PlaneSet;
  formatVersion: number;
  /** Width of the value head (2 or 3): the largest player count the model can play. */
  players: number;
  params: number;
  /** Size of the inflated F16 safetensors file. */
  rawBytes: number;
  /** Size of the file in the repository (gzip). */
  packedBytes: number;
  sha256Raw: string;
  sha256Packed: string;
  /** File name below the models directory. */
  file: string;
  sourceSha256: string;
  createdFrom: string;
  /** Free-form measured fields (strength against reference players). */
  strength?: Record<string, unknown>;
}

const KEYS = [
  'id', 'rawBytesF16', 'packedBytes', 'sha256Raw', 'sha256Packed', 'file', 'formatVersion', 'planeSet', 'planes',
  'players', 'params', 'variants', 'sourceSha256', 'createdFrom', 'strength', 'labelKey', 'descriptionKey',
] as const;
const ID_RE = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const SHA_RE = /^[0-9a-f]{64}$/;

const invalid = (m: string): never => {
  throw new ModelError('manifest-invalid', m);
};
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const posInt = (v: unknown, what: string): number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v > 0 ? v : invalid(`${what} must be a positive integer`);
const text = (v: unknown, what: string): string =>
  typeof v === 'string' && v.length > 0 ? v : invalid(`${what} must be a non-empty string`);
const hash = (v: unknown, what: string): string =>
  typeof v === 'string' && SHA_RE.test(v) ? v : invalid(`${what} must be 64 lower-case hex digits`);

function parseEntry(value: unknown, at: string): ModelInfo {
  if (!isObject(value)) return invalid(`${at} is not an object`);
  for (const k of Object.keys(value)) {
    if (!(KEYS as readonly string[]).includes(k)) invalid(`${at}: unknown key ${k}`);
  }
  for (const k of KEYS) {
    if (!(k in value)) invalid(`${at}: missing key ${k}`);
  }
  const id = text(value.id, `${at}.id`);
  if (!ID_RE.test(id)) invalid(`${at}.id ${JSON.stringify(id)} is not lower-case words joined by '-'`);
  const planeSet = value.planeSet;
  if (planeSet !== 'plain' && planeSet !== 'classic1' && planeSet !== 'classic1v') {
    return invalid(`${at}.planeSet is not one of plain, classic1, classic1v`);
  }
  const planes = posInt(value.planes, `${at}.planes`);
  if (planes !== PLANES_OF[planeSet]) invalid(`${at}: plane set ${planeSet} has ${PLANES_OF[planeSet]} planes, the entry says ${planes}`);
  const players = posInt(value.players, `${at}.players`);
  if (players !== 2 && players !== 3) invalid(`${at}.players must be 2 or 3`);
  const file = text(value.file, `${at}.file`);
  if (file !== `${id}.safetensors.gz`) invalid(`${at}.file must be ${id}.safetensors.gz`);
  if (!Array.isArray(value.variants) || value.variants.length === 0) invalid(`${at}.variants must be a non-empty array`);
  const variants: Variant[] = [];
  for (const [i, raw] of (value.variants as unknown[]).entries()) {
    if (!isObject(raw) || Object.keys(raw).length !== 2) {
      return invalid(`${at}.variants[${i}] must be {jump, players}`);
    }
    const { jump, players: p } = raw;
    if ((jump !== 'adjacent' && jump !== 'long') || (p !== 2 && p !== 3)) {
      return invalid(`${at}.variants[${i}] has an unknown jump rule or player count`);
    }
    const variant: Variant = { jump, players: p };
    if (variants.some((v) => sameVariant(v, variant))) invalid(`${at}.variants[${i}] is a duplicate`);
    if (p > players) invalid(`${at}.variants[${i}]: a model with ${players} value outputs cannot play ${p} players`);
    variants.push(variant);
  }
  if (value.strength !== undefined && !isObject(value.strength)) invalid(`${at}.strength must be an object`);
  return {
    id,
    labelKey: text(value.labelKey, `${at}.labelKey`),
    descriptionKey: text(value.descriptionKey, `${at}.descriptionKey`),
    variants,
    planes,
    planeSet,
    formatVersion: posInt(value.formatVersion, `${at}.formatVersion`),
    players,
    params: posInt(value.params, `${at}.params`),
    rawBytes: posInt(value.rawBytesF16, `${at}.rawBytesF16`),
    packedBytes: posInt(value.packedBytes, `${at}.packedBytes`),
    sha256Raw: hash(value.sha256Raw, `${at}.sha256Raw`),
    sha256Packed: hash(value.sha256Packed, `${at}.sha256Packed`),
    file,
    sourceSha256: hash(value.sourceSha256, `${at}.sourceSha256`),
    createdFrom: text(value.createdFrom, `${at}.createdFrom`),
    strength: value.strength as Record<string, unknown> | undefined,
  };
}

/** Strict parse of the manifest (text or an already parsed value). Throws ModelError. */
export function parseModelManifest(source: string | unknown): ModelInfo[] {
  let value: unknown = source;
  if (typeof source === 'string') {
    try {
      value = JSON.parse(source);
    } catch {
      throw new ModelError('manifest-truncated', 'model manifest is not valid JSON (cut short?)');
    }
  }
  if (!isObject(value) || value.v !== 1 || !Array.isArray(value.models) || Object.keys(value).length !== 2) {
    return invalid('model manifest must be {"v":1,"models":[...]}');
  }
  const models = (value.models as unknown[]).map((m, i) => parseEntry(m, `models[${i}]`));
  const seen = new Set<string>();
  for (const m of models) {
    if (seen.has(m.id)) invalid(`duplicate model id ${m.id}`);
    seen.add(m.id);
  }
  return models;
}

let bundled: ModelInfo[] | undefined;

/** The models of the manifest that is compiled into the app. */
export function listModels(): ModelInfo[] {
  bundled ??= parseModelManifest(manifestJson);
  return bundled;
}

/** The models allowed for a variant. The variants list of an entry is authoritative. */
export function modelsFor(variant: Variant, models: readonly ModelInfo[] = listModels()): ModelInfo[] {
  return models.filter((m) => m.variants.some((v) => sameVariant(v, variant)));
}

/** True if the model may be used for the variant. */
export function modelFits(info: ModelInfo, variant: Variant): boolean {
  return info.variants.some((v) => sameVariant(v, variant));
}

// ---- safetensors: header reading and F16 -> F32 widening ----

interface TensorSpec {
  name: string;
  shape: number[];
  begin: number;
  end: number;
}

interface Header {
  metadata: Record<string, string>;
  tensors: TensorSpec[];
  dataStart: number;
}

const header = (m: string): never => {
  throw new ModelError('model-header', m);
};

function readHeader(bytes: Uint8Array): Header {
  if (bytes.length < 8) return header('file is shorter than 8 bytes');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const n = view.getBigUint64(0, true);
  if (n > BigInt(bytes.length - 8)) return header('header is longer than the file');
  const dataStart = 8 + Number(n);
  let json: unknown;
  try {
    json = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(8, dataStart)));
  } catch {
    return header('header is not valid JSON');
  }
  if (!isObject(json)) return header('header is not an object');
  const metadata: Record<string, string> = {};
  const tensors: TensorSpec[] = [];
  for (const [name, v] of Object.entries(json)) {
    if (name === '__metadata__') {
      if (!isObject(v)) return header('__metadata__ is not an object');
      for (const [k, s] of Object.entries(v)) {
        if (typeof s !== 'string') return header(`metadata ${k} is not a string`);
        metadata[k] = s;
      }
      continue;
    }
    if (!isObject(v) || v.dtype !== 'F16' || !Array.isArray(v.shape) || !Array.isArray(v.data_offsets) || v.data_offsets.length !== 2) {
      return header(`tensor ${name}: expected dtype F16, shape and data_offsets`);
    }
    const shape = v.shape as unknown[];
    const [begin, end] = v.data_offsets as unknown[];
    if (!shape.every((d) => typeof d === 'number' && Number.isSafeInteger(d) && d >= 0)
      || typeof begin !== 'number' || typeof end !== 'number' || !Number.isSafeInteger(begin) || !Number.isSafeInteger(end)) {
      return header(`tensor ${name}: bad shape or offsets`);
    }
    const count = (shape as number[]).reduce((a, d) => a * d, 1);
    if (end - begin !== 2 * count) return header(`tensor ${name}: ${end - begin} bytes for ${count} F16 values`);
    tensors.push({ name, shape: shape as number[], begin, end });
  }
  let pos = 0;
  for (const t of [...tensors].sort((a, b) => a.begin - b.begin)) {
    if (t.begin !== pos) return header(`tensor ${t.name} starts at ${t.begin}, expected ${pos} (gap or overlap)`);
    pos = t.end;
  }
  if (pos !== bytes.length - dataStart) return header(`data section has ${bytes.length - dataStart} bytes, the tensors cover ${pos}`);
  return { metadata, tensors, dataStart };
}

const f32 = new Float32Array(1);
const f32bits = new Uint32Array(f32.buffer);

/** IEEE half to single, exact for every half value (subnormals, infinities, NaN included). */
export function halfToFloat(h: number): number {
  const sign = h & 0x8000 ? -1 : 1;
  const exp = (h >> 10) & 0x1f;
  const frac = h & 0x3ff;
  if (exp === 0) return sign * frac * 2 ** -24;
  if (exp === 31) return frac ? NaN : sign * Infinity;
  f32bits[0] = ((h & 0x8000) << 16) | ((exp + 112) << 23) | (frac << 13);
  return f32[0] as number;
}

/** Builds the F32 safetensors file of an F16 file; the metadata keys weights_dtype and source_sha256 are dropped. */
export function widenSafetensors(f16: Uint8Array): Uint8Array {
  const h = readHeader(f16);
  const out: Record<string, unknown> = {};
  const meta: Record<string, string> = {};
  for (const [k, v] of Object.entries(h.metadata)) {
    if (k !== 'weights_dtype' && k !== 'source_sha256') meta[k] = v;
  }
  out.__metadata__ = meta;
  const order = [...h.tensors].sort((a, b) => a.begin - b.begin);
  const pos = new Map<string, number>();
  let at = 0;
  for (const t of order) pos.set(t.name, at), (at += 2 * (t.end - t.begin));
  for (const t of h.tensors) {
    const b = pos.get(t.name) as number;
    out[t.name] = { dtype: 'F32', shape: t.shape, data_offsets: [b, b + 2 * (t.end - t.begin)] };
  }
  let head: Uint8Array = new TextEncoder().encode(JSON.stringify(out));
  const pad = (8 - (head.length % 8)) % 8;
  if (pad) {
    const padded = new Uint8Array(head.length + pad).fill(0x20);
    padded.set(head);
    head = padded;
  }
  const result = new Uint8Array(8 + head.length + at);
  const view = new DataView(result.buffer);
  view.setBigUint64(0, BigInt(head.length), true);
  result.set(head, 8);
  const src = new DataView(f16.buffer, f16.byteOffset, f16.byteLength);
  const base = 8 + head.length;
  for (const t of order) {
    const n = (t.end - t.begin) / 2;
    const from = h.dataStart + t.begin;
    const to = base + (pos.get(t.name) as number);
    for (let i = 0; i < n; i++) view.setFloat32(to + 4 * i, halfToFloat(src.getUint16(from + 2 * i, true)), true);
  }
  return result;
}

// ---- loading ----

const GZIP_MAGIC = [0x1f, 0x8b];
const startsWith = (b: Uint8Array, p: number[]): boolean => b.length >= p.length && p.every((x, i) => b[i] === x);

async function gunzip(bytes: Uint8Array): Promise<Uint8Array> {
  try {
    const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  } catch (e) {
    // gzip carries CRC32 and the length: a cut-short download fails here, loudly.
    throw new ModelError('model-corrupt', `gzip stream is damaged or cut short: ${String(e)}`);
  }
}

function checkLength(actual: number, expected: number, what: string): void {
  if (actual < expected) throw new ModelError('model-truncated', `${what} has ${actual} of ${expected} bytes`);
  if (actual > expected) throw new ModelError('model-trailing', `${what} has ${actual - expected} bytes more than ${expected}`);
}

export interface LoadModelOptions {
  /** Directory of the packed files with a trailing slash. Default: `${BASE_URL}models/`. */
  baseUrl?: string;
  /** For tests. Default: crypto.subtle; null models an insecure context (then only the lengths are checked). */
  subtle?: SubtleCrypto | null;
}

/**
 * Fetches and checks a packed model, cheap before expensive: packed length, sha256 of the packed file, gunzip, inflated
 * length, sha256 of the inflated file, header consistency with the manifest entry. Returns the F32 safetensors bytes.
 * Throws ModelError.
 */
export async function loadModel(
  info: ModelInfo,
  doFetch: typeof fetch = fetch,
  options: LoadModelOptions = {},
): Promise<Uint8Array> {
  const subtle = options.subtle === undefined ? (globalThis.crypto?.subtle ?? null) : options.subtle;
  const hex = async (b: Uint8Array): Promise<string> =>
    sha256Hex(subtle as SubtleCrypto, b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer);
  const url = `${options.baseUrl ?? `${import.meta.env.BASE_URL}models/`}${info.file}`;
  const response = await doFetch(url);
  if (!response.ok) throw new ModelError('model-http', `${url}: HTTP ${response.status}`);
  const body = new Uint8Array(await response.arrayBuffer());

  // The server may already have inflated the body (Content-Encoding: gzip on a .gz file): then there is no gzip magic.
  let raw: Uint8Array = body;
  if (startsWith(body, GZIP_MAGIC)) {
    checkLength(body.length, info.packedBytes, 'packed model');
    if (subtle && (await hex(body)) !== info.sha256Packed) {
      throw new ModelError('model-checksum', `packed model ${info.id}: sha256 differs from the manifest`);
    }
    raw = await gunzip(body);
  }
  checkLength(raw.length, info.rawBytes, 'model');
  if (subtle && (await hex(raw)) !== info.sha256Raw) {
    throw new ModelError('model-checksum', `model ${info.id}: sha256 of the inflated file differs from the manifest`);
  }

  const h = readHeader(raw);
  const expect: Array<[string, string, string]> = [
    ['format', 'sternhalma-nn', h.metadata.format ?? ''],
    ['format_version', String(info.formatVersion), h.metadata.format_version ?? ''],
    ['input_planes', String(info.planes), h.metadata.input_planes ?? ''],
    ['planes', info.planeSet, h.metadata.planes ?? ''],
    ['players', String(info.players), h.metadata.players ?? ''],
    ['weights_dtype', 'F16', h.metadata.weights_dtype ?? ''],
    ['source_sha256', info.sourceSha256, h.metadata.source_sha256 ?? ''],
  ];
  for (const [key, want, got] of expect) {
    if (want !== got) throw new ModelError('model-mismatch', `model ${info.id}: header ${key} is ${JSON.stringify(got)}, the manifest says ${JSON.stringify(want)}`);
  }
  const params = h.tensors.reduce((n, t) => n + t.shape.reduce((a, d) => a * d, 1), 0);
  if (params !== info.params) {
    throw new ModelError('model-mismatch', `model ${info.id}: the tensors hold ${params} weights, the manifest says ${info.params}`);
  }
  return widenSafetensors(raw);
}
