// Schema and consistency checks for the data files that ExplainerDataTest (search/src/test/java/sternhalma/search/)
// generates. The Java test verifies that the files equal what the engine produces; this test verifies what the
// figures rely on: shapes, ranges and relations that must hold between the numbers (the search tree obeys negamax,
// the MCTS tables follow from the simulations, the planes agree with the board). Files that other work packages
// transcribe from docs/ are checked where they live, not here.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = any;

function load(name: string): Json {
  return JSON.parse(readFileSync(new URL(`./${name}`, import.meta.url), 'utf8'));
}

const CELLS = 121;

function sourceOk(d: Json): void {
  expect(d.source.generator).toBe('ExplainerDataTest');
  expect(d.source.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(typeof d.source.how).toBe('string');
}

function isCell(n: unknown): boolean {
  return Number.isInteger(n) && (n as number) >= 0 && (n as number) < CELLS;
}

function ownerCounts(cells: number[]): Record<number, number> {
  const out: Record<number, number> = {};
  for (const c of cells) out[c] = (out[c] ?? 0) + 1;
  return out;
}

describe('ab-tree-start.json', () => {
  const d = load('ab-tree-start.json');

  it('has the source block and the anchors of the fact sheet', () => {
    sourceOk(d);
    expect(d.meta.depth).toBe(2);
    expect(d.meta.nodes).toBe(55);
    expect(d.meta.minimaxNodes).toBe(210);
    expect(d.meta.score).toBe(0);
    expect(d.meta.bestMove.text).toBe('(2,-6)->(2,-4)');
    expect(d.meta.rootMoves).toBe(14);
    expect(d.meta.rootJumps).toBe(6);
    expect(d.meta.iterationNodes).toEqual([14, 41]);
  });

  it('is the start position', () => {
    expect(d.cells).toHaveLength(CELLS);
    expect(ownerCounts(d.cells)).toEqual({ '-1': 101, '0': 10, '1': 10 });
    expect(d.rootMoves).toHaveLength(14);
    expect(d.rootMoves.filter((m: Json) => m.kind === 'jump')).toHaveLength(6);
    expect(d.rootMoves.filter((m: Json) => m.kind === 'step')).toHaveLength(8);
  });

  it('has 55 visits in visit order with a consistent tree', () => {
    const v: Json[] = d.visits;
    expect(v).toHaveLength(55);
    v.forEach((x, i) => {
      expect(x.id).toBe(i + 1);
      expect(x.parent).toBeGreaterThanOrEqual(0);
      expect(x.parent).toBeLessThan(x.id);
      expect(isCell(x.move.from) && isCell(x.move.to)).toBe(true);
      expect([1, 2]).toContain(x.iteration);
      expect(Number.isInteger(x.alpha) && Number.isInteger(x.beta) && Number.isInteger(x.score)).toBe(true);
      expect(Math.abs(x.alpha)).toBeLessThanOrEqual(d.meta.infinity);
      expect(Math.abs(x.beta)).toBeLessThanOrEqual(d.meta.infinity);
      expect(typeof x.cutoff).toBe('boolean');
      if (x.skipped > 0) expect(x.cutoff).toBe(true);
    });
    expect(v.filter((x) => x.iteration === 1)).toHaveLength(14);
    expect(v.filter((x) => x.iteration === 2)).toHaveLength(41);
  });

  it('obeys negamax: the score of a node is the best negated score of its searched children', () => {
    const v: Json[] = d.visits;
    const children = new Map<number, Json[]>();
    for (const x of v) children.set(x.parent, [...(children.get(x.parent) ?? []), x]);
    for (const x of v) {
      const kids = children.get(x.id) ?? [];
      if (x.depthLeft === 0) {
        expect(kids).toHaveLength(0);
        continue;
      }
      expect(kids.length + x.skipped).toBe(x.legal);
      expect(Math.abs(x.score - Math.max(...kids.map((k) => -k.score)))).toBe(0); // not toBe on the values: -0 and 0 differ
      kids.forEach((k, i) => {
        expect(k.order).toBe(i); // children appear in the order they were searched
        expect(k.depthLeft).toBe(x.depthLeft - 1);
      });
    }
    // the root of the second iteration: best move and score of meta
    const rootKids = (children.get(0) ?? []).filter((k) => k.iteration === 2);
    expect(rootKids).toHaveLength(14);
    expect(Math.abs(Math.max(...rootKids.map((k) => -k.score)) - d.meta.score)).toBe(0);
    const best = rootKids.find((k) => -k.score === d.meta.score)!;
    expect(best.move).toEqual({ from: d.meta.bestMove.from, to: d.meta.bestMove.to });
  });
});

describe('ab-budget-mid.json', () => {
  const d = load('ab-budget-mid.json');

  it('lists the five UI levels with the completed depths of the fact sheet', () => {
    sourceOk(d);
    expect(d.meta.levels).toEqual([1000, 5000, 20000, 100000, 500000]);
    expect(d.browser.map((e: Json) => e.nodes)).toEqual(d.meta.levels);
    expect(d.plain.map((e: Json) => e.nodes)).toEqual(d.meta.levels);
    expect(d.browser.map((e: Json) => e.depth)).toEqual([3, 4, 6, 8, 10]);
    expect(d.plain[2].depth).toBe(3);
    expect(d.plain[3].depth).toBe(4);
    expect(d.cells).toHaveLength(CELLS);
  });

  it('has moves that are cells and depths that never fall with the budget', () => {
    for (const series of [d.browser, d.plain]) {
      series.forEach((e: Json, i: number) => {
        expect(isCell(e.move.from) && isCell(e.move.to)).toBe(true);
        expect(e.move.text).toMatch(/^\(-?\d+,-?\d+\)->\(-?\d+,-?\d+\)$/);
        if (i > 0) expect(e.depth).toBeGreaterThanOrEqual(series[i - 1].depth);
      });
    }
  });
});

describe('eval-mid.json', () => {
  const d = load('eval-mid.json');

  it('has the anchors of the fact sheet', () => {
    sourceOk(d);
    expect(d.meta.ply).toBe(30);
    expect(d.meta.legalMoves).toBe(54);
    expect(d.meta.score).toBe(179);
    expect(d.meta.scale).toBe(100);
    expect(d.history).toHaveLength(30);
    expect(d.cells).toHaveLength(CELLS);
    expect(ownerCounts(d.cells)).toEqual({ '-1': 101, '0': 10, '1': 10 });
  });

  it('adds up: weight x value x scale per feature gives the score; the probability is the sigmoid', () => {
    expect(d.features.map((f: Json) => f.name)).toEqual([
      'dsum', 'dmax', 'dsq', 'inGoal', 'inStart', 'spread', 'lateral', 'jumps', 'adjacent', 'steps', 'dsumPhase', 'tempo',
      'bestGain',
    ]);
    let total = 0;
    for (const f of d.features) {
      expect(f.contribution).toBeCloseTo(d.meta.scale * f.weight * f.value, 2);
      total += f.contribution;
    }
    expect(Math.round(total)).toBe(d.meta.score);
    expect(d.meta.winProbability).toBeCloseTo(1 / (1 + Math.exp(-d.meta.score / d.meta.scale)), 3);
  });

  it('has marks whose counts equal the sums, and sums whose difference is the feature value', () => {
    const [mover, opponent] = d.sides;
    expect(mover.role).toBe('mover');
    expect(opponent.role).toBe('opponent');
    expect(mover.player).toBe(d.meta.sideToMove);
    for (const s of d.sides) {
      expect(s.pieces).toHaveLength(10);
      expect(s.marks.inGoal).toHaveLength(s.sums.inGoal);
      expect(s.marks.inStart).toHaveLength(s.sums.inStart);
      expect(s.marks.adjacent).toHaveLength(s.sums.adjacent);
      expect(s.marks.jumps).toHaveLength(s.sums.jumps);
      expect(s.marks.steps).toHaveLength(s.sums.steps);
      expect(s.pieces.reduce((a: number, p: Json) => a + p.distance, 0)).toBe(s.sums.dsum);
      for (const p of s.pieces) expect(d.cells[p.cell]).toBe(s.player);
    }
    const byName = (n: string) => d.features.find((f: Json) => f.name === n).value;
    for (const n of ['dsum', 'dmax', 'inGoal', 'inStart', 'lateral', 'jumps', 'adjacent', 'steps']) {
      expect(byName(n)).toBe(mover.sums[n] - opponent.sums[n]);
    }
    expect(byName('dsq')).toBeCloseTo((mover.sums.dsq - opponent.sums.dsq) / 10, 6);
    expect(byName('spread')).toBe(mover.sums.dmax - mover.sums.dmin - (opponent.sums.dmax - opponent.sums.dmin));
  });
});

describe('net-shape.json', () => {
  const d = load('net-shape.json');

  it('has the parameter sums of models.json', () => {
    sourceOk(d);
    const byId = Object.fromEntries(d.models.map((m: Json) => [m.id, m]));
    expect(byId['small-2p-adjacent-expert'].params).toBe(61734);
    expect(byId['small-shared-all'].params).toBe(62215);
    expect(byId['small-2p-adjacent-expert'].inputPlanes).toBe(13);
    expect(byId['small-shared-all'].inputPlanes).toBe(15);
    expect(byId['small-shared-all'].players).toBe(3);
    for (const m of d.models) {
      expect(m.tensors.reduce((a: number, t: Json) => a + t.params, 0)).toBe(m.params);
      expect(m.layers.reduce((a: number, l: Json) => a + l.params, 0)).toBe(m.params);
      for (const t of m.tensors) expect(t.shape.reduce((a: number, b: number) => a * b, 1)).toBe(t.params);
      expect(m.layers.map((l: Json) => l.id)).toEqual([
        'conv_in', 'block0', 'block1', 'block2', 'block3', 'head_policy', 'head_value', 'priors',
      ]);
      expect(m.sha256Raw).toMatch(/^[0-9a-f]{64}$/);
    }
  });

  it('has the gather table: 121 x 7, symmetric neighbours, padding row 121', () => {
    expect(d.gather).toHaveLength(CELLS);
    const counts: Record<number, number> = {};
    d.gather.forEach((row: number[], c: number) => {
      expect(row).toHaveLength(7);
      expect(row[0]).toBe(c);
      const neighbours = row.slice(1).filter((g) => g !== d.meta.paddingRow);
      counts[neighbours.length] = (counts[neighbours.length] ?? 0) + 1;
      for (const g of neighbours) expect(d.gather[g].slice(1)).toContain(c);
    });
    expect(counts).toEqual({ 6: 73, 5: 6, 4: 36, 2: 6 });
    expect(d.meta.neighbourCounts).toEqual({ '2': 6, '4': 36, '5': 6, '6': 73 });
  });
});

describe.each(['start', 'mid', 'race'])('forward-%s.json', (name) => {
  const d = load(`forward-${name}.json`);

  it('has the shapes the figure reads', () => {
    sourceOk(d);
    expect(d.meta.position).toBe(name);
    expect(d.meta.model).toBe('small-2p-adjacent-expert');
    expect(d.meta.planeNames).toHaveLength(13);
    expect(d.cells).toHaveLength(CELLS);
    expect(d.planes).toHaveLength(13);
    for (const p of d.planes) expect(p).toHaveLength(CELLS);
    expect(d.layers.map((l: Json) => l.id)).toEqual(['conv_in', 'block0', 'block1', 'block2', 'block3']);
    for (const l of d.layers) {
      expect(l.norm).toHaveLength(CELLS);
      for (const n of l.norm) expect(n).toBeGreaterThanOrEqual(0);
    }
    expect(d.policyLogits.from).toHaveLength(CELLS);
    expect(d.policyLogits.to).toHaveLength(CELLS);
  });

  it('has priors that sum to 1 and values in [-1, 1]', () => {
    expect(d.policy).toHaveLength(d.meta.legalMoves);
    for (const key of ['prior', 'priorNetOnly']) {
      const sum = d.policy.reduce((a: number, m: Json) => a + m[key], 0);
      expect(Math.abs(sum - 1)).toBeLessThan(5e-3); // 4 decimals per entry
    }
    for (const m of d.policy) expect(isCell(m.from) && isCell(m.to)).toBe(true);
    for (const key of ['values', 'valuesNetOnly']) {
      expect(d[key]).toHaveLength(2);
      for (const v of d[key]) {
        expect(v).toBeGreaterThanOrEqual(-1);
        expect(v).toBeLessThanOrEqual(1);
      }
    }
  });

  it('shows the board the planes are made from: own and next planes follow the owners', () => {
    const mover = d.meta.sideToMove;
    for (let c = 0; c < CELLS; c++) {
      expect(d.planes[0][c]).toBe(d.cells[c] === mover ? 1 : 0);
      expect(d.planes[1][c]).toBe(d.cells[c] === 1 - mover ? 1 : 0);
      expect(d.planes[2][c]).toBe(0); // no third player
    }
    expect(d.meta.canonicalRotated).toBe(mover === 1);
  });
});

describe('forward-race.json', () => {
  it('is a passed position (plane 11) with player 0 to move', () => {
    const d = load('forward-race.json');
    expect(d.meta.sideToMove).toBe(0);
    expect(d.planes[11].every((v: number) => v === 1)).toBe(true);
  });
});

describe('mcts-mid.json', () => {
  const d = load('mcts-mid.json');

  it('has the browser settings and 50 simulations', () => {
    sourceOk(d);
    expect(d.meta.sims).toBe(50);
    expect(d.meta.cpuct).toBe(1);
    expect(d.meta.batch).toBe(1);
    expect(d.meta.noise).toBe(0);
    expect(d.meta.temperature).toBe(0);
    expect(d.sims).toHaveLength(50);
    d.sims.forEach((s: Json, i: number) => {
      expect(s.k).toBe(i + 1);
      expect(s.nodes).toHaveLength(s.path.length);
      expect(s.nodes[0]).toBe(0);
      expect(['net', 'terminal']).toContain(s.leaf.kind);
      expect(s.values).toHaveLength(2);
      if (s.leaf.new && s.leaf.kind === 'net') {
        expect(s.expanded.moves).toHaveLength(s.expanded.priors.length);
        const sum = s.expanded.priors.reduce((a: number, b: number) => a + b, 0);
        expect(Math.abs(sum - 1)).toBeLessThan(5e-3);
      }
    });
  });

  it('has a final table that follows from the simulations (N and Q of the root edges)', () => {
    const n = d.root.moves.length;
    const visits: Json = new Array<number>(n).fill(0);
    const sums: Json = new Array<number>(n).fill(0);
    for (const s of d.sims) {
      visits[s.path[0]] += 1;
      sums[s.path[0]] += s.values[d.meta.rootMover];
    }
    expect(d.final).toHaveLength(n);
    d.final.forEach((row: Json, i: number) => {
      expect(row.N).toBe(visits[i]);
      expect(row.P).toBeCloseTo(d.root.priors[i], 4);
      expect(row.move).toEqual(d.root.moves[i]);
      if (visits[i] === 0) expect(row.Q).toBeNull();
      else expect(row.Q).toBeCloseTo(sums[i] / visits[i], 3);
    });
    expect(visits.reduce((a: number, b: number) => a + b, 0)).toBe(50);
    expect(Math.max(...visits)).toBe(visits[d.meta.choiceEdge]);
    expect(d.root.moves[d.meta.choiceEdge]).toEqual(d.meta.choice);
  });

  it('takes at every simulation the root edge with the highest PUCT score (the formula of the figure)', () => {
    const n = d.root.moves.length;
    const visits: Json = new Array<number>(n).fill(0);
    const sums: Json = new Array<number>(n).fill(0);
    const rootValue: number = d.root.values[d.meta.rootMover];
    let total = 0;
    let totalSum = 0;
    for (const s of d.sims) {
      const mean = (rootValue + totalSum) / (1 + total);
      const scores = visits.map((v: number, i: number) => {
        const q = v === 0 ? mean - d.meta.fpu : sums[i] / v;
        return q + d.meta.cpuct * d.root.priors[i] * Math.sqrt(Math.max(1, total)) / (1 + v);
      });
      const best = Math.max(...scores);
      // the stored numbers are rounded to 4 decimals, so a near tie may fall the other way
      expect(scores[s.path[0]]).toBeGreaterThan(best - 2e-3);
      visits[s.path[0]] += 1;
      sums[s.path[0]] += s.values[d.meta.rootMover];
      total += 1;
      totalSum += s.values[d.meta.rootMover];
    }
  });
});
