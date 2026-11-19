package sternhalma.webnet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The games of the node harness (teavm-net/test/harness.mjs): per variant a fixed game of {@link #PLIES} plies played
 * with {@link HashBackend}, one line per ply {@code move sims nodes exactLeaves visits}. The JVM writes them
 * ({@code ./gradlew :teavm-net:goldenExport}); {@link GoldenFilesTest} checks that the files are current; the harness
 * checks that the TeaVM builds (Wasm GC and JS) play the same.
 */
final class GoldenGame {
    static final int PLIES = 14;
    static final int SIMS = 48;
    static final int BATCH = 4;
    static final int SEED = 20261119;

    /** A variant of the golden games: name, jump rule, players, planes id, value width. */
    record Variant(String name, String jump, int players, String planes, int valueWidth) {
    }

    static final List<Variant> VARIANTS = List.of(
            new Variant("2p-adjacent", "adjacent", 2, "classic1", 2),
            new Variant("2p-long", "long", 2, "classic1v", 3),
            new Variant("3p-adjacent", "adjacent", 3, "classic1v", 3),
            new Variant("3p-long", "long", 3, "classic1v", 3));

    private GoldenGame() {
    }

    /** Plays the game with a fresh core; returns the lines. */
    static List<String> play(Variant v) {
        WebNetCore core = new WebNetCore();
        core.create(WebNetCore.parseJump(v.jump()), v.players(), SIMS, BATCH, SEED, "");
        core.setWeightsInfo(v.planes(), v.valueWidth());
        int inputSize = core.inputSize();
        List<Integer> history = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int ply = 0; ply < PLIES; ply++) {
            int n = core.begin(toArray(history));
            while (n > 0) {
                float[][] out = HashBackend.forward(core.inputs(), inputSize, n, v.valueWidth());
                n = core.apply(out[0], out[1], out[2]);
            }
            WebNetCore.Best b = core.best();
            lines.add(b.move() + " " + b.sims() + " " + b.nodes() + " " + b.exactLeaves() + " " + join(b.visits()));
            history.add(b.move());
        }
        return lines;
    }

    static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }

    private static String join(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            sb.append(i == 0 ? "" : ",").append(values[i]);
        }
        return sb.toString();
    }

    /** usage: GoldenGame [output directory, default teavm-net/test/golden]. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "teavm-net/test/golden");
        Files.createDirectories(dir);
        for (Variant v : VARIANTS) {
            Files.write(dir.resolve(v.name() + ".txt"), play(v));
            System.err.println("wrote " + dir.resolve(v.name() + ".txt"));
        }
    }
}
