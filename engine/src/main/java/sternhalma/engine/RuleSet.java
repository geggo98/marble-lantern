package sternhalma.engine;

import java.util.Objects;

/**
 * The rule switches of docs/rules.md. Immutable.
 *
 * <p>Values that the rules document reserves or does not define yet are rejected with an
 * {@link UnsupportedOperationException} instead of being ignored silently.
 */
public record RuleSet(
        int players,
        int moveLimit,
        LeaveGoal leaveGoal,
        BlockadeRule blockadeRule,
        SuicideMoves suicideMoves,
        BlockingStates blockingStates,
        Repetition repetition,
        DrawHandling drawHandling,
        Ranking ranking,
        JumpRule jumpRule) {

    public enum LeaveGoal { FORBIDDEN, ALLOWED }

    public enum BlockadeRule { NONE, OPPONENT_FILLS }

    public enum SuicideMoves { ILLEGAL, LEGAL }

    public enum BlockingStates { NONE, ILLEGAL }

    public enum Repetition { MOVE_LIMIT, REPETITION_IS_DRAW }

    public enum DrawHandling { DRAW, SCORE_BY_DISTANCE }

    public enum Ranking { FIRST_WINNER_ONLY, FULL_RANKING }

    /**
     * How a piece jumps. {@code ADJACENT}: over one adjacent piece to the cell right behind it (default).
     * {@code LONG}: the Halma long hop, over the first piece in a line from any distance k to the mirror cell
     * (docs/rules.md, "Jump rule").
     */
    public enum JumpRule { ADJACENT, LONG }

    /** The rule set with the default jump rule {@link JumpRule#ADJACENT}. */
    public RuleSet(int players, int moveLimit, LeaveGoal leaveGoal, BlockadeRule blockadeRule,
            SuicideMoves suicideMoves, BlockingStates blockingStates, Repetition repetition,
            DrawHandling drawHandling, Ranking ranking) {
        this(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates, repetition, drawHandling,
                ranking, JumpRule.ADJACENT);
    }

    public RuleSet {
        if (players != 2 && players != 3) {
            throw new IllegalArgumentException("players must be 2 or 3, got " + players);
        }
        if (moveLimit <= 0) {
            throw new IllegalArgumentException("moveLimit must be positive, got " + moveLimit);
        }
        Objects.requireNonNull(leaveGoal, "leaveGoal");
        Objects.requireNonNull(blockadeRule, "blockadeRule");
        Objects.requireNonNull(suicideMoves, "suicideMoves");
        Objects.requireNonNull(blockingStates, "blockingStates");
        Objects.requireNonNull(repetition, "repetition");
        Objects.requireNonNull(drawHandling, "drawHandling");
        Objects.requireNonNull(ranking, "ranking");
        Objects.requireNonNull(jumpRule, "jumpRule");
        if (blockingStates == BlockingStates.ILLEGAL) {
            throw new UnsupportedOperationException(
                    "blockingStates=ILLEGAL needs an exact definition of 'unreachable' for 10 pieces (docs/rules.md)");
        }
        if (ranking == Ranking.FULL_RANKING) {
            throw new UnsupportedOperationException("ranking=FULL_RANKING is reserved and not implemented");
        }
    }

    /** The project defaults of docs/rules.md: 200 plies for two players, 300 for three. */
    public static RuleSet defaults(int players) {
        return new RuleSet(
                players,
                players == 2 ? 200 : 300,
                LeaveGoal.FORBIDDEN,
                BlockadeRule.OPPONENT_FILLS,
                SuicideMoves.ILLEGAL,
                BlockingStates.NONE,
                Repetition.MOVE_LIMIT,
                DrawHandling.SCORE_BY_DISTANCE,
                Ranking.FIRST_WINNER_ONLY,
                JumpRule.ADJACENT);
    }

    public RuleSet withMoveLimit(int value) {
        return new RuleSet(players, value, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule);
    }

    public RuleSet withLeaveGoal(LeaveGoal value) {
        return new RuleSet(players, moveLimit, value, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule);
    }

    public RuleSet withBlockadeRule(BlockadeRule value) {
        return new RuleSet(players, moveLimit, leaveGoal, value, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule);
    }

    public RuleSet withSuicideMoves(SuicideMoves value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, value, blockingStates,
                repetition, drawHandling, ranking, jumpRule);
    }

    public RuleSet withRepetition(Repetition value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                value, drawHandling, ranking, jumpRule);
    }

    public RuleSet withDrawHandling(DrawHandling value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, value, ranking, jumpRule);
    }

    public RuleSet withJumpRule(JumpRule value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, value);
    }
}
