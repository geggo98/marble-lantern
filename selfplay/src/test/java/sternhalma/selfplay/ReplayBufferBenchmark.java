package sternhalma.selfplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import sternhalma.nn.TrainingBatch;
import sternhalma.selfplay.SyntheticSamples.Sample;

/**
 * Samples per second of {@link ReplayBuffer#batch}, single thread (a main method in the test sources, not a test).
 * Run: {@code ./gradlew --no-build-cache :selfplay:replayBenchmark}. The window is filled with 20,000 samples of
 * seeded random playouts (about as many legal moves per sample as the E05 games) in two generations.
 */
public final class ReplayBufferBenchmark {

    private ReplayBufferBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("replay-benchmark");
        try {
            for (int players = 2; players <= 3; players++) {
                run(dir, players);
            }
        } finally {
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Files.delete(f);
                }
            }
            Files.delete(dir);
        }
    }

    private static void run(Path dir, int players) throws IOException {
        List<Sample> samples = SyntheticSamples.samples(players, 1, 20_000);
        double moves = samples.stream().mapToInt(s -> s.from().length).average().orElse(0);
        ReplayBuffer buffer = new ReplayBuffer(players, 100_000, true);
        buffer.addGeneration(0, List.of(SyntheticSamples.write(dir, "p" + players + "a.safetensors", players,
                samples.subList(0, 10_000))));
        buffer.addGeneration(1, List.of(SyntheticSamples.write(dir, "p" + players + "b.safetensors", players,
                samples.subList(10_000, 20_000))));
        System.out.printf("%d players: %d samples, %.1f legal moves per sample%n", players, buffer.size(), moves);
        for (int batchSize : new int[] {64, 256, 1024}) {
            int steps = 20_000 / batchSize;
            long sink = 0;
            for (int i = 0; i < steps; i++) { // warm-up
                sink += buffer.batch(1, 0, i, batchSize).size();
            }
            double[] rates = new double[5];
            for (int r = 0; r < rates.length; r++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < steps; i++) {
                    TrainingBatch b = buffer.batch(2 + r, 0, i, batchSize);
                    sink += b.size();
                }
                rates[r] = (double) steps * batchSize / ((System.nanoTime() - t0) / 1e9);
            }
            Arrays.sort(rates);
            System.out.printf("  batch %4d: %,9.0f samples/s (median of 5; range %,.0f to %,.0f)%s%n", batchSize,
                    rates[2], rates[0], rates[4], sink < 0 ? "?" : "");
        }
    }
}
