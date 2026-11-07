package sternhalma.selfplay;

import java.util.HashSet;
import java.util.Set;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.search.RaceAdjudication;

/**
 * Checks an S1 verdict whose game, played on by the players, ended otherwise (a conversion failure, not a label
 * error, per the amended E03 criterion): the predicted winner W plays its plan while the loser plays (a) the moves
 * it played in the game, as long as they are legal, else its first legal move, and (b) every legal move at every
 * ply (transposition set on the loser's configuration and move count, capped). Every line must end in W's win on or
 * before the predicted ply. This is a smaller copy of the attack of the search module's test sources
 * ({@code RaceAdversary}), which is not visible here.
 */
final class VerdictCheck {

    /** {@code failure} is null when every line examined ended in time; {@code capped} when (b) hit the cap. */
    record Result(long nodes, boolean capped, String failure) {
    }

    private final RaceAdjudication.Verdict verdict;
    private final Position pos;
    private final int w;
    private final long cap;
    private final Set<String> seen = new HashSet<>();
    private long nodes;
    private boolean capped;
    private String failure;

    private VerdictCheck(Position start, RaceAdjudication.Verdict verdict, long cap) {
        this.pos = start.copy();
        this.verdict = verdict;
        this.w = verdict.winner();
        this.cap = cap;
    }

    static Result check(Position start, RaceAdjudication.Verdict verdict, int[] gameMoves, long cap) {
        VerdictCheck line = new VerdictCheck(start, verdict, Long.MAX_VALUE);
        line.search(0, 0, gameMoves);
        if (line.failure != null) {
            return new Result(line.nodes, false, "recorded line: " + line.failure);
        }
        VerdictCheck all = new VerdictCheck(start, verdict, cap);
        all.search(0, 0, null);
        return new Result(line.nodes + all.nodes, all.capped, all.failure == null ? null : "full width: " + all.failure);
    }

    private void fail(String what) {
        if (failure == null) {
            failure = what + " at ply " + pos.ply();
        }
    }

    private static boolean contains(int[] a, int x) {
        for (int v : a) {
            if (v == x) {
                return true;
            }
        }
        return false;
    }

    private void search(int wMoves, int loMoves, int[] recorded) {
        if (failure != null || capped) {
            return;
        }
        if (++nodes > cap) {
            capped = true;
            return;
        }
        int[] legal = pos.legalMoves();
        if (pos.outcome() != null || legal.length == 0) {
            fail("game ended unexpectedly");
            return;
        }
        if (pos.sideToMove() == w) {
            int t = wMoves + 1;
            if (t > verdict.winnerMoves()) {
                fail("plan exhausted");
                return;
            }
            int move = verdict.plan().steps().get(t - 1).move();
            if (!contains(legal, move)) {
                fail("plan move " + t + " illegal");
                return;
            }
            pos.make(move);
            Outcome after = pos.outcome();
            if (t == verdict.winnerMoves()) {
                if (after == null || !after.hasWinner() || after.winner() != w || pos.ply() > verdict.endPly()) {
                    fail("plan end is not a win in time: " + after);
                }
            } else if (after != null) {
                fail("game ended during the plan: " + after);
            } else {
                search(t, loMoves, recorded);
            }
            pos.unmake();
            return;
        }
        if (recorded != null) {
            int move = pos.ply() < recorded.length && contains(legal, recorded[pos.ply()]) ? recorded[pos.ply()] : legal[0];
            loStep(move, wMoves, loMoves, recorded);
            return;
        }
        for (int move : legal) {
            if (failure != null || capped) {
                return;
            }
            loStep(move, wMoves, loMoves, null);
        }
    }

    private void loStep(int move, int wMoves, int loMoves, int[] recorded) {
        pos.make(move);
        try {
            if (pos.outcome() != null) {
                fail("the loser ended the game: " + pos.outcome());
                return;
            }
            if (recorded == null && !seen.add(java.util.Arrays.toString(pos.pieces(1 - w)) + "/" + (loMoves + 1))) {
                return;
            }
            search(wMoves, loMoves + 1, recorded);
        } finally {
            pos.unmake();
        }
    }
}
