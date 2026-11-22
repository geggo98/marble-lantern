// The model of figure J6 (topic java-web): the data flow of one call of the server engine in the online mode, in eight steps after the idle
// state. Pure data, no text: every sentence of the figure is a catalog key (`fig.hilla.*`). The lines of the wire card are data (code, JSON,
// header lines) and have no key; hilla-model.test.ts checks each identifier of them against the file that SOURCES names.
// Design: web/design/about/about-notes.md (kit) and the J6 spec of the java3 round (steps, hooks, sources).

export type BoxId = 'call' | 'generated' | 'client' | 'controller' | 'endpoint' | 'engines';
export type BoxKind = 'own' | 'lib' | 'gen';
export type LaneId = 'browser' | 'server';
export type StepId = 'idle' | 'build' | 'call' | 'pack' | 'wire' | 'unpack' | 'run' | 'reply' | 'resolve';
/** A gap between two boxes of a lane and the direction of the arrow in it: the call (`req`) or the reply (`rep`). */
export type ArrowId = 'b12:req' | 'b12:rep' | 'b23:req' | 'b23:rep' | 's21:req' | 's21:rep' | 's32:req' | 's32:rep';
export type EdgeId = 'nopath' | 'build' | 'request' | 'reply';
export type WireWhere = 'build' | 'browser' | 'network' | 'server';
export type WireForm = 'source' | 'ts' | 'json' | 'java';
export type WaitState = 'none' | 'pending' | 'done';

export interface HillaStep {
  readonly id: StepId;
  readonly boxes: readonly BoxId[];
  readonly arrows: readonly ArrowId[];
  readonly edges: readonly EdgeId[];
  /** The lane that holds the data in this step (`network` and `null` mark no lane). */
  readonly here: LaneId | 'network' | null;
  readonly where: WireWhere | null;
  readonly form: WireForm | null;
  readonly wait: WaitState;
}

/** The nine states: index 0 is idle, 1 to 8 are the steps. */
export const STEPS: readonly HillaStep[] = [
  { id: 'idle', boxes: [], arrows: [], edges: [], here: null, where: null, form: null, wait: 'none' },
  { id: 'build', boxes: ['endpoint', 'generated'], arrows: [], edges: ['build'], here: null, where: 'build', form: 'source', wait: 'none' },
  { id: 'call', boxes: ['call', 'generated'], arrows: ['b12:req'], edges: ['nopath'], here: 'browser', where: 'browser', form: 'ts', wait: 'pending' },
  { id: 'pack', boxes: ['generated', 'client'], arrows: ['b23:req'], edges: [], here: 'browser', where: 'browser', form: 'json', wait: 'pending' },
  { id: 'wire', boxes: ['client', 'controller'], arrows: [], edges: ['request'], here: 'network', where: 'network', form: 'json', wait: 'pending' },
  { id: 'unpack', boxes: ['controller', 'endpoint'], arrows: ['s21:req'], edges: [], here: 'server', where: 'server', form: 'java', wait: 'pending' },
  { id: 'run', boxes: ['endpoint', 'engines'], arrows: ['s32:req'], edges: [], here: 'server', where: 'server', form: 'java', wait: 'pending' },
  { id: 'reply', boxes: ['engines', 'endpoint', 'controller'], arrows: ['s32:rep', 's21:rep'], edges: ['reply'], here: 'network', where: 'network', form: 'json', wait: 'pending' },
  { id: 'resolve', boxes: ['client', 'generated', 'call'], arrows: ['b23:rep', 'b12:rep'], edges: [], here: 'browser', where: 'browser', form: 'ts', wait: 'done' },
];

/** The number of steps after the idle state. */
export const STEP_COUNT = STEPS.length - 1;

/** The six boxes: identifier (data, not translated; two parts get a break point between them), kind, lane. */
export const BOXES: Readonly<Record<BoxId, { readonly id: readonly [string, string?]; readonly kind: BoxKind; readonly lane: LaneId }>> = {
  call: { id: ['api.choose(…)'], kind: 'own', lane: 'browser' },
  generated: { id: ['GameEndpoint', '.ts'], kind: 'gen', lane: 'browser' },
  client: { id: ['ConnectClient'], kind: 'lib', lane: 'browser' },
  engines: { id: ['Engines.java'], kind: 'own', lane: 'server' },
  endpoint: { id: ['GameEndpoint', '.java'], kind: 'own', lane: 'server' },
  controller: { id: ['Endpoint', 'Controller'], kind: 'lib', lane: 'server' },
};

/** Boxes of each lane from top to bottom (the DOM order). The call enters the server lane at the bottom box. */
export const LANE_BOXES: Readonly<Record<LaneId, readonly BoxId[]>> = {
  browser: ['call', 'generated', 'client'],
  server: ['engines', 'endpoint', 'controller'],
};

/** The gaps between two boxes of a lane, from top to bottom: the call runs down in the browser lane and up in the server lane. */
export const GAPS: Readonly<Record<LaneId, readonly ('b12' | 'b23' | 's32' | 's21')[]>> = {
  browser: ['b12', 'b23'],
  server: ['s32', 's21'],
};

// ---- the wire card ----

/** A piece of a card line: `f` file name (muted), `k` the part that changes form in this step (bold, accent), `h` a hole (a value without a measured source), `p` plain. */
export interface Seg {
  readonly t: 'f' | 'k' | 'h' | 'p';
  readonly s: string;
}
const f = (s: string): Seg => ({ t: 'f', s });
const k = (s: string): Seg => ({ t: 'k', s });
const h = (s: string): Seg => ({ t: 'h', s: `‹${s}›` });
const p = (s: string): Seg => ({ t: 'p', s });

/** The body of the card for each step after idle. At most seven lines; no comments and no sentences. */
export const WIRE: Readonly<Record<Exclude<StepId, 'idle'>, readonly Seg[]>> = {
  build: [f('GameEndpoint.java'), p('\nServerChoice '), k('choose'), p('(\n    String engineId, int[] history,\n    int nodes)\n'), f('generated/GameEndpoint.ts'), p('\n'), k('choose'), p('(engineId, history, nodes)\n: Promise<ServerChoice | undefined>')],
  call: [f('server/client.ts'), p('\nconst result = await '), k('api.choose'), p('(\n    "browser", [], 20000);')],
  pack: [f('generated/GameEndpoint.ts'), p('\nclient.call("GameEndpoint",\n    "choose", { engineId, history,\n    nodes })\n'), f('Connect.js'), p('\nbody = '), k('JSON.stringify'), p('(params)\nnew Request(url, {method: "POST"})')],
  wire: [k('POST'), p(' /connect/GameEndpoint/choose\nAccept: application/json\nContent-Type: application/json\nX-CSRF-Token: '), h('token'), p('\n\n{"engineId":"browser",\n "history":[],"nodes":20000}')],
  unpack: [f('GameEndpoint.java'), p('\n'), k('String'), p(' engineId = "browser"\n'), k('int[]'), p('  history  = {}\n'), k('int'), p('    nodes    = 20000\nchoose(engineId, history, nodes)')],
  run: [f('Engines.java'), p('\nposition = '), k('replay'), p('(history);\nslots.'), k('tryAcquire'), p('(30, SECONDS);\nsearchClassical(engineId,\n    position, nodes);')],
  reply: [p('new '), k('ServerChoice'), p('(move, score,\n    depth, nodes, ms, raceMove,\n    rows1Requests)\n{"move":'), h('int'), p(',"score":'), h('int'), p(',\n "depth":'), h('int'), p(',"nodes":'), h('long'), p(',\n "ms":'), h('double'), p(',"raceMove":'), h('bool'), p(',\n "rows1Requests":0}')],
  resolve: [f('server/client.ts'), p('\nresult.'), k('move'), p('    result.score\nresult.depth   result.nodes\nresult.ms      result.raceMove\nresult.rows1Requests')],
};

/** The text of a card body as plain string (for tests). */
export const wireText = (id: Exclude<StepId, 'idle'>): string => WIRE[id].map((x) => x.s).join('');

/** The distinct pairs (where, form) of the steps: the tags of the card stand in one grid cell, one pair each, so the head keeps its width. */
export function tagPairs(): readonly (readonly [WireWhere, WireForm])[] {
  const seen = new Map<string, readonly [WireWhere, WireForm]>();
  for (const s of STEPS) if (s.where && s.form) seen.set(`${s.where}|${s.form}`, [s.where, s.form]);
  return [...seen.values()];
}

/**
 * Where each shown identifier comes from: a file of the repository (relative to the root) and the strings that must still occur in it
 * (hilla-model.test.ts). `generated` marks a file that the Hilla build writes or that npm installs: the test skips it when it is absent.
 */
export interface SourceCheck {
  readonly step: Exclude<StepId, 'idle'>;
  readonly file: string;
  readonly needles: readonly string[];
  readonly generated?: boolean;
}
export const SOURCES: readonly SourceCheck[] = [
  { step: 'build', file: 'app/src/main/java/sternhalma/app/GameEndpoint.java', needles: ['ServerChoice choose(String engineId, int[] history, int nodes)', '@BrowserCallable'] },
  { step: 'build', file: 'app/src/main/frontend/generated/GameEndpoint.ts', needles: ['choose_1(engineId: string | undefined, history: Array<number> | undefined, nodes: number', 'Promise<ServerChoice_1 | undefined>'], generated: true },
  { step: 'call', file: 'web/src/engines/server/client.ts', needles: ['this.api.choose(', '20_000'] },
  { step: 'call', file: 'app/src/main/java/sternhalma/app/Engines.java', needles: ['new ServerEngine("browser"'] },
  { step: 'pack', file: 'app/src/main/frontend/generated/GameEndpoint.ts', needles: ['client_1.call("GameEndpoint", "choose", { engineId, history, nodes }'], generated: true },
  { step: 'pack', file: 'app/node_modules/@vaadin/hilla-frontend/Connect.js', needles: ['JSON.stringify(params', 'new Request(', 'method: "POST"'], generated: true },
  { step: 'wire', file: 'app/node_modules/@vaadin/hilla-frontend/Connect.js', needles: ['Accept: "application/json"', '"Content-Type"'], generated: true },
  { step: 'wire', file: 'app/node_modules/@vaadin/hilla-frontend/CsrfInfoSource.js', needles: ['X-CSRF-Token'], generated: true },
  { step: 'wire', file: 'app/src/main/frontend/generated/connect-client.default.ts', needles: ['prefix: "connect"'], generated: true },
  { step: 'unpack', file: 'app/src/main/java/sternhalma/app/GameEndpoint.java', needles: ['int[] history', 'String engineId', 'int nodes'] },
  { step: 'run', file: 'app/src/main/java/sternhalma/app/Engines.java', needles: ['replay(history)', 'slots.tryAcquire(WAIT_SECONDS', 'searchClassical(engineId, position, nodes)', 'WAIT_SECONDS = 30', 'TimeUnit.SECONDS'] },
  { step: 'reply', file: 'app/src/main/java/sternhalma/app/ServerChoice.java', needles: ['int move, int score, int depth, long nodes, double ms, boolean raceMove, int rows1Requests'] },
  { step: 'resolve', file: 'web/src/engines/server/client.ts', needles: ['const result = await this.api.choose(', 'return result;'] },
  { step: 'reply', file: 'app/src/test/java/sternhalma/app/EnginesTest.java', needles: ['rows1Requests'] },
];
