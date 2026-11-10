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

import sternhalma.nn.Networks;

/**
 * The exit codes of real processes: SIGTERM ends with 143 and SIGINT with 130 after the summary and with no
 * half-written file, a second process exits with 75 while the first one holds the lock, {@code --status} names the
 * running process, and the restarts end with the results of an uninterrupted run.
 */
class LoopProcessTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private static List<String> command(String... args) {
        List<String> cmd = new ArrayList<>(List.of(JAVA));
        if (Networks.vectorAvailable()) {
            // the same forward pass as the uninterrupted run in this JVM, or the games would differ in the last bit
            cmd.addAll(List.of("--add-modules", "jdk.incubator.vector"));
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), "sternhalma.selfplay.Loop"));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static Process start(Path log, List<String> args) throws IOException {
        return new ProcessBuilder(command(args.toArray(String[]::new))).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
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

    private static void await(String what, Process p, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (!condition.getAsBoolean()) {
            assertTrue(p.isAlive(), "the process ended early while waiting for " + what);
            assertTrue(System.nanoTime() < deadline, "timeout waiting for " + what);
            Thread.sleep(50);
        }
    }

    private static long shards(Path run) {
        try {
            return LoopFixture.count(LoopFixture.gen(run, 1).resolve("samples"), "samples-.*\\.safetensors");
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean contains(Path file, String text) {
        try {
            return Files.readString(file).contains(text);
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void signalsLockStatusAndRestartsEndWithTheResultsOfAnUninterruptedRun(@TempDir Path tmp) throws Exception {
        Path init = LoopFixture.initNetwork(tmp.resolve("init.safetensors"), 3);
        List<String> args = new ArrayList<>(List.of("run", tmp.resolve("run").toString(), "--init", init.toString()));
        args.addAll(LoopFixture.options("--games", "60", "--progress-seconds", "0.2", "--generations", "1"));
        Path run = tmp.resolve("run");

        // the uninterrupted run, in this JVM
        Path reference = tmp.resolve("reference");
        List<String> inJvm = new ArrayList<>(List.of("--init", init.toString()));
        inJvm.addAll(LoopFixture.options("--games", "60", "--generations", "1"));
        assertEquals(0, LoopFixture.loop(reference, LoopFixture.NEVER, inJvm).code());

        // SIGTERM during the self-play of generation 1: 143 after the summary
        Process first = start(tmp.resolve("first.log"), args);
        await("a sample shard", first, () -> shards(run) >= 1);
        first.destroy();
        assertTrue(first.waitFor(120, TimeUnit.SECONDS));
        String firstLog = Files.readString(tmp.resolve("first.log"));
        assertEquals(143, first.exitValue(), firstLog);
        assertTrue(firstLog.contains("loop: aborted"), firstLog);
        assertTrue(firstLog.contains("run again to continue"), firstLog);
        try (Stream<Path> s = Files.walk(run)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().contains(".tmp")), "no half-written file");
        }
        assertTrue(output("run", "--status", run.toString()).startsWith("0:not running"), "the lock is released");

        // a second process while the first one of the restart holds the lock: 75, and --status names the first
        Process second = start(tmp.resolve("second.log"), args);
        await("the restart to run", second, () -> contains(tmp.resolve("second.log"), "gen 1 selfplay: "));
        Process third = start(tmp.resolve("third.log"), args);
        assertTrue(third.waitFor(120, TimeUnit.SECONDS));
        assertEquals(75, third.exitValue(), Files.readString(tmp.resolve("third.log")));
        assertTrue(Files.readString(tmp.resolve("third.log")).contains("running already"));
        String status = output("run", "--status", run.toString());
        for (int i = 0; i < 25 && !status.contains("last progress: gen "); i++) {
            Thread.sleep(200); // the status file gets its first progress line a moment after the log does
            status = output("run", "--status", run.toString());
        }
        assertTrue(status.startsWith("75:"), status);
        assertTrue(status.contains("pid=" + second.pid()), status);
        assertTrue(status.contains("last progress: gen "), "phase, generation and the last progress line: " + status);

        // SIGINT: 130
        kill("INT", second);
        assertTrue(second.waitFor(120, TimeUnit.SECONDS));
        String secondLog = Files.readString(tmp.resolve("second.log"));
        assertEquals(130, second.exitValue(), secondLog);
        assertTrue(secondLog.contains("loop: aborted"), secondLog);
        assertTrue(output("run", "--status", run.toString()).startsWith("0:not running"));

        // and the restart finishes with the results of the uninterrupted run
        Process last = start(tmp.resolve("last.log"), args);
        assertTrue(last.waitFor(300, TimeUnit.SECONDS));
        String lastLog = Files.readString(tmp.resolve("last.log"));
        assertEquals(0, last.exitValue(), lastLog);
        assertTrue(lastLog.contains("nothing more to do"), lastLog);
        LoopTest.assertSameResults(reference, run);
        String report = output("monitor-report", run.toString());
        assertTrue(report.startsWith("0:") && report.contains("loop shares of"), report);
    }
}
