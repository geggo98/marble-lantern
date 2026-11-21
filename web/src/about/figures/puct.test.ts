// V10: the replay of mcts-mid.json. The PUCT column is checked against the stored final table (N, Q, P) for three values of c, against a
// hand calculation, and against the recorded selection of every simulation.
import { describe, expect, it } from 'vitest';
import data from '../data/mcts-mid.json';
import { SUBSTEPS, argmax, childOf, columnCells, edgeQ, links, movesOf, puctColumn, rootState, visibleSims } from './puct';
import type { McstData } from './puct';

const D = data as unknown as McstData;
const N = D.sims.length;

describe('the PUCT column equals the formula on the stored N, Q, P', () => {
  // The mean of the root node after all simulations, from the stored values (a value rounded to 4 decimals each).
  const mean = (D.root.values[0] as number) / (1 + N) + D.sims.reduce((a, s) => a + (s.values[0] as number), 0) / (1 + N);

  it.each([0.5, 1, 2.5])('c = %f after the 50th simulation', (c) => {
    const column = puctColumn(D, rootState(D, N), c);
    expect(column).toHaveLength(D.final.length);
    D.final.forEach((row, i) => {
      const q = row.Q ?? mean; // an unvisited edge takes the node mean (fpu 0)
      expect(column[i], `edge ${i} ${JSON.stringify(row.move)}`).toBeCloseTo(q + (c * row.P * Math.sqrt(N)) / (1 + row.N), 3);
    });
  });

  it('hand calculation for the most visited edge, move (2, 5): 0.9693 + 0.594 * sqrt(50) / 38 = 1.0798 at c = 1', () => {
    const edge = D.meta.choiceEdge;
    expect(D.final[edge]).toMatchObject({ P: 0.594, N: 37, Q: 0.9693 });
    expect(puctColumn(D, rootState(D, N), 1)[edge]).toBeCloseTo(0.9693 + (0.594 * Math.sqrt(50)) / 38, 3);
    expect(puctColumn(D, rootState(D, N), 1)[edge]).toBeCloseTo(1.0798, 3);
    // c = 4 quadruples the exploration term: 0.9693 + 4 * 0.110532 = 1.4115
    expect(puctColumn(D, rootState(D, N), 4)[edge]).toBeCloseTo(1.4115, 3);
  });

  it('at step 0 there are no visits and the score is Q = value of the root plus c * P * 1 (sqrt of max(1, 0))', () => {
    const column = puctColumn(D, rootState(D, 0), 2);
    D.root.priors.forEach((p, i) => expect(column[i]).toBeCloseTo((D.root.values[0] as number) + 2 * p, 6));
  });
});

describe('the selection of every simulation is the highest PUCT score of the state before it (c = 1)', () => {
  it('the taken edge is the argmax, or within 1e-3 of it: the stored values have 4 decimals, so one near tie (simulation 47, 2e-5 apart) falls the other way', () => {
    let exact = 0;
    D.sims.forEach((s, i) => {
      const column = puctColumn(D, rootState(D, i), D.meta.cpuct);
      const best = Math.max(...column);
      expect(column[s.path[0] as number] as number, `simulation ${s.k}`).toBeGreaterThan(best - 1e-3);
      if (argmax(column) === s.path[0]) exact++;
    });
    expect(exact).toBe(49);
  });
  it('a larger c prefers an edge with a high prior and few visits (the choice at c = 4 differs from c = 0.25 after 50 simulations)', () => {
    const st = rootState(D, N);
    expect(argmax(puctColumn(D, st, 0.25))).toBe(D.meta.choiceEdge);
    expect(argmax(puctColumn(D, st, 4))).not.toBe(argmax(puctColumn(D, st, 0.25)));
  });
  it('argmax takes the first of equal scores', () => {
    expect(argmax([1, 3, 3, 2])).toBe(1);
    expect(argmax([5])).toBe(0);
  });
});

describe('rootState', () => {
  it('counts the visits and reaches the stored final table at k = 50', () => {
    const st = rootState(D, N);
    D.final.forEach((row, i) => {
      expect(st.visits[i]).toBe(row.N);
      if (row.N > 0) expect(edgeQ(D, st, i)).toBeCloseTo(row.Q as number, 3);
    });
    expect(st.visits.reduce((a, b) => a + b, 0)).toBe(50);
  });
  it('folds only the first k simulations', () => {
    expect(rootState(D, 7).visits.reduce((a, b) => a + b, 0)).toBe(7);
    expect(rootState(D, 0).visits.every((v) => v === 0)).toBe(true);
  });
});

describe('visibleSims: the backup of simulation k is the last one to count', () => {
  it.each([
    [0, 'backup', 0],
    [0, 'select', 0],
    [5, 'select', 4],
    [5, 'expand', 4],
    [5, 'evaluate', 4],
    [5, 'backup', 5],
  ] as const)('k = %i, %s shows %i simulations', (k, sub, expected) => expect(visibleSims(k, sub)).toBe(expected));
  it('has four sub-steps in the order of the narration', () => expect(SUBSTEPS).toEqual(['select', 'expand', 'evaluate', 'backup']));
});

describe('the tree', () => {
  it('node k is created by simulation k and its moves are the ones expanded then', () => {
    expect(movesOf(D, 0)).toBe(D.root.moves);
    expect(movesOf(D, 3)).toEqual(D.sims[2]?.expanded.moves);
  });
  it('links: the first simulation links the root edge of its path to node 1; the 2nd goes on from node 1', () => {
    const l = links(D, 2);
    expect(l[0]).toEqual({ node: 0, edge: 1, child: 1, sim: 1 });
    expect(l[1]).toEqual({ node: 1, edge: 46, child: 2, sim: 2 });
    expect(childOf(D, 2, 0, 1)).toBe(1);
    expect(childOf(D, 1, 1, 46)).toBeUndefined();
  });
  it('every simulation adds exactly one new node (a leaf, 50 nodes after 50 simulations)', () => {
    const children = new Set(links(D, N).map((l) => l.child));
    expect(children.size).toBe(50);
  });

  const COL = D.meta.choiceEdge; // edge 1, visited by the first simulation
  it('column cells: nothing before the first simulation reaches the column', () => {
    expect(columnCells(D, 1, 'select', COL, 8)).toEqual([]);
  });
  it('the new leaf is active from the expand step on; in the select step it does not exist yet', () => {
    // simulation 2 walks root -> edge 1 -> edge 46 and creates node 2 at depth 2 below the column
    const select = columnCells(D, 2, 'select', COL, 8);
    const expand = columnCells(D, 2, 'expand', COL, 8);
    expect(select.some((c) => c.state === 'active')).toBe(false);
    expect(expand.filter((c) => c.state === 'active').map((c) => c.node)).toEqual([2]);
    expect(expand.length).toBe(select.length + 1);
  });
  it('keeps the marked cells when the cap cuts the list: simulation 50 walks 3 edges; its leaf and the path node stay in a column capped at 2', () => {
    const last = D.sims[49] as McstData['sims'][number];
    const cells = columnCells(D, 50, 'backup', last.path[0] as number, 2);
    expect(cells).toHaveLength(2);
    expect(cells).toContainEqual({ node: last.leaf.node, state: 'active' });
    expect(cells).toContainEqual({ node: last.nodes[2], state: 'path' });
  });
});
