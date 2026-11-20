package sternhalma.search;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.BlockingStates;
import sternhalma.engine.RuleSet.Repetition;
import sternhalma.tablebase.RaceCounts;
import sternhalma.tablebase.Rows;

/**
 * The race criterion S1: ends a passed two-player game with a label that cannot be wrong. It is the only component
 * of the race module that may label a position (docs/baseline-round2-plan.md, label policy); {@link RaceMove} and
 * the race table are play heuristics and never produce labels.
 *
 * <h2>Soundness statement</h2>
 *
 * <p>Notation. The two players are W (the predicted winner) and Lo. Rows are W's rows ({@link Rows#row} for W: W
 * advances towards higher rows, W's goal corner is rows 5 to 8, W's start corner and Lo's goal corner are rows -8
 * to -5). F is Lo's front: the largest W-row of any Lo piece, that is the Lo piece closest to W. k counts the Lo
 * moves made after the adjudicated position.
 *
 * <p>Preconditions, checked by {@link #adjudicate} (otherwise it returns null, "not applicable"):
 * <ul>
 *   <li>{@code rules.players() == 2}, {@code jumpRule == ADJACENT} (the proof and the race table know only the adjacent hop), {@code foreignCamps == CLOSED} (the lower bound of Lo's moves and the plans assume that no piece enters a foreign corner), {@code blockingStates == NONE} (an {@code ILLEGAL} rule could forbid a plan
 *       move for a reason outside this argument), {@code repetition == MOVE_LIMIT} (under {@code
 *       REPETITION_IS_DRAW} a position of the plan could repeat a position of the game's history, which the position
 *       does not expose; the plan only guarantees that W's own configurations never repeat);</li>
 *   <li>both players have exactly {@value Position#PIECES} pieces and the game is not over;</li>
 *   <li>the position is <em>passed</em>: every piece of player 0 has a larger {@code r} than every piece of player
 *       1, so each player is entirely ahead of the other in its own direction;</li>
 *   <li>the geometry of the row axis: a step changes the row by -1, 0 or +1, a jump by -2, 0 or +2, a jump that
 *       lands two rows further has its hurdle one row further; and every cell a player may stand on with row at most
 *       7 has a neighbour on the next row that the player may stand on ({@code GeometryRowAxisTest} checks both
 *       exhaustively for both players).</li>
 * </ul>
 * All other rule switches may take any value: {@code leaveGoal} (the plan respects it), {@code blockadeRule},
 * {@code suicideMoves} and {@code drawHandling} (see below why they cannot interfere).
 *
 * <p>The verdict "W wins at ply {@code endPly}" is returned only when a plan of U W-moves exists such that
 * <ol>
 *   <li>(front) if Lo moves first, every W piece stands on a row {@code >= F + 3}; after W's move t, if a Lo move
 *       follows, every W piece stands on a row {@code >= F + k + 3} where k is the number of Lo moves made before
 *       that Lo move;</li>
 *   <li>(landings) every landing cell of W's move t, intermediate landings of a jump chain included, lies on a row
 *       {@code >= F + k + 1} with k the number of Lo moves made before W's move t;</li>
 *   <li>(hurdles) every chain of the plan jumps over W pieces only, and the plan is a legal solitaire sequence
 *       under {@code leaveGoal} that ends with all 10 W pieces in W's goal (the engine's move generator confirms
 *       every plan move on the W-only position, and an independent breadth-first search confirms the restricted
 *       path);</li>
 *   <li>(tempo) {@code U <= LB} if W is to move, {@code U <= LB - 1} if Lo is to move, where LB is the parity
 *       assignment lower bound of Lo's moves ({@link Solitaire#lowerBound}); and</li>
 *   <li>(limit) {@code endPly = ply + 2U - 1} (W to move) or {@code ply + 2U} (Lo to move) is {@code <=
 *       moveLimit}.</li>
 * </ol>
 *
 * <p>Claim: whatever Lo plays, W's plan moves are legal when W is to play them, nobody else wins first and the game
 * does not end otherwise, so W wins on or before {@code endPly}.
 *
 * <p>Proof. <em>Lemma (front advance).</em> Before Lo's move number k + 1 let every Lo piece stand on a row {@code
 * <= F + k} and every W piece on a row {@code >= F + k + 3}. Then after the move every Lo piece stands on a row
 * {@code <= F + k + 1}. A step moves one row at most. In a jump chain the moving piece starts on a row {@code <= F
 * + k}; a jump that gains two rows from row x needs a hurdle on row x + 1; a Lo hurdle lies on a row {@code <= F +
 * k}, so such a jump starts on a row {@code <= F + k - 1} and lands on a row {@code <= F + k + 1}; a W hurdle lies
 * on a row {@code >= F + k + 3}, so such a jump would have to start on a row {@code >= F + k + 2}, which by
 * induction over the chain the piece never reaches (it starts at {@code <= F + k} and every jump so far landed at
 * {@code <= F + k + 1}). Jumps that keep or lose rows cannot raise the maximum. Hence the bound; and Lo never
 * stands next to a W piece during a move (rows differ by at least 2), so Lo never jumps over a W piece.
 *
 * <p>By induction over Lo's moves with condition 1 as the hypothesis, after k Lo moves every Lo piece stands on a
 * row {@code <= F + k}. Condition 2 then says that no landing cell of W's move t holds a Lo piece, condition 3 that
 * its hurdles stand where the plan put them (Lo cannot move W pieces) and that the move is legal in the W-only
 * position; the only further rule that could forbid it is Definition 2 (a move that fills Lo's goal), which needs
 * all 10 of Lo's goal cells occupied: W pieces never enter Lo's goal (in a passed position fewer than 10 cells lie
 * behind each goal's front row, so a W piece in Lo's goal would leave fewer than 10 cells for Lo, and the plan's
 * landings lie on rows {@code >= F + 1 >= -3}), so Lo's goal is full only when Lo has already won. The same count
 * shows that no Lo piece stands in W's goal, so W's final configuration fills W's goal with own pieces, a win under
 * both blockade rules. Lo cannot win first: Lo's pieces never jump over W pieces (lemma) and no W piece is in Lo's
 * goal, so Lo plays a solitaire with own hurdles only and needs at least LB moves (each move moves one piece; a
 * piece reaches a goal cell in one move only by a step to an adjacent cell or a chain to a cell of the same class
 * {@code (q mod 2, r mod 2)}, which jumps preserve); condition 4 puts W's last move before Lo's LB-th move.
 * The game cannot end otherwise: the move limit is respected by condition 5, repetition cannot end the game under
 * {@code MOVE_LIMIT}, and Lo always has a legal move (so the game never ends for lack of moves): Lo's piece with
 * the largest row outside its goal stands on a row {@code <= F + k <= 5}; its neighbours on the next row are empty
 * (no Lo piece is further, W pieces are at least three rows further), one of them is a cell Lo may stand on
 * (geometry precondition), the move does not leave Lo's goal, and it fills W's goal only if W's goal is already
 * full of W pieces, in which case W has won before. If every Lo piece is in Lo's goal, Lo has won, which condition
 * 4 excludes. End of proof.
 *
 * <p>What this does not claim: the plan need not be shortest and Lo's bound need not be tight, so S1 can fail to
 * fire in won positions; it never fires in a position that is not won. The surveyor's unguarded fast path and
 * the use of a region table count as Lo's bound (an upper bound, not a lower bound) are deliberately absent.
 *
 * <p>Redundancy, found by a mutation check: condition 2 follows from conditions 1 and 3. Before W's move t every W
 * piece stands on a row {@code >= F + k + 2} (condition 1 for the previous Lo move, or the precondition when Lo
 * moves first), a step lands at most one row lower, and a chain over W hurdles can lose two rows only over a W
 * hurdle one row below its start, so no landing lies below {@code F + k + 1}. The code still checks condition 2
 * separately in {@link #verify}, as a guard against a future change of the plan search; a test cannot tell the
 * two apart.
 *
 * <p>Not thread-safe (it keeps solitaire scratch space); one instance per thread.
 */
public final class RaceAdjudication {

    /** Beam width of the plan search: wide enough that a plan is rarely missed, cheap enough for every ply. */
    public static final int DEFAULT_WIDTH = 300;

    /**
     * The verdict: {@code winner} wins on or before {@code endPly} with a plan of {@code winnerMoves} moves; the
     * loser needs at least {@code loserBound} moves; {@code front} is the loser's front row in the winner's rows.
     */
    public record Verdict(int winner, int endPly, int winnerMoves, int loserBound, int front, Solitaire.Plan plan) {
    }

    private final RaceCounts table;
    private final int width;

    /** @param table race counts to order the beam, or null */
    public RaceAdjudication(RaceCounts table) {
        this(table, DEFAULT_WIDTH);
    }

    public RaceAdjudication(RaceCounts table, int width) {
        this.table = table;
        this.width = width;
    }

    /** The rule preconditions of the soundness statement. */
    public static boolean applicable(RuleSet rules) {
        return rules.players() == 2 && rules.jumpRule() == RuleSet.JumpRule.ADJACENT
                && rules.foreignCamps() == RuleSet.ForeignCamps.CLOSED
                && rules.blockingStates() == BlockingStates.NONE
                && rules.repetition() == Repetition.MOVE_LIMIT;
    }

    /**
     * Whether the armies have passed each other: every piece of player 0 has a larger {@code r} than every piece of
     * player 1 (both with all their pieces on the board). Two-player positions only.
     */
    public static boolean passed(Position pos) {
        if (pos.rules().players() != 2 || pos.rules().jumpRule() != RuleSet.JumpRule.ADJACENT
                || pos.rules().foreignCamps() != RuleSet.ForeignCamps.CLOSED) {
            return false;
        }
        int min0 = Integer.MAX_VALUE;
        int max1 = Integer.MIN_VALUE;
        int n0 = 0;
        int n1 = 0;
        for (int c = 0; c < Geometry.CELLS; c++) {
            int o = pos.owner(c);
            if (o == 0) {
                n0++;
                min0 = Math.min(min0, Geometry.r(c));
            } else if (o == 1) {
                n1++;
                max1 = Math.max(max1, Geometry.r(c));
            }
        }
        return n0 == Position.PIECES && n1 == Position.PIECES && min0 > max1;
    }

    /** The S1 verdict for a position, or null if the criterion does not apply or does not fire. */
    public Verdict adjudicate(Position pos) {
        if (!applicable(pos.rules()) || pos.outcome() != null || !passed(pos)) {
            return null;
        }
        int stm = pos.sideToMove();
        Verdict v = tryWinner(pos, stm);
        return v != null ? v : tryWinner(pos, 1 - stm);
    }

    private Verdict tryWinner(Position pos, int w) {
        int lo = 1 - w;
        RuleSet rules = pos.rules();
        int[] cellsW = pos.pieces(w);
        int[] cellsLo = pos.pieces(lo);
        Solitaire solW = new Solitaire(w, rules.leaveGoal(), table);
        Solitaire solLo = new Solitaire(lo, rules.leaveGoal(), null);
        if (solW.isGoal(cellsW) || solLo.isGoal(cellsLo)) {
            return null; // the game would be over
        }
        boolean wToMove = pos.sideToMove() == w;
        int front = solW.frontRow(cellsLo);
        int loBound = solLo.lowerBound(cellsLo);
        int pliesLeft = rules.moveLimit() - pos.ply();
        int maxMoves = Math.min(wToMove ? loBound : loBound - 1, wToMove ? (pliesLeft + 1) / 2 : pliesLeft / 2);
        if (maxMoves < 1 || solW.lowerBound(cellsW) > maxMoves) {
            return null;
        }
        if (!wToMove && solW.rearRow(cellsW) < front + 3) {
            return null; // Lo moves now; the lemma needs the gap already
        }
        int loMovesBeforeW = wToMove ? -1 : 0; // k for W's move t is t + loMovesBeforeW
        Solitaire.Plan plan = solW.plan(cellsW, width, maxMoves,
                t -> front + (t + loMovesBeforeW) + 1,
                t -> front + (t + loMovesBeforeW) + 3);
        if (plan == null) {
            return null;
        }
        verify(pos, w, front, plan, wToMove);
        int u = plan.moves();
        int endPly = pos.ply() + (wToMove ? 2 * u - 1 : 2 * u);
        return new Verdict(w, endPly, u, loBound, front, plan);
    }

    /**
     * Re-derives conditions 1 to 3 of the statement for a plan without trusting the beam: engine legality of every
     * move on the W-only position, an independent restricted path search, the landing and rear rows, and the final
     * configuration. A failure is a bug in the plan search and is reported loudly.
     */
    static void verify(Position pos, int w, int front, Solitaire.Plan plan, boolean wToMove) {
        RuleSet rules = pos.rules();
        int u = plan.moves();
        List<int[]> states = plan.states();
        if (states.size() != u + 1 || !Arrays.equals(states.get(0), pos.pieces(w))) {
            throw new IllegalStateException("plan does not start at the position");
        }
        Solitaire solW = new Solitaire(w, rules.leaveGoal(), null);
        if (!wToMove && solW.rearRow(states.get(0)) < front + 3) {
            throw new IllegalStateException("front condition violated before Lo's first move");
        }
        for (int t = 1; t <= u; t++) {
            int k = wToMove ? t - 1 : t;
            Solitaire.Step step = plan.steps().get(t - 1);
            int[] before = states.get(t - 1);
            int[] after = states.get(t);
            if (step.pathMinRow(w) < front + k + 1) {
                throw new IllegalStateException("landing condition violated at move " + t);
            }
            if (!reachableRestricted(w, rules, before, step.from(), step.path(), front + k + 1)) {
                throw new IllegalStateException("plan path not reproducible at move " + t);
            }
            Position solo = Position.empty(rules);
            for (int c : before) {
                solo.put(w, c);
            }
            solo.setSideToMove(w);
            int move = step.move();
            if (Arrays.stream(solo.legalMoves()).noneMatch(m -> m == move)) {
                throw new IllegalStateException("plan move " + Move.toString(move) + " is not legal at move " + t);
            }
            int[] expected = before.clone();
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] == step.from()) {
                    expected[i] = step.to();
                }
            }
            Arrays.sort(expected);
            if (!Arrays.equals(expected, after)) {
                throw new IllegalStateException("plan state mismatch at move " + t);
            }
            boolean loMoveFollows = t < u;
            if (loMoveFollows && solW.rearRow(after) < front + k + 3) {
                throw new IllegalStateException("front condition violated after move " + t);
            }
        }
        if (!solW.isGoal(states.get(u))) {
            throw new IllegalStateException("plan does not end in the goal");
        }
    }

    /**
     * Independent check that the given path (landing cells in order) is a legal step or jump chain of the piece on
     * {@code from} over the player's own pieces, with every landing on a row {@code >= minRow}. Written against
     * {@link Geometry} directly, not against {@link Solitaire}.
     */
    static boolean reachableRestricted(int player, RuleSet rules, int[] own, int from, int[] path, int minRow) {
        Layout layout = Layout.of(2);
        boolean[] occupied = new boolean[Geometry.CELLS];
        for (int c : own) {
            occupied[c] = true;
        }
        if (!occupied[from]) {
            return false;
        }
        occupied[from] = false;
        int to = path[path.length - 1];
        if (rules.leaveGoal() == RuleSet.LeaveGoal.FORBIDDEN && Geometry.cornerOf(from) == layout.goalCorner(player)
                && Geometry.cornerOf(to) != layout.goalCorner(player)) {
            return false;
        }
        for (int c : path) {
            if (occupied[c] || !layout.allowed(player, c) || Rows.row(player, c) < minRow || c == from) {
                return false;
            }
        }
        if (path.length == 1) {
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                if (Geometry.neighbor(from, d) == to) {
                    return true;
                }
            }
        }
        int current = from;
        for (int land : path) {
            boolean jumped = false;
            for (int d = 0; d < Geometry.DIRECTIONS && !jumped; d++) {
                int over = Geometry.neighbor(current, d);
                jumped = over >= 0 && occupied[over] && Geometry.neighbor(over, d) == land;
            }
            if (!jumped) {
                return false;
            }
            current = land;
        }
        // the path must not revisit a cell (a chain may not return to its start cell either)
        ArrayDeque<Integer> seen = new ArrayDeque<>();
        seen.add(from);
        for (int c : path) {
            if (seen.contains(c)) {
                return false;
            }
            seen.add(c);
        }
        return true;
    }
}
