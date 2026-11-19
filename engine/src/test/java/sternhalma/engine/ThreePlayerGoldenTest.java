package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Checks {@code three-player-golden.tsv}, the file that the C++ port reproduces: the committed file equals a fresh run
 * of {@link ThreePlayerGolden}, and the rows cover what the C++ test must reproduce (both jump rules, wins, running
 * games, every side to move).
 */
class ThreePlayerGoldenTest {

    private static List<String> committed() throws IOException {
        try (InputStream in = ThreePlayerGoldenTest.class.getResourceAsStream("/three-player-golden.tsv")) {
            assertTrue(in != null, "resource three-player-golden.tsv is missing");
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }

    @Test
    void committedFileEqualsAFreshRun() throws IOException {
        List<String> fresh = ThreePlayerGolden.lines();
        List<String> file = committed();
        assertEquals("", file.get(file.size() - 1), "the file ends with a newline");
        assertEquals(fresh, file.subList(0, file.size() - 1));
    }

    @Test
    void rowsCoverTheCases() throws IOException {
        int rows = 0;
        int wins = 0;
        int ongoing = 0;
        int moveLimits = 0;
        int longRows = 0;
        boolean[] sides = new boolean[3];
        Set<Integer> winners = new HashSet<>();
        for (String line : committed()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals(15, f.length, line);
            rows++;
            sides[Integer.parseInt(f[6])] = true;
            longRows += f[2].equals("LONG") ? 1 : 0;
            String outcome = f[14];
            if (outcome.startsWith("WIN/")) {
                wins++;
                winners.add(Integer.parseInt(outcome.split("/")[1]));
            }
            ongoing += outcome.equals("ONGOING") ? 1 : 0;
            moveLimits += outcome.startsWith("MOVE_LIMIT/") ? 1 : 0;
            Set<String> cells = new HashSet<>();
            for (int p = 0; p < 3; p++) {
                String[] list = f[7 + p].split(",");
                assertEquals(10, list.length, line);
                cells.addAll(List.of(list));
            }
            assertEquals(30, cells.size(), "a cell holds two pieces in row " + f[0]);
        }
        assertEquals(ThreePlayerGolden.POSITIONS, rows);
        assertEquals(ThreePlayerGolden.POSITIONS / 2, longRows);
        assertTrue(sides[0] && sides[1] && sides[2], "every side to move occurs");
        assertTrue(wins >= 2, "rows that end in a win: " + wins);
        assertTrue(ongoing >= 10, "rows of running games: " + ongoing);
        assertTrue(moveLimits >= 2, "rows that end at the move limit: " + moveLimits);
    }
}
