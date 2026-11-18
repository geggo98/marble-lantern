package sternhalma.search;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sternhalma.tablebase.RaceTable;

/**
 * Gate for tests that need the rows&gt;=1 race table. With only Git LFS pointers present, {@link RaceTable#rows1()}
 * would rebuild the table (about four minutes) inside the test, so such a test is skipped with a message instead.
 * {@code devenv tasks run tables:unpack} (or the archive plus {@code zstd} on the PATH) provides the table.
 */
final class Rows1Tables {

    private Rows1Tables() {
    }

    /** Whether the table is available without a rebuild (unpacks the archive into the cache if needed). */
    static boolean available() {
        return RaceTable.rows1IfCached() != null;
    }

    /** Skips the calling test unless {@link #available()}. */
    static void assume() {
        assumeTrue(available(), "the rows>=1 table is not available (only Git LFS pointers?);"
                + " run devenv tasks run tables:unpack");
    }
}
