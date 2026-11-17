package sternhalma.tablebase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * The 64 KB blocks of the rows&gt;=1 table. The table is a dense array of one byte per state, indexed by the
 * combinatorial rank ({@link Ranking}), so the block of a lookup is {@code rank >> 16}. A set of blocks is the
 * unit that a browser could download; {@link BlockRestrictedCounts} serves the rows&gt;=1 value only inside such a
 * set. A set is stored as a text file: comment lines start with {@code #}, every other line is one block index.
 */
public final class RowOneBlocks {

    /** log2 of the block size in table entries (one entry is one byte, so 64 KB). */
    public static final int SHIFT = 16;
    /** Table entries (bytes) per block. */
    public static final int SIZE = 1 << SHIFT;

    private RowOneBlocks() {
    }

    /** The block of a rank. */
    public static int blockOf(long rank) {
        return (int) (rank >>> SHIFT);
    }

    /** The number of blocks of a table with {@code states} entries (3879 for the rows&gt;=1 table). */
    public static int blockCount(long states) {
        return (int) ((states + SIZE - 1) >>> SHIFT);
    }

    /** The number of blocks of the rows&gt;=1 table, from its ranking alone (no table needed). */
    public static int rows1BlockCount() {
        return blockCount(RaceTable.ROWS1.ranking().count());
    }

    /** Writes a block set (ascending indices) with a comment line that says what it is. */
    public static void write(Path file, BitSet blocks, String comment) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(comment.replace('\n', ' ')).append('\n');
        sb.append("# blocks=").append(blocks.cardinality()).append(" of ").append(rows1BlockCount()).append('\n');
        for (int b = blocks.nextSetBit(0); b >= 0; b = blocks.nextSetBit(b + 1)) {
            sb.append(b).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + ProcessHandle.current().pid());
        Files.writeString(tmp, sb.toString());
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Reads a block set. A file that holds no block at all is an error (an empty set would make every lookup fall
     * back, which looks like a working run).
     */
    public static BitSet read(Path file) throws IOException {
        int total = rows1BlockCount();
        BitSet set = new BitSet(total);
        for (String line : Files.readAllLines(file)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int b = Integer.parseInt(s);
            if (b < 0 || b >= total) {
                throw new IOException(file + ": block " + b + " outside 0.." + (total - 1));
            }
            set.set(b);
        }
        if (set.isEmpty()) {
            throw new IOException(file + ": no block in the file");
        }
        return set;
    }
}
