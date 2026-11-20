package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The committed {@code triangle-rows-search-golden.tsv}, which the C++ engine reproduces, equals a fresh run. */
class TriangleRowsSearchGoldenTest {

    @Test
    void committedFileEqualsAFreshRun() throws IOException {
        Path file = Path.of("src/test/resources/triangle-rows-search-golden.tsv");
        assertTrue(Files.exists(file), "missing " + file.toAbsolutePath() + "; write it with TriangleRowsSearchGolden");
        List<String> committed = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> fresh = TriangleRowsSearchGolden.lines();
        assertEquals(fresh.size(), committed.size(), "line count");
        for (int i = 0; i < fresh.size(); i++) {
            assertEquals(fresh.get(i), committed.get(i), "line " + (i + 1));
        }
        long searched = fresh.stream().filter(l -> !l.startsWith("#") && !l.endsWith("\t-")).count();
        assertTrue(searched >= 40, "searched rows: " + searched);
    }
}
