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
        ForeignCamps foreignCamps,
        TriangleRows triangleRows) {

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

    /**
     * The size of the start and goal houses (docs/rules.md, "Board size"). {@code FOUR} (default): a house is the
     * corner triangle of 4 rows, 10 cells, 10 pieces per player. {@code FIVE}: a house is the corner triangle plus the
     * hexagon edge row in front of it, 5 rows and 15 cells, 15 pieces per player. The board has 121 cells either way.
     */
    public enum TriangleRows {
        FOUR(4), FIVE(5);

        private final int rows;

        TriangleRows(int rows) {
            this.rows = rows;
        }

        /** Rows of a house, and cells on each side of the triangle: 4 or 5. */
        public int rows() {
            return rows;
        }

        /** Cells of a house and pieces per player: 10 or 15. */
        public int pieces() {
            return rows * (rows + 1) / 2;
        }
    }

    /** The rule set with the default jump rule {@link JumpRule#ADJACENT} and closed foreign corners. */
    public RuleSet(int players, int moveLimit, LeaveGoal leaveGoal, BlockadeRule blockadeRule,
            SuicideMoves suicideMoves, BlockingStates blockingStates, Repetition repetition,
            DrawHandling drawHandling, Ranking ranking) {
        this(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates, repetition, drawHandling,
                ranking, JumpRule.ADJACENT, ForeignCamps.CLOSED, TriangleRows.FOUR);
    }

    /** The rule set with closed foreign corners ({@link ForeignCamps#CLOSED}). */
    public RuleSet(int players, int moveLimit, LeaveGoal leaveGoal, BlockadeRule blockadeRule,
            SuicideMoves suicideMoves, BlockingStates blockingStates, Repetition repetition,
            DrawHandling drawHandling, Ranking ranking, JumpRule jumpRule) {
        this(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates, repetition, drawHandling,
                ranking, jumpRule, ForeignCamps.CLOSED, TriangleRows.FOUR);
    }

    /** The rule set with 4-row houses ({@link TriangleRows#FOUR}). */
    public RuleSet(int players, int moveLimit, LeaveGoal leaveGoal, BlockadeRule blockadeRule,
            SuicideMoves suicideMoves, BlockingStates blockingStates, Repetition repetition,
            DrawHandling drawHandling, Ranking ranking, JumpRule jumpRule, ForeignCamps foreignCamps) {
        this(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates, repetition, drawHandling,
                ranking, jumpRule, foreignCamps, TriangleRows.FOUR);
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
        Objects.requireNonNull(triangleRows, "triangleRows");
        if (blockingStates == BlockingStates.ILLEGAL) {
            throw new UnsupportedOperationException(
                    "blockingStates=ILLEGAL needs an exact definition of 'unreachable' (docs/rules.md)");
        }
    }

    /** The project defaults of docs/rules.md: 4-row houses, 200 plies for two players, 300 for three. */
    public static RuleSet defaults(int players) {
        return defaults(players, TriangleRows.FOUR);
    }

    /**
     * The project defaults for a house size: 200 plies for two players and 300 for three with
     * {@link TriangleRows#FOUR}, 250 and 375 with {@link TriangleRows#FIVE} (15 instead of 10 pieces to bring home,
     * so about 1.25 times as many plies).
     */
    public static RuleSet defaults(int players, TriangleRows rows) {
        int base = players == 2 ? 200 : 300;
        return new RuleSet(
                players,
                rows == TriangleRows.FIVE ? base * 5 / 4 : base,
                LeaveGoal.FORBIDDEN,
                BlockadeRule.OPPONENT_FILLS,
                SuicideMoves.ILLEGAL,
                BlockingStates.NONE,
                Repetition.MOVE_LIMIT,
                DrawHandling.SCORE_BY_DISTANCE,
                Ranking.FIRST_WINNER_ONLY,
                JumpRule.ADJACENT,
                ForeignCamps.CLOSED,
                rows);
    }

    public RuleSet withMoveLimit(int value) {
        return new RuleSet(players, value, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule, foreignCamps, triangleRows);
    }

    public RuleSet withLeaveGoal(LeaveGoal value) {
        return new RuleSet(players, moveLimit, value, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule, foreignCamps, triangleRows);
    }

    public RuleSet withBlockadeRule(BlockadeRule value) {
        return new RuleSet(players, moveLimit, leaveGoal, value, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule, foreignCamps, triangleRows);
    }

    public RuleSet withSuicideMoves(SuicideMoves value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, value, blockingStates,
                repetition, drawHandling, ranking, jumpRule, foreignCamps, triangleRows);
    }

    public RuleSet withRepetition(Repetition value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                value, drawHandling, ranking, jumpRule, foreignCamps, triangleRows);
    }

    public RuleSet withDrawHandling(DrawHandling value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, value, ranking, jumpRule, foreignCamps, triangleRows);
    }

    public RuleSet withRanking(Ranking value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, value, jumpRule, foreignCamps, triangleRows);
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
                repetition, drawHandling, ranking, value, foreignCamps, triangleRows);
    }

    /**
     * The record text, e.g. {@code RuleSet[players=2, moveLimit=200, ..., jumpRule=ADJACENT]}. {@code foreignCamps}
     * appears only when it is not {@code CLOSED}, and {@code triangleRows} only when it is not {@code FOUR}, so the text of every rule set that existed before the switch stays
     * byte for byte the same: shard metadata and their golden hashes contain it, and a reader treats a missing key as
     * {@code CLOSED} (GameShard.rules).
     */
    @Override
    public String toString() {
        return "RuleSet[players=" + players + ", moveLimit=" + moveLimit + ", leaveGoal=" + leaveGoal
                + ", blockadeRule=" + blockadeRule + ", suicideMoves=" + suicideMoves + ", blockingStates="
                + blockingStates + ", repetition=" + repetition + ", drawHandling=" + drawHandling + ", ranking="
                + ranking + ", jumpRule=" + jumpRule
                + (foreignCamps == ForeignCamps.CLOSED ? "" : ", foreignCamps=" + foreignCamps)
                + (triangleRows == TriangleRows.FOUR ? "" : ", triangleRows=" + triangleRows) + "]";
    }

    public RuleSet withForeignCamps(ForeignCamps value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule, value, triangleRows);
    }

    public RuleSet withTriangleRows(TriangleRows value) {
        return new RuleSet(players, moveLimit, leaveGoal, blockadeRule, suicideMoves, blockingStates,
                repetition, drawHandling, ranking, jumpRule, foreignCamps, value);
    }
}
