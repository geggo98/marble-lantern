package sternhalma.tablebase;

import java.util.BitSet;

/**
 * The rows&gt;=1 table restricted to a set of 64 KB blocks ({@link RowOneBlocks}). A configuration whose rank lies
 * in a block of the set gets the exact rows&gt;=1 count. Any other configuration is treated as if the rows&gt;=1
 * table did not exist: it gets the rows&gt;=2 count if that table covers it, else it is not covered. The two search
 * roles then fall back exactly as they do today with {@code racerows=2}: the evaluator to its base value, race play to
 * the beam plan.
 *
 * <p>Mixing the two tables is sound for the heuristic use here: a rows&gt;=2 count is an upper bound of the
 * rows&gt;=1 count of the same configuration, so a fallback value is never below the exact one.
 */
public final class BlockRestrictedCounts implements RaceCounts {

    private final RaceTable rows1;
    private final RaceTable rows2;
    private final BitSet blocks;

    public BlockRestrictedCounts(RaceTable rows1, RaceTable rows2, BitSet blocks) {
        if (rows1.minRow() != 1 || rows2.minRow() != 2) {
            throw new IllegalArgumentException("need the rows>=1 and the rows>=2 table");
        }
        this.rows1 = rows1;
        this.rows2 = rows2;
        this.blocks = (BitSet) blocks.clone();
    }

    /** The blocks that are served. */
    public BitSet blocks() {
        return (BitSet) blocks.clone();
    }

    @Override
    public boolean covers(int[] ownCells) {
        return count(ownCells) >= 0;
    }

    @Override
    public int count(int[] ownCells) {
        Ranking r1 = rows1.rankingOf();
        if (!r1.covers(ownCells)) {
            return -1;
        }
        long rank = r1.rank(ownCells);
        if (blocks.get(RowOneBlocks.blockOf(rank))) {
            int v = rows1.entry(rank);
            return v == LookupObserver.UNKNOWN ? -1 : v;
        }
        return rows2.count(ownCells);
    }
}
