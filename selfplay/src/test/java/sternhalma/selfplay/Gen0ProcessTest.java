package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import sternhalma.nn.Checkpoint;

/**
 * The exit codes of real processes: a second process exits with 75 while the first holds the lock, {@code --status} names
 * the running process, SIGINT ends with 130 and SIGTERM with 143 after the summary, a restart resumes from the checkpoint,
 * and the separate strength step stops with 130 and keeps no half result.
 */
class Gen0ProcessTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private static List<String> command(String... args) {
        List<String> cmd = new ArrayList<>(List.of(JAVA, "-cp", System.getProperty("java.class.path"),
                "sternhalma.selfplay.Gen0"));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static Process start(Path log, String... args) throws IOException {
        return new ProcessBuilder(command(args)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static String output(String... args) throws Exception {
        Process p = new ProcessBuilder(command(args)).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return p.exitValue() + ":" + text;
    }

    private static void kill(String signal, Process p) throws Exception {
        Process k = new ProcessBuilder("kill", "-" + signal, Long.toString(p.pid())).start();
        assertEquals(0, k.waitFor());
    }

    private static long newestCheckpoint(Path run) throws IOException {
        List<Path> all = Checkpoint.list(run.resolve("gen-0000").resolve("train"));
        return all.isEmpty() ? -1 : Checkpoint.stepOf(all.get(all.size() - 1));
    }

    private static void await(String what, Process p, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (!condition.getAsBoolean()) {
            assertTrue(p.isAlive(), "the process ended early while waiting for " + what);
            assertTrue(System.nanoTime() < deadline, "timeout waiting for " + what);
            Thread.sleep(50);
        }
    }

    private static boolean grew(Path run, long than) {
        try {
            return newestCheckpoint(run) > than;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void lockStatusSignalsAndRestart(@TempDir Path tmp) throws Exception {
        Path data = Files.createDirectory(tmp.resolve("data"));
        SyntheticShards.write(data, "samples-s1-0000.safetensors", 1, 0, 20, 8, 21);
        SyntheticShards.write(data, "samples-s1-0001.safetensors", 1, 20, 20, 8, 22);
        Path run = tmp.resolve("run");
        String[] args = {"run", data.toString(), run.toString(), "--preset", "tiny", "--steps", "400000", "--batch", "16",
                "--microbatch", "8", "--threads", "1", "--warmup", "10", "--holdout", "0.25", "--eval-samples", "40",
                "--checkpoint-steps", "200", "--progress-seconds", "0.2"};

        Process first = start(tmp.resolve("first.log"), args);
        await("a checkpoint", first, () -> grew(run, -1));
        // a second process on the same run directory: 75
        Process second = start(tmp.resolve("second.log"), args);
        assertTrue(second.waitFor(120, TimeUnit.SECONDS));
        assertEquals(75, second.exitValue(), Files.readString(tmp.resolve("second.log")));
        assertTrue(Files.readString(tmp.resolve("second.log")).contains("running already"));
        String status = output("run", "--status", run.toString());
        assertTrue(status.startsWith("75:"), status);
        assertTrue(status.contains("pid=" + first.pid()), status);

        kill("INT", first);
        assertTrue(first.waitFor(120, TimeUnit.SECONDS));
        String firstLog = Files.readString(tmp.resolve("first.log"));
        assertEquals(130, first.exitValue(), firstLog);
        assertTrue(firstLog.contains("gen 0 finish: aborted at step"), firstLog);
        assertTrue(firstLog.contains("run again to continue"), firstLog);
        long afterInt = newestCheckpoint(run);
        assertTrue(afterInt > 0, "the abort leaves a final checkpoint");
        assertFalse(Files.exists(run.resolve("gen-0000").resolve("candidate.safetensors")));

        Process third = start(tmp.resolve("third.log"), args);
        await("a newer checkpoint", third, () -> grew(run, afterInt));
        third.destroy(); // SIGTERM
        assertTrue(third.waitFor(120, TimeUnit.SECONDS));
        String thirdLog = Files.readString(tmp.resolve("third.log"));
        assertEquals(143, third.exitValue(), thirdLog);
        assertTrue(thirdLog.contains("start at step " + afterInt), thirdLog);
        assertTrue(thirdLog.contains("rate of an earlier run"), thirdLog);
        try (Stream<Path> s = Files.walk(run)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")), "no half-written file");
        }
        String after = output("run", "--status", run.toString());
        assertTrue(after.startsWith("0:not running"), after);
        assertTrue(newestCheckpoint(run) > afterInt);
        // the monitor report reads what the aborted processes left
        String report = output("monitor-report", run.toString());
        assertTrue(report.startsWith("0:"), report);
        assertTrue(report.contains("2 process(es)"), report);
    }

    @Test
    void theSeparateStrengthStepStopsWithOneHundredThirtyAndKeepsNoHalfResult(@TempDir Path tmp) throws Exception {
        Path data = Files.createDirectory(tmp.resolve("data"));
        SyntheticShards.write(data, "samples-s1-0000.safetensors", 1, 0, 20, 8, 21);
        SyntheticShards.write(data, "samples-s1-0001.safetensors", 1, 20, 20, 8, 22);
        Path run = tmp.resolve("run");
        Process train = start(tmp.resolve("train.log"), "run", data.toString(), run.toString(), "--preset", "tiny",
                "--steps", "20", "--batch", "8", "--microbatch", "4", "--warmup", "2", "--holdout", "0.25",
                "--checkpoint-steps", "10");
        assertTrue(train.waitFor(120, TimeUnit.SECONDS));
        assertEquals(0, train.exitValue(), Files.readString(tmp.resolve("train.log")));

        Process estimate = start(tmp.resolve("strength.log"), "strength", run.toString(), "--elo-pairs", "400",
                "--ladder", "100", "--ladder-spec", "ab:eval=distance", "--progress-seconds", "0.2");
        await("a progress line", estimate, () -> {
            try {
                return Files.readString(tmp.resolve("strength.log")).contains("pairs  ");
            } catch (IOException e) {
                return false;
            }
        });
        kill("INT", estimate);
        assertTrue(estimate.waitFor(120, TimeUnit.SECONDS));
        String log = Files.readString(tmp.resolve("strength.log"));
        assertEquals(130, estimate.exitValue(), log);
        assertTrue(log.contains("estimate is discarded"), log);
        Path strength = run.resolve("gen-0000").resolve("strength");
        assertTrue(!Files.exists(strength) || Files.list(strength).findAny().isEmpty(), "no half result");
        assertTrue(output("run", "--status", run.toString()).startsWith("0:not running"));
    }
}
