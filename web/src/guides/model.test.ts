import { describe, expect, it } from 'vitest';
import { makeMove } from '../engines/engine';
import type { AnalysisResult } from '../engines/engine';
import { BAND_ORDER, bandRank } from './bands';
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
  it('bands every move by its loss against the best move of the position', () => {
    expect(view.moves.get(a)).toBe('much-better'); // the best move is top
    expect(view.moves.get(b)).toBe('better'); // 40 below
    expect(view.moves.get(d)).toBe('worse'); // 200 below
    expect(view.moves.get(c)).toBe('worse'); // 300 below
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
  it('rejects a result whose lists differ in length', () => {
    expect(() => buildView({ ...result([[a, 1]]), scores: [] })).toThrow(RangeError);
  });
  it('handles an empty result', () => {
    expect(buildView({ moves: [], scores: [], position: 0, nodes: 0, ms: 0 }).moves.size).toBe(0);
  });
});

// Owner report 2026-11-19: on the start position ten pieces carried the top band, but a selected piece offered only targets
// marked "about equal". Pieces and targets must use one scale: loss against the best move of the position.
describe('one scale for pieces and targets (start position shape)', () => {
  // ten pieces, each with a few moves that all tie with the best score (as on the opening position), plus weaker ones
  const pairs: Array<[number, number]> = [];
  for (let piece = 0; piece < 10; piece++) {
    pairs.push([makeMove(piece, 20 + piece), 55], [makeMove(piece, 40 + piece), 55], [makeMove(piece, 60 + piece), 10]);
  }
  const view = buildView(result(pairs));

  it('gives the best move of the position the top band, as a target', () => {
    expect(view.moves.get(makeMove(0, 20))).toBe('much-better');
  });
  it('gives every piece the best band among its own targets', () => {
    for (const piece of view.pieces.keys()) {
      const own = [...view.moves].filter(([m]) => (m & 0xff) === piece).map(([, band]) => bandRank(band));
      expect(view.pieces.get(piece), `piece ${piece}`).toBe(BAND_ORDER[Math.min(...own)]);
    }
  });
});
