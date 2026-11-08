package sternhalma.search;

import sternhalma.engine.Position;

/**
 * The assert mode of the incremental evaluation (E13): an {@link EvalState} that, on every {@link #evaluate}, also
 * runs the full {@link Evaluator#evaluate} on the same position and throws an {@link AssertionError} on any
 * difference; after every {@link #make} and {@link #unmake} it checks the internal sums
 * ({@link EvalState#verify}). Slow; for tests ({@link AlphaBeta#AlphaBeta(Evaluator, int, boolean, SearchOptions,
 * boolean)}).
 */
final class VerifyingEvalState implements EvalState {

    private final Evaluator oracle;
    private final EvalState inner;
    private long evaluations;

    VerifyingEvalState(Evaluator oracle, EvalState inner) {
        this.oracle = oracle;
        this.inner = inner;
    }

    /** Evaluations compared so far. */
    long evaluations() {
        return evaluations;
    }

    @Override
    public boolean reset(Position root) {
        boolean ok = inner.reset(root);
        if (ok) {
            inner.verify(root);
        }
        return ok;
    }

    @Override
    public void make(Position position, int move) {
        inner.make(position, move);
        inner.verify(position);
    }

    @Override
    public void unmake(Position position) {
        inner.unmake(position);
        inner.verify(position);
    }

    @Override
    public int evaluate(Position position) {
        int incremental = inner.evaluate(position);
        int full = oracle.evaluate(position);
        evaluations++;
        if (incremental != full) {
            throw new AssertionError("incremental " + incremental + " differs from the full evaluation " + full
                    + "\n" + position);
        }
        return incremental;
    }

    @Override
    public void verify(Position position) {
        inner.verify(position);
    }
}
