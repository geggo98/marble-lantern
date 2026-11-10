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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.Checkpoint;
import sternhalma.nn.Networks;
import sternhalma.selfplay.LoopFixture.Result;
import sternhalma.tornado.GpuTrainers;

/**
 * The training loop with {@code --trainer gpu} (block B10 of docs/training.md, Part B) on a TINY network: the
 * results of a GPU run are those of another GPU run (the device trainer is deterministic), an abort during the
 * training resumes from the newest checkpoint with the bits of an uninterrupted run, the trainer kind is part of the
 * configuration, a JVM without a device falls back to the CPU trainer with a message, and real processes that are
 * killed during the training restart to the same results.
 *
 * <p>Runs in the Gradle task {@code gpuTest}, whose JVM has the TornadoVM flags; the tests are skipped where no
 * device is usable (and the task is skipped without the SDK).
 */
class LoopGpuTest {

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
        String why = GpuTrainers.unavailableReason();
        if (Boolean.getBoolean("sternhalma.tornado.flags") && why != null && !why.contains("no device")) {
            throw new AssertionError("the build passed the TornadoVM flags, but the device trainer is not usable: " + why);
        }
        assumeTrue(why == null, "no usable device: " + why);
    }

    private static List<String> opts(String... more) {
        List<String> args = new ArrayList<>(List.of("--init", init.toString(), "--trainer", "gpu"));
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

    @Test
    void aGpuRunWritesTheTrainerIntoRunJsonTrainsOnTheDeviceAndRepeatsItsBits() throws Exception {
        Path run = reference();
        String json = Files.readString(run.resolve("run.json"));
        assertTrue(json.contains("\"trainer\":\"gpu\""), json);
        for (int g = 1; g <= 2; g++) {
            assertTrue(Files.exists(gen(run, g).resolve("candidate.safetensors")), "candidate of generation " + g);
        }
        Path again = shared.resolve("again");
        Result r = LoopFixture.loop(again, NEVER, opts());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.err().contains("gen 1 train: GPU trainer on "), r.err());
        assertTrue(r.err().contains("steps/s"), "the progress line has the steps per second: " + r.err());
        LoopTest.assertSameResults(reference(), again);
    }

    @Test
    void theTrainerKindBelongsToTheConfiguration() throws Exception {
        Path run = shared.resolve("kind");
        assertEquals(130, LoopFixture.loop(run, stopIn("train", 1), opts()).code());
        // the same directory with the CPU trainer (the default): another configuration, refused with exit 1
        List<String> cpu = new ArrayList<>(List.of("--init", init.toString()));
        cpu.addAll(LoopFixture.options());
        Result refused = LoopFixture.loop(run, NEVER, cpu);
        assertEquals(1, refused.code(), refused.err());
        assertTrue(refused.err().contains("holds another configuration"), refused.err());
        assertTrue(Files.readString(run.resolve("run.json")).contains("\"trainer\":\"gpu\""));
        // and the CPU run's configuration is the one that run.json had before the option existed: no trainer field
        Path cpuRun = shared.resolve("cpu");
        assertEquals(130, LoopFixture.loop(cpuRun, stopIn("selfplay", 1), cpu).code());
        assertFalse(Files.readString(cpuRun.resolve("run.json")).contains("trainer"));
    }

    @Test
    void anAbortDuringTheTrainingResumesFromTheNewestCheckpointWithTheBitsOfAnUninterruptedRun() throws Exception {
        Path run = shared.resolve("abort-train");
        Result first = LoopFixture.loop(run, stopIn("train", 3), opts("--threads", "1"));
        assertEquals(130, first.code(), first.err());
        assertTrue(first.err().contains("gen 1 train: aborted at step 3"), first.err());
        assertFalse(Files.exists(gen(run, 1).resolve("candidate.safetensors")), "no candidate from an aborted training");
        assertEquals(List.of(3L), Checkpoint.list(gen(run, 1).resolve("train")).stream().map(Checkpoint::stepOf).toList(),
                "the abort leaves a checkpoint at step 3");
        Result second = LoopFixture.loop(run, NEVER, opts());
        assertEquals(0, second.code(), second.err());
        Matcher m = Pattern.compile("start at step (\\d+)").matcher(second.err());
        assertTrue(m.find() && Integer.parseInt(m.group(1)) == 3, "training resumed at step 3: " + second.err());
        LoopTest.assertSameResults(reference(), run);
    }

    // ---- real processes ----

    /** The command of a Loop process: the flags of this JVM (TornadoVM's, the Vector API) or none. */
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

    private static boolean checkpoints(Path run, int atLeast) {
        try {
            return Checkpoint.list(gen(run, 1).resolve("train")).size() >= atLeast;
        } catch (IOException e) {
            return false;
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

    private static void kill(String signal, Process p) throws Exception {
        assertEquals(0, new ProcessBuilder("kill", "-" + signal, Long.toString(p.pid())).start().waitFor());
    }

    /**
     * A process with the GPU trainer is killed during the training, once with SIGKILL (no summary, no clean-up) and once
     * with SIGTERM (143), and the restarts end with the results of an uninterrupted run in this JVM: the checkpoint
     * holds the parameters and the optimiser state that the device had at that step.
     */
    @Test
    void realProcessesKilledDuringTheTrainingRestartToTheSameResults(@TempDir Path tmp) throws Exception {
        Path run = tmp.resolve("run");
        String[] more = {"--generations", "1", "--games", "40", "--reuse", "40", "--checkpoint-steps", "10", "--keep", "3"};
        List<String> args = new ArrayList<>(List.of("run", run.toString()));
        args.addAll(opts(more));
        Path expected = tmp.resolve("expected");
        assertEquals(0, LoopFixture.loop(expected, NEVER, opts(more)).code());

        Process first = start(tmp.resolve("first.log"), true, args);
        await("two checkpoints", first, () -> checkpoints(run, 2));
        kill("KILL", first);
        assertTrue(first.waitFor(60, TimeUnit.SECONDS));
        assertEquals(137, first.exitValue());
        assertFalse(Files.exists(gen(run, 1).resolve("candidate.safetensors")), "no candidate yet");

        Process second = start(tmp.resolve("second.log"), true, args);
        await("the restart to train", second, () -> contains(tmp.resolve("second.log"), "gen 1 train: GPU trainer"));
        await("a new checkpoint", second, () -> contains(tmp.resolve("second.log"), "start at step "));
        kill("TERM", second);
        assertTrue(second.waitFor(120, TimeUnit.SECONDS));
        String secondLog = Files.readString(tmp.resolve("second.log"));
        assertTrue(second.exitValue() == 143 || second.exitValue() == 0, secondLog);

        Process last = start(tmp.resolve("last.log"), true, args);
        assertTrue(last.waitFor(300, TimeUnit.SECONDS));
        String lastLog = Files.readString(tmp.resolve("last.log"));
        assertEquals(0, last.exitValue(), lastLog);
        assertTrue(lastLog.contains("start at step ") || second.exitValue() == 0, lastLog);
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

    /**
     * A JVM without the TornadoVM flags has no device: {@code --trainer gpu} says so, trains on the CPU and records the
     * trainer that ran in run.json, which names no trainer then (a CPU run).
     */
    @Test
    void aJvmWithoutADeviceFallsBackToTheCpuTrainerWithAMessage(@TempDir Path tmp) throws Exception {
        Path run = tmp.resolve("run");
        List<String> args = new ArrayList<>(List.of("run", run.toString()));
        args.addAll(opts("--generations", "0"));
        Process p = start(tmp.resolve("fallback.log"), false, args);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        String log = Files.readString(tmp.resolve("fallback.log"), StandardCharsets.UTF_8);
        assertEquals(0, p.exitValue(), log);
        assertTrue(log.contains("loop: --trainer gpu: ") && log.contains("training the candidates on the CPU instead"), log);
        assertFalse(Files.readString(run.resolve("run.json")).contains("trainer"));
    }
}
