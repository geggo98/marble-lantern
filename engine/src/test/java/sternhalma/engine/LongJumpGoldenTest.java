package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.JumpRule;

/**
 * Checks {@code long-jump-golden.tsv}, the file that the C++ port reproduces: the committed file equals a fresh run
 * of {@link LongJumpGolden}, and every row agrees with {@link LongJumpOracle}, which does not use the generator.
 */
class LongJumpGoldenTest {

    private static List<String> committed() throws IOException {
        try (InputStream in = LongJumpGoldenTest.class.getResourceAsStream("/long-jump-golden.tsv")) {
            assertTrue(in != null, "resource long-jump-golden.tsv is missing");
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }

    @Test
    void committedFileEqualsAFreshRun() throws IOException {
        List<String> fresh = LongJumpGolden.lines();
        List<String> file = committed();
        assertEquals("", file.get(file.size() - 1), "the file ends with a newline");
        assertEquals(fresh, file.subList(0, file.size() - 1));
    }

    @Test
    void everyRowAgreesWithTheOracle() throws IOException {
        int rows = 0;
        int differsFromAdjacent = 0;
        for (String line : committed()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals(11, f.length, line);
            assertEquals("2", f[1]);
            assertEquals("LONG", f[2]);
            Position pos = Position.empty(RuleSet.defaults(2).withJumpRule(JumpRule.LONG));
            for (int p = 0; p < 2; p++) {
                for (String c : f[6 + p].split(",")) {
                    pos.put(p, Integer.parseInt(c));
                }
            }
            pos.setSideToMove(Integer.parseInt(f[5]));
            // the oracle has no leaveGoal filter (default rules: FORBIDDEN): apply it here. The Definition-2 filter
            // rejects a move only when it fills a goal that holds 9 pieces; if that happened in a row, the count
            // comparison below would fail
            int goal = pos.layout().goalCorner(pos.sideToMove());
            int[] oracle = LongJumpOracle.moves(pos, true).stream()
                    .filter(m -> Geometry.cornerOf(Move.from(m)) != goal || Geometry.cornerOf(Move.to(m)) == goal)
                    .mapToInt(Integer::intValue).toArray();
            assertEquals(Integer.parseInt(f[8]), oracle.length, "move count of row " + f[0]);
            assertEquals(f[9], LongJumpGolden.sha256(LongJumpGolden.sorted(oracle)), "sorted hash of row " + f[0]);
            if (oracle.length != LongJumpOracle.moves(pos, false).stream()
                    .filter(m -> Geometry.cornerOf(Move.from(m)) != goal || Geometry.cornerOf(Move.to(m)) == goal)
                    .count()) {
                differsFromAdjacent++;
            }
            rows++;
        }
        assertEquals(LongJumpGolden.POSITIONS, rows);
        assertTrue(differsFromAdjacent >= 20, "rows where LONG has more moves than ADJACENT: " + differsFromAdjacent);
    }
}
