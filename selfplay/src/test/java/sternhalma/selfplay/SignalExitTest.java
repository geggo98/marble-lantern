package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The exit codes of real processes: SIGINT ends with 130 and SIGTERM with 143 after the summary, a second run on the
 * same directory ends with 75 while the first holds the lock, {@code --status} reports the running process, and a
 * restart completes the data set (training.md open question 11, verified on the target JDK).
 */
class SignalExitTest {

    private static Process start(Path dir, Path log, int games) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "sternhalma.selfplay.SelfPlay", "run",
                "--out", dir.toString(), "--spec", JobTest.SPEC, "--games", Integer.toString(games), "--seed", "1006",
                "--shard", "2", "--threads", "1", "--control", "0", "--progress", "0"));
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static long shards(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".safetensors")).count();
        }
    }

    private static void awaitShards(Path dir, long n, Process p) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (shards(dir) < n) {
            assertTrue(p.isAlive(), "the run ended early");
            assertTrue(System.nanoTime() < deadline, "no shard written in time");
            Thread.sleep(100);
        }
    }

    private static void kill(String signal, Process p) throws Exception {
        Process k = new ProcessBuilder("kill", "-" + signal, Long.toString(p.pid())).start();
        assertEquals(0, k.waitFor());
    }

    @Test
    void signalsLockStatusAndRestart(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("data");
        int games = 400;

        Process first = start(dir, tmp.resolve("first.log"), games);
        awaitShards(dir, 1, first);
        // a second run on the same directory: 75, and --status names the running process
        Process second = start(dir, tmp.resolve("second.log"), games);
        assertTrue(second.waitFor(120, TimeUnit.SECONDS));
        assertEquals(75, second.exitValue(), Files.readString(tmp.resolve("second.log")));
        Process status = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), "sternhalma.selfplay.SelfPlay", "run", "--status", "--out",
                dir.toString()).redirectErrorStream(true).start();
        String statusText = new String(status.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(status.waitFor(60, TimeUnit.SECONDS));
        assertEquals(75, status.exitValue(), statusText);
        assertTrue(statusText.contains("pid=" + first.pid()), statusText);

        kill("INT", first);
        assertTrue(first.waitFor(120, TimeUnit.SECONDS));
        String firstLog = Files.readString(tmp.resolve("first.log"));
        assertEquals(130, first.exitValue(), firstLog);
        assertTrue(firstLog.contains("aborted, run again to continue"), firstLog);
        long afterInt = shards(dir);

        Process third = start(dir, tmp.resolve("third.log"), games);
        awaitShards(dir, afterInt + 1, third);
        third.destroy(); // SIGTERM
        assertTrue(third.waitFor(120, TimeUnit.SECONDS));
        String thirdLog = Files.readString(tmp.resolve("third.log"));
        assertEquals(143, third.exitValue(), thirdLog);
        assertTrue(thirdLog.contains(afterInt + " complete (skip)"), thirdLog);
        assertTrue(thirdLog.contains("aborted"), thirdLog);
        try (Stream<Path> s = Files.list(dir)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().contains(".tmp.")), "no half-written shard");
        }
        Process statusAfter = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "sternhalma.selfplay.SelfPlay", "run", "--status",
                "--out", dir.toString()).redirectErrorStream(true).start();
        String after = new String(statusAfter.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(statusAfter.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, statusAfter.exitValue(), after);
        assertTrue(after.startsWith("not running"), after);
        // every shard on disk reads and validates
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(f -> f.getFileName().toString().endsWith(".safetensors")).toList()) {
                assertEquals(2, GameShard.read(f).games().size(), f.toString());
            }
        }
    }
}
