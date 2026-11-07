package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.BlockadeRule;
import sternhalma.engine.RuleSet.DrawHandling;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.engine.RuleSet.Repetition;
import sternhalma.engine.RuleSet.SuicideMoves;

/**
 * Experiment E02: the generator with piece lists, flat tables and the Definition-2 pre-check must emit exactly
 * the moves of the old generator ({@link LegacyMoveGenerator}) in exactly the same order, on more than a million
 * positions over the rule variants of docs/testing.md and three players. The search tree depends on the order,
 * so a set comparison is not enough.
 *
 * <p>The positions come from random playouts, greedy playouts (which reach the late game) and constructed
 * positions with an almost full goal, where the Definition-2 test actually rejects moves. The test counts those
 * rejections and the leaveGoal rejections and fails if they are too rare, so that a pre-check that skipped the
 * test too often could not pass on positions where the test never mattered.
 */
class MoveOrderEquivalenceTest {

    private static final int POSITIONS_PER_VARIANT = 160_000;

    private record Variant(String name, RuleSet rules) {
    }

    private static final List<Variant> VARIANTS = List.of(
            new Variant("default", RuleSet.defaults(2)),
            new Variant("leaveGoal=ALLOWED", RuleSet.defaults(2).withLeaveGoal(LeaveGoal.ALLOWED)),
            new Variant("repetition draw", RuleSet.defaults(2).withRepetition(Repetition.REPETITION_IS_DRAW)
                    .withDrawHandling(DrawHandling.DRAW)),
            new Variant("blockade NONE, suicide LEGAL, half limit", RuleSet.defaults(2)
                    .withBlockadeRule(BlockadeRule.NONE).withSuicideMoves(SuicideMoves.LEGAL).withMoveLimit(100)),
            new Variant("blockade NONE, suicide ILLEGAL", RuleSet.defaults(2).withBlockadeRule(BlockadeRule.NONE)),
            new Variant("suicide LEGAL", RuleSet.defaults(2).withSuicideMoves(SuicideMoves.LEGAL)),
            new Variant("3 players", RuleSet.defaults(3)),
            new Variant("3 players, leaveGoal=ALLOWED, blockade NONE", RuleSet.defaults(3)
                    .withLeaveGoal(LeaveGoal.ALLOWED).withBlockadeRule(BlockadeRule.NONE)));

    /** Counters over one variant. */
    private static final class Stats {
        long positions;
        long moves;
        long nearlyFull;
        long suicideRejections;
        long leaveGoalRejections;
        long referenceChecks;
    }

    @Test
    void theNewGeneratorEmitsTheOldMovesInTheOldOrderOnOverAMillionPositions() {
        long total = 0;
        StringBuilder report = new StringBuilder();
        for (int v = 0; v < VARIANTS.size(); v++) {
            Variant variant = VARIANTS.get(v);
            Stats stats = run(variant, 0xE02_0000L + v);
            total += stats.positions;
            report.append(String.format("%-45s %,9d positions %,11d moves %,8d nearly full %,7d suicide %,8d leaveGoal%n",
                    variant.name(), stats.positions, stats.moves, stats.nearlyFull, stats.suicideRejections,
                    stats.leaveGoalRejections));
            RuleSet r = variant.rules();
            // Definition 2 can fire only under Definition 1 with two players: there a move into the other player's
            // goal (the mover's start corner) can fill it. Under NONE the goal must hold 10 own pieces, and with
            // three players no other piece may enter a goal, so no move of another player can complete it.
            if (r.players() == 2 && r.suicideMoves() == SuicideMoves.ILLEGAL
                    && r.blockadeRule() == BlockadeRule.OPPONENT_FILLS) {
                assertTrue(stats.suicideRejections >= 1000,
                        variant.name() + ": the Definition-2 test rejected only " + stats.suicideRejections + " moves");
            }
            if (r.leaveGoal() == LeaveGoal.FORBIDDEN) {
                assertTrue(stats.leaveGoalRejections >= 1000,
                        variant.name() + ": leaveGoal rejected only " + stats.leaveGoalRejections + " moves");
            }
            assertTrue(stats.nearlyFull >= 1000, variant.name() + ": only " + stats.nearlyFull + " nearly full goals");
            assertTrue(stats.referenceChecks >= 1000, variant.name() + ": only " + stats.referenceChecks + " oracle checks");
        }
        System.out.print(report);
        assertTrue(total >= 1_000_000, "only " + total + " positions");
    }

    private static Stats run(Variant variant, long seed) {
        RuleSet rules = variant.rules();
        SplittableRandom random = new SplittableRandom(seed);
        LegacyMoveGenerator legacy = new LegacyMoveGenerator(rules);
        Stats stats = new Stats();
        for (int game = 0; stats.positions < POSITIONS_PER_VARIANT; game++) {
            // per 8 games: 1 random playout, 1 greedy playout, 6 short games from a constructed almost full goal
            int mode = Math.min(game % 8, 2);
            Position pos = mode == 2 ? nearlyFullGoal(rules, random) : Position.initial(rules);
            double epsilon = mode == 0 ? 1.0 : mode == 1 ? 0.1 : 0.3;
            int maxPlies = mode == 2 ? 12 : rules.moveLimit();
            for (int ply = 0; ply < maxPlies && pos.outcome() == null; ply++) {
                String where = variant.name() + ", seed " + seed + ", game " + game + ", ply " + ply + "\n" + pos;
                int[] moves = check(pos, legacy, stats, where);
                if (moves.length == 0) {
                    break;
                }
                pos.make(pick(pos, moves, random, epsilon));
            }
        }
        return stats;
    }

    private static int[] check(Position pos, LegacyMoveGenerator legacy, Stats stats, String where) {
        int[] expected = legacy.legalMoves(pos);
        int[] actual = pos.legalMoves();
        assertArrayEquals(expected, actual, "move order differs: " + where);
        stats.positions++;
        stats.moves += actual.length;
        stats.suicideRejections += legacy.suicideRejections;
        stats.leaveGoalRejections += legacy.leaveGoalRejections;
        int p = pos.sideToMove();
        for (int q = 0; q < pos.rules().players(); q++) {
            if (q != p && pos.goalOccupied(q) >= Geometry.CORNER_SIZE - 1) {
                stats.nearlyFull++;
                break;
            }
        }
        assertPieceListsMatchTheBoard(pos, where);
        if (stats.positions % 64 == 0) {
            stats.referenceChecks++;
            Set<Integer> engine = new HashSet<>();
            for (int m : actual) {
                engine.add(Move.from(m) * 1000 + Move.to(m));
            }
            assertEquals(ReferenceGenerator.legalMoves(pos), engine, "independent oracle: " + where);
        }
        if (stats.positions % 16 == 0) {
            int[][] before = new int[pos.rules().players()][];
            for (int q = 0; q < before.length; q++) {
                before[q] = pos.pieces(q);
            }
            for (int m : actual) {
                pos.make(m);
                assertPieceListsMatchTheBoard(pos, "after " + Move.toString(m) + ": " + where);
                pos.unmake();
                for (int q = 0; q < before.length; q++) {
                    assertArrayEquals(before[q], pos.pieces(q), "unmake " + Move.toString(m) + ": " + where);
                }
            }
        }
        return actual;
    }

    /** The piece lists and goal counters equal a recount from the owner of each cell. */
    static void assertPieceListsMatchTheBoard(Position pos, String where) {
        Layout layout = pos.layout();
        for (int q = 0; q < pos.rules().players(); q++) {
            int[] scan = new int[Geometry.CELLS];
            int n = 0;
            int inGoal = 0;
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (pos.owner(c) == q) {
                    scan[n++] = c;
                }
                if (pos.owner(c) >= 0 && Geometry.cornerOf(c) == layout.goalCorner(q)) {
                    inGoal++;
                }
            }
            assertEquals(n, pos.pieceCount(q), "piece count of " + q + ": " + where);
            for (int i = 0; i < n; i++) {
                if (scan[i] != pos.pieceCell(q, i)) {
                    assertEquals(scan[i], pos.pieceCell(q, i), "piece " + i + " of player " + q + ": " + where);
                }
            }
            assertEquals(inGoal, pos.goalOccupied(q), "goalOccupied(" + q + "): " + where);
        }
    }

    /** Greedy on the moving piece's distance gain (random tie-break), or a uniformly random move. */
    private static int pick(Position pos, int[] moves, SplittableRandom random, double epsilon) {
        if (random.nextDouble() < epsilon) {
            return moves[random.nextInt(moves.length)];
        }
        Layout layout = pos.layout();
        int p = pos.sideToMove();
        int best = Integer.MIN_VALUE;
        int choice = moves[0];
        int ties = 0;
        for (int m : moves) {
            int gain = layout.distance(p, Move.from(m)) - layout.distance(p, Move.to(m));
            if (gain > best) {
                best = gain;
                choice = m;
                ties = 1;
            } else if (gain == best && random.nextInt(++ties) == 0) {
                choice = m;
            }
        }
        return choice;
    }

    /**
     * A legal setup where one player's goal holds 8 to 10 pieces, including opponent pieces when the goal is
     * another player's start corner (two players), so that Definition 1 and 2 decide moves into and out of it.
     */
    static Position nearlyFullGoal(RuleSet rules, SplittableRandom random) {
        int players = rules.players();
        Layout layout = Layout.of(players);
        while (true) {
            Position pos = Position.empty(rules);
            int[] left = new int[players];
            java.util.Arrays.fill(left, Position.PIECES);
            int q = random.nextInt(players);
            int[] goal = Geometry.cornerCells(layout.goalCorner(q)).clone();
            shuffle(goal, random);
            int fill = random.nextInt(3) == 0 ? 8 + random.nextInt(3) : 9;
            for (int i = 0; i < fill; i++) {
                // two players: the goal of q is the start corner of the other player, who may stand there
                int owner = players == 2 && random.nextInt(3) == 0 ? 1 - q : q;
                if (left[owner] == 0) {
                    owner = 1 - owner;
                }
                pos.put(owner, goal[i]);
                left[owner]--;
            }
            if (players == 2) {
                // pieces of the other player next to the free goal cells, so that a step can complete the goal
                for (int i = fill; i < goal.length; i++) {
                    for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                        int n = Geometry.neighbor(goal[i], d);
                        if (n >= 0 && left[1 - q] > 0 && pos.owner(n) < 0 && layout.allowed(1 - q, n)
                                && random.nextInt(3) == 0) {
                            pos.put(1 - q, n);
                            left[1 - q]--;
                        }
                    }
                }
            }
            int[] cells = new int[Geometry.CELLS];
            for (int c = 0; c < Geometry.CELLS; c++) {
                cells[c] = c;
            }
            shuffle(cells, random);
            for (int p = 0; p < players; p++) {
                for (int c : cells) {
                    if (left[p] == 0) {
                        break;
                    }
                    if (pos.owner(c) < 0 && layout.allowed(p, c) && !(p != q && random.nextInt(4) != 0
                            && Geometry.cornerOf(c) == layout.goalCorner(q))) {
                        pos.put(p, c);
                        left[p]--;
                    }
                }
            }
            pos.setSideToMove(players == 2 && random.nextInt(4) != 0 ? 1 - q : random.nextInt(players));
            if (pos.outcome() == null) {
                return pos;
            }
        }
    }

    private static void shuffle(int[] a, SplittableRandom random) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}
