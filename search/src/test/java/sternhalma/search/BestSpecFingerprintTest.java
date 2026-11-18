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
 * Pins the search trees of the reference players R2 ({@link ReferencePositions#R2_SPEC}, the frozen alias
 * {@code linear-r1}) and R3 ({@link ReferencePositions#R3_SPEC}, the frozen evaluator {@code racelin1-r3}) and of
 * {@link Strategies#BEST_SPEC} at 20000 nodes: for each spec the {@link Fingerprint} over the 64
 * {@link ReferencePositions} (move, score, completed depth and node count of the node-limited search, and move,
 * score and exact node count of a depth-4 search) and over the move sequences and node totals of one self-play
 * game at 1000 and one at 5000 nodes. The R2 constants were recorded with the code of commit cb4b750 plus round-2
 * experiment E00 (which only adds code); the R3 constants by the integration commit of round 2, which also
 * re-recorded the BEST constants, because it switched BEST_SPEC. At each freeze the new reference and BEST_SPEC
 * search the same tree, so their constants are equal until the next integration moves BEST_SPEC.
 *
 * <p><b>Rule.</b> An identical-tree optimisation keeps all nine constants unchanged. A feature that changes the
 * tree is off by default (a new spec key), so it changes no constant while it is off. Changing the weights of
 * {@code linear} legitimately changes the {@code BEST_*} constants only while BEST_SPEC uses {@code linear} (it did
 * until the integration of round 2); the {@code R2_*} and {@code R3_*} constants change only when the engine
 * legitimately changes what the reference players play (for example its hashing or the rules), and then the frozen
 * jars stay the cost references. Any re-record needs a written reason in the commit message and in
 * docs/search-and-evaluation.md ("Round 2 yardstick").
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

    /** The recorded constants of one spec: the positions checksum and the two game checksums. */
    private record Recorded(long positions, long game1000, long game5000) {
    }

    @Test
    void referencePlayerR2SearchesTheRecordedTree() {
        assertParts(ReferencePositions.R2_SPEC, R2);
    }

    @Test
    void referencePlayerR3SearchesTheRecordedTree() {
        Rows1Tables.assume();
        assertParts(ReferencePositions.R3_SPEC, R3);
    }

    @Test
    void bestSpecSearchesTheRecordedTree() {
        Rows1Tables.assume();
        assertParts(BEST_20000, BEST);
    }

    private static void assertParts(String spec, Recorded r) {
        Fingerprint.Part p = Fingerprint.positions(spec);
        assertEquals(r.positions(), p.checksum(), () -> "tree of " + spec + " on the reference positions changed:\n"
                + String.join("\n", p.lines()));
        Fingerprint.Part g1 = Fingerprint.game(spec, Fingerprint.GAME_NODES[0]);
        assertEquals(r.game1000(), g1.checksum(), () -> "game at 1000 nodes of " + spec + " changed:\n"
                + String.join("\n", g1.lines()));
        Fingerprint.Part g5 = Fingerprint.game(spec, Fingerprint.GAME_NODES[1]);
        assertEquals(r.game5000(), g5.checksum(), () -> "game at 5000 nodes of " + spec + " changed:\n"
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

    /**
     * R3 is the integrated BEST of round 2 at 20000 nodes: the frozen evaluator {@code racelin1-r3} (race arithmetic
     * from the rows&gt;=1 table over the frozen round-3 weights), futility pruning and race play with that table.
     */
    @Test
    void theFrozenReferenceR3IsRegisteredAndCoveredByTheContract() {
        PlayerSpec r3 = PlayerSpec.parse(ReferencePositions.R3_SPEC);
        assertTrue(Evaluators.names().contains("racelin1-r3"));
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith("ab:eval=racelin1-r3,")),
                "the strategy contract must cover racelin1-r3");
        assertEquals("racelin1-r3", r3.evaluator());
        assertEquals(20_000, r3.nodes());
        assertTrue(r3.options().futilityOn());
        assertEquals(1, r3.race());
        assertEquals(1, r3.raceRows());
        assertTrue(r3.weights().isEmpty(), "the reference takes no live weights");
    }

    // R2: recorded 2026-11-07 with the code of commit cb4b750 plus E00. Re-record only with a written reason (see above).
    private static final Recorded R2 = new Recorded(-8723226000912328943L, -6000360130468970639L, -1429733491373947983L);

    // R3: recorded 2026-11-08 by the integration commit of round 2 (docs/search-and-evaluation.md, "Integration of
    // round 2"). Re-record only with a written reason (see above).
    private static final Recorded R3 = new Recorded(4852147404152522458L, 2660956470353287877L, -3579989785793258016L);

    // BEST: re-recorded 2026-11-08 by the integration commit of round 2, which switched Strategies.BEST_SPEC from the
    // round-1 player (eval=linear, the same tree as R2) to eval=racelin1-r3 with futility pruning and race play. Equal
    // to R3 until the next integration moves BEST_SPEC. The round-1 constants live on in R2 above.
    private static final Recorded BEST = R3;
}
