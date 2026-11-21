// The model behind the three box-diagram figures (V1 offline, V2 online, V3 training pipeline): which box sits in which region, which
// boxes are stores, and which boxes and arrows each step of the stepper lights up. Pure functions, no React.
//
// The data files arch-*.json hold boxes and edges but no regions of the figure and no steps (plan section 3 expected steps in the
// JSON); the regions and steps are the content of the figure, so they live here, by box id and by edge key `<from>-<to>`. The tests
// check every id against the data files and every step against the catalog.
import type { ArchBox, ArchData, ArchEdge } from '../kit';
import type { BoxKind } from '../kit';

export type ArchKind = 'offline' | 'online' | 'pipeline';

export interface RawBox extends ArchBox {
  source?: { paths?: readonly string[]; generated?: boolean; note?: string };
}

export interface RawArch {
  boxes: readonly RawBox[];
  edges: readonly ArchEdge[];
  files?: readonly { id: string; name: string }[];
}

export interface StepDef {
  nodes: readonly string[];
  /** Edge keys `<from>-<to>`. */
  edges: readonly string[];
}

export type Device = 'cpu' | 'gpu';

export const edgeKey = (e: Pick<ArchEdge, 'from' | 'to'>): string => `${e.from}-${e.to}`;

/** Catalog prefix `fig.<prefix>.` of each figure. */
export const PREFIX: Record<ArchKind, string> = { offline: 'offline', online: 'online', pipeline: 'pipeline' };

const OFFLINE_GROUP: Record<string, string> = { 'main thread': 'main', 'Web Worker': 'workers', 'build output': 'host', 'browser service worker': 'sw' };
const ONLINE_GROUP: Record<string, string> = { 'browser main thread': 'browser', 'Web Workers': 'browser' };
const PIPELINE_GROUP: Record<string, string> = {
  'JVM command line': 'jobs',
  'JVM virtual threads': 'jobs',
  JVM: 'jobs',
  TornadoVM: 'jobs',
  disk: 'run',
  'disk, git-ignored': 'run',
  script: 'ship',
};

/** The region (`fig.<prefix>.group.<id>`) of a box, from the `runsIn` of the data file; null leaves the box out of the figure. */
export function groupOf(kind: ArchKind, box: RawBox): string | null {
  const runsIn = box.runsIn ?? '';
  if (kind === 'offline') return OFFLINE_GROUP[runsIn] ?? null;
  // The cloud box of the training file has no arrow and belongs to no region of the figure.
  if (kind === 'pipeline') return PIPELINE_GROUP[runsIn] ?? null;
  return ONLINE_GROUP[runsIn] ?? 'server';
}

export const KINDS: Record<ArchKind, Readonly<Record<string, BoxKind>>> = {
  offline: { models: 'store', raceTable: 'store', serviceWorker: 'external' },
  online: { raceTables: 'store', jarBuild: 'external' },
  pipeline: { sampleFiles: 'store', checkpoint: 'store', runDirectory: 'store' },
};

export const OFFLINE_STEPS: readonly StepDef[] = [
  { nodes: ['page', 'controller'], edges: ['page-controller'] },
  { nodes: ['controller', 'session', 'workerClient'], edges: ['controller-session', 'session-workerClient'] },
  { nodes: ['workerClient', 'javaEngine', 'cppEngine', 'netMcts'], edges: ['workerClient-javaEngine', 'workerClient-cppEngine', 'workerClient-netMcts'] },
  {
    nodes: ['javaEngine', 'cppEngine', 'netMcts', 'netCpu', 'netGpu', 'models', 'tableLoader'],
    edges: ['tableLoader-javaEngine', 'netMcts-netCpu', 'netMcts-netGpu', 'models-netMcts'],
  },
  { nodes: ['workerClient', 'javaEngine', 'cppEngine', 'netMcts'], edges: ['workerClient-javaEngine', 'workerClient-cppEngine', 'workerClient-netMcts'] },
  { nodes: ['controller', 'page', 'board'], edges: ['page-controller', 'page-board'] },
];

export const OFFLINE_CANCEL_STEPS: readonly StepDef[] = [
  { nodes: ['page', 'controller', 'workerClient'], edges: ['page-controller', 'session-workerClient'] },
  { nodes: ['workerClient', 'javaEngine', 'cppEngine', 'netMcts'], edges: ['workerClient-javaEngine', 'workerClient-cppEngine', 'workerClient-netMcts'] },
  { nodes: ['tableLoader', 'javaEngine'], edges: ['tableLoader-javaEngine'] },
];

export const ONLINE_STEPS: readonly StepDef[] = [
  { nodes: ['page', 'serverAdapter', 'hillaClient'], edges: ['page-serverAdapter', 'serverAdapter-hillaClient'] },
  { nodes: ['hillaClient', 'endpoint'], edges: ['hillaClient-endpoint'] },
  { nodes: ['endpoint', 'engines'], edges: ['endpoint-engines'] },
  { nodes: ['engines'], edges: [] },
  {
    nodes: ['engines', 'alphaBeta', 'raceTables', 'networkPlayer', 'cpuForward', 'gpuForward'],
    edges: ['engines-alphaBeta', 'engines-networkPlayer', 'alphaBeta-raceTables', 'networkPlayer-cpuForward', 'networkPlayer-gpuForward'],
  },
  { nodes: ['endpoint', 'hillaClient', 'serverAdapter', 'page'], edges: ['hillaClient-endpoint', 'serverAdapter-hillaClient', 'page-serverAdapter'] },
];

export const PIPELINE_STEPS: readonly StepDef[] = [
  { nodes: ['loop', 'selfPlayGames', 'mcts', 'pooledEvaluator'], edges: ['loop-selfPlayGames', 'selfPlayGames-mcts', 'selfPlayGames-pooledEvaluator', 'pooledEvaluator-selfPlayGames'] },
  { nodes: ['loop', 'selfPlayGames', 'sampleFiles', 'runDirectory'], edges: ['loop-runDirectory'] },
  {
    nodes: ['sampleFiles', 'replayBuffer', 'trainerCpu', 'trainerGpu', 'checkpoint'],
    edges: ['sampleFiles-replayBuffer', 'replayBuffer-trainerCpu', 'replayBuffer-trainerGpu', 'trainerCpu-checkpoint', 'trainerGpu-checkpoint'],
  },
  { nodes: ['checkpoint', 'gate'], edges: ['checkpoint-gate'] },
  { nodes: ['gate', 'loop', 'runDirectory'], edges: ['gate-loop', 'loop-runDirectory'] },
];

export const STEPS: Record<ArchKind, readonly StepDef[]> = { offline: OFFLINE_STEPS, online: ONLINE_STEPS, pipeline: PIPELINE_STEPS };

export interface Arranged {
  data: ArchData;
  /** Box by id, with its `source` block. */
  boxes: ReadonlyMap<string, RawBox>;
}

/**
 * The data of the diagram: the boxes of the data file with `runsIn` replaced by the region id, without the boxes that belong to no region and,
 * for the pipeline, without the trainer that the device switch did not choose. Edges to a removed box are removed as well.
 */
export function arrange(kind: ArchKind, raw: RawArch, device: Device = 'cpu'): Arranged {
  const dropTrainer = kind === 'pipeline' ? (device === 'cpu' ? 'trainerGpu' : 'trainerCpu') : null;
  const boxes: ArchBox[] = [];
  const byId = new Map<string, RawBox>();
  for (const b of raw.boxes) {
    const group = groupOf(kind, b);
    if (group === null || b.id === dropTrainer) continue;
    boxes.push({ id: b.id, label: b.label, runsIn: group });
    byId.set(b.id, b);
  }
  const edges = raw.edges.filter((e) => byId.has(e.from) && byId.has(e.to));
  return { data: { boxes, edges }, boxes: byId };
}

export interface ResolvedStep {
  boxes: string[];
  edges: number[];
}

/** The boxes and edge indices of a step in `data`; ids that are not in this arrangement (the other trainer) are left out. */
export function resolveStep(step: StepDef, data: ArchData): ResolvedStep {
  const present = new Set(data.boxes.map((b) => b.id));
  const index = new Map(data.edges.map((e, i) => [edgeKey(e), i] as const));
  return {
    boxes: step.nodes.filter((id) => present.has(id)),
    edges: step.edges.flatMap((k) => {
      const i = index.get(k);
      return i === undefined ? [] : [i];
    }),
  };
}

/** Permits that run and requests that wait for `requests` concurrent requests at `permits` permits (design notes, V2). */
export function semaphoreState(requests: number, permits: number): { running: number; waiting: number } {
  const k = Math.max(0, Math.floor(requests));
  const p = Math.max(0, Math.floor(permits));
  return { running: Math.min(k, p), waiting: Math.max(0, k - p) };
}
