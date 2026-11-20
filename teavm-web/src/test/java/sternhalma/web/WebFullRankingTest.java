package sternhalma.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * The ranking argument of the facade ({@code "first"} or {@code "full"}) and the three outcome strings of a full-ranking
 * game: {@code ok ongoing}, {@code ok ongoing finished <mask>} and {@code ok ranking <a> <b> <c>}. No race table is
 * needed: three players and the long hop use none.
 */
class WebFullRankingTest {

    private static String csv(List<Integer> moves) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < moves.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(moves.get(i));
        }
        return sb.toString();
    }

    /** A move with the largest distance gain; ties are broken by the seeded random generator. */
    private static int greedy(Position pos, java.util.SplittableRandom random) {
        Layout layout = pos.layout();
        int side = pos.sideToMove();
        int best = Integer.MIN_VALUE;
        List<Integer> picks = new ArrayList<>();
        for (int m : pos.legalMoves()) {
            int gain = layout.distance(side, Move.from(m)) - layout.distance(side, Move.to(m));
            if (gain > best) {
                best = gain;
                picks.clear();
            }
            if (gain == best) {
                picks.add(m);
            }
        }
        return picks.get(random.nextInt(picks.size()));
    }

    private static RuleSet full(String jump) {
        return RuleSet.defaults(3).withRanking(RuleSet.Ranking.FULL_RANKING)
                .withJumpRule(WebCore.parseJump(jump));
    }

    @Test
    void parsesTheRankingArgument() {
        assertEquals(RuleSet.Ranking.FIRST_WINNER_ONLY, WebCore.parseRanking(null));
        assertEquals(RuleSet.Ranking.FIRST_WINNER_ONLY, WebCore.parseRanking(""));
        assertEquals(RuleSet.Ranking.FIRST_WINNER_ONLY, WebCore.parseRanking("first"));
        assertEquals(RuleSet.Ranking.FULL_RANKING, WebCore.parseRanking("full"));
        assertTrue(Web.legalMoves("", null, "3", "both", null, null).startsWith("error internal "));
        assertTrue(Web.outcome("", null, "3", "FULL", null, null).startsWith("error internal "));
        assertTrue(Web.newGame(100, 100, null, "3", 100, "second", null, null).startsWith("error internal "));
    }

    @Test
    void firstModeStringsAreAsBefore() {
        for (String ranking : new String[] {null, "", "first"}) {
            assertEquals("ok ongoing", Web.outcome("", null, "3", ranking, null, null));
            assertEquals("ok ongoing", Web.outcome("", null, "2", ranking, null, null));
        }
        // two players: "full" plays the same game and reports the same strings
        assertEquals("ok ongoing", Web.outcome("", null, "2", "full", null, null));
        assertEquals(Web.legalMoves("", null, "2", null, null, null), Web.legalMoves("", null, "2", "full", null, null));
    }

    @Test
    void aFullGameReportsTheStringsAndTheEngineAgrees() {
        for (String jump : new String[] {"adjacent", "long"}) {
            assertEquals("ok", Web.newGame(100, 100, jump, "3", 100, "full", null, null));
            Position oracle = Position.initial(full(jump));
            List<Integer> history = new ArrayList<>();
            boolean sawFinished = false;
            String last = null;
            while (true) {
                String state = Web.outcome(csv(history), jump, "3", "full", null, null);
                last = state;
                if (state.startsWith("ok ranking ")) {
                    break;
                }
                int mask = oracle.finishedMask();
                if (mask == 0) {
                    assertEquals("ok ongoing", state);
                } else {
                    assertEquals("ok ongoing finished " + mask, state);
                    sawFinished = true;
                }
                // the moves are those of the next player who has not finished, and the engine picks one of them
                String legal = Web.legalMoves(csv(history), jump, "3", "full", null, null);
                assertEquals("ok " + String.join(",", Arrays.stream(oracle.legalMoves()).mapToObj(Integer::toString).toList()), legal);
                assertEquals(0, mask >> oracle.sideToMove() & 1, "a finished player is not to move");
                String[] choose = Web.choose(csv(history), jump, "3", "full", null, null).split(" ");
                assertEquals("ok", choose[0]);
                int move = Integer.parseInt(choose[1]);
                assertTrue(Arrays.stream(oracle.legalMoves()).anyMatch(m -> m == move));
                history.add(move);
                oracle.make(move);
                assertTrue(history.size() <= 300, "the game is limited to 300 plies");
            }
            Outcome expected = oracle.outcome();
            int[] r = expected.ranking();
            assertEquals("ok ranking " + r[0] + " " + r[1] + " " + r[2], last);
            assertEquals(3, Arrays.stream(r).distinct().count());
            System.out.println("facade full game, jump=" + jump + ": " + history.size() + " plies, " + last
                    + ", saw a finished player: " + sawFinished);
        }
    }

    /** A greedy playout reaches the continued phase: the strings and the choice of the next active player. */
    @Test
    void theContinuedPhaseIsReportedWithTheFinishedSet() {
        java.util.SplittableRandom random = new java.util.SplittableRandom(5);
        Position oracle = Position.initial(full("adjacent"));
        List<Integer> history = new ArrayList<>();
        while (oracle.finishedMask() == 0 && oracle.outcome() == null) {
            int move = greedy(oracle, random);
            history.add(move);
            oracle.make(move);
        }
        int mask = oracle.finishedMask();
        assertTrue(mask == 1 || mask == 2 || mask == 4, "one finisher: " + mask);
        String h = csv(history);
        assertEquals("ok ongoing finished " + mask, Web.outcome(h, "adjacent", "3", "full", null, null));
        // the same history in first mode is over, with the first finisher as the winner
        assertEquals("ok win " + Integer.numberOfTrailingZeros(mask), Web.outcome(h, "adjacent", "3", "first", null, null));
        // a finished player never moves: legal moves belong to the next active player
        int side = oracle.sideToMove();
        assertEquals(0, mask >> side & 1);
        for (String m : Web.legalMoves(h, "adjacent", "3", "full", null, null).substring(3).split(",")) {
            assertEquals(side, oracle.owner(Move.from(Integer.parseInt(m))));
        }
        // in first mode the game is over: no legal moves
        assertEquals("ok ", Web.legalMoves(h, "adjacent", "3", "first", null, null));
        // a game started with one ranking rule refuses the other
        assertEquals("ok", Web.newGame(100, 100, "adjacent", "3", 100, "full", null, null));
        assertTrue(Web.choose(h, "adjacent", "3", "first", null, null).startsWith("error "), Web.choose(h, "adjacent", "3", "first", null, null));
        String choose = Web.choose(h, "adjacent", "3", "full", null, null);
        assertTrue(choose.startsWith("ok "), choose);
    }

    /** Analysis and the static evaluation in the continued phase: finished seats, the active moves, the rank scores. */
    @Test
    void analyseAndEvaluate3WorkInTheContinuedPhase() {
        for (String jump : new String[] {"adjacent", "long"}) {
            java.util.SplittableRandom random = new java.util.SplittableRandom(5);
            Position oracle = Position.initial(full(jump));
            List<Integer> history = new ArrayList<>();
            while (oracle.finishedMask() == 0 && oracle.outcome() == null) {
                int move = greedy(oracle, random);
                history.add(move);
                oracle.make(move);
            }
            int mask = oracle.finishedMask();
            int first = Integer.numberOfTrailingZeros(mask);
            String h = csv(history);
            String[] eval = Web.evaluate3(h, jump, "full", null, null).split(" ");
            assertEquals("ok", eval[0]);
            assertEquals(1_000_000, Integer.parseInt(eval[1 + first]), jump);
            for (int p = 0; p < 3; p++) {
                if (p != first) {
                    assertTrue(Math.abs(Integer.parseInt(eval[1 + p])) < 100_000, jump + " seat " + p + ": " + eval[1 + p]);
                }
            }
            // first mode: the same history is a finished game, evaluated like any position
            assertTrue(Web.evaluate3(h, jump, "first", null, null).startsWith("ok "));
            String[] reply = Web.analyse(h, "", 300, jump, "3", "full", null, null).split(" ");
            assertEquals("ok", reply[0], String.join(" ", reply));
            int[] legal = oracle.legalMoves();
            assertEquals(legal.length, reply[4].split(",").length);
            assertEquals(legal.length, reply[5].split(",").length);
            for (String m : reply[4].split(",")) {
                assertEquals(oracle.sideToMove(), oracle.owner(Move.from(Integer.parseInt(m))));
            }
            // a game that is over has nothing to analyse; in first mode that is the case right after the first finisher
            assertTrue(Web.analyse(h, "", 300, jump, "3", "first", null, null).startsWith("error illegal-history "));
        }
    }

    @Test
    void theMoveLimitInAFullGameIsARankingNotADraw() {
        // 3 plies: the move limit of the facade is 300, so reach it with a long greedy history of a full game
        java.util.SplittableRandom random = new java.util.SplittableRandom(6);
        Position oracle = Position.initial(full("long"));
        List<Integer> history = new ArrayList<>();
        while (oracle.outcome() == null && history.size() < 400) {
            int move = greedy(oracle, random);
            history.add(move);
            oracle.make(move);
        }
        Outcome expected = oracle.outcome();
        assertTrue(expected != null);
        int[] r = expected.ranking();
        assertEquals("ok ranking " + r[0] + " " + r[1] + " " + r[2], Web.outcome(csv(history), "long", "3", "full", null, null));
    }

    @Test
    void benchWithFullRankingKeepsPlaying() {
        String[] reply = Web.bench(6, 100, "adjacent", "3", "full", null, null).split(" ");
        assertEquals("ok", reply[0]);
        assertEquals(6, reply[1].split(",").length);
        assertEquals(6, Web.bench(6, 100, "long", "2", "full", null, null).split(" ")[1].split(",").length);
    }
}
