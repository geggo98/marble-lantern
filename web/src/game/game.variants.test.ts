// The game controller with three seats, hot-seat, two engines in a row, undo, swap and the end of the game, for the variants
// of docs/web-ui.md "Game variants". Run against the fake engine (real worker protocol, in-process) and a scripted engine.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkerEngine } from '../engines/client';
import type { AnalysisResult, ChooseResult, Engine, EngineInfo, EngineOutcome, GameSetup, LoadReport } from '../engines/engine';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { makeMove } from '../engines/engine';
import { cellId } from '../board/cells';
import { replay } from './board-state';
import { GameController } from './game';
import type { ControllerSeat } from './game';
import type { Variant } from './variant';

const assets = { engine: [], manifest: '', tableGz: '' };
const THREE: Variant = { jump: 'adjacent', players: 3 };
const THREE_LONG: Variant = { jump: 'long', players: 3 };

async function fakeEngine() {
  const core = new FakeEngineCore(5);
  const engine = new WorkerEngine({ info: FAKE_INFO, assets, createWorker: () => new FakeWorker(core) });
  await engine.init();
  return engine;
}

const controller = async (seats: ControllerSeat[], variant: Variant = THREE, enginePauseMs = 0) =>
  new GameController({ engine: await fakeEngine(), seats, variant, nodes: [50, 50, 50], enginePauseMs });

afterEach(() => {
  vi.useRealTimers();
});

describe('three seats: one human, two engines', () => {
  it('waits for the human on seat 0, then both engines answer in turn', async () => {
    const game = await controller(['human', 'engine', 'engine']);
    await game.newGame();
    expect(game.state.history).toHaveLength(0);
    expect(game.isHumanTurn()).toBe(true);
    expect(game.state.owner.filter((x) => x >= 0)).toHaveLength(30);
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(3);
    expect(game.state.sideToMove).toBe(0);
    expect(game.state.lastEngine?.side).toBe(2);
    expect(game.isHumanTurn()).toBe(true);
    // every move was legal for its seat by the independent generator
    const owner = replay([], THREE);
    game.state.history.forEach((move, ply) => {
      const side = ply % 3;
      expect(tinyLegalMoves(owner, side, THREE)).toContain(move);
      owner[move & 0xff] = -1;
      owner[move >>> 8] = side;
    });
  });

  it('lets the engines open when the human sits on seat 2', async () => {
    const game = await controller(['engine', 'engine', 'human']);
    await game.newGame();
    expect(game.state.history).toHaveLength(2);
    expect(game.state.sideToMove).toBe(2);
    await expect(game.play(0)).rejects.toThrow(/not a legal|illegal/i);
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(5);
  });

  it('refuses a move when an engine is on turn and an illegal move on the human turn', async () => {
    const game = await controller(['human', 'engine', 'engine']);
    await game.newGame();
    await expect(game.play(makeMove(0, 1))).rejects.toThrow();
    const turnOfEngine = await controller(['engine', 'human', 'engine']);
    await turnOfEngine.newGame(); // the engine of seat 0 moves, then the human is to move
    expect(turnOfEngine.state.history).toHaveLength(1);
  });

  it('pauses between the moves of two engines in a row, and cancel stops the pause', async () => {
    vi.useFakeTimers();
    const game = await controller(['human', 'engine', 'engine'], THREE, 350);
    await game.newGame();
    const first = game.state.legal[0]!;
    const playing = game.play(first);
    await vi.advanceTimersByTimeAsync(0);
    expect(game.state.history).toHaveLength(2); // the human and the engine of seat 1
    await vi.advanceTimersByTimeAsync(349);
    expect(game.state.history).toHaveLength(2); // still waiting before the engine of seat 2
    await vi.advanceTimersByTimeAsync(1);
    await playing;
    expect(game.state.history).toHaveLength(3);

    // a second round: cancel during the pause leaves the game after the first engine move
    await game.newGame();
    const again = game.play(game.state.legal[0]!);
    await vi.advanceTimersByTimeAsync(0);
    expect(game.state.history).toHaveLength(2);
    game.cancel();
    await again;
    expect(game.state.history).toHaveLength(2);
    expect(game.state.thinking).toBe(false);
  });

  it('uses no pause by default for two players, and 350 ms for three', async () => {
    vi.useFakeTimers();
    const two = new GameController({ engine: await fakeEngine(), mode: 'engine-vs-engine', nodes: [10, 10] });
    await two.newGame();
    const run = two.runEngineVsEngine();
    await vi.advanceTimersByTimeAsync(0);
    await run; // would never finish under fake timers if a pause existed
    expect(two.state.outcome).not.toBeNull();
    const three = new GameController({ engine: await fakeEngine(), seats: ['human', 'engine', 'engine'], variant: THREE, nodes: [10, 10, 10] });
    await three.newGame();
    const p = three.play(three.state.legal[0]!);
    await vi.advanceTimersByTimeAsync(0);
    expect(three.state.history).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(350);
    await p;
    expect(three.state.history).toHaveLength(3);
  });
});

describe('hot-seat: two humans and one engine', () => {
  it('passes the move from one human to the next without the engine, then the engine plays', async () => {
    const game = await controller(['human', 'human', 'engine']);
    await game.newGame();
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(1);
    expect(game.isHumanTurn()).toBe(true);
    expect(game.state.sideToMove).toBe(1);
    expect(game.state.thinking).toBe(false);
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(3); // the engine answered on seat 2
    expect(game.state.lastEngine?.side).toBe(2);
    expect(game.state.sideToMove).toBe(0);
  });

  it('is not swappable: canSwap is false and swapSides refuses', async () => {
    const game = await controller(['human', 'human', 'engine']);
    await game.newGame();
    expect(game.state.canSwap).toBe(false);
    await expect(game.swapSides()).rejects.toThrow(/two human/);
  });
});

describe('undo', () => {
  it('one human, two engines: takes back the human move and both engine answers', async () => {
    const game = await controller(['human', 'engine', 'engine']);
    await game.newGame();
    await game.play(game.state.legal[0]!);
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(6);
    await game.undo();
    expect(game.state.history).toHaveLength(3);
    expect(game.isHumanTurn()).toBe(true);
    await game.undo();
    expect(game.state.history).toHaveLength(0);
    await game.undo(); // nothing to take back
    expect(game.state.history).toHaveLength(0);
  });

  it('human on seat 2: goes back to the position where seat 2 is to move again', async () => {
    const game = await controller(['engine', 'engine', 'human']);
    await game.newGame(); // engines: plies 0 and 1
    await game.play(game.state.legal[0]!); // ply 2, then engines 3 and 4
    expect(game.state.history).toHaveLength(5);
    await game.undo();
    expect(game.state.history).toHaveLength(2);
    expect(game.state.sideToMove).toBe(2);
  });

  it('hot-seat: one ply when the previous seat was human; the engine move goes with the human move before it', async () => {
    const game = await controller(['human', 'human', 'engine']);
    await game.newGame();
    await game.play(game.state.legal[0]!); // seat 0
    await game.play(game.state.legal[0]!); // seat 1, engine answers: 3 plies, seat 0 to move
    await game.play(game.state.legal[0]!); // seat 0: 4 plies, seat 1 to move
    expect(game.state.history).toHaveLength(4);
    await game.undo();
    expect(game.state.history).toHaveLength(3); // one ply: seat 0 is a human seat
    await game.undo();
    expect(game.state.history).toHaveLength(1); // engine move and seat 1's move: seat 1 is to move
    expect(game.state.sideToMove).toBe(1);
  });

  it('two players with one human is unchanged: human move and engine answer', async () => {
    const game = new GameController({ engine: await fakeEngine(), mode: 'human-vs-engine', humanSide: 0, nodes: [10, 10] });
    await game.newGame();
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(2);
    await game.undo();
    expect(game.state.history).toHaveLength(0);
  });

  it('a demo takes back one move', async () => {
    const game = await controller(['engine', 'engine', 'engine']);
    await game.newGame();
    await game.stepEngine();
    await game.stepEngine();
    expect(game.state.history).toHaveLength(2);
    await game.undo();
    expect(game.state.history).toHaveLength(1);
  });
});

describe('swapSides', () => {
  it('three players, one human: the human seat rotates to the next seat and the board stays', async () => {
    const game = await controller(['human', 'engine', 'engine']);
    await game.newGame();
    await game.play(game.state.legal[0]!);
    expect(game.state.canSwap).toBe(true);
    await game.swapSides();
    expect(game.state.seats).toEqual(['engine', 'human', 'engine']);
    // seat 0 is to move after 3 plies: its engine plays, then the human of seat 1 is on turn
    expect(game.state.history).toHaveLength(4);
    expect(game.state.sideToMove).toBe(1);
    expect(game.isHumanTurn()).toBe(true);
    await game.swapSides(2);
    expect(game.state.seats).toEqual(['engine', 'engine', 'human']);
    expect(game.state.history).toHaveLength(5); // the engine of seat 1 moved; seat 2 is the human
    expect(game.isHumanTurn()).toBe(true);
  });

  it('three players: the rotation wraps from the last seat to the first', async () => {
    const game = await controller(['engine', 'engine', 'human']);
    await game.newGame();
    await game.swapSides();
    expect(game.state.seats).toEqual(['human', 'engine', 'engine']);
    expect(game.state.history).toHaveLength(3); // two plies were played; the engine that now has seat 2 moves, then seat 0 (the human) is on turn
    expect(game.state.sideToMove).toBe(0);
    expect(game.isHumanTurn()).toBe(true);
  });

  it('two players: the human takes the other side, as before', async () => {
    const game = new GameController({ engine: await fakeEngine(), mode: 'human-vs-engine', humanSide: 0, nodes: [10, 10] });
    await game.newGame();
    await game.play(game.state.legal[0]!);
    await game.swapSides(1);
    expect(game.state.seats).toEqual(['engine', 'human']);
    expect(game.state.history).toHaveLength(3); // the engine played side 0
    expect(game.state.canSwap).toBe(true);
    await game.swapSides();
    expect(game.state.seats).toEqual(['human', 'engine']);
  });

  it('rejects a seat that does not exist', async () => {
    const game = new GameController({ engine: await fakeEngine(), mode: 'human-vs-engine', humanSide: 0, nodes: [10, 10] });
    await game.newGame();
    await expect(game.swapSides(2)).rejects.toThrow(RangeError);
  });
});

describe('variants in newGame and loadGame', () => {
  it('starts a three-player game from a two-player controller and back', async () => {
    const game = new GameController({ engine: await fakeEngine(), mode: 'human-vs-engine', humanSide: 0, nodes: [10, 10] });
    await game.newGame();
    expect(game.state.variant).toEqual({ jump: 'adjacent', players: 2 });
    await game.newGame({ variant: THREE, seats: ['human', 'engine', 'engine'], nodes: [10, 10, 10] });
    expect(game.state.variant).toEqual(THREE);
    expect(game.state.owner.filter((x) => x === 2)).toHaveLength(10);
    expect(game.state.seats).toEqual(['human', 'engine', 'engine']);
    await game.newGame({ variant: { jump: 'adjacent', players: 2 }, humanSide: 1 });
    expect(game.state.owner.filter((x) => x === 2)).toHaveLength(0);
    expect(game.state.history).toHaveLength(1); // the engine of side 0 opens
  });

  it('refuses seats that do not fit the player count', async () => {
    const game = await controller(['human', 'engine', 'engine']);
    await expect(game.newGame({ seats: ['human', 'engine'] })).rejects.toThrow(RangeError);
    expect(() => new GameController({ engine: {} as Engine, seats: ['human', 'engine'], variant: THREE, nodes: [1] })).toThrow(RangeError);
  });

  it('plays long-hop two-player games: the engine gets the variant', async () => {
    const game = new GameController({ engine: await fakeEngine(), seats: ['human', 'engine'], variant: { jump: 'long', players: 2 }, nodes: [10, 10] });
    await game.newGame();
    await game.play(game.state.legal[0]!);
    expect(game.state.history).toHaveLength(2);
    expect(game.state.variant.jump).toBe('long');
  });

  it('loadGame takes a variant and seats, and keeps the game when the history is illegal for that variant', async () => {
    const game = await controller(['human', 'engine', 'engine']);
    await game.newGame();
    await game.play(game.state.legal[0]!);
    const history = [...game.state.history];
    await game.loadGame(history.slice(0, 3), { variant: THREE, seats: ['engine', 'human', 'engine'] });
    expect(game.state.seats).toEqual(['engine', 'human', 'engine']);
    // three plies are done; seat 0 (an engine) is on turn and plays, then the human of seat 1 is to move
    expect(game.state.history).toHaveLength(4);
    // the same moves are not a three-player history of another start layout: a two-player replay refuses them
    await expect(game.loadGame(history, { variant: { jump: 'adjacent', players: 2 }, humanSide: 0 })).rejects.toBeDefined();
    expect(game.state.variant).toEqual(THREE);
    expect(game.state.history).toHaveLength(4);
  });
});

/** An engine that answers as scripted and records the variants it is asked about. */
class ScriptedEngine implements Engine {
  readonly info: EngineInfo = FAKE_INFO;
  verdict: EngineOutcome = { kind: 'ongoing' };
  legalReply: number[] | null = null;
  variants: (Variant | undefined)[] = [];
  setups: GameSetup[] = [];
  async init(): Promise<LoadReport> { throw new Error('unused'); }
  async newGame(setup: GameSetup): Promise<void> { this.setups.push(setup); }
  async legalMoves(history: readonly number[], variant?: Variant): Promise<number[]> {
    this.variants.push(variant);
    return this.legalReply ?? tinyLegalMoves(replay(history, variant), history.length % (variant?.players ?? 2), variant);
  }
  async choose(): Promise<ChooseResult> { throw new Error('unused'); }
  async analyse(): Promise<AnalysisResult> { throw new Error('unused'); }
  async outcome(_history: readonly number[], variant?: Variant): Promise<EngineOutcome> {
    this.variants.push(variant);
    return this.verdict;
  }
  dispose(): void {}
}

describe('the end of the game', () => {
  const scripted = (variant: Variant = THREE) => {
    const engine = new ScriptedEngine();
    return { engine, game: new GameController({ engine, seats: variant.players === 3 ? ['human', 'human', 'human'] : ['human', 'engine'], variant, nodes: [1, 1, 1] }) };
  };

  it('a win reported by the engine names the seat, here the third player', async () => {
    const { engine, game } = scripted();
    engine.verdict = { kind: 'win', winner: 2 };
    await game.newGame();
    expect(game.state.outcome).toEqual({ kind: 'win', winner: 2 });
    expect(game.state.legal).toEqual([]);
    expect(game.isHumanTurn()).toBe(false);
  });

  it('a draw without a legal-move problem is a draw (repetition)', async () => {
    const { engine, game } = scripted();
    engine.verdict = { kind: 'draw' };
    await game.newGame();
    expect(game.state.outcome).toEqual({ kind: 'draw' });
  });

  it('a draw with no legal move is "no-moves" for the side to move', async () => {
    const { engine, game } = scripted();
    engine.verdict = { kind: 'draw' };
    engine.legalReply = [];
    await game.newGame();
    expect(game.state.outcome).toEqual({ kind: 'no-moves', side: 0 });
  });

  it('a game at the move limit is a draw: 300 plies for three players, 200 for two', async () => {
    // one piece per seat shuffles between two cells; the engine is scripted, so only the board has to be physically possible
    const shuffle = (players: number, plies: number): number[] => {
      const pairs = players === 3
        ? [[cellId(1, -5), cellId(0, -4)], [cellId(4, 1), cellId(3, 1)], [cellId(-5, 1), cellId(-4, 1)]]
        : [[cellId(1, -5), cellId(0, -4)], [cellId(-1, 5), cellId(0, 4)]];
      const moves: number[] = [];
      const at = pairs.map((p) => p[0]!);
      for (let ply = 0; ply < plies; ply++) {
        const seat = ply % players;
        const [a, b] = pairs[seat]!;
        const from = at[seat]!;
        const to = from === a ? b! : a!;
        moves.push(makeMove(from, to));
        at[seat] = to;
      }
      return moves;
    };
    const three = scripted();
    const history3 = shuffle(3, 300);
    expect(() => replay(history3, THREE)).not.toThrow();
    await three.game.loadGame(history3.slice(0, 299), { variant: THREE, seats: ['human', 'human', 'human'] });
    expect(three.game.state.outcome).toBeNull();
    await three.game.loadGame(history3, { variant: THREE, seats: ['human', 'human', 'human'] });
    expect(three.game.state.outcome).toEqual({ kind: 'move-limit' });

    const two = scripted({ jump: 'adjacent', players: 2 });
    const history2 = shuffle(2, 200);
    await two.game.loadGame(history2.slice(0, 199), { humanSide: 0 });
    expect(two.game.state.outcome).toBeNull();
    await two.game.loadGame(history2, { humanSide: 0 });
    expect(two.game.state.outcome).toEqual({ kind: 'move-limit' });
  });

  it('passes the variant to every engine call', async () => {
    const { engine, game } = scripted(THREE_LONG);
    await game.newGame();
    await game.newGame({ variant: THREE_LONG, seats: ['human', 'human', 'human'] });
    expect(engine.setups.every((s) => s.variant?.jump === 'long' && s.variant.players === 3)).toBe(true);
    expect(engine.variants.length).toBeGreaterThan(0);
    expect(engine.variants.every((v) => v?.jump === 'long' && v.players === 3)).toBe(true);
  });
});
