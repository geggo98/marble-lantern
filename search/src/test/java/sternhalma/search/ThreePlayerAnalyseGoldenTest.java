package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet.JumpRule;

/**
 * Checks {@code three-player-analyse-golden.tsv}, the file that the C++ port of the three-player analysis reproduces:
 * the committed file equals a fresh run of {@link ThreePlayerAnalyseGolden}, requested subsets of the moves in another
 * order give the same values as the full list, and the rows are meaningful.
 */
class ThreePlayerAnalyseGoldenTest {

    private static List<String> committed() throws IOException {
        try (InputStream in = ThreePlayerAnalyseGoldenTest.class.getResourceAsStream("/three-player-analyse-golden.tsv")) {
            assertTrue(in != null, "resource three-player-analyse-golden.tsv is missing");
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }

    @Test
    void committedFileEqualsAFreshRun() throws IOException {
        List<String> fresh = ThreePlayerAnalyseGolden.lines();
        List<String> file = committed();
        assertEquals("", file.get(file.size() - 1), "the file ends with a newline");
        assertEquals(fresh, file.subList(0, file.size() - 1));
    }

    @Test
    void subsetsInAnyOrderGiveTheValuesOfTheFullList() throws IOException {
        for (String line : committed()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            int id = Integer.parseInt(f[0]);
            if (id % 5 != 0) {
                continue;
            }
            Position pos = ThreePlayerChooseGolden.position(id);
            JumpRule jump = ThreePlayerChooseGolden.jumpOf(id);
            int[] moves = ThreePlayerChooseGolden.sorted(pos.legalMoves());
            String[] cells = f[7].split(",");
            assertEquals(moves.length, cells.length);
            List<Integer> picks = new ArrayList<>();
            for (int i = 1; i < moves.length; i += 3) {
                picks.add(i);
            }
            Collections.reverse(picks);
            ThreePlayerPlayer player = ThreePlayerSpec.parse(ThreePlayerAnalyseGolden.spec(ThreePlayerAnalyseGolden.BUDGETS[0], jump))
                    .create();
            for (int i : picks) {
                SearchResult r = player.analyseMove(pos, moves[i]);
                String[] c = cells[i].split(":");
                assertEquals(Integer.parseInt(c[1]), r.score(), "row " + id + " move " + cells[i]);
                assertEquals(Long.parseLong(c[3]), r.nodes());
            }
        }
    }

    @Test
    void rowsAreMeaningful() throws IOException {
        int rows = 0;
        boolean[] sides = new boolean[3];
        boolean[] rules = new boolean[2];
        int distinctScores = 0;
        for (String line : committed()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals(9, f.length, line);
            rows++;
            sides[Integer.parseInt(f[2])] = true;
            rules[f[1].equals("LONG") ? 1 : 0] = true;
            assertEquals(3, f[6].split(",").length);
            for (int b = 0; b < 2; b++) {
                String[] cells = f[7 + b].split(",");
                long budget = ThreePlayerAnalyseGolden.BUDGETS[b];
                java.util.Set<String> scores = new java.util.HashSet<>();
                for (String cell : cells) {
                    String[] c = cell.split(":");
                    long nodes = Long.parseLong(c[3]);
                    boolean winsAtOnce = Integer.parseInt(c[1]) == AlphaBeta.MATE - 1 && c[2].equals("0") && nodes == 1;
                    assertTrue(winsAtOnce || (Integer.parseInt(c[2]) >= 1 && nodes >= 1 && nodes < 2 * budget), cell);
                    scores.add(c[1]);
                }
                distinctScores += scores.size() > 1 ? 1 : 0;
            }
        }
        assertEquals(ThreePlayerChooseGolden.POSITIONS, rows);
        assertTrue(sides[0] && sides[1] && sides[2], "every side to move occurs");
        assertTrue(rules[0] && rules[1], "both jump rules occur");
        assertTrue(distinctScores >= 2 * rows - 4, "moves differ in score: " + distinctScores);
    }
}
