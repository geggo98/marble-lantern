package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.Rows;

/**
 * The solitaire generator against the engine, the restricted paths against an independent search, the beam against
 * the proven 27-move optimum [Bell2009], and the plan's bookkeeping.
 */
class SolitaireTest {

    /** Random configurations of one player: pieces anywhere the player may stand, {@code n} of them. */
    static int[] randomConfiguration(Random random, int player, int n) {
        Layout layout = Layout.of(2);
        TreeSet<Integer> cells = new TreeSet<>();
        while (cells.size() < n) {
            int c = random.nextInt(Geometry.CELLS);
            if (layout.allowed(player, c)) {
                cells.add(c);
            }
        }
        return cells.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Configurations biased towards the race: most pieces in the front half, in the player's rows. */
    static int[] raceConfiguration(Random random, int player, int n) {
        Layout layout = Layout.of(2);
        TreeSet<Integer> cells = new TreeSet<>();
        int minRow = random.nextInt(-3, 5);
        while (cells.size() < n) {
            int c = random.nextInt(Geometry.CELLS);
            if (layout.allowed(player, c) && Rows.row(player, c) >= minRow) {
                cells.add(c);
            }
        }
        return cells.stream().mapToInt(Integer::intValue).toArray();
    }

    static Set<Integer> engineMoves(int player, int[] cells, LeaveGoal leaveGoal) {
        Position pos = Position.empty(RuleSet.defaults(2).withLeaveGoal(leaveGoal));
        for (int c : cells) {
            pos.put(player, c);
        }
        pos.setSideToMove(player);
        Set<Integer> moves = new HashSet<>();
        for (int m : pos.legalMoves()) {
            moves.add(m);
        }
        return moves;
    }

    static Set<Integer> solitaireMoves(Solitaire sol, int[] cells, int minLanding) {
        long lo = 0;
        long hi = 0;
        for (int c : cells) {
            lo = Solitaire.withLo(lo, c);
            hi = Solitaire.withHi(hi, c);
        }
        Set<Integer> moves = new HashSet<>();
        for (int from : cells) {
            sol.landings(lo, hi, from, minLanding, to -> {
                assertTrue(moves.add(Move.of(from, to)), "duplicate move " + Move.toString(Move.of(from, to)));
            });
        }
        return moves;
    }

    /** An independent restricted reachability: breadth-first over landings with row {@code >= minLanding}. */
    static Set<Integer> referenceRestrictedMoves(int player, int[] cells, LeaveGoal leaveGoal, int minLanding) {
        Layout layout = Layout.of(2);
        boolean[] occ = new boolean[Geometry.CELLS];
        for (int c : cells) {
            occ[c] = true;
        }
        Set<Integer> moves = new HashSet<>();
        int goal = layout.goalCorner(player);
        for (int from : cells) {
            occ[from] = false;
            boolean[] reached = new boolean[Geometry.CELLS];
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(from, d);
                if (n >= 0 && !occ[n] && layout.allowed(player, n) && Rows.row(player, n) >= minLanding) {
                    reached[n] = true;
                }
            }
            boolean[] visited = new boolean[Geometry.CELLS];
            visited[from] = true;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(from);
            while (!queue.isEmpty()) {
                int c = queue.poll();
                for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                    int over = Geometry.neighbor(c, d);
                    if (over < 0 || !occ[over]) {
                        continue;
                    }
                    int land = Geometry.neighbor(over, d);
                    if (land < 0 || occ[land] || visited[land] || !layout.allowed(player, land)
                            || Rows.row(player, land) < minLanding) {
                        continue;
                    }
                    visited[land] = true;
                    reached[land] = true;
                    queue.add(land);
                }
            }
            occ[from] = true;
            for (int to = 0; to < Geometry.CELLS; to++) {
                if (reached[to] && (leaveGoal == LeaveGoal.ALLOWED || Geometry.cornerOf(from) != goal
                        || Geometry.cornerOf(to) == goal)) {
                    moves.add(Move.of(from, to));
                }
            }
        }
        return moves;
    }

    @Test
    void unrestrictedGeneratorEqualsTheEngineMoveList() {
        Random random = new Random(3);
        int checked = 0;
        for (LeaveGoal leaveGoal : LeaveGoal.values()) {
            for (int player = 0; player < 2; player++) {
                Solitaire sol = new Solitaire(player, leaveGoal, null);
                for (int i = 0; i < 4000; i++) {
                    int[] cells = i % 2 == 0 ? randomConfiguration(random, player, 10)
                            : raceConfiguration(random, player, 1 + random.nextInt(10));
                    assertEquals(engineMoves(player, cells, leaveGoal), solitaireMoves(sol, cells, Integer.MIN_VALUE),
                            "player " + player + " " + leaveGoal + " " + Arrays.toString(cells));
                    checked++;
                }
            }
        }
        assertEquals(16000, checked);
    }

    @Test
    void restrictedGeneratorEqualsAnIndependentSearchAndItsPathsAreValid() {
        Random random = new Random(4);
        RuleSet rules = RuleSet.defaults(2);
        for (int player = 0; player < 2; player++) {
            Solitaire sol = new Solitaire(player, LeaveGoal.FORBIDDEN, null);
            for (int i = 0; i < 4000; i++) {
                int[] cells = raceConfiguration(random, player, 10);
                int minLanding = random.nextInt(-4, 7);
                Set<Integer> moves = solitaireMoves(sol, cells, minLanding);
                assertEquals(referenceRestrictedMoves(player, cells, LeaveGoal.FORBIDDEN, minLanding), moves);
                long lo = 0;
                long hi = 0;
                for (int c : cells) {
                    lo = Solitaire.withLo(lo, c);
                    hi = Solitaire.withHi(hi, c);
                }
                for (int m : moves) {
                    Solitaire.Step step = sol.step(lo, hi, Move.from(m), Move.to(m), minLanding);
                    assertNotNull(step);
                    assertEquals(Move.to(m), step.to());
                    assertTrue(step.pathMinRow(player) >= minLanding);
                    assertTrue(RaceAdjudication.reachableRestricted(player, rules, cells, step.from(), step.path(),
                            minLanding), "path " + Arrays.toString(step.path()) + " of " + Move.toString(m));
                }
            }
        }
    }

    @Test
    void theIndependentPathCheckRejectsBrokenPaths() {
        RuleSet rules = RuleSet.defaults(2);
        // player 0, pieces on (0,0) and (0,1): the piece on (0,0) jumps over (0,1) to (0,2)
        int a = Geometry.id(0, 0);
        int h = Geometry.id(0, 1);
        int land = Geometry.id(0, 2);
        int[] own = {a, h};
        assertTrue(RaceAdjudication.reachableRestricted(0, rules, own, a, new int[] {land}, -8));
        assertTrue(!RaceAdjudication.reachableRestricted(0, rules, own, a, new int[] {land}, 3), "row restriction");
        assertTrue(!RaceAdjudication.reachableRestricted(0, rules, own, a, new int[] {Geometry.id(0, 3)}, -8),
                "no hurdle");
        assertTrue(!RaceAdjudication.reachableRestricted(0, rules, own, a, new int[] {h}, -8), "occupied landing");
        assertTrue(!RaceAdjudication.reachableRestricted(0, rules, new int[] {h}, a, new int[] {land}, -8),
                "no piece on the start cell");
        assertTrue(RaceAdjudication.reachableRestricted(0, rules, own, a, new int[] {Geometry.id(1, 0)}, -8), "step");
        assertTrue(!RaceAdjudication.reachableRestricted(0, rules, own, a, new int[] {Geometry.id(2, 0)}, -8),
                "two cells away without a hurdle");
    }

    /**
     * [Bell2009] proves 27 moves optimal for one player alone from the start. Every plan is a verified legal
     * sequence, so fewer than 27 would contradict the proof; the beam needs width 20000 to find 27 (width 1000 finds
     * 31, 5000 finds 29; about 4 s).
     */
    @Test
    void theBeamReproducesTheProvenTwentySevenMoveSolitaire() {
        Solitaire sol = new Solitaire(0, LeaveGoal.FORBIDDEN, RaceTable.rows2());
        int[] start = Position.initial(RuleSet.defaults(2)).pieces(0);
        Solitaire.Plan plan = sol.plan(start, 20_000, 40, Integer.MIN_VALUE);
        assertNotNull(plan, "no plan found");
        assertTrue(plan.moves() >= 27, "found " + plan.moves() + " moves, below the proven optimum");
        assertEquals(27, plan.moves(), "the beam of width 20000 should find the optimum");
        RaceAdjudication.verify(Position.initial(RuleSet.defaults(2)), 0, -100, plan, true);
        // the same plan for player 1 by symmetry
        Solitaire sol1 = new Solitaire(1, LeaveGoal.FORBIDDEN, RaceTable.rows2());
        Solitaire.Plan plan1 = sol1.plan(Position.initial(RuleSet.defaults(2)).pieces(1), 20_000, 40,
                Integer.MIN_VALUE);
        assertNotNull(plan1);
        assertEquals(27, plan1.moves());
    }

    /** Replaces one goal cell by an outside cell and returns the configuration. */
    private static int[] goalWithout(int goalCell, int outsideCell) {
        int[] goal = Geometry.cornerCells(Layout.of(2).goalCorner(0)).clone();
        for (int i = 0; i < goal.length; i++) {
            if (goal[i] == goalCell) {
                goal[i] = outsideCell;
            }
        }
        Arrays.sort(goal);
        return goal;
    }

    @Test
    void theLowerBoundIsZeroInTheGoalAndCountsOutsidePieces() {
        Solitaire sol = new Solitaire(0, LeaveGoal.FORBIDDEN, null);
        int[] goal = Geometry.cornerCells(Layout.of(2).goalCorner(0)).clone();
        Arrays.sort(goal);
        assertEquals(0, sol.lowerBound(goal));
        // (-2,5) is free and the piece stands next to it on (-2,4): one move (a step)
        assertEquals(1, sol.lowerBound(goalWithout(Geometry.id(-2, 5), Geometry.id(-2, 4))));
        // (-2,6) is free [class (0,0)] and the piece stands on (0,0) [class (0,0)], not adjacent: one move (a chain)
        assertEquals(1, sol.lowerBound(goalWithout(Geometry.id(-2, 6), Geometry.id(0, 0))));
        // (-4,5) is free [class (0,1)] and the piece stands on (0,0) [class (0,0)], not adjacent: two moves
        assertEquals(2, sol.lowerBound(goalWithout(Geometry.id(-4, 5), Geometry.id(0, 0))));
        // (-4,5) is free and the piece stands on (-4,4), adjacent: one move
        assertEquals(1, sol.lowerBound(goalWithout(Geometry.id(-4, 5), Geometry.id(-4, 4))));
    }

    @Test
    void threePiecesCompetingForOneClassNeedExtraMoves() {
        Solitaire sol = new Solitaire(0, LeaveGoal.FORBIDDEN, null);
        // goal cells by class (q mod 2, r mod 2): (0,1): (-4,5),(-2,5),(-4,7); (1,1): (-3,5),(-1,5),(-3,7);
        // (0,0): (-4,6),(-2,6),(-4,8); (1,0): (-3,6)
        int[] cells = {Geometry.id(-4, 5), Geometry.id(-2, 5), Geometry.id(-4, 7), Geometry.id(-3, 5),
            Geometry.id(-1, 5), Geometry.id(-3, 7), Geometry.id(-4, 6), Geometry.id(-2, 6), Geometry.id(-4, 8),
            Geometry.id(-3, 6)};
        Arrays.sort(cells);
        assertEquals(0, sol.lowerBound(cells));
        // free (-3,6) [class (1,0)], (-4,6) and (-2,6) [class (0,0)]; three pieces of class (1,0) far away:
        // one takes (-3,6) in one move, the other two need two moves each
        int[] three = cells.clone();
        three[0] = Geometry.id(1, 0);
        three[1] = Geometry.id(3, 0);
        three[2] = Geometry.id(1, -2);
        Arrays.sort(three);
        // which cells are missing now: (-4,5), (-2,5), (-4,7) were replaced (sorted order differs); recompute
        Set<Integer> present = new HashSet<>();
        for (int c : three) {
            present.add(c);
        }
        int outside = 0;
        for (int c : three) {
            if (Geometry.cornerOf(c) != Layout.of(2).goalCorner(0)) {
                outside++;
            }
        }
        assertEquals(3, outside);
        int lb = sol.lowerBound(three);
        assertTrue(lb >= 3 && lb <= 6, "lb " + lb);
        // brute check of the bound against the formula 2*outside - matching
        int matching = maxMatching(three, 0);
        assertEquals(2 * outside - matching, lb);
    }

    /** Brute-force maximum matching for the test's own cross-check (permutations over at most 10 pieces). */
    static int maxMatching(int[] cells, int player) {
        Layout layout = Layout.of(2);
        int goal = layout.goalCorner(player);
        java.util.List<Integer> outside = new java.util.ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        for (int c : cells) {
            taken.add(c);
            if (Geometry.cornerOf(c) != goal) {
                outside.add(c);
            }
        }
        java.util.List<Integer> free = new java.util.ArrayList<>();
        for (int g : Geometry.cornerCells(goal)) {
            if (!taken.contains(g)) {
                free.add(g);
            }
        }
        return bestMatching(outside, free, 0, new boolean[free.size()]);
    }

    private static int bestMatching(java.util.List<Integer> outside, java.util.List<Integer> free, int i,
            boolean[] used) {
        if (i == outside.size()) {
            return 0;
        }
        int best = bestMatching(outside, free, i + 1, used);
        for (int j = 0; j < free.size(); j++) {
            if (!used[j] && Solitaire.oneMoveClass(outside.get(i), free.get(j))) {
                used[j] = true;
                best = Math.max(best, 1 + bestMatching(outside, free, i + 1, used));
                used[j] = false;
            }
        }
        return best;
    }

    @Test
    void theLowerBoundEqualsTheBruteForceMatchingFormula() {
        Random random = new Random(5);
        for (int player = 0; player < 2; player++) {
            Solitaire sol = new Solitaire(player, LeaveGoal.FORBIDDEN, null);
            for (int i = 0; i < 2000; i++) {
                int[] cells = raceConfiguration(random, player, 10);
                int outside = 0;
                for (int c : cells) {
                    if (Geometry.cornerOf(c) != Layout.of(2).goalCorner(player)) {
                        outside++;
                    }
                }
                assertEquals(2 * outside - maxMatching(cells, player), sol.lowerBound(cells));
            }
        }
    }
}
