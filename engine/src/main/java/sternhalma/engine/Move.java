package sternhalma.engine;

/** A move packed into an int: the start cell in the low byte and the end cell above it. */
public final class Move {

    private Move() {
    }

    public static int of(int from, int to) {
        return from | (to << 8);
    }

    public static int from(int move) {
        return move & 0xFF;
    }

    public static int to(int move) {
        return move >>> 8;
    }

    /** Text form with axial coordinates, e.g. {@code (0,-5)->(1,-5)}. */
    public static String toString(int move) {
        int from = from(move);
        int to = to(move);
        return "(" + Geometry.q(from) + "," + Geometry.r(from) + ")->(" + Geometry.q(to) + "," + Geometry.r(to) + ")";
    }
}
