package sternhalma.search;

import java.util.*;
import sternhalma.engine.*;
import sternhalma.engine.RuleSet.JumpRule;

/**
 * Study behind the choice of the static evaluation for the three-player advantage indicator (not a test; run with
 * {@code java -cp <engine, search main and test classes> sternhalma.search.ThreePlayerEvaluationStudy adjacent|long <games> <nodes>}).
 * Seeded games (12 random plies, then three {@code ab3} players with the node budget), at plies 45, 75 and 105 the static
 * vector and a depth-2 search score per player; how often the best player of each predicts the winner.
 */
final class ThreePlayerEvaluationStudy {
    public static void main(String[] a) {
        JumpRule jump = a[0].equals("long") ? JumpRule.LONG : JumpRule.ADJACENT;
        int games = Integer.parseInt(a[1]);
        long budget = Long.parseLong(a[2]);
        int[] checkpoints = {45, 75, 105};
        int[] hitStatic = new int[3], hitShallow = new int[3], n = new int[3];
        double[] rankStatic = new double[3], rankShallow = new double[3];
        int decided = 0;
        for (int g = 0; g < games; g++) {
            RuleSet rules = RuleSet.defaults(3).withJumpRule(jump);
            Position pos = Position.initial(rules);
            SplittableRandom rnd = new SplittableRandom(1000 + g);
            ThreePlayerPlayer[] pl = new ThreePlayerPlayer[3];
            for (int i = 0; i < 3; i++) pl[i] = ThreePlayerSpec.parse("ab3:nodes=" + budget + ",tt=14" + (jump == JumpRule.LONG ? ",jump=long" : "")).create();
            ThreePlayerSearch s = new ThreePlayerSearch(LinearWeights.ROUND2, 14);
            List<int[][]> snaps = new ArrayList<>();
            while (pos.outcome() == null) {
                for (int k = 0; k < 3; k++) if (pos.ply() == checkpoints[k]) {
                    int[] st = s.evaluateAll(pos);
                    int[] sh = new int[3];
                    for (int r = 0; r < 3; r++) sh[r] = s.searchAs(pos, r, 2, Long.MAX_VALUE).score();
                    snaps.add(new int[][] {st, sh, {k}});
                }
                int[] legal = pos.legalMoves();
                if (legal.length == 0) break;
                int m = pos.ply() < 12 ? legal[rnd.nextInt(legal.length)] : pl[pos.sideToMove()].choose(pos);
                pos.make(m);
            }
            Outcome o = pos.outcome();
            if (o == null || !o.hasWinner()) continue;
            decided++;
            int w = o.winner();
            for (int[][] sn : snaps) {
                int k = sn[2][0];
                n[k]++;
                if (argmax(sn[0]) == w) hitStatic[k]++;
                if (argmax(sn[1]) == w) hitShallow[k]++;
                rankStatic[k] += rank(sn[0], w);
                rankShallow[k] += rank(sn[1], w);
            }
        }
        System.out.println("jump=" + jump + " games=" + games + " decided=" + decided);
        for (int k = 0; k < 3; k++)
            System.out.printf("ply %d: n=%d static top-1 %.3f mean rank of winner %.3f | depth2 top-1 %.3f rank %.3f%n", checkpoints[k], n[k],
                (double) hitStatic[k] / n[k], rankStatic[k] / n[k], (double) hitShallow[k] / n[k], rankShallow[k] / n[k]);
    }
    static int argmax(int[] v) { int b = 0; for (int i = 1; i < 3; i++) if (v[i] > v[b]) b = i; return b; }
    static int rank(int[] v, int w) { int r = 1; for (int i = 0; i < 3; i++) if (v[i] > v[w]) r++; return r; }
}
