package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;

/** The three-player browser player: legality, determinism, budget and measured strength (fixed seeds). */
class ThreePlayerTest {

    private static RuleSet rules(JumpRule jump) {
        RuleSet d = RuleSet.defaults(3);
        return new RuleSet(3, d.moveLimit(), d.leaveGoal(), d.blockadeRule(), d.suicideMoves(), d.blockingStates(),
                d.repetition(), d.drawHandling(), d.ranking(), jump);
    }

    /** Picks the move that reduces the own distance sum most; ties go to a seeded random choice. */
    static final class Greedy implements Player {
        private final RandomGenerator random;

        Greedy(long seed) {
            random = RandomGeneratorFactory.of("SplittableRandom").create(seed);
        }

        @Override
        public int choose(Position position) {
            int p = position.sideToMove();
            int[] moves = position.legalMoves();
            int[] best = new int[moves.length];
            int count = 0;
            int bestValue = Integer.MAX_VALUE;
            for (int move : moves) {
                position.make(move);
                int value = position.distanceSum(p);
                position.unmake();
                if (value < bestValue) {
                    bestValue = value;
                    count = 0;
                }
                if (value == bestValue) {
                    best[count++] = move;
                }
            }
            return best[random.nextInt(count)];
        }

        @Override
        public String name() {
            return "greedy";
        }
    }

    /** Result of a game: the winner or -1, the plies played. */
    record Game(int winner, int plies) {
    }

    /** Plays one game after {@code opening} random plies; checks legality of every move and the end of the game. */
    static Game play(RuleSet rules, Player[] players, long seed, int opening) {
        Position pos = Position.initial(rules);
        RandomGenerator random = RandomGeneratorFactory.of("SplittableRandom").create(seed);
        for (int i = 0; i < opening; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        while (true) {
            Outcome outcome = pos.outcome();
            if (outcome != null) {
                assertTrue(pos.ply() <= rules.moveLimit(), "ply " + pos.ply() + " beyond the limit");
                return new Game(outcome.winner(), pos.ply());
            }
            int[] legal = pos.legalMoves();
            if (legal.length == 0) {
                return new Game(-1, pos.ply());
            }
            int move = players[pos.sideToMove()].choose(pos);
            assertTrue(Arrays.stream(legal).anyMatch(m -> m == move), "illegal move by " + players[pos.sideToMove()].name());
            pos.make(move);
        }
    }

    private static final long NODES = 20_000;

    private static ThreePlayerPlayer ab(JumpRule jump) {
        return ThreePlayerSpec.parse("ab3:nodes=" + NODES + ",tt=16" + (jump == JumpRule.LONG ? ",jump=long" : "")).create();
    }

    /** Wins of the engine per seat against two opponents made by {@code opponent}, over {@code games} games per seat. */
    private static int[] winsPerSeat(JumpRule jump, int games, java.util.function.LongFunction<Player> opponent,
            long seedBase, int opening) {
        java.util.concurrent.atomic.AtomicIntegerArray won = new java.util.concurrent.atomic.AtomicIntegerArray(3);
        // games are independent and seeded, so running them in parallel does not change any result
        java.util.stream.IntStream.range(0, 3 * games).parallel().forEach(index -> {
            int seat = index / games;
            int g = index % games;
                long seed = seedBase + 1000L * seat + g;
                Player[] players = {opponent.apply(seed * 2), opponent.apply(seed * 2 + 1), null};
                // seats: the engine sits at 'seat', the opponents at the other two
                Player[] byseat = new Player[3];
                int next = 0;
                for (int s = 0; s < 3; s++) {
                    byseat[s] = s == seat ? ab(jump) : players[next++];
                }
                Game game = play(rules(jump), byseat, seed, opening);
                if (game.winner() == seat) {
                    won.incrementAndGet(seat);
                }
        });
        int[] wins = new int[3];
        for (int s = 0; s < 3; s++) {
            wins[s] = won.get(s);
        }
        return wins;
    }

    @Test
    void specParsesAndRejectsWhatItDoesNotKnow() {
        ThreePlayerSpec spec = ThreePlayerSpec.parse("ab3:nodes=500,depth=6,tt=12,jump=long");
        assertEquals(500, spec.nodes());
        assertEquals(6, spec.depth());
        assertEquals(12, spec.tableBits());
        assertEquals(JumpRule.LONG, spec.jumpRule());
        assertEquals(20_000, ThreePlayerSpec.parse("ab3").nodes());
        assertEquals(JumpRule.ADJACENT, ThreePlayerSpec.parse("ab3").jumpRule());
        assertThrows(IllegalArgumentException.class, () -> ThreePlayerSpec.parse("ab:nodes=5"));
        assertThrows(IllegalArgumentException.class, () -> ThreePlayerSpec.parse("ab3:colour=red"));
        assertThrows(IllegalArgumentException.class, () -> ThreePlayerSpec.parse("ab3:jump=far"));
        assertThrows(IllegalArgumentException.class, () -> ThreePlayerSpec.parse("ab3:eval=racelin-r3"));
        assertNotNull(Strategies.BROWSER_3P_SPEC);
        assertEquals(JumpRule.ADJACENT, ThreePlayerSpec.parse(Strategies.BROWSER_3P_SPEC).jumpRule());
        assertEquals(JumpRule.LONG, ThreePlayerSpec.parse(Strategies.BROWSER_3P_LONG_SPEC).jumpRule());
    }

    @Test
    void refusesTheWrongPlayerCountAndTheWrongJumpRule() {
        ThreePlayerPlayer adjacent = ThreePlayerSpec.parse("ab3:nodes=200").create();
        assertThrows(IllegalArgumentException.class,
                () -> adjacent.choose(Position.initial(rules(JumpRule.LONG))));
        assertThrows(IllegalArgumentException.class,
                () -> adjacent.choose(Position.initial(RuleSet.defaults(2))));
    }

    @Test
    void resultIsDeterministicAndStaysNearTheNodeBudget() {
        for (JumpRule jump : JumpRule.values()) {
            Position a = Position.initial(rules(jump));
            Position b = Position.initial(rules(jump));
            SearchResult ra = ab(jump).analyse(a);
            SearchResult rb = ab(jump).analyse(b);
            assertEquals(ra, rb);
            assertTrue(ra.depth() >= 2, ra.toString());
            assertTrue(ra.nodes() <= NODES + 200, "overshoot of the node limit: " + ra);
            assertTrue(Arrays.stream(a.legalMoves()).anyMatch(m -> m == ra.move()));
        }
    }

    @Test
    void takesAWinInOne() {
        // player 0 has nine pieces in the goal and one piece one step from the last goal cell
        for (JumpRule jump : JumpRule.values()) {
            Position pos = Position.initial(rules(jump));
            // play greedy for player 0 only until it can win in one, with the others random
            Player[] seats = {new Greedy(1), new RandomPlayer(2), new RandomPlayer(3)};
            int guard = 0;
            while (pos.outcome() == null && guard++ < 300) {
                int[] legal = pos.legalMoves();
                if (pos.sideToMove() == 0) {
                    boolean winning = false;
                    for (int m : legal) {
                        pos.make(m);
                        winning |= pos.hasWon(0);
                        pos.unmake();
                    }
                    if (winning) {
                        SearchResult r = ThreePlayerSpec.parse("ab3:nodes=2000"
                                + (jump == JumpRule.LONG ? ",jump=long" : "")).create().analyse(pos);
                        pos.make(r.move());
                        assertTrue(pos.hasWon(0), "missed a win in one: " + r);
                        assertTrue(r.score() >= AlphaBeta.MATE - 10, r.toString());
                        return;
                    }
                }
                pos.make(seats[pos.sideToMove()].choose(pos));
            }
        }
        // no position with a win in one was reached: nothing was tested
        throw new AssertionError("the test game never offered a win in one");
    }

    @Test
    void beatsRandomPlayersFromEverySeat() {
        int games = 40;
        for (JumpRule jump : JumpRule.values()) {
            int[] wins = winsPerSeat(jump, games, RandomPlayer::new, 7, 0);
            System.out.println("3p vs 2 random, jump=" + jump + ": wins per seat " + Arrays.toString(wins) + " of " + games);
            for (int seat = 0; seat < 3; seat++) {
                assertTrue(wins[seat] >= 38, jump + " seat " + seat + ": " + wins[seat] + " of " + games);
            }
        }
    }

    @Test
    void beatsTwoGreedyDistancePlayersClearly() {
        int games = 20; // per seat, 60 per rule
        for (JumpRule jump : JumpRule.values()) {
            int[] wins = winsPerSeat(jump, games, Greedy::new, 11, 4);
            int total = Arrays.stream(wins).sum();
            System.out.println("3p vs 2 greedy, jump=" + jump + ": wins per seat " + Arrays.toString(wins) + " of "
                    + games + ", total " + total + " of " + 3 * games);
            assertTrue(total > 3 * games * 0.6, jump + ": " + total + " of " + 3 * games + " is not clearly above 1/3");
        }
    }

    @Test
    void threeEnginesPlayLegalGamesToTheEnd() {
        for (JumpRule jump : JumpRule.values()) {
            long nodes = 0;
            long nanos = 0;
            long moves = 0;
            for (int g = 0; g < 2; g++) {
                ThreePlayerPlayer[] seats = {ab(jump), ab(jump), ab(jump)};
                Game game = play(rules(jump), seats, 100 + g, 2);
                assertTrue(game.plies() <= rules(jump).moveLimit());
                for (ThreePlayerPlayer seat : seats) {
                    nodes += seat.totalNodes();
                    nanos += seat.totalNanos();
                    moves += seat.moves();
                }
            }
            double perSecond = nodes / (nanos / 1e9);
            System.out.printf("3p speed, jump=%s: %.0f nodes/s, %.0f ms per move of %d nodes (%d moves)%n", jump, perSecond,
                    nanos / 1e6 / moves, NODES, moves);
            assertTrue(nanos / 1e6 / moves < 1000, "a move of " + NODES + " nodes took " + nanos / 1e6 / moves + " ms");
        }
    }
}
