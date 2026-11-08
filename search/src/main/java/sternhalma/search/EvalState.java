package sternhalma.search;

import sternhalma.engine.Position;

/**
 * The incremental state of an {@link Evaluator} along one line of play (round-2 experiment E13): sums that a move
 * changes in a few places are kept up to date by {@link #make} and {@link #unmake}, and {@link #evaluate} forms the
 * value from them. The value is always bit-identical to {@link Evaluator#evaluate} on the same position; that
 * evaluation stays the oracle ({@link VerifyingEvalState} compares the two on every call).
 *
 * <p>A state follows exactly one {@link Position} object: after {@link #reset} every move goes through
 * {@link #make} (which plays it on the position) and is taken back with {@link #unmake}, in stack order. Not
 * thread-safe; {@link Evaluator#incremental} returns a new state for each searcher.
 */
public interface EvalState {

    /**
     * Starts a line of play at {@code root}, discarding the stack.
     *
     * @return false if the evaluator cannot track this position incrementally (three players, more than ten
     *         pieces); the caller then evaluates with {@link Evaluator#evaluate}
     */
    boolean reset(Position root);

    /** Plays {@code move} on {@code position}, which must be the position of the last {@link #reset}. */
    void make(Position position, int move);

    /** Takes back the last {@link #make} on {@code position}. */
    void unmake(Position position);

    /** The value of the current position, equal to {@link Evaluator#evaluate}. */
    int evaluate(Position position);

    /** Throws {@link AssertionError} if the internal sums differ from a full recomputation; for tests. */
    default void verify(Position position) {
    }
}
