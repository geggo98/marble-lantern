package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Checks {@code three-player-ranking-analyse-golden.tsv}: the committed file equals a fresh run of
 * {@link ThreePlayerRankingAnalyseGolden}, and the rows show the continued phase: a seat that is out holds a rank
 * constant, a seat still in play holds an evaluation.
 */
class ThreePlayerRankingAnalyseGoldenTest {

    private static List<String> committed() throws IOException {
        try (InputStream in = ThreePlayerRankingAnalyseGoldenTest.class
                .getResourceAsStream("/three-player-ranking-analyse-golden.tsv")) {
            assertTrue(in != null, "resource three-player-ranking-analyse-golden.tsv is missing");
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }

    @Test
    void committedFileEqualsAFreshRun() throws IOException {
        List<String> fresh = ThreePlayerRankingAnalyseGolden.lines();
        List<String> file = committed();
        assertEquals("", file.get(file.size() - 1), "the file ends with a newline");
        assertEquals(fresh, file.subList(0, file.size() - 1));
    }

    @Test
    void seatsThatAreOutHoldRankConstantsAndTheOthersAnEvaluation() throws IOException {
        int rows = 0;
        int withFinished = 0;
        for (String line : committed()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals(10, f.length, line);
            rows++;
            int finished = Integer.parseInt(f[3]);
            String[] eval = f[7].split(",");
            assertEquals(3, eval.length);
            for (int p = 0; p < 3; p++) {
                int value = Integer.parseInt(eval[p]);
                boolean out = (finished >> p & 1) == 1;
                assertEquals(out, value == AlphaBeta.MATE || value == ThreePlayerSearch.SECOND, "row " + f[0] + " seat " + p);
            }
            withFinished += finished != 0 ? 1 : 0;
            for (int b = 0; b < 2; b++) {
                for (String cell : f[8 + b].split(",")) {
                    assertEquals(4, cell.split(":").length, cell);
                }
            }
        }
        assertEquals(ThreePlayerRankingChooseGolden.POSITIONS, rows);
        assertTrue(withFinished >= rows - 4, "rows in the continued phase: " + withFinished);
    }
}
