package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.Ranking;

/**
 * The parity-assignment lower bound of {@link Solitaire#lowerBound} must never exceed the true number of solitaire
 * moves: on every rows&gt;=2 configuration it is at most the table count (a necessary condition, the count being an
 * upper bound of the unrestricted distance), and on sampled configurations it is at most the exact unrestricted
 * distance found by iterative deepening with the trivially admissible heuristic "pieces outside the goal". The
 * sample also shows that the bound is tight in a good share of cases.
 */
class LowerBoundAdmissibilityTest {

    @Test
    void theBoundNeverExceedsTheTableCountOnAllThirteenMillionStates() throws Exception {
        RaceTable table = RaceTable.rows2();
        Ranking ranking = table.rankingOf();
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        long n = ranking.count();
        List<Future<long[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                long begin = n * t / threads;
                long end = n * (t + 1) / threads;
                futures.add(pool.submit(() -> {
                    Solitaire sol = new Solitaire(0, LeaveGoal.FORBIDDEN, null);
                    long violations = 0;
                    long tight = 0;
                    for (long rank = begin; rank < end; rank++) {
                        int[] cells = ranking.unrank(rank);
                        int lb = sol.lowerBound(cells);
                        int count = table.entry(rank);
                        if (lb > count) {
                            violations++;
                        } else if (lb == count) {
                            tight++;
                        }
                    }
                    return new long[] {violations, tight};
                }));
            }
            long violations = 0;
            long tight = 0;
            for (Future<long[]> f : futures) {
                long[] r = f.get();
                violations += r[0];
                tight += r[1];
            }
            System.out.printf(java.util.Locale.ROOT,
                    "lower bound vs table count on %d rows>=2 states: %d violations, tight on %d (%.1f%%)%n", n,
                    violations, tight, 100.0 * tight / n);
            assertEquals(0, violations, "lower bound above the table count");
            assertTrue(tight > n / 100, "the bound is tight on " + tight + " of " + n + " states");
        }
    }

    /**
     * Exact unrestricted solitaire distance by iterative deepening over a plain occupancy array, with the
     * trivially admissible heuristic "pieces outside the goal"; -1 if the node cap is hit. Written against
     * {@link Geometry} directly (steps, jump chains over own pieces, allowed cells, leaveGoal), independent of
     * {@link Solitaire}; {@link #theExactSolverAgreesWithTheEngineOnItsMoves} checks its move generation.
     */
    static int exactDistance(int player, int[] cells, LeaveGoal leaveGoal, long cap) {
        Solver solver = new Solver(player, leaveGoal, cap);
        for (int c : cells) {
            solver.occupied[c] = true;
        }
        int outside = solver.outside();
        for (int limit = outside; limit <= 40; limit++) {
            int r = solver.dfs(limit);
            if (r == 1) {
                return limit;
            }
            if (r < 0) {
                return -1;
            }
        }
        return -1;
    }

    static final class Solver {
        final int player;
        final LeaveGoal leaveGoal;
        final long cap;
        final Layout layout = Layout.of(2);
        final int goal;
        final boolean[] occupied = new boolean[Geometry.CELLS];
        long nodes;

        Solver(int player, LeaveGoal leaveGoal, long cap) {
            this.player = player;
            this.leaveGoal = leaveGoal;
            this.cap = cap;
            this.goal = layout.goalCorner(player);
        }

        int outside() {
            int n = 0;
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (occupied[c] && Geometry.cornerOf(c) != goal) {
                    n++;
                }
            }
            return n;
        }

        /** All end cells of moves of the piece on {@code from}: steps and jump chains over own pieces. */
        List<Integer> landings(int from) {
            List<Integer> result = new ArrayList<>();
            occupied[from] = false;
            boolean[] visited = new boolean[Geometry.CELLS];
            visited[from] = true;
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(from, d);
                if (n >= 0 && !occupied[n] && layout.allowed(player, n) && endAllowed(from, n)) {
                    result.add(n);
                }
            }
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(from);
            while (!queue.isEmpty()) {
                int c = queue.poll();
                for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                    int over = Geometry.neighbor(c, d);
                    if (over < 0 || !occupied[over]) {
                        continue;
                    }
                    int land = Geometry.neighbor(over, d);
                    if (land < 0 || occupied[land] || visited[land] || !layout.allowed(player, land)) {
                        continue;
                    }
                    visited[land] = true;
                    queue.add(land);
                    if (endAllowed(from, land)) {
                        result.add(land);
                    }
                }
            }
            occupied[from] = true;
            return result;
        }

        private boolean endAllowed(int from, int to) {
            return leaveGoal == LeaveGoal.ALLOWED || Geometry.cornerOf(from) != goal || Geometry.cornerOf(to) == goal;
        }

        /** 1 found within the limit, 0 not, -1 capped. */
        int dfs(int limit) {
            return dfs(limit, outside());
        }

        /** As {@link #dfs(int)} with the number of pieces outside the goal known (kept incrementally). */
        private int dfs(int limit, int outside) {
            if (++nodes > cap) {
                return -1;
            }
            if (outside == 0) {
                return 1;
            }
            if (outside > limit) {
                return 0;
            }
            for (int from = 0; from < Geometry.CELLS; from++) {
                if (!occupied[from]) {
                    continue;
                }
                boolean fromGoal = Geometry.cornerOf(from) == goal;
                for (int to : landings(from)) {
                    boolean toGoal = Geometry.cornerOf(to) == goal;
                    occupied[from] = false;
                    occupied[to] = true;
                    int r = dfs(limit - 1, outside + (fromGoal ? 1 : 0) - (toGoal ? 1 : 0));
                    occupied[to] = false;
                    occupied[from] = true;
                    if (r != 0) {
                        return r;
                    }
                }
            }
            return 0;
        }
    }

    @Test
    void theExactSolverAgreesWithTheEngineOnItsMoves() {
        Random random = new Random(77);
        for (int i = 0; i < 2000; i++) {
            int player = random.nextInt(2);
            LeaveGoal leaveGoal = random.nextBoolean() ? LeaveGoal.ALLOWED : LeaveGoal.FORBIDDEN;
            int[] cells = SolitaireTest.raceConfiguration(random, player, 1 + random.nextInt(10));
            Solver solver = new Solver(player, leaveGoal, Long.MAX_VALUE);
            for (int c : cells) {
                solver.occupied[c] = true;
            }
            java.util.Set<Integer> moves = new java.util.HashSet<>();
            for (int from : cells) {
                for (int to : solver.landings(from)) {
                    moves.add(Move.of(from, to));
                }
            }
            assertEquals(SolitaireTest.engineMoves(player, cells, leaveGoal), moves);
        }
    }

    @Test
    void theBoundNeverExceedsTheExactUnrestrictedDistanceOnSampledConfigurations() throws Exception {
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        int target = 10_000;
        List<Future<int[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                long seed = 500 + t;
                int share = target / threads + 1;
                futures.add(pool.submit(() -> {
                    Random random = new Random(seed);
                    int solved = 0;
                    int capped = 0;
                    int violations = 0;
                    int tight = 0;
                    int sumExact = 0;
                    while (solved < share) {
                        int player = random.nextInt(2);
                        LeaveGoal leaveGoal = random.nextInt(4) == 0 ? LeaveGoal.ALLOWED : LeaveGoal.FORBIDDEN;
                        // late-race configurations: most pieces in the goal, the rest a few rows before it
                        int[] cells = lateRace(random, player);
                        Solitaire sol = new Solitaire(player, leaveGoal, null);
                        int lb = sol.lowerBound(cells);
                        int exact = exactDistance(player, cells, leaveGoal, 2_000_000);
                        if (exact < 0) {
                            capped++;
                            continue;
                        }
                        solved++;
                        sumExact += exact;
                        if (lb > exact) {
                            violations++;
                        } else if (lb == exact) {
                            tight++;
                        }
                    }
                    return new int[] {solved, capped, violations, tight, sumExact};
                }));
            }
            int solved = 0;
            int capped = 0;
            int violations = 0;
            int tight = 0;
            int sumExact = 0;
            for (Future<int[]> f : futures) {
                int[] r = f.get();
                solved += r[0];
                capped += r[1];
                violations += r[2];
                tight += r[3];
                sumExact += r[4];
            }
            System.out.printf(java.util.Locale.ROOT,
                    "lower bound vs exact distance on %d late-race configurations (%d capped): %d violations, "
                            + "tight on %d (%.1f%%), mean exact distance %.2f%n",
                    solved, capped, violations, tight, 100.0 * tight / solved, (double) sumExact / solved);
            assertTrue(solved >= target, solved + " solved, " + capped + " capped");
            assertEquals(0, violations, "lower bound above the exact distance");
            assertTrue(sumExact > 2 * solved, "mean exact distance " + (double) sumExact / solved);
            // the bound is a lower bound, not an estimate: measured tight on about 16% of these configurations
            assertTrue(tight > solved / 20, "tight on " + tight + " of " + solved);
        }
    }

    /** A configuration with 5 to 9 pieces in the goal and the rest on rows 1 to 4 (player's rows). */
    static int[] lateRace(Random random, int player) {
        Layout layout = Layout.of(2);
        int[] goal = Geometry.cornerCells(layout.goalCorner(player)).clone();
        java.util.List<Integer> goalList = new ArrayList<>();
        for (int g : goal) {
            goalList.add(g);
        }
        java.util.Collections.shuffle(goalList, random);
        int inGoal = 5 + random.nextInt(5);
        java.util.TreeSet<Integer> cells = new java.util.TreeSet<>(goalList.subList(0, inGoal));
        while (cells.size() < 10) {
            int c = random.nextInt(Geometry.CELLS);
            int row = sternhalma.tablebase.Rows.row(player, c);
            if (layout.allowed(player, c) && row >= 1 && row <= 4) {
                cells.add(c);
            }
        }
        return cells.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void theExactSolverIsRightOnHandCases() {
        // player 0: nine pieces in the goal, (-2,5) free, one piece on (-2,4): one step
        int[] goal = Geometry.cornerCells(Layout.of(2).goalCorner(0)).clone();
        int[] one = goal.clone();
        for (int i = 0; i < one.length; i++) {
            if (one[i] == Geometry.id(-2, 5)) {
                one[i] = Geometry.id(-2, 4);
            }
        }
        assertEquals(1, exactDistance(0, one, LeaveGoal.FORBIDDEN, 1_000_000));
        assertEquals(1, new Solitaire(0, LeaveGoal.FORBIDDEN, null).lowerBound(one));
        // (-4,5) free, piece on (0,0): no one-move route (class differs), so the bound says two moves; with no
        // hurdle anywhere near the piece the exact distance is the hex distance of five steps (the bound is not tight)
        int[] two = goal.clone();
        for (int i = 0; i < two.length; i++) {
            if (two[i] == Geometry.id(-4, 5)) {
                two[i] = Geometry.id(0, 0);
            }
        }
        assertEquals(2, new Solitaire(0, LeaveGoal.FORBIDDEN, null).lowerBound(two));
        assertEquals(5, exactDistance(0, two, LeaveGoal.FORBIDDEN, 1_000_000));
        // with a hurdle the bound is tight: outside pieces on (-2,3) [the class (0,1) of the free (-4,5)] and on
        // (-3,4) [adjacent to the free (-3,5)]: the jump (-2,3) over (-3,4) to (-4,5), then the step to (-3,5)
        int[] jump = goal.clone();
        for (int i = 0; i < jump.length; i++) {
            if (jump[i] == Geometry.id(-4, 5)) {
                jump[i] = Geometry.id(-2, 3);
            } else if (jump[i] == Geometry.id(-3, 5)) {
                jump[i] = Geometry.id(-3, 4);
            }
        }
        java.util.Arrays.sort(jump);
        assertEquals(2, exactDistance(0, jump, LeaveGoal.FORBIDDEN, 1_000_000),
                "the jump (-2,3) over (-3,4) to (-4,5), then (-3,4) steps to (-3,5)");
        assertEquals(2, new Solitaire(0, LeaveGoal.FORBIDDEN, null).lowerBound(jump));
        assertEquals(0, exactDistance(0, goal, LeaveGoal.FORBIDDEN, 1_000_000));
    }
}
