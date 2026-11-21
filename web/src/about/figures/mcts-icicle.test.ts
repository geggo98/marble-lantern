// The icicle layout of Fig. 3.3. Expected numbers come from counting the simulations of mcts-mid.json by hand (root move 1: 37 visits,
// root move 3: 6, root move 12: 5, root move 16: 2; 50 simulations; the deepest path has 9 moves), not from the layout code.
import { describe, expect, it } from 'vitest';
import data from '../data/mcts-mid.json';
import { ICICLE_MIN_WIDTH, icicleLayout, nodeVisits, shareWidths } from './mcts-icicle';
import type { IcicleBlock } from './mcts-icicle';
import type { McstData } from './puct';

const D = data as unknown as McstData;
const W = 640;

const depth1 = (blocks: readonly IcicleBlock[]): IcicleBlock[] => blocks.filter((b) => b.depth === 1);
const sum = (xs: readonly number[]): number => xs.reduce((a, b) => a + b, 0);

describe('shareWidths', () => {
  it('splits in proportion and keeps the total', () => {
    expect(shareWidths([1, 3], 100, 4)).toEqual([25, 75]);
  });
  it('raises a share below the minimum and lets the others give way, so the total stays', () => {
    const w = shareWidths([1, 99], 100, 4);
    expect(w[0]).toBe(4);
    expect(w[1]).toBeCloseTo(96, 9);
  });
  it('gives a weight of 0 the minimum and shares everything else; all zero shares equally', () => {
    expect(shareWidths([0, 10], 50, 4)).toEqual([4, 46]);
    expect(shareWidths([0, 0], 50, 4)).toEqual([25, 25]);
  });
});

describe('icicleLayout', () => {
  it('before the first simulation the tree is the root and one block for every move: the whole width', () => {
    const l = icicleLayout(D, 0, 'select', W, Infinity);
    expect(l.visits).toBe(0);
    expect(l.blocks).toHaveLength(1);
    expect(l.blocks[0]).toMatchObject({ key: 'rest', depth: 1, x: 0, w: W, state: 'todo', edge: -1 });
  });

  it('after 50 simulations four root moves are blocks, wide in proportion to their visits 37, 6, 5 and 2', () => {
    const l = icicleLayout(D, 50, 'backup', W, Infinity);
    expect(l.visits).toBe(50);
    const columns = depth1(l.blocks).filter((b) => b.edge >= 0);
    expect(Object.fromEntries(columns.map((b) => [b.edge, b.visits]))).toEqual({ 1: 37, 3: 6, 12: 5, 16: 2 });
    const byEdge = Object.fromEntries(columns.map((b) => [b.edge, b.w]));
    expect((byEdge[1] as number) / (byEdge[3] as number)).toBeCloseTo(37 / 6, 6);
    expect((byEdge[12] as number) / (byEdge[16] as number)).toBeCloseTo(5 / 2, 6);
    expect(columns.map((b) => b.edge)).toEqual([...columns.map((b) => b.edge)].sort((a, b) => (D.root.priors[b] as number) - (D.root.priors[a] as number) || a - b));
  });

  it('the blocks of a row fill the width without gaps or overlap, and a reply block lies inside its parent', () => {
    const l = icicleLayout(D, 50, 'backup', W, Infinity);
    const top = depth1(l.blocks);
    expect(sum(top.map((b) => b.w))).toBeCloseTo(W, 6);
    let at = 0;
    for (const b of top) {
      expect(b.x).toBeCloseTo(at, 6);
      at += b.w;
    }
    for (const b of l.blocks.filter((x) => x.depth >= 2)) {
      const parent = l.blocks.find((p) => p.depth === b.depth - 1 && p.x <= b.x + 1e-6 && p.x + p.w >= b.x + b.w - 1e-6);
      expect(parent, `block ${b.key} at depth ${b.depth} has no parent that contains it`).toBeDefined();
    }
  });

  it('the deepest row is the deepest path of the whole run (9), from the first state on', () => {
    expect(icicleLayout(D, 0, 'select', W, Infinity).maxDepth).toBe(9);
    expect(icicleLayout(D, 12, 'select', W, Infinity).maxDepth).toBe(9);
  });

  it('a move with one visit stays visible: no root block is narrower than the minimum', () => {
    for (const width of [240, 260, 640]) {
      for (const b of depth1(icicleLayout(D, 50, 'backup', width, Infinity).blocks)) expect(b.w).toBeGreaterThanOrEqual(ICICLE_MIN_WIDTH - 1e-9);
    }
  });

  it('marks the path of the simulation and, from the expand sub-step on, the new leaf (simulation 2: path of 1 node below the root move, leaf new)', () => {
    const sim = D.sims[1]!;
    const pathNodes = sim.nodes.slice(1);
    const select = icicleLayout(D, 2, 'select', W, Infinity).blocks;
    expect(select.filter((b) => b.state === 'active')).toHaveLength(0);
    expect(select.filter((b) => b.state === 'path')).toHaveLength(pathNodes.length);
    const expand = icicleLayout(D, 2, 'expand', W, Infinity).blocks;
    expect(expand.filter((b) => b.state === 'active')).toHaveLength(1);
    expect(expand.filter((b) => b.state === 'path')).toHaveLength(pathNodes.length);
  });

  it('with a limit of columns the least visited moves join the block of the rest, whose visits are counted', () => {
    const l = icicleLayout(D, 50, 'backup', W, 2);
    const columns = depth1(l.blocks).filter((b) => b.edge >= 0);
    expect(columns.map((b) => b.edge).sort((a, b) => a - b)).toEqual([1, 3]);
    const rest = l.blocks.find((b) => b.key === 'rest')!;
    expect(rest.visits).toBe(5 + 2);
    expect(rest.state).toBe('visited');
    expect(sum(depth1(l.blocks).map((b) => b.w))).toBeCloseTo(W, 6);
  });

  it('counts the visits of a node as the simulations that pass it or create it', () => {
    const counts = nodeVisits(D, 50);
    const first = D.sims[0]!;
    expect(counts.get(first.leaf.node)).toBeGreaterThanOrEqual(1);
    // every simulation but those that end in a node of depth 1 passes the root move's node; the total over depth-1 nodes is 50
    const rootChildren = new Set(D.sims.map((s) => (s.path.length === 1 ? s.leaf.node : (s.nodes[1] as number))));
    expect(sum([...rootChildren].map((n) => counts.get(n) ?? 0))).toBe(50);
  });
});
