package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;

/**
 * Pins the search trees of the reference player R2 ({@link ReferencePositions#R2_SPEC}, the frozen alias
 * {@code linear-r1}) and of {@link Strategies#BEST_SPEC} at 20000 nodes: for each spec the {@link Fingerprint}
 * over the 64 {@link ReferencePositions} (move, score, completed depth and node count of the node-limited search,
 * and move, score and exact node count of a depth-4 search) and over the move sequences and node totals of one
 * self-play game at 1000 and one at 5000 nodes. The constants were recorded with the code of commit cb4b750 plus
 * round-2 experiment E00 (which only adds code); at that point both specs search the same tree, because
 * {@code linear-r1} and {@code linear} hold the same weights.
 *
 * <p><b>Rule.</b> An identical-tree optimisation keeps all six constants unchanged. A feature that changes the
 * tree is off by default (a new spec key), so it changes neither constant set while it is off. Changing the
 * weights of {@code linear} (E04) legitimately changes the {@code BEST_*} constants and never the {@code R2_*}
 * constants. Any re-record needs a written reason in the commit message and in docs/search-and-evaluation.md
 * ("Round 2 yardstick"); the {@code R2_*} constants change only when the engine legitimately changes what R2
 * plays (for example its hashing or the rules), and then the frozen jar stays the cost reference.
 *
 * <p><b>How E01 and E02 use it.</b> Both speed packs claim the identical tree, so they need no arena run. Their
 * acceptance is: this test green without touching a constant (both sets: E01 changes AlphaBeta's move ordering
 * and LMR table, which BEST_SPEC and R2 both use; E02 changes Position and LinearEvaluator, which both use), and
 * the Bench ratio new/old over the same positions. On a failure, print the per-position lines of both builds with
 * {@code ./gradlew :search:fingerprint} (once on the candidate and once on the merge base) and diff them: the first
 * differing position or game move shows where the trees part. {@code Bench <spec>} against the frozen jar also
 * reports on how many positions and games the two builds searched identical trees, which is the same check
 * across builds without recorded constants.
 */
class BestSpecFingerprintTest {

    private static final String BEST_20000 = Strategies.BEST_SPEC + ",nodes=20000";

    @Test
    void referencePlayerR2SearchesTheRecordedTree() {
        assertParts(ReferencePositions.R2_SPEC, R2_POSITIONS, R2_GAME_1000, R2_GAME_5000);
    }

    @Test
    void bestSpecSearchesTheRecordedTree() {
        assertParts(BEST_20000, BEST_POSITIONS, BEST_GAME_1000, BEST_GAME_5000);
    }

    private static void assertParts(String spec, long positions, long game1000, long game5000) {
        Fingerprint.Part p = Fingerprint.positions(spec);
        assertEquals(positions, p.checksum(), () -> "tree of " + spec + " on the reference positions changed:\n"
                + String.join("\n", p.lines()));
        Fingerprint.Part g1 = Fingerprint.game(spec, Fingerprint.GAME_NODES[0]);
        assertEquals(game1000, g1.checksum(), () -> "game at 1000 nodes of " + spec + " changed:\n"
                + String.join("\n", g1.lines()));
        Fingerprint.Part g5 = Fingerprint.game(spec, Fingerprint.GAME_NODES[1]);
        assertEquals(game5000, g5.checksum(), () -> "game at 5000 nodes of " + spec + " changed:\n"
                + String.join("\n", g5.lines()));
    }

    @Test
    void theReferencePositionsAreDeterministicDistinctAndInRange() {
        List<int[]> lists = ReferencePositions.moveLists();
        assertEquals(ReferencePositions.COUNT, lists.size());
        Set<Position> distinct = new HashSet<>();
        for (int[] moves : lists) {
            assertTrue(moves.length >= ReferencePositions.MIN_PLY && moves.length <= ReferencePositions.MAX_PLY,
                    "ply " + moves.length);
            Position pos = ReferencePositions.replay(moves);
            assertNull(pos.outcome(), "a reference position must not be terminal");
            assertTrue(pos.legalMoves().length > 0);
            distinct.add(pos);
        }
        assertEquals(ReferencePositions.COUNT, distinct.size());
        // callers get copies: changing one does not change the set
        lists.get(0)[0] = -1;
        assertTrue(ReferencePositions.moveLists().get(0)[0] != -1);
        for (int g = 0; g < ReferencePositions.FULL_GAMES; g++) {
            assertEquals(ReferencePositions.OPENING_PLIES, ReferencePositions.gameOpening(g).length);
        }
    }

    @Test
    void withNodesReplacesTheNodeBudget() {
        assertEquals("ab:eval=linear,pvs=1,nodes=5000", Fingerprint.withNodes("ab:eval=linear,nodes=20000,pvs=1", 5000));
        assertEquals("ab:nodes=7", Fingerprint.withNodes("ab", 7));
        assertEquals(5000, PlayerSpec.parse(Fingerprint.withNodes(ReferencePositions.R2_SPEC, 5000)).nodes());
    }

    @Test
    void theFrozenAliasIsRegisteredAndCoveredByTheContract() {
        assertTrue(Evaluators.names().contains("linear-r1"));
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith("ab:eval=linear-r1,")),
                "the strategy contract must cover linear-r1");
        assertEquals("linear-r1", PlayerSpec.parse(ReferencePositions.R2_SPEC).evaluator());
        assertEquals(20_000, PlayerSpec.parse(ReferencePositions.R2_SPEC).nodes());
    }

    // Recorded 2026-11-07, code of commit cb4b750 plus E00. Re-record only with a written reason (see above).
    private static final long R2_POSITIONS = -8723226000912328943L;
    private static final long R2_GAME_1000 = -6000360130468970639L;
    private static final long R2_GAME_5000 = -1429733491373947983L;
    private static final long BEST_POSITIONS = -8723226000912328943L;
    private static final long BEST_GAME_1000 = -6000360130468970639L;
    private static final long BEST_GAME_5000 = -1429733491373947983L;
}
