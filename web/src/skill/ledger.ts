// The measurement ledger: the loss of every human move that was measured, kept in this browser over many games.
// Only numbers and a hash of each position are stored (never the moves), so the entry is small and says nothing about a
// game. localStorage can be missing, full or blocked (private window): every access is guarded, and the app then keeps
// the ledger in memory for the life of the page.

export const LEDGER_KEY = 'ml.skill.v1';
/** The newest entries are kept; with the move limit of 200 plies this is about ten full games. */
export const LEDGER_MAX = 1000;

export interface LedgerData {
  /** Measuring is paused (another person plays): nothing is analysed and nothing is recorded. */
  paused: boolean;
  /** [position hash, raw loss], oldest first. Games with the adjacent jump rule; every entry written before the game variants is of this kind. */
  entries: [string, number][];
  /** The same for two-player games with the long hop. Missing (not an empty list) in files that have none. */
  long?: [string, number][];
}

export interface LedgerStore {
  load(): LedgerData;
  save(data: LedgerData): void;
}

/** Hash of a position with the move that was played, 64 bit as 16 hex characters (two FNV-1a runs). Not a secret. */
export function hashKey(text: string): string {
  let a = 0x811c9dc5;
  let b = 0x01000193 ^ 0x9e3779b9;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    a = Math.imul(a ^ c, 0x01000193);
    b = Math.imul(b ^ c, 0x85ebca6b) ^ (b >>> 13);
  }
  return (a >>> 0).toString(16).padStart(8, '0') + (b >>> 0).toString(16).padStart(8, '0');
}

export const EMPTY_LEDGER: LedgerData = { paused: false, entries: [] };

/** Reads stored text strictly: anything unexpected gives an empty ledger, never an exception. */
export function parseLedger(text: string | null): LedgerData {
  if (!text) return { paused: false, entries: [] };
  try {
    const v = JSON.parse(text) as { v?: unknown; paused?: unknown; entries?: unknown; long?: unknown };
    if (v.v !== 1 || typeof v.paused !== 'boolean' || !Array.isArray(v.entries)) return { paused: false, entries: [] };
    const entries = parseEntries(v.entries);
    const long = v.long === undefined ? undefined : Array.isArray(v.long) ? parseEntries(v.long) : null;
    if (entries === null || long === null) return { paused: false, entries: [] };
    return { paused: v.paused, entries, ...(long ? { long } : {}) };
  } catch {
    return { paused: false, entries: [] };
  }
}

/** The checked entries, or null when one of them is not a [16 hex characters, loss >= 0] pair. */
function parseEntries(list: unknown[]): [string, number][] | null {
  const entries: [string, number][] = [];
  for (const e of list.slice(-LEDGER_MAX)) {
    if (!Array.isArray(e) || e.length !== 2) return null;
    const [h, loss] = e as [unknown, unknown];
    if (typeof h !== 'string' || !/^[0-9a-f]{16}$/.test(h) || typeof loss !== 'number' || !Number.isFinite(loss) || loss < 0) return null;
    entries.push([h, loss]);
  }
  return entries;
}

export function serializeLedger(data: LedgerData): string {
  return JSON.stringify({
    v: 1,
    paused: data.paused,
    entries: data.entries.slice(-LEDGER_MAX),
    ...(data.long && data.long.length > 0 ? { long: data.long.slice(-LEDGER_MAX) } : {}),
  });
}

/** The store of the page: localStorage when it works, memory otherwise. */
export function browserLedgerStore(storage: () => Storage | null = () => globalThis.localStorage ?? null): LedgerStore {
  let memory: LedgerData = { paused: false, entries: [] };
  return {
    load() {
      try {
        const s = storage();
        if (s) {
          memory = parseLedger(s.getItem(LEDGER_KEY));
        }
      } catch {
        /* blocked: keep the memory copy */
      }
      return { paused: memory.paused, entries: [...memory.entries], ...(memory.long ? { long: [...memory.long] } : {}) };
    },
    save(data) {
      memory = { paused: data.paused, entries: data.entries.slice(-LEDGER_MAX), ...(data.long ? { long: data.long.slice(-LEDGER_MAX) } : {}) };
      try {
        storage()?.setItem(LEDGER_KEY, serializeLedger(memory));
      } catch {
        /* full or blocked: the memory copy still serves this page */
      }
    },
  };
}
