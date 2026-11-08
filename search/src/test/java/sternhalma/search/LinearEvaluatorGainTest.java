package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.engine.RuleSet.SuicideMoves;
import sternhalma.search.LinearEvaluator.GainMode;

/**
 * Round-2 experiment E07: the {@code bestGain} feature of {@link LinearEvaluator}. The cheap search (steps, a jump
 * depth-first search with a stamp-visited array, early exit at the cap) is checked against the engine's own move
 * generator, which is an independent implementation of the same move rules.
 */
class LinearEvaluatorGainTest {

    /**
     * The definition, from the engine's legal moves of {@code p}: the largest distance gain of a move whose piece is
     * not in p's goal, floored at 0 and capped.
     */
    static int referenceGain(Position pos, int p) {
        Position view = pos.sideToMove() == p ? pos : pos.transformed(c -> c, false).setSideToMove(p);
        Layout layout = view.layout();
        int best = 0;
        for (int m : view.legalMoves()) {
            int from = Move.from(m);
            if (Geometry.cornerOf(from) == layout.goalCorner(p)) {
                continue;
            }
            best = Math.max(best, layout.distance(p, from) - layout.distance(p, Move.to(m)));
        }
        return Math.min(best, LinearEvaluator.GAIN_CAP);
    }

    /** Greedy-with-noise playouts (many jump chains in the middle game), scattered and clustered set-ups. */
    static List<Position> positions(RuleSet rules, long seed, int count) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Position> out = new ArrayList<>();
        while (out.size() < count) {
            int kind = random.nextInt(3);
            if (kind == 0) {
                Position pos = Position.initial(rules);
                double epsilon = random.nextDouble() * 0.6;
                int plies = random.nextInt(180);
                for (int i = 0; i < plies && pos.outcome() == null; i++) {
                    int[] moves = pos.legalMoves();
                    if (moves.length == 0) {
                        break;
                    }
                    pos.make(random.nextDouble() < epsilon ? moves[random.nextInt(moves.length)]
                            : greedy(pos, moves, random));
                    if (random.nextInt(4) == 0) {
                        out.add(pos.copy());
                    }
                }
            } else {
                Position pos = setUp(rules, random, kind == 2);
                if (pos != null) {
                    out.add(pos);
                }
            }
        }
        return out;
    }

    private static int greedy(Position pos, int[] moves, SplittableRandom random) {
        Layout layout = pos.layout();
        int p = pos.sideToMove();
        int best = Integer.MIN_VALUE;
        int choice = moves[0];
        for (int m : moves) {
            int g = layout.distance(p, Move.from(m)) - layout.distance(p, Move.to(m)) + random.nextInt(2);
            if (g > best) {
                best = g;
                choice = m;
            }
        }
        return choice;
    }

    /** Ten pieces per player on random allowed cells; clustered: within a radius of the centre (dense hurdles). */
    private static Position setUp(RuleSet rules, SplittableRandom random, boolean clustered) {
        Position pos = Position.empty(rules);
        Layout layout = pos.layout();
        int radius = 3 + random.nextInt(2);
        for (int p = 0; p < 2; p++) {
            int pieces = Position.PIECES;
            int tries = 0;
            while (pieces > 0) {
                if (++tries > 100_000) {
                    return null;
                }
                int c = random.nextInt(Geometry.CELLS);
                boolean near = Math.max(Math.abs(Geometry.q(c)), Math.max(Math.abs(Geometry.r(c)),
                        Math.abs(Geometry.s(c)))) <= radius;
                if (pos.owner(c) < 0 && layout.allowed(p, c) && (!clustered || near)) {
                    pos.put(p, c);
                    pieces--;
                }
            }
        }
        pos.setSideToMove(random.nextInt(2));
        return pos.outcome() == null ? pos : null;
    }

    @Test
    void bestGainEqualsTheEngineMoveListOnManyPositions() {
        long checked = 0;
        long jumpChainsDecide = 0;
        int[] histogram = new int[LinearEvaluator.GAIN_CAP + 1];
        for (LeaveGoal leaveGoal : LeaveGoal.values()) {
            // suicide moves legal: then the engine never removes a move, and the definition is exact
            RuleSet rules = RuleSet.defaults(2).withLeaveGoal(leaveGoal).withSuicideMoves(SuicideMoves.LEGAL);
            for (Position pos : positions(rules, 0xE07_0001L + leaveGoal.ordinal(), 15_000)) {
                for (int p = 0; p < 2; p++) {
                    int expected = referenceGain(pos, p);
                    assertEquals(expected, LinearEvaluator.bestGain(pos, p), "player " + p + "\n" + pos);
                    histogram[expected]++;
                    if (expected > stepGain(pos, p)) {
                        jumpChainsDecide++;
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked >= 60_000, "checked " + checked);
        // the set must exercise the jump search and the cap, not only steps
        assertTrue(jumpChainsDecide > checked / 10, "jumps decide only " + jumpChainsDecide + " of " + checked);
        assertTrue(histogram[LinearEvaluator.GAIN_CAP] > checked / 50, "cap reached " + histogram[6] + " times");
        for (int g = 0; g <= LinearEvaluator.GAIN_CAP; g++) {
            assertTrue(histogram[g] > 0, "no position with best gain " + g);
        }
    }

    /** With the default rules (suicide moves illegal) the feature is never below the engine's moves. */
    @Test
    void withTheSuicideRuleTheFeatureIsNeverBelowTheLegalMoves() {
        for (Position pos : positions(RuleSet.defaults(2), 0xE07_0003L, 4000)) {
            for (int p = 0; p < 2; p++) {
                assertTrue(LinearEvaluator.bestGain(pos, p) >= referenceGain(pos, p), pos.toString());
            }
        }
    }

    private static int stepGain(Position pos, int p) {
        Layout layout = pos.layout();
        int best = 0;
        for (int c : pos.pieces(p)) {
            if (Geometry.cornerOf(c) == layout.goalCorner(p)) {
                continue;
            }
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(c, d);
                if (n >= 0 && pos.owner(n) < 0 && layout.allowed(p, n)) {
                    best = Math.max(best, layout.distance(p, c) - layout.distance(p, n));
                }
            }
        }
        return best;
    }

    /**
     * A constructed position: one piece of player 0 far from its goal with a ladder of hurdles towards the goal, so
     * that a chain of single jumps gains two rows per jump, while every step gains at most 1. The gain of the chain
     * is computed from the cells of the chain, independently of the move generator.
     */
    @Test
    void aConstructedJumpChainGivesItsKnownGain() {
        RuleSet rules = RuleSet.defaults(2);
        Layout layout = Layout.of(2);
        // find the direction in which player 0's distance falls fastest from the centre
        int centre = Geometry.id(0, 0);
        int dir = -1;
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            int land = Geometry.jumpLanding(centre, d);
            if (land >= 0 && (dir < 0 || layout.distance(0, land) < layout.distance(0, Geometry.jumpLanding(centre, dir)))) {
                dir = d;
            }
        }
        for (int jumps = 1; jumps <= 3; jumps++) {
            Position pos = Position.empty(rules);
            int from = Geometry.jumpLanding(Geometry.jumpLanding(centre, opposite(dir)), opposite(dir)); // two back
            pos.put(0, from);
            int c = from;
            for (int j = 0; j < jumps; j++) {
                pos.put(1, Geometry.neighbor(c, dir)); // hurdle of the opponent: any piece is a hurdle
                c = Geometry.jumpLanding(c, dir);
            }
            int expected = Math.min(layout.distance(0, from) - layout.distance(0, c), LinearEvaluator.GAIN_CAP);
            assertTrue(expected >= 2 * jumps, "the ladder must gain at least two per jump: " + expected);
            assertEquals(expected, LinearEvaluator.bestGain(pos, 0), jumps + " jumps\n" + pos);
            assertEquals(expected, referenceGain(pos, 0), "the engine agrees");
            // remove the last hurdle: the chain is one jump shorter
            if (jumps > 1) {
                Position shorter = Position.empty(rules);
                shorter.put(0, from);
                int e = from;
                for (int j = 0; j < jumps - 1; j++) {
                    shorter.put(1, Geometry.neighbor(e, dir));
                    e = Geometry.jumpLanding(e, dir);
                }
                assertEquals(Math.min(layout.distance(0, from) - layout.distance(0, e), LinearEvaluator.GAIN_CAP),
                        LinearEvaluator.bestGain(shorter, 0), (jumps - 1) + " jumps");
            }
        }
    }

    private static int opposite(int d) {
        for (int e = 0; e < Geometry.DIRECTIONS; e++) {
            int c = Geometry.id(0, 0);
            if (Geometry.neighbor(Geometry.neighbor(c, d), e) == c) {
                return e;
            }
        }
        throw new AssertionError();
    }

    @Test
    void piecesInTheGoalAreSkippedAndTheFloorIsZero() {
        RuleSet rules = RuleSet.defaults(2).withLeaveGoal(LeaveGoal.ALLOWED);
        Layout layout = Layout.of(2);
        // premise of the skip: every goal cell has distance 0, so a goal piece can never gain (the skip only saves
        // time); with leaveGoal allowed its moves out of the goal lose distance and the floor keeps the gain at 0
        for (int p = 0; p < 2; p++) {
            for (int c : Geometry.cornerCells(layout.goalCorner(p))) {
                assertEquals(0, layout.distance(p, c));
            }
        }
        Position pos = Position.empty(rules);
        int[] goal = Geometry.cornerCells(layout.goalCorner(0));
        pos.put(0, goal[0]);
        pos.put(1, Geometry.cornerCells(layout.goalCorner(1))[0]);
        assertTrue(pos.legalMoves().length > 0);
        assertEquals(0, LinearEvaluator.bestGain(pos, 0));
        assertEquals(0, referenceGain(pos, 0));
        // a piece in its start corner on an otherwise empty board: steps gain 1, nothing to jump over
        Position back = Position.empty(rules);
        int start = Geometry.cornerCells(layout.startCorner(0))[0];
        back.put(0, start);
        back.put(1, Geometry.cornerCells(layout.startCorner(1))[0]); // far away, in player 1's start corner
        assertEquals(1, LinearEvaluator.bestGain(back, 0));
        assertEquals(referenceGain(back, 0), LinearEvaluator.bestGain(back, 0));
    }

    @Test
    void theGainFeatureIsAntisymmetricInBothModeAndTheMoverOwnInMoverMode() {
        double[] x = new double[LinearEvaluator.GAIN_FEATURES];
        double[] y = new double[LinearEvaluator.GAIN_FEATURES];
        int nonzero = 0;
        for (Position pos : positions(RuleSet.defaults(2), 0xE07_0004L, 3000)) {
            Position other = pos.transformed(c -> c, false).setSideToMove(1 - pos.sideToMove());
            LinearEvaluator.features(pos, x, GainMode.BOTH);
            LinearEvaluator.features(other, y, GainMode.BOTH);
            for (int i = 0; i < LinearEvaluator.GAIN_FEATURES; i++) {
                if (i != LinearEvaluator.TEMPO) {
                    assertEquals(-x[i], y[i], 1e-9, LinearEvaluator.GAIN_NAMES[i]);
                }
            }
            int me = pos.sideToMove();
            assertEquals(LinearEvaluator.bestGain(pos, me) - LinearEvaluator.bestGain(pos, 1 - me),
                    x[LinearEvaluator.BEST_GAIN]);
            if (x[LinearEvaluator.BEST_GAIN] != 0) {
                nonzero++;
            }
            LinearEvaluator.features(pos, x, GainMode.MOVER);
            assertEquals(LinearEvaluator.bestGain(pos, me), x[LinearEvaluator.BEST_GAIN]);
        }
        assertTrue(nonzero > 1000, "too few positions with a nonzero gain difference: " + nonzero);
    }

    @Test
    void theFirstTwelveFeaturesAreBitIdenticalAndAZeroGainWeightScoresAsTheTwelveFeatureEvaluator() {
        double[] x12 = new double[LinearEvaluator.FEATURES];
        double[] x13 = new double[LinearEvaluator.GAIN_FEATURES];
        double[] w13 = java.util.Arrays.copyOf(LinearWeights.ROUND2, LinearEvaluator.GAIN_FEATURES);
        LinearEvaluator twelve = new LinearEvaluator(LinearWeights.ROUND2, LinearWeights.SCALE);
        LinearEvaluator both = new LinearEvaluator(w13, LinearWeights.SCALE, GainMode.BOTH);
        LinearEvaluator mover = new LinearEvaluator(w13, LinearWeights.SCALE, GainMode.MOVER);
        assertNull(twelve.gainMode());
        for (Position pos : positions(RuleSet.defaults(2), 0xE07_0005L, 3000)) {
            LinearEvaluator.features(pos, x12);
            LinearEvaluator.features(pos, x13, GainMode.BOTH);
            for (int i = 0; i < LinearEvaluator.FEATURES; i++) {
                assertEquals(Double.doubleToRawLongBits(x12[i]), Double.doubleToRawLongBits(x13[i]),
                        LinearEvaluator.NAMES[i]);
            }
            assertEquals(twelve.evaluate(pos), both.evaluate(pos));
            assertEquals(twelve.evaluate(pos), mover.evaluate(pos));
        }
    }

    @Test
    void theGainWeightMovesTheScoreByItsFeatureValue() {
        double[] w = new double[LinearEvaluator.GAIN_FEATURES];
        w[LinearEvaluator.BEST_GAIN] = 1.0;
        LinearEvaluator only = new LinearEvaluator(w, 10, GainMode.BOTH);
        LinearEvaluator moverOnly = new LinearEvaluator(w, 10, GainMode.MOVER);
        for (Position pos : positions(RuleSet.defaults(2), 0xE07_0006L, 1000)) {
            int me = pos.sideToMove();
            assertEquals(10 * (LinearEvaluator.bestGain(pos, me) - LinearEvaluator.bestGain(pos, 1 - me)),
                    only.evaluate(pos));
            assertEquals(10 * LinearEvaluator.bestGain(pos, me), moverOnly.evaluate(pos));
        }
    }

    @Test
    void aColourSwappedMirrorImageScoresTheSameWithTheGainFeature() {
        double[] w = java.util.Arrays.copyOf(LinearWeights.ROUND2, LinearEvaluator.GAIN_FEATURES);
        w[LinearEvaluator.BEST_GAIN] = 0.37;
        for (GainMode mode : GainMode.values()) {
            LinearEvaluator e = new LinearEvaluator(w, LinearWeights.SCALE, mode);
            for (Position pos : positions(RuleSet.defaults(2), 0xE07_0007L, 1500)) {
                Position image = pos.transformed(Geometry::playerFlip, true);
                assertEquals(e.evaluate(pos), e.evaluate(image), mode + "\n" + pos);
                int me = pos.sideToMove();
                assertEquals(LinearEvaluator.bestGain(pos, me), LinearEvaluator.bestGain(image, image.sideToMove()));
            }
        }
    }

    @Test
    void theWeightCountMustMatchTheMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new LinearEvaluator(new double[LinearEvaluator.FEATURES], 100, GainMode.BOTH));
        assertThrows(IllegalArgumentException.class,
                () -> new LinearEvaluator(new double[LinearEvaluator.GAIN_FEATURES], 100, null));
        assertEquals(GainMode.BOTH, new LinearEvaluator(new double[LinearEvaluator.GAIN_FEATURES], 100).gainMode());
    }

    @Test
    void versionedAndUnversionedWeightFilesAreRead(@TempDir Path dir) throws IOException {
        double[] w12 = {-1, 0, 0, 0, 0, 0, 0, 0.5, 0, 0, 0, 0.25};
        double[] w13 = {-1, 0, 0, 0, 0, 0, 0, 0.5, 0, 0, 0, 0.25, 0.75};
        List<Position> positions = positions(RuleSet.defaults(2), 0xE07_0008L, 300);
        Path legacy = dir.resolve("legacy.txt");
        Files.writeString(legacy, "# comment\n50\n-1 0 0 0 0 0 0 0.5 0 0 0 0.25\n");
        Path v12 = dir.resolve("v12.txt");
        Files.writeString(v12, LinearWeights.toText("twelve", 50, w12, null));
        Path both = dir.resolve("both.txt");
        Files.writeString(both, LinearWeights.toText("both", 50, w13, GainMode.BOTH));
        Path mover = dir.resolve("mover.txt");
        Files.writeString(mover, LinearWeights.toText("mover", 50, w13, GainMode.MOVER));
        // a legacy file whose scale happens to be 12 is still a legacy file (13 tokens, not 14)
        Path legacy12 = dir.resolve("legacy12.txt");
        Files.writeString(legacy12, "12\n-1 0 0 0 0 0 0 0.5 0 0 0 0.25\n");
        LinearEvaluator e12 = new LinearEvaluator(w12, 50);
        LinearEvaluator eBoth = new LinearEvaluator(w13, 50, GainMode.BOTH);
        LinearEvaluator eMover = new LinearEvaluator(w13, 50, GainMode.MOVER);
        LinearEvaluator eLegacy12 = new LinearEvaluator(w12, 12);
        assertEquals(GainMode.MOVER, ((LinearEvaluator) Evaluators.byName("linear-file:" + mover)).gainMode());
        assertNull(((LinearEvaluator) Evaluators.byName("linear-file:" + v12)).gainMode());
        int differ = 0;
        for (Position pos : positions) {
            assertEquals(e12.evaluate(pos), Evaluators.byName("linear-file:" + legacy).evaluate(pos));
            assertEquals(e12.evaluate(pos), Evaluators.byName("linear-file:" + v12).evaluate(pos));
            assertEquals(eBoth.evaluate(pos), Evaluators.byName("linear-file:" + both).evaluate(pos));
            assertEquals(eMover.evaluate(pos), Evaluators.byName("linear-file:" + mover).evaluate(pos));
            assertEquals(eLegacy12.evaluate(pos), Evaluators.byName("linear-file:" + legacy12).evaluate(pos));
            if (eBoth.evaluate(pos) != eMover.evaluate(pos)) {
                differ++;
            }
        }
        assertTrue(differ > 0, "the two modes must be told apart");
    }

    @Test
    void malformedWeightFilesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LinearWeights.parse("13 sideways 100 1 1 1 1 1 1 1 1 1 1 1 1 1"
                .split(" "), "unknown mode"));
        assertThrows(IllegalArgumentException.class, () -> LinearWeights.parse("13 both 100 1 1 1 1 1 1 1 1 1 1 1 1"
                .split(" "), "one weight short"));
        assertThrows(IllegalArgumentException.class, () -> LinearWeights.parse("100 1 1 1 1 1 1 1 1 1 1 1"
                .split(" "), "legacy, one weight short"));
        assertThrows(IllegalArgumentException.class, () -> LinearWeights.parse("13 100 1 1 1 1 1 1 1 1 1 1 1 1 1"
                .split(" "), "13 without mode"));
    }

    @Test
    void theRegisteredGainEvaluatorUsesTheGainFeature() {
        LinearEvaluator r3 = (LinearEvaluator) Evaluators.byName("linear-r3");
        assertEquals(GainMode.BOTH, r3.gainMode());
        assertEquals(LinearEvaluator.GAIN_FEATURES, LinearWeights.ROUND3.length);
        assertTrue(LinearWeights.ROUND3[LinearEvaluator.BEST_GAIN] > 0, "a larger own gain must help the mover");
        LinearEvaluator direct = new LinearEvaluator(LinearWeights.ROUND3, LinearWeights.SCALE, GainMode.BOTH);
        LinearEvaluator without = new LinearEvaluator(
                java.util.Arrays.copyOf(LinearWeights.ROUND3, LinearEvaluator.FEATURES), LinearWeights.SCALE);
        int differ = 0;
        for (Position pos : positions(RuleSet.defaults(2), 0xE07_0009L, 500)) {
            assertEquals(direct.evaluate(pos), r3.evaluate(pos));
            if (without.evaluate(pos) != r3.evaluate(pos)) {
                differ++;
            }
        }
        assertTrue(differ > 100, "the gain term must change scores: " + differ);
        // the frozen 12-feature sets stay 12-feature evaluators
        for (String name : List.of("linear", "linear-r1", "linear-r2")) {
            assertNull(((LinearEvaluator) Evaluators.byName(name)).gainMode(), name);
        }
        // the strategy contract covers the evaluator alone and with the re-tuned pruning and race play
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith("ab:eval=linear-r3,nodes=")));
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith(Strategies.GAIN_SPEC + ",")));
        assertTrue(Strategies.GAIN_SPEC.contains("futk=20") && Strategies.GAIN_SPEC.contains("race=1"));
        assertTrue(!Strategies.BEST_SPEC.contains("linear-r3"), "BEST_SPEC changes only in an integration commit");
    }
}
