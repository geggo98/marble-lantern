package sternhalma.tablebase;

/**
 * Exact remaining solitaire move counts of one player's configuration, as a race table provides them for a
 * region of the board. This is the only type through which the search module reaches a table.
 *
 * <p>Cells are given in player 0's orientation (goal corner {@code r > 4}); for player 1 map every cell with
 * {@link Rows#toPlayer0(int, int)} first. A count is the number of moves the player needs to fill the goal with
 * only its own pieces on the board, every configuration on the way staying inside the table's region. It is
 * exact for that restricted game and therefore an <em>upper</em> bound of the unrestricted solitaire distance;
 * it must never serve as a lower bound in an adjudication.
 */
public interface RaceCounts {

    /** Whether the configuration lies inside the region the table covers (exactly the right number of pieces). */
    boolean covers(int[] ownCells);

    /** The remaining moves of a covered configuration, or -1 if {@link #covers} is false. */
    int count(int[] ownCells);
}
