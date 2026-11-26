package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.search.Player;
import sternhalma.search.RandomPlayer;
import sternhalma.selfplay.TableMatch.TableLine;

/** The mixed table of {@code ThreeSeatMatch --table}: three players, six seatings per opening, the winner's identity kept. */
class TableMatchTest {

    @TempDir
    static Path shared;
    static Path netA;
    static Path netB;

    @BeforeAll
    static void networks() throws IOException {
        netA = shared.resolve("a.safetensors");
        netB = shared.resolve("b.safetensors");
        Parameters.init(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V), 3).save(netA);
        Parameters.init(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V), 4).save(netB);
    }

    record Run(int code, String out, String err) {
    }

    private static Run table(Path dir, String... args) {
        List<String> all = new ArrayList<>(List.of("--out", dir.toString(), "--progress-seconds", "1"));
        all.addAll(List.of(args));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ThreeSeatMatch.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Path only(Path dir, String prefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> l = s.filter(f -> f.getFileName().toString().startsWith(prefix)).toList();
            assertEquals(1, l.size(), prefix + " in " + dir);
            return l.get(0);
        }
    }

    /** A player that plays a random legal move and records the seats it was asked to move for. */
    private static final class Probe implements Player {
        final Set<Integer> seats = new HashSet<>();
        final RandomPlayer inner;
        final long seed;

        Probe(long seed) {
            this.seed = seed;
            inner = new RandomPlayer(seed);
        }

        @Override
        public int choose(Position position) {
            seats.add(position.sideToMove());
            return inner.choose(position);
        }

        @Override
        public String name() {
            return "probe";
        }
    }

    @Test
    void theTextSplitsAtTheCommasBetweenPlayersOnly() {
        assertEquals(List.of("random", "ab3:nodes=5,tt=12", "mcts:sims=3,batch=1,net=/x/y.safetensors"),
                TableMatch.split("random,ab3:nodes=5,tt=12,mcts:sims=3,batch=1,net=/x/y.safetensors"));
        assertEquals(List.of("mcts:sims=3,net=a", "mcts:sims=3,net=b", "ab3"), TableMatch.split("mcts:sims=3,net=a,mcts:sims=3,net=b,ab3"));
        assertThrows(IllegalArgumentException.class, () -> TableMatch.split("random,random"));
        assertThrows(IllegalArgumentException.class, () -> TableMatch.split("random,random,random,random"));
    }

    @Test
    void everyPlayerSitsInEverySeatTwiceAndSeedsAreDistinct() {
        RuleSet rules = RuleSet.defaults(3);
        Position start = StrengthEstimate.opening(rules, 1, 0, 6);
        List<List<Probe>> made = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        List<LongFunction<Player>> factories = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            int index = p;
            factories.add(seed -> {
                Probe probe = new Probe(seed);
                made.get(index).add(probe);
                return probe;
            });
        }
        TableLine line = TableMatch.playOpening(start, 0, factories, 1000);
        int[][] timesInSeat = new int[3][3];
        Set<Long> seeds = new HashSet<>();
        for (int p = 0; p < 3; p++) {
            assertEquals(6, made.get(p).size(), "player " + p + " plays all 6 games");
            for (Probe probe : made.get(p)) {
                assertEquals(1, probe.seats.size());
                timesInSeat[p][probe.seats.iterator().next()]++;
                assertTrue(seeds.add(probe.seed), "seed " + probe.seed + " is used once");
            }
        }
        for (int p = 0; p < 3; p++) {
            assertArrayEquals(new int[] {2, 2, 2}, timesInSeat[p], "player " + p);
        }
        // game k has three different players, one per seat
        for (int k = 0; k < 6; k++) {
            assertEquals(Set.of(0, 1, 2), Set.of(TableMatch.SEATINGS[k][0], TableMatch.SEATINGS[k][1], TableMatch.SEATINGS[k][2]));
        }
        Set<String> seatings = new HashSet<>();
        for (int[] s : TableMatch.SEATINGS) {
            seatings.add(java.util.Arrays.toString(s));
        }
        assertEquals(6, seatings.size(), "all 6 permutations");
        assertEquals(start.hash(), line.openingHash());
    }

    @Test
    void theWinnerIsRecordedByIdentityNotOnlyBySeat() {
        // measured: ab3 with 500 nodes beats two random players in every seat; so in the table {random, ab3, random} the
        // winner of each of the 6 games is player 1, whatever seat it has
        RuleSet rules = RuleSet.defaults(3);
        Position start = StrengthEstimate.opening(rules, 1, 1, 6);
        LongFunction<Player> strong = ThreeSeatMatch.factory("ab3:nodes=500,tt=14", null, JumpRule.ADJACENT);
        TableLine line = TableMatch.playOpening(start, 1, List.of(RandomPlayer::new, strong, RandomPlayer::new), 5);
        for (int k = 0; k < 6; k++) {
            assertEquals(1, line.winnerPlayer(k), "seating " + k);
            assertEquals(indexOf(TableMatch.SEATINGS[k], 1), line.winnerSeat()[k], "the seat of player 1 in seating " + k);
        }
        // the CPU is booked to the player, not to the seat: only the searching player has moves with CPU time
        assertTrue(line.cpuNanos()[1] > 5 * line.cpuNanos()[0] && line.cpuNanos()[1] > 5 * line.cpuNanos()[2],
                "cpu per player: " + java.util.Arrays.toString(line.cpuNanos()));
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] == v) {
                return i;
            }
        }
        throw new AssertionError();
    }

    @Test
    void identicalDeterministicPlayersGiveEveryPlayerExactlyOneThirdOfTheDecidedGames() throws Exception {
        // identical deterministic players: the game depends on the seat only, so each seat's result counts once for each player
        Path dir = shared.resolve("identical");
        Run r = table(dir, "--table", "ab3:nodes=60,tt=12,ab3:nodes=60,tt=12,ab3:nodes=60,tt=12", "--openings", "6", "--seed", "7",
                "--threads", "2");
        assertEquals(0, r.code, r.err);
        Map<String, String> result = RunMonitor.parse(Files.readString(only(dir, "table-result-")));
        int w0 = Integer.parseInt(result.get("p0_wins"));
        int w1 = Integer.parseInt(result.get("p1_wins"));
        int w2 = Integer.parseInt(result.get("p2_wins"));
        int draws = Integer.parseInt(result.get("draws"));
        assertEquals(36, w0 + w1 + w2 + draws);
        assertEquals(w0, w1);
        assertEquals(w1, w2);
        assertEquals((36 - draws) / 3, w0);
        assertTrue(w0 > 0, "some games are decided, or the check proves nothing");
        assertTrue(r.out.contains("table ab3:nodes=60,tt=12 | "), r.out);
    }

    @Test
    void aMixedNetworkTableIsTheSameWhateverTheThreadCountAndKeepsEachPlayersOwnNetwork() throws Exception {
        String spec = "mcts:sims=3,batch=1,race=0,net=" + netA + ",mcts:sims=3,batch=1,race=0,net=" + netB + ",random";
        String[] args = {"--table", spec, "--openings", "3", "--seed", "11", "--jump", "long"};
        Path d1 = shared.resolve("t1");
        Path d3 = shared.resolve("t3");
        List<String> a1 = new ArrayList<>(List.of(args));
        a1.addAll(List.of("--threads", "1"));
        List<String> a3 = new ArrayList<>(List.of(args));
        a3.addAll(List.of("--threads", "3"));
        Run one = table(d1, a1.toArray(String[]::new));
        Run three = table(d3, a3.toArray(String[]::new));
        assertEquals(0, one.code, one.err);
        assertEquals(0, three.code, three.err);
        assertEquals(one.out, three.out);
        assertEquals(Files.readString(only(d1, "table-result-")), Files.readString(only(d3, "table-result-")));
        Map<Integer, TableLine> x = TableMatch.read(only(d1, "tables-"));
        Map<Integer, TableLine> y = TableMatch.read(only(d3, "tables-"));
        assertEquals(3, x.size());
        for (int o = 0; o < 3; o++) {
            assertEquals(x.get(o).openingHash(), y.get(o).openingHash());
            assertArrayEquals(x.get(o).winnerSeat(), y.get(o).winnerSeat());
            assertArrayEquals(x.get(o).plies(), y.get(o).plies());
            assertArrayEquals(x.get(o).sims(), y.get(o).sims(), "searches are counted per player");
            assertTrue(x.get(o).sims()[0] > 0 && x.get(o).sims()[1] > 0, "both network players searched");
            assertEquals(0, x.get(o).sims()[2], "the random player has no simulations");
        }
        assertTrue(one.out.contains("jump=long"), one.out);
    }

    @Test
    void aRerunPlaysOnlyTheMissingOpeningsAndIgnoresATornLine() throws Exception {
        Path dir = shared.resolve("resume");
        String[] args = {"--table", "random,random,random", "--openings", "4", "--seed", "6", "--threads", "2"};
        Run first = table(dir, args);
        assertEquals(0, first.code, first.err);
        assertTrue(first.err.contains("4 ok, 0 skip, 0 error, 0 left - nothing more to do"), first.err);
        Path file = only(dir, "tables-");
        List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size());
        Files.write(file, List.of(lines.get(0), lines.get(1), lines.get(3), lines.get(2).substring(0, 40)));
        Run second = table(dir, args);
        assertEquals(0, second.code, second.err);
        assertTrue(second.err.contains("1 ok, 3 skip, 0 error, 0 left"), second.err);
        assertEquals(first.out, second.out, "the redone opening is the same opening");
        assertEquals(4, TableMatch.read(file).size());
        // a line whose winner seat is no seat, or whose seating is not the one of this format, is not a finished opening
        Files.write(file, List.of(lines.get(0).replace("\"w0\":", "\"w0\":7,\"x\":"), lines.get(1).replace("\"perm2\":\"102\"",
                "\"perm2\":\"012\"")));
        assertEquals(0, TableMatch.read(file).size());
    }

    @Test
    void theSummaryCountsPlayersSeatsAndDrawsFromKnownLines() {
        // 2 openings. winnerSeat per seating k=0..5 with SEATINGS 012,021,102,120,201,210
        Map<Integer, TableLine> lines = new TreeMap<>();
        // opening 0: seat 0 wins every game -> players 0,0,1,1,2,2
        lines.put(0, new TableLine(0, 1, new int[] {0, 0, 0, 0, 0, 0}, new int[] {10, 10, 10, 10, 10, 10},
                new boolean[6], new long[3], new long[3], new long[3]));
        // opening 1: one draw at the move limit, five games won in seat 2 -> players 2,1,2,0,1,0
        lines.put(1, new TableLine(1, 2, new int[] {2, 2, 2, 2, -1, 2}, new int[] {20, 20, 20, 20, 300, 20},
                new boolean[] {false, false, false, false, true, false}, new long[3], new long[3], new long[3]));
        TableMatch.Summary s = TableMatch.summarize(List.of("a", "b", "c"), JumpRule.ADJACENT, 2, 1, lines);
        // wins: opening 0 -> a2, b2, c2; opening 1 -> seating 0: seat2 = player 2 (c); 1: seat2 = player 1 (b);
        // 2: seat2 = player 2 (c); 3: seat2 = player 0 (a); 5: seat2 = player 0 (a)
        assertEquals(4, s.playerShare()[0].wins());
        assertEquals(3, s.playerShare()[1].wins());
        assertEquals(4, s.playerShare()[2].wins());
        assertEquals(1, s.draws());
        assertArrayEquals(new int[] {6, 0, 5}, s.seatWins());
        assertEquals(2, s.playerSeatShare()[0][0].wins());
        assertEquals(2, s.playerSeatShare()[0][2].wins());
        assertEquals(0, s.playerSeatShare()[0][1].wins());
        assertEquals(4 / 12.0, s.playerShare()[0].share(), 1e-12);
        assertEquals(1 / 12.0, s.moveLimitShare(), 1e-12);
        assertEquals((60 + 20 * 5 + 300) / 12.0, s.meanPlies(), 1e-12);
        assertEquals(12, s.games());
    }

    @Test
    void theLockUsageAndTornFormatErrorsAreReportedLikeTheOtherModes() throws Exception {
        Path dir = Files.createDirectories(shared.resolve("lock"));
        try (FileChannel ch = FileChannel.open(dir.resolve(".threeseat.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = ch.tryLock()) {
            assertTrue(lock != null);
            assertEquals(75, table(dir, "--table", "random,random,random", "--openings", "2").code);
        }
        assertEquals(2, table(shared.resolve("u1"), "--table", "random,random").code, "two players");
        assertEquals(2, table(shared.resolve("u2"), "--table", "random,random,random", "--vs", "random").code, "table and vs");
        assertEquals(2, table(shared.resolve("u3"), "--table", "random,random,random", "--candidate", "random").code);
        assertEquals(2, table(shared.resolve("u4"), "--table", "random,random,raw").code, "raw without a network");
        assertEquals(2, table(shared.resolve("u5"), "--table", "random,random,random", "--openings", "0").code);
    }
}
