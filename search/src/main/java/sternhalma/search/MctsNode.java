package sternhalma.search;

import java.util.Arrays;

/**
 * A node of the MCTS tree. The statistics of a move live on the edge, in the arrays of the node it is played from, and
 * are from the point of view of {@link #mover}, the side to move in that node: {@code visits[i]} real visits,
 * {@code sum[i]} the sum of the values the mover got from them, {@code virtualLoss[i]} visits that are on their way
 * (counted as a loss for the mover until they come back).
 *
 * <p>A node is in one of four states: <em>pending</em> (its position is in a batch and has no moves yet),
 * <em>expanded</em> ({@link #moves} set, priors and a network value), <em>exact</em> ({@link #exact} set: the game is
 * over there, or an S1 verdict decides it, so every visit backs up the same values) or, as an array entry of its
 * parent, absent (the move was never tried). Not thread-safe; a tree belongs to one {@link Mcts}.
 */
final class MctsNode {

    /** The side to move in this node's position. */
    final int mover;
    /** Hash and ply of the position, to find the node again after the moves of a real game (tree reuse). */
    final long hash;
    final int ply;

    boolean pending;
    /** Values of all players (absolute) if the node is exact, else null. */
    double[] exact;

    int[] moves;
    /** Priors over {@link #moves}, after the prior temperature. */
    float[] prior;
    /** The priors the selection uses at the root of a search (with Dirichlet noise), or null. */
    float[] searchPrior;
    int[] visits;
    double[] sum;
    int[] virtualLoss;
    MctsNode[] child;
    int totalVisits;
    int totalVirtual;
    double totalSum;
    /** The network's value of the mover at expansion; the start of the node's mean value. */
    double value;
    /** Index of a move that wins at once, or -1. */
    int winEdge = -1;

    MctsNode(int mover, long hash, int ply) {
        this.mover = mover;
        this.hash = hash;
        this.ply = ply;
    }

    /** An expanded node, for tests and for {@link Mcts}. */
    static MctsNode expanded(int mover, long hash, int ply, int[] moves, float[] prior, double value) {
        MctsNode n = new MctsNode(mover, hash, ply);
        n.expand(moves, prior, value);
        return n;
    }

    void expand(int[] newMoves, float[] newPrior, double newValue) {
        moves = newMoves;
        prior = newPrior;
        visits = new int[newMoves.length];
        sum = new double[newMoves.length];
        virtualLoss = new int[newMoves.length];
        child = new MctsNode[newMoves.length];
        value = newValue;
        pending = false;
    }

    boolean isExpanded() {
        return moves != null && !pending;
    }

    /** The mean value of the node for its mover: the network value counts as one visit. */
    double mean() {
        return (value + totalSum) / (1 + totalVisits);
    }

    /**
     * The value of edge {@code i} for the mover, virtual losses counted as lost visits; an edge without any visit
     * gets the mean value of the node minus {@code fpu}.
     */
    double q(int i, double fpu) {
        int n = visits[i] + virtualLoss[i];
        if (n == 0) {
            return mean() - fpu;
        }
        return (sum[i] - virtualLoss[i]) / n;
    }

    /**
     * The PUCT score {@code Q + c * P * sqrt(N_parent) / (1 + N)}. {@code N_parent} is the number of visits of the
     * node's edges, virtual losses included (at least 1, so that the priors decide the first choice of a node);
     * {@code N} the visits of the edge, virtual losses included.
     */
    static double puct(double q, double prior, double parentVisits, double edgeVisits, double cpuct) {
        return q + cpuct * prior * Math.sqrt(Math.max(1.0, parentVisits)) / (1.0 + edgeVisits);
    }

    /** The edge the selection takes: the winning move if there is one, else the highest PUCT score (ties: first). */
    int select(double cpuct, double fpu) {
        if (winEdge >= 0) {
            return winEdge;
        }
        float[] p = searchPrior != null ? searchPrior : prior;
        double parent = totalVisits + totalVirtual;
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < moves.length; i++) {
            double score = puct(q(i, fpu), p[i], parent, visits[i] + virtualLoss[i], cpuct);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Puts a virtual loss on edge {@code i}. */
    void addVirtualLoss(int i) {
        virtualLoss[i]++;
        totalVirtual++;
    }

    /** Takes exactly that virtual loss off again. */
    void removeVirtualLoss(int i) {
        if (virtualLoss[i] <= 0) {
            throw new IllegalStateException("no virtual loss on edge " + i);
        }
        virtualLoss[i]--;
        totalVirtual--;
    }

    /** A real visit of edge {@code i} with the value {@code v} for the mover. */
    void record(int i, double v) {
        visits[i]++;
        sum[i] += v;
        totalVisits++;
        totalSum += v;
    }

    /** Whether no virtual loss is left in the subtree (an invariant between searches). */
    boolean cleanOfVirtualLoss() {
        if (totalVirtual != 0 || pending) {
            return false;
        }
        if (moves == null) {
            return true;
        }
        for (int i = 0; i < moves.length; i++) {
            if (virtualLoss[i] != 0 || child[i] != null && !child[i].cleanOfVirtualLoss()) {
                return false;
            }
        }
        return true;
    }

    /** Number of nodes in the subtree, this one included. */
    int size() {
        int n = 1;
        if (child != null) {
            for (MctsNode c : child) {
                if (c != null) {
                    n += c.size();
                }
            }
        }
        return n;
    }

    @Override
    public String toString() {
        return "MctsNode[mover=" + mover + ", ply=" + ply + ", visits=" + Arrays.toString(visits) + "]";
    }
}
