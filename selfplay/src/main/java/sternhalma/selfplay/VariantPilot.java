package sternhalma.selfplay;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sternhalma.engine.RuleSet;

/**
 * {@code SelfPlay pilot}: plays a few games of one variant in memory (nothing is written) and prints the figures that
 * decide whether a data run is affordable: game length, share of games that end at the move limit, share of
 * adjudicated games, samples per game and the cost per game and per sample. The options are those of {@code run}
 * ({@code --games} defaults to 20).
 */
final class VariantPilot {

    private VariantPilot() {
    }

    /** What a pilot measured. {@code plies} and {@code samples} are per game, in game-id order. */
    record Stats(String variant, int games, int[] plies, int[] samples, int atMoveLimit, int playedOut,
            int adjudicated, double cpuMsPerGame, double wallMsPerGame) {

        double mean(int[] a) {
            return Arrays.stream(a).average().orElse(0);
        }

        /** Games that ended by the move limit, among the games that were played to an end. */
        double moveLimitShare() {
            return playedOut == 0 ? 0 : (double) atMoveLimit / playedOut;
        }

        double meanSamples() {
            return mean(samples);
        }

        String line() {
            int[] sorted = plies.clone();
            Arrays.sort(sorted);
            double samplesPerGame = meanSamples();
            return String.format(Locale.ROOT,
                    "%s: %d games; plies mean %.1f median %d min %d max %d; move limit %.0f%% of %d played out; "
                            + "adjudicated %.0f%%; samples/game %.1f; %.0f ms/game CPU, %.0f ms/game wall, "
                            + "%.2f ms/sample CPU",
                    variant, games, mean(plies), sorted[sorted.length / 2], sorted[0], sorted[sorted.length - 1],
                    100 * moveLimitShare(), playedOut, 100.0 * adjudicated / games, samplesPerGame, cpuMsPerGame,
                    wallMsPerGame, samplesPerGame == 0 ? 0 : cpuMsPerGame / samplesPerGame);
        }
    }

    static int run(Map<String, String> opts, PrintStream out, PrintStream err) throws Exception {
        SelfPlayGame.Settings settings = SelfPlay.settings(opts);
        int games = SelfPlay.intOpt(opts, "games", 20);
        int threads = SelfPlay.intOpt(opts, "threads", 1);
        err.println("pilot: " + settings.describe() + "; games=" + games + "; threads=" + threads);
        Stats stats = play(settings, games, threads);
        out.println(stats.line());
        return 0;
    }

    static Stats play(SelfPlayGame.Settings settings, int games, int threads) throws Exception {
        RuleSet rules = settings.rules();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        List<Future<GameRecord>> futures = new ArrayList<>();
        ThreadLocal<SelfPlayGame> perThread = ThreadLocal.withInitial(() -> new SelfPlayGame(settings));
        for (int id = 0; id < games; id++) {
            int gameId = id;
            futures.add(pool.submit(() -> perThread.get().play(gameId)));
        }
        int[] plies = new int[games];
        int[] samples = new int[games];
        int atLimit = 0;
        int playedOut = 0;
        int adjudicated = 0;
        long cpu = 0;
        try {
            for (int i = 0; i < games; i++) {
                GameRecord r = futures.get(i).get();
                plies[i] = r.moves().length;
                samples[i] = r.samples() == null ? 0 : r.samples().count();
                if (r.playedOut()) {
                    playedOut++;
                    if (r.finalPly() >= rules.moveLimit()) {
                        atLimit++;
                    }
                }
                if (r.label() != Adjudication.Layer.NONE) {
                    adjudicated++;
                }
                cpu += r.cpuNanos();
            }
        } finally {
            pool.shutdownNow();
        }
        double wall = (System.nanoTime() - t0) / 1e6;
        String variant = rules.players() + "p " + rules.jumpRule().name().toLowerCase(Locale.ROOT)
                + (rules.triangleRows() == sternhalma.engine.RuleSet.TriangleRows.FOUR ? "" : " rows " + rules.triangleRows().rows());
        return new Stats(variant, games, plies, samples, atLimit, playedOut, adjudicated, cpu / 1e6 / games,
                wall / games);
    }
}
