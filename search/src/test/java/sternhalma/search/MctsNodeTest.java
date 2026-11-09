package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The PUCT arithmetic, the virtual loss and the statistics of a node, on hand-built trees. */
class MctsNodeTest {

    private static final double EPS = 1e-12;

    /** Three moves with priors 0.5, 0.3, 0.2 and the network value 0.1 for the mover. */
    private static MctsNode node() {
        return MctsNode.expanded(0, 0L, 0, new int[] {10, 11, 12}, new float[] {0.5f, 0.3f, 0.2f}, 0.1);
    }

    @Test
    void puctIsQPlusCTimesPriorTimesSqrtOfParentOverOnePlusVisits() {
        // 0.25 + 1.5 * 0.4 * sqrt(9) / (1 + 2) = 0.25 + 1.5 * 0.4 * 3 / 3 = 0.85
        assertEquals(0.85, MctsNode.puct(0.25, 0.4, 9, 2, 1.5), EPS);
        // sqrt(N_parent) and 1 + N must not be swapped: 0.25 + 1.5 * 0.4 * sqrt(2) / (1 + 9)
        assertEquals(0.25 + 1.5 * 0.4 * Math.sqrt(2) / 10, MctsNode.puct(0.25, 0.4, 2, 9, 1.5), EPS);
        assertEquals(-0.5, MctsNode.puct(-0.5, 0.9, 25, 3, 0.0), EPS, "c = 0 leaves Q alone");
    }

    @Test
    void aNodeWithoutVisitsUsesOneAsParentVisitsSoThePriorsDecide() {
        assertEquals(0.3 * 1.5 * 1.0 / 1.0, MctsNode.puct(0, 0.3, 0, 0, 1.5), EPS);
        MctsNode n = node();
        assertEquals(0, n.select(1.5, 0.0), "highest prior first");
        MctsNode m = MctsNode.expanded(0, 0L, 0, new int[] {10, 11, 12}, new float[] {0.2f, 0.3f, 0.5f}, 0.0);
        assertEquals(2, m.select(1.5, 0.0));
    }

    @Test
    void selectTakesTheHighestScoreOfAHandBuiltTree() {
        MctsNode n = node();
        // edge 0: 4 visits, sum 2.0 (Q 0.5); edge 1: 1 visit, sum 0.6 (Q 0.6); edge 2: unvisited (Q = mean - fpu)
        for (int k = 0; k < 4; k++) {
            n.record(0, 0.5);
        }
        n.record(1, 0.6);
        // total visits 5; mean = (0.1 + 2.6) / 6 = 0.45
        assertEquals(0.45, n.mean(), EPS);
        double s0 = 0.5 + 1.5 * 0.5 * Math.sqrt(5) / 5;
        double s1 = 0.6 + 1.5 * 0.3 * Math.sqrt(5) / 2;
        double s2 = 0.45 + 1.5 * 0.2 * Math.sqrt(5) / 1;
        assertTrue(s2 > s1 && s1 > s0, "hand-computed order " + s0 + " " + s1 + " " + s2);
        assertEquals(2, n.select(1.5, 0.0));
        // with a first-play reduction of 0.5 the unvisited edge drops to Q = -0.05: s2 = -0.05 + 0.6708 = 0.62 < s1
        assertEquals(1, n.select(1.5, 0.5));
        // a smaller constant makes the visited edge with the best Q win: Q 0.6 against 0.45 (unvisited)
        assertEquals(1, n.select(0.1, 0.0));
    }

    @Test
    void anUnvisitedEdgeCountsAsTheNodeMeanMinusFpu() {
        MctsNode n = node();
        assertEquals(0.1, n.q(1, 0.0), EPS, "no visits: the network value");
        assertEquals(-0.15, n.q(1, 0.25), EPS);
        n.record(0, 0.9);
        assertEquals((0.1 + 0.9) / 2, n.q(2, 0.0), EPS, "mean = (value + sum) / (1 + visits)");
        assertEquals(0.9, n.q(0, 0.0), EPS);
    }

    @Test
    void virtualLossCountsAsALostVisitAndLeavesWithoutATrace() {
        MctsNode n = node();
        n.record(0, 0.5);
        n.record(0, 0.3); // 2 visits, sum 0.8
        n.addVirtualLoss(0);
        // (0.8 - 1) / 3
        assertEquals(-0.2 / 3, n.q(0, 0.0), EPS);
        assertEquals(1, n.totalVirtual);
        // the parent count of the U term includes the virtual loss
        double parent = 2 + 1;
        assertEquals(n.q(0, 0.0) + 1.5 * 0.5 * Math.sqrt(parent) / 4,
                MctsNode.puct(n.q(0, 0.0), 0.5, parent, 3, 1.5), EPS);
        n.removeVirtualLoss(0);
        assertEquals(0.4, n.q(0, 0.0), EPS);
        assertEquals(0, n.totalVirtual);
        assertTrue(n.cleanOfVirtualLoss());
        assertEquals(2, n.visits[0], "removing a virtual loss is not a visit");
        assertEquals(0.8, n.sum[0], EPS);
    }

    @Test
    void aVirtualLossOnTheBestEdgeMakesTheNextSelectionTakeAnother() {
        MctsNode n = node();
        int first = n.select(1.5, 0.0);
        n.addVirtualLoss(first);
        int second = n.select(1.5, 0.0);
        assertTrue(second != first, "virtual loss diversifies the batch");
        n.addVirtualLoss(second);
        n.removeVirtualLoss(first);
        n.removeVirtualLoss(second);
        assertEquals(first, n.select(1.5, 0.0), "back to the start");
    }

    @Test
    void theParentVisitsOfTheSelectionIncludeTheVirtualLossesOfOtherEdges() {
        // priors 0.6 / 0.35 / 0.05, network value 0; edge 0 has 2 visits with mean 0.9, so the node mean is 0.6
        MctsNode n = MctsNode.expanded(0, 0L, 0, new int[] {1, 2, 3}, new float[] {0.6f, 0.35f, 0.05f}, 0.0);
        n.record(0, 0.9);
        n.record(0, 0.9);
        // 2 parent visits: edge 0 scores 0.9 + 0.6 sqrt(2) / 3 = 1.183, the unvisited edge 1 scores 0.6 + 0.35 sqrt(2) = 1.095
        assertEquals(0, n.select(1.0, 0.0));
        // 20 virtual losses on the third edge raise the parent count to 22: 0.9 + 0.6 sqrt(22) / 3 = 1.84 against
        // 0.6 + 0.35 sqrt(22) = 2.24, so the unvisited edge is taken
        for (int k = 0; k < 20; k++) {
            n.addVirtualLoss(2);
        }
        assertEquals(1, n.select(1.0, 0.0));
    }

    @Test
    void removingAVirtualLossThatWasNeverAddedFails() {
        MctsNode n = node();
        assertThrows(IllegalStateException.class, () -> n.removeVirtualLoss(1));
        n.addVirtualLoss(1);
        n.removeVirtualLoss(1);
        assertThrows(IllegalStateException.class, () -> n.removeVirtualLoss(1));
    }

    @Test
    void aWinningEdgeIsAlwaysTheChoiceAndTiesGoToTheFirstMove() {
        MctsNode n = node();
        n.winEdge = 2;
        assertEquals(2, n.select(1.5, 0.0));
        MctsNode tie = MctsNode.expanded(1, 0L, 0, new int[] {1, 2, 3}, new float[] {0.25f, 0.25f, 0.5f}, 0.0);
        tie.record(0, 0.2);
        tie.record(1, 0.2);
        // equal visits, equal Q, equal priors for edges 0 and 1; edge 2 unvisited with a larger prior wins here
        assertEquals(2, tie.select(1.5, 0.0));
        MctsNode flat = MctsNode.expanded(1, 0L, 0, new int[] {1, 2, 3}, new float[] {0.2f, 0.2f, 0.2f}, 0.0);
        assertEquals(0, flat.select(1.5, 0.0), "a tie takes the first move in move order");
    }

    @Test
    void theSearchPriorReplacesTheStoredPriorOnlyWhileSet() {
        MctsNode n = node();
        n.searchPrior = new float[] {0.0f, 0.0f, 1.0f};
        assertEquals(2, n.select(1.5, 0.0));
        n.searchPrior = null;
        assertEquals(0, n.select(1.5, 0.0));
    }

    @Test
    void recordingAddsToTheEdgeAndTheNodeTotals() {
        MctsNode n = node();
        n.record(1, -0.5);
        n.record(1, 0.25);
        n.record(2, 1.0);
        assertEquals(2, n.visits[1]);
        assertEquals(-0.25, n.sum[1], EPS);
        assertEquals(3, n.totalVisits);
        assertEquals(0.75, n.totalSum, EPS);
        assertFalse(n.cleanOfVirtualLoss() && n.pending);
        assertEquals(1, n.size());
    }
}
