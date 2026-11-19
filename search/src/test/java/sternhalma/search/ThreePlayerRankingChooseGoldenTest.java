package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Checks {@code three-player-ranking-choose-golden.tsv}, the file that the C++ port of the three-player search
 * reproduces for {@code ranking=FULL_RANKING}: the committed file equals a fresh run of
 * {@link ThreePlayerRankingChooseGolden}, and the rows cover the continued phase (a finished player, both rules,
 * searches that go deeper than one ply, the node budget respected).
 */
class ThreePlayerRankingChooseGoldenTest {

    private static List<String> committed() throws IOException {
        try (InputStream in = ThreePlayerRankingChooseGoldenTest.class
                .getResourceAsStream("/three-player-ranking-choose-golden.tsv")) {
            assertTrue(in != null, "resource three-player-ranking-choose-golden.tsv is missing");
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }

    @Test
    void committedFileEqualsAFreshRun() throws IOException {
        List<String> fresh = ThreePlayerRankingChooseGolden.lines();
        List<String> file = committed();
        assertEquals("", file.get(file.size() - 1), "the file ends with a newline");
        assertEquals(fresh, file.subList(0, file.size() - 1));
    }

    @Test
    void rowsAreInTheContinuedPhase() throws IOException {
        int rows = 0;
        int withFinished = 0;
        int longRows = 0;
        boolean[] sides = new boolean[3];
        for (String line : committed()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals(19, f.length, line);
            rows++;
            int side = Integer.parseInt(f[4]);
            int finished = Integer.parseInt(f[5]);
            sides[side] = true;
            assertEquals(0, finished >> side & 1, "the side to move has not finished, row " + f[0]);
            withFinished += finished != 0 ? 1 : 0;
            longRows += f[1].equals("LONG") ? 1 : 0;
            for (int b = 0; b < 2; b++) {
                int at = 9 + 5 * b;
                long budget = ThreePlayerRankingChooseGolden.BUDGETS[b];
                int depth = Integer.parseInt(f[at + 2]);
                long nodes = Long.parseLong(f[at + 3]);
                assertTrue(depth >= 1, "depth in row " + f[0]);
                assertTrue(nodes >= 1 && nodes < 2 * budget, "nodes " + nodes + " in row " + f[0]);
            }
        }
        assertEquals(ThreePlayerRankingChooseGolden.POSITIONS, rows);
        assertEquals(rows / 2, longRows);
        assertTrue(withFinished >= rows * 3 / 4, "rows with a finished player: " + withFinished);
        assertTrue(sides[1] || sides[2], "a continued-phase side to move occurs");
    }
}
