package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.tablebase.LookupObserver;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.RowOneBlocks;

/** The rank logger, its file format and its report (see {@link RankProbe}). */
class RankProbeTest {

    private static final String FAST_SPEC = "ab:eval=racelin1-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,"
            + "partial=1,fut=1,futk=20,fut2=1,race=1,racerows=1,nodes=300,tt=14";

    private record Result(int exit, String out, String err) {
    }

    private static Result probe(String... args) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int exit = RankProbe.run(args, new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Result(exit, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static void needRows1() {
        assumeTrue(RaceTable.rows1IfCached() != null, "the rows>=1 table is not available (-Psternhalma.tablebase.dir)");
    }

    private static ProbeLog.GameRows rows(int game, int plies, ProbeLog.Row... rows) {
        ByteBuffer b = ByteBuffer.allocate(rows.length * ProbeLog.RECORD_BYTES);
        long lookups = 0;
        for (ProbeLog.Row r : rows) {
            ProbeLog.put(b, r);
            lookups += r.count();
        }
        return new ProbeLog.GameRows(game, plies, rows.length, lookups, b.array());
    }

    private static Map<String, String> settings(String source) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("settings", "source=" + source + ";synthetic");
        return m;
    }

    private static ProbeLog.Row row(int game, int ply, int role, int k, int block, int count) {
        return new ProbeLog.Row(game, ply, role, k, block << 16 | 5, 10, 11, 12, count);
    }

    @Test
    void aShardRoundTripsAndATruncatedOrEditedOneIsRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.rlog.gz");
        ProbeLog.write(file, settings("best"), 0, 0, List.of(rows(0, 90, row(0, 40, 0, 1, 7, 3), row(0, 41, 1, 0, 8, 1)),
                rows(1, 80)));
        ProbeLog.Shard s = ProbeLog.read(file);
        assertEquals("2", s.manifest().get("games"));
        assertEquals("2", s.manifest().get("rows"));
        assertEquals("4", s.manifest().get("lookups"));
        assertEquals("170", s.manifest().get("plies"));
        List<ProbeLog.Row> back = new ArrayList<>();
        s.forEach(back::add);
        assertEquals(List.of(row(0, 40, 0, 1, 7, 3), row(0, 41, 1, 0, 8, 1)), back);
        assertEquals(7, back.get(0).block());
        byte[] bytes = Files.readAllBytes(file);
        Path cut = dir.resolve("cut.rlog.gz");
        Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length - 12));
        assertThrows(IOException.class, () -> ProbeLog.read(cut));
        Path junk = dir.resolve("junk.rlog.gz");
        Files.write(junk, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> ProbeLog.read(junk));
    }

    @Test
    void aRandomGameRunLeavesValidShardsAndAResumeDoesNothing(@TempDir Path dir) throws Exception {
        needRows1();
        Result first = probe("run", "--out", dir.toString(), "--source", "random", "--games", "4", "--seed", "5",
                "--shard", "2", "--opening-min", "4", "--opening-max", "6");
        assertEquals(0, first.exit(), first.err());
        List<Path> shards;
        try (Stream<Path> s = Files.list(dir)) {
            shards = s.filter(p -> p.getFileName().toString().endsWith(".rlog.gz")).sorted().toList();
        }
        assertEquals(2, shards.size());
        long plies = 0;
        for (Path p : shards) {
            ProbeLog.Shard s = ProbeLog.read(p);
            assertEquals("random", s.manifest().get("source"));
            assertEquals("2", s.manifest().get("games"));
            plies += Long.parseLong(s.manifest().get("plies"));
        }
        assertTrue(plies > 4 * 10, "the games played at least their openings: " + plies);
        assertTrue(first.err().contains("2 ok, 0 skip, 0 error"), first.err());
        Result again = probe("run", "--out", dir.toString(), "--source", "random", "--games", "4", "--seed", "5",
                "--shard", "2", "--opening-min", "4", "--opening-max", "6");
        assertEquals(0, again.exit());
        assertTrue(again.err().contains("0 ok, 2 skip, 0 error"), again.err());
        // other settings in the same directory stop the run instead of mixing two experiments
        Result other = probe("run", "--out", dir.toString(), "--source", "random", "--games", "4", "--seed", "5",
                "--shard", "2", "--opening-min", "4", "--opening-max", "7");
        assertEquals(1, other.exit());
        assertTrue(other.err().contains("written with settings"), other.err());
        // a damaged shard is moved aside and redone
        Files.write(shards.get(0), new byte[] {0, 1, 2});
        Result repair = probe("run", "--out", dir.toString(), "--source", "random", "--games", "4", "--seed", "5",
                "--shard", "2", "--opening-min", "4", "--opening-max", "6");
        assertEquals(0, repair.exit(), repair.err());
        assertTrue(repair.err().contains("1 ok, 1 skip, 0 error, 1 cleanup"), repair.err());
        assertTrue(Files.exists(Path.of(shards.get(0) + ".corrupt")));
        ProbeLog.read(shards.get(0));
    }

    @Test
    void aBestSelfPlayGameLogsTheRealReadsOfBothRoles(@TempDir Path dir) throws Exception {
        needRows1();
        Result run = probe("run", "--out", dir.toString(), "--source", "best", "--spec", FAST_SPEC, "--games", "2",
                "--seed", "7", "--shard", "2");
        assertEquals(0, run.exit(), run.err());
        Path shard;
        try (Stream<Path> s = Files.list(dir)) {
            shard = s.filter(p -> p.getFileName().toString().endsWith(".rlog.gz")).findFirst().orElseThrow();
        }
        ProbeLog.Shard s = ProbeLog.read(shard);
        long[] perRole = new long[2];
        int[] last = {-1};
        boolean[] sorted = {true};
        s.forEach(r -> {
            perRole[r.role()] += r.count();
            assertTrue(r.k() >= 0 && r.k() <= 8);
            assertTrue(r.block() >= 0 && r.block() < RowOneBlocks.rows1BlockCount());
            assertTrue(r.rows1() >= 0 && r.rows1() <= 255);
            // a configuration with a stone in row 1 is not covered by the rows>=2 table
            if (r.k() > 0) {
                assertEquals(255, r.rows2());
            }
            int key = r.game() * 1_000_000 + r.ply() * 2 + r.role();
            sorted[0] &= key >= last[0];
            last[0] = key;
        });
        assertTrue(perRole[0] > 0, "the leaf evaluator read the table: " + perRole[0]);
        assertTrue(perRole[1] > 0, "race play read the table: " + perRole[1]);
        assertTrue(sorted[0], "the records of one position must be adjacent");
        assertEquals(Long.parseLong(s.manifest().get("lookups")), perRole[0] + perRole[1]);
    }

    @Test
    void theReportCountsWhatItSawAndNeverSaysNoHitsForEmptyInput(@TempDir Path dir) throws Exception {
        ProbeLog.write(dir.resolve("e.rlog.gz"), settings("empty"), 0, 0, List.of(rows(0, 50)));
        Result r = probe("report", dir.toString());
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("1 shards, 1 games, 50 plies, 0 records, 0 lookups"), r.out());
        assertTrue(r.out().contains("NO LOOKUPS"), r.out());
        assertTrue(r.out().contains("hold-out matrix: NO LOOKUPS in any source"), r.out());
        Path none = Files.createDirectory(dir.resolve("none"));
        Result missing = probe("report", none.toString());
        assertEquals(1, missing.exit());
        assertTrue(missing.out().contains("NO INPUT FILES"), missing.out());
    }

    @Test
    void theReportCurveHoldOutAndKHistogramAreRightOnAKnownLog(@TempDir Path dir) throws Exception {
        // source "a": even games 0 and 2 read blocks 1 and 2 (9:1), odd games 1 and 3 read block 1 (8) and block 3 (2).
        // Phases: ply 10 is band 0, ply 120 is band 2 (width 50). k: 1 in role 0 (leaf), 3 in role 1 (raceplay).
        ProbeLog.write(dir.resolve("a-0.rlog.gz"), settings("a"), 0, 0, List.of(
                rows(0, 130, row(0, 10, 0, 1, 1, 9), row(0, 120, 1, 3, 2, 1)),
                rows(1, 130, row(1, 10, 0, 1, 1, 8), row(1, 120, 1, 3, 3, 2))));
        ProbeLog.write(dir.resolve("a-1.rlog.gz"), settings("a"), 1, 2, List.of(
                rows(2, 130, row(2, 10, 0, 1, 1, 9), row(2, 120, 1, 3, 2, 1)),
                rows(3, 130, row(3, 10, 0, 1, 1, 8), row(3, 120, 1, 3, 3, 2))));
        Path blocks = dir.resolve("top.txt");
        Result r = probe("report", dir.toString(), "--phase-width", "50", "--budgets", "0.03", "--emit-blocks",
                blocks.toString(), "--from", "a", "--top", "2");
        assertEquals(0, r.exit(), r.err());
        String out = r.out();
        assertTrue(out.contains("2 shards, 4 games, 520 plies, 8 records, 40 lookups"), out);
        // leaf 34 of 40 lookups (85%), raceplay 6 (15%); k=3 is all of raceplay: 15% of all, 0% of leaf lookups
        assertTrue(out.contains("role leaf: 34 lookups (85.0%)"), out);
        assertTrue(out.contains("leaf lookups with k>=3: 0.00%"), out);
        assertTrue(out.contains("k=3   15.00%"), out);
        // blocks 1, 2, 3 hold 34, 2, 4 lookups; 3 distinct of 3879
        assertTrue(out.contains("distinct blocks touched: 3 of 3879"), out);
        // 1 block serves 34 of 40 = 85%; 2 blocks 38 = 95% (so 90% and 95% need 2); 99% and 99.9% need all 3
        assertTrue(out.contains("1/2/2/3/3"), out);
        // hold-out: the set of the even games is the top 0.03% = 2 blocks of {1:18, 2:2}: {1, 2}; the odd games read blocks
        // 1 (16) and 3 (4): 16 of 20 lookups hit (80%); the odd-game positions are (1,10,leaf), (1,120,race), (3,10,..),
        // (3,120,..): the two leaf positions hit, the two race positions: (1,120) reads block 3 = miss, (3,120) block 3 = miss
        assertTrue(out.contains("a: top 0.03% (2 blocks) [2]"), out);
        assertTrue(out.contains("80.00% / 50.00%"), out);
        // the cover-99% set of the even games is {1, 2} too
        assertTrue(out.contains("a: cover 99% of even games [2]"), out);
        BitSet emitted = RowOneBlocks.read(blocks);
        assertEquals(2, emitted.cardinality());
        assertTrue(emitted.get(1) && emitted.get(3), "the two most frequent blocks of all games: " + emitted);
    }

    @Test
    void blockChoiceByFrequencyBreaksTiesByLowerIndex() {
        long[] counts = new long[10];
        counts[4] = 5;
        counts[2] = 5;
        counts[7] = 1;
        assertEquals(bits(2, 4), ProbeReport.topBlocks(counts, 2));
        assertEquals(bits(2, 4, 7), ProbeReport.topBlocks(counts, 5)); // blocks without a lookup never enter
        assertEquals(bits(2, 4), ProbeReport.coverSet(counts, 0.9));
        assertEquals(bits(2, 4, 7), ProbeReport.coverSet(counts, 1.0));
    }

    private static BitSet bits(int... idx) {
        BitSet b = new BitSet();
        for (int i : idx) {
            b.set(i);
        }
        return b;
    }

    @Test
    void theRecorderMergesRepeatedLookupsAndKeepsTheirCount() {
        RankRecorder rec = new RankRecorder();
        rec.start(3);
        int[] cells = new int[10];
        rec.ply(20);
        rec.lookup(LookupObserver.LEAF, cells, 1000, 5, 255, 7);
        rec.lookup(LookupObserver.LEAF, cells, 1000, 5, 255, 7);
        rec.lookup(LookupObserver.RACEPLAY, cells, 1000, 5, 255, 7);
        rec.ply(21);
        rec.lookup(LookupObserver.LEAF, cells, 1000, 5, 255, 7);
        ProbeLog.GameRows g = rec.finish(40);
        assertEquals(3, g.rows());
        assertEquals(4, g.lookups());
        List<ProbeLog.Row> out = new ArrayList<>();
        new ProbeLog.Shard(Map.of(), g.packed(), g.rows()).forEach(out::add);
        assertEquals(2, out.get(0).count());
        assertEquals(20, out.get(0).ply());
        assertEquals(LookupObserver.LEAF, out.get(0).role());
        assertEquals(1, out.get(1).count());
        assertEquals(LookupObserver.RACEPLAY, out.get(1).role());
        assertFalse(out.get(2).ply() == 20);
    }
}
