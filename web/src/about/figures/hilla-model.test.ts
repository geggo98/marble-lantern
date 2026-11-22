// Model of figure J6 (Hilla data flow): the table of the nine states, the path through the six boxes, and the data check: every identifier
// that the wire card shows still occurs in the file that SOURCES names for it.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { BOXES, GAPS, LANE_BOXES, SOURCES, STEPS, STEP_COUNT, WIRE, tagPairs, wireText } from './hilla-model';
import type { ArrowId, BoxId, StepId } from './hilla-model';

const ROOT = fileURLToPath(new URL('../../../../', import.meta.url));
const ids: readonly Exclude<StepId, 'idle'>[] = ['build', 'call', 'pack', 'wire', 'unpack', 'run', 'reply', 'resolve'];

describe('J6 steps', () => {
  it('has the idle state and eight steps in the order of the spec', () => {
    expect(STEPS.map((s) => s.id)).toEqual(['idle', ...ids]);
    expect(STEP_COUNT).toBe(8);
  });

  it('lights at least one box in every step and none at idle', () => {
    expect(STEPS[0]!.boxes).toEqual([]);
    for (const s of STEPS.slice(1)) expect(s.boxes.length, s.id).toBeGreaterThan(0);
  });

  it('walks the U: down the browser lane, over the network, up the server lane, and back', () => {
    const path = STEPS.slice(2).map((s) => s.boxes);
    expect(path).toEqual([
      ['call', 'generated'],
      ['generated', 'client'],
      ['client', 'controller'],
      ['controller', 'endpoint'],
      ['endpoint', 'engines'],
      ['engines', 'endpoint', 'controller'],
      ['client', 'generated', 'call'],
    ]);
    // consecutive request steps share a box: the data is handed on, never skipped
    for (let i = 1; i < 5; i++) expect(path[i]!.some((b) => path[i - 1]!.includes(b)), STEPS[i + 2]!.id).toBe(true);
  });

  it('runs the call arrows in the call direction and the reply arrows back', () => {
    const arrows = STEPS.flatMap((s) => s.arrows);
    expect(arrows).toEqual(['b12:req', 'b23:req', 's21:req', 's32:req', 's32:rep', 's21:rep', 'b23:rep', 'b12:rep']);
    expect(new Set(arrows).size).toBe(8);
    const gaps = [...GAPS.browser, ...GAPS.server];
    for (const a of arrows) expect(gaps).toContain(a.split(':')[0]);
  });

  it('keeps the Promise pending from the call to the reply and done in the last step', () => {
    expect(STEPS.map((s) => s.wait)).toEqual(['none', 'none', 'pending', 'pending', 'pending', 'pending', 'pending', 'pending', 'done']);
  });

  it('marks the build step as outside run time and the network steps as no lane', () => {
    expect(STEPS[1]).toMatchObject({ where: 'build', form: 'source', here: null, edges: ['build'] });
    expect(STEPS.filter((s) => s.here === 'network').map((s) => s.id)).toEqual(['wire', 'reply']);
  });

  it('assigns every box to its lane and kind, and every lane lists three boxes', () => {
    expect(Object.keys(BOXES).sort()).toEqual(['call', 'client', 'controller', 'endpoint', 'engines', 'generated']);
    for (const lane of ['browser', 'server'] as const) {
      expect(LANE_BOXES[lane]).toHaveLength(3);
      for (const b of LANE_BOXES[lane]) expect(BOXES[b].lane).toBe(lane);
    }
    expect(Object.values(BOXES).map((b) => b.kind).sort()).toEqual(['gen', 'lib', 'lib', 'own', 'own', 'own']);
    // row 2 holds the two files with the same name
    expect(LANE_BOXES.browser[1]).toBe('generated');
    expect(LANE_BOXES.server[1]).toBe('endpoint');
  });

  it('refers only to existing boxes and arrows', () => {
    const boxes = Object.keys(BOXES) as BoxId[];
    for (const s of STEPS) for (const b of s.boxes) expect(boxes).toContain(b);
    const all = new Set<ArrowId>(['b12:req', 'b12:rep', 'b23:req', 'b23:rep', 's21:req', 's21:rep', 's32:req', 's32:rep']);
    for (const s of STEPS) for (const a of s.arrows) expect(all.has(a)).toBe(true);
  });

  it('has four tag pairs for the card in the eight steps (build/source, browser/ts, browser/json, network/json, server/java)', () => {
    expect(tagPairs().map((x) => x.join('/'))).toEqual(['build/source', 'browser/ts', 'browser/json', 'network/json', 'server/java']);
  });
});

describe('J6 wire card', () => {
  it('has a body for every step after idle, of at most seven lines, without comments', () => {
    expect(Object.keys(WIRE)).toEqual(ids);
    for (const id of ids) {
      const text = wireText(id);
      expect(text.split('\n').length, id).toBeLessThanOrEqual(7);
      expect(text, id).not.toMatch(/\/\/|\/\*|#!/);
      expect(WIRE[id].some((x) => x.t === 'k'), id).toBe(true); // the part that changes form is marked in every step
    }  });

  it('marks values without a measured source as holes, and only in the wire and reply cards', () => {
    const holes = (id: (typeof ids)[number]): string[] => WIRE[id].filter((x) => x.t === 'h').map((x) => x.s);
    expect(holes('wire')).toEqual(['‹token›']);
    expect(holes('reply')).toEqual(['‹int›', '‹int›', '‹int›', '‹long›', '‹double›', '‹bool›']);
    for (const id of ids.filter((i) => i !== 'wire' && i !== 'reply')) expect(holes(id), id).toEqual([]);
  });

  it('shows the seven fields of ServerChoice in the declared order in the reply', () => {
    expect(wireText('reply')).toContain('(move, score,\n    depth, nodes, ms, raceMove,\n    rows1Requests)');
  });
});

describe('J6 sources', () => {
  const squash = (s: string): string => s.replace(/\s+/g, ' ').replace(/(\d)_(\d)/g, '$1$2');
  const sources = SOURCES.map((s) => ({ ...s, path: `${ROOT}${s.file}` }));

  it('names a file for every step after idle', () => {
    for (const id of ids) expect(SOURCES.some((s) => s.step === id), id).toBe(true);
  });

  it.each(sources.filter((s) => !s.generated).map((s) => [s.step, s.file, s] as const))('step %s: %s still holds its identifiers', (_step, _file, s) => {
    expect(existsSync(s.path), `${s.file} is missing`).toBe(true);
    const text = squash(readFileSync(s.path, 'utf8'));
    for (const needle of s.needles) expect(text, needle).toContain(squash(needle));
  });

  // The generated client is not in Git and node_modules is not either: check them where the Hilla build and npm have run.
  it.each(sources.filter((s) => s.generated).map((s) => [s.step, s.file, s] as const))('step %s: %s (build product), when present', (_step, _file, s) => {
    if (!existsSync(s.path)) return;
    const text = squash(readFileSync(s.path, 'utf8'));
    for (const needle of s.needles) expect(text, needle).toContain(squash(needle));
  });
});
