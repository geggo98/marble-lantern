// The pure models of the figures of topics 1 and 2 against their data files and catalogs. Expected values come from the fact sheets and
// from hand calculation (see the comments), not from the output of the code under test.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import architectureEn from '../i18n/en/architecture.json';
import offline from '../data/arch-offline.json';
import online from '../data/arch-online.json';
import pipeline from '../data/arch-training.json';
import treeJson from '../data/source-tree.json';
import evalJson from '../data/eval-mid.json';
import budgetJson from '../data/ab-budget-mid.json';
import { OFFLINE_CANCEL_STEPS, STEPS, arrange, edgeKey, groupOf, resolveStep, semaphoreState } from './arch-model';
import type { ArchKind, RawArch } from './arch-model';
import { moveText } from './ab-model';
import { GRAPH, WHERE, graphLayout, matchesFilter, moduleDeps, ranks, visibleEntries } from './tree-model';
import type { TreeData } from './tree-model';
import { barShare, contributionOf, overlayOf, scoreOf, winProbability } from './eval-model';
import type { EvalData, Setting } from './eval-model';

const RAW: Record<ArchKind, RawArch> = { offline: offline as RawArch, online: online as RawArch, pipeline: pipeline as RawArch };
const repoRoot = fileURLToPath(new URL('../../../../', import.meta.url));
const en = architectureEn as Record<string, string>;

describe('arch-model: steps against the data files and the catalog', () => {
  it.each(['offline', 'online', 'pipeline'] as const)('%s: every box and edge of every step exists in the data file', (kind) => {
    const boxes = new Set(RAW[kind].boxes.map((b) => b.id));
    const edges = new Set(RAW[kind].edges.map(edgeKey));
    const steps = kind === 'offline' ? [...STEPS.offline, ...OFFLINE_CANCEL_STEPS] : STEPS[kind];
    for (const s of steps) {
      expect(s.nodes.filter((id) => !boxes.has(id))).toEqual([]);
      expect(s.edges.filter((k) => !edges.has(k))).toEqual([]);
    }
  });

  it('has as many steps as the catalog has step texts (V1: 6 and 3, V2: 6, V3: 5)', () => {
    const count = (prefix: string): number => Object.keys(en).filter((k) => k.startsWith(prefix) && /\.\d+$/.test(k)).length;
    expect(STEPS.offline).toHaveLength(count('fig.offline.step.'));
    expect(OFFLINE_CANCEL_STEPS).toHaveLength(count('fig.offline.cancel.step.'));
    expect(STEPS.online).toHaveLength(count('fig.online.step.'));
    expect(STEPS.pipeline).toHaveLength(count('fig.pipeline.step.'));
    expect([STEPS.offline.length, OFFLINE_CANCEL_STEPS.length, STEPS.online.length, STEPS.pipeline.length]).toEqual([6, 3, 6, 5]);
  });

  it.each(['offline', 'online', 'pipeline'] as const)('%s: every box and edge has its catalog texts, every region its label', (kind) => {
    const { data } = arrange(kind, RAW[kind], 'cpu');
    const gpu = arrange(kind, RAW[kind], 'gpu').data;
    for (const d of [data, gpu]) {
      for (const b of d.boxes) {
        expect(en[`fig.${kind}.node.${b.id}.label`], b.id).toBeTypeOf('string');
        expect(en[`fig.${kind}.node.${b.id}.detail`], b.id).toBeTypeOf('string');
      }
      for (const e of d.edges) expect(en[`fig.${kind}.edge.${edgeKey(e)}`], edgeKey(e)).toBeTypeOf('string');
      for (const g of new Set(d.boxes.map((b) => b.runsIn))) expect(en[`fig.${kind}.group.${g}`], String(g)).toBeTypeOf('string');
    }
  });

  it('puts the 15 boxes of the offline file into the four regions of the catalog', () => {
    const regions = new Map<string, string[]>();
    for (const b of offline.boxes) regions.set(groupOf('offline', b)!, [...(regions.get(groupOf('offline', b)!) ?? []), b.id]);
    expect([...regions.keys()].sort()).toEqual(['host', 'main', 'sw', 'workers']);
    expect(regions.get('workers')).toEqual(['javaEngine', 'cppEngine', 'netMcts', 'netCpu', 'netGpu']);
    expect(regions.get('host')).toEqual(['raceTable']);
  });

  it('leaves the cloud box (no arrow, no region) out of the pipeline and keeps only the trainer of the chosen device', () => {
    const cpu = arrange('pipeline', RAW.pipeline, 'cpu').data;
    const gpu = arrange('pipeline', RAW.pipeline, 'gpu').data;
    expect(cpu.boxes.map((b) => b.id)).not.toContain('cloud');
    expect(cpu.boxes.map((b) => b.id)).toContain('trainerCpu');
    expect(cpu.boxes.map((b) => b.id)).not.toContain('trainerGpu');
    expect(gpu.boxes.map((b) => b.id)).toContain('trainerGpu');
    expect(gpu.boxes.map((b) => b.id)).not.toContain('trainerCpu');
    expect(cpu.edges.map(edgeKey)).not.toContain('replayBuffer-trainerGpu');
    expect(cpu.edges).toHaveLength(pipeline.edges.length - 2);
  });

  it('resolves a step to the indices of its edges in the arranged data, and skips the trainer that is not shown', () => {
    const { data } = arrange('pipeline', RAW.pipeline, 'gpu');
    const r = resolveStep(STEPS.pipeline[2]!, data);
    expect(r.boxes).toEqual(['sampleFiles', 'replayBuffer', 'trainerGpu', 'checkpoint']);
    expect(r.edges.map((i) => edgeKey(data.edges[i]!))).toEqual(['sampleFiles-replayBuffer', 'replayBuffer-trainerGpu', 'trainerGpu-checkpoint']);
  });

  it('shows the request path of step 2 of V1: controller, session, worker client', () => {
    const { data } = arrange('offline', RAW.offline);
    const r = resolveStep(STEPS.offline[1]!, data);
    expect(r.boxes).toEqual(['controller', 'session', 'workerClient']);
    expect(r.edges.map((i) => edgeKey(data.edges[i]!))).toEqual(['controller-session', 'session-workerClient']);
  });

  it('counts running and waiting requests at p permits (V2)', () => {
    expect(semaphoreState(1, 4)).toEqual({ running: 1, waiting: 0 });
    expect(semaphoreState(4, 4)).toEqual({ running: 4, waiting: 0 });
    expect(semaphoreState(5, 4)).toEqual({ running: 4, waiting: 1 });
    expect(semaphoreState(8, 4)).toEqual({ running: 4, waiting: 4 });
    expect(semaphoreState(0, 4)).toEqual({ running: 0, waiting: 0 });
  });
});

const tree = treeJson as unknown as TreeData;

describe('tree-model and source-tree.json (V4)', () => {
  it('lists only paths that exist, except the git-ignored ones', () => {
    const missing = tree.entries.filter((e) => !e.tags.includes('ignored') && !existsSync(`${repoRoot}${e.path}`)).map((e) => e.path);
    expect(missing).toEqual([]);
  });

  it('has a catalog note for every entry and no note without an entry', () => {
    const notes = Object.keys(en).filter((k) => k.startsWith('fig.tree.note.')).map((k) => k.slice('fig.tree.note.'.length));
    expect(tree.entries.map((e) => e.id).sort()).toEqual(notes.sort());
  });

  it('has the ten Gradle modules of settings.gradle.kts', () => {
    const settings = readFileSync(`${repoRoot}settings.gradle.kts`, 'utf8');
    for (const m of tree.modules) expect(settings, m).toContain(`"${m}"`);
    expect(tree.modules).toHaveLength(10);
  });

  it('shows the top level first and the children of expanded entries after their parent', () => {
    const top = visibleEntries(tree.entries, new Set()).map((e) => e.id);
    expect(top).not.toContain('tables');
    expect(top).toContain('tablebase');
    const open = visibleEntries(tree.entries, new Set(['tablebase'])).map((e) => e.id);
    expect(open.indexOf('tables')).toBe(open.indexOf('tablebase') + 1);
    // a child of a collapsed parent stays hidden
    expect(visibleEntries(tree.entries, new Set(['webEngines'])).map((e) => e.id)).toContain('webEnginesJava');
    expect(visibleEntries(tree.entries, new Set(['webEnginesJava'])).map((e) => e.id)).not.toContain('webEnginesJava');
  });

  it('filters by where the code runs', () => {
    const byId = (id: string) => tree.entries.find((e) => e.id === id)!;
    expect(matchesFilter(byId('selfplay'), 'training')).toBe(true);
    expect(matchesFilter(byId('selfplay'), 'browser')).toBe(false);
    expect(matchesFilter(byId('selfplay'), 'all')).toBe(true);
    expect(WHERE).toEqual(['browser', 'server', 'training', 'build']);
  });

  it('reads the dependencies of the fact sheet: the dependency lists of the fact sheet', () => {
    // Fact sheet "Dependency graph as implemented": engine <- nn <- nn-tornado; engine <- tablebase; engine, tablebase, nn <- search;
    // search, tablebase, nn, nn-tornado <- selfplay; engine, tablebase, search <- teavm-web; engine, nn, tablebase, search <- teavm-net;
    // nn <- teavm-nn-bench; engine, search, tablebase, nn, nn-tornado <- app.
    expect(moduleDeps(tree, 'app').dependsOn.sort()).toEqual(['engine', 'nn', 'nn-tornado', 'search', 'tablebase']);
    expect(moduleDeps(tree, 'engine').dependsOn).toEqual([]);
    expect(moduleDeps(tree, 'nn').usedBy.sort()).toEqual(['app', 'nn-tornado', 'search', 'selfplay', 'teavm-net', 'teavm-nn-bench']);
    expect(moduleDeps(tree, 'selfplay').usedBy).toEqual([]);
  });

  it('ranks engine 0 and puts every module above the modules it depends on, without overlapping boxes', () => {
    const r = ranks(tree);
    expect(r.get('engine')).toBe(0);
    for (const d of tree.deps) expect(r.get(d.from)!).toBeGreaterThan(r.get(d.to)!);
    const layout = graphLayout(tree);
    expect(layout.boxes).toHaveLength(10);
    for (const a of layout.boxes) {
      expect(a.x).toBeGreaterThanOrEqual(0);
      expect(a.x + a.w).toBeLessThanOrEqual(GRAPH.width);
      for (const b of layout.boxes) {
        if (a === b) continue;
        const apart = a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y;
        expect(apart, `${a.module} and ${b.module}`).toBe(true);
      }
    }
    // an edge runs downward: from the bottom of the dependent module to the top of its dependency
    for (const e of layout.edges) expect(e.y2).toBeGreaterThan(e.y1);
  });
});

const ev = evalJson as unknown as EvalData;
const all: Setting = { include: new Set(ev.features.map((f) => f.name)), weights: {} };

describe('eval-model and eval-mid.json (V6)', () => {
  it('recomputes the score +179 from the 13 features and the learned weights (fact sheet section 6.3)', () => {
    expect(ev.features).toHaveLength(13);
    expect(scoreOf(ev.features, all, ev.meta.scale)).toBe(179);
    // hand calculation of the sum, 100 x sum(value x weight) = 178.645: 233.5248 + 0 + 166.424 + 28.531 - 28.5806 + 15.4641 + 69.846 + 18.9874 - 17.4175 - 13.5791 - 327.3216 + 32.7666 + 0
    expect(scoreOf(ev.features, all, ev.meta.scale)).toBe(Math.round(178.6451));
  });

  it('gives the win probability sigmoid(179 / 100) = 0.857', () => {
    expect(winProbability(179, 100)).toBeCloseTo(0.8569, 3);
    expect(winProbability(0, 100)).toBe(0.5);
    expect(Math.round(winProbability(179, 100) * 100)).toBe(86);
  });

  it('drops a switched-off feature from the sum: without the largest negative one (race x phase, -327) the score is 506', () => {
    const without: Setting = { include: new Set(ev.features.filter((f) => f.name !== 'dsumPhase').map((f) => f.name)), weights: {} };
    expect(scoreOf(ev.features, without, ev.meta.scale)).toBe(Math.round(178.6451 + 327.3216));
    expect(scoreOf(ev.features, { include: new Set(), weights: {} }, ev.meta.scale)).toBe(0);
  });

  it('applies a changed weight to its own feature only: dsum weight 0 removes +234', () => {
    const zero: Setting = { include: all.include, weights: { dsum: 0 } };
    expect(scoreOf(ev.features, zero, ev.meta.scale)).toBe(Math.round(178.6451 - 233.5248));
    const dsum = ev.features.find((f) => f.name === 'dsum')!;
    expect(contributionOf(dsum, zero, 100)).toBeCloseTo(0, 9);
    expect(contributionOf(dsum, all, 100)).toBeCloseTo(233.5248, 3);
  });

  it('matches the contributions stored in the file', () => {
    for (const f of ev.features) expect(contributionOf(f, all, 100), f.name).toBeCloseTo(f.contribution, 2);
  });

  it('scales a bar against the largest learned contribution and clamps it', () => {
    expect(barShare(-327.3216, 327.3216)).toBe(-1);
    expect(barShare(163.6608, 327.3216)).toBeCloseTo(0.5, 6);
    expect(barShare(900, 327.3216)).toBe(1);
    expect(barShare(5, 0)).toBe(0);
  });

  it('marks what a feature counts on the board', () => {
    expect(overlayOf('inGoal', ev).rings).toEqual([117]);
    expect(overlayOf('inStart', ev).rings.sort((a, b) => a - b)).toEqual([2, 9, 111, 113, 114, 118]);
    expect(overlayOf('adjacent', ev).pairs).toHaveLength(7); // 5 pairs of the mover, 2 of the opponent
    expect(overlayOf('jumps', ev).arrows).toHaveLength(4); // 3 + 1
    expect(overlayOf('jumps', ev).arrows.filter((a) => a.dashed)).toHaveLength(1);
    expect(overlayOf('steps', ev).arrows).toHaveLength(33); // 15 + 18
    expect(overlayOf('dsum', ev).discs).toHaveLength(20);
    expect(overlayOf('dmax', ev).rings.sort((a, b) => a - b)).toEqual([2, 118]); // the pieces with distance 12
    expect(overlayOf('tempo', ev)).toEqual({ discs: [], rings: [], pairs: [], arrows: [] });
  });
});

describe('ab-budget-mid.json (V7)', () => {
  it('has five levels with the depths of the fact sheet and moves in the notation of the figure', () => {
    expect(budgetJson.meta.levels).toEqual([1000, 5000, 20000, 100000, 500000]);
    expect(budgetJson.browser.map((r) => r.depth)).toEqual([3, 4, 6, 8, 10]);
    expect(budgetJson.plain.map((r) => r.depth)).toEqual([2, 3, 3, 4, 5]);
    for (const r of [...budgetJson.browser, ...budgetJson.plain]) expect(moveText(r.move.from, r.move.to)).toBe(r.move.text);
  });
});
