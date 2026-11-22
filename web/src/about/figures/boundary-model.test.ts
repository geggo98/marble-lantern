// Model of figure J7 (boundary-model.ts): the step table of the design spec cell by cell, the counters of the three builds, the payload literals,
// and the C++ call count read from the source of the glue. A change of web/src/engines/cpp/core.ts that adds or drops a call must fail here, not
// leave a wrong number ("N + 10") in the Info part.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import en from '../i18n/en/java-web.json';
import de from '../i18n/de/java-web.json';
import {
  ALSO,
  CPP_CALL_SITES,
  HOWS,
  STEPS,
  STEP_COUNT,
  STEP_IDS,
  TABLE_ROWS,
  VARIANTS,
  WALL_KIND,
  callsText,
  narrationKey,
  payload,
  rowOfStep,
} from './boundary-model';
import type { Variant } from './boundary-model';

const catalogs = { en, de } as Record<'en' | 'de', Record<string, string>>;

describe('steps (spec section 7)', () => {
  it('has seven states, 0 to 6, with the ids of the spec', () => {
    expect(STEP_IDS).toEqual(['idle', 'post', 'queue', 'call', 'run', 'return', 'reply']);
    expect(STEPS.map((s) => s.id)).toEqual([...STEP_IDS]);
    expect(STEP_COUNT).toBe(6);
  });

  it('matches the step table cell by cell', () => {
    // [active lanes, wall A, wall B, page/glue/engine, messages, worker]
    const table = [
      [[], null, null, ['idle', 'idle', 'idle'], 0, 'idle'],
      [['page'], 'in', null, ['free', 'idle', 'idle'], 1, 'idle'],
      [['glue'], null, null, ['free', 'busy', 'idle'], 1, 'busy'],
      [['glue', 'engine'], null, 'in', ['free', 'busy', 'busy'], 1, 'busy'],
      [['engine'], null, null, ['free', 'incall', 'busy'], 1, 'busy'],
      [['glue', 'engine'], null, 'out', ['free', 'busy', 'idle'], 1, 'busy'],
      [['page'], 'out', null, ['idle', 'idle', 'idle'], 2, 'idle'],
    ] as const;
    STEPS.forEach((s, i) => {
      const [active, a, b, states, msgs, worker] = table[i]!;
      expect([[...s.active], s.wallA, s.wallB, [...s.states], s.msgs, s.worker], `step ${i}`).toEqual([[...active], a, b, [...states], msgs, worker]);
    });
  });

  it('crosses wall A exactly twice (one request in, one reply out), and wall B twice (the call, the return)', () => {
    expect(STEPS.filter((s) => s.wallA).map((s) => s.wallA)).toEqual(['in', 'out']);
    expect(STEPS.filter((s) => s.wallB).map((s) => s.wallB)).toEqual(['in', 'out']);
  });

  it('keeps the page free (not blocked) while the worker is busy', () => {
    for (const s of STEPS.filter((x) => x.worker === 'busy')) expect(s.states[0]).toBe('free');
  });

  it('marks the table rows call, thread, types at steps 3, 4, 5 and no other', () => {
    expect(STEPS.map((_, i) => rowOfStep(i))).toEqual([undefined, undefined, undefined, 'call', 'thread', 'types', undefined]);
  });
});

describe('counters (spec section 6)', () => {
  it('counts one call for the TeaVM builds, N + 4 and N + 10 for C++', () => {
    const text = (v: Variant): string[] => STEPS.map((_, i) => callsText(v, i));
    expect(text('js')).toEqual(['0', '0', '0', '1', '1', '1', '1']);
    expect(text('wasmgc')).toEqual(text('js'));
    expect(text('cpp')).toEqual(['0', '0', '0', 'N + 4', 'N + 4', 'N + 10', 'N + 10']);
  });

  it('draws wall B as dotted, double and thick for js, wasmgc and cpp', () => {
    expect(VARIANTS.map((v) => WALL_KIND[v])).toEqual(['none', 'convert', 'slot']);
  });
});

describe('narration keys', () => {
  it('names one key per step, with a key per variant at step 3 and a key per family at step 5; all exist in both languages', () => {
    for (const v of VARIANTS) {
      const keys = STEPS.map((_, i) => narrationKey(i, v));
      expect(keys.slice(0, 3)).toEqual(['fig.boundary.narr.idle', 'fig.boundary.narr.post', 'fig.boundary.narr.queue']);
      expect(keys[3]).toBe(`fig.boundary.narr.call.${v}`);
      expect(keys[4]).toBe('fig.boundary.narr.run');
      expect(keys[5]).toBe(v === 'cpp' ? 'fig.boundary.narr.return.cpp' : 'fig.boundary.narr.return.teavm');
      expect(keys[6]).toBe('fig.boundary.narr.reply');
      for (const lang of ['en', 'de'] as const) for (const k of keys) expect(catalogs[lang][k], `${lang} ${k}`).toBeTruthy();
    }
  });
});

describe('payload card (spec sections 4 and 6)', () => {
  it('says how data cross at each step', () => {
    const how = (v: Variant): string[] => STEPS.map((_, i) => payload(i, v).how);
    expect(how('js')).toEqual(['none', 'clone', 'none', 'direct', 'none', 'direct', 'clone']);
    expect(how('wasmgc')).toEqual(['none', 'clone', 'none', 'convert', 'none', 'convert', 'clone']);
    expect(how('cpp')).toEqual(['none', 'clone', 'none', 'numbers', 'none', 'numbers', 'clone']);
    expect(new Set(VARIANTS.flatMap((v) => STEPS.map((_, i) => payload(i, v).how)))).toEqual(new Set(HOWS));
  });

  it('quotes the literals of the repository', () => {
    const lines = (i: number, v: Variant): string => payload(i, v).lines.map((l) => ('code' in l ? l.code : `<${l.note}>`)).join('\n');
    expect(lines(1, 'js')).toContain("worker.postMessage({ id, type: 'choose'");
    expect(lines(3, 'js')).toContain('m.choose("m1,…,mN", jump, players,');
    expect(lines(3, 'cpp')).toContain('_shw_game_new5(jump, players, ranking, camps, rows)');
    expect(lines(3, 'cpp')).toContain('_shw_play(m1) … _shw_play(mN)');
    expect(lines(4, 'cpp')).toContain('int shw_choose(int player_id)');
    expect(lines(4, 'js')).toContain('WebCore.Choice c = CORE.choose(parse(history), …);');
    expect(lines(5, 'wasmgc')).toContain('"ok <move> <score> <depth> <nodes> <ms>');
    expect(lines(6, 'js')).toContain('postMessage({ id, ok: true, result: { move, score,');
  });

  it('uses only note keys that exist in both languages', () => {
    for (const lang of ['en', 'de'] as const) {
      for (const v of VARIANTS) {
        STEPS.forEach((_, i) => {
          for (const l of payload(i, v).lines) if ('note' in l) expect(catalogs[lang][l.note], `${lang} ${l.note}`).toBeTruthy();
        });
      }
    }
  });
});

describe('texts of the figure', () => {
  it('has a text in both languages for the rows, cells and notes the model names', () => {
    const keys = [
      ...TABLE_ROWS.flatMap((r) => [`fig.boundary.row.${r.id}`, ...VARIANTS.map((v) => `fig.boundary.cell.${r.id}.${v}`)]),
      'fig.boundary.row.thread',
      'fig.boundary.cell.thread.all',
      ...ALSO.map((a) => `fig.boundary.also.${a}`),
      ...HOWS.map((h) => `fig.boundary.how.${h}`),
    ];
    for (const lang of ['en', 'de'] as const) for (const k of keys) expect(catalogs[lang][k], `${lang} ${k}`).toBeTruthy();
  });
});

describe('the C++ call count against the source of the glue', () => {
  const source = readFileSync(fileURLToPath(new URL('../../engines/cpp/core.ts', import.meta.url)), 'utf8');

  /** The text of a method from its signature to the closing brace at the same indent (the file indents methods by two spaces). */
  const method = (signature: RegExp): string => {
    const m = signature.exec(source);
    if (!m) throw new Error(`no method ${signature}`);
    const rest = source.slice(m.index);
    const end = rest.search(/\n {2}\}\n/);
    return rest.slice(0, end + 4);
  };
  /** Call sites `this.m._shw_*(` of a text, leaving out `_shw_error` (it is called on error paths only). */
  const sites = (text: string): string[] => [...text.matchAll(/this\.m\._shw_(?!error\b)(\w+)\(/g)].map((x) => x[1]!);

  it('has 10 call sites outside the loop and 1 inside it in choose() and replay()', () => {
    const choose = method(/\n {2}choose\(history: readonly number\[\], variant\?: Variant\): ChooseResult \{/);
    const replay = method(/\n {2}private replay\(history: readonly number\[\], variant: Variant\): void \{/);
    const loopStart = replay.indexOf('history.forEach(');
    expect(loopStart, 'replay() loops over the history with forEach').toBeGreaterThan(0);
    const loop = replay.slice(loopStart);
    const outside = [...sites(choose), ...sites(replay.slice(0, loopStart))];
    expect(sites(loop)).toEqual(['play']);
    expect([...outside].sort()).toEqual(['choose', 'game_new5', 'has_result', 'last_depth', 'last_nodes', 'last_score', 'race_moves', 'race_moves', 'rows1_requests', 'side_to_move']);
    expect(outside).toHaveLength(CPP_CALL_SITES.outsideLoop);
    expect(sites(loop)).toHaveLength(CPP_CALL_SITES.insideLoop);
  });

  it('the TeaVM reply is one string with "ok" and seven fields (Web.java)', () => {
    const web = readFileSync(fileURLToPath(new URL('../../../../teavm-web/src/main/java/sternhalma/web/Web.java', import.meta.url)), 'utf8');
    expect(web).toContain('{@code ok <move> <score> <depth> <nodes> <ms> <raceMove 0|1> <rows1Requests>}'); // the card prints raceMove without the "0|1"
  });
});
