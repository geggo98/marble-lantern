package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.ForeignCamps;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.TriangleRows;

/**
 * Checks {@code triangle-rows-golden.tsv}, the file that the C++ port and the web engines reproduce for 15-cell
 * houses: the committed file equals a fresh run of {@link TriangleRowsGolden}, and the houses, the legal moves and
 * the wins of every position of every playout agree with {@link TriangleRowsOracle}, which uses neither the layout
 * tables nor the engine's move generation.
 */
class TriangleRowsGoldenTest {

    private static List<String> committed() throws IOException {
        try (InputStream in = TriangleRowsGoldenTest.class.getResourceAsStream("/triangle-rows-golden.tsv")) {
            assertTrue(in != null, "resource triangle-rows-golden.tsv is missing");
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }

    private static TreeSet<Integer> set(int[] values) {
        TreeSet<Integer> s = new TreeSet<>();
        for (int v : values) {
            s.add(v);
        }
        return s;
    }

    private static String cells(TreeSet<Integer> cells) {
        StringBuilder text = new StringBuilder();
        for (int c : cells) {
            text.append(text.isEmpty() ? "" : " ").append(c).append("=(").append(TriangleRowsOracle.q(c)).append(',')
                    .append(TriangleRowsOracle.r(c)).append(')');
        }
        return "[" + text + "]";
    }

    @Test
    void committedFileEqualsAFreshRun() throws IOException {
        List<String> file = committed();
        assertEquals("", file.get(file.size() - 1), "the file ends with a newline");
        assertEquals(TriangleRowsGolden.lines(), file.subList(0, file.size() - 1));
    }

    @Test
    void theOracleNumbersTheCellsLikeTheEngine() {
        assertEquals(Geometry.CELLS, TriangleRowsOracle.cells());
        for (int c = 0; c < Geometry.CELLS; c++) {
            assertEquals(Geometry.q(c), TriangleRowsOracle.q(c), "q of cell " + c);
            assertEquals(Geometry.r(c), TriangleRowsOracle.r(c), "r of cell " + c);
        }
    }

    @Test
    void layoutHousesAndStartPositionsEqualTheOracle() {
        for (TriangleRows rows : TriangleRows.values()) {
            for (int players = 2; players <= 3; players++) {
                Layout layout = Layout.of(players, rows);
                Position start = Position.initial(RuleSet.defaults(players, rows));
                for (int p = 0; p < players; p++) {
                    String where = rows + ", " + players + " players, player " + p;
                    TreeSet<Integer> startHouse = TriangleRowsOracle.house(TriangleRowsOracle.start(players, p),
                            rows.rows());
                    TreeSet<Integer> goalHouse = TriangleRowsOracle.house(TriangleRowsOracle.goal(players, p),
                            rows.rows());
                    assertEquals(rows.pieces(), startHouse.size(), "oracle start house size, " + where);
                    assertEquals(cells(startHouse), cells(set(layout.startCells(p))), "start house, " + where);
                    assertEquals(cells(goalHouse), cells(set(layout.goalCells(p))), "goal house, " + where);
                    assertEquals(cells(startHouse), cells(set(start.pieces(p))), "start position, " + where);
                    for (int c = 0; c < Geometry.CELLS; c++) {
                        assertEquals(startHouse.contains(c), layout.inStart(p, c), "inStart cell " + c + ", " + where);
                        assertEquals(goalHouse.contains(c), layout.inGoal(p, c), "inGoal cell " + c + ", " + where);
                    }
                }
            }
        }
    }

    /** Coverage counters over all playouts; each must be large enough for the oracle check to mean something. */
    private static final class Coverage {
        int positions;
        int pieceOnGoalEdgeRow;
        int opponentGoalAtFourteen;
        int wins;
    }

    @Test
    void everyPositionOfEveryPlayoutAgreesWithTheOracle() {
        Coverage seen = new Coverage();
        for (int id = 0; id < TriangleRowsGolden.ROWS; id++) {
            RuleSet rules = TriangleRowsGolden.rules(id);
            int row = id;
            Position last = TriangleRowsGolden.play(id, pos -> check(row, rules, pos, seen));
            if (last.outcome() != null && last.outcome().reason() == Outcome.Reason.WIN) {
                seen.wins++;
            }
        }
        String summary = "positions " + seen.positions + ", piece on the goal edge row " + seen.pieceOnGoalEdgeRow
                + ", opponent goal at 14 " + seen.opponentGoalAtFourteen + ", wins " + seen.wins;
        assertTrue(seen.positions > 5_000, summary);
        assertTrue(seen.pieceOnGoalEdgeRow > 500, summary);
        assertTrue(seen.opponentGoalAtFourteen > 50, summary);
        assertTrue(seen.wins >= 12, summary);
    }

    private static void check(int id, RuleSet rules, Position pos, Coverage seen) {
        int players = rules.players();
        int p = pos.sideToMove();
        int[] owner = new int[Geometry.CELLS];
        for (int c = 0; c < Geometry.CELLS; c++) {
            owner[c] = pos.owner(c);
        }
        String where = "row " + id + " (" + rules + ") ply " + pos.ply();
        int[] legal = pos.legalMoves();
        TreeSet<Integer> engine = set(legal);
        assertEquals(legal.length, engine.size(), "duplicate moves, " + where);
        TreeSet<Integer> oracle = TriangleRowsOracle.moves(owner, players, p,
                rules.jumpRule() == JumpRule.LONG, rules.foreignCamps(), 5);
        assertEquals(text(oracle), text(engine), "legal moves, " + where);
        for (int q = 0; q < players; q++) {
            assertEquals(TriangleRowsOracle.wins(owner, players, q, 5), pos.hasWon(q), "win of " + q + ", " + where);
        }
        seen.positions++;
        TriangleRowsOracle.Corner goal = TriangleRowsOracle.goal(players, p);
        for (int c : pos.pieces(p)) {
            if (TriangleRowsOracle.inHouse(goal, c, 5) && !TriangleRowsOracle.inHouse(goal, c, 4)) {
                seen.pieceOnGoalEdgeRow++;
                break;
            }
        }
        for (int q = 0; q < players; q++) {
            if (q != p && pos.goalOccupied(q) == 14 && pos.outcome() == null) {
                seen.opponentGoalAtFourteen++; // the engine runs its suicide check (Definition 2) here
                break;
            }
        }
    }

    /**
     * The playouts never reach a move that would fill an opponent's goal (Definition 2), so this case is built by
     * hand: three players, the goal house of player 1 (s &gt;= 4 side) holds 14 pieces, among them the start piece of
     * player 0 on the shared vertex cell (0, -4), three pieces of player 2 on the edge row and ten of player 1. Player 0 must not step into the last
     * hole (-2, -2); the oracle without the suicide rule shows that the step exists.
     */
    @Test
    void aMoveThatFillsAnOpponentsFifteenCellGoalIsIllegalWithThreePlayers() {
        int players = 3;
        int hole = TriangleRowsOracle.id(-2, -2);
        int vertex = TriangleRowsOracle.id(0, -4);
        int mover = TriangleRowsOracle.id(-1, -2);
        TreeSet<Integer> goalOf1 = TriangleRowsOracle.house(TriangleRowsOracle.goal(players, 1), 5);
        assertTrue(goalOf1.contains(hole) && goalOf1.contains(vertex) && !goalOf1.contains(mover));
        assertTrue(TriangleRowsOracle.inHouse(TriangleRowsOracle.start(players, 0), vertex, 5),
                "(0, -4) is a start cell of player 0 and a goal cell of player 1");
        int[] owner = new int[Geometry.CELLS];
        Arrays.fill(owner, -1);
        owner[vertex] = 0;
        owner[mover] = 0;
        for (int c : goalOf1) { // the 10-cell corner is foreign to players 0 and 2, so player 1 holds it
            if (owner[c] < 0 && c != hole) {
                owner[c] = TriangleRowsOracle.inHouse(TriangleRowsOracle.goal(players, 1), c, 4) ? 1 : 2;
            }
        }
        for (JumpRule jump : JumpRule.values()) {
            RuleSet rules = RuleSet.defaults(players, TriangleRows.FIVE).withJumpRule(jump);
            Position pos = Position.empty(rules);
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (owner[c] >= 0) {
                    pos.put(owner[c], c);
                }
            }
            pos.setSideToMove(0);
            assertEquals(14, pos.goalOccupied(1));
            TreeSet<Integer> oracle = TriangleRowsOracle.moves(owner, players, 0, jump == JumpRule.LONG,
                    ForeignCamps.CLOSED, 5);
            TreeSet<Integer> unfiltered = TriangleRowsOracle.moves(owner, players, 0, jump == JumpRule.LONG,
                    ForeignCamps.CLOSED, 5, false);
            assertTrue(unfiltered.contains(Move.of(mover, hole)), "the step exists without the suicide rule");
            assertTrue(!oracle.contains(Move.of(mover, hole)), "the oracle forbids it");
            assertEquals(text(oracle), text(set(pos.legalMoves())), "legal moves, " + jump);
        }
    }

    private static String text(TreeSet<Integer> moves) {
        StringBuilder s = new StringBuilder();
        for (int m : moves) {
            s.append(Move.from(m)).append('-').append(Move.to(m)).append(' ');
        }
        return s.toString();
    }

    @Test
    void everyRowOfTheFileAgreesWithTheOracle() throws IOException {
        int rows = 0;
        int[] perCamps = new int[ForeignCamps.values().length];
        for (String line : committed()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals(17, f.length, line);
            int players = Integer.parseInt(f[1]);
            JumpRule jump = JumpRule.valueOf(f[2]);
            ForeignCamps camps = ForeignCamps.valueOf(f[3]);
            int[] owner = new int[Geometry.CELLS];
            Arrays.fill(owner, -1);
            int pieces = 0;
            for (int p = 0; p < players; p++) {
                for (String c : f[8 + p].split(",")) {
                    owner[Integer.parseInt(c)] = p;
                    pieces++;
                }
            }
            assertEquals(15 * players, pieces, "pieces of row " + f[0]);
            if (players == 2) {
                assertEquals("-", f[10]);
            }
            int side = Integer.parseInt(f[7]);
            int[] oracle = TriangleRowsOracle.moves(owner, players, side, jump == JumpRule.LONG, camps, 5).stream()
                    .mapToInt(Integer::intValue).toArray();
            assertEquals(Integer.parseInt(f[11]), oracle.length, "move count of row " + f[0]);
            assertEquals(f[12], LongJumpGolden.sha256(LongJumpGolden.sorted(oracle)), "sorted hash of row " + f[0]);
            if (f[16].startsWith("WIN/")) {
                int winner = Integer.parseInt(f[16].split("/")[1]);
                assertTrue(TriangleRowsOracle.wins(owner, players, winner, 5), "winner of row " + f[0]);
            } else {
                for (int q = 0; q < players; q++) {
                    assertTrue(!TriangleRowsOracle.wins(owner, players, q, 5), "row " + f[0] + " is won by " + q);
                }
            }
            perCamps[camps.ordinal()]++;
            rows++;
        }
        assertEquals(TriangleRowsGolden.ROWS, rows);
        for (ForeignCamps camps : ForeignCamps.values()) {
            assertEquals(TriangleRowsGolden.ROWS / 3, perCamps[camps.ordinal()], "rows with " + camps);
        }
    }
}
