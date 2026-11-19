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

// Three players: Engine.analyse returns paranoid scores from the point of view of the mover, in the same units as for two players
// (analysis.ts), so the same view applies to whichever seat is to move. The numbers are made up in that scale: one step of
// progress is about 30 points, a mate is 999,999 or -999,998.
describe('three-player analysis results (paranoid scores of the seat to move)', () => {
  // the mover trails both others: mostly negative scores, and one piece whose moves allow a forced loss
  const pairs: Array<[number, number]> = [];
  [-20, -50, -95, -140, -410].forEach((score, i) => pairs.push([makeMove(1, 30 + i), score]));
  [-25, -80, -300].forEach((score, i) => pairs.push([makeMove(2, 40 + i), score]));
  pairs.push([makeMove(3, 50), -999_998], [makeMove(3, 51), -999_994]);
  const view = buildView(result(pairs));

  it('takes the best score as the value of the position, also when every score is negative', () => {
    expect(view.position).toBe(-20);
  });
  it('bands against the best move of the position, as for two players', () => {
    expect(view.moves.get(makeMove(1, 30))).toBe('much-better'); // the best move is top
    expect(view.moves.get(makeMove(2, 40))).toBe('much-better'); // 5 below: within the search noise
    expect(view.moves.get(makeMove(1, 31))).toBe('much-better'); // 30 below: still within one step
    expect(view.moves.get(makeMove(2, 41))).toBe('better'); // 60 below
    expect(view.moves.get(makeMove(1, 32))).toBe('better'); // 75 below
    expect(view.moves.get(makeMove(1, 33))).toBe('equal'); // 120 below
    expect(view.moves.get(makeMove(2, 42))).toBe('worse'); // 280 below
    expect(view.moves.get(makeMove(1, 34))).toBe('much-worse'); // 390 below
  });
  it('rates a move into a forced loss as poor, whatever the number of plies', () => {
    expect(view.moves.get(makeMove(3, 50))).toBe('much-worse');
    expect(view.moves.get(makeMove(3, 51))).toBe('much-worse');
  });
  it('keeps the invariants: a piece is as good as its best target, and the best move of the position is top', () => {
    for (const piece of view.pieces.keys()) {
      const own = [...view.moves].filter(([m]) => (m & 0xff) === piece).map(([, band]) => bandRank(band));
      expect(view.pieces.get(piece), `piece ${piece}`).toBe(BAND_ORDER[Math.min(...own)]);
    }
    expect(Math.min(...[...view.moves.values()].map(bandRank))).toBe(0);
    expect(view.pieces.get(1)).toBe('much-better');
    expect(view.pieces.get(3)).toBe('much-worse');
  });
});
