package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

class Stage4EvaluatorTest {

    /**
     * A position from a game where each ply picks the best of three random moves by distance gain, so the
     * armies advance and meet instead of milling around the start corners.
     */
    private static Position biasedRandomPosition(Random random, int plies) {
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < plies && pos.outcome() == null; i++) {
            int[] moves = pos.legalMoves();
            if (moves.length == 0) {
                break;
            }
            int p = pos.sideToMove();
            int best = moves[random.nextInt(moves.length)];
            for (int k = 0; k < 2; k++) {
                int m = moves[random.nextInt(moves.length)];
                if (gain(pos, p, m) > gain(pos, p, best)) {
                    best = m;
                }
            }
            pos.make(best);
        }
        return pos;
    }

    private static int gain(Position pos, int player, int move) {
        return pos.layout().distance(player, Move.from(move)) - pos.layout().distance(player, Move.to(move));
    }

    @Test
    void zeroWeightsEqualTheDistanceEvaluator() {
        Evaluator distance = new DistanceEvaluator();
        Evaluator stage4 = new Stage4Evaluator(0, 0, 0, 0);
        Random random = new Random(7);
        for (int i = 0; i < 300; i++) {
            Position pos = biasedRandomPosition(random, random.nextInt(120));
            assertEquals(distance.evaluate(pos), stage4.evaluate(pos));
        }
    }

    @Test
    void colourSwappedMirrorScoresTheSame() {
        Evaluator[] evaluators = {
            new Stage4Evaluator(7, 0, 0, 0), new Stage4Evaluator(0, 3, 0, 0),
            new Stage4Evaluator(0, 0, 5, 0), new Stage4Evaluator(0, 0, 0, 4),
            new Stage4Evaluator(6, -2, 3, 5),
        };
        Random random = new Random(11);
        int differing = 0;
        Evaluator distance = new DistanceEvaluator();
        for (int i = 0; i < 400; i++) {
            Position pos = biasedRandomPosition(random, random.nextInt(150));
            Position flipped = pos.transformed(Geometry::playerFlip, true);
            for (Evaluator e : evaluators) {
                assertEquals(e.evaluate(pos), e.evaluate(flipped), () -> e + " on " + pos);
            }
            if (evaluators[4].evaluate(pos) != distance.evaluate(pos)) {
                differing++;
            }
        }
        assertTrue(differing > 300, "the correction terms should usually change the score, changed " + differing);
    }

    @Test
    void stragglerTermPenalisesTheLastPiece() {
        // the same distance sum, but player 0 has one piece far behind in the second position
        Position even = Position.empty(RuleSet.defaults(2)).put(0, 0, 0).put(0, 0, 1).put(1, 0, -4);
        Position strag = Position.empty(RuleSet.defaults(2)).put(0, 0, -1).put(0, 0, 2).put(1, 0, -4);
        Evaluator distance = new DistanceEvaluator();
        assertEquals(distance.evaluate(even), distance.evaluate(strag));
        Evaluator straggler = new Stage4Evaluator(5, 0, 0, 0);
        assertTrue(straggler.evaluate(even) > straggler.evaluate(strag));
    }

    @Test
    void cohesionAndJumpTermsCountPairsAndForwardJumps() {
        // player 0 moves towards r = +8; pieces at (0,0) and (0,1): one adjacent pair, (0,0) can jump to (0,2)
        Position pos = Position.empty(RuleSet.defaults(2)).put(0, 0, 0).put(0, 0, 1).put(1, 4, -8);
        int base = new DistanceEvaluator().evaluate(pos);
        assertEquals(base + 1, new Stage4Evaluator(0, 0, 1, 0).evaluate(pos));
        assertTrue(new Stage4Evaluator(0, 0, 0, 1).evaluate(pos) > base);
    }

    /** Straightforward reference for the pair and jump counts of one player. */
    private static int[] naiveCounts(Position pos, int p) {
        int pairs = 0;
        int jumps = 0;
        var layout = pos.layout();
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (pos.owner(c) != p) {
                continue;
            }
            for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                int n = Geometry.neighbor(c, dir);
                if (n < 0 || pos.owner(n) < 0) {
                    continue;
                }
                if (pos.owner(n) == p && n > c && (layout.distance(p, c) > 0 || layout.distance(p, n) > 0)) {
                    pairs++;
                }
                int land = Geometry.neighbor(n, dir);
                if (land >= 0 && pos.owner(land) < 0 && layout.allowed(p, land)
                        && layout.distance(p, land) < layout.distance(p, c)) {
                    jumps++;
                }
            }
        }
        return new int[] {pairs, jumps};
    }

    @Test
    void precomputedTablesMatchANaiveCount() {
        Evaluator distance = new DistanceEvaluator();
        Evaluator pairsOnly = new Stage4Evaluator(0, 0, 1, 0);
        Evaluator jumpsOnly = new Stage4Evaluator(0, 0, 0, 1);
        Random random = new Random(5);
        for (int i = 0; i < 300; i++) {
            Position pos = biasedRandomPosition(random, random.nextInt(150));
            int me = pos.sideToMove();
            int[] mine = naiveCounts(pos, me);
            int[] theirs = naiveCounts(pos, 1 - me);
            int base = distance.evaluate(pos);
            assertEquals(base + mine[0] - theirs[0], pairsOnly.evaluate(pos));
            assertEquals(base + mine[1] - theirs[1], jumpsOnly.evaluate(pos));
        }
    }

    @Test
    void specSetsTheWeights() {
        PlayerSpec spec = PlayerSpec.parse("ab:eval=stage4,ws=5,wc=-2,nodes=100");
        assertEquals(Map.of("ws", 5, "wc", -2), spec.weights());
        assertEquals(100, spec.nodes());
        assertTrue(spec.create().name().contains("stage4"));
        assertTrue(PlayerSpec.parse("ab:eval=distance,nodes=20000").weights().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:eval=distance,ws=3").create());
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:eval=stage4,wz=3").create());
        assertThrows(NumberFormatException.class, () -> PlayerSpec.parse("ab:eval=stage4,ws=x"));
        assertNotEquals(PlayerSpec.parse("ab:eval=stage4").create().name(),
                PlayerSpec.parse("ab:eval=stage4,ws=1").create().name());
    }
}
