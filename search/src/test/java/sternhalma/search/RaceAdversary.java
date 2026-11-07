package sternhalma.search;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.tablebase.Rows;

/**
 * Attacks an S1 verdict: the predicted winner W plays its plan move by move, the predicted loser Lo plays either
 * every legal move (exhaustively, with a transposition set on Lo's configuration and the number of Lo moves made)
 * or one line of a given policy. At every node the soundness invariants are checked: Lo never wins or ends the
 * game, Lo's front stays within the bound, W's next plan move is legal, and every leaf is a W win on or before the
 * predicted ply.
 */
final class RaceAdversary {

    /** The outcome of an attack: {@code failure} is null when every line ended in a W win in time. */
    record Result(long nodes, long leaves, boolean capped, String failure) {
        boolean sound() {
            return failure == null;
        }
    }

    private record Key(long lo, long hi, int loMoves) {
    }

    private final Position pos;
    private final RaceAdjudication.Verdict verdict;
    private final int w;
    private final int lo;
    private final long cap;
    private final Set<Key> seen = new HashSet<>();
    private long nodes;
    private long leaves;
    private boolean capped;
    private String failure;

    private RaceAdversary(Position start, RaceAdjudication.Verdict verdict, long cap) {
        this.pos = start.copy();
        this.verdict = verdict;
        this.w = verdict.winner();
        this.lo = 1 - w;
        this.cap = cap;
    }

    /** Lo plays every legal move at every Lo ply; at most {@code cap} nodes. */
    static Result exhaustive(Position start, RaceAdjudication.Verdict verdict, long cap) {
        RaceAdversary a = new RaceAdversary(start, verdict, cap);
        a.search(0, 0, null, null);
        return new Result(a.nodes, a.leaves, a.capped, a.failure);
    }

    /** Lo plays the given policy; W plays the plan. */
    static Result line(Position start, RaceAdjudication.Verdict verdict, Player loPolicy) {
        RaceAdversary a = new RaceAdversary(start, verdict, Long.MAX_VALUE);
        a.search(0, 0, loPolicy, null);
        return new Result(a.nodes, a.leaves, a.capped, a.failure);
    }

    /** Lo plays the game's recorded moves while they are legal, else the fallback policy. */
    static Result recorded(Position start, RaceAdjudication.Verdict verdict, int[] gameMoves, Player fallback) {
        RaceAdversary a = new RaceAdversary(start, verdict, Long.MAX_VALUE);
        a.search(0, 0, fallback, gameMoves);
        return new Result(a.nodes, a.leaves, a.capped, a.failure);
    }

    private void fail(String what) {
        if (failure == null) {
            failure = what + " at ply " + pos.ply() + "\n" + pos;
        }
    }

    private int loFront() {
        int front = Integer.MIN_VALUE;
        for (int c : pos.pieces(lo)) {
            front = Math.max(front, Rows.row(w, c));
        }
        return front;
    }

    private static boolean contains(int[] moves, int move) {
        for (int m : moves) {
            if (m == move) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param wMoves   W plan moves made so far
     * @param loMoves  Lo moves made so far
     * @param policy   Lo's policy, or null for the exhaustive attack
     * @param recorded the game's moves (Lo follows them while legal), or null
     */
    private void search(int wMoves, int loMoves, Player policy, int[] recorded) {
        if (failure != null || capped) {
            return;
        }
        if (++nodes > cap) {
            capped = true;
            return;
        }
        Outcome outcome = pos.outcome();
        if (outcome != null) {
            fail("unexpected end " + outcome);
            return;
        }
        int[] legal = pos.legalMoves();
        if (legal.length == 0) {
            fail("the side to move has no legal move");
            return;
        }
        if (pos.sideToMove() == w) {
            int t = wMoves + 1;
            if (t > verdict.winnerMoves()) {
                fail("plan exhausted without a win");
                return;
            }
            int move = verdict.plan().steps().get(t - 1).move();
            if (!contains(legal, move)) {
                fail("plan move " + Move.toString(move) + " (move " + t + ") is not legal");
                return;
            }
            pos.make(move);
            Outcome after = pos.outcome();
            if (t == verdict.winnerMoves()) {
                leaves++;
                if (after == null || !after.hasWinner() || after.winner() != w) {
                    fail("the plan's last move does not win: " + after);
                } else if (pos.ply() > verdict.endPly()) {
                    fail("W wins after the predicted ply " + verdict.endPly());
                }
            } else if (after != null) {
                fail("game ended during the plan: " + after);
            } else {
                search(t, loMoves, policy, recorded);
            }
            pos.unmake();
            return;
        }
        // Lo to move
        if (policy != null) {
            int move = -1;
            if (recorded != null && pos.ply() < recorded.length && contains(legal, recorded[pos.ply()])) {
                move = recorded[pos.ply()];
            } else {
                move = policy.choose(pos);
                if (!contains(legal, move)) {
                    fail("the Lo policy returned an illegal move");
                    return;
                }
            }
            loStep(move, wMoves, loMoves, policy, recorded);
            return;
        }
        for (int move : legal) {
            if (failure != null || capped) {
                return;
            }
            loStep(move, wMoves, loMoves, null, null);
        }
    }

    private void loStep(int move, int wMoves, int loMoves, Player policy, int[] recorded) {
        pos.make(move);
        try {
            int k = loMoves + 1;
            Outcome after = pos.outcome();
            if (after != null) {
                fail("game ended after Lo's move " + Move.toString(move) + ": " + after);
                return;
            }
            if (loFront() > verdict.front() + k) {
                fail("front bound violated after Lo's move " + Move.toString(move) + ": front " + loFront() + " > "
                        + verdict.front() + " + " + k);
                return;
            }
            if (policy == null) {
                int[] cells = pos.pieces(lo);
                long l = 0;
                long h = 0;
                for (int c : cells) {
                    l = Solitaire.withLo(l, c);
                    h = Solitaire.withHi(h, c);
                }
                if (!seen.add(new Key(l, h, k))) {
                    return;
                }
            }
            search(wMoves, k, policy, recorded);
        } finally {
            pos.unmake();
        }
    }

    // ---- Lo policies for long plans ----

    /** Lo maximises its front (towards W) every move: the most direct attempt to break the lemma. */
    static Player frontMaximiser(int w) {
        return new Player() {
            @Override
            public int choose(Position position) {
                int lo = 1 - w;
                int best = -1;
                int bestFront = Integer.MIN_VALUE;
                for (int m : position.legalMoves()) {
                    int front = Rows.row(w, Move.to(m));
                    // the front after the move: the max over the other pieces and the landing cell
                    for (int c : position.pieces(lo)) {
                        if (c != Move.from(m)) {
                            front = Math.max(front, Rows.row(w, c));
                        }
                    }
                    int tie = Rows.row(w, Move.to(m));
                    if (front > bestFront || (front == bestFront && best >= 0 && tie > Rows.row(w, Move.to(best)))) {
                        best = m;
                        bestFront = front;
                    }
                }
                return best;
            }

            @Override
            public String name() {
                return "front-maximiser";
            }
        };
    }

    /** Lo moves a piece as close as possible to W's next planned landing cells (the surveyor's chaser). */
    static Player chaser(RaceAdjudication.Verdict verdict) {
        return new Player() {
            int wMovesSeen = -1;

            @Override
            public int choose(Position position) {
                int w = verdict.winner();
                // how many W moves have been made: W's configuration tells; count plan states that match
                int[] wCells = position.pieces(w);
                int t = 0;
                for (int i = 0; i < verdict.plan().states().size(); i++) {
                    if (Arrays.equals(verdict.plan().states().get(i), wCells)) {
                        t = i;
                    }
                }
                wMovesSeen = t;
                int[] targets = t < verdict.winnerMoves() ? verdict.plan().steps().get(t).path() : new int[0];
                int best = -1;
                int bestDist = Integer.MAX_VALUE;
                for (int m : position.legalMoves()) {
                    int d = Integer.MAX_VALUE;
                    for (int target : targets) {
                        d = Math.min(d, hexDistance(Move.to(m), target));
                    }
                    if (d < bestDist) {
                        best = m;
                        bestDist = d;
                    }
                }
                return best < 0 ? position.legalMoves()[0] : best;
            }

            @Override
            public String name() {
                return "chaser";
            }
        };
    }

    static int hexDistance(int a, int b) {
        int dq = Geometry.q(a) - Geometry.q(b);
        int dr = Geometry.r(a) - Geometry.r(b);
        int ds = Geometry.s(a) - Geometry.s(b);
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(ds)) / 2;
    }

    static Player random(long seed) {
        Random random = new Random(seed);
        return new Player() {
            @Override
            public int choose(Position position) {
                int[] legal = position.legalMoves();
                return legal[random.nextInt(legal.length)];
            }

            @Override
            public String name() {
                return "random";
            }
        };
    }
}
