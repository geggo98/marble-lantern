// "Play on for 2nd place": GameController.continuePlay, the ranking outcome, undo across the win. A scripted engine decides the
// verdicts (the unit part); a whole game on the real C++ engine goes through a win, continuePlay and the final ranking.
import { describe, expect, it } from 'vitest';
import { existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import type { AnalysisResult, ChooseResult, Engine, EngineInfo, EngineOutcome, GameSetup, LoadReport } from '../engines/engine';
import { FAKE_INFO } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { CppCore } from '../engines/cpp/core';
import type { CppModuleFactory } from '../engines/cpp/core';
import { PLAY_ON_WIN } from '../../e2e/fixtures';
import { decodeMoves } from '../app/game-url';
import { playState, replay } from './board-state';
import { GameController } from './game';
import type { ControllerSeat } from './game';
import type { Variant } from './variant';

const THREE: Variant = { jump: 'adjacent', players: 3 };
const THREE_FULL: Variant = { jump: 'adjacent', players: 3, ranking: 'full' };

/** An engine that answers as scripted; the legal moves come from the tiny generator, for the seat `ply % players`. */
class ScriptedEngine implements Engine {
  readonly info: EngineInfo = FAKE_INFO;
  verdict: EngineOutcome = { kind: 'ongoing' };
  setups: GameSetup[] = [];
  async init(): Promise<LoadReport> { throw new Error('unused'); }
  async newGame(setup: GameSetup): Promise<void> { this.setups.push(setup); }
  async legalMoves(history: readonly number[], variant?: Variant): Promise<number[]> {
    return tinyLegalMoves(replay(history, variant), history.length % (variant?.players ?? 2), variant);
  }
  async choose(): Promise<ChooseResult> { throw new Error('unused'); }
  async analyse(): Promise<AnalysisResult> { throw new Error('unused'); }
  async outcome(): Promise<EngineOutcome> { return this.verdict; }
  dispose(): void {}
}

const scripted = (seats: ControllerSeat[] = ['human', 'human', 'human'], variant: Variant = THREE) => {
  const engine = new ScriptedEngine();
  return { engine, game: new GameController({ engine, seats, variant, nodes: [1, 1, 1], enginePauseMs: 0 }) };
};

describe('canPlayOn', () => {
  it('is true after a win in a three-player game under first-winner rules', async () => {
    const { engine, game } = scripted();
    engine.verdict = { kind: 'win', winner: 1 };
    await game.newGame();
    expect(game.state.outcome).toEqual({ kind: 'win', winner: 1 });
    expect(game.state.canPlayOn).toBe(true);
  });

  it('is false while the game runs, after a draw, and for two players', async () => {
    const { engine, game } = scripted();
    await game.newGame();
    expect(game.state.canPlayOn).toBe(false);
    engine.verdict = { kind: 'draw' };
    await game.newGame();
    expect(game.state.canPlayOn).toBe(false);
    const two = scripted(['human', 'engine'], { jump: 'adjacent', players: 2 });
    two.engine.verdict = { kind: 'win', winner: 1 };
    await two.game.newGame();
    expect(two.game.state.outcome).toEqual({ kind: 'win', winner: 1 });
    expect(two.game.state.canPlayOn).toBe(false);
    await expect(two.game.continuePlay()).rejects.toThrow(/play on/i);
  });

  it('continuePlay refuses while the game runs', async () => {
    const { game } = scripted();
    await game.newGame();
    await expect(game.continuePlay()).rejects.toThrow(/play on/i);
    expect(game.state.variant).toEqual(THREE);
  });
});

describe('continuePlay', () => {
  it('switches to full ranking, keeps history and seats, asks the engine for a new game with the new variant', async () => {
    const { engine, game } = scripted();
    await game.newGame();
    for (let i = 0; i < 3; i++) await game.play(game.state.legal[0]!);
    const history = [...game.state.history];
    engine.verdict = { kind: 'win', winner: 2 };
    await game.loadGame(history);
    expect(game.state.canPlayOn).toBe(true);
    engine.verdict = { kind: 'ongoing', finished: [2] };
    await game.continuePlay();
    expect(game.state.variant).toEqual(THREE_FULL);
    expect(game.state.history).toEqual(history);
    expect(game.state.seats).toEqual(['human', 'human', 'human']);
    expect(game.state.outcome).toBeNull();
    expect(game.state.canPlayOn).toBe(false);
    expect(engine.setups.at(-1)?.variant).toEqual(THREE_FULL);
  });

  it('the controller derives side to move and finished seats from the ranking rule: the seat 0 moves after seat 2 finishes', async () => {
    // a TS replay of the same history says the same (the turn order is playState, tested against the engines in ranking.cross.test.ts)
    const { game } = scripted();
    await game.newGame();
    for (let i = 0; i < 3; i++) await game.play(game.state.legal[0]!);
    expect(game.state.sideToMove).toBe(playState(game.state.history, THREE).sideToMove);
    expect(game.state.finished).toEqual([]);
  });

  it('a ranking verdict ends the game with the ranking: finished seats and ranking are in the state', async () => {
    const { engine, game } = scripted();
    engine.verdict = { kind: 'win', winner: 1 };
    await game.newGame();
    engine.verdict = { kind: 'ranking', ranking: [1, 0, 2] };
    await game.continuePlay();
    expect(game.state.outcome).toEqual({ kind: 'ranking', ranking: [1, 0, 2], reason: 'stalled' });
    expect(game.state.ranking).toEqual([1, 0, 2]);
    expect(game.state.legal).toEqual([]);
    expect(game.state.canPlayOn).toBe(false);
  });

  it('a new game after Play on starts under first-winner rules again', async () => {
    const { engine, game } = scripted();
    engine.verdict = { kind: 'win', winner: 0 };
    await game.newGame();
    engine.verdict = { kind: 'ongoing' };
    await game.continuePlay();
    expect(game.state.variant).toEqual(THREE_FULL);
    await game.newGame();
    expect(game.state.variant).toEqual(THREE);
  });

  it('undo keeps full ranking while the winning move stays, and returns to first-winner rules when it is taken back', async () => {
    const { engine, game } = scripted();
    await game.newGame();
    for (let i = 0; i < 3; i++) await game.play(game.state.legal[0]!); // plies 0 to 2; the win is "at" length 3
    engine.verdict = { kind: 'win', winner: 2 };
    await game.loadGame([...game.state.history]);
    engine.verdict = { kind: 'ongoing' };
    await game.continuePlay();
    await game.play(game.state.legal[0]!); // length 4, played under full ranking
    await game.undo(); // length 3: the winning move is still there
    expect(game.state.history).toHaveLength(3);
    expect(game.state.variant).toEqual(THREE_FULL);
    await game.undo(); // length 2: before the winning move
    expect(game.state.history).toHaveLength(2);
    expect(game.state.variant).toEqual(THREE);
  });
});

// ---- a whole game on the real engine ----

const path = (rel: string): string => fileURLToPath(new URL(`../../public/${rel}`, import.meta.url));
const present = existsSync(path('engines/cpp/engine-cpp.js')) && existsSync(path('engines/cpp/engine-cpp.wasm'));
const cppFactory: CppModuleFactory = async () =>
  ((await import(/* @vite-ignore */ pathToFileURL(path('engines/cpp/engine-cpp.js')).href)) as { default: CppModuleFactory }).default();

/** The C++ core behind the Engine interface. */
async function cppEngine(): Promise<Engine> {
  const core = await CppCore.create(cppFactory);
  return {
    info: FAKE_INFO,
    init: async () => { throw new Error('unused'); },
    newGame: async (setup) => core.newGame(setup),
    legalMoves: async (h, v) => core.legalMoves(h, v),
    choose: async (h, _signal, v) => core.choose(h, v),
    analyse: async () => { throw new Error('unused'); },
    outcome: async (h, v) => core.outcome(h, v),
    dispose: () => {},
  };
}

describe.skipIf(!present)('three engines play on after the first win (C++ engine)', () => {
  it('first-winner rules end with a win; continuePlay plays on to a complete ranking that starts with the winner', async () => {
    const game = new GameController({ engine: await cppEngine(), seats: ['engine', 'engine', 'engine'], variant: THREE, nodes: [60, 80, 100], enginePauseMs: 0 });
    await game.newGame({ mode: 'engine-vs-engine' });
    await game.runEngineVsEngine();
    expect(game.state.outcome?.kind).toBe('win');
    expect(game.state.canPlayOn).toBe(true);
    const winner = (game.state.outcome as { winner: number }).winner;
    const plies = game.state.history.length;

    await game.continuePlay();
    expect(game.state.variant).toEqual(THREE_FULL);
    expect(game.state.outcome?.kind).toBe('ranking');
    expect(game.state.history.length).toBeGreaterThan(plies);
    const ranking = game.state.ranking!;
    expect(ranking[0]).toBe(winner); // the first winner keeps rank 1
    expect([...ranking].sort()).toEqual([0, 1, 2]);
    expect(playState(game.state.history, THREE_FULL).finishers[0]).toBe(winner);
    expect(game.state.history.slice(plies).length).toBeGreaterThan(0);

    // the game link: the moves after the win are legal with r=full, illegal without it, and the position reloads to the same end
    const history = [...game.state.history];
    const seats: ControllerSeat[] = ['human', 'engine', 'engine'];
    await expect(game.loadGame(history, { variant: THREE, seats })).rejects.toBeDefined();
    expect(game.state.variant).toEqual(THREE_FULL); // refused: the game stays as it was
    await game.loadGame(history, { variant: THREE_FULL, seats });
    expect(game.state.outcome).toEqual({ kind: 'ranking', ranking, reason: 'complete' });
    await game.loadGame(history.slice(0, plies), { variant: THREE, seats });
    expect(game.state.outcome).toEqual({ kind: 'win', winner });
  }, 300_000);

  it('the browser fixture PLAY_ON_WIN is what the engine plays: a win of seat 2 at ply 132, then the ranking 2, 1, 0 after 142 plies', async () => {
    const moves = decodeMoves(PLAY_ON_WIN.moves, 300);
    const game = new GameController({ engine: await cppEngine(), seats: ['human', 'engine', 'engine'], variant: THREE, nodes: [10, 10, 10], enginePauseMs: 0 });
    await game.loadGame(moves, { variant: THREE, seats: ['human', 'engine', 'engine'] });
    expect(game.state.outcome).toEqual({ kind: 'win', winner: PLAY_ON_WIN.winner });
    expect(game.state.canPlayOn).toBe(true);
    // the human (seat 0) is next after the winner: the game waits for it; the engines were not asked
    await game.continuePlay();
    expect(game.state.sideToMove).toBe(0);
    expect(game.state.finished).toEqual([PLAY_ON_WIN.winner]);
    expect(game.isHumanTurn()).toBe(true);
    // the same game with engines on the two seats that are left reaches the ranking of the fixture
    const demo = new GameController({ engine: await cppEngine(), seats: ['engine', 'engine', 'human'], variant: THREE, nodes: [10, 10, 10], enginePauseMs: 0 });
    await demo.loadGame(moves, { variant: THREE, seats: ['engine', 'engine', 'human'] });
    await demo.continuePlay();
    expect(demo.state.history).toHaveLength(PLAY_ON_WIN.endPlies);
    expect(demo.state.ranking).toEqual(PLAY_ON_WIN.ranking);
  }, 300_000);

  it('a human seat that is out is skipped: the engines go on without waiting for it', async () => {
    const game = new GameController({ engine: await cppEngine(), seats: ['engine', 'engine', 'engine'], variant: THREE, nodes: [60, 80, 100], enginePauseMs: 0 });
    await game.newGame({ mode: 'engine-vs-engine' });
    await game.runEngineVsEngine();
    const winner = (game.state.outcome as { winner: number }).winner;
    // seat of the winner is a human in a hot-seat-like setting: it never gets a turn after the win
    await game.swapSides(winner as 0 | 1 | 2);
    expect(game.state.seats[winner]).toBe('human');
    await game.continuePlay();
    expect(game.state.history.length).toBeGreaterThan(0);
    expect(game.state.outcome?.kind).toBe('ranking');
    expect(game.isHumanTurn()).toBe(false);
  }, 300_000);
});
