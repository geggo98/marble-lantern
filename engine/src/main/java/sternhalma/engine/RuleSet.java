package sternhalma.engine;

import java.util.Objects;

/**
 * The rule switches of docs/rules.md. Immutable.
 *
 * <p>Values that the rules document reserves or does not define yet ({@code blockingStates=ILLEGAL}) are
 * rejected with an {@link UnsupportedOperationException} instead of being ignored silently.
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
        JumpRule jumpRule,
        ForeignCamps foreignCamps) {

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

    /**
     * Whether a piece may enter a foreign corner: a corner triangle that is neither the mover's start corner nor the
     * mover's goal corner (docs/rules.md, "Foreign corners"). {@code CLOSED} (default): a piece never stands on such a
     * cell, not even between two jumps of a chain. {@code PASS_THROUGH}: a jump chain may land there between two
     * jumps, but the move must not end there; a step into a foreign corner is not a move. {@code OPEN}: a move may
     * also end there.
     */
    public enum ForeignCamps { CLOSED, PASS_THROUGH, OPEN }

    /** The rule set with the default jump rule {@link JumpRule#ADJACENT} and closed foreign corners. */
    public RuleSet(int players, int moveLimit, LeaveGoal leaveGoal, BlockadeRule blockadeRule,
            SuicideMoves suicideMoves, BlockingStates blockingStates, Repetition repetition,
            DrawHandling drawHandling, Ranking ranking) {
        this(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates, repetition, drawHandling,
                ranking, JumpRule.ADJACENT, ForeignCamps.CLOSED);
    }

    /** The rule set with closed foreign corners ({@link ForeignCamps#CLOSED}). */
    public RuleSet(int players, int moveLimit, LeaveGoal leaveGoal, BlockadeRule blockadeRule,
            SuicideMoves suicideMoves, BlockingStates blockingStates, Repetition repetition,
            DrawHandling drawHandling, Ranking ranking, JumpRule jumpRule) {
        this(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates, repetition, drawHandling,
                ranking, jumpRule, ForeignCamps.CLOSED);
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
        Objects.requireNonNull(foreignCamps, "foreignCamps");
        if (blockingStates == BlockingStates.ILLEGAL) {
            throw new UnsupportedOperationException(
                    "blockingStates=ILLEGAL needs an exact definition of 'unreachable' for 10 pieces (docs/rules.md)");
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
                JumpRule.ADJACENT,
                ForeignCamps.CLOSED);
    }

    public RuleSet withMoveLimit(int value) {
        return new RuleSet(players, value, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule, foreignCamps);
    }

    public RuleSet withLeaveGoal(LeaveGoal value) {
        return new RuleSet(players, moveLimit, value, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule, foreignCamps);
    }

    public RuleSet withBlockadeRule(BlockadeRule value) {
        return new RuleSet(players, moveLimit, leaveGoal, value, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule, foreignCamps);
    }

    public RuleSet withSuicideMoves(SuicideMoves value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, value, blockingStates,
                repetition, drawHandling, ranking, jumpRule, foreignCamps);
    }

    public RuleSet withRepetition(Repetition value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                value, drawHandling, ranking, jumpRule, foreignCamps);
    }

    public RuleSet withDrawHandling(DrawHandling value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, value, ranking, jumpRule, foreignCamps);
    }

    public RuleSet withRanking(Ranking value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, value, jumpRule, foreignCamps);
    }

    /**
     * Whether a player who fills the goal leaves the game while the others play on for the lower ranks
     * ({@link Ranking#FULL_RANKING} with three players). With two players the ranking switch has no effect.
     */
    public boolean playsOnAfterWin() {
        return ranking == Ranking.FULL_RANKING && players > 2;
    }

    public RuleSet withJumpRule(JumpRule value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, value, foreignCamps);
    }

    /**
     * The record text, e.g. {@code RuleSet[players=2, moveLimit=200, ..., jumpRule=ADJACENT]}. {@code foreignCamps}
     * appears only when it is not {@code CLOSED}, so the text of every rule set that existed before the switch stays
     * byte for byte the same: shard metadata and their golden hashes contain it, and a reader treats a missing key as
     * {@code CLOSED} (GameShard.rules).
     */
    @Override
    public String toString() {
        return "RuleSet[players=" + players + ", moveLimit=" + moveLimit + ", leaveGoal=" + leaveGoal
                + ", blockadeRule=" + blockadeRule + ", suicideMoves=" + suicideMoves + ", blockingStates="
                + blockingStates + ", repetition=" + repetition + ", drawHandling=" + drawHandling + ", ranking="
                + ranking + ", jumpRule=" + jumpRule
                + (foreignCamps == ForeignCamps.CLOSED ? "" : ", foreignCamps=" + foreignCamps) + "]";
    }

    public RuleSet withForeignCamps(ForeignCamps value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule, value);
    }
}
