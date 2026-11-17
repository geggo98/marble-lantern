package sternhalma.selfplay;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import sternhalma.tablebase.LookupObserver;
import sternhalma.tablebase.PushEstimator;

/**
 * Collects the rows&gt;=1 lookups of one game for {@link ProbeLog}: lookups with the same (ply, role, rank) are merged
 * into a count. The ply is set by the game loop before each move choice; lookups of a search carry the ply of its root.
 * One instance per game thread, reused with {@link #start}.
 */
final class RankRecorder implements LookupObserver {

    private record Entry(int k, int rows1, int rows2, int estimate, int[] count) {
    }

    private final Map<Long, Entry> merged = new HashMap<>();
    private int game;
    private int ply;
    private long lookups;

    void start(int game) {
        this.game = game;
        this.ply = 0;
        this.lookups = 0;
        merged.clear();
    }

    /** The game ply that the following lookups belong to. */
    void ply(int ply) {
        this.ply = ply;
    }

    long lookups() {
        return lookups;
    }

    @Override
    public void lookup(int role, int[] cells, long rank, int rows1, int rows2, int estimate) {
        lookups++;
        long key = ((long) ply << 30) | ((long) role << 28) | rank; // rank < 2^28
        Entry e = merged.get(key);
        if (e == null) {
            merged.put(key, new Entry(PushEstimator.stonesInRow1(cells), rows1, rows2, estimate, new int[] {1}));
        } else {
            e.count()[0]++;
        }
    }

    /** The records of the game so far, sorted by (ply, role, rank), so that the lookups of one position are adjacent. */
    ProbeLog.GameRows finish(int plies) {
        long[] keys = merged.keySet().stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(keys);
        ByteBuffer b = ByteBuffer.allocate(keys.length * ProbeLog.RECORD_BYTES);
        for (long key : keys) {
            Entry e = merged.get(key);
            ProbeLog.put(b, new ProbeLog.Row(game, (int) (key >>> 30), (int) ((key >>> 28) & 3), e.k(),
                    (int) (key & ((1L << 28) - 1)), e.rows1(), e.rows2(), e.estimate(), e.count()[0]));
        }
        return new ProbeLog.GameRows(game, plies, keys.length, lookups, b.array());
    }
}
