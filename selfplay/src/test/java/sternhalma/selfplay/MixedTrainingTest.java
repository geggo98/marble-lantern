package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Evaluator;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.ReferenceTrainer;
import sternhalma.nn.StepTrainer;
import sternhalma.nn.TrainingBatch;
import sternhalma.selfplay.ReplayBuffer.Strata;
import sternhalma.tornado.GpuTrainers;

/**
 * Training one TINY classic1v network on mixed data of all four variants: the loss goes down for the CPU trainer and
 * for the Tornado trainer, every variant learns (its policy KL falls), {@code Gen0 run --planes classic1v} works on
 * a parent directory with one subdirectory per variant, reports the proxies per variant, resumes, and writes a
 * version 3 file that the evaluator and the raw policy player use in every variant.
 */
class MixedTrainingTest {

    private static final int GAMES = 12;
    private static final int PER_GAME = 8;

    @TempDir
    Path dir;

    private static RuleSet rules(Variant v) {
        return RuleSet.defaults(v.players()).withMoveLimit(300).withJumpRule(v.jumpRule());
    }

    /** One subdirectory per variant, each with one shard of synthetic teacher samples (48 samples). */
    static Path dataDir(Path dir, String name) throws IOException {
        Path data = Files.createDirectories(dir.resolve(name));
        for (Variant v : Variant.values()) {
            Path sub = Files.createDirectories(data.resolve(v.label()));
            SyntheticShards.write(sub, "samples-000000.safetensors", rules(v), 21 + v.ordinal(), 0, 2 * GAMES, PER_GAME,
                    300 + v.ordinal());
        }
        return data;
    }

    private static ReplayBuffer buffer(Path data) throws IOException {
        ReplayBuffer b = ReplayBuffer.mixed(Integer.MAX_VALUE, false, PlaneSet.CLASSIC1V, Strata.EQUAL);
        b.addGeneration(0, Gen0.dataFiles(data));
        return b;
    }

    // ---- the loss goes down ----

    /** Trains a TINY classic1v net on all four variants; the CPU trainer or the Tornado trainer. */
    static void overfit(Path dir, boolean gpu) throws IOException {
        ReplayBuffer buffer = buffer(dataDir(dir, "overfit-" + gpu));
        NetworkConfig config = NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V);
        Parameters parameters = Parameters.init(config, 7);
        Optimizer optimizer = new Optimizer(parameters, Optimizer.Settings.adamw());
        int batchSize = 32;
        Map<Variant, HeldOutMetrics.Metrics> before = HeldOutMetrics.evaluateByVariant(new Network(parameters), buffer,
                1000, 1);
        assertEquals(4, before.size());
        double firstLoss = 0;
        double lastLoss = 0;
        int steps = 200;
        try (StepTrainer trainer = gpu ? GpuTrainers.create(parameters, optimizer, batchSize, 1.0)
                : StepTrainer.cpu(parameters, optimizer, 1.0, 16, 2)) {
            for (int step = 0; step < steps; step++) {
                TrainingBatch batch = buffer.batch(3, 0, step, batchSize);
                assertEquals(3, batch.players());
                ReferenceTrainer.Result r = trainer.step(batch, 0.01);
                if (step < 10) {
                    firstLoss += r.loss() / 10;
                }
                if (step >= steps - 10) {
                    lastLoss += r.loss() / 10;
                }
            }
            trainer.sync();
        }
        assertTrue(lastLoss < 0.8 * firstLoss, "loss " + firstLoss + " to " + lastLoss);
        Map<Variant, HeldOutMetrics.Metrics> after = HeldOutMetrics.evaluateByVariant(new Network(parameters), buffer,
                1000, 1);
        for (Variant v : Variant.values()) {
            assertTrue(after.get(v).klPolicy() < before.get(v).klPolicy(),
                    v.label() + " policy KL " + before.get(v).klPolicy() + " to " + after.get(v).klPolicy());
        }
        // the value targets are random numbers, so a single variant may need more steps; over all variants it falls
        double valueBefore = before.values().stream().mapToDouble(HeldOutMetrics.Metrics::valueLoss).sum();
        double valueAfter = after.values().stream().mapToDouble(HeldOutMetrics.Metrics::valueLoss).sum();
        assertTrue(valueAfter < valueBefore, "value loss " + valueBefore + " to " + valueAfter);
        System.out.printf("overfit (%s): loss %.3f to %.3f; KL per variant %s%n", gpu ? "gpu" : "cpu", firstLoss,
                lastLoss, after.entrySet().stream().map(e -> e.getKey().label() + " " + String.format("%.2f to %.2f",
                        before.get(e.getKey()).klPolicy(), e.getValue().klPolicy())).toList());
    }

    @Test
    void aTinySharedNetOverfitsAMixedFourVariantDatasetOnTheCpu() throws IOException {
        overfit(dir, false);
    }

    // ---- Gen0 ----

    record Result(int code, String err, String out) {
    }

    static Result gen0(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Gen0.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), () -> false);
        return new Result(code, err.toString(StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8));
    }

    static String[] options(Path data, Path run, String... more) {
        List<String> args = new ArrayList<>(List.of(data.toString(), run.toString(), "--planes", "classic1v", "--preset",
                "tiny", "--steps", "8", "--batch", "8", "--microbatch", "4", "--threads", "2", "--warmup", "2", "--lr",
                "0.01", "--holdout", "0.25", "--eval-samples", "40", "--proxy-seconds", "0", "--elo-seconds", "0",
                "--checkpoint-steps", "4", "--progress-seconds", "0.01", "--seed", "5"));
        args.addAll(List.of(more));
        return args.toArray(String[]::new);
    }

    static String[] run(String[] options) {
        String[] all = new String[options.length + 1];
        all[0] = "run";
        System.arraycopy(options, 0, all, 1, options.length);
        return all;
    }

    @Test
    void gen0TrainsOnAParentDirectoryWithOneSubdirectoryPerVariantAndReportsPerVariant() throws Exception {
        Path data = dataDir(dir, "gen0");
        Path run = dir.resolve("run");
        Result r = gen0(run(options(data, run)));
        assertEquals(0, r.code, r.err);
        String json = Files.readString(run.resolve("run.json"));
        assertTrue(json.contains("\"players\":3") && json.contains("\"planes\":\"classic1v\"")
                && json.contains("\"strata\":\"equal\""), json);
        // proxies per variant in the monitor lines and on stderr
        String metrics = Files.readString(run.resolve("metrics.jsonl"));
        for (Variant v : Variant.values()) {
            assertTrue(metrics.contains("\"variant\":\"" + v.label() + "\""), v.label() + " missing in " + metrics);
            assertTrue(r.err.contains("proxies at step 8, " + v.label()), r.err);
        }
        // the proxies command reports them as well
        Result p = gen0("proxies", run.toString(), "--data", data.toString(), "--samples", "40", "--threads", "1");
        assertEquals(0, p.code, p.err);
        for (Variant v : Variant.values()) {
            assertTrue(p.out.contains("  " + v.label() + ":"), p.out);
        }
        // a second run after success does nothing
        Result again = gen0(run(options(data, run)));
        assertEquals(0, again.code, again.err);
        assertTrue(again.err.contains("nothing more to do"), again.err);
        // another sampling is another configuration
        Result other = gen0(run(options(data, run, "--strata", "natural")));
        assertEquals(1, other.code, other.err);
        assertTrue(other.err.contains("another configuration"), other.err);
    }

    @Test
    void theCandidateIsAVersion3FileThatPlaysALegalGameInEveryVariant() throws Exception {
        Path data = dataDir(dir, "legal");
        Path run = dir.resolve("legal-run");
        Result r = gen0(run(options(data, run)));
        assertEquals(0, r.code, r.err);
        Path candidate = run.resolve("gen-0000").resolve("candidate.safetensors");
        Parameters parameters = Parameters.load(candidate);
        assertEquals(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V), parameters.config());
        assertEquals(NetworkConfig.FORMAT_VERSION_VARIANTS, Integer.parseInt(sternhalma.nn.Safetensors.read(candidate).metadata().get("format_version")));
        Network network = new Network(parameters);
        Evaluator evaluator = new Evaluator(network);
        RawPolicyPlayer player = new RawPolicyPlayer(network, "shared");
        for (Variant v : Variant.values()) {
            Position position = Position.initial(rules(v));
            int plies = 0;
            while (position.outcome() == null && plies < 60) {
                int[] legal = position.legalMoves();
                assertTrue(legal.length > 0, v.label());
                assertNotNull(evaluator.evaluate(position), v.label());
                int move = player.choose(position);
                assertTrue(java.util.Arrays.stream(legal).anyMatch(m -> m == move),
                        v.label() + " ply " + plies + ": move " + move + " is not legal");
                position.make(move);
                plies++;
            }
            assertEquals(60, Math.min(60, plies), v.label() + " ended early at ply " + plies);
        }
        assertFalse(r.err.contains("Exception"), r.err);
    }

    @Test
    void aSingleDirectoryOfOneVariantStillTrainsASharedNet() throws Exception {
        Path data = dataDir(dir, "single").resolve(Variant.TWO_LONG.label());
        Path run = dir.resolve("single-run");
        Result r = gen0(run(options(data, run)));
        assertEquals(0, r.code, r.err);
        assertTrue(Files.readString(run.resolve("metrics.jsonl")).contains("\"variant\":\"2p-long\""));
    }

    /**
     * The winner of a sample is the relative player with the highest output among the players of its own game: the
     * dummy third output of a two-player sample, set high on purpose, must not count.
     */
    @Test
    void winnerAccuracyUsesOnlyTheOutputsOfTheSamplesOwnPlayers() throws IOException {
        ReplayBuffer buffer = buffer(dataDir(dir, "winner"));
        List<Integer> picked = new ArrayList<>();
        for (Variant v : Variant.values()) {
            int[] all = buffer.indicesOf(v);
            for (int i : all) {
                if (buffer.winner(i) >= 0 && picked.stream().filter(k -> buffer.variantOf(k) == v).count() < 6) {
                    picked.add(i);
                }
            }
        }
        int n = picked.size();
        int[] indices = picked.stream().mapToInt(Integer::intValue).toArray();
        TrainingBatch batch = buffer.batchOf(indices, new boolean[n]);
        int[] winners = new int[n];
        int[] counts = new int[n];
        float[] right = new float[3 * n];
        float[] wrong = new float[3 * n];
        for (int s = 0; s < n; s++) {
            winners[s] = buffer.winner(indices[s]);
            counts[s] = buffer.variantOf(indices[s]).players();
            for (int i = 0; i < 3; i++) {
                right[3 * s + i] = i == winners[s] ? 0.5f : -0.5f;
                wrong[3 * s + i] = i == (winners[s] + 1) % counts[s] ? 0.5f : -0.5f;
            }
            if (counts[s] == 2) {
                right[3 * s + 2] = 0.9f; // the dummy output is the highest of the three
                wrong[3 * s + 2] = 0.9f;
            }
        }
        float[] policy = new float[n * sternhalma.nn.Encoder.POLICY_SIZE];
        HeldOutMetrics.Accumulator good = new HeldOutMetrics.Accumulator();
        good.add(batch, policy, right, winners, counts);
        HeldOutMetrics.Accumulator bad = new HeldOutMetrics.Accumulator();
        bad.add(batch, policy, wrong, winners, counts);
        assertEquals(n, good.metrics().winnerSamples());
        assertEquals(1.0, good.metrics().winnerAccuracy());
        assertEquals(0.0, bad.metrics().winnerAccuracy());
        assertTrue(n >= 20, "samples of all four variants: " + n);
    }
}
