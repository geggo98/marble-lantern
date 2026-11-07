package sternhalma.nn;

import java.util.Objects;
import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;

/**
 * The canonical orientation of the board for one mover (docs/network.md, "Canonical orientation").
 *
 * <p>The map rotates the star so that the mover's start corner becomes the top corner R_NEG. In cube
 * coordinates:
 * <ul>
 *   <li>2 players, mover 0: identity; mover 1: (q, r, s) to (-q, -r, -s), equal to {@code Geometry.rotate180};
 *   <li>3 players, mover 0: identity; mover 1: (q, r, s) to (r, s, q); mover 2: (q, r, s) to (s, q, r).
 * </ul>
 * After the map, relative player i (the player who moves i plies after the mover) starts in
 * {@code Layout.of(players).startCorner(i)}, and {@code Layout.allowed(mover, c)} equals
 * {@code Layout.allowed(0, toCanonical(c))} for every cell c.
 *
 * <p>Instances are immutable and may be shared; {@link #of(int, int)} may return cached instances.
 */
public final class Orientation {

    private static final Orientation[] TWO = {new Orientation(2, 0), new Orientation(2, 1)};
    private static final Orientation[] THREE = {
        new Orientation(3, 0), new Orientation(3, 1), new Orientation(3, 2)
    };

    private final int players;
    private final int mover;
    /** Board cell to canonical cell. */
    private final int[] forward = new int[Geometry.CELLS];
    /** Canonical cell to board cell. */
    private final int[] backward = new int[Geometry.CELLS];

    private Orientation(int players, int mover) {
        this.players = players;
        this.mover = mover;
        for (int c = 0; c < Geometry.CELLS; c++) {
            int q = Geometry.q(c);
            int r = Geometry.r(c);
            int s = Geometry.s(c);
            // Each case is a rotation of the star in cube coordinates; the axial id uses the first two
            // components of the image (q', r'), s' follows from q' + r' + s' = 0.
            int image;
            if (mover == 0) {
                image = c;
            } else if (players == 2) {
                image = Geometry.id(-q, -r);     // (q, r, s) -> (-q, -r, -s)
            } else if (mover == 1) {
                image = Geometry.id(r, s);       // (q, r, s) -> (r, s, q)
            } else {
                image = Geometry.id(s, q);       // (q, r, s) -> (s, q, r)
            }
            if (image < 0) {
                throw new IllegalStateException("cell " + c + " maps off the board");
            }
            forward[c] = image;
        }
        java.util.Arrays.fill(backward, -1);
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (backward[forward[c]] >= 0) {
                throw new IllegalStateException("map is not a bijection at cell " + forward[c]);
            }
            backward[forward[c]] = c;
        }
    }

    /**
     * The orientation for a mover.
     *
     * @throws IllegalArgumentException if players is not 2 or 3, or mover is not in [0, players)
     */
    public static Orientation of(int players, int mover) {
        Orientation[] table = switch (players) {
            case 2 -> TWO;
            case 3 -> THREE;
            default -> throw new IllegalArgumentException("players must be 2 or 3, got " + players);
        };
        if (mover < 0 || mover >= players) {
            throw new IllegalArgumentException("mover " + mover + " outside 0.." + (players - 1));
        }
        return table[mover];
    }

    /** The orientation for the side to move of a position. */
    public static Orientation of(Position position) {
        return of(position.rules().players(), position.sideToMove());
    }

    public int players() {
        return players;
    }

    /** The absolute player who moves (relative player 0). */
    public int mover() {
        return mover;
    }

    /**
     * The canonical cell of a board cell (0 to 120).
     *
     * @throws IndexOutOfBoundsException if the cell is not in [0, 121)
     */
    public int toCanonical(int cell) {
        return forward[Objects.checkIndex(cell, Geometry.CELLS)];
    }

    /**
     * The board cell of a canonical cell; the inverse of {@link #toCanonical}.
     *
     * @throws IndexOutOfBoundsException if the cell is not in [0, 121)
     */
    public int fromCanonical(int canonicalCell) {
        return backward[Objects.checkIndex(canonicalCell, Geometry.CELLS)];
    }

    /**
     * An engine move ({@code Move.of(from, to)}) with both cells mapped to canonical cells.
     *
     * @throws IndexOutOfBoundsException if a cell of the move is not in [0, 121)
     */
    public int toCanonicalMove(int move) {
        return Move.of(toCanonical(Move.from(move)), toCanonical(Move.to(move)));
    }

    /**
     * The inverse of {@link #toCanonicalMove}.
     *
     * @throws IndexOutOfBoundsException if a cell of the move is not in [0, 121)
     */
    public int fromCanonicalMove(int canonicalMove) {
        return Move.of(fromCanonical(Move.from(canonicalMove)), fromCanonical(Move.to(canonicalMove)));
    }

    /**
     * The relative player of an absolute player: (absolute - mover) mod players.
     *
     * @throws IllegalArgumentException if the player is not in [0, players)
     */
    public int relativePlayer(int absolutePlayer) {
        requirePlayer(absolutePlayer);
        return (absolutePlayer - mover + players) % players;
    }

    /**
     * The absolute player of a relative player: (mover + relative) mod players.
     *
     * @throws IllegalArgumentException if the player is not in [0, players)
     */
    public int absolutePlayer(int relativePlayer) {
        requirePlayer(relativePlayer);
        return (mover + relativePlayer) % players;
    }

    private void requirePlayer(int player) {
        if (player < 0 || player >= players) {
            throw new IllegalArgumentException("player " + player + " outside 0.." + (players - 1));
        }
    }

    @Override
    public String toString() {
        return "Orientation[players=" + players + ", mover=" + mover + "]";
    }
}
