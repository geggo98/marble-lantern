// The analysis engine of the net engines (docs/web-ui.md, "Analysis for the net engines"): while a net engine plays, analyse and
// evaluate3 go to a classical engine in its own worker, created on the first call, freed when a classical engine plays again.
import { describe, expect, it } from 'vitest';
import { SwitchableEngine } from './switchable-engine';
import type { AnalysisSource } from './switchable-engine';
import { EngineFailure } from '../engines/protocol';
import type { AnalysisResult, Engine, EngineId } from '../engines/engine';

interface Fake extends Engine {
  calls: string[];
  disposed: boolean;
}

const report = { fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, totalMs: 0, tableVerifiedBy: 'engine' as const };

/** A classical fake answers analyse with its id in `position`; a net fake rejects analyse and evaluate3 with unsupported. */
function fake(id: EngineId, init: () => Promise<unknown> = () => Promise.resolve()): Fake {
  const net = id.startsWith('net-');
  const e: Fake = {
    calls: [],
    disposed: false,
    info: { id, labelKey: 'x', runtime: 'wasm', language: 'C++', version: '', binaryBytes: 0, binaryGzipBytes: 0 },
    init: async () => {
      e.calls.push('init');
      await init();
      return report;
    },
    newGame: async () => { e.calls.push('newGame'); },
    legalMoves: async () => [],
    choose: async () => { throw new Error('not used'); },
    analyse: async (history): Promise<AnalysisResult> => {
      e.calls.push(`analyse:${history.length}`);
      if (net) throw new EngineFailure('unsupported', 'net');
      return { moves: [1], scores: [id.length], position: id.length, nodes: 1, ms: 0 };
    },
    evaluate3: async () => {
      e.calls.push('evaluate3');
      if (net) throw new EngineFailure('unsupported', 'net');
      return [1, 2, 3];
    },
    outcome: async () => ({ kind: 'ongoing' }),
    dispose: () => { e.disposed = true; },
  };
  return e;
}

const setup = { nodes: [100, 100] };

/** A source that makes classical fakes with the given id and records every one it made. */
function source(id: EngineId = 'java-wasmgc', init?: () => Promise<unknown>): { made: Fake[]; get: () => AnalysisSource } {
  const made: Fake[] = [];
  return { made, get: () => ({ id, create: () => { const f = fake(id, init); made.push(f); return f; } }) };
}

describe('SwitchableEngine analysis for net engines', () => {
  it('a classical engine analyses itself: no helper, analysisId is its own id', async () => {
    const sw = new SwitchableEngine();
    const src = source();
    sw.setAnalysisSource(src.get);
    const cpp = fake('cpp-wasm');
    await sw.use(cpp, setup);
    await sw.analyse([1, 2], null, 10);
    expect(cpp.calls).toContain('analyse:2');
    expect(src.made).toHaveLength(0);
    expect(sw.analysisId).toBe('cpp-wasm');
  });

  it('a net engine: analyse and evaluate3 go to one helper, created and initialised once; the net engine is never asked', async () => {
    const sw = new SwitchableEngine();
    const src = source('java-wasmgc');
    sw.setAnalysisSource(src.get);
    const net = fake('net-wasm');
    await sw.use(net, setup);
    expect(sw.analysisId, 'the scope names the analysis engine before it exists').toBe('java-wasmgc');
    expect(sw.hasAnalysisEngine).toBe(false);
    const a = await sw.analyse([1], null, 10);
    expect(a.position).toBe('java-wasmgc'.length);
    await sw.evaluate3([1, 2, 3]);
    await sw.analyse([1, 2, 3, 4], null, 10);
    expect(src.made).toHaveLength(1);
    expect(src.made[0]!.calls).toEqual(['init', 'analyse:1', 'evaluate3', 'analyse:4']);
    expect(net.calls.filter((c) => c.startsWith('analyse') || c === 'evaluate3'), 'the net engine analyses nothing').toEqual([]);
    expect(sw.hasAnalysisEngine).toBe(true);
  });

  it('without a source, or a source without a classical engine, the net engine answers itself (unsupported)', async () => {
    for (const s of [null, () => null]) {
      const sw = new SwitchableEngine();
      sw.setAnalysisSource(s);
      await sw.use(fake('net-webgpu'), setup);
      await expect(sw.analyse([], null, 10)).rejects.toMatchObject({ code: 'unsupported' });
      await expect(sw.evaluate3([])).rejects.toMatchObject({ code: 'unsupported' });
      expect(sw.analysisId).toBe('net-webgpu');
    }
  });

  it('net to net keeps the helper; a classical engine frees it; dispose frees it too', async () => {
    const sw = new SwitchableEngine();
    const src = source();
    sw.setAnalysisSource(src.get);
    await sw.use(fake('net-wasm'), setup);
    await sw.analyse([], null, 10);
    await sw.use(fake('net-webgpu'), setup);
    await sw.analyse([], null, 10);
    expect(src.made).toHaveLength(1);
    expect(src.made[0]!.disposed).toBe(false);
    await sw.use(fake('cpp-wasm'), setup);
    expect(src.made[0]!.disposed, 'a classical engine needs no helper').toBe(true);
    expect(sw.hasAnalysisEngine).toBe(false);
    await sw.use(fake('net-wasm'), setup);
    await sw.analyse([], null, 10);
    expect(src.made).toHaveLength(2);
    sw.dispose();
    expect(src.made[1]!.disposed).toBe(true);
  });

  it('an abort while the helper starts rejects at once with AbortError; the start goes on and serves the next call', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    const sw = new SwitchableEngine();
    const src = source('cpp-wasm', () => gate);
    sw.setAnalysisSource(src.get);
    await sw.use(fake('net-wasm'), setup);
    const controller = new AbortController();
    const first = sw.analyse([1], null, 10, controller.signal);
    controller.abort();
    await expect(first).rejects.toMatchObject({ name: 'AbortError' });
    release();
    await sw.analyse([1, 2], null, 10);
    expect(src.made).toHaveLength(1);
    expect(src.made[0]!.calls).toEqual(['init', 'analyse:2']);
  });

  it('a signal that is already aborted rejects without starting anything new', async () => {
    const sw = new SwitchableEngine();
    const src = source();
    sw.setAnalysisSource(src.get);
    await sw.use(fake('net-wasm'), setup);
    const controller = new AbortController();
    controller.abort();
    await expect(sw.analyse([], null, 10, controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('a failed start is kept until the next engine is in use, then tried again', async () => {
    let fail = true;
    const sw = new SwitchableEngine();
    const src = source('java-wasmgc', async () => { if (fail) throw new EngineFailure('table-checksum', 'bad table'); });
    sw.setAnalysisSource(src.get);
    await sw.use(fake('net-wasm'), setup);
    await expect(sw.analyse([], null, 10)).rejects.toMatchObject({ code: 'table-checksum' });
    await expect(sw.evaluate3([])).rejects.toMatchObject({ code: 'table-checksum' });
    expect(src.made, 'not one table download per position').toHaveLength(1);
    expect(src.made[0]!.disposed).toBe(true);
    fail = false;
    await sw.use(fake('net-webgpu'), setup);
    await expect(sw.analyse([], null, 10)).resolves.toMatchObject({ position: 'java-wasmgc'.length });
    expect(src.made).toHaveLength(2);
  });

  it('a helper that finishes its start after a classical engine took over is disposed, and its caller gets an AbortError', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    const sw = new SwitchableEngine();
    const src = source('cpp-wasm', () => gate);
    sw.setAnalysisSource(src.get);
    await sw.use(fake('net-wasm'), setup);
    const pending = sw.analyse([], null, 10);
    await sw.use(fake('java-js'), setup);
    release();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(src.made[0]!.disposed).toBe(true);
    expect(src.made[0]!.calls).toEqual(['init']);
  });
});
