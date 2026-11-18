package sternhalma.tablebase;

/**
 * The exact rows&gt;=1 table with an observer: every lookup is reported to a {@link LookupObserver}, the values
 * returned are those of the table itself. For measurements only; the production path reads {@link RaceTable}
 * directly. One instance per role (leaf evaluator, race play) and per thread: the scratch space is not shared.
 */
public final class ObservedRows1Counts implements RaceCounts {

    private final RaceTable rows1;
    private final RaceTable rows2;
    private final PushEstimator push;
    private final LookupObserver observer;
    private final int role;

    public ObservedRows1Counts(RaceTable rows1, RaceTable rows2, LookupObserver observer, int role) {
        if (rows1.minRow() != 1 || rows2.minRow() != 2) {
            throw new IllegalArgumentException("need the rows>=1 and the rows>=2 table");
        }
        this.rows1 = rows1;
        this.rows2 = rows2;
        this.push = new PushEstimator(rows2, new int[PushEstimator.CLASSES]);
        this.observer = observer;
        this.role = role;
    }

    @Override
    public boolean covers(int[] ownCells) {
        return look(ownCells) >= 0;
    }

    @Override
    public int count(int[] ownCells) {
        return look(ownCells);
    }

    private int look(int[] cells) {
        Ranking ranking = rows1.rankingOf();
        if (!ranking.covers(cells)) {
            return -1;
        }
        long rank = ranking.rank(cells);
        int v = rows1.entry(rank);
        int fallback = rows2.rankingOf().covers(cells) ? rows2.entry(rows2.rankingOf().rank(cells)) : LookupObserver.UNKNOWN;
        int est = push.estimate(cells);
        observer.lookup(role, cells, rank, v, fallback, est < 0 ? LookupObserver.UNKNOWN : est);
        return v == LookupObserver.UNKNOWN ? -1 : v;
    }
}
