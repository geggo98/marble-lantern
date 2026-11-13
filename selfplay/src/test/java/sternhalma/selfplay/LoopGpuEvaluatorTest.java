package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sternhalma.selfplay.LoopFixture.NEVER;
import static sternhalma.selfplay.LoopFixture.gen;
import static sternhalma.selfplay.LoopFixture.stopIn;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.Networks;
import sternhalma.selfplay.LoopFixture.Result;
import sternhalma.tornado.GpuEvaluators;

/**
 * The training loop with {@code --evaluator gpu} (block G1 of docs/training.md) on a TINY network: the self-play games take
 * their evaluations from the device, a GPU run repeats its own bits, the evaluator is not part of {@code run.json} (a run may
 * be resumed with the other one, and the shards say which one wrote them), a JVM without a device falls back to the CPU with a
 * message, and a real process that is killed during the self-play restarts to the results of an uninterrupted run.
 *
 * <p>Runs in the Gradle task {@code gpuTest}, whose JVM has the TornadoVM flags; the tests are skipped where no device is
 * usable (and the task is skipped without the SDK).
 */
class LoopGpuEvaluatorTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @TempDir
    static Path shared;
    static Path init;
    private static Path reference;

    @BeforeAll
    static void network() throws IOException {
        init = LoopFixture.initNetwork(shared.resolve("init.safetensors"), 3);
    }

    @BeforeEach
    void requireDevice() {
        String why = GpuEvaluators.unavailableReason();
        if (Boolean.getBoolean("sternhalma.tornado.flags") && why != null && !why.contains("no device")) {
            throw new AssertionError("the build passed the TornadoVM flags, but the device evaluator is not usable: " + why);
        }
        assumeTrue(why == null, "no usable device: " + why);
    }

    private static List<String> opts(String... more) {
        List<String> args = new ArrayList<>(List.of("--init", init.toString(), "--evaluator", "gpu", "--games-in-flight", "4"));
        args.addAll(LoopFixture.options(more));
        return args;
    }

    private static synchronized Path reference() throws Exception {
        if (reference == null) {
            reference = shared.resolve("reference");
            Result r = LoopFixture.loop(reference, NEVER, opts());
            assertEquals(0, r.code(), r.err());
        }
        return reference;
    }

    private static String shardEvaluators(Path run, int generation) throws IOException {
        try (Stream<Path> s = Files.list(gen(run, generation).resolve("samples"))) {
            return s.filter(f -> f.getFileName().toString().endsWith(".safetensors")).sorted()
                    .map(f -> {
                        try {
                            return SampleFile.read(f).metadata().get("evaluator");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).distinct().toList().toString();
        }
    }

    @Test
    void aGpuRunNamesTheEvaluatorInTheShardsNotInRunJsonAndRepeatsItsBits() throws Exception {
        Path run = reference();
        assertFalse(Files.readString(run.resolve("run.json")).contains("evaluator"), "the evaluator is not part of the configuration");
        assertEquals("[gpu]", shardEvaluators(run, 1));
        Path again = shared.resolve("again");
        Result r = LoopFixture.loop(again, NEVER, opts());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.err().contains("gen 1 selfplay: evaluator gpu ("), r.err());
        assertTrue(r.err().contains("4 games in flight in 2 cohorts"), r.err());
        LoopTest.assertSameResults(reference(), again);
    }

    /** The evaluator is no part of the configuration: a run that began on the device goes on on the CPU, and the other way. */
    @Test
    void aResumeWithTheOtherEvaluatorIsAllowedAndKeepsTheShards() throws Exception {
        Path run = shared.resolve("mixed");
        // one game in flight, so that shard 0 (games 0 and 1) is complete when the stop comes after the second game
        Result first = LoopFixture.loop(run, stopIn("selfplay", 2), opts("--games-in-flight", "1"));
        assertEquals(130, first.code(), first.err());
        assertEquals("[gpu]", shardEvaluators(run, 1));
        List<String> cpu = new ArrayList<>(List.of("--init", init.toString()));
        cpu.addAll(LoopFixture.options());
        Result second = LoopFixture.loop(run, NEVER, cpu);
        assertEquals(0, second.code(), second.err());
        assertTrue(second.err().contains("was written by the gpu evaluator, this run uses the cpu one: kept"), second.err());
        assertEquals("[gpu, cpu]", shardEvaluators(run, 1));
    }

    private static List<String> command(boolean withFlags, String... args) {
        List<String> cmd = new ArrayList<>(List.of(JAVA));
        if (withFlags) {
            for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (!a.startsWith("-Dorg.gradle") && !a.startsWith("-Dgradle") && !a.startsWith("-javaagent")
                        && !a.startsWith("-Djava.security.manager") && !a.startsWith("-Dsternhalma.tablebase")) {
                    cmd.add(a);
                }
            }
        } else if (Networks.vectorAvailable()) {
            cmd.addAll(List.of("--add-modules", "jdk.incubator.vector"));
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), "sternhalma.selfplay.Loop"));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static Process start(Path log, boolean withFlags, List<String> args) throws IOException {
        return new ProcessBuilder(command(withFlags, args.toArray(String[]::new))).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }

    private static long shards(Path run) {
        try {
            return LoopFixture.count(gen(run, 1).resolve("samples"), "samples-.*\\.safetensors");
        } catch (IOException e) {
            return 0;
        }
    }

    private static void await(String what, Process p, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        while (!condition.getAsBoolean()) {
            assertTrue(p.isAlive(), "the process ended early while waiting for " + what);
            assertTrue(System.nanoTime() < deadline, "timeout waiting for " + what);
            Thread.sleep(50);
        }
    }

    /**
     * A process with the device evaluator is killed during the self-play, once with SIGKILL (no summary, no clean-up) and once
     * with SIGTERM (143, the games in flight are discarded), and the restart ends with the results of an uninterrupted run in
     * this JVM.
     */
    @Test
    void realProcessesKilledDuringTheSelfPlayRestartToTheSameResults(@TempDir Path tmp) throws Exception {
        Path run = tmp.resolve("run");
        String[] more = {"--generations", "1", "--games", "60", "--games-in-flight", "8"};
        List<String> args = new ArrayList<>(List.of("run", run.toString()));
        args.addAll(opts(more));
        Path expected = tmp.resolve("expected");
        assertEquals(0, LoopFixture.loop(expected, NEVER, opts(more)).code());

        Process first = start(tmp.resolve("first.log"), true, args);
        await("a shard", first, () -> shards(run) >= 1);
        new ProcessBuilder("kill", "-KILL", Long.toString(first.pid())).start().waitFor();
        assertTrue(first.waitFor(60, TimeUnit.SECONDS));
        assertEquals(137, first.exitValue());

        Process second = start(tmp.resolve("second.log"), true, args);
        await("the restart to play", second, () -> Files.exists(tmp.resolve("second.log"))
                && contains(tmp.resolve("second.log"), "evaluator gpu"));
        new ProcessBuilder("kill", "-TERM", Long.toString(second.pid())).start().waitFor();
        assertTrue(second.waitFor(120, TimeUnit.SECONDS));
        String secondLog = Files.readString(tmp.resolve("second.log"));
        assertTrue(second.exitValue() == 143 || second.exitValue() == 0, secondLog);

        Process last = start(tmp.resolve("last.log"), true, args);
        assertTrue(last.waitFor(300, TimeUnit.SECONDS));
        String lastLog = Files.readString(tmp.resolve("last.log"));
        assertEquals(0, last.exitValue(), lastLog);
        LoopTest.assertSameResults(expected, run);
        LoopTest.assertNoTemporaries(run);
    }

    private static boolean contains(Path file, String text) {
        try {
            return Files.readString(file).contains(text);
        } catch (IOException e) {
            return false;
        }
    }

    /** A JVM without the TornadoVM flags has no device: {@code --evaluator gpu} says so and plays on the CPU. */
    @Test
    void aJvmWithoutADeviceFallsBackToTheCpuWithAMessage(@TempDir Path tmp) throws Exception {
        Path run = tmp.resolve("run");
        List<String> args = new ArrayList<>(List.of("run", run.toString()));
        args.addAll(opts("--generations", "1"));
        Process p = start(tmp.resolve("fallback.log"), false, args);
        assertTrue(p.waitFor(240, TimeUnit.SECONDS));
        String log = Files.readString(tmp.resolve("fallback.log"), StandardCharsets.UTF_8);
        assertEquals(0, p.exitValue(), log);
        assertTrue(log.contains("loop: --evaluator gpu: ") && log.contains("playing the self-play games on the CPU instead"), log);
        assertTrue(log.contains("gen 1 selfplay: evaluator cpu, "), log);
        assertEquals("[cpu]", shardEvaluators(run, 1));
    }
}
