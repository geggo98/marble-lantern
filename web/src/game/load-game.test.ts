import { describe, expect, it } from 'vitest';
import { WorkerEngine } from '../engines/client';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from '../engines/testing/fake-engine';
import { GameController } from './game';

async function fakeEngine(options: { chooseDelayMs?: number } = {}) {
  const core = new FakeEngineCore(3);
  const engine = new WorkerEngine({
    info: FAKE_INFO,
    assets: { engine: [], manifest: '', tableGz: '' },
    createWorker: () => new FakeWorker(core, options),
  });
  await engine.init();
  return engine;
}

async function playedMoves(plies: number): Promise<number[]> {
  const game = new GameController({ engine: await fakeEngine(), mode: 'human-vs-engine', humanSide: 0, nodes: [100, 100] });
  await game.newGame();
  while (game.state.history.length < plies) await game.play(game.state.legal[0]!);
  return [...game.state.history];
}

describe('GameController.loadGame', () => {
  it('replaces the game by a position, with the legal moves of that position', async () => {
    const moves = await playedMoves(4);
    const game = new GameController({ engine: await fakeEngine(), mode: 'human-vs-engine', humanSide: 1, nodes: [100, 100] });
    await game.newGame();
    await game.loadGame(moves.slice(0, 2), { humanSide: 0 });
    expect(game.state.history).toEqual(moves.slice(0, 2));
    expect(game.state.sideToMove).toBe(0);
    expect(game.state.legal.length).toBeGreaterThan(0);
    expect(game.state.thinking).toBe(false);
  });

  it('lets the engine continue when it is on move after the load, and waits when the human is', async () => {
    const moves = await playedMoves(4);
    const game = new GameController({ engine: await fakeEngine(), mode: 'human-vs-engine', humanSide: 0, nodes: [100, 100] });
    await game.newGame();
    await game.loadGame(moves.slice(0, 3), { humanSide: 1 }); // side 1 is on move and is the human
    expect(game.state.history).toHaveLength(3);
    await game.loadGame(moves.slice(0, 2), { humanSide: 1 }); // side 0 is on move and is the engine: it plays at once
    expect(game.state.history).toHaveLength(3);
    expect(game.state.sideToMove).toBe(1);
  });

  it('cancels a running search and drops its answer', async () => {
    const game = new GameController({ engine: await fakeEngine({ chooseDelayMs: 80 }), mode: 'human-vs-engine', humanSide: 0, nodes: [100, 100] });
    await game.newGame();
    const turn = game.play(game.state.legal[0]!);
    await new Promise((r) => setTimeout(r, 15));
    expect(game.state.thinking).toBe(true);
    await game.loadGame([], { humanSide: 0 });
    await turn;
    expect(game.state.history).toEqual([]);
    expect(game.state.thinking).toBe(false);
  });

  it('rejects an illegal history and leaves the game as it was', async () => {
    const game = new GameController({ engine: await fakeEngine(), mode: 'human-vs-engine', humanSide: 0, nodes: [100, 100] });
    await game.newGame();
    await game.play(game.state.legal[0]!);
    const before = [...game.state.history];
    await expect(game.loadGame([0 | (1 << 8)])).rejects.toMatchObject({ code: 'illegal-history' }); // cell 0 holds no piece
    expect(game.state.history).toEqual(before);
  });
});
