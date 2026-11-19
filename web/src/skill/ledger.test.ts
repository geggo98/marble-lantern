import { describe, expect, it } from 'vitest';
import { LEDGER_KEY, LEDGER_MAX, browserLedgerStore, hashKey, parseLedger, serializeLedger } from './ledger';

describe('hashKey', () => {
  it('is stable, 16 hex characters, and differs for different positions', () => {
    expect(hashKey('1,2,3')).toMatch(/^[0-9a-f]{16}$/);
    expect(hashKey('1,2,3')).toBe(hashKey('1,2,3'));
    const seen = new Set<string>();
    for (let i = 0; i < 5000; i++) seen.add(hashKey(`${i},${i * 7},${i % 13}`));
    expect(seen.size).toBe(5000);
  });
});

describe('parseLedger / serializeLedger', () => {
  it('round-trips', () => {
    const data = { paused: true, entries: [[hashKey('a'), 12.5], [hashKey('b'), 0]] as [string, number][] };
    expect(parseLedger(serializeLedger(data))).toEqual(data);
  });
  it('gives an empty ledger, never an exception, for anything unexpected', () => {
    const empty = { paused: false, entries: [] };
    for (const text of [null, '', '{', 'null', '[]', '{"v":2,"paused":false,"entries":[]}', '{"v":1,"paused":"no","entries":[]}',
      '{"v":1,"paused":false,"entries":[["zz",1]]}', `{"v":1,"paused":false,"entries":[["${hashKey('a')}",-1]]}`,
      `{"v":1,"paused":false,"entries":[["${hashKey('a')}","1"]]}`, `{"v":1,"paused":false,"entries":[["${hashKey('a')}"]]}`]) {
      expect(parseLedger(text)).toEqual(empty);
    }
  });
  it('keeps only the newest entries', () => {
    const entries = Array.from({ length: LEDGER_MAX + 5 }, (_, i): [string, number] => [hashKey(String(i)), i]);
    const back = parseLedger(serializeLedger({ paused: false, entries }));
    expect(back.entries).toHaveLength(LEDGER_MAX);
    expect(back.entries[LEDGER_MAX - 1]![1]).toBe(LEDGER_MAX + 4);
  });
  it('stores no moves, only hashes and numbers', () => {
    expect(serializeLedger({ paused: false, entries: [[hashKey('12,345,6'), 7]] })).not.toContain('12,345');
  });
});

describe('browserLedgerStore', () => {
  const fake = (): Storage => {
    const m = new Map<string, string>();
    return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v), removeItem: (k) => void m.delete(k), clear: () => m.clear(), key: () => null, length: 0 } as Storage;
  };
  it('saves to storage and a new store reads it back (another visit)', () => {
    const s = fake();
    browserLedgerStore(() => s).save({ paused: true, entries: [[hashKey('x'), 3]] });
    expect(s.getItem(LEDGER_KEY)).not.toBeNull();
    expect(browserLedgerStore(() => s).load()).toEqual({ paused: true, entries: [[hashKey('x'), 3]] });
  });
  it('keeps working in memory when storage is missing or throws', () => {
    for (const storage of [() => null, () => { throw new Error('blocked'); }]) {
      const store = browserLedgerStore(storage);
      store.save({ paused: false, entries: [[hashKey('x'), 3]] });
      expect(store.load().entries).toEqual([[hashKey('x'), 3]]);
    }
    const full = { getItem: () => null, setItem: () => { throw new Error('quota'); } } as unknown as Storage;
    const store = browserLedgerStore(() => full);
    expect(() => store.save({ paused: false, entries: [] })).not.toThrow();
  });
});
