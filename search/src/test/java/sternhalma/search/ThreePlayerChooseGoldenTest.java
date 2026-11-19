package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Checks {@code three-player-choose-golden.tsv}, the file that the C++ port of the three-player search reproduces:
 * the committed file equals a fresh run of {@link ThreePlayerChooseGolden}, and the rows are meaningful (every side to
 * move, both rules, searches that go deeper than one ply, the node budget respected).
 */
class ThreePlayerChooseGoldenTest {

    private static List<String> committed() throws IOException {
        try (InputStream in = ThreePlayerChooseGoldenTest.class.getResourceAsStream("/three-player-choose-golden.tsv")) {
            assertTrue(in != null, "resource three-player-choose-golden.tsv is missing");
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }

    @Test
    void committedFileEqualsAFreshRun() throws IOException {
        List<String> fresh = ThreePlayerChooseGolden.lines();
        List<String> file = committed();
        assertEquals("", file.get(file.size() - 1), "the file ends with a newline");
        assertEquals(fresh, file.subList(0, file.size() - 1));
    }

    @Test
    void rowsAreMeaningful() throws IOException {
        int rows = 0;
        boolean[] sides = new boolean[3];
        int deeper = 0;
        int differentMoves = 0;
        for (String line : committed()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals(19, f.length, line);
            rows++;
            sides[Integer.parseInt(f[5])] = true;
            for (int b = 0; b < 2; b++) {
                int at = 9 + 5 * b;
                long budget = ThreePlayerChooseGolden.BUDGETS[b];
                int depth = Integer.parseInt(f[at + 2]);
                long nodes = Long.parseLong(f[at + 3]);
                assertTrue(depth >= 1, "depth in row " + f[0]);
                // a search stops at the budget; the last iteration may run over by one node call chain
                assertTrue(nodes >= 1 && nodes < 2 * budget, "nodes " + nodes + " in row " + f[0]);
                deeper += depth >= 3 ? 1 : 0;
            }
            differentMoves += f[9].equals(f[14]) ? 0 : 1;
        }
        assertEquals(ThreePlayerChooseGolden.POSITIONS, rows);
        assertTrue(sides[0] && sides[1] && sides[2], "every side to move occurs");
        assertTrue(deeper >= rows, "searches that reach depth 3: " + deeper);
        assertTrue(differentMoves >= 1, "rows where the larger budget changes the move: " + differentMoves);
    }
}
