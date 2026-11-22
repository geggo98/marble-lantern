package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Encoder;
import sternhalma.nn.Evaluation;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.HexGrid;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Orientation;
import sternhalma.nn.Parameters;
import sternhalma.nn.ReferenceOps;
import sternhalma.nn.ResidualPriors;
import sternhalma.nn.Tensor;

/**
 * Generates and verifies the JSON data files of the About figures ({@code web/src/about/data/}, plan 6.3), and one
 * fixture of the TypeScript net tests ({@code web/src/engines/net/testdata/forward-mover1.json}).
 *
 * <p><b>Modes.</b> By default (CI) the test regenerates every file in memory and compares it with the committed
 * file: integers exactly, floating-point numbers within 1e-4. With {@code -Dexplainer.write=true} (Gradle:
 * {@code -Pexplainer.write=true}; devenv task {@code about:data}) it writes the files instead. The asserted anchors
 * (node counts, scores, parameter sums, ...) run in both modes, so a file is never written from a wrong trace.
 *
 * <p><b>The traced search.</b> The engine's {@link AlphaBeta} has no trace hook, and production code stays as it is
 * (plan, risk 2). {@link Tracer} is a replica of the baseline search ({@link SearchOptions#DEFAULT}, evaluator
 * {@code distance}): iterative deepening, negamax with alpha-beta, the engine's own move generation, its own ordering
 * keys ({@code AlphaBeta.pick}) and {@code AlphaBeta.terminalScore}. It leaves out the transposition table, which
 * cannot hit up to depth 3 from the start position (from depth 4 it does: 1337 nodes with the table, 1490 without). The
 * test asserts that the replica matches {@link AlphaBeta} in node count, score and move at depths 1 to 3 of the
 * start position, and that the engine itself gives the node counts of the fact sheet (14, 55, 441, 1337, 8122), so a
 * drift of the replica fails here.
 *
 * <p><b>Models.</b> The shipped models of {@code web/models} (gzip, F16) are inflated and widened to F32 with
 * {@link Float#float16ToFloat(short)}, as the browser does, and run with the scalar reference {@link Network}.
 *
 * <p><b>Cells</b> are the engine's cell ids (the same ids as {@code web/src/board/cells.generated.json}); an owner
 * array has one entry per cell, -1 for an empty cell. Moves are {@code {from, to}} cell ids.
 */
class ExplainerDataTest {

    private static final String DATE = "2026-11-21";
    private static final String GENERATOR = "ExplainerDataTest";
    private static final boolean WRITE = Boolean.getBoolean("explainer.write");
    /** Floats are written with 4 decimals; the compare allows one unit in the last place plus a rounding margin. */
    private static final double FLOAT_TOLERANCE = 1e-4 + 1e-9;
    private static final int INFINITY = AlphaBeta.MATE + 1;
    private static final String EXPERT = "small-2p-adjacent-expert";
    private static final String SHARED = "small-shared-all";
    private static final String EXPERT_R5 = "small-2p-adjacent-r5-expert";
    private static final int[] BUDGETS = {1_000, 5_000, 20_000, 100_000, 500_000};
    private static final String[] PLANE_NAMES = {
        "own", "next", "third", "goal", "start", "playable", "progress", "distOwn", "distNext", "reachOwn",
        "reachNext", "passed", "eval"
    };

    // ---- tests ------------------------------------------------------------------------------------------------

    @Test
    void abTreeStart() throws IOException {
        Position start = Position.initial(RuleSet.defaults(2));
        // The replica against the engine and against the fact sheet (classical algorithm, section 6.2).
        long[] factNodes = {14, 55, 441, 1337, 8122};
        int[] factScores = {20, 0, 40, 0, 30};
        for (int depth = 1; depth <= 5; depth++) {
            AlphaBeta engine = new AlphaBeta(new DistanceEvaluator());
            SearchResult real = engine.search(start, depth);
            // From depth 4 on, transpositions hit the table of the engine (1337 nodes against 1490 without a
            // table), and the replica has no table. Up to depth 3 no position repeats, so the replica must match.
            if (depth <= 3) {
                Tracer tracer = new Tracer(start, depth, false);
                assertEquals(real.nodes(), tracer.nodes, "replica node count, depth " + depth);
                assertEquals(real.score(), tracer.score, "replica score, depth " + depth);
                assertEquals(real.move(), tracer.move, "replica move, depth " + depth);
            }
            assertEquals(factNodes[depth - 1], real.nodes(), "fact sheet node count, depth " + depth);
            assertEquals(factScores[depth - 1], real.score(), "fact sheet score, depth " + depth);
            assertEquals("(2,-6)->(2,-4)", Move.toString(real.move()), "fact sheet move, depth " + depth);
        }

        Tracer tracer = new Tracer(start, 2, true);
        assertEquals(55, tracer.nodes);
        assertEquals(55, tracer.visits.size());
        assertEquals(0, tracer.score);
        assertEquals("(2,-6)->(2,-4)", Move.toString(tracer.move));

        Map<String, Object> file = abTreeFile(start, tracer, List.of(), "start position",
                "Tracer in ExplainerDataTest, a replica of AlphaBeta, asserted equal to it at depths 1 to 3");
        Map<?, ?> meta = (Map<?, ?>) file.get("meta");
        assertEquals(14, meta.get("rootMoves"));
        assertEquals(6, meta.get("rootJumps"), "6 jumps from the back rows (fact sheet, section 6.2)");
        assertEquals(210L, meta.get("minimaxNodes"), "14 + 14 x 14 without pruning (fact sheet, section 6.2)");
        assertEquals(List.of(14L, 41L), meta.get("iterationNodes"));
        check("ab-tree-start.json", file);
    }

    @Test
    void abTreeMid() throws IOException {
        Position mid = Position.initial(RuleSet.defaults(2));
        for (int[] ft : MID_HISTORY) {
            mid.make(moveOf(mid, ft[0], ft[1]));
        }
        assertEquals(24, mid.ply());
        assertEquals(0, mid.sideToMove());
        assertEquals(67, mid.legalMoves().length);

        // The replica against the engine at depths 1 and 2. This position has no transposition up to depth 2, so the
        // replica without a table must match; if that ever changes, the assert fails loudly.
        for (int depth = 1; depth <= 2; depth++) {
            SearchResult real = new AlphaBeta(new DistanceEvaluator()).search(mid, depth);
            Tracer replica = new Tracer(mid, depth, false);
            assertEquals(real.nodes(), replica.nodes, "replica node count, depth " + depth);
            assertEquals(real.score(), replica.score, "replica score, depth " + depth);
            assertEquals(real.move(), replica.move, "replica move, depth " + depth);
        }

        Tracer tracer = new Tracer(mid, 2, true);
        assertEquals(298, tracer.nodes, "67 calls at depth 1, 231 at depth 2");
        assertEquals(-10, tracer.score);
        assertEquals("(0,-1)->(-2,3)", Move.toString(tracer.move));

        Map<String, Object> file = abTreeFile(mid, tracer, Arrays.asList(MID_HISTORY), MID_GAME,
                "Tracer in ExplainerDataTest, a replica of AlphaBeta, asserted equal to it at depths 1 and 2");
        Map<?, ?> meta = (Map<?, ?>) file.get("meta");
        assertEquals(67, meta.get("rootMoves"));
        assertEquals(3180L, meta.get("minimaxNodes"), "67 + the replies of all 67 root moves, without pruning");
        assertEquals(List.of(67L, 231L), meta.get("iterationNodes"));
        List<?> roots = (List<?>) file.get("rootMoves");
        assertEquals(2, roots.stream().filter(r -> !(Boolean) ((Map<?, ?>) r).get("cutoff")).count(),
                "two root moves are searched in full: the first one and the one that raises alpha");
        assertEquals(65, roots.stream().filter(r -> (Boolean) ((Map<?, ?>) r).get("cutoff")).count());
        check("ab-tree-mid.json", file);
    }

    /** The game of the mid-game example: how its position came about, for the caption and the data chip. */
    private static final String MID_GAME = "G3: linear-r3 (side 0) against distance (side 1), 3000 nodes each, 4 random opening plies, seed 5";

    /** The 24 moves (from, to) that lead from the start position to the mid-game example. */
    private static final int[][] MID_HISTORY = {
        {4, 17}, {113, 104}, {9, 18}, {116, 103}, {2, 29}, {112, 90}, {3, 41}, {115, 79}, {0, 16}, {79, 68}, {8, 51},
        {120, 58}, {1, 4}, {103, 59}, {6, 28}, {117, 79}, {4, 61}, {90, 49}, {29, 50}, {59, 4}, {17, 80}, {68, 29},
        {16, 60}, {58, 3}
    };

    /**
     * The file of schema 2 for one position (design spec v5-mid-spec.md, section 5): one entry per root move in the
     * order of the iteration for depth 2, every score for the side to move at the root. It is built from the visits of
     * the {@link Tracer} and asserts the three sums that the figure relies on.
     */
    private static Map<String, Object> abTreeFile(Position start, Tracer tracer, List<int[]> history,
            String positionName, String how) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visits = (List<Map<String, Object>>) (List<?>) tracer.visits;
        int mover = start.sideToMove();
        Layout layout = start.layout();
        long d1Calls = visits.stream().filter(v -> (int) v.get("iteration") == 1).count();
        List<Map<String, Object>> rootsD1 = visits.stream()
                .filter(v -> (int) v.get("parent") == 0 && (int) v.get("iteration") == 1).toList();
        List<Map<String, Object>> rootsD2 = visits.stream()
                .filter(v -> (int) v.get("parent") == 0 && (int) v.get("iteration") == 2).toList();
        assertEquals(rootsD1.size(), rootsD2.size(), "both iterations visit every root move");

        int negInf = -INFINITY;
        int alpha = negInf;
        int bestScore = negInf;
        long visited2 = 0;
        long replyTotal = 0;
        int jumps = 0;
        List<Object> rootList = new ArrayList<>();
        for (int i = 0; i < rootsD2.size(); i++) {
            Map<String, Object> node = rootsD2.get(i);
            int from = (int) ((Map<?, ?>) node.get("move")).get("from");
            int to = (int) ((Map<?, ?>) node.get("move")).get("to");
            Map<String, Object> d1 = rootsD1.stream().filter(v -> {
                Map<?, ?> mv = (Map<?, ?>) v.get("move");
                return (int) mv.get("from") == from && (int) mv.get("to") == to;
            }).findFirst().orElseThrow();
            int id = (int) node.get("id");
            List<Map<String, Object>> kids = visits.stream().filter(v -> (int) v.get("parent") == id).toList();
            boolean jump = !isNeighbour(from, to);
            jumps += jump ? 1 : 0;
            // Negamax: the call of a root move returns the score of the replier, the mover's value is its negation.
            // A leaf call after a reply is evaluated for the side to move, which is the mover again, so the score of
            // a reply call is already a score for the mover.
            int value = -(int) node.get("score");
            boolean cutoff = (boolean) node.get("cutoff");
            int replies = (int) node.get("legal");
            int skipped = (int) node.get("skipped");
            assertEquals(replies, kids.size() + skipped, "searched and skipped replies make all replies");
            int lowest = Integer.MAX_VALUE;
            int decider = -1;
            List<Object> searched = new ArrayList<>();
            for (int k = 0; k < kids.size(); k++) {
                Map<String, Object> kid = kids.get(k);
                Map<?, ?> rm = (Map<?, ?>) kid.get("move");
                int score = (int) kid.get("score");
                assertEquals(k, (int) kid.get("order"));
                if (score < lowest) {
                    lowest = score;
                    decider = k;
                }
                searched.add(List.of((long) (int) rm.get("from"), (long) (int) rm.get("to"),
                        isNeighbour((int) rm.get("from"), (int) rm.get("to")) ? 0L : 1L, (long) score));
            }
            assertEquals(lowest, value, "the value of a root move is the lowest score of its searched replies");
            int alphaBefore = alpha;
            assertEquals(cutoff, value <= alphaBefore, "a cut-off is a root move whose value is not above alpha");
            boolean newBest = value > bestScore;
            if (newBest) {
                bestScore = value;
                if (value > alpha) {
                    alpha = value;
                }
            }
            Position after = start.copy();
            after.make(moveOf(start, from, to));
            Map<?, ?> deciding = (Map<?, ?>) kids.get(decider).get("move");
            visited2 += 1 + kids.size();
            replyTotal += replies;
            Map<String, Object> entry = obj(
                    "from", from, "to", to,
                    "path", ints(hopPath(start, from, to)),
                    "kind", jump ? "jump" : "step",
                    "gain", i == 0 ? (Object) "pv"
                            : (Object) (long) (layout.distance(mover, from) - layout.distance(mover, to)),
                    "d1", -(int) d1.get("score"),
                    "visit1", (int) d1.get("id"),
                    "visit2", id,
                    "replies", replies,
                    "alphaBefore", alphaBefore <= negInf ? (Object) "-infinity" : (Object) (long) alphaBefore,
                    "alphaAfter", alpha <= negInf ? (Object) "-infinity" : (Object) (long) alpha,
                    "value", value,
                    "bound", cutoff ? "upper" : "exact",
                    "cutoff", cutoff,
                    "skipped", skipped,
                    "newBest", newBest);
            // The deciding reply is the one with the lowest score (the first of them). Its chain of cells is only
            // written for a jump: the chain of a step is [from, to], and the file stays within its size budget.
            if (!isNeighbour((int) deciding.get("from"), (int) deciding.get("to"))) {
                entry.put("replyPath", ints(hopPath(after, (int) deciding.get("from"), (int) deciding.get("to"))));
            }
            entry.put("searched", searched);
            rootList.add(entry);
        }
        long minimax = rootsD2.size() + replyTotal;
        // The three sums of the spec, section 5, that the figure relies on.
        assertEquals(tracer.nodes - d1Calls, visited2, "sum(1 + searched) over the root moves is the nodes of depth 2");
        assertEquals(tracer.score, alpha, "alpha after the last root move is the score of the search");
        assertEquals(tracer.score, bestScore);
        long counted = start.legalMoves().length;
        for (int m : start.legalMoves()) {
            Position child = start.copy();
            child.make(m);
            counted += child.legalMoves().length;
        }
        assertEquals(counted, minimax, "rootMoves.length + sum(replies) is the tree without pruning");

        Map<String, Object> bestMove = move(tracer.move);
        bestMove.put("text", Move.toString(tracer.move));
        List<Object> historyList = new ArrayList<>();
        for (int[] ft : history) {
            historyList.add(List.of((long) ft[0], (long) ft[1]));
        }
        Map<String, Object> meta = obj(
                "spec", "ab:eval=distance, SearchOptions.DEFAULT (no enhancements), fixed depth 2",
                "depth", 2,
                "perspective", "mover",
                "ply", start.ply(),
                "sideToMove", mover,
                "game", positionName,
                "history", historyList,
                "nodes", tracer.nodes,
                "iterationNodes", List.of(d1Calls, tracer.nodes - d1Calls),
                "minimaxNodes", minimax,
                "bestMove", bestMove,
                "score", tracer.score,
                "rootMoves", rootsD2.size(),
                "rootJumps", jumps,
                "infinity", INFINITY,
                "note", "scores are for the side to move at the root; a reply is [from, to, kind (0 step, 1 jump), "
                        + "score]; path is one shortest chain of cells, replyPath that of the deciding reply if it jumps; "
                        + "nodes counts the calls of depths 1 and 2");
        return obj(
                "schema", 2,
                "source", source(how),
                "meta", meta,
                "cells", owners(start),
                "rootMoves", rootList);
    }

    private static int moveOf(Position p, int from, int to) {
        for (int m : p.legalMoves()) {
            if (Move.from(m) == from && Move.to(m) == to) {
                return m;
            }
        }
        throw new IllegalStateException("no legal move " + from + "-" + to);
    }

    /**
     * One shortest chain of cells for a move on the board of {@code p}: {@code [from, to]} for a step, else the
     * cells of the fewest jumps, found breadth-first in direction order, as {@code jumpPath} of the web app does (the
     * piece's own start cell counts as empty).
     */
    private static int[] hopPath(Position p, int from, int to) {
        if (isNeighbour(from, to)) {
            return new int[] {from, to};
        }
        int[] previous = new int[Geometry.CELLS];
        Arrays.fill(previous, -2);
        previous[from] = -1;
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int over = Geometry.neighbor(at, d);
                if (over < 0 || over == from || p.owner(over) < 0) {
                    continue;
                }
                int land = Geometry.jumpLanding(at, d);
                if (land < 0 || (land != from && p.owner(land) >= 0) || previous[land] != -2) {
                    continue;
                }
                previous[land] = at;
                if (land == to) {
                    List<Integer> chain = new ArrayList<>();
                    for (int c = to; c != -1; c = previous[c]) {
                        chain.add(c);
                    }
                    java.util.Collections.reverse(chain);
                    return chain.stream().mapToInt(Integer::intValue).toArray();
                }
                queue.add(land);
            }
        }
        throw new IllegalStateException("no jump chain " + from + "-" + to);
    }

    @Test
    void abBudgetMid() throws IOException {
        Fixtures.Game mid = Fixtures.mid();
        int[] browserDepth = {3, 4, 6, 8, 10};
        String[] browserMove = {"(1,-4)->(1,-2)", "(4,-5)->(3,-4)", "(4,-5)->(3,-5)", "(4,-7)->(4,-6)", "(4,-7)->(4,-6)"};
        List<Object> browser = new ArrayList<>();
        List<Object> plain = new ArrayList<>();
        for (int i = 0; i < BUDGETS.length; i++) {
            long budget = BUDGETS[i];
            AlphaBetaPlayer player = PlayerSpec.parse(Strategies.BROWSER_SPEC + ",nodes=" + budget).create();
            int m = player.choose(mid.position);
            SearchResult r = player.lastResult();
            assertEquals(browserDepth[i], r.depth(), "browser spec depth at " + budget);
            assertEquals(browserMove[i], Move.toString(m), "browser spec move at " + budget);
            browser.add(budgetEntry(budget, r, m));

            AlphaBeta plainSearch = new AlphaBeta(Evaluators.byName("linear-r3"));
            SearchResult p = plainSearch.search(mid.position, 30, Long.MAX_VALUE, budget);
            plain.add(budgetEntry(budget, p, p.move()));
            if (budget == 20_000) {
                assertEquals(3, p.depth(), "plain evaluator at 20000 nodes (fact sheet, section 6.3)");
            }
            if (budget == 100_000) {
                assertEquals(4, p.depth(), "plain evaluator at 100000 nodes (fact sheet, section 6.3)");
            }
        }
        Map<String, Object> file = obj(
                "source", source("AlphaBetaPlayer with Strategies.BROWSER_SPEC and AlphaBeta with linear-r3 and default "
                        + "options on the mid-game position of eval-mid.json; no wall times (they depend on the machine)"),
                "meta", obj("position", "mid", "ply", mid.position.ply(), "browserSpec", Strategies.BROWSER_SPEC,
                        "plainSpec", "ab:eval=linear-r3 without enhancements (SearchOptions.DEFAULT)",
                        "levels", Arrays.stream(BUDGETS).boxed().map(b -> (Object) b.longValue()).toList()),
                "cells", owners(mid.position),
                "browser", browser,
                "plain", plain);
        check("ab-budget-mid.json", file);
    }

    @Test
    void evalMid() throws IOException {
        Fixtures.Game game = Fixtures.mid();
        Position mid = game.position;
        assertEquals(30, mid.ply());
        assertEquals(54, mid.legalMoves().length, "54 legal moves at ply 30 (fact sheet, section 6.3)");
        int me = mid.sideToMove();
        int layoutIndex = LinearEvaluator.layoutIndex(mid.rules());

        double[] features = new double[LinearEvaluator.GAIN_FEATURES];
        LinearEvaluator.features(mid, features, LinearEvaluator.GainMode.BOTH);
        double[] weights = LinearWeights.ROUND3;
        double scale = LinearWeights.SCALE;
        int score = Evaluators.byName("linear-r3").evaluate(mid);
        assertEquals(179, score, "linear-r3 score at ply 30 (fact sheet, section 6.3)");

        double sum = 0;
        List<Object> featureList = new ArrayList<>();
        for (int i = 0; i < LinearEvaluator.GAIN_FEATURES; i++) {
            double contribution = scale * weights[i] * features[i];
            sum += weights[i] * features[i];
            // the weight keeps all its digits (the figure's reset button restores it); other floats have 4 decimals
            featureList.add(obj("name", LinearEvaluator.GAIN_NAMES[i], "value", features[i],
                    "weight", new BigDecimal(Double.toString(weights[i])), "contribution", contribution));
        }
        assertEquals(score, (int) Math.round(scale * sum), "the table of weights x values adds up to the score");
        double[] fact = {-9, Double.NaN, -7.1, 1, -2, 2, -6, 2, 3, -3, -12.51, 1, 0};
        for (int i = 0; i < fact.length; i++) {
            if (!Double.isNaN(fact[i])) {
                assertEquals(fact[i], features[i], 0.005, "fact sheet feature " + LinearEvaluator.GAIN_NAMES[i]);
            }
        }

        List<Object> sides = new ArrayList<>();
        for (int p : new int[] {me, 1 - me}) {
            sides.add(sideData(mid, layoutIndex, p, p == me ? "mover" : "opponent"));
        }
        // the feature "mover minus opponent" equals the difference of the two sides' sums
        int[] a = new int[LinearEvaluator.SIDE_SUMS];
        int[] b = new int[LinearEvaluator.SIDE_SUMS];
        LinearEvaluator.side(mid, layoutIndex, me, a);
        LinearEvaluator.side(mid, layoutIndex, 1 - me, b);
        assertEquals(features[0], a[LinearEvaluator.DSUM] - b[LinearEvaluator.DSUM], 1e-9);
        assertEquals(features[7], a[LinearEvaluator.JUMPS] - b[LinearEvaluator.JUMPS], 1e-9);

        List<Object> history = new ArrayList<>();
        for (int m : game.history) {
            history.add(move(m));
        }
        double probability = 1.0 / (1.0 + Math.exp(-score / scale));
        Map<String, Object> file = obj(
                "source", source("LinearEvaluator.features and Evaluators.byName(\"linear-r3\") on the position after 30 "
                        + "plies of ab:eval=distance,nodes=2000 against itself; weights = LinearWeights.ROUND3"),
                "meta", obj("ply", mid.ply(), "legalMoves", mid.legalMoves().length, "sideToMove", me,
                        "evaluator", "linear-r3", "scale", scale, "score", score, "winProbability", probability,
                        "distance", "single-agent distance to the goal corner of the piece's player, Layout.distance"),
                "history", history,
                "cells", owners(mid),
                "features", featureList,
                "sides", sides);
        check("eval-mid.json", file);
        if (!WRITE) {
            // the generic compare allows 1e-4; the weights must be the exact values of LinearWeights.ROUND3
            List<?> committed = (List<?>) Json.parseObject(Files.readString(dataDir().resolve("eval-mid.json")))
                    .get("features");
            for (int i = 0; i < weights.length; i++) {
                assertEquals(weights[i], ((Number) Json.asObject(committed.get(i)).get("weight")).doubleValue(), 0.0,
                        "committed weight of " + LinearEvaluator.GAIN_NAMES[i]);
            }
        }
    }

    @Test
    void netShape() throws IOException {
        Map<String, Object> manifest = Json.parseObject(Files.readString(modelsDir().resolve("models.json")));
        List<Object> models = new ArrayList<>();
        Map<String, Long> expectedParams = Map.of(EXPERT, 61_734L, SHARED, 62_215L);
        for (String id : List.of(EXPERT, SHARED)) {
            Fixtures.Model model = Fixtures.model(id);
            long total = 0;
            List<Object> tensors = new ArrayList<>();
            for (Map.Entry<String, int[]> t : model.shapes.entrySet()) {
                long n = Tensor.elementCount(t.getValue());
                total += n;
                tensors.add(obj("name", t.getKey(), "shape", ints(t.getValue()), "params", n));
            }
            assertEquals(expectedParams.get(id), total, "parameter sum of " + id + " (models.json)");
            assertEquals(model.parameters.config().parameterCount(), total);
            Map<String, Object> entry = findModel(manifest, id);
            assertEquals(((Long) entry.get("params")).longValue(), total);
            NetworkConfig cfg = model.parameters.config();
            models.add(obj("id", id, "file", model.file, "formatVersion", entry.get("formatVersion"),
                    "planeSet", cfg.planes().id(), "inputPlanes", cfg.inputPlanes(), "channels", cfg.channels(),
                    "blocks", cfg.blocks(), "players", cfg.players(), "valueHidden", cfg.valueHidden(),
                    "params", total, "sha256Raw", model.sha256Raw, "tensors", tensors, "layers", layers(model.shapes)));
        }
        List<Object> gather = new ArrayList<>();
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int c = 0; c < HexGrid.CELLS; c++) {
            List<Object> row = new ArrayList<>();
            int neighbours = 0;
            for (int k = 0; k < HexGrid.STENCIL; k++) {
                int g = HexGrid.gather(c, k);
                row.add((long) g);
                if (k > 0 && g != HexGrid.PADDING) {
                    neighbours++;
                }
            }
            gather.add(row);
            counts.merge(neighbours, 1, Integer::sum);
        }
        assertEquals(Map.of(6, 73, 5, 6, 4, 36, 2, 6), counts, "gather neighbour counts (inference fact sheet, 1.4)");
        Map<String, Object> countObj = new LinkedHashMap<>();
        counts.forEach((k, v) -> countObj.put(Integer.toString(k), (long) v));
        Map<String, Object> file = obj(
                "source", source("safetensors headers of web/models/*.safetensors.gz (F16) and nn HexGrid"),
                "meta", obj("gatherNote", "gather[cell][k]: k = 0 the cell itself, k = 1 to 6 the neighbour in Geometry "
                        + "direction k - 1, 121 = padding row (neighbour off the board)",
                        "paddingRow", HexGrid.PADDING, "neighbourCounts", countObj),
                "models", models,
                "gather", gather);
        check("net-shape.json", file);
    }

    @Test
    void forwardStart() throws IOException {
        Position start = Position.initial(RuleSet.defaults(2));
        check("forward-start.json", forwardFile("start", start, 0));
    }

    @Test
    void forwardMid() throws IOException {
        Fixtures.Game mid = Fixtures.mid();
        check("forward-mid.json", forwardFile("mid", mid.position, mid.history.size()));
    }

    @Test
    void forwardRace() throws IOException {
        Fixtures.Game race = Fixtures.race();
        assertTrue(RaceAdjudication.passed(race.position), "the race position is passed");
        assertEquals(0, race.position.sideToMove(), "player 0 to move, so the board is not rotated for the network");
        check("forward-race.json", forwardFile("race", race.position, race.history.size()));
    }

    /**
     * A forward golden with player 1 to move, so that the board is rotated for the network. It is a test fixture of the
     * TypeScript reference forward pass ({@code web/src/engines/net/reference-forward.test.ts}), not Info data, and it
     * carries the history so that the test can encode the position with the real net facade.
     */
    @Test
    void forwardMover1() throws IOException {
        Fixtures.Game game = Fixtures.mover1();
        assertEquals(1, game.position.sideToMove(), "player 1 to move, so the board is rotated for the network");
        Map<String, Object> file = forwardFile("mover1", game.position, game.history.size());
        List<Object> history = new ArrayList<>();
        for (int m : game.history) {
            history.add(move(m));
        }
        file.put("history", history);
        check(testDataDir(), "forward-mover1.json", file);
    }

    /**
     * A forward golden on the 5-row board with the 5-row expert, player 1 to move: the fixture of the TypeScript cross test
     * ({@code web/src/engines/net/reference-forward.test.ts}) that pins the planes of the TeaVM net facade and the TypeScript
     * forward pass to the JVM encoder and the scalar reference on 15-cell houses. The position comes from 41 plies of a
     * classical self-play game on 5 rows; it carries the history, so that the test can replay it in the facade.
     */
    @Test
    void forwardRows5() throws IOException {
        Fixtures.Game game = Fixtures.rows5();
        assertEquals(1, game.position.sideToMove(), "player 1 to move, so the board is rotated for the network");
        assertEquals(RuleSet.TriangleRows.FIVE, game.position.rules().triangleRows(), "a 5-row position");
        Map<String, Object> file = forwardFile("rows5", game.position, game.history.size(), EXPERT_R5);
        List<Object> history = new ArrayList<>();
        for (int m : game.history) {
            history.add(move(m));
        }
        file.put("history", history);
        file.put("rows", 5L);
        check(testDataDir(), "forward-r5.json", file);
    }

    @Test
    void mctsMid() throws IOException {
        Fixtures.Game game = Fixtures.mid();
        Position mid = game.position;
        Fixtures.Model model = Fixtures.model(EXPERT);
        CachedNet net = new CachedNet(new Network(model.parameters));
        sternhalma.nn.Evaluator evaluator = new sternhalma.nn.Evaluator(net);
        // the options of the browser net engine (WebNetCore.defaultOptions) with the smallest preset of 50 simulations
        MctsOptions options = MctsOptions.defaults().withS1(false).withRace(0).withSims(50);
        assertEquals(1, options.batch());
        assertEquals(1.0, options.cpuct());
        assertEquals(0.0, options.noise());
        assertEquals(0.0, options.temperature());

        Map<List<Integer>, Integer> ids = new LinkedHashMap<>();
        ids.put(List.of(), 0);
        List<Object> sims = new ArrayList<>();
        Snapshot previous = null;
        Snapshot last = null;
        MctsResult lastResult = null;
        Evaluation rootEvaluation = evaluator.evaluate(mid);
        for (int k = 1; k <= 50; k++) {
            Mcts mcts = new Mcts(net, options.withSims(k), 0L);
            MctsResult result = mcts.search(mid);
            assertEquals(k, result.sims());
            Snapshot now = Snapshot.of(mcts.root());
            List<Integer> path = new ArrayList<>();
            Snapshot.pathOf(now, previous, path);
            assertTrue(!path.isEmpty(), "simulation " + k + " visits at least one edge");
            // the nodes along the path and the leaf
            List<Object> pathNodes = new ArrayList<>();
            Snapshot node = now;
            Position leafPosition = mid.copy();
            List<Double> backed = new ArrayList<>();
            List<Integer> prefix = new ArrayList<>();
            List<Integer> movers = new ArrayList<>();
            for (int step = 0; step < path.size(); step++) {
                pathNodes.add((long) ids.get(List.copyOf(prefix)));
                int edge = path.get(step);
                movers.add(leafPosition.sideToMove());
                // the value that went into this edge: sum difference against the previous tree
                Snapshot before = Snapshot.at(previous, prefix);
                backed.add(node.sum[edge] - (before == null ? 0.0 : before.sum[edge]));
                leafPosition.make(node.moves[edge]);
                prefix.add(edge);
                node = node.child[edge];
            }
            Snapshot beforeLeaf = Snapshot.at(previous, prefix);
            boolean isNew = beforeLeaf == null;
            int leafId;
            if (isNew) {
                leafId = ids.size();
                ids.put(List.copyOf(prefix), leafId);
            } else {
                leafId = ids.get(List.copyOf(prefix));
            }
            String kind = node.exact != null ? "terminal" : "net";
            double[] values;
            Map<String, Object> expanded = null;
            if (node.exact != null) {
                values = node.exact;
                assertTrue(leafPosition.outcome() != null, "an exact leaf without S1 is a finished game");
            } else {
                Evaluation e = evaluator.evaluate(leafPosition);
                values = new double[] {e.values()[0], e.values()[1]};
                if (isNew) {
                    expanded = expandedOf(node, e, leafPosition);
                }
            }
            // consistency: every edge of the path received the value of its own mover
            for (int step = 0; step < path.size(); step++) {
                assertEquals(values[movers.get(step)], backed.get(step), 1e-9, "simulation " + k + ", edge " + step
                        + ": the backed up value is the mover's value");
            }
            Map<String, Object> sim = obj("k", k, "path", ints(path), "nodes", pathNodes,
                    "leaf", obj("node", leafId, "kind", kind, "new", isNew),
                    "values", List.of(values[0], values[1]));
            if (expanded != null) {
                sim.put("expanded", expanded);
            }
            sims.add(sim);
            previous = now;
            last = now;
            lastResult = result;
        }
        assertEquals(50, last.totalVisits);
        assertEquals(50, sims.size());

        List<Object> table = new ArrayList<>();
        Snapshot root = last;
        int chosenEdge = -1;
        for (int i = 0; i < root.moves.length; i++) {
            if (root.moves[i] == lastResult.move()) {
                chosenEdge = i;
            }
            Map<String, Object> row = obj("move", pair(root.moves[i]), "P", (double) root.prior[i], "N", root.visits[i]);
            row.put("Q", root.visits[i] == 0 ? null : (Object) (root.sum[i] / root.visits[i]));
            table.add(row);
        }
        int mostVisited = 0;
        for (int i = 1; i < root.moves.length; i++) {
            if (root.visits[i] > root.visits[mostVisited]) {
                mostVisited = i;
            }
        }
        assertEquals(root.visits[mostVisited], root.visits[chosenEdge], "the final move is a most visited move");
        Map<String, Object> rootInfo = obj("moves", pairs(rootEvaluation.moves()),
                "priors", floats(rootEvaluation.priors()), "values", floats(rootEvaluation.values()));
        Map<String, Object> file = obj(
                "source", source("Mcts (browser options: cpuct 1.0, batch 1, no noise, temp 0, s1 off, race 0), expert "
                        + "model " + EXPERT + " (F16 widened to F32), scalar reference Network; the tree after "
                        + "simulation k is the search with sims=k (deterministic); Q and N after each step follow "
                        + "from path and values"),
                "meta", obj("model", EXPERT, "position", "mid", "ply", mid.ply(), "rootMover", mid.sideToMove(),
                        "sims", 50, "cpuct", options.cpuct(), "batch", options.batch(), "noise", options.noise(),
                        "temperature", options.temperature(), "fpu", options.fpu(), "s1", options.s1(),
                        "formula", "Q + cpuct * P * sqrt(max(1, N_parent)) / (1 + N); an unvisited edge has Q = node "
                                + "mean - fpu, with mean = (value + sum of backed up values) / (1 + visits)",
                        "choice", pair(lastResult.move()), "choiceEdge", chosenEdge),
                "cells", owners(mid),
                "root", rootInfo,
                "sims", sims,
                "final", table);
        check("mcts-mid.json", file);
    }

    // ---- file plumbing ----------------------------------------------------------------------------------------

    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
        }
        throw new IllegalStateException("settings.gradle.kts not found above " + Path.of("").toAbsolutePath());
    }

    private static Path dataDir() {
        return repoRoot().resolve("web/src/about/data");
    }

    /** Fixtures of the TypeScript tests of the net engine. */
    private static Path testDataDir() {
        return repoRoot().resolve("web/src/engines/net/testdata");
    }

    private static Path modelsDir() {
        return repoRoot().resolve("web/models");
    }

    /** Writes the file (write mode) or compares the regenerated file with the committed one. */
    private static void check(String name, Map<String, Object> data) throws IOException {
        check(dataDir(), name, data);
    }

    private static void check(Path dir, String name, Map<String, Object> data) throws IOException {
        String text = Json.write(data);
        Path file = dir.resolve(name);
        if (WRITE) {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(name + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("explainer data written: " + file + " (" + text.length() + " characters)");
            return;
        }
        assertTrue(Files.isRegularFile(file), name + " is missing; write it with the devenv task about:data "
                + "(gradle -Pexplainer.write=true)");
        Object expected = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        Object actual = Json.parse(text);
        String difference = Json.difference("$", expected, actual);
        assertNull(difference, name + " differs from the regenerated data (committed vs regenerated): " + difference
                + "; if the change is intended, run the devenv task about:data and commit the files");
    }

    private static Map<String, Object> source(String how) {
        return obj("generator", GENERATOR, "date", DATE, "how", how,
                "regenerate", "devenv task about:data (gradle -Pexplainer.write=true :search:test --tests "
                        + "sternhalma.search.ExplainerDataTest)");
    }

    // ---- JSON building blocks -----------------------------------------------------------------------------------

    /** A map in insertion order from key/value pairs. */
    private static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> move(int m) {
        return obj("from", Move.from(m), "to", Move.to(m));
    }

    private static List<Object> pair(int m) {
        return List.of((long) Move.from(m), (long) Move.to(m));
    }

    private static List<Object> pairs(int[] moves) {
        List<Object> out = new ArrayList<>();
        for (int m : moves) {
            out.add(pair(m));
        }
        return out;
    }

    private static List<Object> ints(int[] a) {
        List<Object> out = new ArrayList<>();
        for (int v : a) {
            out.add((long) v);
        }
        return out;
    }

    private static List<Object> ints(List<Integer> a) {
        List<Object> out = new ArrayList<>();
        for (int v : a) {
            out.add((long) v);
        }
        return out;
    }

    private static List<Object> floats(float[] a) {
        List<Object> out = new ArrayList<>();
        for (float v : a) {
            out.add((double) v);
        }
        return out;
    }

    private static List<Object> owners(Position p) {
        List<Object> out = new ArrayList<>();
        for (int c = 0; c < Geometry.CELLS; c++) {
            out.add((long) p.owner(c));
        }
        return out;
    }

    private static boolean isNeighbour(int a, int b) {
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            if (Geometry.neighbor(a, d) == b) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> budgetEntry(long budget, SearchResult r, int move) {
        Map<String, Object> bestMove = move(move);
        bestMove.put("text", Move.toString(move));
        return obj("nodes", budget, "depth", r.depth(), "score", r.score(), "move", bestMove);
    }

    // ---- eval-mid -----------------------------------------------------------------------------------------------

    /** The marks of one side, counted here from the geometry and compared with the evaluator's own sums. */
    private static Map<String, Object> sideData(Position pos, int layoutIndex, int p, String role) {
        Layout layout = pos.layout();
        List<Object> pieces = new ArrayList<>();
        List<Object> inGoal = new ArrayList<>();
        List<Object> inStart = new ArrayList<>();
        List<Object> adjacent = new ArrayList<>();
        List<Object> jumps = new ArrayList<>();
        List<Object> steps = new ArrayList<>();
        int dsum = 0;
        int dmax = 0;
        int dmin = Integer.MAX_VALUE;
        int dsq = 0;
        int lateral = 0;
        for (int i = 0; i < pos.pieceCount(p); i++) {
            int c = pos.pieceCell(p, i);
            int d = layout.distance(p, c);
            int lat = Math.abs(Geometry.q(c) - Geometry.s(c));
            dsum += d;
            dsq += d * d;
            dmax = Math.max(dmax, d);
            dmin = Math.min(dmin, d);
            lateral += lat;
            pieces.add(obj("cell", c, "distance", d, "lateral", lat));
            if (layout.inGoal(p, c)) {
                inGoal.add((long) c);
            } else if (layout.inStart(p, c)) {
                inStart.add((long) c);
            }
            for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                int a = Geometry.neighbor(c, dir);
                if (a < 0) {
                    continue;
                }
                if (pos.owner(a) < 0) {
                    if (closer(layout, p, a, d)) {
                        steps.add(List.of((long) c, (long) a));
                    }
                } else {
                    if (pos.owner(a) == p && a > c) {
                        adjacent.add(List.of((long) c, (long) a));
                    }
                    int land = Geometry.jumpLanding(c, dir);
                    if (closer(layout, p, land, d) && pos.owner(land) < 0) {
                        jumps.add(List.of((long) c, (long) land));
                    }
                }
            }
        }
        int[] sums = new int[LinearEvaluator.SIDE_SUMS];
        LinearEvaluator.side(pos, layoutIndex, p, sums);
        assertEquals(sums[LinearEvaluator.DSUM], dsum);
        assertEquals(sums[LinearEvaluator.DMAX], dmax);
        assertEquals(sums[LinearEvaluator.DMIN], dmin);
        assertEquals(sums[LinearEvaluator.DSQ], dsq);
        assertEquals(sums[LinearEvaluator.LAT], lateral);
        assertEquals(sums[LinearEvaluator.GOAL], inGoal.size());
        assertEquals(sums[LinearEvaluator.START], inStart.size());
        assertEquals(sums[LinearEvaluator.ADJ], adjacent.size());
        assertEquals(sums[LinearEvaluator.JUMPS], jumps.size());
        assertEquals(sums[LinearEvaluator.STEPS], steps.size());
        return obj("role", role, "player", p, "pieces", pieces,
                "sums", obj("dsum", dsum, "dmax", dmax, "dmin", dmin, "dsq", dsq, "inGoal", inGoal.size(),
                        "inStart", inStart.size(), "lateral", lateral, "jumps", jumps.size(),
                        "adjacent", adjacent.size(), "steps", steps.size()),
                "marks", obj("inGoal", inGoal, "inStart", inStart, "adjacent", adjacent, "jumps", jumps, "steps", steps));
    }

    private static boolean closer(Layout layout, int p, int cell, int here) {
        return cell >= 0 && layout.allowed(p, cell) && layout.distance(p, cell) < here;
    }

    // ---- net-shape ----------------------------------------------------------------------------------------------

    private static Map<String, Object> findModel(Map<String, Object> manifest, String id) {
        for (Object o : (List<?>) manifest.get("models")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            if (id.equals(m.get("id"))) {
                return m;
            }
        }
        throw new IllegalStateException("model " + id + " not in models.json");
    }

    /** Groups the tensors by layer: conv_in, each block, the two heads and the two residual priors. */
    private static List<Object> layers(Map<String, int[]> shapes) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String name : shapes.keySet()) {
            String id = name.startsWith("block") ? name.substring(0, name.indexOf('.'))
                    : name.startsWith("head_value") ? "head_value"
                    : name.startsWith("prior.") ? "priors"
                    : name.substring(0, name.lastIndexOf('.'));
            groups.computeIfAbsent(id, k -> new ArrayList<>()).add(name);
        }
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            long params = 0;
            List<Object> names = new ArrayList<>();
            for (String n : g.getValue()) {
                params += Tensor.elementCount(shapes.get(n));
                names.add(n);
            }
            out.add(obj("id", g.getKey(), "tensors", names, "params", params));
        }
        return out;
    }

    // ---- forward pass -------------------------------------------------------------------------------------------

    private static Map<String, Object> forwardFile(String name, Position pos, int ply) throws IOException {
        return forwardFile(name, pos, ply, EXPERT);
    }

    private static Map<String, Object> forwardFile(String name, Position pos, int ply, String modelId) throws IOException {
        Fixtures.Model model = Fixtures.model(modelId);
        Parameters params = model.parameters;
        NetworkConfig cfg = params.config();
        Network reference = new Network(params);
        sternhalma.nn.Evaluator evaluator = new sternhalma.nn.Evaluator(reference);
        float[] input = evaluator.encode(List.of(pos));
        Orientation orientation = Orientation.of(pos);

        ForwardTrace trace = ForwardTrace.of(params, input);
        // the replica of the layer table agrees with the reference network, bit for bit in the outputs
        float[] policy = new float[Encoder.POLICY_SIZE];
        float[] values = new float[cfg.players()];
        reference.forward(input, 1, policy, values);
        assertArrayEquals(policy, trace.policy, 0f, "policy logits of the replica of the layer table");
        assertArrayEquals(values, trace.values, 0f, "values of the replica of the layer table");

        Evaluation withPriors = evaluator.decode(List.of(pos), trace.policy, trace.values).getFirst();
        Evaluation netOnly = evaluator.decode(List.of(pos), trace.policyNetOnly, trace.valuesNetOnly).getFirst();
        assertArrayEquals(withPriors.moves(), netOnly.moves());
        double priorSum = 0;
        for (float pr : withPriors.priors()) {
            priorSum += pr;
        }
        assertEquals(1.0, priorSum, 1e-5);
        for (float v : withPriors.values()) {
            assertTrue(v >= -1f && v <= 1f);
        }

        int stride = cfg.inputPlanes();
        List<Object> planes = new ArrayList<>();
        for (int pl = 0; pl < stride; pl++) {
            Object[] row = new Object[Geometry.CELLS];
            for (int canonical = 0; canonical < Geometry.CELLS; canonical++) {
                row[orientation.fromCanonical(canonical)] = number(input[Encoder.index(stride, canonical, pl)]);
            }
            planes.add(Arrays.asList(row));
        }
        List<Object> layers = new ArrayList<>();
        String[] ids = new String[trace.norms.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i == 0 ? "conv_in" : "block" + (i - 1);
            Object[] row = new Object[Geometry.CELLS];
            for (int canonical = 0; canonical < Geometry.CELLS; canonical++) {
                row[orientation.fromCanonical(canonical)] = (double) trace.norms.get(i)[canonical];
            }
            layers.add(obj("id", ids[i], "norm", Arrays.asList(row)));
        }
        Object[] logitFrom = new Object[Geometry.CELLS];
        Object[] logitTo = new Object[Geometry.CELLS];
        for (int canonical = 0; canonical < Geometry.CELLS; canonical++) {
            int cell = orientation.fromCanonical(canonical);
            logitFrom[cell] = (double) trace.policyNetOnly[Encoder.policyIndex(canonical, Encoder.FROM)];
            logitTo[cell] = (double) trace.policyNetOnly[Encoder.policyIndex(canonical, Encoder.TO)];
        }
        List<Object> policyList = new ArrayList<>();
        for (int i = 0; i < withPriors.moves().length; i++) {
            Map<String, Object> entry = move(withPriors.moves()[i]);
            entry.put("prior", (double) withPriors.priors()[i]);
            entry.put("priorNetOnly", (double) netOnly.priors()[i]);
            policyList.add(entry);
        }
        return obj(
                "source", source("scalar reference Network with the shipped expert model " + modelId + " (F16 widened to "
                        + "F32, sha256 of the inflated file " + model.sha256Raw + "); layer norms are the L2 norm over the "
                        + "32 channels of a cell after the ReLU"),
                "meta", obj("position", name, "model", modelId, "ply", ply, "sideToMove", pos.sideToMove(),
                        "canonicalRotated", pos.sideToMove() == 1,
                        "boardNote", "all arrays are indexed by board cell; the network itself reads the board rotated "
                                + "by 180 degrees when player 1 is to move (canonicalRotated)",
                        "legalMoves", withPriors.moves().length, "planeNames", PLANE_NAMES_LIST,
                        "valuesNote", "values[a] is the expected result of absolute player a in [-1, 1]; valuesNetOnly "
                                + "leaves out the residual prior of the value head (prior.k), priorNetOnly leaves out "
                                + "the prior of the policy head (prior.beta); prior.beta and prior.k are in the shipped model",
                        "beta", (double) params.get(ResidualPriors.BETA).data()[0],
                        "k", (double) params.get(ResidualPriors.K).data()[0]),
                "cells", owners(pos),
                "planes", planes,
                "layers", layers,
                "policyLogits", obj("from", Arrays.asList(logitFrom), "to", Arrays.asList(logitTo)),
                "policy", policyList,
                "values", floats(withPriors.values()),
                "valuesNetOnly", floats(netOnly.values()));
    }

    private static final List<Object> PLANE_NAMES_LIST = new ArrayList<>(Arrays.asList((Object[]) PLANE_NAMES));

    /** An integer where the float is integral, else the float: keeps the 0/1 planes short. */
    private static Object number(float v) {
        return v == Math.rint(v) ? (Object) (long) v : (Object) (double) v;
    }

    /** The layer table of docs/network.md up to the heads, run step by step with the reference operations. */
    private static final class ForwardTrace {
        final List<float[]> norms = new ArrayList<>();
        float[] policyNetOnly;
        float[] valuesNetOnly;
        float[] policy;
        float[] values;

        static ForwardTrace of(Parameters params, float[] input) {
            NetworkConfig cfg = params.config();
            int c = cfg.channels();
            int h = cfg.valueHidden();
            int players = cfg.players();
            int activation = HexGrid.ROWS * c;
            ForwardTrace t = new ForwardTrace();
            float[] x = new float[activation];
            float[] tmp = new float[activation];
            float[] u = new float[activation];
            ReferenceOps.hexConv(input, 0, cfg.inputPlanes(), data(params, "conv_in.weight"),
                    data(params, "conv_in.bias"), c, x, 0);
            ReferenceOps.relu(x, 0, activation);
            t.norms.add(norm(x, c));
            for (int i = 0; i < cfg.blocks(); i++) {
                String block = "block" + i;
                ReferenceOps.hexConv(x, 0, c, data(params, block + ".conv1.weight"), data(params, block + ".conv1.bias"),
                        c, tmp, 0);
                ReferenceOps.relu(tmp, 0, activation);
                ReferenceOps.hexConv(tmp, 0, c, data(params, block + ".conv2.weight"),
                        data(params, block + ".conv2.bias"), c, u, 0);
                ReferenceOps.add(u, 0, x, 0, activation);
                ReferenceOps.relu(u, 0, activation);
                float[] swap = x;
                x = u;
                u = swap;
                t.norms.add(norm(x, c));
            }
            t.policyNetOnly = new float[Encoder.POLICY_SIZE];
            ReferenceOps.pointwise(x, 0, HexGrid.CELLS, c, data(params, "head_policy.weight"),
                    data(params, "head_policy.bias"), Encoder.POLICY_CHANNELS, t.policyNetOnly, 0);
            t.policy = t.policyNetOnly.clone();
            ResidualPriors.addPolicy(data(params, ResidualPriors.BETA)[0], input, 0, cfg.inputPlanes(), t.policy, 0);

            float[] pooled = new float[c];
            ReferenceOps.meanOverCells(x, 0, c, pooled, 0);
            float[] hidden = new float[h];
            ReferenceOps.dense(pooled, 0, c, data(params, "head_value.dense1.weight"),
                    data(params, "head_value.dense1.bias"), h, hidden, 0);
            ReferenceOps.relu(hidden, 0, h);
            float[] raw = new float[players];
            ReferenceOps.dense(hidden, 0, h, data(params, "head_value.dense2.weight"),
                    data(params, "head_value.dense2.bias"), players, raw, 0);
            t.valuesNetOnly = raw.clone();
            ReferenceOps.tanh(t.valuesNetOnly, 0, players);
            t.values = raw.clone();
            float k = data(params, ResidualPriors.K)[0];
            for (int i = 0; i < players; i++) {
                t.values[i] += k * ResidualPriors.valueBase(input, 0, cfg.inputPlanes(), i);
            }
            ReferenceOps.tanh(t.values, 0, players);
            return t;
        }

        private static float[] data(Parameters params, String name) {
            return params.get(name).data();
        }

        /** L2 norm over the channels of each of the 121 cell rows. */
        private static float[] norm(float[] x, int channels) {
            float[] out = new float[Geometry.CELLS];
            for (int row = 0; row < Geometry.CELLS; row++) {
                double sum = 0;
                for (int ch = 0; ch < channels; ch++) {
                    double v = x[row * channels + ch];
                    sum += v * v;
                }
                out[row] = (float) Math.sqrt(sum);
            }
            return out;
        }
    }

    // ---- MCTS ---------------------------------------------------------------------------------------------------

    private static Map<String, Object> expandedOf(Snapshot node, Evaluation evaluation, Position position) {
        assertArrayEquals(node.moves, evaluation.moves(), "the expanded node has the legal moves in generation order");
        Map<String, Object> e = obj("moves", pairs(node.moves), "priors", floats(node.prior),
                "value", node.value);
        if (node.winEdge >= 0) {
            e.put("winEdge", node.winEdge);
        }
        assertEquals(position.sideToMove(), node.mover);
        return e;
    }

    /** A deep copy of the statistics of a search tree, so that trees of different runs can be compared. */
    private static final class Snapshot {
        int mover;
        int[] moves;
        float[] prior;
        int[] visits;
        double[] sum;
        double value;
        int winEdge;
        int totalVisits;
        double[] exact;
        Snapshot[] child;

        static Snapshot of(MctsNode n) {
            Snapshot s = new Snapshot();
            s.mover = n.mover;
            s.value = n.value;
            s.winEdge = n.winEdge;
            s.totalVisits = n.totalVisits;
            s.exact = n.exact;
            if (n.moves != null) {
                s.moves = n.moves.clone();
                s.prior = n.prior.clone();
                s.visits = n.visits.clone();
                s.sum = n.sum.clone();
                s.child = new Snapshot[n.moves.length];
                for (int i = 0; i < n.moves.length; i++) {
                    if (n.child[i] != null) {
                        s.child[i] = of(n.child[i]);
                    }
                }
            }
            return s;
        }

        /** The edges of the one simulation between two trees: on each node the edge whose visits grew. */
        static void pathOf(Snapshot now, Snapshot before, List<Integer> path) {
            for (int i = 0; i < now.moves.length; i++) {
                int was = before == null ? 0 : before.visits[i];
                if (now.visits[i] > was) {
                    assertEquals(was + 1, now.visits[i], "one simulation adds one visit to an edge");
                    path.add(i);
                    Snapshot next = now.child[i];
                    Snapshot nextBefore = before == null ? null : before.child[i];
                    if (next != null && next.moves != null && next.totalVisits > (nextBefore == null ? 0 : nextBefore.totalVisits)) {
                        pathOf(next, nextBefore, path);
                    }
                    return;
                }
            }
        }

        /** The node at the end of the edges, or null if the tree does not have it (yet). */
        static Snapshot at(Snapshot root, List<Integer> prefix) {
            Snapshot s = root;
            for (int edge : prefix) {
                if (s == null || s.child == null) {
                    return null;
                }
                s = s.child[edge];
            }
            return s;
        }
    }

    /** Remembers the outputs of the network by input, so that 50 searches cost 51 forward passes. */
    private static final class CachedNet implements ForwardPass {
        private record Entry(float[] input, float[] policy, float[] values) {
        }

        private final ForwardPass delegate;
        private final Map<Integer, List<Entry>> cache = new LinkedHashMap<>();

        CachedNet(ForwardPass delegate) {
            this.delegate = delegate;
        }

        @Override
        public NetworkConfig config() {
            return delegate.config();
        }

        @Override
        public boolean threadSafe() {
            return false;
        }

        @Override
        public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
            assertEquals(1, batch, "the cache serves batch 1");
            int size = config().inputSize();
            float[] key = Arrays.copyOf(input, size);
            List<Entry> bucket = cache.computeIfAbsent(Arrays.hashCode(key), h -> new ArrayList<>());
            for (Entry e : bucket) {
                if (Arrays.equals(e.input, key)) {
                    System.arraycopy(e.policy, 0, policyLogits, 0, e.policy.length);
                    System.arraycopy(e.values, 0, values, 0, e.values.length);
                    return;
                }
            }
            float[] p = new float[Encoder.POLICY_SIZE];
            float[] v = new float[config().players()];
            delegate.forward(key, 1, p, v);
            bucket.add(new Entry(key, p, v));
            System.arraycopy(p, 0, policyLogits, 0, p.length);
            System.arraycopy(v, 0, values, 0, v.length);
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    /** Shared inputs: the two real positions and the shipped models. */
    private static final class Fixtures {
        record Game(Position position, List<Integer> history) {
        }

        record Model(String file, Parameters parameters, Map<String, int[]> shapes, String sha256Raw) {
        }

        private static Game mid;
        private static Game race;
        private static Game mover1;
        private static Game rows5;
        private static final Map<String, Model> MODELS = new LinkedHashMap<>();

        /** 30 plies of ab:eval=distance,nodes=2000 against itself (classical algorithm, section 6.3). */
        static Game mid() {
            if (mid == null) {
                mid = play(30, false);
            }
            return mid;
        }

        /** The position one ply after {@link #mid()} in the same self-play game: player 1 to move. */
        static Game mover1() {
            if (mover1 == null) {
                mover1 = play(31, false);
            }
            return mover1;
        }

        /** 41 plies of ab:eval=distance,nodes=2000,rows=5 against itself on the 5-row board: player 1 to move, the houses in contact. */
        static Game rows5() {
            if (rows5 == null) {
                rows5 = play(RuleSet.defaults(2, RuleSet.TriangleRows.FIVE), "ab:eval=distance,nodes=2000,rows=5", 41, false);
            }
            return rows5;
        }

        /**
         * The first position of the same self-play game in which the armies have passed each other and player 0 is
         * to move.
         */
        static Game race() {
            if (race == null) {
                race = play(Integer.MAX_VALUE, true);
            }
            return race;
        }

        private static Game play(int plies, boolean untilRace) {
            return play(RuleSet.defaults(2), "ab:eval=distance,nodes=2000", plies, untilRace);
        }

        private static Game play(RuleSet rules, String spec, int plies, boolean untilRace) {
            Player a = PlayerSpec.parse(spec).create();
            Player b = PlayerSpec.parse(spec).create();
            Position pos = Position.initial(rules);
            List<Integer> history = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                if (untilRace ? RaceAdjudication.passed(pos) && pos.sideToMove() == 0 : i == plies) {
                    return new Game(pos, history);
                }
                int m = (i % 2 == 0 ? a : b).choose(pos);
                history.add(m);
                pos.make(m);
            }
            throw new IllegalStateException("no such position within 300 plies");
        }

        static Model model(String id) throws IOException {
            Model cached = MODELS.get(id);
            if (cached != null) {
                return cached;
            }
            String file = id + ".safetensors.gz";
            byte[] packed = Files.readAllBytes(modelsDir().resolve(file));
            byte[] raw;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
                raw = in.readAllBytes();
            }
            String sha;
            try {
                sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            Map<String, Object> manifest = Json.parseObject(Files.readString(modelsDir().resolve("models.json")));
            assertEquals(findModel(manifest, id).get("sha256Raw"), sha, "inflated file of " + id + " matches models.json");
            Model model = parseF16(file, raw, sha);
            MODELS.put(id, model);
            return model;
        }

        /** Safetensors with dtype F16: 8-byte header length, JSON header, data; widened to F32 here. */
        private static Model parseF16(String file, byte[] raw, String sha) {
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int headerLength = (int) buf.getLong(0);
            Map<String, Object> header = Json.parseObject(new String(raw, 8, headerLength, StandardCharsets.UTF_8));
            int dataStart = 8 + headerLength;
            Map<String, String> metadata = new LinkedHashMap<>();
            Map<String, Tensor> tensors = new LinkedHashMap<>();
            Map<String, int[]> shapes = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : header.entrySet()) {
                if (e.getKey().equals("__metadata__")) {
                    for (Map.Entry<String, Object> m : Json.asObject(e.getValue()).entrySet()) {
                        metadata.put(m.getKey(), (String) m.getValue());
                    }
                    continue;
                }
                Map<String, Object> t = Json.asObject(e.getValue());
                assertEquals("F16", t.get("dtype"), "tensor " + e.getKey());
                List<?> shapeList = (List<?>) t.get("shape");
                int[] shape = new int[shapeList.size()];
                for (int i = 0; i < shape.length; i++) {
                    shape[i] = ((Long) shapeList.get(i)).intValue();
                }
                List<?> offsets = (List<?>) t.get("data_offsets");
                int begin = dataStart + ((Long) offsets.get(0)).intValue();
                int end = dataStart + ((Long) offsets.get(1)).intValue();
                float[] data = new float[(end - begin) / 2];
                assertEquals(Tensor.elementCount(shape), data.length, "tensor " + e.getKey());
                for (int i = 0; i < data.length; i++) {
                    data[i] = Float.float16ToFloat(buf.getShort(begin + 2 * i));
                }
                tensors.put(e.getKey(), new Tensor(e.getKey(), shape, data));
                shapes.put(e.getKey(), shape);
            }
            NetworkConfig config = NetworkConfig.fromMetadata(metadata);
            return new Model(file, Parameters.of(config, tensors), shapes, sha);
        }
    }

    // ---- the traced search --------------------------------------------------------------------------------------

    /** Replica of the baseline {@link AlphaBeta} with a trace; see the class comment. */
    private static final class Tracer {
        private final Position pos;
        private final Evaluator evaluator = new DistanceEvaluator();
        private final boolean record;
        final List<Object> visits = new ArrayList<>();
        long nodes;
        int score;
        int move;
        private int iteration;

        Tracer(Position start, int maxDepth, boolean record) {
            this.pos = start.copy();
            this.record = record;
            int[] rootMoves = new int[Position.MAX_MOVES];
            int count = pos.generate(rootMoves);
            int bestMove = rootMoves[0];
            int bestScore = 0;
            for (int depth = 1; depth <= maxDepth; depth++) {
                iteration = depth;
                int[] result = searchRoot(depth, bestMove, -AlphaBeta.MATE - 1, AlphaBeta.MATE + 1);
                bestMove = result[0];
                bestScore = result[1];
            }
            assertTrue(count > 0);
            this.move = bestMove;
            this.score = bestScore;
        }

        /** Orders by the table move, then by distance gain, as AlphaBeta.order does with every option off. */
        private void order(int[] moves, int count, int[] keys, int first) {
            Layout layout = pos.layout();
            int player = pos.sideToMove();
            for (int i = 0; i < count; i++) {
                int m = moves[i];
                keys[i] = m == first ? Integer.MAX_VALUE
                        : layout.distance(player, Move.from(m)) - layout.distance(player, Move.to(m));
            }
        }

        private int[] searchRoot(int depth, int firstGuess, int alphaIn, int beta) {
            int[] moves = new int[Position.MAX_MOVES];
            int[] keys = new int[Position.MAX_MOVES];
            int count = pos.generate(moves);
            order(moves, count, keys, firstGuess);
            AlphaBeta.pick(moves, keys, 0, count);
            int alpha = alphaIn;
            int bestMove = moves[0];
            int bestScore = -AlphaBeta.MATE - 1;
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    AlphaBeta.pick(moves, keys, i, count);
                }
                pos.make(moves[i]);
                int s = -negamax(depth - 1, -beta, -alpha, 1, 0, i, moves[i]);
                pos.unmake();
                if (s > bestScore) {
                    bestScore = s;
                    bestMove = moves[i];
                    if (s > alpha) {
                        alpha = s;
                    }
                }
            }
            return new int[] {bestMove, bestScore};
        }

        private int negamax(int depth, int alphaIn, int betaIn, int height, int parent, int order, int moveHere) {
            nodes++;
            int id = (int) nodes;
            Map<String, Object> visit = null;
            if (record) {
                visit = obj("id", id, "parent", parent, "iteration", iteration, "move", move(moveHere), "order", order,
                        "depthLeft", depth, "alpha", alphaIn, "beta", betaIn);
                visits.add(visit);
            }
            int result;
            int legal = 0;
            int skipped = 0;
            boolean cutoff = false;
            Outcome outcome = pos.outcome();
            if (outcome != null) {
                result = AlphaBeta.terminalScore(pos, height);
            } else if (depth == 0) {
                result = evaluator.evaluate(pos);
            } else {
                int[] moves = new int[Position.MAX_MOVES];
                int[] keys = new int[Position.MAX_MOVES];
                int count = pos.generate(moves);
                if (count == 0) {
                    throw new IllegalStateException("a position without moves is outside the replica");
                }
                legal = count;
                order(moves, count, keys, -1);
                AlphaBeta.pick(moves, keys, 0, count);
                int alpha = alphaIn;
                int best = -AlphaBeta.MATE - 1;
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        AlphaBeta.pick(moves, keys, i, count);
                    }
                    pos.make(moves[i]);
                    int s = -negamax(depth - 1, -betaIn, -alpha, height + 1, id, i, moves[i]);
                    pos.unmake();
                    if (s > best) {
                        best = s;
                    }
                    if (s > alpha) {
                        alpha = s;
                    }
                    if (alpha >= betaIn) {
                        cutoff = true;
                        skipped = count - 1 - i;
                        break;
                    }
                }
                result = best;
            }
            if (visit != null) {
                visit.put("legal", legal);
                visit.put("score", result);
                visit.put("cutoff", cutoff);
                visit.put("skipped", skipped);
            }
            return result;
        }
    }

    // ---- minimal JSON -------------------------------------------------------------------------------------------

    /**
     * A small JSON reader and writer (no library on the test class path). The writer lays out the top level and the
     * second level over several lines, deeper levels inline, so that a diff of a data file stays readable.
     */
    static final class Json {
        private final String text;
        private int pos;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json p = new Json(text);
            p.skipWs();
            Object v = p.value();
            p.skipWs();
            if (p.pos != text.length()) {
                throw new IllegalArgumentException("trailing characters at " + p.pos);
            }
            return v;
        }

        static Map<String, Object> parseObject(String text) {
            return asObject(parse(text));
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> asObject(Object o) {
            return (Map<String, Object>) o;
        }

        private void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private Object value() {
            char c = text.charAt(pos);
            switch (c) {
                case '{': {
                    pos++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    skipWs();
                    if (text.charAt(pos) == '}') {
                        pos++;
                        return m;
                    }
                    while (true) {
                        skipWs();
                        String key = string();
                        skipWs();
                        expect(':');
                        skipWs();
                        m.put(key, value());
                        skipWs();
                        if (text.charAt(pos) == ',') {
                            pos++;
                        } else {
                            expect('}');
                            return m;
                        }
                    }
                }
                case '[': {
                    pos++;
                    List<Object> l = new ArrayList<>();
                    skipWs();
                    if (text.charAt(pos) == ']') {
                        pos++;
                        return l;
                    }
                    while (true) {
                        skipWs();
                        l.add(value());
                        skipWs();
                        if (text.charAt(pos) == ',') {
                            pos++;
                        } else {
                            expect(']');
                            return l;
                        }
                    }
                }
                case '"':
                    return string();
                case 't':
                    pos += 4;
                    return Boolean.TRUE;
                case 'f':
                    pos += 5;
                    return Boolean.FALSE;
                case 'n':
                    pos += 4;
                    return null;
                default:
                    return number();
            }
        }

        private void expect(char c) {
            if (text.charAt(pos) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + pos + " but found " + text.charAt(pos));
            }
            pos++;
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (text.charAt(pos) != '"') {
                char c = text.charAt(pos++);
                if (c == '\\') {
                    char e = text.charAt(pos++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            pos++;
            return sb.toString();
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String s = text.substring(start, pos);
            if (s.isEmpty()) {
                throw new IllegalArgumentException("unexpected character at " + start + ": " + text.charAt(start));
            }
            if (s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
                return Double.parseDouble(s);
            }
            return Long.parseLong(s);
        }

        /** The first difference between two parsed documents, or null: integers exactly, floats within 1e-4. */
        static String difference(String path, Object expected, Object actual) {
            if (expected instanceof Map<?, ?> em && actual instanceof Map<?, ?> am) {
                if (!em.keySet().equals(am.keySet())) {
                    return path + ": keys " + em.keySet() + " vs " + am.keySet();
                }
                for (Map.Entry<?, ?> e : em.entrySet()) {
                    String d = difference(path + "." + e.getKey(), e.getValue(), am.get(e.getKey()));
                    if (d != null) {
                        return d;
                    }
                }
                return null;
            }
            if (expected instanceof List<?> el && actual instanceof List<?> al) {
                if (el.size() != al.size()) {
                    return path + ": length " + el.size() + " vs " + al.size();
                }
                for (int i = 0; i < el.size(); i++) {
                    String d = difference(path + "[" + i + "]", el.get(i), al.get(i));
                    if (d != null) {
                        return d;
                    }
                }
                return null;
            }
            if (expected instanceof Number en && actual instanceof Number an) {
                if (en instanceof Long && an instanceof Long) {
                    return en.equals(an) ? null : path + ": " + en + " vs " + an;
                }
                return Math.abs(en.doubleValue() - an.doubleValue()) <= FLOAT_TOLERANCE ? null
                        : path + ": " + en + " vs " + an;
            }
            if (expected == null ? actual == null : expected.equals(actual)) {
                return null;
            }
            return path + ": " + expected + " vs " + actual;
        }

        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            write(sb, value, 0);
            return sb.append('\n').toString();
        }

        private static boolean isContainer(Object o) {
            return o instanceof Map<?, ?> || o instanceof List<?>;
        }

        private static void write(StringBuilder sb, Object v, int depth) {
            if (v instanceof Map<?, ?> m) {
                boolean multi = depth <= 1;
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    sb.append(first ? "" : ",");
                    first = false;
                    if (multi) {
                        sb.append('\n').append("  ".repeat(depth + 1));
                    }
                    quote(sb, (String) e.getKey());
                    sb.append(multi ? ": " : ":");
                    write(sb, e.getValue(), depth + 1);
                }
                if (multi && !m.isEmpty()) {
                    sb.append('\n').append("  ".repeat(depth));
                }
                sb.append('}');
            } else if (v instanceof List<?> l) {
                boolean multi = depth <= 1 && l.stream().anyMatch(ExplainerDataTest.Json::isContainer);
                sb.append('[');
                boolean first = true;
                for (Object o : l) {
                    sb.append(first ? "" : ",");
                    first = false;
                    if (multi) {
                        sb.append('\n').append("  ".repeat(depth + 1));
                    }
                    write(sb, o, depth + 1);
                }
                if (multi && !l.isEmpty()) {
                    sb.append('\n').append("  ".repeat(depth));
                }
                sb.append(']');
            } else if (v == null) {
                sb.append("null");
            } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
                sb.append(v);
            } else if (v instanceof BigDecimal b) {
                sb.append(b.toPlainString());
            } else if (v instanceof Number n) {
                double d = n.doubleValue();
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("not finite: " + n);
                }
                sb.append(new BigDecimal(d).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            } else if (v instanceof String s) {
                quote(sb, s);
            } else {
                throw new IllegalArgumentException("unsupported value " + v.getClass());
            }
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
        }
    }
}
