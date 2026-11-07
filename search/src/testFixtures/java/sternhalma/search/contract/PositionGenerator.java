package sternhalma.search.contract;

import java.util.Random;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Generates legal, non-terminal positions for property tests by playing legal moves from the start position.
 * Everything is a function of the arguments, so a failing case can be rebuilt from its printed description.
 */
public final class PositionGenerator {

    /** The rule variants that the contract tests cover; index 0 is the project default. */
    public static final int VARIANTS = 4;

    private PositionGenerator() {
    }

    /** A rule set for a player count and a variant index in 0 .. {@link #VARIANTS} - 1. */
    public static RuleSet rules(int players, int variant) {
        RuleSet base = RuleSet.defaults(players);
        return switch (variant) {
            case 0 -> base;
            case 1 -> base.withLeaveGoal(RuleSet.LeaveGoal.ALLOWED);
            case 2 -> base.withRepetition(RuleSet.Repetition.REPETITION_IS_DRAW)
                    .withDrawHandling(RuleSet.DrawHandling.DRAW);
            case 3 -> base.withBlockadeRule(RuleSet.BlockadeRule.NONE)
                    .withSuicideMoves(RuleSet.SuicideMoves.LEGAL)
                    .withMoveLimit(base.moveLimit() / 2);
            default -> throw new IllegalArgumentException("variant " + variant);
        };
    }

    /**
     * A position after up to {@code plies} legal plies. With probability {@code greed} a ply is the move that
     * gains the most single-agent distance for the mover (this reaches late-game positions), otherwise a
     * uniformly random legal move (this reaches odd positions). The generation stops early where the game
     * would end, and the result always has at least one legal move and no outcome yet.
     */
    public static Position played(RuleSet rules, long seed, int plies, double greed) {
        Random random = new Random(seed);
        Position pos = Position.initial(rules);
        for (int i = 0; i < plies; i++) {
            if (pos.outcome() != null) {
                break;
            }
            int[] moves = pos.legalMoves();
            if (moves.length == 0) {
                break;
            }
            pos.make(random.nextDouble() < greed ? bestGain(pos, moves, random) : moves[random.nextInt(moves.length)]);
        }
        return backToPlayable(pos);
    }

    /**
     * The last position before a game ends: greedy play until the game is over, then one move taken back.
     * A winning or limit-reaching move is available there, which is the edge of the terminal handling.
     */
    public static Position justBeforeTheEnd(RuleSet rules, long seed) {
        Random random = new Random(seed);
        Position pos = Position.initial(rules);
        while (pos.outcome() == null) {
            int[] moves = pos.legalMoves();
            if (moves.length == 0) {
                break;
            }
            pos.make(random.nextDouble() < 0.9 ? bestGain(pos, moves, random) : moves[random.nextInt(moves.length)]);
        }
        return backToPlayable(pos);
    }

    /** Takes moves back until the position can be played on. The start position always can. */
    private static Position backToPlayable(Position pos) {
        while (pos.ply() > 0 && (pos.outcome() != null || pos.legalMoves().length == 0)) {
            pos.unmake();
        }
        return pos;
    }

    private static int bestGain(Position pos, int[] moves, Random random) {
        Layout layout = pos.layout();
        int player = pos.sideToMove();
        int best = Integer.MIN_VALUE;
        int chosen = moves[0];
        int ties = 0;
        for (int m : moves) {
            int gain = layout.distance(player, Move.from(m)) - layout.distance(player, Move.to(m));
            if (gain > best) {
                best = gain;
                chosen = m;
                ties = 1;
            } else if (gain == best && random.nextInt(++ties) == 0) {
                chosen = m;
            }
        }
        return chosen;
    }

    /** A readable picture of the position for failure messages. */
    public static String describe(Position pos) {
        StringBuilder sb = new StringBuilder();
        sb.append(pos.rules().players()).append(" players, ply ").append(pos.ply())
                .append(", side to move ").append(pos.sideToMove()).append('\n');
        int cells = Geometry.CELLS;
        for (int p = 0; p < pos.rules().players(); p++) {
            sb.append("player ").append(p).append(':');
            for (int c = 0; c < cells; c++) {
                if (pos.owner(c) == p) {
                    sb.append(" (").append(Geometry.q(c)).append(',').append(Geometry.r(c)).append(')');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
