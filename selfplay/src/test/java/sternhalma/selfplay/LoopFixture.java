package sternhalma.selfplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;

/**
 * Tiny runs of the training loop for the tests: a TINY classical network of random weights as generation 0 and budgets
 * of a few games, a few simulations and a few pairs, so that a generation takes seconds.
 */
final class LoopFixture {

    private LoopFixture() {
    }

    record Result(int code, String err, String out) {
    }

    /** A TINY network with the classical planes of random weights, written to {@code file}. */
    static Path initNetwork(Path file, long seed) throws IOException {
        Parameters.init(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), seed).save(file);
        return file;
    }

    /** The options of every tiny run; the tests add or replace some. */
    static List<String> options(String... more) {
        List<String> args = new ArrayList<>(List.of("--generations", "2", "--games", "4", "--shard-games", "2", "--sims", "4",
                "--temp-plies", "8", "--batch", "16", "--microbatch", "8", "--reuse", "1", "--window", "100000", "--holdout",
                "0.25", "--warmup", "2", "--lr", "0.003", "--gate-pairs", "4", "--pair-shard", "2", "--baseline-rung", "100",
                "--baseline-pairs", "2", "--strength-every", "1", "--ladder", "100", "--strength-pairs", "2", "--threads", "2",
                "--checkpoint-steps", "6", "--keep", "2", "--eval-samples", "40", "--proxy-seconds", "0",
                "--progress-seconds", "0.05", "--seed", "7", "--promote-z", "0"));
        args.addAll(Arrays.asList(more));
        // a later value of the same option wins: the parser puts them in a map in order
        return args;
    }

    static Result loop(Path run, Loop.StopHook hook, List<String> options) throws Exception {
        return loop(run, hook, options, null);
    }

    /** A run in which {@code fake} answers the pairs of the gate and baseline matches that it wants to (see {@link Loop.PairFake}). */
    static Result loop(Path run, Loop.StopHook hook, List<String> options, Loop.PairFake fake) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("run", run.toString()));
        args.addAll(options);
        int code = Loop.run(args.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), hook, fake);
        return new Result(code, err.toString(StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8));
    }

    /** A pair of the given score without play: 1 two wins, 0 two losses, 0.5 a win and a loss. */
    static MctsMatch.PairLine pairLine(int pair, double score) {
        int wins = score > 0.5 ? 2 : score < 0.5 ? 0 : 1;
        int losses = score > 0.5 ? 0 : score < 0.5 ? 2 : 1;
        return new MctsMatch.PairLine(pair, score, wins, 0, losses, 10, 0, 0, 0, 0, 0, 0, 0);
    }

    static Result command(Loop.StopHook hook, String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Loop.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), hook);
        return new Result(code, err.toString(StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8));
    }

    static final Loop.StopHook NEVER = (phase, done) -> false;

    /** Stops in {@code phase} once {@code done} items of it are done by this process. */
    static Loop.StopHook stopIn(String phase, long done) {
        return (p, n) -> p.equals(phase) && n >= done;
    }

    static Path gen(Path run, int g) {
        return run.resolve(String.format("gen-%04d", g));
    }

    static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }

    static long count(Path dir, String glob) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().matches(glob)).count();
        }
    }
}
