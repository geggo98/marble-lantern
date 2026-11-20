package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.TriangleRows;

/**
 * The phase feature {@code dsumPhase} of the linear evaluator on the 5-row board (board-rows package V1). The feature is
 * the {@code dsum} difference times the total remaining distance of both sides / 100; its weight is positive and the
 * weight of {@code dsum} negative, so a large total distance turns the reward for progress around. The weights were
 * fitted with 10 pieces per side. With 15 pieces the total distance of the opening is about 1.35 times larger, and
 * without a correction a step towards the goal lowered the score: the 3-player browser player on 5 rows put no piece
 * into its goal in 1500 plies (measured 2026-11-20, cpp/results/rows5-v1-2026-11-20.txt). The total distance is
 * therefore counted in 10-piece units: times 10 / pieces.
 */
class PhaseOnFiveRowsTest {

    /** The score of the side sums, from {@code me}'s point of view. */
    private static int score(LinearEvaluator evaluator, int[] me, int[] opponent) {
        double[] out = new double[LinearEvaluator.FEATURES];
        LinearEvaluator.combine(me, opponent, out);
        return evaluator.score(out, 0.0);
    }

    /**
     * In the start position of every board size and player count, the move of player 0 with the largest distance gain
     * scores higher for player 0 than the start position, with each fitted 12-feature weight set. The score is that of
     * the side sums of player 0 against player 1, before and after the move.
     */
    @Test
    void theMostProgressScoresHigherThanTheStart() {
        for (TriangleRows rows : TriangleRows.values()) {
            for (int players = 2; players <= 3; players++) {
                RuleSet rules = RuleSet.defaults(players, rows);
                Position pos = Position.initial(rules);
                int layout = LinearEvaluator.layoutIndex(rules);
                int[] dist = LinearEvaluator.DIST[layout][0];
                int best = -1;
                for (int m : pos.legalMoves()) {
                    int gain = dist[Move.from(m)] - dist[Move.to(m)];
                    if (best < 0 || gain > dist[Move.from(best)] - dist[Move.to(best)]) {
                        best = m;
                    }
                }
                int[] me = new int[LinearEvaluator.SIDE_SUMS];
                int[] opponent = new int[LinearEvaluator.SIDE_SUMS];
                LinearEvaluator.side(pos, layout, 0, me);
                LinearEvaluator.side(pos, layout, 1, opponent);
                pos.make(best);
                int[] after = new int[LinearEvaluator.SIDE_SUMS];
                LinearEvaluator.side(pos, layout, 0, after);
                for (double[] weights : new double[][] {LinearWeights.R1, LinearWeights.ROUND2}) {
                    LinearEvaluator evaluator = new LinearEvaluator(weights, LinearWeights.SCALE);
                    String what = rows + ", " + players + " players, move " + Move.toString(best) + " (gain "
                            + (dist[Move.from(best)] - dist[Move.to(best)]) + ")";
                    int before = score(evaluator, me, opponent);
                    int now = score(evaluator, after, opponent);
                    assertTrue(now > before, what + ": scores " + now + ", the start " + before);
                }
            }
        }
    }

    /** The phase term in 10-piece units: 15 pieces count their total distance times 10/15; 10 pieces unchanged. */
    @Test
    void thePhaseCountsTenPieceUnits() {
        int[] me = new int[LinearEvaluator.SIDE_SUMS];
        int[] opponent = new int[LinearEvaluator.SIDE_SUMS];
        me[LinearEvaluator.DSUM] = 140;
        opponent[LinearEvaluator.DSUM] = 130;
        double[] out = new double[LinearEvaluator.FEATURES];
        me[LinearEvaluator.COUNT] = 15;
        opponent[LinearEvaluator.COUNT] = 15;
        LinearEvaluator.combine(me, opponent, out);
        // by hand: dsum difference 10, total 270, in 10-piece units 180, / 100
        assertEquals(10.0 * 180 / 100, out[10], 1e-12, "15 pieces");
        me[LinearEvaluator.COUNT] = 10;
        opponent[LinearEvaluator.COUNT] = 10;
        LinearEvaluator.combine(me, opponent, out);
        assertEquals(10.0 * 270 / 100, out[10], 0.0, "10 pieces");
    }

    /**
     * The 3-player browser player of the 5-row board finishes games: before the correction it reached the move limit
     * (375 plies) with no piece in any goal. Seeded random openings of 4 plies, both jump rules.
     */
    @Test
    void threePlayerBrowserGamesOnFiveRowsEndWithAWin() {
        for (JumpRule jump : JumpRule.values()) {
            String spec = (jump == JumpRule.LONG ? Strategies.BROWSER_3P_ROWS5_LONG_SPEC : Strategies.BROWSER_3P_ROWS5_SPEC)
                    .replace("nodes=20000", "nodes=1000");
            for (long seed = 1; seed <= 2; seed++) {
                Position end = play(RuleSet.defaults(3, TriangleRows.FIVE).withJumpRule(jump), seed, new Player[] {
                    ThreePlayerSpec.parse(spec).create(), ThreePlayerSpec.parse(spec).create(), ThreePlayerSpec.parse(spec).create()});
                assertTrue(end.outcome() != null && end.outcome().hasWinner(),
                        jump + " seed " + seed + ": no winner at ply " + end.ply() + ", pieces in goal "
                                + end.goalOccupied(0) + "/" + end.goalOccupied(1) + "/" + end.goalOccupied(2));
            }
        }
    }

    /** The 2-player browser player of the 5-row board at the lowest level (1000 nodes) finishes its games too. */
    @Test
    void twoPlayerBrowserGamesOnFiveRowsEndWithAWinAtTheLowestLevel() {
        for (JumpRule jump : JumpRule.values()) {
            String spec = (jump == JumpRule.LONG ? Strategies.BROWSER_ROWS5_LONG_SPEC : Strategies.BROWSER_ROWS5_SPEC) + ",nodes=1000";
            for (long seed = 1; seed <= 3; seed++) {
                Position end = play(RuleSet.defaults(2, TriangleRows.FIVE).withJumpRule(jump), seed,
                        new Player[] {PlayerSpec.parse(spec).create(), PlayerSpec.parse(spec).create()});
                assertTrue(end.outcome() != null && end.outcome().hasWinner(),
                        jump + " seed " + seed + ": no winner at ply " + end.ply() + ", pieces in goal "
                                + end.goalOccupied(0) + "/" + end.goalOccupied(1));
            }
        }
    }

    private static Position play(RuleSet rules, long seed, Player[] players) {
        Random random = new Random(seed);
        Position pos = Position.initial(rules);
        while (pos.outcome() == null && pos.legalMoves().length > 0) {
            int[] legal = pos.legalMoves();
            pos.make(pos.ply() < 4 ? legal[random.nextInt(legal.length)] : players[pos.sideToMove()].choose(pos));
        }
        return pos;
    }
}
