package sternhalma.web;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The benchmark game on the JVM: the Java baseline and the source of the expected fingerprint of the browser engines
 * (plan section 8). Same code path as the TeaVM build ({@link WebCore#bench}).
 *
 * <p>Usage: {@code BenchReference [--json] [<rows2.bin> [<plies> [<nodes>]]]}, defaults
 * {@code tablebase/tables/rows2.bin 40 20000}. The checksum folds the moves as
 * {@code checksum = checksum * 31 + move} (wrap-around, signed 64 bit), the same formula as the TypeScript side.
 * Prints {@code checksum=<c> nodes=<n> ms=<t> rows1Requests=<r>} or, with {@code --json}, one JSON object.
 */
public final class BenchReference {
    private BenchReference() {
    }

    public static void main(String[] args) throws Exception {
        boolean json = args.length > 0 && args[0].equals("--json");
        int first = json ? 1 : 0;
        Path table = Path.of(args.length > first ? args[first] : "tablebase/tables/rows2.bin");
        int plies = args.length > first + 1 ? Integer.parseInt(args[first + 1]) : 40;
        int nodes = args.length > first + 2 ? Integer.parseInt(args[first + 2]) : 20_000;

        WebCore core = new WebCore();
        core.setTable(Files.readAllBytes(table), true);
        core.bench(2, nodes); // warm-up game: JIT, like the warm-up of the browser runners
        long t0 = System.nanoTime();
        WebCore.Bench bench = core.bench(plies, nodes);
        double totalMs = (System.nanoTime() - t0) / 1e6;

        long checksum = 0;
        for (int move : bench.moves()) {
            checksum = checksum * 31 + move;
        }
        long total = 0;
        for (long n : bench.nodes()) {
            total += n;
        }
        double searchMs = 0;
        for (double ms : bench.ms()) {
            searchMs += ms;
        }
        if (json) {
            System.out.println("{\"plies\":" + bench.moves().length + ",\"nodesPerMove\":" + nodes + ",\"checksum\":\"" + checksum
                    + "\",\"nodes\":" + total + ",\"rows1Requests\":" + bench.rows1Requests() + "}");
        } else {
            System.out.printf("checksum=%d nodes=%d ms=%.0f rows1Requests=%d%n", checksum, total, searchMs, bench.rows1Requests());
            System.out.printf("(game incl. replay %.0f ms)%n", totalMs);
        }
    }
}
