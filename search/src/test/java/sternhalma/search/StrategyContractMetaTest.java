package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.contract.PositionGenerator;
import sternhalma.search.contract.StrategyChecks;

/**
 * Tests of the contract checks themselves: each deliberately broken strategy must make a check fail, and the
 * start position generator must deliver the variety that the contract promises.
 */
class StrategyContractMetaTest {

    private static final Position START = Position.initial(RuleSet.defaults(2));

    private static Strategy strategy(String name, java.util.function.LongFunction<Player> factory) {
        return new Strategy(name, Set.of(2), true, factory);
    }

    /** A player that always plays a move that is not legal. */
    private static final class Cheater implements Player {
        @Override
        public int choose(Position position) {
            return Move.of(0, 0);
        }

        @Override
        public String name() {
            return "cheater";
        }
    }

    /** A player that fails with a runtime exception, like a bug in a search would. */
    private static final class Crasher implements Player {
        @Override
        public int choose(Position position) {
            throw new ArrayIndexOutOfBoundsException("boom");
        }

        @Override
        public String name() {
            return "crasher";
        }
    }

    /** A legal player that runs away from the goal; it can never win. */
    private static final class Runaway implements Player {
        @Override
        public int choose(Position position) {
            Layout layout = position.layout();
            int player = position.sideToMove();
            int best = Integer.MIN_VALUE;
            int chosen = -1;
            for (int m : position.legalMoves()) {
                int loss = layout.distance(player, Move.to(m)) - layout.distance(player, Move.from(m));
                if (loss > best) {
                    best = loss;
                    chosen = m;
                }
            }
            return chosen;
        }

        @Override
        public String name() {
            return "runaway";
        }
    }

    /** A player that plays a move on the position it was handed instead of only returning one. */
    private static final class Meddler implements Player {
        @Override
        public int choose(Position position) {
            int move = position.legalMoves()[0];
            position.make(move);
            return move;
        }

        @Override
        public String name() {
            return "meddler";
        }
    }

    // ---- the checks fail on broken strategies ----

    @Test
    void anIllegalMoveStopsAGameAndFailsTheSelfPlayCheck() {
        assertThrows(IllegalMoveException.class, () -> GameRunner.play(START, new Cheater(), new Cheater()));
        AssertionError error = assertThrows(AssertionError.class,
                () -> StrategyChecks.assertCleanSelfPlay(strategy("cheater", seed -> new Cheater()), START, 1));
        assertTrue(error.getMessage().contains("illegal move"), error.getMessage());
        assertTrue(error.getMessage().contains("strategy: cheater"), "the failure must name the strategy");
    }

    @Test
    void aTechnicalErrorFailsTheSelfPlayCheckAndTheBeatRandomCheck() {
        Strategy crasher = strategy("crasher", seed -> new Crasher());
        AssertionError selfPlay = assertThrows(AssertionError.class, () -> StrategyChecks.assertCleanSelfPlay(crasher, START, 1));
        assertTrue(selfPlay.getMessage().contains("technical error"), selfPlay.getMessage());
        AssertionError random = assertThrows(AssertionError.class, () -> StrategyChecks.assertBeatsRandom(crasher, START, true, 1));
        assertTrue(random.getMessage().contains("technical error"), random.getMessage());
    }

    @Test
    void aStrategyThatCannotWinFailsTheBeatRandomCheck() {
        Strategy runaway = strategy("runaway", seed -> new Runaway());
        assertDoesNotThrow(() -> StrategyChecks.assertCleanSelfPlay(runaway, START, 1), "runaway plays legal moves");
        for (boolean first : new boolean[] {true, false}) {
            AssertionError error = assertThrows(AssertionError.class, () -> StrategyChecks.assertBeatsRandom(runaway, START, first, 3));
            assertTrue(error.getMessage().contains("did not lose"), error.getMessage());
        }
    }

    @Test
    void aStrategyThatTheRandomPlayerCanBeatFailsToo() {
        // the random player as the "strategy": half the time the other random player wins, so some seed must fail
        Strategy random = strategy("random-as-strong", RandomPlayer::new);
        boolean failedOnce = false;
        for (long seed = 1; seed <= 20 && !failedOnce; seed++) {
            try {
                StrategyChecks.assertBeatsRandom(random, START, true, seed);
            } catch (AssertionError expected) {
                failedOnce = true;
            }
        }
        assertTrue(failedOnce, "a random strategy cannot satisfy 'always beats random'");
    }

    @Test
    void aPlayerThatMeddlesWithThePositionIsCaught() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> StrategyChecks.assertCleanSelfPlay(strategy("meddler", seed -> new Meddler()), START, 1));
        assertNotNull(error.getMessage());
    }

    @Test
    void theGameLengthFloorDependsOnTheBlockadeRule() {
        RuleSet def = RuleSet.defaults(2);
        RuleSet none = def.withBlockadeRule(RuleSet.BlockadeRule.NONE);
        assertEquals(8, StrategyChecks.minPliesOfAWonGame(def));
        assertEquals(19, StrategyChecks.minPliesOfAWonGame(none));
        assertDoesNotThrow(() -> StrategyChecks.assertPlausibleLength(8, def, "ctx"));
        assertThrows(AssertionError.class, () -> StrategyChecks.assertPlausibleLength(7, def, "ctx"));
        assertDoesNotThrow(() -> StrategyChecks.assertPlausibleLength(19, none, "ctx"));
        assertThrows(AssertionError.class, () -> StrategyChecks.assertPlausibleLength(18, none, "ctx"));
    }

    @Test
    void theProvenFloorHoldsForEveryRealGameWithoutTheBlockadeRule() {
        // the proof says 19 plies; real games under NONE must therefore never be shorter
        RuleSet none = RuleSet.defaults(2).withBlockadeRule(RuleSet.BlockadeRule.NONE).withMoveLimit(400);
        int shortest = Integer.MAX_VALUE;
        for (long seed = 1; seed <= 60; seed++) {
            GameRunner.Result r = GameRunner.play(Position.initial(none), new AlphaBetaPlayer(2), new RandomPlayer(seed));
            if (r.outcome().hasWinner()) {
                shortest = Math.min(shortest, r.plies());
            }
        }
        assertTrue(shortest >= StrategyChecks.PROVEN_MIN_PLIES_WITHOUT_BLOCKADE_RULE, "shortest game: " + shortest);
    }

    // ---- the position generator delivers variety ----

    @Test
    void generatedPositionsAreLegalNonTerminalAndPlayable() {
        for (int players = 2; players <= 3; players++) {
            for (int variant = 0; variant < PositionGenerator.VARIANTS; variant++) {
                RuleSet rules = PositionGenerator.rules(players, variant);
                for (long seed = 1; seed <= 25; seed++) {
                    for (double greed : new double[] {0.0, 0.5, 0.95}) {
                        Position pos = PositionGenerator.played(rules, seed, (int) (seed * 7), greed);
                        assertNull(pos.outcome(), "players " + players + " variant " + variant + " seed " + seed);
                        assertTrue(pos.legalMoves().length > 0);
                        for (int p = 0; p < players; p++) {
                            assertEquals(Position.PIECES, pos.pieces(p).length);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theStartPositionIsGeneratedAndOthersDiffer() {
        RuleSet rules = RuleSet.defaults(2);
        assertEquals(START, PositionGenerator.played(rules, 99, 0, 0.5));
        assertEquals(0, PositionGenerator.played(rules, 99, 0, 0.5).ply());
        Set<Position> distinct = new HashSet<>();
        int maxPly = 0;
        for (long seed = 1; seed <= 60; seed++) {
            Position pos = PositionGenerator.played(rules, seed, 120, 0.95);
            distinct.add(pos);
            maxPly = Math.max(maxPly, pos.ply());
        }
        assertTrue(distinct.size() > 50, "only " + distinct.size() + " distinct late positions");
        assertTrue(maxPly >= 100, "greedy play reaches late positions, max ply " + maxPly);
    }

    @Test
    void positionsJustBeforeTheEndHaveAMoveThatEndsTheGame() {
        int ended = 0;
        for (int variant = 0; variant < PositionGenerator.VARIANTS; variant++) {
            for (long seed = 1; seed <= 10; seed++) {
                Position pos = PositionGenerator.justBeforeTheEnd(PositionGenerator.rules(2, variant), seed);
                assertNull(pos.outcome());
                boolean someMoveEnds = false;
                for (int m : pos.legalMoves()) {
                    pos.make(m);
                    someMoveEnds |= pos.outcome() != null;
                    pos.unmake();
                }
                assertTrue(someMoveEnds || pos.ply() == 0, "variant " + variant + " seed " + seed + " ply " + pos.ply());
                ended += someMoveEnds ? 1 : 0;
            }
        }
        assertNotEquals(0, ended);
        assertTrue(ended >= 30, "most end-of-game positions must really be one move from the end, got " + ended + " of 40");
    }

    @Test
    void allRuleVariantsExistAndDiffer() {
        Set<RuleSet> variants = new HashSet<>();
        for (int v = 0; v < PositionGenerator.VARIANTS; v++) {
            variants.add(PositionGenerator.rules(2, v));
        }
        assertEquals(PositionGenerator.VARIANTS, variants.size());
        assertEquals(RuleSet.defaults(2), PositionGenerator.rules(2, 0));
    }
}
