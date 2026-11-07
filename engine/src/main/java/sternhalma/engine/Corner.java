package sternhalma.engine;

/**
 * The six corners of the star, in clockwise order on screen (pointy-top layout, y pointing down),
 * as fixed in docs/rules.md. The ordinal is the clockwise index.
 */
public enum Corner {
    /** q &gt; 4, upper right. */
    Q_POS,
    /** s &lt; -4, lower right. */
    S_NEG,
    /** r &gt; 4, bottom. */
    R_POS,
    /** q &lt; -4, lower left. */
    Q_NEG,
    /** s &gt; 4, upper left. */
    S_POS,
    /** r &lt; -4, top. */
    R_NEG;

    private static final Corner[] ALL = values();

    /** The corner on the other side of the star. */
    public Corner opposite() {
        return ALL[(ordinal() + 3) % ALL.length];
    }

    /** The corner with the given clockwise index (0 to 5). */
    public static Corner ofIndex(int index) {
        return ALL[index];
    }
}
