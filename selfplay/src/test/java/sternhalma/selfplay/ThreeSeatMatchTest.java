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
import sternhalma.selfplay.ThreeSeatMatch.OpeningLine;
import sternhalma.selfplay.ThreeSeatMatch.Result;

/** The three-seat match: the candidate rotates through the seats of every opening; seeded, resumable, one run at a time. */
class ThreeSeatMatchTest {

    @TempDir
    static Path shared;
    static Path net;

    @BeforeAll
    static void network() throws IOException {
        net = shared.resolve("variant.safetensors");
        Parameters.init(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V), 3).save(net);
    }

    record Run(int code, String out, String err) {
    }

    private static Run match(Path dir, String... args) {
        List<String> all = new ArrayList<>(List.of("--out", dir.toString(), "--threads", "2", "--progress-seconds", "1"));
        all.addAll(List.of(args));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ThreeSeatMatch.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String[] withThreads(String threads, String[] args) {
        List<String> all = new ArrayList<>(List.of("--threads", threads));
        all.addAll(List.of(args));
        return all.toArray(String[]::new);
    }

    private static List<Path> files(Path dir, String prefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().startsWith(prefix)).sorted().toList();
        }
    }

    /** A player that plays a random legal move and records the seats it was asked to move for. */
    private static final class Probe implements Player {
        final Set<Integer> seats = new HashSet<>();
        final RandomPlayer inner;

        Probe(long seed) {
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
    void theCandidateTakesSeatZeroOneAndTwoInTurnAndTheOpponentsTheOtherTwo() {
        RuleSet rules = RuleSet.defaults(3);
        Position start = StrengthEstimate.opening(rules, 1, 0, 6);
        List<Probe> candidates = new ArrayList<>();
        List<Probe> opponents = new ArrayList<>();
        OpeningLine line = ThreeSeatMatch.playOpening(start, 0, seed -> {
            Probe p = new Probe(seed);
            candidates.add(p);
            return p;
        }, seed -> {
            Probe p = new Probe(seed);
            opponents.add(p);
            return p;
        }, 77);
        assertEquals(3, candidates.size(), "one candidate per game");
        assertEquals(6, opponents.size(), "two opponents per game");
        for (int rotation = 0; rotation < 3; rotation++) {
            assertEquals(Set.of(rotation), candidates.get(rotation).seats, "game " + rotation + ": the candidate sits in seat "
                    + rotation + " only");
            Set<Integer> others = new HashSet<>(Set.of(0, 1, 2));
            others.remove(rotation);
            Set<Integer> opponentSeats = new HashSet<>();
            opponentSeats.addAll(opponents.get(2 * rotation).seats);
            opponentSeats.addAll(opponents.get(2 * rotation + 1).seats);
            assertEquals(others, opponentSeats, "game " + rotation + ": the opponents sit in the other two seats");
            assertEquals(1, opponents.get(2 * rotation).seats.size());
            assertEquals(1, opponents.get(2 * rotation + 1).seats.size());
        }
        assertEquals(start.hash(), line.openingHash());
        for (int seat = 0; seat < 3; seat++) {
            assertTrue(line.plies()[seat] > start.ply(), "the game goes on from the opening");
        }
    }

    @Test
    void resultsAreCountedFromTheCandidatesSeat() {
        // measured: an ab3 player with 500 nodes beats two random players in every seat (12 of 12 games, seed 1), and a
        // random player never beats two of them
        RuleSet rules = RuleSet.defaults(3);
        Position start = StrengthEstimate.opening(rules, 1, 1, 6);
        LongFunction<Player> strong = ThreeSeatMatch.factory("ab3:nodes=500,tt=14", null, JumpRule.ADJACENT);
        OpeningLine wins = ThreeSeatMatch.playOpening(start, 1, strong, RandomPlayer::new, 5);
        assertArrayEquals(new Result[] {Result.WIN, Result.WIN, Result.WIN}, wins.results());
        assertEquals(1.0, wins.winShare());
        OpeningLine losses = ThreeSeatMatch.playOpening(start, 1, RandomPlayer::new, strong, 5);
        assertArrayEquals(new Result[] {Result.LOSS, Result.LOSS, Result.LOSS}, losses.results());
        assertEquals(0.0, losses.winShare());
    }

    @Test
    void theSummaryHasTheKnownIntervalsAndCountsDrawsAndMoveLimitGames() {
        // 10 openings: seat 0 wins 5, seat 1 wins 0, seat 2 wins 10; two draws at the move limit in seat 1
        Map<Integer, OpeningLine> lines = new java.util.TreeMap<>();
        for (int o = 0; o < 10; o++) {
            Result[] r = {o < 5 ? Result.WIN : Result.LOSS, o < 2 ? Result.DRAW : Result.LOSS, Result.WIN};
            boolean[] ml = {false, o < 2, false};
            lines.put(o, new OpeningLine(o, o, r, new int[] {100, 300, 200}, ml, 0, 0, 0));
        }
        ThreeSeatMatch.Summary s = ThreeSeatMatch.summarize("c", "o", JumpRule.ADJACENT, 10, 1, false, lines);
        assertEquals(5, s.seats()[0].wins());
        assertEquals(0.5, s.seats()[0].share());
        assertEquals(0.1871, s.seats()[0].lower(), 5e-5, "binom.test(5, 10)");
        assertEquals(0.8129, s.seats()[0].upper(), 5e-5);
        assertEquals(0.0, s.seats()[1].lower());
        assertEquals(0.3085, s.seats()[1].upper(), 5e-5, "0 of 10");
        assertEquals(0.6915, s.seats()[2].lower(), 5e-5, "10 of 10");
        assertEquals(1.0, s.seats()[2].upper());
        assertEquals(15, s.total().wins());
        assertEquals(30, s.total().games());
        assertEquals(0.5, s.total().share());
        assertEquals(2 / 30.0, s.drawShare(), 1e-12);
        assertEquals(2 / 30.0, s.moveLimitShare(), 1e-12);
        assertEquals(200.0, s.meanPlies(), 1e-12);
        assertArrayEquals(new int[] {5, 8, 0}, s.losses());
        assertArrayEquals(new int[] {0, 2, 0}, s.draws());
        assertEquals("compatible with chance", s.total().versusChance());
        assertEquals("below chance", s.seats()[1].versusChance());
        assertEquals("above chance", s.seats()[2].versusChance());
    }

    @Test
    void aTinyNetworkMatchIsDeterministicWhateverTheThreadsAndRotatesTheSeatsForBothJumpRules() throws Exception {
        for (String jump : List.of("adjacent", "long")) {
            String[] args = {"--candidate", "mcts:sims=3,batch=2,race=0", "--net", net.toString(), "--vs", "random", "--openings",
                    "3", "--seed", "11", "--opening-plies", "6", "--jump", jump};
            Run one = match(shared.resolve("d1-" + jump), args);
            assertEquals(0, one.code, one.err);
            assertTrue(one.err.contains("3 ok, 0 skip, 0 error, 0 left - nothing more to do"), one.err);
            // a later --threads replaces the default of 2: 1 thread and 3 threads against 2
            Run two = match(shared.resolve("d2-" + jump), withThreads("1", args));
            Run three = match(shared.resolve("d3-" + jump), withThreads("3", args));
            assertEquals(one.out, two.out, "same text with 1 or 2 threads");
            assertEquals(one.out, three.out);
            Path resultOne = files(shared.resolve("d1-" + jump), "result-").get(0);
            Path resultTwo = files(shared.resolve("d2-" + jump), "result-").get(0);
            assertEquals(Files.readString(resultOne), Files.readString(resultTwo), "the JSON result is the same");
            Map<Integer, OpeningLine> a = ThreeSeatMatch.read(files(shared.resolve("d1-" + jump), "openings-").get(0));
            Map<Integer, OpeningLine> b = ThreeSeatMatch.read(files(shared.resolve("d2-" + jump), "openings-").get(0));
            assertEquals(3, a.size());
            for (int o = 0; o < 3; o++) {
                assertEquals(a.get(o).openingHash(), b.get(o).openingHash());
                assertArrayEquals(a.get(o).results(), b.get(o).results());
                assertArrayEquals(a.get(o).plies(), b.get(o).plies());
                assertTrue(a.get(o).candidateSims() > 0, "the candidate searched (and so played in a seat)");
            }
            assertTrue(one.out.contains("jump=" + jump), one.out);
            assertTrue(one.out.contains("seat 0:") && one.out.contains("seat 1:") && one.out.contains("seat 2:"), one.out);
            assertTrue(one.out.contains("CP95"), one.out);
        }
    }

    @Test
    void theTwoJumpRulesPlayOtherOpeningsAndOtherFiles() throws Exception {
        String[] common = {"--candidate", "random", "--vs", "random", "--openings", "2", "--seed", "5"};
        Run adjacent = match(shared.resolve("r-adjacent"), common);
        List<String> longArgs = new ArrayList<>(List.of(common));
        longArgs.addAll(List.of("--jump", "long"));
        Run longHop = match(shared.resolve("r-long"), longArgs.toArray(String[]::new));
        assertEquals(0, adjacent.code, adjacent.err);
        assertEquals(0, longHop.code, longHop.err);
        assertTrue(adjacent.out.contains("jump=adjacent") && longHop.out.contains("jump=long"));
    }

    @Test
    void aRerunPlaysOnlyTheMissingOpeningsAndIgnoresATornLine() throws Exception {
        Path dir = shared.resolve("resume");
        String[] args = {"--candidate", "random", "--vs", "random", "--openings", "4", "--seed", "6"};
        Run first = match(dir, args);
        assertEquals(0, first.code, first.err);
        Path file = files(dir, "openings-").get(0);
        List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size());
        // the process died while writing the third line, after the fourth had been written by another thread
        Files.write(file, List.of(lines.get(0), lines.get(1), lines.get(3), lines.get(2).substring(0, 25)));
        Run second = match(dir, args);
        assertEquals(0, second.code, second.err);
        assertTrue(second.err.contains("1 ok, 3 skip, 0 error, 0 left"), second.err);
        assertEquals(first.out, second.out, "the redone opening is the same opening");
        assertEquals(4, ThreeSeatMatch.read(file).size());
        Run third = match(dir, args);
        assertTrue(third.err.contains("0 ok, 4 skip, 0 error, 0 left - nothing more to do"), third.err);
        assertEquals(first.out, third.out);
    }

    @Test
    void controlAb3PutsTheOpponentSpecInTheCandidateSeats() throws Exception {
        Run r = match(shared.resolve("control"), "--control", "ab3", "--vs", "ab3:nodes=100,tt=12", "--openings", "2", "--seed", "3");
        assertEquals(0, r.code, r.err);
        assertTrue(r.out.startsWith("control ab3:nodes=100,tt=12 vs 2 x ab3:nodes=100,tt=12"), r.out);
        assertEquals(0, match(shared.resolve("control-long"), "--control", "ab3", "--vs", "ab3:nodes=100,tt=12", "--openings", "2",
                "--jump", "long").code);
        assertEquals(2, match(shared.resolve("control-bad"), "--control", "ab3", "--vs", "random").code);
    }

    @Test
    void ab3SpecsGetTheJumpRuleOfTheGamesAndAnotherRuleIsRefused() {
        assertEquals("ab3:nodes=5,jump=long", ThreeSeatMatch.withJump("ab3:nodes=5", JumpRule.LONG));
        assertEquals("ab3:jump=long", ThreeSeatMatch.withJump("ab3", JumpRule.LONG));
        assertEquals("ab3:nodes=5,jump=long", ThreeSeatMatch.withJump("ab3:nodes=5,jump=long", JumpRule.LONG));
        assertEquals("ab3:nodes=5", ThreeSeatMatch.withJump("ab3:nodes=5", JumpRule.ADJACENT));
        ThreeSeatMatch.factory("ab3:nodes=5", null, JumpRule.LONG).apply(1);
        assertThrows(IllegalArgumentException.class, () -> ThreeSeatMatch.factory("ab3:nodes=5,jump=long", null, JumpRule.ADJACENT));
        assertThrows(IllegalArgumentException.class, () -> ThreeSeatMatch.factory("ab:nodes=5", null, JumpRule.ADJACENT));
        assertThrows(IllegalArgumentException.class, () -> ThreeSeatMatch.factory("raw", null, JumpRule.ADJACENT));
    }

    @Test
    void aSecondRunInTheSameDirectoryExitsWith75AndUsageErrorsExitWith2() throws Exception {
        Path dir = Files.createDirectories(shared.resolve("lock"));
        try (FileChannel ch = FileChannel.open(dir.resolve(".threeseat.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = ch.tryLock()) {
            assertTrue(lock != null);
            Run r = match(dir, "--candidate", "random", "--vs", "random", "--openings", "2");
            assertEquals(75, r.code, r.err);
            assertTrue(files(dir, "openings-").isEmpty());
        }
        assertEquals(2, match(shared.resolve("u1"), "--vs", "random").code, "no candidate");
        assertEquals(2, match(shared.resolve("u2"), "--candidate", "random", "--control", "ab3", "--vs", "ab3").code,
                "candidate and control");
        assertEquals(2, match(shared.resolve("u3"), "--candidate", "random", "--vs", "random", "--jump", "x").code);
        assertEquals(2, match(shared.resolve("u4"), "--candidate", "random", "--vs", "random", "--openings", "0").code);
    }
}
