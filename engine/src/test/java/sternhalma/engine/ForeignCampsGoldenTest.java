package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.ForeignCamps;
import sternhalma.engine.RuleSet.JumpRule;

/**
 * Checks {@code foreign-camps-golden.tsv}, the file that the C++ port reproduces: the committed file equals a fresh
 * run of {@link ForeignCampsGolden}, and every row agrees with {@link ForeignCampsOracle}, which does not use the
 * generator or the engine's move generation.
 */
class ForeignCampsGoldenTest {

    private static List<String> committed() throws IOException {
        try (InputStream in = ForeignCampsGoldenTest.class.getResourceAsStream("/foreign-camps-golden.tsv")) {
            assertTrue(in != null, "resource foreign-camps-golden.tsv is missing");
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }

    @Test
    void committedFileEqualsAFreshRun() throws IOException {
        List<String> file = committed();
        assertEquals("", file.get(file.size() - 1), "the file ends with a newline");
        assertEquals(ForeignCampsGolden.lines(), file.subList(0, file.size() - 1));
    }

    @Test
    void everyRowAgreesWithTheOracle() throws IOException {
        int rows = 0;
        int openRowsWithAForeignPiece = 0;
        for (String line : committed()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals(13, f.length, line);
            int players = Integer.parseInt(f[1]);
            JumpRule jump = JumpRule.valueOf(f[2]);
            ForeignCamps camps = ForeignCamps.valueOf(f[3]);
            Position pos = Position.empty(RuleSet.defaults(players).withJumpRule(jump).withForeignCamps(camps));
            boolean foreignPiece = false;
            for (int p = 0; p < players; p++) {
                for (String c : f[7 + p].split(",")) {
                    int cell = Integer.parseInt(c);
                    pos.put(p, cell);
                    foreignPiece |= !ForeignCampsOracle.home(players, p, cell);
                }
            }
            if (players == 2) {
                assertEquals("-", f[9]);
            }
            pos.setSideToMove(Integer.parseInt(f[6]));
            // the oracle has no leaveGoal filter (default rules: FORBIDDEN): apply it here. A Definition-2 rejection
            // would show as a count difference below
            int goal = pos.layout().goalCorner(pos.sideToMove());
            int[] oracle = ForeignCampsOracle.moves(pos, jump == JumpRule.LONG, camps).stream()
                    .filter(m -> Geometry.cornerOf(Move.from(m)) != goal || Geometry.cornerOf(Move.to(m)) == goal)
                    .mapToInt(Integer::intValue).toArray();
            assertEquals(Integer.parseInt(f[10]), oracle.length, "move count of row " + f[0]);
            assertEquals(f[11], LongJumpGolden.sha256(LongJumpGolden.sorted(oracle)), "sorted hash of row " + f[0]);
            if (camps == ForeignCamps.OPEN && foreignPiece) {
                openRowsWithAForeignPiece++;
            }
            if (camps == ForeignCamps.PASS_THROUGH) {
                assertTrue(!foreignPiece, "a piece rests on a foreign cell under PASS_THROUGH, row " + f[0]);
            }
            rows++;
        }
        assertEquals(ForeignCampsGolden.ROWS, rows);
        assertTrue(openRowsWithAForeignPiece >= 8, "OPEN rows with a piece on a foreign cell: " + openRowsWithAForeignPiece);
    }
}
