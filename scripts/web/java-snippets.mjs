// Writes web/src/about/data/java-snippets.json: the code snippets of the Info topic "java", each cut verbatim from a line range of
// this repository (path, startLine, endLine, text). Run from anywhere: `node scripts/web/java-snippets.mjs`. With `--check` it writes
// nothing and exits 1 when the committed file differs from what the repository gives now (the vitest java-snippets.test.ts does the same per entry).
// Two entries are not cut from the repository: the generated Hilla client (git-ignored, `generated: true`) and the outlook
// illustration (hypothetical code, `illustration: true`). Origin of the ranges: the fact sheet of the topic, sections 7a to 8.
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const out = `${root}web/src/about/data/java-snippets.json`;

// id (lower case of the fact sheet id), language, path, first line, last line (1-based, inclusive)
const RANGES = [
  ['a1', 'java', 'engine/src/main/java/sternhalma/engine/Move.java', 9, 11],
  ['a2', 'java', 'engine/src/main/java/sternhalma/engine/Position.java', 388, 397],
  ['a3', 'java', 'engine/src/main/java/sternhalma/engine/Position.java', 542, 554],
  ['b1', 'java', 'nn-tornado/src/main/java/sternhalma/tornado/KernelsHexConv.java', 98, 110],
  ['b2', 'java', 'nn-tornado/src/main/java/sternhalma/tornado/BatchedMatMul.java', 57, 62],
  ['d1', 'java', 'nn/src/main/java/sternhalma/nn/VectorNetwork.java', 305, 315],
  ['d2', 'java', 'nn/src/main/java/sternhalma/nn/VectorSupport.java', 23, 23],
  ['e1', 'java', 'selfplay/src/main/java/sternhalma/selfplay/Job.java', 102, 106],
  ['e2', 'java', 'teavm-web/src/main/java/sternhalma/web/Web.java', 28, 35],
  ['e3', 'java', 'nn-tornado/src/main/java/sternhalma/tornado/OpsCopy.java', 20, 22],
  ['h1', 'java', 'app/src/main/java/sternhalma/app/GameEndpoint.java', 10, 21],
  ['h3', 'ts', 'web/src/engines/server/load.ts', 13, 15],
];

const GENERATED = {
  id: 'hgen',
  lang: 'ts',
  path: 'app/src/main/frontend/generated/GameEndpoint.ts',
  startLine: 5,
  endLine: 5,
  generated: true,
  // The file is written by the Vaadin Gradle plugin and git-ignored; this line was read from a working copy on 2026-11-21.
  text: 'async function choose_1(engineId: string | undefined, history: Array<number> | undefined, nodes: number, init?: EndpointRequestInit_1): Promise<ServerChoice_1 | undefined> { return client_1.call("GameEndpoint", "choose", { engineId, history, nodes }, init); }',
};

const ILLUSTRATION = {
  id: 'illus',
  lang: 'java',
  illustration: true,
  text: [
    '// ILLUSTRATION – assumes JEP 401 (preview in JDK 28) and a future final Vector API.',
    'value record Move(byte from, byte to) {}            // today: int from | (to << 8), Move.java:9-11',
    'Move[] moves = new Move[Position.MAX_MOVES];        // a JVM may flatten this array (JEP 401)',
    '',
    'FloatVector acc = FloatVector.broadcast(SPECIES, bias[o]);  // FloatVector expected to become a value class (JEP 537)',
    'for (int ch = 0; ch < inChannels; ch++, w += TILE) {',
    '    acc = FloatVector.fromArray(SPECIES, weight, w).fma(FloatVector.broadcast(SPECIES, in[r + ch]), acc);',
    '}',
  ].join('\n'),
};

const cache = new Map();
const lines = (path) => {
  if (!cache.has(path)) cache.set(path, readFileSync(`${root}${path}`, 'utf8').split('\n'));
  return cache.get(path);
};

const snippets = RANGES.map(([id, lang, path, startLine, endLine]) => {
  const text = lines(path).slice(startLine - 1, endLine).join('\n');
  if (text.trim() === '') throw new Error(`${path}:${startLine}-${endLine} is empty`);
  return { id, lang, path, startLine, endLine, text };
});

const json = `${JSON.stringify({ schema: 1, snippets: [...snippets, GENERATED, ILLUSTRATION] }, null, 2)}\n`;
if (process.argv.includes('--check')) {
  if (readFileSync(out, 'utf8') !== json) {
    console.error('java-snippets: web/src/about/data/java-snippets.json differs from the repository');
    process.exit(1);
  }
  console.error(`java-snippets: ${snippets.length} snippets match the repository`);
} else {
  writeFileSync(out, json);
  console.error(`java-snippets: wrote ${snippets.length + 2} entries to ${out}`);
}
