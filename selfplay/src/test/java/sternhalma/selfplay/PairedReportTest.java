package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.selfplay.ThreeSeatMatch.OpeningLine;
import sternhalma.selfplay.ThreeSeatMatch.Result;

/** The paired z-score of two result files over the same openings. */
class PairedReportTest {

    @TempDir
    Path dir;

    /** An opening whose candidate won {@code wins} of the three games (seats from 0 on), the rest lost. */
    private static OpeningLine opening(int index, long hash, int wins) {
        Result[] r = new Result[3];
        for (int seat = 0; seat < 3; seat++) {
            r[seat] = seat < wins ? Result.WIN : Result.LOSS;
        }
        return new OpeningLine(index, hash, r, new int[] {100, 100, 100}, new boolean[3], 0, 0, 0);
    }

    private Path file(String name, long hashSalt, int... wins) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < wins.length; i++) {
            lines.add(RunMonitor.json(opening(i, 1000 + i + hashSalt, wins[i]).fields()));
        }
        Path f = dir.resolve(name);
        Files.write(f, lines);
        return f;
    }

    @Test
    void theZScoreIsTheHandCalculatedOne() throws IOException {
        // shares A = 1, 2/3, 1, 1/3 and B = 1/3, 2/3, 1/3, 1/3: d = 2/3, 0, 2/3, 0; mean 1/3; the squared deviations
        // sum to 4/9, so sd = sqrt(4/27) = 0.3849, se = sd / 2 = 0.19245 and z = (1/3) / 0.19245 = 1.7321 (= sqrt 3)
        PairedReport.Report r = PairedReport.compare(file("a", 0, 3, 2, 3, 1), file("b", 0, 1, 2, 1, 1));
        assertEquals(4, r.units());
        assertEquals(1.0 / 3, r.meanDifference(), 1e-12);
        assertEquals(Math.sqrt(4.0 / 27) / 2, r.standardError(), 1e-12);
        assertEquals(Math.sqrt(3), r.z(), 1e-9);
        assertEquals(0.0833, r.pValue(), 1e-3);
        assertFalse(r.significant(), "1.73 is below 1.96");
        assertEquals((1 + 2.0 / 3 + 1 + 1.0 / 3) / 4, r.meanA(), 1e-12);
        assertEquals((1.0 / 3 + 2.0 / 3 + 1.0 / 3 + 1.0 / 3) / 4, r.meanB(), 1e-12);
    }

    @Test
    void aLargerSampleOfTheSameDifferenceIsSignificantAndTheSignFollowsTheOrder() throws IOException {
        // d alternates 2/3, 0 over 16 units: mean 1/3, sd = sqrt(16 * (1/9) / 15), se = sd / 4, z = 2 sqrt(3) * sqrt(15 / 16) / ...
        int[] a = new int[16];
        int[] b = new int[16];
        for (int i = 0; i < 16; i++) {
            a[i] = i % 2 == 0 ? 3 : 2;
            b[i] = i % 2 == 0 ? 1 : 2;
        }
        double sd = Math.sqrt(16 * (1.0 / 9) / 15);
        PairedReport.Report ab = PairedReport.compare(file("a", 0, a), file("b", 0, b));
        PairedReport.Report ba = PairedReport.compare(file("b", 0, b), file("a", 0, a));
        assertEquals((1.0 / 3) / (sd / 4), ab.z(), 1e-9);
        assertTrue(ab.significant());
        assertEquals(-ab.z(), ba.z(), 1e-12);
        assertTrue(ab.text().contains("A is better"), ab.text());
        assertTrue(ba.text().contains("B is better"), ba.text());
    }

    @Test
    void noDifferenceAndConstantDifferenceHaveDefinedZ() throws IOException {
        assertEquals(0.0, PairedReport.compare(file("a", 0, 2, 1, 3), file("b", 0, 2, 1, 3)).z());
        PairedReport.Report constant = PairedReport.compare(file("c", 0, 3, 2, 3), file("d", 0, 2, 1, 2));
        assertEquals(0.0, constant.standardError(), 1e-15);
        assertEquals(Double.POSITIVE_INFINITY, constant.z());
        assertEquals(0.0, constant.pValue());
    }

    @Test
    void thePValueOfOnePointNineSixIsFivePercent() {
        assertEquals(0.05, PairedReport.twoSidedP(1.96), 1e-4);
        assertEquals(1.0, PairedReport.twoSidedP(0.0), 1e-7);
        assertEquals(0.3173, PairedReport.twoSidedP(-1.0), 1e-4);
    }

    @Test
    void unitsOfOneFileOnlyAreCountedAndOpeningsThatDifferAreRefused() throws IOException {
        PairedReport.Report r = PairedReport.compare(file("a", 0, 3, 2, 3, 1, 1), file("b", 0, 1, 2, 1));
        assertEquals(3, r.units());
        assertEquals(2, r.onlyInA());
        assertEquals(0, r.onlyInB());
        // another seed or rule gives other opening positions for the same index
        Path c = file("c", 0, 3, 2, 3);
        Path d = file("d", 7, 1, 2, 1);
        assertThrows(IllegalArgumentException.class, () -> PairedReport.compare(c, d));
        Path e = file("e", 0, 3);
        Path f = file("f", 0, 1);
        assertThrows(IllegalArgumentException.class, () -> PairedReport.compare(e, f), "one common unit has no spread");
    }

    @Test
    void pairFilesOfTheMatchToolAreComparedByPairScoreAndKindsAreNotMixed() throws IOException {
        Path a = pairs("pa", 1.0, 0.5, 1.0, 0.0);
        Path b = pairs("pb", 0.5, 0.5, 0.5, 0.0);
        PairedReport.Report r = PairedReport.compare(a, b);
        assertEquals(4, r.units());
        assertEquals(0.25, r.meanDifference(), 1e-12);
        assertEquals(0.25, r.z() * r.standardError(), 1e-12);
        Path three = file("c", 0, 1, 2, 3, 1);
        assertThrows(IllegalArgumentException.class, () -> PairedReport.compare(a, three));
    }

    private Path pairs(String name, double... scores) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            lines.add(RunMonitor.json(new MctsMatch.PairLine(i, scores[i], 1, 0, 1, 80, 0, 0, 0, 0, 0, 0, 0).fields()));
        }
        Path f = dir.resolve(name);
        Files.write(f, lines);
        return f;
    }

    @Test
    void theCommandLinePrintsTheReportAndWritesTheJson() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Path json = dir.resolve("report.json");
        int code = PairedReport.run(new String[] {file("a", 0, 3, 2, 3, 1).toString(), file("b", 0, 1, 2, 1, 1).toString(),
                "--json", json.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("z = +1.73"), out.toString());
        assertTrue(Files.readString(json).contains("\"units\":4"), Files.readString(json));
        assertEquals(2, PairedReport.run(new String[] {"only-one"}, System.out, new PrintStream(err, true)));
    }
}
