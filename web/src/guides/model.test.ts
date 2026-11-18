import { describe, expect, it } from 'vitest';
import { makeMove } from '../engines/engine';
import type { AnalysisResult } from '../engines/engine';
import { buildView } from './model';

const result = (pairs: Array<[number, number]>): AnalysisResult => ({
  moves: pairs.map(([m]) => m),
  scores: pairs.map(([, s]) => s),
  position: Math.max(...pairs.map(([, s]) => s)),
  nodes: 0,
  ms: 0,
});

describe('buildView', () => {
  const a = makeMove(10, 20);
  const b = makeMove(10, 21);
  const c = makeMove(11, 22);
  const d = makeMove(11, 23);
  const view = buildView(result([[a, 100], [b, 60], [c, -200], [d, -100]]));

  it('takes the best score as the value of the position', () => {
    expect(view.position).toBe(100);
  });
  it('bands every move against the value of the position', () => {
    expect(view.moves.get(a)).toBe('equal');
    expect(view.moves.get(b)).toBe('worse'); // 40 below
    expect(view.moves.get(d)).toBe('much-worse'); // 200 below
    expect(view.moves.get(c)).toBe('much-worse');
    expect(view.moves.size).toBe(4);
  });
  it('bands a piece by its best move', () => {
    expect(view.pieces.get(10)).toBe('much-better');
    expect(view.pieces.get(11)).toBe('worse'); // best -100, 200 below the best of the position
    expect(view.pieces.size).toBe(2);
  });
  it('keeps the scores by move', () => {
    expect(view.scores.get(b)).toBe(60);
  });
  it('compares moves against another reference when one is given, so that moves can be better than it', () => {
    const other = buildView(result([[a, 100], [b, 60]]), 0);
    expect(other.moves.get(a)).toBe('much-better');
    expect(other.moves.get(b)).toBe('better');
    expect(other.pieces.get(10)).toBe('much-better'); // pieces still compare against the best move
  });
  it('rejects a result whose lists differ in length', () => {
    expect(() => buildView({ ...result([[a, 1]]), scores: [] })).toThrow(RangeError);
  });
  it('handles an empty result', () => {
    expect(buildView({ moves: [], scores: [], position: 0, nodes: 0, ms: 0 }).moves.size).toBe(0);
  });
});
