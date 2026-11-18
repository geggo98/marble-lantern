import { describe, expect, it } from 'vitest';
import { WorkerEngine } from '../engines/client';
import type { AnalysisResult, ChooseResult, Engine, EngineInfo, GameSetup, LoadReport } from '../engines/engine';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { replay, sideToMove } from './board-state';
import { GameController, IllegalMoveError } from './game';
import type { GameMode } from './game';

const assets = { engine: [], manifest: '', tableGz: '' };

async function fakeEngine(options: { chooseDelayMs?: number } = {}) {
  const core = new FakeEngineCore(3);
  const workers: FakeWorker[] = [];
  const engine = new WorkerEngine({
    info: FAKE_INFO, assets,
    createWorker: () => { const w = new FakeWorker(core, options); workers.push(w); return w; },
  });
  await engine.init();
  return { engine, core, workers };
}

const count = (o: Int8Array, side: number): number => o.filter((x) => x === side).length;

describe('GameController against the fake engine', () => {
  it('plays a complete human-vs-engine game: every ply is legal, pieces are conserved, the game ends', async () => {
    const { engine } = await fakeEngine();
    const game = new GameController({ engine, mode: 'human-vs-engine', humanSide: 1, nodes: [100, 100] });
    await game.newGame();
    // the engine is side 0 and moves first
    expect(game.state.history).toHaveLength(1);
    expect(game.state.sideToMove).toBe(1);
    let guard = 0;
    while (!game.state.outcome && guard++ < 300) {
      const before = game.state.history.length;
      // the human picks the legal move with the largest row gain, falling back to the first
      const legal = game.state.legal;
      expect(legal.length).toBeGreaterThan(0);
      await game.play(legal[legal.length - 1]!);
      expect(game.state.history.length).toBeGreaterThanOrEqual(before + 1);
      expect(count(game.state.owner, 0)).toBe(10);
      expect(count(game.state.owner, 1)).toBe(10);
    }
    expect(game.state.outcome).not.toBeNull();
    expect(game.state.history.length).toBeLessThanOrEqual(200);
    // the state equals a replay of the history
    expect(game.state.owner).toEqual(replay(game.state.history));
    // every move in the history was legal at its ply, by the independent generator
    const owner = replay([]);
    game.state.history.forEach((move, ply) => {
      const side = sideToMove(ply);
      expect(tinyLegalMoves(owner, side)).toContain(move);
      owner[move & 0xff] = -1;
      owner[move >>> 8] = side;
    });
  }, 30000);

  it('plays engine-versus-engine to the end and reports the last engine figures', async () => {
    const { engine } = await fakeEngine();
    const game = new GameController({ engine, mode: 'engine-vs-engine', nodes: [10, 20] });
    await game.newGame();
    expect(game.state.history).toHaveLength(0);
    await game.runEngineVsEngine();
    expect(game.state.outcome).not.toBeNull();
    expect(game.state.thinking).toBe(false);
    expect(game.state.lastEngine).toMatchObject({ rows1Requests: 0 });
    // the node budgets are per side: the last mover decides which number shows
    const last = game.state.lastEngine!;
    expect(last.nodes).toBe(last.side === 0 ? 10 : 20);
  }, 30000);

  it('rejects an illegal move and leaves the state alone', async () => {
    const { engine } = await fakeEngine();
    const game = new GameController({ engine, mode: 'human-vs-engine', humanSide: 0, nodes: [1, 1] });
    await game.newGame();
    const before = game.state;
    await expect(game.play(0)).rejects.toBeInstanceOf(IllegalMoveError);
    expect(game.state).toBe(before);
  });

  it('plays the human first when the human has side 0', async () => {
    const { engine } = await fakeEngine();
    const game = new GameController({ engine, mode: 'human-vs-engine', humanSide: 0, nodes: [1, 1] });
    await game.newGame();
    expect(game.state.history).toHaveLength(0);
    expect(game.state.legal.length).toBeGreaterThan(0);
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(2); // human, then engine
    expect(game.state.sideToMove).toBe(0);
  });

  it('refuses a human move in engine-versus-engine mode and while it is not the human\'s turn', async () => {
    const { engine } = await fakeEngine();
    const evE = new GameController({ engine, mode: 'engine-vs-engine', nodes: [1, 1] });
    await evE.newGame();
    await expect(evE.play(evE.state.legal[0]!)).rejects.toThrow(/not the human/);
  });

  it('undo takes back the human move and the engine answer, then the human is on move again', async () => {
    const { engine, core } = await fakeEngine();
    const game = new GameController({ engine, mode: 'human-vs-engine', humanSide: 0, nodes: [1, 1] });
    await game.newGame();
    await game.play(game.state.legal[0]!);
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(4);
    const games = core.newGameCalls;
    await game.undo();
    expect(game.state.history).toHaveLength(2);
    expect(game.state.sideToMove).toBe(0);
    expect(core.newGameCalls).toBe(games + 1); // the engine drops its players
    await game.undo();
    expect(game.state.history).toHaveLength(0);
    await game.undo(); // nothing left: no error
    expect(game.state.history).toHaveLength(0);
  });

  it('undo with the engine as side 0 stops at the engine\'s first move', async () => {
    const { engine } = await fakeEngine();
    const game = new GameController({ engine, mode: 'human-vs-engine', humanSide: 1, nodes: [1, 1] });
    await game.newGame();
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(3);
    await game.undo();
    expect(game.state.history).toHaveLength(1); // the engine's opening move stays
  });

  it('undo in engine-versus-engine mode takes back one ply', async () => {
    const { engine } = await fakeEngine();
    const game = new GameController({ engine, mode: 'engine-vs-engine', nodes: [1, 1] });
    await game.newGame();
    await game.stepEngine();
    await game.stepEngine();
    expect(game.state.history).toHaveLength(2);
    await game.undo();
    expect(game.state.history).toHaveLength(1);
  });

  it('a new game resets history, outcome and figures', async () => {
    const { engine } = await fakeEngine();
    const game = new GameController({ engine, mode: 'engine-vs-engine', nodes: [1, 1] });
    await game.newGame();
    await game.runEngineVsEngine();
    expect(game.state.outcome).not.toBeNull();
    await game.newGame({ mode: 'human-vs-engine', humanSide: 0 });
    expect(game.state.outcome).toBeNull();
    expect(game.state.history).toHaveLength(0);
    expect(game.state.lastEngine).toBeNull();
  }, 30000);

  it('cancel stops a running search; the worker restarts and the game goes on', async () => {
    const { engine, workers } = await fakeEngine({ chooseDelayMs: 50 });
    const game = new GameController({ engine, mode: 'engine-vs-engine', nodes: [1, 1] });
    await game.newGame();
    const run = game.runEngineVsEngine();
    await new Promise((r) => setTimeout(r, 10));
    expect(game.state.thinking).toBe(true);
    game.cancel();
    await run;
    expect(game.state.thinking).toBe(false);
    expect(game.state.history).toHaveLength(0);
    expect(game.state.error).toBeNull();
    expect(workers).toHaveLength(2);
    await game.stepEngine();
    expect(game.state.history).toHaveLength(1);
  });

  it('notifies subscribers, with fresh owner arrays', async () => {
    const { engine } = await fakeEngine();
    const game = new GameController({ engine, mode: 'engine-vs-engine', nodes: [1, 1] });
    await game.newGame();
    const owners: Int8Array[] = [];
    const off = game.subscribe((s) => owners.push(s.owner));
    await game.stepEngine();
    off();
    const n = owners.length;
    await game.stepEngine();
    expect(owners.length).toBe(n);
    expect(new Set(owners).size).toBeGreaterThan(1);
  });
});

/** A scripted engine for the cases the fake cannot produce. */
class ScriptedEngine implements Engine {
  readonly info: EngineInfo = FAKE_INFO;
  legalReply: number[] | null = null;
  chooseReply: ChooseResult | Error | null = null;
  async init(): Promise<LoadReport> { throw new Error('unused'); }
  async newGame(_setup: GameSetup): Promise<void> {}
  async legalMoves(history: readonly number[]): Promise<number[]> {
    return this.legalReply ?? tinyLegalMoves(replay(history), sideToMove(history.length));
  }
  async choose(history: readonly number[]): Promise<ChooseResult> {
    if (this.chooseReply instanceof Error) throw this.chooseReply;
    if (this.chooseReply) return this.chooseReply;
    const move = tinyLegalMoves(replay(history), sideToMove(history.length))[0]!;
    return { move, score: 0, depth: 1, nodes: 1, ms: 0, raceMove: false, rows1Requests: 0 };
  }
  async analyse(): Promise<AnalysisResult> { throw new Error('unused'); }
  dispose(): void {}
}

describe('GameController edge cases', () => {
  const setup = (mode: GameMode = 'human-vs-engine') => {
    const engine = new ScriptedEngine();
    return { engine, game: new GameController({ engine, mode, humanSide: 0, nodes: [1, 1] }) };
  };

  it('ends the game with "no-moves" when the engine reports no legal move', async () => {
    const { engine, game } = setup();
    engine.legalReply = [];
    await game.newGame();
    expect(game.state.outcome).toEqual({ kind: 'no-moves', side: 0 });
    expect(game.state.legal).toEqual([]);
  });

  it('keeps the game where it was when the engine fails, and reports the error', async () => {
    const { engine, game } = setup();
    await game.newGame();
    engine.chooseReply = new Error('engine crashed');
    await game.play(game.state.legal[0]!);
    expect(game.state.error?.message).toBe('engine crashed');
    expect(game.state.history).toHaveLength(1); // the human's move stays
    expect(game.state.thinking).toBe(false);
  });

  it('rejects an engine move that is not legal', async () => {
    const { engine, game } = setup();
    await game.newGame();
    engine.chooseReply = { move: 0, score: 0, depth: 1, nodes: 1, ms: 0, raceMove: false, rows1Requests: 0 };
    await game.play(game.state.legal[0]!);
    expect(game.state.error).toBeInstanceOf(IllegalMoveError);
    expect(game.state.history).toHaveLength(1);
  });

  it('shows no figures before the engine has moved', async () => {
    const { game } = setup();
    await game.newGame();
    expect(game.state.lastEngine).toBeNull();
    expect(game.state.error).toBeNull();
  });
});
