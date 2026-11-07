package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.search.Strategies;

/** Resume, abort, cleanup, settings check, lock and status of the self-play job, in process. */
class JobTest {

    static final String SPEC = Strategies.BEST_SPEC + ",nodes=300,tt=12";
    static final int GAMES = 12;
    static final int SHARD = 3;

    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(log, true, StandardCharsets.UTF_8);

    private SelfPlay.GamesJob job(Path dir, String spec) {
        SelfPlay.GamesJob job = new SelfPlay.GamesJob(dir,
                SelfPlayGameTest.settings(spec, 0.5, true, Adjudication.Config.DEFAULT), GAMES, SHARD, 2, err);
        job.progressSeconds = 0;
        return job;
    }

    private static List<Path> shards(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".safetensors")).sorted().toList();
        }
    }

    private static List<GameRecord> games(Path dir) throws Exception {
        List<GameRecord> all = new ArrayList<>();
        for (Path f : shards(dir)) {
            all.addAll(GameShard.read(f).games());
        }
        return all;
    }

    @Test
    void abortedRunsResumeToTheSameDataAndASecondRunDoesNothing(@TempDir Path tmp) throws Exception {
        Path whole = tmp.resolve("whole");
        assertEquals(0, job(whole, SPEC).run());
        assertEquals(GAMES / SHARD, shards(whole).size());
        String text = log.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("4 ok, 0 skip, 0 error, 0 cleanup, 0 left"), text);
        assertTrue(text.contains("items/s") || text.contains("/s  ETA"), "progress lines: " + text);

        Path split = tmp.resolve("split");
        SelfPlay.GamesJob first = job(split, SPEC);
        first.stopAfter = 4;
        assertEquals(Job.EXIT_ABORTED, first.run());
        int partial = shards(split).size();
        assertTrue(partial >= 1 && partial < GAMES / SHARD, "aborted after a few shards: " + partial);
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("aborted"));
        // a leftover temporary file of a killed run is cleaned up
        Files.writeString(split.resolve("samples-s1006-00003.safetensors.tmp.999"), "half");
        log.reset();
        assertEquals(0, job(split, SPEC).run());
        text = log.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains(partial + " complete (skip)"), text);
        assertTrue(text.contains("1 cleanup"), text);
        List<GameRecord> a = games(whole);
        List<GameRecord> b = games(split);
        assertEquals(GAMES, b.size());
        for (int i = 0; i < GAMES; i++) {
            assertEquals(a.get(i).id(), b.get(i).id());
            assertArrayEquals(a.get(i).moves(), b.get(i).moves(), "game " + i);
            assertEquals(a.get(i).label(), b.get(i).label());
            assertArrayEquals(a.get(i).labelValues(), b.get(i).labelValues());
        }
        // nothing more to do: no shard is rewritten
        Path one = shards(split).get(0);
        FileTime before = Files.getLastModifiedTime(one);
        log.reset();
        assertEquals(0, job(split, SPEC).run());
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("0 ok, 4 skip"), log.toString(StandardCharsets.UTF_8));
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("nothing more to do"));
        assertEquals(before, Files.getLastModifiedTime(one));
    }

    @Test
    void corruptShardsAreRedoneAndForeignSettingsStopTheRun(@TempDir Path dir) throws Exception {
        assertEquals(0, job(dir, SPEC).run());
        Path victim = shards(dir).get(1);
        byte[] bytes = Files.readAllBytes(victim);
        Files.write(victim, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        log.reset();
        assertEquals(0, job(dir, SPEC).run());
        String text = log.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("1 ok, 3 skip") && text.contains("1 cleanup"), text);
        assertTrue(Files.exists(victim.resolveSibling(victim.getFileName() + ".corrupt")));
        assertEquals(SHARD, GameShard.read(victim).games().size());

        log.reset();
        assertEquals(Job.EXIT_ERROR, job(dir, SPEC.replace("nodes=300", "nodes=400")).run());
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("written with settings"));
    }

    @Test
    void aHeldLockMeansRunningAlreadyAndStatusSaysSo(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        ByteArrayOutputStream statusOut = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(statusOut, true, StandardCharsets.UTF_8);
        assertEquals(0, Job.status(dir, SelfPlay.JOB, out));
        assertTrue(statusOut.toString(StandardCharsets.UTF_8).startsWith("not running"));
        Files.writeString(dir.resolve(".selfplay.status"), "pid=" + ProcessHandle.current().pid()
                + " start=2026-11-07T00:00:00Z\nselfplay: 5/10 items\n");
        try (FileChannel ch = FileChannel.open(dir.resolve(".selfplay.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = ch.lock()) {
            assertTrue(lock.isValid());
            assertEquals(Job.EXIT_RUNNING, job(dir, SPEC).run());
            assertTrue(log.toString(StandardCharsets.UTF_8).contains("running already: pid="));
            statusOut.reset();
            assertEquals(Job.EXIT_RUNNING, Job.status(dir, SelfPlay.JOB, out));
            String s = statusOut.toString(StandardCharsets.UTF_8);
            assertTrue(s.contains("elapsed=") && s.contains("last progress: selfplay: 5/10 items"), s);
            assertTrue(shards(dir).isEmpty(), "no game was played");
        }
        statusOut.reset();
        assertEquals(0, Job.status(dir, SelfPlay.JOB, out));
        assertTrue(statusOut.toString(StandardCharsets.UTF_8).contains("leftover status"));
        assertFalse(statusOut.toString(StandardCharsets.UTF_8).contains("running already"));
    }
}
