package sternhalma.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.TriangleRows;
import sternhalma.search.Strategies;
import sternhalma.search.ThreePlayerSpec;

/**
 * The argument {@code rows} of the facade (docs/rules.md, "Board size"): {@code "5"} plays 15-cell houses with 15
 * pieces per player, {@code "4"}, null and the empty string play the old 10-cell board.
 */
class WebTriangleRowsTest {

    private static String csv(List<Integer> moves) {
        return String.join(",", moves.stream().map(String::valueOf).toList());
    }

    private static String joined(int[] moves) {
        return String.join(",", Arrays.stream(moves).mapToObj(String::valueOf).toList());
    }

    /** A seeded random playout on the engine's own Position; the expected values come from the engine, not the facade. */
    private static List<Integer> playout(Position pos, int plies, long seed) {
        List<Integer> history = new ArrayList<>();
        long state = seed;
        for (int ply = 0; ply < plies && pos.outcome() == null; ply++) {
            int[] legal = pos.legalMoves();
            state = state * 6364136223846793005L + 1442695040888963407L;
            int move = legal[(int) ((state >>> 33) % legal.length)];
            pos.make(move);
            history.add(move);
        }
        return history;
    }

    @Test
    void unknownValuesAreRejectedAndTheOldSpellingsMeanFourRows() {
        assertTrue(Web.legalMoves("", null, "2", null, null, "6").startsWith("error internal "));
        assertTrue(Web.legalMoves("", null, "2", null, null, "five").startsWith("error internal "));
        assertTrue(Web.newGame(100, 100, null, null, 0, null, null, "05").startsWith("error internal "));
        String four = Web.legalMoves("", "adjacent", "2", "first", "closed", "4");
        assertEquals(four, Web.legalMoves("", "adjacent", "2", "first", "closed", null));
        assertEquals(four, Web.legalMoves("", "adjacent", "2", "first", "closed", ""));
        assertEquals("ok " + joined(Position.initial(RuleSet.defaults(2)).legalMoves()), four);
        assertEquals("ok ongoing", Web.outcome("", "adjacent", "2", "first", "closed", ""));
    }

    @Test
    void legalMovesOnFiveRowsAreThoseOfThePosition() {
        for (String players : new String[] {"2", "3"}) {
            for (String jump : new String[] {"adjacent", "long"}) {
                RuleSet.JumpRule rule = WebCore.parseJump(jump);
                Position pos = Position.initial(RuleSet.defaults(Integer.parseInt(players), TriangleRows.FIVE).withJumpRule(rule));
                for (int c = 0; c < 15; c++) {
                    assertEquals(15, pos.pieceCount(c % Integer.parseInt(players)), "15 pieces per player");
                }
                List<Integer> history = new ArrayList<>();
                long state = 99;
                for (int ply = 0; ply < 50 && pos.outcome() == null; ply++) {
                    int[] expected = pos.legalMoves();
                    assertEquals("ok " + joined(expected), Web.legalMoves(csv(history), jump, players, "first", "closed", "5"),
                            players + " " + jump + " ply " + ply);
                    state = state * 6364136223846793005L + 1442695040888963407L;
                    int move = expected[(int) ((state >>> 33) % expected.length)];
                    pos.make(move);
                    history.add(move);
                }
            }
        }
    }

    /** A move of the 5-row start that moves a piece of the edge row: the 4-row board has no piece there. */
    @Test
    void fiveRowMovesAreNotLegalOnFourRowsAndTheOtherWayRound() {
        String five = Web.legalMoves("", "adjacent", "2", "first", "closed", "5").substring(3);
        String four = Web.legalMoves("", "adjacent", "2", "first", "closed", "4").substring(3);
        List<String> fiveOnly = new ArrayList<>(Arrays.asList(five.split(",")));
        fiveOnly.removeAll(Arrays.asList(four.split(",")));
        assertFalse(fiveOnly.isEmpty(), "the 15th piece must open moves the 4-row start does not have");
        String move = fiveOnly.get(0);
        assertTrue(Web.legalMoves(move, "adjacent", "2", "first", "closed", "4").startsWith("error illegal-history "));
        assertTrue(Web.legalMoves(move, "adjacent", "2", "first", "closed", "5").startsWith("ok "));
        List<String> fourOnly = new ArrayList<>(Arrays.asList(four.split(",")));
        fourOnly.removeAll(Arrays.asList(five.split(",")));
        // The 10 pieces of the 4-row start are a subset of the 15 of the 5-row start; a move that a 10-cell piece makes
        // into the edge row is blocked on 5 rows. Either set may be empty; the two lists must not be equal.
        assertNotEquals(four, five);
    }

    @Test
    void theMoveLimitIs250And375PliesOnFiveRows() {
        for (int players : new int[] {2, 3}) {
            int limit = players == 2 ? 250 : 375;
            Position pos = Position.initial(RuleSet.defaults(players, TriangleRows.FIVE));
            List<Integer> history = playout(pos, limit, 7);
            assertEquals(limit, history.size(), players + " players: the seeded playout ended early: " + pos.outcome());
            String all = csv(history);
            String before = csv(history.subList(0, limit - 1));
            assertEquals("ok ongoing", Web.outcome(before, "adjacent", String.valueOf(players), "first", "closed", "5"));
            assertEquals("ok draw", Web.outcome(all, "adjacent", String.valueOf(players), "first", "closed", "5"));
            assertEquals(pos.outcome() != null, !Web.outcome(all, "adjacent", String.valueOf(players), "first", "closed", "5")
                    .equals("ok ongoing"));
        }
        // The 4-row board keeps its 200 and 300 plies.
        Position four = Position.initial(RuleSet.defaults(2));
        List<Integer> history = playout(four, 200, 7);
        assertEquals(200, history.size());
        assertEquals("ok draw", Web.outcome(csv(history), "adjacent", "2", "first", "closed", ""));
    }

    @Test
    void aGameOnFiveRowsNeedsNoTableAndChoosesLegalMoves() {
        // No setTable was called in this JVM for the 5-row game: it has no race table. Two and three players, both jump rules.
        for (String players : new String[] {"2", "3"}) {
            for (String jump : new String[] {"adjacent", "long"}) {
                assertEquals("ok", Web.newGame(300, 300, jump, players, 300, "first", "closed", "5"), players + " " + jump);
                List<Integer> history = new ArrayList<>();
                Position pos = Position.initial(RuleSet.defaults(Integer.parseInt(players), TriangleRows.FIVE)
                        .withJumpRule(WebCore.parseJump(jump)));
                for (int ply = 0; ply < 6; ply++) {
                    String[] reply = Web.choose(csv(history), jump, players, "first", "closed", "5").split(" ");
                    assertEquals("ok", reply[0], String.join(" ", reply));
                    int move = Integer.parseInt(reply[1]);
                    assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move), "illegal engine move " + move);
                    assertEquals("0", reply[6], "no race move on 5 rows");
                    pos.make(move);
                    history.add(move);
                }
            }
        }
    }

    @Test
    void chooseNeedsTheBoardOfTheGame() {
        assertEquals("ok", Web.newGame(200, 200, "long", "2", 0, "first", "closed", "5"));
        // The refusal comes from WebCore itself, not from a player that meets the other board by chance.
        assertTrue(Web.choose("", "long", "2", "first", "closed", "4").contains("call newGame first"));
        assertTrue(Web.choose("", "long", "2", "first", "closed", null).contains("call newGame first"));
        assertTrue(Web.choose("", "long", "2", "first", "closed", "5").startsWith("ok "));
        assertEquals("ok", Web.newGame(200, 200, "long", "2", 0, "first", "closed", ""));
        assertTrue(Web.choose("", "long", "2", "first", "closed", "5").contains("call newGame first"));
        assertTrue(Web.choose("", "long", "2", "first", "closed", "4").startsWith("ok "));
    }

    @Test
    void analyseAndEvaluate3FollowTheBoardSize() {
        Position pos = Position.initial(RuleSet.defaults(2, TriangleRows.FIVE).withJumpRule(RuleSet.JumpRule.LONG));
        String reply = Web.analyse("", "", 200, "long", "2", "first", "closed", "5");
        String[] f = reply.split(" ");
        assertEquals("ok", f[0], reply);
        assertEquals(joined(pos.legalMoves()), f[4], "all legal moves of the 5-row start are scored");
        assertEquals(pos.legalMoves().length, f[5].split(",").length);
        // The same call on 4 rows scores the 4-row moves.
        String fourReply = Web.analyse("", "", 200, "long", "2", "first", "closed", "4");
        assertTrue(fourReply.startsWith("ok "), fourReply);
        String four = fourReply.split(" ")[4];
        assertEquals("ok " + joined(Position.initial(RuleSet.defaults(2).withJumpRule(RuleSet.JumpRule.LONG)).legalMoves()),
                "ok " + four, "the 4-row call after a 5-row call scores the 4-row moves");
        assertNotEquals(f[4], four);
        // The analysis player is rebuilt when only the board size changes: a call back on 5 rows equals the first one.
        assertEquals(reply.split(" ")[4], Web.analyse("", "", 200, "long", "2", "first", "closed", "5").split(" ")[4]);

        // The start is symmetric (all zero), so compare after a seeded playout, against the search module's own player.
        Position three = Position.initial(RuleSet.defaults(3, TriangleRows.FIVE).withJumpRule(RuleSet.JumpRule.LONG));
        List<Integer> history = playout(three, 30, 5);
        int[] expected = ThreePlayerSpec.parse(Strategies.BROWSER_3P_ROWS5_LONG_SPEC).create().evaluation(three);
        assertNotEquals("[0, 0, 0]", Arrays.toString(expected), "the playout must leave the symmetric start");
        assertEquals("ok " + expected[0] + " " + expected[1] + " " + expected[2],
                Web.evaluate3(csv(history), "long", "first", "closed", "5"));
        // Back on 4 rows (the cached analysis player must follow the size), and analyse for three players on both.
        Position three4 = Position.initial(RuleSet.defaults(3).withJumpRule(RuleSet.JumpRule.LONG));
        List<Integer> history4 = playout(three4, 30, 5);
        int[] expected4 = ThreePlayerSpec.parse(Strategies.BROWSER_3P_LONG_SPEC).create().evaluation(three4);
        assertEquals("ok " + expected4[0] + " " + expected4[1] + " " + expected4[2],
                Web.evaluate3(csv(history4), "long", "first", "closed", "4"));
        String a5 = Web.analyse(csv(history), "", 100, "long", "3", "first", "closed", "5");
        String a4 = Web.analyse(csv(history4), "", 100, "long", "3", "first", "closed", "4");
        assertEquals("ok " + joined(three.legalMoves()), "ok " + a5.split(" ")[4], a5);
        assertEquals("ok " + joined(three4.legalMoves()), "ok " + a4.split(" ")[4], a4);
    }

    @Test
    void benchOnFiveRowsPlaysLegalMoves() {
        for (String players : new String[] {"2", "3"}) {
            String[] f = Web.bench(8, 200, "long", players, "first", "closed", "5").split(" ");
            assertEquals("ok", f[0], String.join(" ", f));
            Position pos = Position.initial(RuleSet.defaults(Integer.parseInt(players), TriangleRows.FIVE)
                    .withJumpRule(RuleSet.JumpRule.LONG));
            for (String m : f[1].split(",")) {
                int move = Integer.parseInt(m);
                assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(x -> x == move), "bench move " + move);
                pos.make(move);
            }
        }
    }

    @Test
    void aCallWithTheEmptySizeIsByteIdenticalToTheOldBoard() {
        // choose, with the search made deterministic by the fixed node budget: the move, score, depth and nodes agree.
        // The 4-row race game needs the table; the long hop does not, so the comparison uses it.
        assertEquals("ok", Web.newGame(400, 400, "long", "2", 0, "first", "closed", ""));
        String[] empty = Web.choose("", "long", "2", "first", "closed", "").split(" ");
        assertEquals("ok", Web.newGame(400, 400, "long", "2", 0, "first", "closed", "4"));
        String[] four = Web.choose("", "long", "2", "first", "closed", "4").split(" ");
        assertEquals(Arrays.asList(empty).subList(0, 5), Arrays.asList(four).subList(0, 5));
    }

    /** A fresh core has no race table; a 5-row game must still start and play (the 4-row adjacent game cannot). */
    @Test
    void fiveRowsNeedNoTableInAFreshCore() {
        WebCore core = new WebCore();
        RuleSet.JumpRule adjacent = RuleSet.JumpRule.ADJACENT;
        RuleSet.Ranking first = RuleSet.Ranking.FIRST_WINNER_ONLY;
        RuleSet.ForeignCamps closed = RuleSet.ForeignCamps.CLOSED;
        WebFailure noTable = org.junit.jupiter.api.Assertions.assertThrows(WebFailure.class,
                () -> core.newGame(100, 100, adjacent, first, closed, TriangleRows.FOUR));
        assertTrue(noTable.getMessage().contains("no table"), noTable.getMessage());
        core.newGame(100, 100, adjacent, first, closed, TriangleRows.FIVE);
        WebCore.Choice choice = core.choose(new int[0], adjacent, 2, first, closed, TriangleRows.FIVE);
        assertFalse(choice.raceMove());
        assertEquals(0, core.rows1Requests());
    }
}
