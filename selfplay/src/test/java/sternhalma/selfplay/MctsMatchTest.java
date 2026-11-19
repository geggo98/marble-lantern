package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.search.Elo;

/** The match tool: paired games, one JSON line per finished pair, resumable from those lines, one run at a time. */
class MctsMatchTest {

    @TempDir
    static Path shared;
    static Path net;

    @BeforeAll
    static void network() throws IOException {
        net = shared.resolve("net.safetensors");
        Parameters.init(NetworkConfig.tiny(2), 3).save(net);
    }

    record Run(int code, String out, String err) {
    }

    private static Run match(Path dir, String... more) {
        List<String> args = new ArrayList<>(List.of("--out", dir.toString(), "--net", net.toString(), "--ladder-spec",
                "ab:eval=distance", "--threads", "2", "--progress-seconds", "1"));
        args.addAll(List.of(more));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MctsMatch.run(args.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static List<Path> pairFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().startsWith("pairs-")).sorted().toList();
        }
    }

    @Test
    void playsPairsWritesOneLinePerPairAndSkipsWhatIsDone() throws Exception {
        Path dir = shared.resolve("a");
        String[] args = {"--subject", "mcts:sims=3,batch=2,race=0", "--vs", "ab:1", "--vs", "raw", "--pairs", "3",
                "--seed", "5"};
        Run first = match(dir, args);
        assertEquals(0, first.code, first.err);
        assertTrue(first.err.contains("6 ok, 0 skip, 0 error, 0 left - nothing more to do"), first.err);
        assertEquals(2, first.out.lines().count(), first.out);
        List<Path> files = pairFiles(dir);
        assertEquals(2, files.size());
        for (Path f : files) {
            Map<Integer, MctsMatch.PairLine> lines = MctsMatch.read(f);
            assertEquals(List.of(0, 1, 2), List.copyOf(lines.keySet()));
            for (MctsMatch.PairLine l : lines.values()) {
                assertEquals(2, l.wins() + l.draws() + l.losses());
                assertTrue(l.subjectSims() > 0 && l.subjectSims() <= 3 * l.subjectMoves(),
                        "at most 3 simulations per move, none for a single legal move; no race module");
            }
            MctsMatch.Summary s = MctsMatch.summarize("s", "o", 3, 5, MctsMatch.read(f));
            assertEquals(Elo.fromScore(s.score()), s.elo(), 1e-12);
        }
        byte[] before = Files.readAllBytes(files.get(0));
        Run second = match(dir, args);
        assertEquals(0, second.code, second.err);
        assertTrue(second.err.contains("0 ok, 6 skip, 0 error, 0 left - nothing more to do"), second.err);
        assertEquals(first.out, second.out, "the same summary from the files alone");
        assertEquals(before.length, Files.readAllBytes(files.get(0)).length);
    }

    @Test
    void aRerunPlaysOnlyTheMissingPairsAndIgnoresATornLine() throws Exception {
        Path dir = shared.resolve("b");
        String[] args = {"--subject", "mcts:sims=3,batch=2,race=0", "--vs", "ab:1", "--pairs", "4", "--seed", "6"};
        Run first = match(dir, args);
        assertEquals(0, first.code, first.err);
        Path file = pairFiles(dir).get(0);
        List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size());
        // the process died while writing the third line, after the fourth had been written by another thread
        List<String> damaged = new ArrayList<>(List.of(lines.get(0), lines.get(1), lines.get(3), lines.get(2).substring(0, 20)));
        Files.write(file, damaged);
        Run second = match(dir, args);
        assertEquals(0, second.code, second.err);
        assertTrue(second.err.contains("1 ok, 3 skip, 0 error, 0 left"), second.err);
        assertEquals(first.out.split(";")[0], second.out.split(";")[0], "the redone pair is the same pair (only the CPU times differ)");
        assertEquals(4, MctsMatch.read(file).size());
    }

    @Test
    void aSecondRunInTheSameDirectoryExitsWith75() throws Exception {
        Path dir = Files.createDirectories(shared.resolve("c"));
        try (FileChannel ch = FileChannel.open(dir.resolve(".match.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = ch.tryLock()) {
            assertTrue(lock != null);
            Run r = match(dir, "--subject", "mcts:sims=3,race=0", "--vs", "ab:1", "--pairs", "2");
            assertEquals(75, r.code, r.err);
            assertTrue(pairFiles(dir).isEmpty());
        }
    }

    @Test
    void usageErrorsExitWith2() {
        assertEquals(2, MctsMatch.run(new String[] {"--out", shared.resolve("d").toString()}, System.out, System.err));
        assertEquals(2, match(shared.resolve("e"), "--subject", "ab:nodes=3", "--vs", "ab:1").code);
        assertEquals(2, match(shared.resolve("f"), "--subject", "mcts:sims=3,bogus=1", "--vs", "ab:1").code);
        assertEquals(2, match(shared.resolve("g"), "--subject", "mcts:sims=3", "--vs", "ab:5").code, "a rung between 2 and 99");
        assertEquals(2, match(shared.resolve("h"), "--subject", "mcts:sims=3", "--vs", "ab:1", "--pairs", "1").code);
    }

    @Test
    void jumpLongPlaysTheLongHopWithAClassic1vNetAndTheLongLadderByDefault() throws Exception {
        Path variantNet = shared.resolve("variant.safetensors");
        Parameters.init(NetworkConfig.tiny(3).withPlanes(sternhalma.nn.PlaneSet.CLASSIC1V), 5).save(variantNet);
        Path dir = shared.resolve("long");
        // no --ladder-spec given for the long rule: the default is BROWSER_LONG_SPEC (match() passes one, so call directly)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MctsMatch.run(new String[] {"--out", dir.toString(), "--net", variantNet.toString(), "--subject",
                "mcts:sims=3,batch=2,race=0", "--vs", "ab:1", "--vs", "raw", "--pairs", "3", "--seed", "8", "--threads", "2",
                "--jump", "long", "--progress-seconds", "1"}, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("6 ok, 0 skip, 0 error, 0 left"), err.toString());
        for (Path f : pairFiles(dir)) {
            Map<Integer, MctsMatch.PairLine> lines = MctsMatch.read(f);
            assertEquals(3, lines.size());
            for (MctsMatch.PairLine l : lines.values()) {
                assertEquals(2, l.wins() + l.draws() + l.losses());
            }
        }
        // the adjacent run of the same arguments is another file (the hash holds the rule), so a rerun cannot mix them
        Path adjacent = shared.resolve("long-vs-adjacent");
        Run a = match(adjacent, "--subject", "mcts:sims=3,batch=2,race=0", "--vs", "ab:1", "--pairs", "3", "--seed", "8");
        assertEquals(0, a.code, a.err);
        List<String> longNames = pairFiles(dir).stream().map(f -> f.getFileName().toString()).toList();
        assertTrue(!longNames.contains(pairFiles(adjacent).get(0).getFileName().toString()), "the rule is part of the file name");
    }

    @Test
    void aLadderSpecOfTheOtherJumpRuleIsAUsageError() {
        assertEquals(2, match(shared.resolve("j1"), "--subject", "mcts:sims=3,race=0", "--vs", "ab:1", "--jump", "long").code,
                "the ab:eval=distance spec plays adjacent hops");
        assertEquals(2, match(shared.resolve("j2"), "--subject", "mcts:sims=3,race=0", "--vs", "ab:1", "--ladder-spec",
                "ab:eval=distance,jump=long").code, "a long spec in adjacent games");
        assertEquals(2, match(shared.resolve("j3"), "--subject", "mcts:sims=3,race=0", "--vs", "ab:1", "--jump", "diagonal").code);
    }
}
