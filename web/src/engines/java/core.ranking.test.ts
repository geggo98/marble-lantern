// The ranking argument of the Java core and the new outcome replies, against a stub of the facade: no engine build is needed.
// The real engines are compared in engines/variants.cross.test.ts.
import { describe, expect, it } from 'vitest';
import { EngineFailure } from '../protocol';
import { JavaCore } from './core';
import type { JavaModule } from './core';

interface Call {
  name: string;
  args: unknown[];
}

/** A facade that records every call and answers `outcome` with `reply`. */
function stub(reply: string): { module: JavaModule; calls: Call[] } {
  const calls: Call[] = [];
  const record = (name: string, answer: string) => (...args: unknown[]): string => {
    calls.push({ name, args });
    return answer;
  };
  const module: JavaModule = {
    setTable: record('setTable', 'ok 1'),
    newGame: record('newGame', 'ok'),
    legalMoves: record('legalMoves', 'ok 1,2'),
    choose: record('choose', 'ok 1 0 1 1 1 0 0'),
    bench: record('bench', 'ok 1 1 1 0'),
    analyse: record('analyse', 'ok 1 1 0 1 1'),
    outcome: record('outcome', reply),
    evaluate3: record('evaluate3', 'ok 1 2 3'),
  };
  return { module, calls };
}

describe('ranking in the Java core', () => {
  // Since 2026-11-20 the foreign-corner rule (camps) follows the ranking, and the board size (rows) follows the camps as
  // the last argument: the camps are the one before it, the ranking the one before the camps.
  it('passes the ranking before the foreign-corner rule in every call, "first" when the variant has none', () => {
    const { module, calls } = stub('ok ongoing');
    const core = JavaCore.create(module);
    core.newGame({ nodes: [100, 100, 100], variant: { jump: 'long', players: 3, ranking: 'full' } });
    core.legalMoves([], { jump: 'long', players: 3, ranking: 'full' });
    core.choose([], { jump: 'long', players: 3, ranking: 'full' });
    core.outcome([], { jump: 'long', players: 3, ranking: 'full' });
    core.bench(3, 100, { jump: 'long', players: 3, ranking: 'full' });
    core.analyse([], null, 100, { jump: 'long', players: 3, ranking: 'full' });
    core.evaluate3([], { jump: 'long', players: 3, ranking: 'full' });
    core.evaluate3([], { jump: 'long', players: 3 });
    core.legalMoves([], { jump: 'long', players: 3 });
    core.outcome([], { jump: 'long', players: 3 });
    const last = (name: string, index: number): unknown => calls.filter((c) => c.name === name)[index]!.args.at(-3);
    expect(last('newGame', 0)).toBe('full');
    expect(last('legalMoves', 0)).toBe('full');
    expect(last('choose', 0)).toBe('full');
    expect(last('outcome', 0)).toBe('full');
    expect(last('bench', 0)).toBe('full');
    expect(last('analyse', 0)).toBe('full');
    expect(last('evaluate3', 0)).toBe('full');
    expect(last('evaluate3', 1)).toBe('first');
    expect(last('legalMoves', 1)).toBe('first');
    expect(last('outcome', 1)).toBe('first');
    expect(calls.every((c) => c.args.at(-2) === 'closed'), 'camps "closed" for a variant without the field').toBe(true);
    expect(calls.every((c) => c.args.at(-1) === ''), 'rows "" (4 rows) until the variant carries a board size').toBe(true);
  });

  it('passes the foreign-corner rule before the board size in every call', () => {
    const { module, calls } = stub('ok ongoing');
    const core = JavaCore.create(module);
    for (const camps of ['pass-through', 'open'] as const) {
      const variant = { jump: 'adjacent', players: 3, camps } as const;
      core.newGame({ nodes: [100, 100, 100], variant });
      core.legalMoves([], variant);
      core.choose([], variant);
      core.outcome([], variant);
      core.bench(3, 100, variant);
      core.analyse([], null, 100, variant);
      core.evaluate3([], variant);
    }
    expect(calls).toHaveLength(14);
    expect(calls.slice(0, 7).every((c) => c.args.at(-2) === 'pass-through')).toBe(true);
    expect(calls.slice(7).every((c) => c.args.at(-2) === 'open')).toBe(true);
  });

  it('reads the outcome replies of a full-ranking game', () => {
    const variant = { jump: 'long', players: 3, ranking: 'full' } as const;
    const outcome = (reply: string) => JavaCore.create(stub(reply).module).outcome([], variant);
    expect(outcome('ok ongoing')).toEqual({ kind: 'ongoing' });
    expect(outcome('ok ongoing finished 1')).toEqual({ kind: 'ongoing', finished: [0] });
    expect(outcome('ok ongoing finished 4')).toEqual({ kind: 'ongoing', finished: [2] });
    expect(outcome('ok ranking 2 0 1')).toEqual({ kind: 'ranking', ranking: [2, 0, 1] });
    // the first-winner replies are as before
    expect(outcome('ok win 1')).toEqual({ kind: 'win', winner: 1 });
    expect(outcome('ok draw')).toEqual({ kind: 'draw' });
  });

  it('rejects replies that are not an outcome', () => {
    const variant = { jump: 'long', players: 3, ranking: 'full' } as const;
    const outcome = (reply: string) => (): unknown => JavaCore.create(stub(reply).module).outcome([], variant);
    for (const bad of ['ok ongoing finished 0', 'ok ongoing finished 9', 'ok ongoing finished', 'ok ranking 0 1', 'ok ranking 0 0 1', 'ok ranking 0 1 3', 'ok ranking a b c', 'ok win 3']) {
      expect(outcome(bad), bad).toThrow(EngineFailure);
    }
  });
});
