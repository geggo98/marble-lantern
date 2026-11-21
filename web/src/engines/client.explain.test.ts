// The two requests of the Info page's live mode, `features` and `netForward`: client calls, the fake engine behind them, and the handler stubs
// (WP2 and WP4 replace the stubs; until then every real engine answers unsupported, so a figure that asks too early fails loudly).
import { describe, expect, it } from 'vitest';
import { WorkerEngine } from './client';
import { CppRequestHandler } from './cpp/handler';
import { JavaRequestHandler } from './java/handler';
import { NetRequestHandler } from './net/handler';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from './testing/fake-engine';
import { tinyLegalMoves } from './testing/tiny-moves';
import { applyMove, initialOwners } from '../game/board-state';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Request } from './protocol';

function harness(options: { chooseDelayMs?: number } = {}) {
  const workers: FakeWorker[] = [];
  const core = new FakeEngineCore(3);
  const engine = new WorkerEngine({
    info: FAKE_INFO,
    assets: { engine: [], manifest: '', tableGz: '' },
    createWorker: () => {
      const w = new FakeWorker(core, options);
      workers.push(w);
      return w;
    },
  });
  return { engine, workers, core };
}

/** Two legal plies from the start position (the first legal move of each side). */
function twoPlies(): number[] {
  const owner = initialOwners(DEFAULT_VARIANT);
  const history: number[] = [];
  for (let ply = 0; ply < 2; ply++) {
    const move = tinyLegalMoves(owner, ply % 2, DEFAULT_VARIANT)[0]!;
    applyMove(owner, move);
    history.push(move);
  }
  return history;
}

describe('features', () => {
  it('goes through the protocol and returns a report of the declared shape', async () => {
    const { engine, core } = harness();
    await engine.init();
    const report = await engine.features(twoPlies());
    expect(core.featuresCalls).toBe(1);
    expect(report.features).toHaveLength(13);
    expect(report.sideToMove).toBe(0);
    expect(report.distance.map((d) => d.length)).toEqual([121, 121]);
  });

  it('rejects a variant that is not classic with the code unsupported', async () => {
    const { engine } = harness();
    await engine.init();
    await expect(engine.features([], { jump: 'long', players: 2 })).rejects.toMatchObject({ code: 'unsupported' });
    await expect(engine.features([], { jump: 'adjacent', players: 3 })).rejects.toMatchObject({ code: 'unsupported' });
  });

  it('rejects an illegal history with illegal-history', async () => {
    const { engine } = harness();
    await engine.init();
    await expect(engine.features([0])).rejects.toMatchObject({ code: 'illegal-history' });
  });
});

describe('netForward', () => {
  it('returns the report, and the rotation flag follows the mover', async () => {
    const { engine } = harness();
    await engine.init();
    const a = await engine.netForward([]);
    expect(a.canonicalRotated).toBe(false);
    expect(a.policy.length).toBeGreaterThan(5);
    const b = await engine.netForward(twoPlies().slice(0, 1));
    expect(b.canonicalRotated).toBe(true);
  });

  it('is abortable like choose: AbortError, the worker is replaced and answers again', async () => {
    const { engine, workers } = harness({ chooseDelayMs: 100 });
    await engine.init();
    const controller = new AbortController();
    const run = engine.netForward([], controller.signal);
    await new Promise((r) => setTimeout(r, 0));
    controller.abort();
    await expect(run).rejects.toMatchObject({ name: 'AbortError' });
    expect(workers).toHaveLength(2);
    expect(workers[0]!.terminated).toBe(true);
    expect((await engine.netForward([])).modelId).toBe('fake');
  });
});

describe('handler stubs', () => {
  const features: Request = { id: 1, type: 'features', history: [], variant: DEFAULT_VARIANT };
  const forward: Request = { id: 2, type: 'netForward', history: [] };

  it('every real handler answers unsupported for a request it has no export for, and never touches an engine core', async () => {
    // The net handler implements netForward (WP4) and the classical ones implement features (WP2).
    for (const handler of [new CppRequestHandler(), new JavaRequestHandler()]) {
      await expect(handler.handle(forward)).rejects.toMatchObject({ code: 'unsupported' });
    }
    await expect(new NetRequestHandler().handle(features)).rejects.toMatchObject({ code: 'unsupported' });
  });

  it('the classical handlers serve features through the engine core (WP2): before init they fail like every core request', async () => {
    // The features export itself is checked against the real engines in features.cross.test.ts.
    for (const handler of [new CppRequestHandler(), new JavaRequestHandler()]) {
      await expect(handler.handle(features)).rejects.toMatchObject({ code: 'internal', message: expect.stringContaining('not initialised') });
    }
  });
});
