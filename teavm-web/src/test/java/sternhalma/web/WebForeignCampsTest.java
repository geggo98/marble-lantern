package sternhalma.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.ForeignCamps;

/** The argument {@code camps} of the facade (docs/rules.md, "Foreign corners"). */
class WebForeignCampsTest {

    private static String csv(List<Integer> moves) {
        return String.join(",", moves.stream().map(String::valueOf).toList());
    }

    @Test
    void unknownValuesAreRejectedAndEmptyMeansClosed() {
        assertTrue(Web.legalMoves("", null, "2", null, "OPEN", null).startsWith("error internal "));
        assertTrue(Web.newGame(100, 100, null, null, 0, null, "half", null).startsWith("error internal "));
        assertEquals(Web.legalMoves("", null, "2", null, "closed", null), Web.legalMoves("", null, "2", null, null, null));
        assertEquals(Web.legalMoves("", null, "2", null, "closed", null), Web.legalMoves("", null, "2", null, "", null));
    }

    /** A history played under OPEN: every reply equals the engine's own move list, and some move ends on a foreign cell. */
    @Test
    void legalMovesFollowTheRuleOfTheCall() {
        for (String players : new String[] {"2", "3"}) {
            int count = Integer.parseInt(players);
            Position pos = Position.initial(RuleSet.defaults(count).withForeignCamps(ForeignCamps.OPEN));
            List<Integer> history = new ArrayList<>();
            boolean differs = false;
            long state = 12345;
            for (int ply = 0; ply < 40 && pos.outcome() == null; ply++) {
                int[] expected = pos.legalMoves();
                assertEquals("ok " + String.join(",", Arrays.stream(expected).mapToObj(String::valueOf).toList()),
                        Web.legalMoves(csv(history), "adjacent", players, "first", "open", null));
                String closed = Web.legalMoves(csv(history), "adjacent", players, "first", "closed", null);
                differs |= !closed.equals(Web.legalMoves(csv(history), "adjacent", players, "first", "open", null));
                state = state * 6364136223846793005L + 1442695040888963407L;
                int move = expected[(int) ((state >>> 33) % expected.length)];
                pos.make(move);
                history.add(move);
            }
            assertTrue(differs, players + " players: open and closed never differed");
        }
    }

    @Test
    void chooseNeedsTheRuleOfTheGameAndPlaysALegalMove() {
        assertEquals("ok", Web.newGame(200, 200, "adjacent", "2", 0, "first", "pass-through", null));
        assertTrue(Web.choose("", "adjacent", "2", "first", "closed", null).startsWith("error internal "));
        String[] reply = Web.choose("", "adjacent", "2", "first", "pass-through", null).split(" ");
        assertEquals("ok", reply[0]);
        String legal = Web.legalMoves("", "adjacent", "2", "first", "pass-through", null);
        assertTrue(Arrays.asList(legal.substring(3).split(",")).contains(reply[1]), legal + " / " + reply[1]);
        assertEquals("ok ongoing", Web.outcome("", "adjacent", "2", "first", "pass-through", null));
        assertNotEquals("", reply[1]);
    }
}
