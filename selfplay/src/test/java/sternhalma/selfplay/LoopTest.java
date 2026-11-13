package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sternhalma.selfplay.LoopFixture.NEVER;
import static sternhalma.selfplay.LoopFixture.gen;
import static sternhalma.selfplay.LoopFixture.stopIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.RuleSet;
import sternhalma.nn.Checkpoint;
import sternhalma.nn.LearningRateSchedule;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.Tensor;
import sternhalma.nn.TrainingRun;
import sternhalma.search.Player;
import sternhalma.search.PlayerSpec;
import sternhalma.selfplay.LoopFixture.Result;

/**
 * The training loop end to end on a TINY network and tiny budgets: the artefacts, the state read from them for every
 * phase, abort and restart in every phase with the results of an uninterrupted run, the idempotent second run, the
 * lock, the independent reproduction of a candidate (fresh optimiser, replay window, schedule), and the accounting.
 */
class LoopTest {

    @TempDir
    static Path shared;
    static Path init;

    @BeforeAll
    static void network() throws IOException {
        init = LoopFixture.initNetwork(shared.resolve("init.safetensors"), 3);
    }

    /** The options of every run, including the init network. */
    static List<String> opts(String... more) {
        List<String> args = new ArrayList<>(List.of("--init", init.toString()));
        args.addAll(LoopFixture.options(more));
        return args;
    }

    private static Path reference;

    /** The uninterrupted run that the others are compared with, made once. */
    static synchronized Path reference() throws Exception {
        if (reference == null) {
            reference = shared.resolve("reference");
            Result r = LoopFixture.loop(reference, NEVER, opts());
            assertEquals(0, r.code(), r.err());
        }
        return reference;
    }

    // ---- comparing runs ----

    private static Map<String, String> pairFields(String line) {
        Map<String, String> m = RunMonitor.parse(line);
        if (m != null) {
            m.remove("s_cpu_ns");
            m.remove("o_cpu_ns");
        }
        return m;
    }

    /** Every result of the run as comparable text or bytes; times (CPU) are left out, they are no result. */
    static Map<String, Object> results(Path run) throws IOException {
        Map<String, Object> out = new TreeMap<>();
        out.put("run.json", Files.readString(run.resolve("run.json")));
        try (Stream<Path> s = Files.walk(run)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = run.relativize(f).toString();
                String name = f.getFileName().toString();
                if (name.startsWith(".") || name.endsWith(".corrupt") || rel.contains("/train/") || name.equals("metrics.jsonl")
                        || name.equals("run.json")) {
                    continue;
                }
                if (name.equals("candidate.safetensors")) {
                    Parameters p = Parameters.load(f);
                    for (Map.Entry<String, Tensor> e : p.tensors().entrySet()) {
                        out.put(rel + ":" + e.getKey(), e.getValue().data());
                    }
                    out.put(rel + ":metadata", new TreeMap<>(p.extraMetadata()).toString());
                } else if (name.endsWith(".safetensors")) {
                    SampleFile.Contents c = SampleFile.read(f);
                    for (Map.Entry<String, SampleFile.Tensor> e : c.tensors().entrySet()) {
                        if (!e.getKey().equals("game_cpu_us")) {
                            out.put(rel + ":" + e.getKey(), e.getValue().data());
                        }
                    }
                    out.put(rel + ":metadata", new TreeMap<>(c.metadata()).toString());
                } else if (name.endsWith(".jsonl")) {
                    List<String> lines = new ArrayList<>();
                    for (String line : Files.readAllLines(f)) {
                        lines.add(String.valueOf(pairFields(line)));
                    }
                    out.put(rel, lines);
                } else {
                    out.put(rel, Files.readString(f));
                }
            }
        }
        return out;
    }

    static void assertSameResults(Path expected, Path actual) throws IOException {
        Map<String, Object> a = results(expected);
        Map<String, Object> b = results(actual);
        assertEquals(a.keySet(), b.keySet(), "the same files");
        for (String key : a.keySet()) {
            assertTrue(java.util.Objects.deepEquals(a.get(key), b.get(key)), "differs: " + key);
        }
    }

    static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : s.toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target);
                } else if (!f.getFileName().toString().equals(".lock")) {
                    Files.copy(f, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(f);
            }
        }
    }

    static void assertNoTemporaries(Path run) throws IOException {
        try (Stream<Path> s = Files.walk(run)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().contains(".tmp")), "no half-written file");
        }
    }

    static Map<String, String> decision(Path run, int g) throws IOException {
        return RunMonitor.parse(Files.readString(gen(run, g).resolve("decision.json")));
    }

    static List<Map<String, String>> events(Path run, String event) throws IOException {
        List<Map<String, String>> out = new ArrayList<>();
        for (String line : Files.readAllLines(run.resolve("metrics.jsonl"))) {
            Map<String, String> m = RunMonitor.parse(line);
            if (m != null && event.equals(m.get("event"))) {
                out.add(m);
            }
        }
        return out;
    }

    // ---- the artefacts of a fresh run ----

    @Test
    void aFreshRunWritesTheDocumentedArtefactsAndNoPointerFile() throws Exception {
        Path run = reference();
        assertTrue(Files.exists(run.resolve("run.json")));
        assertTrue(Files.exists(run.resolve("metrics.jsonl")));
        for (int g = 0; g <= 2; g++) {
            assertTrue(Files.exists(gen(run, g).resolve("candidate.safetensors")), "candidate of generation " + g);
            assertTrue(Files.exists(gen(run, g).resolve("decision.json")), "decision of generation " + g);
        }
        assertEquals("promoted", decision(run, 0).get("decision"), "generation 0 is always promoted");
        assertEquals("true", decision(run, 0).get("init"));
        try (Stream<Path> s = Files.walk(run)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().matches("best.*|latest.*|current.*")), "no pointer file");
        }
        // generation 0 is the given network, byte for byte
        assertArrayEquals(Files.readAllBytes(init), Files.readAllBytes(gen(run, 0).resolve("candidate.safetensors")));
        for (int g = 1; g <= 2; g++) {
            // 4 games in shards of 2, a gate of 4 pairs in shards of 2
            assertEquals(2, LoopFixture.count(gen(run, g).resolve("samples"), "samples-.*\\.safetensors"));
            assertEquals(2, LoopFixture.count(gen(run, g).resolve("gate"), "vs-best-.*\\.jsonl"));
            assertTrue(LoopFixture.count(gen(run, g).resolve("train"), "step-.*\\.safetensors") >= 1);
            Parameters c = Parameters.load(gen(run, g).resolve("candidate.safetensors"));
            Map<String, String> md = c.extraMetadata();
            assertEquals(Integer.toString(g), md.get("generation"));
            assertEquals(LoopConfig.fromJson(RunMonitor.parse(Files.readString(run.resolve("run.json")))).sha256(),
                    md.get("run_config_sha256"));
            long newSamples = Long.parseLong(md.get("new_samples"));
            assertEquals(Math.max(1, (long) Math.ceil(1.0 * newSamples / 16)), Long.parseLong(md.get("step")),
                    "steps = ceil(R * new samples / batch)");
            assertEquals(PlaneSet.CLASSIC1, c.config().planes());
        }
        // the strength record is stored with the network that it measures
        int best = 0;
        for (int g = 1; g <= 2; g++) {
            best = decision(run, g).get("decision").equals("promoted") ? g : best;
        }
        assertTrue(LoopFixture.count(gen(run, best).resolve("strength"), "ab100-.*\\.jsonl") >= 1, "strength of the best network");
        assertNoTemporaries(run);
    }

    @Test
    void theBestNetworkIsTheCandidateOfTheHighestPromotedGenerationAndSelfPlayUsesIt() throws Exception {
        Path run = reference();
        int best = 0;
        for (int g = 1; g <= 2; g++) {
            // the network that played the samples of generation g is the best one before g
            String sha = SampleFile.read(LoopFixture.files(gen(run, g).resolve("samples")).stream()
                    .filter(f -> f.getFileName().toString().endsWith(".safetensors")).findFirst().orElseThrow())
                    .metadata().get("network_sha256");
            assertEquals(Gen0.sha256Hex(Files.readAllBytes(gen(run, best).resolve("candidate.safetensors"))), sha,
                    "generation " + g + " was played by the network of generation " + best);
            assertEquals(Integer.toString(best), decision(run, g).get("best_before"));
            best = decision(run, g).get("decision").equals("promoted") ? g : best;
        }
    }

    @Test
    void theDecisionFollowsTheStatisticsOfTheGateShards() throws Exception {
        Path run = reference();
        for (int g = 1; g <= 2; g++) {
            Map<String, String> d = decision(run, g);
            List<Double> scores = new ArrayList<>();
            for (Path f : LoopFixture.files(gen(run, g).resolve("gate"))) {
                if (f.getFileName().toString().startsWith("vs-best-") && f.getFileName().toString().endsWith(".jsonl")) {
                    List<String> lines = Files.readAllLines(f);
                    for (String line : lines.subList(1, lines.size())) {
                        scores.add(Double.parseDouble(RunMonitor.parse(line).get("score")));
                    }
                }
            }
            double[] s = scores.stream().mapToDouble(Double::doubleValue).toArray();
            Gate.Decision expected = Gate.decide(s, new double[0], new double[0], 2, 0);
            assertEquals(s.length, Integer.parseInt(d.get("pairs")));
            assertEquals(expected.score(), Double.parseDouble(d.get("score")), 1e-12);
            assertEquals(expected.ci95Low(), Double.parseDouble(d.get("ci95_low")), 1e-12);
            assertEquals(0.0, Double.parseDouble(d.get("promote_z")), "the fixture runs the old rule");
            if (Double.isFinite(expected.z())) {
                assertEquals(expected.z(), Double.parseDouble(d.get("z")), 1e-12);
            } else {
                assertEquals("null", d.get("z"), "an infinite statistic is written as null");
            }
            boolean promoted = d.get("decision").equals("promoted");
            if (expected.score() <= 0.5) {
                assertFalse(promoted, "not above one half: rejected");
                assertEquals("true", d.get("baseline_skipped"));
            } else {
                assertEquals(Boolean.parseBoolean(d.get("baseline_ok")), promoted, "above one half: the baseline clause decides");
                assertEquals("2", d.get("baseline_pairs"));
            }
        }
    }

    @Test
    void aSecondRunAfterSuccessDoesNothingAndExitsWithZero() throws Exception {
        Path run = shared.resolve("noop");
        assertEquals(0, LoopFixture.loop(run, NEVER, opts()).code());
        Map<String, Object> before = results(run);
        long metricsBefore = Files.size(run.resolve("metrics.jsonl"));
        Result again = LoopFixture.loop(run, NEVER, opts());
        assertEquals(0, again.code(), again.err());
        assertTrue(again.err().contains("nothing more to do"), again.err());
        assertTrue(again.err().lines().noneMatch(l -> l.matches(".*\\d+/\\d+ items.*")), "no job did any work: " + again.err());
        assertEquals(before.keySet(), results(run).keySet());
        assertSameResults(run, reference());
        assertEquals(metricsBefore, Files.size(run.resolve("metrics.jsonl")), "a run that did nothing records nothing");
        assertNoTemporaries(run);
    }

    @Test
    void moreGenerationsContinueFromTheArtefactsAndGiveTheSameResultsAsOneLongRun() throws Exception {
        Path run = shared.resolve("extend");
        assertEquals(0, LoopFixture.loop(run, NEVER, opts("--generations", "1")).code());
        assertFalse(Files.exists(gen(run, 2)));
        byte[] candidate1 = Files.readAllBytes(gen(run, 1).resolve("candidate.safetensors"));
        Result r = LoopFixture.loop(run, NEVER, opts("--generations", "2"));
        assertEquals(0, r.code(), r.err());
        assertArrayEquals(candidate1, Files.readAllBytes(gen(run, 1).resolve("candidate.safetensors")), "generation 1 is not redone");
        assertFalse(r.err().contains("gen 1 selfplay:"), "no work on generation 1: " + r.err());
        assertSameResults(reference(), run);
    }

    @Test
    void anotherConfigurationUsageErrorsAndMissingInitAreRefused() throws Exception {
        Path run = reference();
        Result other = LoopFixture.loop(run, NEVER, opts("--games", "6"));
        assertEquals(1, other.code(), other.err());
        assertTrue(other.err().contains("another configuration"), other.err());
        // the files of the run are untouched
        assertEquals(Files.readString(run.resolve("run.json")),
                LoopConfig.of(optionsMap(opts()), Gen0.sha256Hex(Files.readAllBytes(init)), "", 0).json() + "\n");

        Path fresh = shared.resolve("noinit");
        Result missing = LoopFixture.loop(fresh, NEVER, LoopFixture.options());
        assertEquals(2, missing.code(), missing.err());
        assertTrue(missing.err().contains("--init"), missing.err());
        Result bad = LoopFixture.command(NEVER, "run");
        assertEquals(2, bad.code());
        Result unknown = LoopFixture.command(NEVER, "frobnicate", fresh.toString());
        assertEquals(2, unknown.code());
        Result badOption = LoopFixture.loop(shared.resolve("badopt"), NEVER, opts("--games", "0"));
        assertEquals(2, badOption.code(), badOption.err());
        // another init network than the run's
        Path other2 = LoopFixture.initNetwork(shared.resolve("other.safetensors"), 4);
        List<String> args = new ArrayList<>(List.of("--init", other2.toString()));
        args.addAll(LoopFixture.options());
        assertEquals(1, LoopFixture.loop(run, NEVER, args).code());
    }

    private static Map<String, String> optionsMap(List<String> options) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < options.size(); i += 2) {
            m.put(options.get(i).substring(2), options.get(i + 1));
        }
        return m;
    }

    // ---- abort and restart in every phase ----

    private static final Pattern START_AT = Pattern.compile("start at step (\\d+)");

    private void abortAndResume(String phase, long done, String expectInResume) throws Exception {
        Path run = shared.resolve("abort-" + phase + "-" + done);
        // one thread: the games and pairs that are in flight when the stop comes are the same on every machine
        Result first = LoopFixture.loop(run, stopIn(phase, done), opts("--threads", "1"));
        assertEquals(130, first.code(), first.err());
        assertTrue(first.err().contains("loop: aborted"), first.err());
        assertTrue(first.err().contains("run again to continue"), first.err());
        assertNoTemporaries(run);
        assertEquals(0, LoopFixture.command(NEVER, "run", "--status", run.toString()).code(), "the lock is released");
        Result second = LoopFixture.loop(run, NEVER, opts());
        assertEquals(0, second.code(), second.err());
        assertTrue(second.err().contains(expectInResume), "the resume " + expectInResume + ": " + second.err());
        assertSameResults(reference(), run);
        assertNoTemporaries(run);
    }

    @Test
    void anAbortDuringSelfPlayResumesFromTheCompleteShards() throws Exception {
        abortAndResume("selfplay", 1, "gen 1 selfplay: 2 shards: 0 complete (skip)"); // 1 game: no shard complete
        abortAndResume("selfplay", 3, "gen 1 selfplay: 2 shards: 1 complete (skip)"); // shard 0 (games 0, 1) complete
    }

    @Test
    void anAbortDuringTrainingResumesFromTheNewestCheckpointWithTheSameCandidate() throws Exception {
        Path run = shared.resolve("abort-train");
        Result first = LoopFixture.loop(run, stopIn("train", 3), opts("--threads", "1"));
        assertEquals(130, first.code(), first.err());
        assertTrue(first.err().contains("gen 1 train: aborted at step 3"), first.err());
        assertFalse(Files.exists(gen(run, 1).resolve("candidate.safetensors")), "no candidate from an aborted training");
        assertFalse(Files.exists(gen(run, 1).resolve("decision.json")));
        assertEquals(List.of(3L), Checkpoint.list(gen(run, 1).resolve("train")).stream().map(Checkpoint::stepOf).toList(),
                "the abort leaves a checkpoint at step 3");
        Result second = LoopFixture.loop(run, NEVER, opts());
        assertEquals(0, second.code(), second.err());
        Matcher m = START_AT.matcher(second.err());
        assertTrue(m.find() && Integer.parseInt(m.group(1)) == 3, "training resumed at step 3: " + second.err());
        assertSameResults(reference(), run);
    }

    @Test
    void anAbortDuringTheGateAndTheStrengthRecordResumesToTheSameResults() throws Exception {
        abortAndResume("gate", 1, "gen 1 gate: 2 shards");
        abortAndResume("gate", 3, "gen 1 gate: 2 shards: 1 complete (skip)");
        abortAndResume("strength", 1, "gen 0 strength vs 100 nodes");
        // the run was aborted in the strength record of generation 0 and continues with it
    }

    @Test
    void anAbortInTheBaselineMatchesResumesToTheSameResults() throws Exception {
        // the baseline is played for a promoted candidate only; this configuration promotes generation 1
        List<String> o = opts("--baseline-pairs", "4", "--seed", "7");
        Path ref = shared.resolve("baseline-ref");
        assertEquals(0, LoopFixture.loop(ref, NEVER, o).code());
        boolean played = Files.isDirectory(gen(ref, 1).resolve("gate"))
                && LoopFixture.count(gen(ref, 1).resolve("gate"), "vs-baseline-.*\\.jsonl") > 0;
        org.junit.jupiter.api.Assumptions.assumeTrue(played, "no promotion on this platform: the baseline was not played");
        Path run = shared.resolve("baseline-abort");
        List<String> single = new ArrayList<>(o);
        single.addAll(List.of("--threads", "1"));
        assertEquals(130, LoopFixture.loop(run, stopIn("baseline", 1), single).code());
        assertEquals(0, LoopFixture.loop(run, NEVER, o).code());
        assertSameResults(ref, run);
    }

    // ---- the state comes from the artefacts ----

    private Path derived(String name) throws Exception {
        Path run = shared.resolve("derived-" + name);
        deleteTree(run);
        copy(reference(), run);
        return run;
    }

    private Result resume(Path run) throws Exception {
        Result r = LoopFixture.loop(run, NEVER, opts());
        assertEquals(0, r.code(), r.err());
        assertSameResults(reference(), run);
        assertNoTemporaries(run);
        return r;
    }

    @Test
    void withoutTheDecisionOfTheLastGenerationOnlyTheDecisionIsRedone() throws Exception {
        Path run = derived("decision");
        Files.delete(gen(run, 2).resolve("decision.json"));
        Result r = resume(run);
        assertTrue(r.err().contains("gen 2 selfplay: 0 ok, 2 skip"), r.err());
        assertTrue(r.err().contains("gen 2 train: 0 ok,") && r.err().contains("nothing more to do"), r.err());
        assertTrue(r.err().contains("gen 2 gate: 0 ok, 2 skip"), r.err());
        assertTrue(r.err().contains("gen 2 decision:"), r.err());
    }

    @Test
    void withoutTheGateShardsTheGateIsPlayedAgain() throws Exception {
        Path run = derived("gate");
        deleteTree(gen(run, 2).resolve("gate"));
        Files.delete(gen(run, 2).resolve("decision.json"));
        Result r = resume(run);
        assertTrue(r.err().contains("gen 2 gate: 2 ok, 0 skip"), r.err());
    }

    @Test
    void withoutTheCandidateTheTrainingResumesFromTheNewestCheckpointThatReads() throws Exception {
        Path run = derived("candidate");
        Path train = gen(run, 2).resolve("train");
        List<Path> checkpoints = Checkpoint.list(train);
        assertTrue(checkpoints.size() >= 1);
        // the newest checkpoint is damaged: the run falls back to the one before, or to a fresh start
        Path newest = checkpoints.get(checkpoints.size() - 1);
        Files.write(newest, Arrays.copyOf(Files.readAllBytes(newest), 100));
        long expectedStart = checkpoints.size() >= 2 ? Checkpoint.stepOf(checkpoints.get(checkpoints.size() - 2)) : 0;
        Files.delete(gen(run, 2).resolve("candidate.safetensors"));
        deleteTree(gen(run, 2).resolve("gate"));
        Files.delete(gen(run, 2).resolve("decision.json"));
        Result r = resume(run);
        Matcher m = START_AT.matcher(r.err());
        assertTrue(m.find(), r.err());
        assertEquals(expectedStart, Long.parseLong(m.group(1)), r.err());
        assertTrue(r.err().contains("1 cleanup"), "the damaged checkpoint is quarantined: " + r.err());
    }

    @Test
    void withoutCandidateAndCheckpointsTheGenerationIsTrainedAgainFromTheBestNetwork() throws Exception {
        Path run = derived("retrain");
        deleteTree(gen(run, 2).resolve("train"));
        Files.delete(gen(run, 2).resolve("candidate.safetensors"));
        deleteTree(gen(run, 2).resolve("gate"));
        Files.delete(gen(run, 2).resolve("decision.json"));
        Result r = resume(run);
        assertTrue(r.err().contains("start at step 0"), r.err());
    }

    @Test
    void aMissingShardOfTheCurrentGenerationIsPlayedAgainAndOneOfAnEarlierGenerationIsAnError() throws Exception {
        Path run = derived("shard");
        Path shard = LoopFixture.files(gen(run, 2).resolve("samples")).stream()
                .filter(f -> f.getFileName().toString().endsWith(".safetensors")).findFirst().orElseThrow();
        Files.delete(shard);
        Files.delete(gen(run, 2).resolve("decision.json"));
        Result r = resume(run);
        assertTrue(r.err().contains("gen 2 selfplay: 1 ok, 1 skip"), r.err());

        // a shard of generation 1 is gone: generation 2 cannot be trained on a window with a hole in it
        Path broken = derived("hole");
        Files.delete(LoopFixture.files(gen(broken, 1).resolve("samples")).stream()
                .filter(f -> f.getFileName().toString().endsWith(".safetensors")).findFirst().orElseThrow());
        Files.delete(gen(broken, 2).resolve("candidate.safetensors"));
        deleteTree(gen(broken, 2).resolve("train"));
        deleteTree(gen(broken, 2).resolve("gate"));
        Files.delete(gen(broken, 2).resolve("decision.json"));
        Result hole = LoopFixture.loop(broken, NEVER, opts());
        assertEquals(1, hole.code(), hole.err());
        assertTrue(hole.err().contains("generation 1") && hole.err().contains("shards"), hole.err());
    }

    @Test
    void aCorruptShardIsQuarantinedAndPlayedAgain() throws Exception {
        Path run = derived("corrupt");
        Path shard = LoopFixture.files(gen(run, 2).resolve("samples")).stream()
                .filter(f -> f.getFileName().toString().endsWith(".safetensors")).findFirst().orElseThrow();
        Files.write(shard, Arrays.copyOf(Files.readAllBytes(shard), 200));
        Files.delete(gen(run, 2).resolve("decision.json"));
        Result r = resume(run);
        assertTrue(r.err().contains("gen 2 selfplay: 1 ok, 1 skip, 0 error, 1 cleanup"), r.err());
        assertTrue(Files.exists(shard.resolveSibling(shard.getFileName() + ".corrupt")));
    }

    @Test
    void aShardOfAnotherNetworkIsAnErrorNotSilentlyUsed() throws Exception {
        Path run = derived("othernet");
        Files.delete(gen(run, 2).resolve("decision.json"));
        // the first shard claims another network
        Path shard = LoopFixture.files(gen(run, 2).resolve("samples")).stream()
                .filter(f -> f.getFileName().toString().endsWith(".safetensors")).findFirst().orElseThrow();
        SampleFile.Contents c = SampleFile.read(shard);
        Map<String, String> md = new java.util.LinkedHashMap<>(c.metadata());
        md.put("network_sha256", "0".repeat(64));
        SampleFile.write(shard, c.tensors(), md);
        Result r = LoopFixture.loop(run, NEVER, opts());
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().contains("written by the network"), r.err());
    }

    @Test
    void leftoverTemporaryFilesAreRemoved() throws Exception {
        Path run = derived("tmp");
        Files.writeString(run.resolve("run.json.tmp"), "x");
        Files.writeString(gen(run, 2).resolve("candidate.safetensors.abc123.tmp"), "x");
        Files.writeString(gen(run, 2).resolve("decision.json.tmp"), "x");
        Files.writeString(gen(run, 2).resolve("samples").resolve("samples-s1-00000.safetensors.tmp.999"), "x");
        Files.writeString(gen(run, 2).resolve("gate").resolve("vs-best-000.jsonl.tmp.999"), "x");
        Files.delete(gen(run, 2).resolve("decision.json"));
        Result r = resume(run);
        assertTrue(r.err().contains("cleanup"), r.err());
    }

    @Test
    void aGateShardOfAnotherMatchIsRedone() throws Exception {
        Path run = derived("othermatch");
        Path shard = gen(run, 2).resolve("gate").resolve("vs-best-000.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(shard));
        lines.set(0, lines.get(0).replace("cand=", "cand=0000"));
        Files.write(shard, lines);
        Files.delete(gen(run, 2).resolve("decision.json"));
        Result r = resume(run);
        assertTrue(r.err().contains("gen 2 gate: 1 ok, 1 skip, 0 error, 1 cleanup"), r.err());
    }

    // ---- results do not depend on threads ----

    @Test
    void theResultsDoNotDependOnTheNumberOfThreads() throws Exception {
        Path run = shared.resolve("threads");
        Result r = LoopFixture.loop(run, NEVER, opts("--threads", "1", "--train-threads", "3"));
        assertEquals(0, r.code(), r.err());
        assertSameResults(reference(), run);
    }

    @Test
    void cadencesDoNotChangeTheResults() throws Exception {
        Path run = shared.resolve("cadence");
        Result r = LoopFixture.loop(run, NEVER, opts("--checkpoint-steps", "1", "--keep", "100", "--eval-samples", "7",
                "--proxy-every", "0", "--progress-seconds", "0.01", "--strength-seed", "99"));
        assertEquals(0, r.code(), r.err());
        // the strength seed is no configuration, but it changes the strength record: leave the shards of it out
        Map<String, Object> a = results(reference());
        Map<String, Object> b = results(run);
        for (String key : a.keySet()) {
            if (key.contains("/strength/")) {
                continue;
            }
            assertTrue(java.util.Objects.deepEquals(a.get(key), b.get(key)), "differs: " + key);
        }
        assertTrue(Checkpoint.list(gen(run, 1).resolve("train")).size() >= 3, "a checkpoint at every step, all kept");
    }

    // ---- an independent trainer reproduces every candidate ----

    @Test
    void aLearningRateDecayIsPartOfTheRunAndTheCandidateFollowsIt() throws Exception {
        Path run = shared.resolve("decay");
        Result r = LoopFixture.loop(run, NEVER, opts("--generations", "1", "--lr-final", "1e-4"));
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.readString(run.resolve("run.json")).contains("\"lr_final\":1.0E-4"), "named in run.json when set");
        assertFalse(Files.readString(reference().resolve("run.json")).contains("lr_final"), "and not when unset");
        Parameters expected = train(run, 1, 100_000, 7, 1e-4);
        Parameters actual = Parameters.load(gen(run, 1).resolve("candidate.safetensors"));
        boolean differsFromConstant = false;
        Parameters constant = train(run, 1, 100_000, 7);
        for (Map.Entry<String, Tensor> e : expected.tensors().entrySet()) {
            assertArrayEquals(e.getValue().data(), actual.get(e.getKey()).data(), "the decay of the loop is the oracle's: " + e.getKey());
            differsFromConstant |= !Arrays.equals(e.getValue().data(), constant.get(e.getKey()).data());
        }
        assertTrue(differsFromConstant, "the oracle is not vacuous: the decay changes the network (more than 3 steps)");
    }

    /**
     * Trains generation {@code g} the way docs/training.md says and without the loop's code: the best network's
     * parameters, a fresh AdamW optimiser, warm-up and then constant, the replay window of whole generations without
     * the held-out games, steps = ceil(R * new samples / batch), the batches of generation g from step 0.
     */
    private static Parameters train(Path run, int g, int window, long seed) throws Exception {
        return train(run, g, window, seed, 0);
    }

    /** The same with a cosine decay to {@code lrFinal} over the planned steps (0: constant). */
    private static Parameters train(Path run, int g, int window, long seed, double lrFinal) throws Exception {
        int best = 0;
        for (int gg = 1; gg < g; gg++) {
            best = decision(run, gg).get("decision").equals("promoted") ? gg : best;
        }
        Parameters parameters = Parameters.load(gen(run, best).resolve("candidate.safetensors"));
        ReplayBuffer buffer = new ReplayBuffer(2, window, true, PlaneSet.CLASSIC1);
        for (int gg = 1; gg <= g; gg++) {
            buffer.addGeneration(gg, Loop.shards(gen(run, gg).resolve("samples")), (m, id) -> !Loop.heldOut(m, id, 0.25, 4));
        }
        long steps = Math.max(1, (long) Math.ceil(1.0 * buffer.size(g) / 16));
        Optimizer optimizer = new Optimizer(parameters, Optimizer.Settings.adamw());
        Path dir = Files.createTempDirectory(shared, "oracle");
        LearningRateSchedule schedule = lrFinal == 0 ? new LearningRateSchedule(0.003, 100, 1000, 0.1, 2, List.of())
                : new LearningRateSchedule(0.003, 100, 1000, 0.1, 2, List.of(), steps, lrFinal);
        TrainingRun.Setup setup = new TrainingRun.Setup(parameters, optimizer, schedule, buffer, 16, 8, 1, 1.0, seed, g, steps);
        TrainingRun.Checkpointing checkpointing = new TrainingRun.Checkpointing(dir, 1_000_000, Long.MAX_VALUE, 1, "0".repeat(64));
        TrainingRun.Reporting reporting = new TrainingRun.Reporting(List.of(), null, Long.MAX_VALUE);
        new TrainingRun(setup, checkpointing, reporting, TrainingRun.Clock.system(), () -> false).run();
        return parameters;
    }

    @Test
    void everyCandidateEqualsTheOneOfAnIndependentTrainerWithAFreshOptimiser() throws Exception {
        Path run = reference();
        for (int g = 1; g <= 2; g++) {
            Parameters expected = train(run, g, 100_000, 7);
            Parameters actual = Parameters.load(gen(run, g).resolve("candidate.safetensors"));
            for (Map.Entry<String, Tensor> e : expected.tensors().entrySet()) {
                assertArrayEquals(e.getValue().data(), actual.get(e.getKey()).data(), "generation " + g + ", " + e.getKey());
            }
        }
        // the oracle is not vacuous: the candidate of generation 2 differs from its parent and from one that kept the
        // optimiser steps of generation 1
        Parameters parent = Parameters.load(gen(run, 0).resolve("candidate.safetensors"));
        Parameters candidate = Parameters.load(gen(run, 1).resolve("candidate.safetensors"));
        boolean differs = false;
        for (Map.Entry<String, Tensor> e : parent.tensors().entrySet()) {
            differs |= !Arrays.equals(e.getValue().data(), candidate.get(e.getKey()).data());
        }
        assertTrue(differs, "training changed the network");
    }

    @Test
    void theFirstCheckpointOfAGenerationHasTheStepCountOfThatGenerationAlone() throws Exception {
        // a fresh optimiser per generation: its step count is Adam's t, the schedule index and the sampler step
        Path run = shared.resolve("steps");
        assertEquals(0, LoopFixture.loop(run, NEVER, opts("--checkpoint-steps", "1", "--keep", "1000")).code());
        for (int g = 1; g <= 2; g++) {
            List<Path> all = Checkpoint.list(gen(run, g).resolve("train"));
            assertEquals(1, Checkpoint.stepOf(all.get(0)), "the first checkpoint of generation " + g + " is at step 1");
            Checkpoint.Loaded first = Checkpoint.read(all.get(0));
            assertEquals(1, first.step());
            assertEquals(g, first.generation());
            long planned = Long.parseLong(Parameters.load(gen(run, g).resolve("candidate.safetensors")).extraMetadata().get("step"));
            assertEquals(planned, Checkpoint.stepOf(all.get(all.size() - 1)), "counted from 0 in every generation");
        }
    }

    // ---- the replay window ----

    @Test
    void theReplayWindowDropsWholeGenerationsAtTheExactBoundary() throws Exception {
        // the training samples of generation 1 and 2, as the reference run loaded them
        Path ref = reference();
        long[] n = new long[3];
        for (int g = 1; g <= 2; g++) {
            n[g] = Long.parseLong(Parameters.load(gen(ref, g).resolve("candidate.safetensors")).extraMetadata().get("new_samples"));
        }
        assertTrue(n[1] > 50 && n[2] > 50, Arrays.toString(n));
        for (long[] c : new long[][] {{n[1] + n[2], 1}, {n[1] + n[2] - 1, 0}, {n[1] + n[2] + 1, 1}, {n[2], 0}, {n[2] - 1, 0}}) {
            Path run = shared.resolve("window-" + c[0]);
            Result r = LoopFixture.loop(run, NEVER, opts("--window", Long.toString(c[0]), "--strength-every", "0"));
            assertEquals(0, r.code(), r.err());
            Matcher g2 = Pattern.compile("gen 2 load: .* window (\\d+) samples in generations \\[([0-9, ]*)\\]").matcher(r.err());
            assertTrue(g2.find(), r.err());
            assertEquals(c[1] == 1 ? "1, 2" : "2", g2.group(2), "window " + c[0] + " with generations of " + n[1] + " and " + n[2]
                    + " samples: more than the window drops the oldest whole generation, exactly the window does not");
            assertEquals(c[1] == 1 ? n[1] + n[2] : n[2], Long.parseLong(g2.group(1)));
        }
    }

    private static String windowOf(Result r, int g) {
        Matcher m = Pattern.compile("gen " + g + " load: .* window (\\d+) samples in generations \\[([0-9, ]*)\\]").matcher(r.err());
        assertTrue(m.find(), r.err());
        return m.group(2) + "/" + m.group(1);
    }

    @Test
    void aTeacherDirectoryJoinsTheWindowAsGenerationZeroAndAgesOutWithTheWindow() throws Exception {
        Path teacher = Files.createDirectory(shared.resolve("teacher"));
        SyntheticShards.write(teacher, "samples-s1-0000.safetensors", 1, 0, 20, 8, 21);
        SyntheticShards.write(teacher, "samples-s1-0001.safetensors", 1, 20, 20, 8, 22);
        List<String> base = List.of("--teacher", teacher.toString(), "--teacher-files", "1", "--generations", "1",
                "--strength-every", "0", "--baseline-pairs", "0");
        Path run = shared.resolve("withteacher");
        List<String> big = new ArrayList<>(base);
        big.addAll(List.of("--window", "100000"));
        Result r = LoopFixture.loop(run, NEVER, opts(big.toArray(String[]::new)));
        assertEquals(0, r.code(), r.err());
        long n1 = Long.parseLong(Parameters.load(gen(run, 1).resolve("candidate.safetensors")).extraMetadata().get("new_samples"));
        assertEquals("0, 1/" + (160 + n1), windowOf(r, 1), "the teacher shard (20 games of 8 samples) is generation 0 of the window");
        assertTrue(Files.readString(run.resolve("run.json")).contains("\"teacher_files\":1"));
        // exactly the window: nothing is dropped; one sample less: the oldest whole generation, the teacher data, goes
        for (long window : new long[] {160 + n1, 160 + n1 - 1}) {
            List<String> o = new ArrayList<>(base);
            o.addAll(List.of("--window", Long.toString(window)));
            Result w = LoopFixture.loop(shared.resolve("withteacher-" + window), NEVER, opts(o.toArray(String[]::new)));
            assertEquals(0, w.code(), w.err());
            assertEquals(window == 160 + n1 ? "0, 1/" + (160 + n1) : "1/" + n1, windowOf(w, 1), "window " + window);
        }
        // the run with another teacher selection is another configuration
        List<String> two = new ArrayList<>(base);
        two.addAll(List.of("--window", "100000", "--teacher-files", "2"));
        Result other = LoopFixture.loop(run, NEVER, opts(two.toArray(String[]::new)));
        assertEquals(1, other.code(), other.err());
    }

    // ---- the lock ----

    @Test
    void aSecondRunOnTheSameDirectoryExitsWithSeventyFiveWhileTheFirstRuns() throws Exception {
        Path run = shared.resolve("lock");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Loop.StopHook block = (phase, done) -> {
            if (phase.equals("selfplay")) {
                inside.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
            return false;
        };
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Result> first = pool.submit(() -> LoopFixture.loop(run, block, opts()));
            assertTrue(inside.await(60, TimeUnit.SECONDS));
            Result second = LoopFixture.loop(run, NEVER, opts());
            assertEquals(75, second.code(), second.err());
            assertTrue(second.err().contains("running already"), second.err());
            Result status = LoopFixture.command(NEVER, "run", "--status", run.toString());
            assertEquals(75, status.code(), status.out());
            assertTrue(status.out().contains("running already: pid="), status.out());
            release.countDown();
            assertEquals(130, first.get(60, TimeUnit.SECONDS).code());
        }
        Result done = LoopFixture.loop(run, NEVER, opts());
        assertEquals(0, done.code(), done.err());
        assertSameResults(reference(), run);
        Result notRunning = LoopFixture.command(NEVER, "run", "--status", run.toString());
        assertEquals(0, notRunning.code());
        assertTrue(notRunning.out().startsWith("not running"), notRunning.out());
    }

    // ---- the strength record ----

    @Test
    void theStrengthRecordFollowsTheCadenceAndHasASeparateStep() throws Exception {
        Path run = shared.resolve("strength");
        Result r = LoopFixture.loop(run, NEVER, opts("--strength-every", "0"));
        assertEquals(0, r.code(), r.err());
        for (int g = 0; g <= 2; g++) {
            assertEquals(0, LoopFixture.count(gen(run, g).resolve("strength"), ".*"), "no strength record on request");
        }
        assertTrue(events(run, "metric").stream().noneMatch(m -> "elo".equals(m.get("kind"))));
        // the separate step measures the best network and is resumable and idempotent
        Result step = LoopFixture.command(NEVER, "strength", run.toString(), "--ladder", "100", "--strength-pairs", "2",
                "--threads", "2", "--progress-seconds", "0.05");
        assertEquals(0, step.code(), step.err());
        int best = 0;
        for (int g = 1; g <= 2; g++) {
            best = decision(run, g).get("decision").equals("promoted") ? g : best;
        }
        assertEquals(1, LoopFixture.count(gen(run, best).resolve("strength"), "ab100-.*\\.jsonl"), "2 pairs: one shard");
        assertTrue(step.err().contains("strength (best = generation " + best + "): vs 100 nodes: Elo"), step.err());
        assertTrue(events(run, "metric").stream().anyMatch(m -> "elo".equals(m.get("kind"))));
        Result again = LoopFixture.command(NEVER, "strength", run.toString(), "--ladder", "100", "--strength-pairs", "2",
                "--threads", "2");
        assertEquals(0, again.code(), again.err());
        assertTrue(again.err().contains("nothing more to do"));

        // every second generation: generations 0 and 2 only
        Path every = shared.resolve("strength-every");
        assertEquals(0, LoopFixture.loop(every, NEVER, opts("--strength-every", "2")).code());
        List<Map<String, String>> elo = events(every, "metric").stream().filter(m -> "elo".equals(m.get("kind"))).toList();
        assertEquals(List.of("0", "2"), elo.stream().map(m -> m.get("gen")).toList());
    }

    // ---- the monitor ----

    @Test
    void theMonitorAccountsForEveryCategoryAndReportsTheirShares() throws Exception {
        Path run = reference();
        RunMonitor.Report r = RunMonitor.report(run);
        assertTrue(r.genWallNanos() > 0);
        int promoted = 0;
        for (int g = 1; g <= 2; g++) {
            promoted += decision(run, g).get("decision").equals("promoted") ? 1 : 0;
        }
        assertEquals(promoted, r.generationsDone(), "the mean counts the promoted generations after generation 0 only");
        for (RunMonitor.Category c : RunMonitor.Category.values()) {
            assertTrue(r.categoryWallNanos(c) > 0, "time recorded for " + c);
        }
        long sum = 0;
        for (RunMonitor.Category c : RunMonitor.Category.values()) {
            sum += r.categoryWallNanos(c);
        }
        assertTrue(sum <= r.genWallNanos() * 1.02, "the categories are disjoint: " + sum + " of " + r.genWallNanos());
        Result report = LoopFixture.command(NEVER, "monitor-report", run.toString());
        assertEquals(0, report.code(), report.err());
        String text = report.out();
        assertTrue(text.contains("loop shares of"), text);
        for (String name : List.of("selfplay", "train", "gate", "strength", "checkpoint", "progress")) {
            assertTrue(text.matches("(?s).*loop shares of [^\\n]*" + name + " \\d+\\.\\d%.*"), name + " share in: " + text);
        }
        assertTrue(text.contains("Elo estimate") && text.contains("strength proxies") && text.contains("checkpoint"), text);
        assertEquals(promoted > 0, text.contains("one Elo estimate costs"), "the Elo frequency in generations: " + text);
    }

    @Test
    void thePhaseEventsCountOnlyTheWorkOfTheProcessAndSoDoTheEtas() throws Exception {
        Path run = shared.resolve("eta");
        assertEquals(130, LoopFixture.loop(run, stopIn("selfplay", 3), opts("--threads", "1")).code());
        Result second = LoopFixture.loop(run, NEVER, opts());
        assertEquals(0, second.code(), second.err());
        List<Map<String, String>> selfplay = events(run, "phase").stream()
                .filter(m -> m.get("phase").equals("selfplay") && m.get("gen").equals("1")).toList();
        assertEquals(2, selfplay.size(), "one phase line per process");
        assertEquals("false", selfplay.get(0).get("complete"));
        assertEquals("2", selfplay.get(1).get("units"),
                "the resumed process did the 2 games of the second shard, not all 4 games of the generation");
        // the progress lines of the resumed phase count only the items still to do
        assertTrue(second.err().contains("gen 1 selfplay: 1/2 items") || second.err().contains("gen 1 selfplay: 2/2 items"),
                second.err());
        // ETA of the phase, the generation and the run on every progress line
        for (String line : second.err().lines().filter(l -> l.contains("items") && l.contains("/s")).toList()) {
            if (line.contains(" ETA ") && line.contains("elapsed")) {
                assertTrue(line.contains("gen ETA") && line.contains("run ETA"), line);
            }
        }
        // and the aborted generation is accounted: the gen line of the aborted process is not 'done'
        List<Map<String, String>> gens = events(run, "gen");
        assertTrue(gens.stream().anyMatch(m -> "0".equals(m.get("done")) && "1".equals(m.get("gen"))), gens.toString());
    }

    @Test
    void progressLinesNameThePhaseTheGenerationAndTheEtas() throws Exception {
        String err = LoopFixture.loop(shared.resolve("lines"), NEVER, opts()).err();
        for (String phase : List.of("selfplay", "train", "gate", "strength")) {
            assertTrue(Pattern.compile("gen [12] " + phase + "[^\\n]*").matcher(err).find(), phase + " in " + err);
        }
        assertTrue(err.contains("gen 1 proxies at step"), "proxies at a checkpoint: " + err);
        assertTrue(err.contains("run ETA") && err.contains("more generation"), err);
        assertTrue(Pattern.compile("gen 1 selfplay phase: 4 done in").matcher(err).find(), err);
        assertTrue(err.contains("gen 2 decision:"), err);
        assertNotEquals(-1, err.indexOf("loop: 3 generations decided"));
    }

    // ---- a rejected generation, and the other findings of the review of block 2h ----

    /** Generation 1 loses every pair of its gate, generation 2 wins every one; the baseline matches are all even. */
    private static final Loop.PairFake REJECT_THEN_PROMOTE = (prefix, g, pair) -> switch (prefix) {
        case "vs-best" -> LoopFixture.pairLine(pair, g == 1 ? 0.0 : 1.0);
        case "vs-baseline" -> LoopFixture.pairLine(pair, 0.5);
        default -> null;
    };

    private static String sha(Path file) throws IOException {
        return Gen0.sha256Hex(Files.readAllBytes(file));
    }

    private static Path rejectedRun;
    private static Result rejectedResult;

    /** The run in which generation 1 is rejected and generation 2 promoted, made once. */
    private static synchronized Path rejected() throws Exception {
        if (rejectedRun == null) {
            Path run = shared.resolve("rejected");
            rejectedResult = LoopFixture.loop(run, NEVER, opts(), REJECT_THEN_PROMOTE);
            assertEquals(0, rejectedResult.code(), rejectedResult.err());
            rejectedRun = run;
        }
        return rejectedRun;
    }

    @Test
    void aRejectedGenerationIsFollowedByOneThatPlaysFromTheBestNetworkNotFromTheRejectedCandidate() throws Exception {
        Path run = rejected();
        assertEquals("rejected", decision(run, 1).get("decision"));
        assertEquals("true", decision(run, 1).get("baseline_skipped"), "a candidate that lost the gate gets no baseline match");
        assertEquals("0", decision(run, 1).get("best_before"));
        assertEquals("promoted", decision(run, 2).get("decision"));
        assertEquals("0", decision(run, 2).get("best_before"), "the best network before generation 2 is still generation 0");
        String sha0 = sha(gen(run, 0).resolve("candidate.safetensors"));
        String sha1 = sha(gen(run, 1).resolve("candidate.safetensors"));
        assertNotEquals(sha0, sha1, "the rejected candidate is another network");
        int shards = 0;
        for (Path shard : LoopFixture.files(gen(run, 2).resolve("samples"))) {
            if (shard.getFileName().toString().endsWith(".safetensors")) {
                shards++;
                assertEquals(sha0, SampleFile.read(shard).metadata().get("network_sha256"),
                        "generation 2 is played by generation 0, not by the rejected candidate: " + shard);
            }
        }
        assertEquals(2, shards);
        assertTrue(rejectedResult.err().contains("gen 2 selfplay: best network = generation 0"), rejectedResult.err());
        assertTrue(rejectedResult.err().contains("gen 1: rejected; best = generation 0"), rejectedResult.err());
        assertTrue(rejectedResult.err().contains("gen 2: promoted; best = generation 2"), rejectedResult.err());
    }

    @Test
    void theCandidateAfterARejectionStartsFromTheBestNetworkAndTheReplayWindowKeepsTheRejectedSamples() throws Exception {
        Path run = rejected();
        Map<String, String> md1 = Parameters.load(gen(run, 1).resolve("candidate.safetensors")).extraMetadata();
        Map<String, String> md2 = Parameters.load(gen(run, 2).resolve("candidate.safetensors")).extraMetadata();
        assertEquals("0", md1.get("parent"));
        assertEquals("0", md2.get("parent"), "generation 2 was trained from generation 0, the best network");
        // bit for bit what an independent trainer makes of generation 0 and the samples of generations 1 and 2
        Parameters expected = train(run, 2, 100_000, 7);
        Parameters actual = Parameters.load(gen(run, 2).resolve("candidate.safetensors"));
        for (Map.Entry<String, Tensor> e : expected.tensors().entrySet()) {
            assertArrayEquals(e.getValue().data(), actual.get(e.getKey()).data(), e.getKey());
        }
        // the samples of the rejected generation stay in the window
        long n1 = Long.parseLong(md1.get("new_samples"));
        long n2 = Long.parseLong(md2.get("new_samples"));
        assertEquals("1, 2/" + (n1 + n2), windowOf(rejectedResult, 2));
    }

    @Test
    void theStrengthRecordOfARejectedGenerationIsThatOfTheBestNetworkAndTheBaselinesLieInTheirOwnGenerations() throws Exception {
        Path run = rejected();
        assertFalse(Files.exists(gen(run, 1).resolve("strength")), "no record for a network that is not the best");
        assertTrue(LoopFixture.count(gen(run, 0).resolve("strength"), "ab100-.*\\.jsonl") >= 1);
        assertTrue(LoopFixture.count(gen(run, 2).resolve("strength"), "ab100-.*\\.jsonl") >= 1);
        Map<String, String> bestOf = new TreeMap<>();
        for (Map<String, String> m : events(run, "metric")) {
            if ("elo".equals(m.get("kind"))) {
                bestOf.put(m.get("gen"), m.get("best"));
            }
        }
        assertEquals(Map.of("0", "0", "1", "0", "2", "2"), bestOf, "the Elo line of generation g measures the best network of g");
        // no baseline in the rejected generation; the best network's one in its own directory, the candidate's in its own
        assertEquals(0, LoopFixture.count(gen(run, 1).resolve("gate"), "vs-baseline-.*"));
        String sha0 = sha(gen(run, 0).resolve("candidate.safetensors")).substring(0, 16);
        String sha2 = sha(gen(run, 2).resolve("candidate.safetensors")).substring(0, 16);
        assertTrue(Files.readAllLines(gen(run, 0).resolve("gate").resolve("vs-baseline-000.jsonl")).get(0).contains("net=" + sha0));
        assertTrue(Files.readAllLines(gen(run, 2).resolve("gate").resolve("vs-baseline-000.jsonl")).get(0).contains("net=" + sha2));
        assertEquals(0, LoopFixture.count(gen(run, 0).resolve("gate"), "vs-best-.*"), "generation 0 has no gate of its own");
    }

    @Test
    void generationZeroAndARejectedGenerationAreNoCompletedGenerationsInTheReport() throws Exception {
        Path run = rejected();
        RunMonitor.Report report = RunMonitor.report(run);
        assertEquals(1, report.generationsDone(), "only generation 2 is a finished, promoted generation after generation 0");
        List<Map<String, String>> gens = events(run, "gen");
        assertEquals(List.of("0", "1", "2"), gens.stream().map(m -> m.get("gen")).toList());
        assertEquals(List.of("0", "0", "1"), gens.stream().map(m -> m.get("full")).toList());
        long wall = 0;
        for (Map<String, String> m : gens) {
            wall += Long.parseLong(m.get("wall_ns"));
        }
        assertEquals(wall, report.genWallNanos(), "the time of all of them is in the shares");
        assertEquals(Long.parseLong(gens.get(2).get("wall_ns")), report.countedGenWallNanos());
    }

    /** A match line as the matches write it, without the CPU times. */
    private static Map<String, String> withoutCpu(Map<String, String> line) {
        Map<String, String> m = new TreeMap<>(line);
        m.remove("s_cpu_ns");
        m.remove("o_cpu_ns");
        return m;
    }

    /** The pairs of a match, played again here: the network against a rung, from the openings of {@code seed}. */
    private static List<Map<String, String>> replayed(Path run, Parameters network, int rung, long seed, int openingPlies,
            int pairs) throws IOException {
        LoopConfig cfg = LoopConfig.fromJson(RunMonitor.parse(Files.readString(run.resolve("run.json"))));
        LongFunction<Player> subject = NetPlayers.factory(cfg.evalPlayer(), network);
        PlayerSpec spec = PlayerSpec.parse(StrengthEstimate.spec(cfg.ladderSpec(), rung));
        List<Map<String, String>> out = new ArrayList<>();
        for (int pair = 0; pair < pairs; pair++) {
            MctsMatch.PairLine line = MctsMatch.playPair(StrengthEstimate.opening(RuleSet.defaults(2), seed, pair, openingPlies),
                    pair, subject, s -> spec.create(), (seed * 1_000_003L + pair) * 2);
            out.add(withoutCpu(RunMonitor.parse(RunMonitor.json(line.fields()))));
        }
        return out;
    }

    private static List<Map<String, String>> shardPairs(Path shard) throws IOException {
        List<String> lines = Files.readAllLines(shard);
        List<Map<String, String>> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            out.add(withoutCpu(RunMonitor.parse(line)));
        }
        return out;
    }

    @Test
    void theStrengthMatchesUseTheStrengthSeedTheBaselineMatchesTheBaselineSeedAndEachNetworkHasItsOwnBaselineShards()
            throws Exception {
        // every candidate beats the best network at the gate, so that the real baseline matches are played
        Path run = shared.resolve("seeds");
        Result r = LoopFixture.loop(run, NEVER, opts("--generations", "1"),
                (prefix, g, pair) -> prefix.equals("vs-best") ? LoopFixture.pairLine(pair, 1.0) : null);
        assertEquals(0, r.code(), r.err());
        LoopConfig cfg = LoopConfig.fromJson(RunMonitor.parse(Files.readString(run.resolve("run.json"))));
        assertNotEquals(cfg.baselineSeed(), 3001L, "the two seeds differ, or this test cannot tell them apart");
        for (int g = 0; g <= 1; g++) {
            Parameters network = Parameters.load(gen(run, g).resolve("candidate.safetensors"));
            String sha = sha(gen(run, g).resolve("candidate.safetensors")).substring(0, 16);
            Path baseline = gen(run, g).resolve("gate").resolve("vs-baseline-000.jsonl");
            assertTrue(Files.readAllLines(baseline).get(0).contains("net=" + sha + ";"), "the baseline of generation " + g);
            assertTrue(Files.readAllLines(baseline).get(0).contains("seed=" + cfg.baselineSeed() + ";"), baseline.toString());
            assertEquals(replayed(run, network, cfg.baselineRung(), cfg.baselineSeed(), cfg.gateOpening(), 2),
                    shardPairs(baseline), "the pairs of the baseline of generation " + g + " are those of the baseline seed");
            Path strength = gen(run, g).resolve("strength").resolve("ab100-000.jsonl");
            if (Files.exists(strength)) {
                assertTrue(Files.readAllLines(strength).get(0).contains("seed=3001;"), strength.toString());
                assertEquals(replayed(run, network, 100, 3001, StrengthEstimate.OPENING_PLIES, 2), shardPairs(strength),
                        "the pairs of the strength record of generation " + g + " are those of the strength seed");
            }
        }
        assertTrue(Files.exists(gen(run, 0).resolve("strength").resolve("ab100-000.jsonl")), "generation 0 is measured");
    }

    @Test
    void theHeldOutBufferOfAGenerationHoldsThatGenerationsHeldOutGamesAlone() throws Exception {
        Path run = rejected();
        Matcher m = Pattern.compile("gen 2 load: (\\d+) new training samples \\((\\d+) held out\\)").matcher(rejectedResult.err());
        assertTrue(m.find(), rejectedResult.err());
        ReplayBuffer own = new ReplayBuffer(2, Integer.MAX_VALUE, false, PlaneSet.CLASSIC1);
        own.addGeneration(2, Loop.shards(gen(run, 2).resolve("samples")), (md, id) -> Loop.heldOut(md, id, 0.25, 4));
        ReplayBuffer first = new ReplayBuffer(2, Integer.MAX_VALUE, false, PlaneSet.CLASSIC1);
        first.addGeneration(1, Loop.shards(gen(run, 1).resolve("samples")), (md, id) -> Loop.heldOut(md, id, 0.25, 4));
        assertTrue(own.size() > 0 && first.size() > 0);
        assertEquals(own.size(), Long.parseLong(m.group(2)), "the held-out samples of generation 2, not those of generation 1 too");
    }

    @Test
    void aShardWithTheGamesOfAnotherShardIsCorruptNotComplete() throws Exception {
        // same settings, same network, the right number of games, but the ids of shard 1 under the name of shard 0
        Path run = derived("gameids");
        List<Path> shards = LoopFixture.files(gen(run, 2).resolve("samples")).stream()
                .filter(f -> f.getFileName().toString().endsWith(".safetensors")).toList();
        assertEquals(2, shards.size());
        Files.copy(shards.get(1), shards.get(0), StandardCopyOption.REPLACE_EXISTING);
        Files.delete(gen(run, 2).resolve("decision.json"));
        Result r = resume(run);
        assertTrue(r.err().contains("gen 2 selfplay: 1 ok, 1 skip, 0 error, 1 cleanup"), r.err());
        assertTrue(Files.exists(shards.get(0).resolveSibling(shards.get(0).getFileName() + ".corrupt")));
    }

    @Test
    void theTrainingPhaseLineOfAResumedProcessCountsTheStepsItDidItself() throws Exception {
        Path run = shared.resolve("eta-train");
        assertEquals(130, LoopFixture.loop(run, stopIn("train", 3), opts("--threads", "1")).code());
        assertEquals(0, LoopFixture.loop(run, NEVER, opts()).code());
        long planned = Long.parseLong(Parameters.load(gen(run, 1).resolve("candidate.safetensors")).extraMetadata().get("step"));
        assertTrue(planned > 3, "the plan is longer than the abort: " + planned);
        List<Map<String, String>> train = events(run, "phase").stream()
                .filter(m -> m.get("phase").equals("train") && m.get("gen").equals("1")).toList();
        assertEquals(2, train.size(), "one phase line per process");
        assertEquals("3", train.get(0).get("units"));
        assertEquals("false", train.get(0).get("complete"));
        assertEquals(Long.toString(planned - 3), train.get(1).get("units"),
                "the resumed process did the steps after 3, not the steps up to the end counted from 0");
        assertEquals("true", train.get(1).get("complete"));
    }

    @Test
    void theEtaLeavesOutBaselineAndStrengthOfTheRunningGenerationUntilTheGateScoreIsAboveOneHalfAndSaysSo() throws Exception {
        Path run = shared.resolve("etanote");
        Result r = LoopFixture.loop(run, NEVER, opts(), REJECT_THEN_PROMOTE);
        assertEquals(0, r.code(), r.err());
        Pattern phaseOf = Pattern.compile("^gen (?<g>\\d+) (?<phase>selfplay|train|gate|baseline|strength)\\b");
        int before = 0;
        int after = 0;
        for (String line : r.err().lines().filter(l -> l.contains("gen ETA ")).toList()) {
            Matcher m = phaseOf.matcher(line);
            assertTrue(m.find(), line);
            boolean note = line.contains(Eta.PENDING_NOTE);
            switch (m.group("phase")) {
                case "selfplay", "train", "gate" -> {
                    assertTrue(note, "before the gate has decided: " + line);
                    assertTrue(line.contains("+, run ETA"), "a lower bound is marked: " + line);
                    before++;
                }
                default -> {
                    assertFalse(note, "the gate showed the candidate above one half: " + line);
                    after++;
                }
            }
        }
        assertTrue(before > 0, "lines before the gate: " + r.err());
        assertTrue(after > 0, "lines of the baseline and strength phases: " + r.err());
    }

    @Test
    void aGenerationOfFourGamesHoldsOutOneGameAtAnyHoldoutFractionAndSeed() throws Exception {
        // review finding: with --holdout 0.1 and 4 games, 5 of 6 seeds had no held-out sample and the run ended with 1
        for (int seed = 1; seed <= 4; seed++) {
            Path run = shared.resolve("holdout01-" + seed);
            Result r = LoopFixture.loop(run, NEVER, opts("--holdout", "0.1", "--generations", "1", "--seed", Integer.toString(seed),
                    "--strength-every", "0", "--baseline-pairs", "0", "--gate-pairs", "2"));
            assertEquals(0, r.code(), "seed " + seed + ": " + r.err());
            Matcher m = Pattern.compile("gen 1 load: (\\d+) new training samples \\((\\d+) held out\\)").matcher(r.err());
            assertTrue(m.find() && Long.parseLong(m.group(2)) > 0 && Long.parseLong(m.group(1)) > 0, r.err());
        }
    }
}
