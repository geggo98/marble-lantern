// The manifest part of the net models (types, errors, models.json, which model may play which variant). No loader code: the main
// bundle imports this file for the model list, the loader (models.ts) belongs to the worker. Re-exported by models.ts.
import manifestJson from '../../../models/models.json';
import type { Variant } from '../../game/variant';
import { rowsOf, sameVariant, withRows } from '../../game/variant';

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
  /**
   * The variants the model may be used for. Authoritative. A manifest variant is `{jump, players}` (4 rows) or
   * `{jump, players, rows: 5}`; here a 5-row variant carries `rows: 5` and a 4-row one no `rows` (withRows).
   */
  variants: Variant[];
  /**
   * The board sizes of the variants as the network's metadata key `triangle_rows` writes them: "4", "5" or "4,5". The loader
   * checks the header against it, and the net engine passes it to the facade (setWeightsInfoRows).
   */
  rows: string;
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
    // `rows` is optional and was added for the 5-row networks. An app built before it rejects a manifest with the field, but every
    // app reads only the manifest compiled into its own bundle (listModels), so an open tab of an older version never sees it.
    const keys = isObject(raw) ? Object.keys(raw) : [];
    if (!isObject(raw) || !keys.includes('jump') || !keys.includes('players') || keys.some((k) => k !== 'jump' && k !== 'players' && k !== 'rows')) {
      return invalid(`${at}.variants[${i}] must be {jump, players} or {jump, players, rows}`);
    }
    const { jump, players: p, rows } = raw;
    if ((jump !== 'adjacent' && jump !== 'long') || (p !== 2 && p !== 3)) {
      return invalid(`${at}.variants[${i}] has an unknown jump rule or player count`);
    }
    if ('rows' in raw && rows !== 4 && rows !== 5) invalid(`${at}.variants[${i}].rows must be 4 or 5`);
    const variant: Variant = withRows({ jump, players: p }, rows === 5 ? 5 : 4);
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
    rows: rowsText(variants),
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

/** The value of the metadata key `triangle_rows` that a list of variants needs: "4", "5" or "4,5". */
export function rowsText(variants: readonly Variant[]): string {
  return [...new Set(variants.map(rowsOf))].sort((a, b) => a - b).join(',');
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
