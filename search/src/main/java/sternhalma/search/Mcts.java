package sternhalma.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.nn.Evaluation;
import sternhalma.nn.ForwardPass;
import sternhalma.tablebase.RaceTable;

/**
 * Monte Carlo tree search with PUCT, the network as prior and value, and batched leaf evaluation (docs/training.md,
 * "As implemented (block 2g)").
 *
 * <h2>One simulation</h2>
 *
 * <p>From the root, each node takes the edge with the highest {@code Q + c * P * sqrt(N_parent) / (1 + N)} (see
 * {@link MctsNode#puct}); Q is the mean value of the edge for the side that plays it, an edge without visits counts as
 * the mean of its node minus {@code fpu}. The walk ends at an edge whose child does not exist yet. That child is
 * either <em>exact</em> or <em>a leaf for the network</em>:
 * <ul>
 *   <li>the game is over there ({@link Position#outcome()}, with the engine's values for a draw by the move limit, so
 *       {@code SCORE_BY_DISTANCE} scores count as they do in a game): every visit backs up the outcome;</li>
 *   <li>the position is passed ({@link RaceAdjudication#passed}) and S1 ({@link RaceAdjudication}) has a verdict: the
 *       winner gets +1, the other -1. S1 is the only source of an exact value there; the race table and the race
 *       evaluator of E08 are not used as values (the band rule of docs/search-and-evaluation.md, E08);</li>
 *   <li>else the position goes into a batch, the network evaluates the batch in one forward pass, and each leaf is
 *       expanded with its priors (softmax over its legal moves, divided by the prior temperature) and backs up the
 *       network's value of every player ({@link Evaluation#values()} is absolute, so each edge on the path adds the
 *       value of its own mover; there is no sign to flip, in two or in three players).</li>
 * </ul>
 *
 * <h2>Batches and virtual loss</h2>
 *
 * <p>Up to {@code batch} descents run before the network is called. Every edge on a path gets one virtual loss, which
 * counts as a visit that lost, so that the next descent takes another path; when the leaf is backed up (or the descent
 * ends in an exact leaf at once, or in a node that is still waiting for the network) exactly these virtual losses are
 * taken off again. A descent that reaches a node that is waiting for the network gives up, undoes its virtual loss and
 * ends the batch; it is not counted, so the simulations run are exactly {@code sims} (unless a time budget stops the
 * search). Leaves are backed up in the order of the descents, and every position is evaluated on its own, so the
 * result does not depend on the batch's order of evaluation. With {@code batch=1} the search is the plain sequential
 * one.
 *
 * <h2>Win in one</h2>
 *
 * <p>When a node is expanded, every legal move is tried on a copy of the position; a move that wins right away
 * ({@link Outcome}, so under the engine's rules for both players winning at once) is the node's {@code winEdge}: the
 * node backs up the exact win instead of the network's value and the selection takes that move every time. A
 * mate in one is found with a single simulation, whatever the network says.
 *
 * <h2>Root</h2>
 *
 * <p>Dirichlet noise ({@code noise}, {@code alpha}) is mixed into the root priors for the selection only; the stored
 * priors stay clean, so a reused tree does not keep noise. The move is the most visited one (ties: the higher mean
 * value, then the higher prior, then the first), or with {@code temp > 0} a draw proportional to
 * {@code visits^(1/temp)}. A position with a single legal move is not searched.
 *
 * <p>With {@code reuse}, the next search starts from the subtree whose position is the next position to move in (the
 * played move and the reply, found by hash and ply), if the tree has it.
 *
 * <p><b>State and threads.</b> All state belongs to the instance: the tree, the random generator (seeded; the only
 * source of randomness), the S1 scratch space and the buffers. Not thread-safe; one instance per player. The network
 * is used as it is, so give each thread a {@link ForwardPass} that is {@link ForwardPass#threadSafe()} or its own.
 * A failed network call (non-finite output) drops the tree before the exception goes on, so no virtual loss stays.
 */
public final class Mcts {

    private final sternhalma.nn.Evaluator evaluator;
    private final MctsOptions options;
    private final RaceAdjudication s1;
    private final RandomGenerator random;
    private MctsNode root;
    private MctsNode[] pathNodes = new MctsNode[256];
    private int[] pathEdges = new int[256];
    private int exactLeaves;

    /**
     * @param network the network, two players or as many as the positions will have
     * @param options the options
     * @param seed    seeds the noise and the sampling of moves
     */
    public Mcts(ForwardPass network, MctsOptions options, long seed) {
        this.evaluator = new sternhalma.nn.Evaluator(Objects.requireNonNull(network, "network"));
        this.options = Objects.requireNonNull(options, "options");
        this.s1 = options.s1() ? new RaceAdjudication(RaceTable.rows2()) : null;
        this.random = RandomGeneratorFactory.of("SplittableRandom").create(seed);
    }

    public MctsOptions options() {
        return options;
    }

    /** The root of the last search (tests); null before the first search or after a failed one. */
    MctsNode root() {
        return root;
    }

    /**
     * Searches the position and chooses a move. The position is not changed.
     *
     * @throws IllegalArgumentException if the game is over in the position or there is no legal move
     * @throws IllegalStateException    if the network gives non-finite outputs
     */
    public MctsResult search(Position position) {
        Position pos = position.copy();
        if (pos.outcome() != null) {
            throw new IllegalArgumentException("the game is over in this position");
        }
        int[] legal = pos.legalMoves();
        if (legal.length == 0) {
            throw new IllegalArgumentException("the position has no legal move");
        }
        exactLeaves = 0;
        try {
            MctsNode start = reusableRoot(pos);
            if (start == null) {
                start = new MctsNode(pos.sideToMove(), pos.hash(), pos.ply());
                root = start;
                expand(start, pos, evaluator.evaluate(pos));
            }
            root = start;
            if (legal.length == 1) {
                return result(start, 0, 0); // the root's only move
            }
            start.searchPrior = options.noise() > 0 ? noisy(start.prior) : null;
            int done = simulate(pos, start);
            start.searchPrior = null;
            return result(start, done, chooseEdge(start));
        } catch (RuntimeException | Error e) {
            root = null; // a failure inside a batch leaves virtual loss and pending nodes behind
            throw e;
        }
    }

    // ---- simulations ----

    private enum Step { EXACT, LEAF, COLLISION }

    /** A position waiting for the network, with the path of its descent. */
    private record Leaf(MctsNode node, MctsNode[] nodes, int[] edges, Position position) {
    }

    private int simulate(Position pos, MctsNode start) {
        long deadline = options.millis() == Long.MAX_VALUE ? Long.MAX_VALUE
                : System.nanoTime() + options.millis() * 1_000_000L;
        int done = 0;
        while (done < options.sims()) {
            if (done > 0 && System.nanoTime() >= deadline) {
                break;
            }
            List<Leaf> batch = new ArrayList<>(options.batch());
            boolean collided = false;
            while (!collided && batch.size() < options.batch() && done + batch.size() < options.sims()) {
                switch (descend(pos, start, batch)) {
                    case EXACT -> done++;
                    case LEAF -> { }
                    case COLLISION -> collided = true;
                }
            }
            if (!batch.isEmpty()) {
                List<Position> positions = new ArrayList<>(batch.size());
                for (Leaf leaf : batch) {
                    positions.add(leaf.position());
                }
                List<Evaluation> evaluations = evaluator.evaluate(positions);
                for (int i = 0; i < batch.size(); i++) {
                    Leaf leaf = batch.get(i);
                    finish(leaf, evaluations.get(i));
                }
                done += batch.size();
            }
        }
        return done;
    }

    /** One walk from the root; the position is back at the root when it returns. */
    private Step descend(Position pos, MctsNode start, List<Leaf> batch) {
        int depth = 0;
        int made = 0;
        MctsNode node = start;
        try {
            while (true) {
                int edge = node.select(options.cpuct(), options.fpu());
                node.addVirtualLoss(edge);
                if (depth == pathNodes.length) {
                    pathNodes = Arrays.copyOf(pathNodes, depth * 2);
                    pathEdges = Arrays.copyOf(pathEdges, depth * 2);
                }
                pathNodes[depth] = node;
                pathEdges[depth] = edge;
                depth++;
                pos.make(node.moves[edge]);
                made++;
                MctsNode child = node.child[edge];
                if (child == null) {
                    double[] exact = exactValues(pos);
                    child = new MctsNode(pos.sideToMove(), pos.hash(), pos.ply());
                    node.child[edge] = child;
                    if (exact != null) {
                        child.exact = exact;
                        backup(pathNodes, pathEdges, depth, exact);
                        exactLeaves++;
                        return Step.EXACT;
                    }
                    child.pending = true;
                    batch.add(new Leaf(child, Arrays.copyOf(pathNodes, depth), Arrays.copyOf(pathEdges, depth),
                            pos.copy()));
                    return Step.LEAF;
                }
                if (child.exact != null) {
                    backup(pathNodes, pathEdges, depth, child.exact);
                    exactLeaves++;
                    return Step.EXACT;
                }
                if (child.pending) {
                    for (int k = 0; k < depth; k++) {
                        pathNodes[k].removeVirtualLoss(pathEdges[k]);
                    }
                    return Step.COLLISION;
                }
                node = child;
            }
        } finally {
            for (; made > 0; made--) {
                pos.unmake();
            }
        }
    }

    /**
     * The exact values of all players if the position is a leaf of the game, else null: the end of the game, or a
     * passed position with an S1 verdict.
     */
    private double[] exactValues(Position pos) {
        Outcome outcome = pos.outcome();
        if (outcome != null) {
            return outcome.values();
        }
        if (s1 != null && RaceAdjudication.passed(pos)) {
            RaceAdjudication.Verdict verdict = s1.adjudicate(pos);
            if (verdict != null) {
                double[] values = new double[2];
                values[verdict.winner()] = 1;
                values[1 - verdict.winner()] = -1;
                return values;
            }
        }
        return null;
    }

    /** Expands a leaf with its evaluation and backs it up. */
    private void finish(Leaf leaf, Evaluation evaluation) {
        double[] values = expand(leaf.node(), leaf.position(), evaluation);
        if (leaf.node().exact != null) {
            exactLeaves++;
        }
        backup(leaf.nodes(), leaf.edges(), leaf.nodes().length, values);
    }

    /**
     * Fills the node from the evaluation and returns the values to back up: the network's, or the exact ones of a
     * move that wins at once, or of the end of the game when the side to move has no move.
     */
    private double[] expand(MctsNode node, Position pos, Evaluation evaluation) {
        int[] moves = evaluation.moves();
        if (moves.length == 0) {
            double[] values = pos.noMovesOutcome().values();
            node.exact = values;
            node.pending = false;
            return values;
        }
        double[] values = new double[evaluation.values().length];
        for (int a = 0; a < values.length; a++) {
            values[a] = evaluation.values()[a];
        }
        node.expand(moves, temper(evaluation.priors(), options.priorTemperature()), values[node.mover]);
        if (options.win1()) {
            for (int i = 0; i < moves.length; i++) {
                pos.make(moves[i]);
                Outcome outcome = pos.outcome();
                pos.unmake();
                if (outcome != null && outcome.hasWinner() && outcome.winner() == node.mover) {
                    node.winEdge = i;
                    node.value = outcome.value(node.mover);
                    return outcome.values();
                }
            }
        }
        return values;
    }

    /** Takes the virtual loss off every edge of the path and records the visit with the value of each edge's mover. */
    private static void backup(MctsNode[] nodes, int[] edges, int depth, double[] values) {
        for (int k = 0; k < depth; k++) {
            nodes[k].removeVirtualLoss(edges[k]);
            nodes[k].record(edges[k], values[nodes[k].mover]);
        }
    }

    // ---- priors, noise, move choice ----

    /** The softmax with temperature t from the softmax with temperature 1: {@code p^(1/t)}, renormalised. */
    static float[] temper(float[] priors, double t) {
        if (t == 1.0) {
            return priors;
        }
        double[] w = new double[priors.length];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = Math.pow(priors[i], 1.0 / t);
            sum += w[i];
        }
        float[] result = new float[w.length];
        for (int i = 0; i < w.length; i++) {
            result[i] = (float) (sum > 0 ? w[i] / sum : 1.0 / w.length);
        }
        return result;
    }

    /** {@code (1 - noise) * prior + noise * Dirichlet(alpha)}. */
    private float[] noisy(float[] prior) {
        double[] g = new double[prior.length];
        double sum = 0;
        for (int i = 0; i < g.length; i++) {
            g[i] = gamma(random, options.dirichlet());
            sum += g[i];
        }
        float[] result = new float[prior.length];
        for (int i = 0; i < g.length; i++) {
            double d = sum > 0 ? g[i] / sum : 1.0 / g.length;
            result[i] = (float) ((1 - options.noise()) * prior[i] + options.noise() * d);
        }
        return result;
    }

    /** A Gamma(alpha, 1) sample (Marsaglia and Tsang; for alpha below 1 via alpha + 1). */
    static double gamma(RandomGenerator random, double alpha) {
        if (alpha < 1) {
            double u;
            do {
                u = random.nextDouble();
            } while (u == 0);
            return gamma(random, alpha + 1) * Math.pow(u, 1.0 / alpha);
        }
        double d = alpha - 1.0 / 3;
        double c = 1.0 / Math.sqrt(9 * d);
        while (true) {
            double x = random.nextGaussian();
            double v = 1 + c * x;
            if (v <= 0) {
                continue;
            }
            v = v * v * v;
            double u = random.nextDouble();
            if (u > 0 && Math.log(u) < 0.5 * x * x + d - d * v + d * Math.log(v)) {
                return d * v;
            }
        }
    }

    private int chooseEdge(MctsNode node) {
        int n = node.moves.length;
        if (options.temperature() > 0 && node.totalVisits > 0) {
            double[] w = new double[n];
            double total = 0;
            for (int i = 0; i < n; i++) {
                w[i] = Math.pow(node.visits[i], 1.0 / options.temperature());
                total += w[i];
            }
            double r = random.nextDouble() * total;
            double acc = 0;
            int last = 0;
            for (int i = 0; i < n; i++) {
                if (w[i] > 0) {
                    acc += w[i];
                    last = i;
                    if (r < acc) {
                        return i;
                    }
                }
            }
            return last;
        }
        int best = 0;
        for (int i = 1; i < n; i++) {
            if (better(node, i, best)) {
                best = i;
            }
        }
        return best;
    }

    /** Whether edge i beats edge j for the final choice: visits, then mean value, then prior. */
    private static boolean better(MctsNode node, int i, int j) {
        if (node.visits[i] != node.visits[j]) {
            return node.visits[i] > node.visits[j];
        }
        if (node.visits[i] > 0) {
            double qi = node.sum[i] / node.visits[i];
            double qj = node.sum[j] / node.visits[j];
            if (qi != qj) {
                return qi > qj;
            }
        }
        return node.prior[i] > node.prior[j];
    }

    private MctsResult result(MctsNode node, int sims, int edge) {
        int n = node.moves.length;
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = node.visits[i] > 0 ? node.sum[i] / node.visits[i] : 0;
        }
        return new MctsResult(node.moves[edge], node.moves.clone(), node.visits.clone(), values, node.mean(), sims,
                node.size(), exactLeaves);
    }

    // ---- reuse ----

    /** The node of the tree of the last search whose position is {@code pos}, if reuse is on and the tree has it. */
    private MctsNode reusableRoot(Position pos) {
        if (!options.reuse() || root == null) {
            return null;
        }
        return find(root, pos, pos.rules().players());
    }

    private static MctsNode find(MctsNode node, Position pos, int depth) {
        if (!node.isExpanded() || node.exact != null) {
            return null;
        }
        if (node.hash == pos.hash() && node.ply == pos.ply()) {
            return node;
        }
        if (depth == 0) {
            return null;
        }
        for (MctsNode child : node.child) {
            if (child != null) {
                MctsNode found = find(child, pos, depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
