package sternhalma.search.contract;

import java.util.Arrays;

import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.search.GameRunner;
import sternhalma.search.Player;
import sternhalma.search.RandomPlayer;
import sternhalma.search.Strategy;

/**
 * The two checks that every strategy must pass. They throw {@link AssertionError} with enough context to
 * rebuild the failing case. They are plain methods so that tests can also apply them to deliberately broken
 * strategies and prove that the checks fail when they should.
 */
public final class StrategyChecks {

    /**
     * Proven minimum without the blockade rule ({@code BlockadeRule.NONE}): the goal must then hold 10 own
     * pieces, so the winner vacates all 10 cells of its start corner, and every move vacates one cell. The winner
     * needs 10 moves. If it moves first, its tenth move is ply 19; if second, ply 20.
     */
    public static final int PROVEN_MIN_PLIES_WITHOUT_BLOCKADE_RULE = 2 * Position.PIECES - 1;

    /**
     * Sanity floor with Definition 1 ({@code OPPONENT_FILLS}). Opponent pieces in the goal count as filling it, so
     * a passive opponent lets the winner fill a single hole and the proof above does not hold. This floor is a
     * project value, not a theorem: games against a random player last at least 28 plies from the default start and
     * at least 15 plies from random openings (measured, 800 games per strategy). A game shorter than the floor means
     * that the win detection or the move generation is broken.
     */
    public static final int SANITY_MIN_PLIES_WITH_BLOCKADE_RULE = 8;

    /** The fewest plies after which a game under these rules may be won. */
    public static int minPliesOfAWonGame(sternhalma.engine.RuleSet rules) {
        return rules.blockadeRule() == sternhalma.engine.RuleSet.BlockadeRule.NONE
                ? PROVEN_MIN_PLIES_WITHOUT_BLOCKADE_RULE
                : SANITY_MIN_PLIES_WITH_BLOCKADE_RULE;
    }

    private StrategyChecks() {
    }

    /**
     * Contract 1. The strategy plays against a seeded random player, in either colour, and must win. The random
     * player must lose. A won game cannot be shorter than {@link #minPliesOfAWonGame}. If this fails, something
     * is generally broken: the strategy, the engine or the game loop.
     */
    public static void assertBeatsRandom(Strategy strategy, Position start, boolean strategyMovesFirst, long seed) {
        int strategyIndex = strategyMovesFirst ? 0 : 1;
        String context = context(strategy, start, "beats-random, strategy index " + strategyIndex + ", seed " + seed);
        Player mine = strategy.create(seed);
        Player random = new RandomPlayer(seed);
        GameRunner.Result result;
        try {
            result = GameRunner.play(start, strategyMovesFirst ? new Player[] {mine, random} : new Player[] {random, mine});
        } catch (RuntimeException e) {
            throw new AssertionError("technical error or illegal move\n" + context, e);
        }
        Outcome outcome = result.outcome();
        if (!outcome.hasWinner() || outcome.winner() != strategyIndex) {
            throw new AssertionError("the random player did not lose: " + outcome + " after " + result.plies()
                    + " plies\n" + context);
        }
        assertPlausibleLength(result.plies(), start.rules(), context);
    }

    /** A won game with fewer plies than the rules allow is a bug. */
    public static void assertPlausibleLength(int plies, sternhalma.engine.RuleSet rules, String context) {
        int minimum = minPliesOfAWonGame(rules);
        if (plies < minimum) {
            throw new AssertionError("a game was won after only " + plies + " plies; the minimum is " + minimum
                    + "\n" + context);
        }
    }

    /**
     * Contract 2. The strategy plays against a second instance of itself from the given start position. No move
     * may be illegal, nothing may throw, and the position must stay consistent after every move: each player keeps
     * its pieces, the incremental hash equals a rebuilt hash, the ply count rises by one, and the game ends within
     * the move limit with values that sum to zero.
     */
    public static void assertCleanSelfPlay(Strategy strategy, Position start, long seed) {
        String context = context(strategy, start, "self-play, seed " + seed);
        int players = start.rules().players();
        Player[] player = new Player[players];
        for (int i = 0; i < players; i++) {
            player[i] = strategy.create(seed + i);
        }
        int startPly = start.ply();
        long startHash = start.hash();
        Position pos = start.copy();
        int[] pieces = new int[players];
        for (int p = 0; p < players; p++) {
            pieces[p] = pos.pieces(p).length;
        }
        int limit = pos.rules().moveLimit();
        try {
            while (true) {
                Outcome outcome = pos.outcome();
                if (outcome != null) {
                    assertValidOutcome(outcome, players);
                    break;
                }
                int[] legal = pos.legalMoves();
                if (legal.length == 0) {
                    assertValidOutcome(pos.noMovesOutcome(), players);
                    break;
                }
                int mover = pos.sideToMove();
                int move = player[mover].choose(pos);
                if (Arrays.stream(legal).noneMatch(m -> m == move)) {
                    throw new AssertionError("illegal move " + describeMove(move) + " by player " + mover);
                }
                int plyBefore = pos.ply();
                pos.make(move);
                assertConsistent(pos, pieces, plyBefore + 1, limit);
            }
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n" + context, e);
        } catch (RuntimeException e) {
            throw new AssertionError("technical error: " + e + "\n" + context, e);
        }
        if (start.ply() != startPly || start.hash() != startHash) {
            throw new AssertionError("the strategy modified the start position it was given\n" + context);
        }
    }

    private static void assertConsistent(Position pos, int[] pieces, int expectedPly, int limit) {
        if (pos.ply() != expectedPly) {
            throw new AssertionError("ply is " + pos.ply() + ", expected " + expectedPly);
        }
        if (pos.ply() > limit) {
            throw new AssertionError("ply " + pos.ply() + " exceeds the move limit " + limit);
        }
        for (int p = 0; p < pieces.length; p++) {
            if (pos.pieces(p).length != pieces[p]) {
                throw new AssertionError("player " + p + " has " + pos.pieces(p).length + " pieces, expected " + pieces[p]);
            }
        }
        long rebuilt = pos.transformed(c -> c, false).hash();
        if (rebuilt != pos.hash()) {
            throw new AssertionError("incremental hash differs from a rebuilt hash at ply " + pos.ply());
        }
    }

    private static void assertValidOutcome(Outcome outcome, int players) {
        if (outcome.values().length != players) {
            throw new AssertionError("outcome has " + outcome.values().length + " values for " + players + " players");
        }
        double sum = 0;
        for (double v : outcome.values()) {
            if (Double.isNaN(v) || Math.abs(v) > 1.0 + 1e-9) {
                throw new AssertionError("outcome value out of range: " + outcome);
            }
            sum += v;
        }
        if (Math.abs(sum) > 1e-9) {
            throw new AssertionError("outcome values do not sum to zero: " + outcome);
        }
        if (outcome.hasWinner() && (outcome.winner() < 0 || outcome.winner() >= players)) {
            throw new AssertionError("winner " + outcome.winner() + " does not exist");
        }
    }

    private static String describeMove(int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        return from < Geometry.CELLS && to < Geometry.CELLS ? Move.toString(move) : "raw value " + move;
    }

    private static String context(Strategy strategy, Position start, String what) {
        return "strategy: " + strategy.name() + "\ncheck: " + what + "\nstart position:\n"
                + PositionGenerator.describe(start) + "rules: " + start.rules();
    }
}
