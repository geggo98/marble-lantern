package sternhalma.tablebase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;

/**
 * Validation plan of docs/endgame-and-opening.md on the race table: forward brute force on a small region (item 1),
 * sampled forward checks on the real board (item 3), layer consistency (item 4), rank round trip (item 6), plus the
 * file format, the Git LFS pointer handling and the determinism of the builder.
 */
class RaceTableTest {

    /** Forward successors of a configuration inside a region, from the engine's move list on a one-player position. */
    static Set<Long> successors(Ranking ranking, int[] cells, LeaveGoal leaveGoal) {
        Position pos = Position.empty(RuleSet.defaults(2).withLeaveGoal(leaveGoal));
        for (int c : cells) {
            pos.put(0, c);
        }
        Set<Long> result = new HashSet<>();
        for (int m : pos.legalMoves()) {
            int[] next = cells.clone();
            for (int i = 0; i < next.length; i++) {
                if (next[i] == Move.from(m)) {
                    next[i] = Move.to(m);
                }
            }
            if (ranking.covers(next)) {
                result.add(ranking.rank(next));
            }
        }
        return result;
    }

    /** The forward successor graph of a whole small region, from the engine's move lists. */
    static int[][] adjacency(Ranking ranking, LeaveGoal leaveGoal) {
        int[][] adj = new int[(int) ranking.count()][];
        for (int rank = 0; rank < adj.length; rank++) {
            adj[rank] = successors(ranking, ranking.unrank(rank), leaveGoal).stream().mapToInt(Long::intValue)
                    .sorted().toArray();
        }
        return adj;
    }

    /** Exact forward distance to the goal by breadth-first search over the successor graph. */
    static int forwardDistance(int[][] adj, int start, int goalRank) {
        int[] dist = new int[adj.length];
        Arrays.fill(dist, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        dist[start] = 0;
        queue.add(start);
        while (!queue.isEmpty()) {
            int r = queue.poll();
            if (r == goalRank) {
                return dist[r];
            }
            for (int s : adj[r]) {
                if (dist[s] < 0) {
                    dist[s] = dist[r] + 1;
                    queue.add(s);
                }
            }
        }
        return -1;
    }

    /**
     * Validation item 1: on rows&gt;=4 (3003 configurations) every entry equals the forward brute-force distance, for
     * both leaveGoal rules.
     */
    @Test
    void theSmallRegionAgreesWithForwardBruteForceForBothLeaveGoalRules() {
        Ranking ranking = new Ranking(Rows.regionCells(4), 10);
        int goalRank = (int) ranking.rank(Geometry.cornerCells(Layout.of(2).goalCorner(0)));
        Map<LeaveGoal, byte[]> tables = new HashMap<>();
        for (LeaveGoal leaveGoal : LeaveGoal.values()) {
            byte[] table = RetrogradeBuilder.build(ranking, leaveGoal, 2);
            tables.put(leaveGoal, table);
            assertEquals(3003, table.length);
            int[][] adj = adjacency(ranking, leaveGoal);
            int unknown = 0;
            for (int rank = 0; rank < adj.length; rank++) {
                int entry = table[rank] & 0xFF;
                int forward = forwardDistance(adj, rank, goalRank);
                if (forward < 0) {
                    unknown++;
                    assertEquals(RetrogradeBuilder.UNKNOWN, entry, "rank " + rank + " cannot reach the goal");
                } else {
                    assertEquals(forward, entry, "rank " + rank + " " + Arrays.toString(ranking.unrank(rank)));
                }
            }
            assertEquals(0, unknown, "every rows>=4 configuration reaches the goal");
        }
        // on this small region leaving the goal never shortens a route; the header still records the rule
        assertArrayEquals(tables.get(LeaveGoal.FORBIDDEN), tables.get(LeaveGoal.ALLOWED));
    }

    /**
     * Validation item 4 on all 352,716 rows&gt;=3 configurations: from distance d &gt; 0 some move reaches d - 1 and
     * none reaches less. Together with distance 0 only at the goal this proves every entry exact: a chain of
     * d - 1 moves exists, and any path of L moves lowers the entry by at most one per move, so d &lt;= L.
     */
    @Test
    void theRowsThreeRegionIsLayerConsistentWhichProvesItExact() {
        Ranking ranking = new Ranking(Rows.regionCells(3), 10);
        byte[] table = RetrogradeBuilder.build(ranking, LeaveGoal.FORBIDDEN, 3);
        assertEquals(352_716, table.length);
        int goalRank = (int) ranking.rank(Geometry.cornerCells(Layout.of(2).goalCorner(0)));
        assertEquals(0, table[goalRank]);
        int max = 0;
        for (long rank = 0; rank < ranking.count(); rank++) {
            int d = table[(int) rank] & 0xFF;
            assertTrue(d != RetrogradeBuilder.UNKNOWN, "rank " + rank + " unreachable");
            max = Math.max(max, d);
            if (d == 0) {
                assertEquals(goalRank, rank);
                continue;
            }
            boolean oneCloser = false;
            for (long s : successors(ranking, ranking.unrank(rank), LeaveGoal.FORBIDDEN)) {
                int ds = table[(int) s] & 0xFF;
                assertTrue(ds >= d - 1, "a move from distance " + d + " reaches distance " + ds);
                oneCloser |= ds == d - 1;
            }
            assertTrue(oneCloser, "no move from rank " + rank + " reaches distance " + (d - 1));
        }
        assertTrue(max >= 10, "max distance " + max);
    }

    @Test
    void theBuilderIsDeterministicAcrossThreadCounts() {
        Ranking ranking = new Ranking(Rows.regionCells(3), 10);
        byte[] one = RetrogradeBuilder.build(ranking, LeaveGoal.FORBIDDEN, 1);
        byte[] four = RetrogradeBuilder.build(ranking, LeaveGoal.FORBIDDEN, 4);
        assertArrayEquals(one, four);
    }

    @Test
    void theSharedTableIsLayerConsistentOnSamplesAndHasTheRecordedChecksum() {
        RaceTable table = RaceTable.rows2();
        Ranking ranking = table.rankingOf();
        assertEquals(13_123_110L, ranking.count());
        assertEquals(RaceTable.SHA256, java.util.HexFormat.of().formatHex(TableFile.sha256(table.encode())));
        int[] goal = Geometry.cornerCells(Layout.of(2).goalCorner(0));
        assertEquals(0, table.count(goal));
        assertTrue(table.maxCount() >= 12 && table.maxCount() <= 20, "max count " + table.maxCount());
        Random random = new Random(13);
        int checked = 0;
        for (int i = 0; i < 20_000; i++) {
            long rank = (long) (random.nextDouble() * ranking.count());
            int[] cells = ranking.unrank(rank);
            int d = table.count(cells);
            assertTrue(d >= 0, "every rows>=2 configuration is covered");
            if (d == 0) {
                continue;
            }
            boolean oneCloser = false;
            for (long s : successors(ranking, cells, LeaveGoal.FORBIDDEN)) {
                int ds = table.entry(s);
                assertTrue(ds >= d - 1);
                oneCloser |= ds == d - 1;
            }
            assertTrue(oneCloser, "rank " + rank);
            checked++;
        }
        assertTrue(checked > 19_000);
        // forward brute force is too slow on 13M states; the first layers are checked by hand: 14 one-move states
        int ones = 0;
        for (long s : successors(ranking, goal, LeaveGoal.ALLOWED)) {
            // reverse moves out of the goal (ALLOWED lists them as forward moves): each is a distance-1 state
            ones++;
            assertEquals(1, table.entry(s));
        }
        assertEquals(14, ones);
        assertFalse(table.covers(new int[] {Geometry.id(0, 1), goal[1], goal[2], goal[3], goal[4], goal[5], goal[6],
            goal[7], goal[8], goal[9]}));
        assertEquals(-1, table.count(new int[9]));
    }

    @Test
    void theFileFormatRoundTripsAndDetectsCorruption(@TempDir Path dir) throws IOException {
        Ranking ranking = new Ranking(Rows.regionCells(4), 10);
        byte[] body = RetrogradeBuilder.build(ranking, LeaveGoal.FORBIDDEN, 1);
        byte[] encoded = TableFile.encode(LeaveGoal.FORBIDDEN, 4, ranking, body);
        Path file = dir.resolve("rows4.bin");
        TableFile.write(file, encoded);
        TableFile.Content content = TableFile.read(file);
        assertArrayEquals(body, content.body());
        assertEquals(4, content.header().minRow());
        assertEquals(LeaveGoal.FORBIDDEN, content.header().leaveGoal());
        assertArrayEquals(ranking.cells(), content.header().cells());
        assertEquals(3003, content.header().states());
        // a flipped byte in the body is caught by the checksum
        byte[] corrupt = encoded.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Path bad = dir.resolve("bad.bin");
        Files.write(bad, corrupt);
        assertTrue(assertThrowsIo(() -> TableFile.read(bad)).getMessage().contains("checksum"));
        // a deterministic encoding: the same bytes twice
        assertArrayEquals(encoded, TableFile.encode(LeaveGoal.FORBIDDEN, 4, ranking, body));
        assertFalse(TableFile.isLfsPointer(file));
    }

    private static IOException assertThrowsIo(ThrowingRunnable r) {
        try {
            r.run();
        } catch (IOException e) {
            return e;
        }
        throw new AssertionError("expected an IOException");
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    @Test
    void aGitLfsPointerIsRecognisedAndNotUsedAsATable(@TempDir Path dir) throws IOException {
        Path tables = dir.resolve("tablebase").resolve("tables");
        Files.createDirectories(tables);
        Path pointer = tables.resolve(RaceTable.FILE_NAME);
        Files.writeString(pointer, "version https://git-lfs.github.com/spec/v1\noid sha256:" + RaceTable.SHA256
                + "\nsize 13123286\n", StandardCharsets.UTF_8);
        assertTrue(TableFile.isLfsPointer(pointer));
        assertEquals(RaceTable.SHA256, TableFile.lfsPointerOid(pointer));
        assertNull(RaceTable.tryLoad(pointer), "a pointer must not load as a table");
        Files.writeString(dir.resolve("settings.gradle.kts"), "// fake root\n");
        assertNull(RaceTable.resolve(null, dir, false), "with only a pointer and no build allowed there is no table");
        // the real file next to it is accepted, a file with another checksum is not
        RaceTable shared = RaceTable.rows2();
        Path cache = dir.resolve("tablebase").resolve("build").resolve("tables");
        TableFile.write(cache.resolve(RaceTable.FILE_NAME), shared.encode());
        RaceTable fromCache = RaceTable.resolve(null, dir, false);
        assertNotNull(fromCache, "the pointer is skipped and the cached real file is used");
        assertEquals(shared.count(Geometry.cornerCells(Layout.of(2).goalCorner(0))),
                fromCache.count(Geometry.cornerCells(Layout.of(2).goalCorner(0))));
        byte[] stale = shared.encode();
        stale[stale.length - 1] ^= 1; // a valid-looking file with another content
        Path staleDir = dir.resolve("stale");
        TableFile.write(staleDir.resolve(RaceTable.FILE_NAME), stale);
        assertNull(RaceTable.tryLoad(staleDir.resolve(RaceTable.FILE_NAME)), "a file with another SHA-256 is ignored");
        assertNull(RaceTable.resolve(staleDir, dir.resolve("nowhere"), false));
    }

    @Test
    void theCommittedTableFileMatchesTheRecordedChecksumWhenPresent() throws IOException {
        Path root = RaceTable.repositoryRoot();
        assertNotNull(root, "the tests run inside the repository");
        Path file = root.resolve("tablebase").resolve("tables").resolve(RaceTable.FILE_NAME);
        assertTrue(Files.isRegularFile(file), "tablebase/tables/rows2.bin is committed (as an LFS object)");
        if (TableFile.isLfsPointer(file)) {
            assertEquals(RaceTable.SHA256, TableFile.lfsPointerOid(file),
                    "the committed LFS object must be the table the builder produces");
        } else {
            assertEquals(RaceTable.SHA256, TableFile.sha256Hex(file));
        }
    }
}
