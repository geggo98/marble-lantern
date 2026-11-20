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

    /**
     * The longest run of own moves without a new best distance sum that a competent strategy may show against a
     * random player. Measured over 600 games per strategy (default start): a search with 1000 nodes per move shows at
     * most 11 (99th percentile 7), with 10000 nodes at most 6; a search with only 100 nodes shows up to 56 and loses
     * 30 percent of its games, and a random player has a median of 26. The bound is far above the competent
     * strategies and clearly below the weak ones. It replaces a fixed maximum game length, which would only encode the
     * strength of today's strategies.
     */
    public static final int MAX_OWN_MOVES_WITHOUT_PROGRESS = 20;

    /** How often one position may occur in a game: competent strategies reach 2, the weak one 3 (measured). */
    public static final int MAX_POSITION_OCCURRENCES = 3;

    /** The fewest plies after which a game under these rules may be won. */
    public static int minPliesOfAWonGame(sternhalma.engine.RuleSet rules) {
        // the proof above counts the pieces of a house: 10 on the 4-row board, 15 on the 5-row board
        int pieces = sternhalma.engine.Layout.of(rules.players(), rules.triangleRows()).pieces();
        return rules.blockadeRule() == sternhalma.engine.RuleSet.BlockadeRule.NONE
                ? 2 * pieces - 1
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
        Player[] players = strategyMovesFirst ? new Player[] {mine, random} : new Player[] {random, mine};
        PlayedGame game;
        try {
            game = play(start, players, strategyIndex);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n" + context, e);
        } catch (RuntimeException e) {
            throw new AssertionError("technical error: " + e + "\n" + context, e);
        }
        if (game.longestStall() > MAX_OWN_MOVES_WITHOUT_PROGRESS) {
            throw new AssertionError("the strategy stalled: " + game.longestStall() + " own moves in a row without a new best"
                    + " distance sum (limit " + MAX_OWN_MOVES_WITHOUT_PROGRESS + ")\n" + context);
        }
        if (game.maxOccurrences() > MAX_POSITION_OCCURRENCES) {
            throw new AssertionError("one position occurred " + game.maxOccurrences() + " times (limit "
                    + MAX_POSITION_OCCURRENCES + "): the strategy oscillates\n" + context);
        }
        Outcome outcome = game.outcome();
        if (!outcome.hasWinner() || outcome.winner() != strategyIndex) {
            throw new AssertionError("the random player did not lose: " + outcome + " after " + game.plies()
                    + " plies\n" + context);
        }
        assertPlausibleLength(game.plies(), start.rules(), context);
    }

    /** A finished game with what the checks need to know about it. */
    private record PlayedGame(Outcome outcome, int plies, int longestStall, int maxOccurrences) {
    }

    /**
     * Plays a game, rejects illegal moves, and records how long player {@code observed} went without a new best
     * distance sum and how often one position occurred.
     */
    private static PlayedGame play(Position start, Player[] players, int observed) {
        Position pos = start.copy();
        int best = Integer.MAX_VALUE;
        int since = 0;
        int stall = 0;
        java.util.Map<Long, Integer> occurrences = new java.util.HashMap<>();
        int maxOccurrences = 1;
        while (true) {
            Outcome outcome = pos.outcome();
            if (outcome == null && pos.legalMoves().length == 0) {
                outcome = pos.noMovesOutcome();
            }
            if (outcome != null) {
                return new PlayedGame(outcome, pos.ply(), stall, maxOccurrences);
            }
            int mover = pos.sideToMove();
            int move = players[mover].choose(pos);
            if (Arrays.stream(pos.legalMoves()).noneMatch(m -> m == move)) {
                throw new AssertionError("illegal move " + describeMove(move) + " by player " + mover);
            }
            pos.make(move);
            maxOccurrences = Math.max(maxOccurrences, occurrences.merge(pos.hash(), 1, Integer::sum));
            if (mover == observed) {
                int distance = pos.distanceSum(observed);
                if (distance < best) {
                    best = distance;
                    since = 0;
                } else {
                    stall = Math.max(stall, ++since);
                }
            }
        }
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
