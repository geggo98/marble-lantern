package sternhalma.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Measures the futility bound of round-2 experiment E06 for an evaluator: how much one move (depth 1) or one move
 * and the opponent's best reply (depth 2) can raise the value above the static value of the node, per step of
 * distance gained by the move. Futility pruning assumes
 * {@code value(move) <= eval(node) + futk x gain + margin}; this tool prints, per gain, the largest and the
 * high-quantile excess {@code value - eval(node) - futk x gain}, from which futk and the margins are set.
 *
 * <p>Positions: self-play games of a spec from random openings ({@value #OPENING_PLIES} plies), every
 * {@value #EVERY}-th position from ply {@value #FROM_PLY}, all legal moves of each. Moves into terminal positions
 * (win, move limit, no moves) and moves into passed positions ({@link LinearTraining#passed}) are excluded from
 * the main table and counted separately; transitions inside or into passed positions get their own table,
 * because in this build (without race play) the search prunes there too.
 *
 * <pre>
 *   java -cp ... sternhalma.search.DeltaProbe probe  [eval] [futk] [games] [seed] [spec]
 *   java -cp ... sternhalma.search.DeltaProbe depth  [games] [seed] [spec ...]   # mean completed depth
 * </pre>
 */
public final class DeltaProbe {

    static final int OPENING_PLIES = 4;
    static final int FROM_PLY = 8;
    static final int EVERY = 3;
    static final int MAX_GAIN = 12;
    static final String DEFAULT_SPEC =
            "ab:eval=linear-r2,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,nodes=1000";

    private DeltaProbe() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        String mode = args.length > 0 ? args[0] : "probe";
        if (mode.equals("depth")) {
            int games = args.length > 1 ? Integer.parseInt(args[1]) : 20;
            long seed = args.length > 2 ? Long.parseLong(args[2]) : 31;
            List<Position> positions = positions(DEFAULT_SPEC, games, seed);
            for (int i = 3; i < args.length; i++) {
                System.out.println(meanDepth(args[i], positions));
            }
            return;
        }
        String eval = args.length > 1 ? args[1] : "linear-r2";
        int futk = args.length > 2 ? Integer.parseInt(args[2]) : SearchOptions.DEFAULT_FUTILITY_GAIN;
        int games = args.length > 3 ? Integer.parseInt(args[3]) : 40;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : 31;
        String spec = args.length > 5 ? args[5] : DEFAULT_SPEC;
        List<Position> positions = positions(spec, games, seed);
        PlayerSpec evalSpec = PlayerSpec.parse("ab:eval=" + eval); // the eval argument may carry weights, e.g. racelin,wout=1
        Report r = probe(Evaluators.byName(evalSpec.evaluator(), evalSpec.weights()), futk, positions);
        System.out.println("# DeltaProbe eval=" + eval + " futk=" + futk + " positions=" + positions.size()
                + " (" + games + " games of " + spec + ", seed " + seed + ")");
        System.out.print(r);
    }

    /**
     * Raw value changes {@code value(move) - eval(node)} per gain (index gain + MAX_GAIN), printed as the excess over
     * {@code futk x gain}. Column {@code cut} is what the pruning rule needs: the search breaks at the first move
     * whose bound fails and skips every later move, all with a gain of at most that move's gain g0, so the margin
     * must cover {@code max over gain <= g0 of (value - eval) - futk x g0}.
     */
    static final class Table {
        final List<List<Integer>> raw = new ArrayList<>();
        final int futk;

        Table(int futk) {
            this.futk = futk;
            for (int g = -MAX_GAIN; g <= MAX_GAIN; g++) {
                raw.add(new ArrayList<>());
            }
        }

        void add(int gain, int delta) {
            raw.get(Math.max(-MAX_GAIN, Math.min(MAX_GAIN, gain)) + MAX_GAIN).add(delta);
        }

        int count() {
            return raw.stream().mapToInt(List::size).sum();
        }

        /** The largest excess {@code delta - futk x gain} over all moves, Integer.MIN_VALUE if empty. */
        int maxExcess() {
            int max = Integer.MIN_VALUE;
            for (int g = -MAX_GAIN; g <= MAX_GAIN; g++) {
                for (int d : raw.get(g + MAX_GAIN)) {
                    max = Math.max(max, d - futk * g);
                }
            }
            return max;
        }

        /** The margin the break rule needs when it cuts at gain g0 (see the class comment). */
        int cutMargin(int g0) {
            int max = Integer.MIN_VALUE;
            for (int g = -MAX_GAIN; g <= g0; g++) {
                for (int d : raw.get(g + MAX_GAIN)) {
                    max = Math.max(max, d - futk * g0);
                }
            }
            return max;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder("gain      n  maxraw     max   q99.9    q99     q90  median     cut\n");
            for (int g = -MAX_GAIN; g <= MAX_GAIN; g++) {
                List<Integer> list = raw.get(g + MAX_GAIN);
                if (list.isEmpty()) {
                    continue;
                }
                int shift = futk * g;
                int[] v = list.stream().mapToInt(d -> d - shift).sorted().toArray();
                b.append(String.format("%4d %7d %7d %7d %7d %7d %7d %7d %7d%n", g, v.length, v[v.length - 1] + shift,
                        v[v.length - 1], q(v, 0.999), q(v, 0.99), q(v, 0.90), q(v, 0.5), cutMargin(g)));
            }
            return b.toString();
        }

        private static int q(int[] sorted, double p) {
            return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * sorted.length))];
        }
    }

    /**
     * The result of a probe: depth-1 and depth-2 excess tables, split by passed transitions and by the evaluator's
     * {@link Evaluator#futilitySafe} hook (E08), and counts. Moves at a node where the hook forbids pruning at that
     * depth go to the {@code hook} tables, whatever their children look like; the search never prunes them. For an
     * evaluator without a hook (linear) those tables are empty and the output is that of E06.
     */
    static final class Report {
        final Table depth1;
        final Table depth2;
        final Table depth1Passed;
        final Table depth2Passed;
        final Table depth1Hook;
        final Table depth2Hook;
        long terminal;
        long passedMoves;
        long passedNodes;
        long unsafeNodes1;
        long unsafeNodes2;

        Report(int futk) {
            depth1 = new Table(futk);
            depth2 = new Table(futk);
            depth1Passed = new Table(futk);
            depth2Passed = new Table(futk);
            depth1Hook = new Table(futk);
            depth2Hook = new Table(futk);
        }

        @Override
        public String toString() {
            return "# depth 1: value(move) - eval(node) - futk*gain, non-terminal, non-passed children ("
                    + depth1.count() + " moves)\n" + depth1
                    + "# depth 2: value after the opponent's best reply - eval(node) - futk*gain (" + depth2.count() + " moves)\n" + depth2
                    + "# depth 1, child passed (" + depth1Passed.count() + " moves; with racelin, E08's hook excludes these)\n"
                    + depth1Passed
                    + "# depth 2, child passed (" + depth2Passed.count() + " moves)\n" + depth2Passed
                    + "# depth 1, node excluded by the evaluator's futilitySafe hook (" + depth1Hook.count()
                    + " moves at " + unsafeNodes1 + " nodes; never pruned)\n" + depth1Hook
                    + "# depth 2, node excluded by the hook (" + depth2Hook.count() + " moves at " + unsafeNodes2
                    + " nodes)\n" + depth2Hook
                    + "# excluded: " + terminal + " moves into terminal positions, " + passedMoves
                    + " moves into passed positions; " + passedNodes + " of the nodes are passed themselves\n";
        }
    }

    /** Positions from self-play of {@code spec}: every {@value #EVERY}-th from ply {@value #FROM_PLY}. */
    static List<Position> positions(String spec, int games, long seed) {
        List<Position> out = new ArrayList<>();
        PlayerSpec ps = PlayerSpec.parse(spec);
        for (int g = 0; g < games; g++) {
            SplittableRandom random = new SplittableRandom(seed * 1_000_003L + g);
            Position pos = Position.initial(RuleSet.defaults(2));
            for (int i = 0; i < OPENING_PLIES; i++) {
                int[] moves = pos.legalMoves();
                pos.make(moves[random.nextInt(moves.length)]);
            }
            AlphaBetaPlayer[] players = {ps.create(), ps.create()};
            while (pos.outcome() == null && pos.legalMoves().length > 0) {
                if (pos.ply() >= FROM_PLY && pos.ply() % EVERY == 0) {
                    out.add(pos.copy());
                }
                pos.make(players[pos.sideToMove()].choose(pos));
            }
        }
        return out;
    }

    static int gain(Position pos, int move) {
        int p = pos.sideToMove();
        return pos.layout().distance(p, Move.from(move)) - pos.layout().distance(p, Move.to(move));
    }

    /** Whether the position ends the game or has no legal move (not scored by the evaluator). */
    static boolean terminal(Position pos) {
        return pos.outcome() != null || pos.legalMoves().length == 0;
    }

    static Report probe(Evaluator eval, int futk, List<Position> positions) {
        Report r = new Report(futk);
        for (Position node : positions) {
            int stand = eval.evaluate(node);
            boolean safe1 = eval.futilitySafe(node, 1);
            boolean safe2 = eval.futilitySafe(node, 2);
            r.passedNodes += LinearTraining.passed(node) ? 1 : 0;
            r.unsafeNodes1 += safe1 ? 0 : 1;
            r.unsafeNodes2 += safe2 ? 0 : 1;
            for (int m : node.legalMoves()) {
                int g = gain(node, m);
                node.make(m);
                if (terminal(node)) {
                    r.terminal++;
                    node.unmake();
                    continue;
                }
                boolean passed = LinearTraining.passed(node);
                int v1 = -eval.evaluate(node);
                int v2 = Integer.MAX_VALUE; // the mover's value after the opponent's best reply
                for (int reply : node.legalMoves()) {
                    node.make(reply);
                    int v = terminal(node) ? AlphaBeta.terminalScore(node, 0) : eval.evaluate(node);
                    node.unmake();
                    v2 = Math.min(v2, v);
                }
                node.unmake();
                if (passed) {
                    r.passedMoves++;
                }
                (safe1 ? passed ? r.depth1Passed : r.depth1 : r.depth1Hook).add(g, v1 - stand);
                (safe2 ? passed ? r.depth2Passed : r.depth2 : r.depth2Hook).add(g, v2 - stand);
            }
        }
        return r;
    }

    /** Mean completed depth and futility cuts per search of {@code spec} on the positions (fresh searcher each). */
    static String meanDepth(String spec, List<Position> positions) {
        PlayerSpec ps = PlayerSpec.parse(spec);
        long depthSum = 0;
        long cuts = 0;
        long nodes = 0;
        int[] histogram = new int[AlphaBeta.MAX_DEPTH + 1];
        for (Position pos : positions) {
            AlphaBeta ab = new AlphaBeta(Evaluators.byName(ps.evaluator(), ps.weights()), ps.tableBits(), true,
                    ps.options());
            SearchResult res = ab.search(pos, ps.depth(), ps.millis(), ps.nodes());
            depthSum += res.depth();
            histogram[res.depth()]++;
            cuts += ab.futilityCuts();
            nodes += res.nodes();
        }
        int n = positions.size();
        int last = histogram.length - 1;
        while (last > 0 && histogram[last] == 0) {
            last--;
        }
        return String.format("%s: positions=%d mean depth %.3f, nodes/search %.0f, futility cuts/search %.0f,"
                + " depth histogram %s", spec, n, (double) depthSum / n, (double) nodes / n, (double) cuts / n,
                Arrays.toString(Arrays.copyOf(histogram, last + 1)));
    }
}
