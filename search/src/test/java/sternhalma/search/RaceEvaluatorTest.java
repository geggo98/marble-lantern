package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.tablebase.RaceCounts;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.Rows;

/**
 * The race leaf value of round-2 experiment E08 ({@link RaceEvaluator}): the conversion rule and its band, where it
 * falls back to the wrapped evaluator, its symmetry, and the futility hook against a brute force over all moves.
 */
class RaceEvaluatorTest {

    private static final RuleSet RULES = RuleSet.defaults(2);
    private static final Evaluator LINEAR = Evaluators.byName("linear-r2");
    private static final RaceTable TABLE = RaceTable.rows2();
    private static final RaceEvaluator RACE = new RaceEvaluator(LINEAR, TABLE);

    /**
     * A random passed position with every piece of each player on its own rows {@code >= minRow} (so with
     * {@code minRow >= 2} both configurations are covered by the rows &gt;= 2 table) and the game not over.
     */
    static Position deepPassed(Random random, int sideToMove, int minRow) {
        Layout layout = Layout.of(2);
        while (true) {
            Position pos = Position.empty(RULES);
            for (int p = 0; p < 2; p++) {
                int placed = 0;
                while (placed < Position.PIECES) {
                    int c = random.nextInt(Geometry.CELLS);
                    if (layout.allowed(p, c) && Rows.row(p, c) >= minRow && pos.owner(c) < 0) {
                        pos.put(p, c);
                        placed++;
                    }
                }
            }
            pos.setSideToMove(sideToMove);
            if (pos.outcome() == null) {
                return pos;
            }
        }
    }

    private static int count(Position pos, int player) {
        return TABLE.count(Rows.toPlayer0(player, pos.pieces(player)));
    }

    /** Positions from random games of random length (mostly contact positions, some late ones). */
    static List<Position> randomGames(Random random, int count) {
        List<Position> result = new ArrayList<>();
        while (result.size() < count) {
            Position pos = Position.initial(RULES);
            int plies = random.nextInt(150);
            for (int i = 0; i < plies && pos.outcome() == null; i++) {
                int[] moves = pos.legalMoves();
                if (moves.length == 0) {
                    break;
                }
                pos.make(moves[random.nextInt(moves.length)]);
            }
            if (pos.outcome() == null) {
                result.add(pos);
            }
        }
        return result;
    }

    /** The same pieces and side to move under other rules (ply 0). */
    private static Position withRules(Position pos, RuleSet rules) {
        Position copy = Position.empty(rules);
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (pos.owner(c) >= 0) {
                copy.put(pos.owner(c), c);
            }
        }
        return copy.setSideToMove(pos.sideToMove());
    }

    @Test
    void theConversionRuleDecidesSignAndMargin() {
        // a = moves of the side to move, b = the opponent's, L = plies left: the mover wins if a <= b and 2a - 1 <= L
        assertEquals(Scores.RACE_BASE, RaceEvaluator.raceScore(1, 1, 200), "equal counts: the tempo decides");
        assertEquals(Scores.RACE_BASE + 200 * 4, RaceEvaluator.raceScore(3, 7, 200));
        assertEquals(-Scores.RACE_BASE, RaceEvaluator.raceScore(2, 1, 200), "one move behind: a lost race by a tempo");
        assertEquals(-(Scores.RACE_BASE + 200 * 3), RaceEvaluator.raceScore(7, 3, 200));
        // the move limit: the mover finishes on ply 2a - 1, the opponent on ply 2b
        assertEquals(Scores.RACE_BASE + 400, RaceEvaluator.raceScore(3, 5, 5));
        assertEquals(RaceEvaluator.UNDECIDED, RaceEvaluator.raceScore(3, 5, 4), "nobody finishes in 4 plies");
        assertEquals(-Scores.RACE_BASE - 200, RaceEvaluator.raceScore(5, 3, 6));
        assertEquals(RaceEvaluator.UNDECIDED, RaceEvaluator.raceScore(5, 3, 5));
        // every race value lies in the band, up to the largest table count
        for (int a = 1; a <= 19; a++) {
            for (int b = 1; b <= 19; b++) {
                int s = RaceEvaluator.raceScore(a, b, 200);
                assertEquals(Scores.Kind.RACE, Scores.kind(s), a + " vs " + b);
                assertEquals(a <= b, s > 0);
            }
        }
    }

    @Test
    void aCoveredPassedPositionGetsTheRaceValueOfItsTableCounts() {
        Random random = new Random(81);
        int wins = 0;
        int losses = 0;
        for (int i = 0; i < 300; i++) {
            Position pos = deepPassed(random, random.nextInt(2), 2);
            int me = pos.sideToMove();
            int a = count(pos, me);
            int b = count(pos, 1 - me);
            assertTrue(a > 0 && b > 0, "both covered and not finished");
            int v = RACE.evaluate(pos);
            assertEquals(RaceEvaluator.raceScore(a, b, RULES.moveLimit()), v, pos.toString());
            assertEquals(Scores.Kind.RACE, Scores.kind(v));
            assertNotEquals(LINEAR.evaluate(pos), v, "the race value replaces the linear one");
            if (v > 0) {
                wins++;
            } else {
                losses++;
            }
        }
        assertTrue(wins > 50 && losses > 50, wins + " won, " + losses + " lost races in the sample");
    }

    @Test
    void theMoveLimitTurnsAnUnfinishableRaceBackToTheWrappedValue() {
        Random random = new Random(82);
        int decided = 0;
        int undecided = 0;
        for (int i = 0; i < 200; i++) {
            Position pos = deepPassed(random, random.nextInt(2), 2);
            int me = pos.sideToMove();
            int a = count(pos, me);
            int b = count(pos, 1 - me);
            for (int left : new int[] {2 * Math.min(a, b) + 1, Math.max(1, 2 * Math.min(a, b) - 2), 1}) {
                Position limited = withRules(pos, RULES.withMoveLimit(left));
                int expected = RaceEvaluator.raceScore(a, b, left);
                int v = RACE.evaluate(limited);
                if (expected == RaceEvaluator.UNDECIDED) {
                    assertEquals(LINEAR.evaluate(limited), v, "nobody finishes: the wrapped value");
                    undecided++;
                } else {
                    assertEquals(expected, v);
                    decided++;
                }
            }
        }
        assertTrue(decided > 100 && undecided > 100, decided + " decided, " + undecided + " undecided");
    }

    @Test
    void outsideThePassOrTheTableItIsTheWrappedEvaluator() {
        Random random = new Random(83);
        int passedUncovered = 0;
        // contact positions from random games
        for (Position pos : randomGames(random, 300)) {
            if (!RaceAdjudication.passed(pos)) {
                assertEquals(LINEAR.evaluate(pos), RACE.evaluate(pos), pos.toString());
            }
        }
        // passed, but one side has a piece on row 1 or 0, which the rows>=2 table does not cover
        for (int i = 0; i < 300; i++) {
            Position pos = RaceMoveTest.randomPassed(random, random.nextInt(2), -1, 1);
            if (pos.outcome() != null) {
                continue;
            }
            boolean covered = TABLE.covers(pos.pieces(0)) && TABLE.covers(Rows.toPlayer0(1, pos.pieces(1)));
            if (!covered) {
                passedUncovered++;
                assertEquals(LINEAR.evaluate(pos), RACE.evaluate(pos), pos.toString());
            }
        }
        assertTrue(passedUncovered > 100, passedUncovered + " uncovered passed positions");
        // no table at all: identical to the wrapped evaluator everywhere, and always futility-safe
        RaceEvaluator none = new RaceEvaluator(LINEAR, null);
        for (int i = 0; i < 50; i++) {
            Position pos = deepPassed(random, i % 2, 2);
            assertEquals(LINEAR.evaluate(pos), none.evaluate(pos));
            assertTrue(none.futilitySafe(pos, 1) && none.futilitySafe(pos, 2));
            assertFalse(RACE.futilitySafe(pos, 1) || RACE.futilitySafe(pos, 2), "a passed position is never safe");
        }
    }

    @Test
    void threePlayersAndAMissingPieceAreLeftToTheWrappedEvaluator() {
        RuleSet three = RuleSet.defaults(3);
        assertFalse(RaceEvaluator.rulesApply(three));
        assertTrue(RaceEvaluator.rulesApply(RULES));
        Position pos3 = Position.initial(three);
        for (int i = 0; i < 21; i++) {
            pos3.make(pos3.legalMoves()[0]); // player 0 to move again: the linear evaluator scores players 0 and 1
        }
        assertEquals(0, pos3.sideToMove());
        assertEquals(LINEAR.evaluate(pos3), RACE.evaluate(pos3));
        assertTrue(RACE.futilitySafe(pos3, 1) && RACE.futilitySafe(pos3, 2));
        // nine pieces: not passed by definition, so the wrapped value, and never a race value within reach
        Position nine = deepPassed(new Random(1), 0, 3);
        Position missing = Position.empty(RULES);
        boolean skipped = false;
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (nine.owner(c) == 0 && !skipped) {
                skipped = true;
            } else if (nine.owner(c) >= 0) {
                missing.put(nine.owner(c), c);
            }
        }
        assertEquals(9, missing.pieceCount(0));
        assertFalse(RaceEvaluator.passed(missing));
        assertFalse(RaceAdjudication.passed(missing));
        assertEquals(LINEAR.evaluate(missing), RACE.evaluate(missing));
        assertTrue(RACE.futilitySafe(missing, 2));
    }

    @Test
    void theValueIsSymmetricUnderTheColourSwap() {
        Random random = new Random(84);
        for (int i = 0; i < 300; i++) {
            Position pos = deepPassed(random, random.nextInt(2), 2);
            Position image = pos.transformed(Geometry::playerFlip, true);
            assertEquals(RACE.evaluate(pos), RACE.evaluate(image), pos.toString());
            assertEquals(RACE.futilitySafe(pos, 1), RACE.futilitySafe(image, 1));
            assertEquals(RACE.futilitySafe(pos, 2), RACE.futilitySafe(image, 2));
        }
        for (Position pos : randomGames(random, 200)) {
            Position image = pos.transformed(Geometry::playerFlip, true);
            assertEquals(RACE.evaluate(pos), RACE.evaluate(image), pos.toString());
            assertEquals(RACE.futilitySafe(pos, 1), RACE.futilitySafe(image, 1), pos.toString());
            assertEquals(RACE.futilitySafe(pos, 2), RACE.futilitySafe(image, 2), pos.toString());
        }
    }

    @Test
    void theFastPassedTestEqualsTheRaceModulesDefinition() {
        Random random = new Random(85);
        int passed = 0;
        List<Position> positions = new ArrayList<>(randomGames(random, 400));
        for (int i = 0; i < 400; i++) {
            positions.add(RaceMoveTest.randomPassed(random, i % 2, -4, 4));
        }
        positions.addAll(DeltaProbe.positions(DeltaProbe.DEFAULT_SPEC.replace("nodes=1000", "nodes=200"), 6, 86));
        for (Position pos : positions) {
            assertEquals(RaceAdjudication.passed(pos), RaceEvaluator.passed(pos), pos.toString());
            passed += RaceEvaluator.passed(pos) ? 1 : 0;
        }
        assertTrue(passed > 400, passed + " passed positions in the sample");
    }

    /** Whether some sequence of {@code depth} plies from {@code pos} reaches a passed position. */
    static boolean canPass(Position pos, int depth) {
        if (RaceAdjudication.passed(pos)) {
            return true;
        }
        if (depth == 0 || pos.outcome() != null) {
            return false;
        }
        for (int m : pos.legalMoves()) {
            pos.make(m);
            boolean found = canPass(pos, depth - 1);
            pos.unmake();
            if (found) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code eval} returns a race-band value for {@code pos} or for some non-terminal position within
     * {@code depth} plies of it.
     */
    static boolean raceValueWithin(Evaluator eval, Position pos, int depth) {
        if (pos.outcome() != null) {
            return false;
        }
        if (Scores.kind(eval.evaluate(pos)) == Scores.Kind.RACE) {
            return true;
        }
        if (depth == 0) {
            return false;
        }
        for (int m : pos.legalMoves()) {
            pos.make(m);
            boolean found = raceValueWithin(eval, pos, depth - 1);
            pos.unmake();
            if (found) {
                return true;
            }
        }
        return false;
    }

    /**
     * The futility hook is sound: wherever the evaluator returns a race value for the position itself or for some
     * position one move (depth 1) or a move and a reply (depth 2) away, it forbids pruning, checked by brute force
     * over all moves on about 4000 positions of self-play games, for the plan's evaluator (race values need both
     * configurations covered) and for the estimate ({@code wout=1}, every passed position). The hook is a necessary
     * condition, not an exact one: the test counts and prints how often it forbids pruning where no race value is
     * in reach, and requires that this stays rare, so that the hook costs little depth in contact positions.
     */
    @Test
    void theFutilityHookForbidsPruningWhereverARaceValueIsInReach() {
        List<Position> positions = DeltaProbe.positions(DeltaProbe.DEFAULT_SPEC.replace("nodes=1000", "nodes=300"),
                130, 87);
        assertTrue(positions.size() >= 4000, positions.size() + " positions");
        RaceEvaluator estimate = new RaceEvaluator(LINEAR, TABLE, 2, true);
        int contact = 0;
        for (Position pos : positions) {
            contact += pos.outcome() == null && !RaceAdjudication.passed(pos) ? 1 : 0;
        }
        for (RaceEvaluator eval : new RaceEvaluator[] {RACE, estimate}) {
            int[] inReach = new int[3];
            int[] unsafe = new int[3];
            int[] conservative = new int[3];
            for (Position pos : positions) {
                if (pos.outcome() != null) {
                    continue;
                }
                for (int depth = 1; depth <= 2; depth++) {
                    boolean brute = raceValueWithin(eval, pos, depth);
                    boolean safe = eval.futilitySafe(pos, depth);
                    if (brute) {
                        inReach[depth]++;
                        assertFalse(safe, "a race value is " + depth + " plies away but futility is allowed at\n" + pos);
                    }
                    if (!safe) {
                        unsafe[depth]++;
                        if (!brute) {
                            conservative[depth]++;
                        }
                    }
                }
            }
            System.out.println("RaceEvaluatorTest hook (" + (eval == RACE ? "plan" : "wout=1") + "): "
                    + positions.size() + " positions, " + contact + " in contact; depth 1: race value in reach "
                    + inReach[1] + ", hook unsafe " + unsafe[1] + " (conservative " + conservative[1]
                    + "); depth 2: in reach " + inReach[2] + ", hook unsafe " + unsafe[2] + " (conservative "
                    + conservative[2] + ")");
            assertTrue(inReach[1] > 100 && inReach[2] > inReach[1], "the sample must contain transitions");
            assertTrue(conservative[2] < 0.05 * contact, "the hook is conservative on " + conservative[2] + " of "
                    + contact + " contact positions at depth 2");
        }
        // without the estimate the hook also allows pruning where a pass is in reach but no race value: a side with
        // more pieces behind the region than moves cannot be covered in time
        int passReachButSafe = 0;
        for (Position pos : positions) {
            if (pos.outcome() == null && canPass(pos, 1) && RACE.futilitySafe(pos, 1)) {
                passReachButSafe++;
                assertFalse(raceValueWithin(RACE, pos, 1));
            }
        }
        assertTrue(passReachButSafe > 100, passReachButSafe + " positions can pass without a race value in reach");
    }

    /** The nearest free cell of player 0 on row 2 by |q - q0|, ties by cell id: the test's own relocation rule. */
    private static int nearestFreeRowTwo(Position pos, int q0) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int c : Rows.regionCells(2)) {
            if (Geometry.r(c) == 2 && pos.owner(c) < 0 && Math.abs(Geometry.q(c) - q0) < bestDistance) {
                best = c;
                bestDistance = Math.abs(Geometry.q(c) - q0);
            }
        }
        return best;
    }

    /**
     * The estimate for pieces behind the region ({@code wout=1}): covered configurations keep their table count; a
     * piece on row 1 adds one step plus the count with it on the nearest free cell of row 2, so its step into the
     * region lowers the count by exactly one, and the leaf stays in the race band instead of jumping to the linear
     * value (the discontinuity that lets the losing side avoid the region with {@code wout=0}).
     */
    @Test
    void theOutsideEstimateIsContinuousAcrossTheRegionBoundary() {
        RaceEvaluator est = new RaceEvaluator(LINEAR, TABLE, 2, true);
        Random random = new Random(88);
        int checked = 0;
        for (int i = 0; i < 300; i++) {
            Position pos = deepPassed(random, 0, 3);
            assertEquals(count(pos, 0), est.count(pos, 0), "covered: the table count");
            assertEquals(RACE.evaluate(pos), est.evaluate(pos));
            // move player 0's rearmost piece to a free allowed cell on row 1 with the same q, if there is one
            int rear = pos.pieceCell(0, 0);
            int behind = Geometry.id(Geometry.q(rear), 1);
            if (behind < 0 || !Layout.of(2).allowed(0, behind) || pos.owner(behind) >= 0) {
                continue;
            }
            Position out = Position.empty(RULES);
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (pos.owner(c) >= 0 && c != rear) {
                    out.put(pos.owner(c), c);
                }
            }
            out.put(0, behind).setSideToMove(0);
            assertTrue(RaceAdjudication.passed(out));
            assertEquals(-1, TABLE.count(out.pieces(0)), "row 1 is outside the rows>=2 region");
            int target = nearestFreeRowTwo(out, Geometry.q(behind));
            int[] relocated = out.pieces(0).clone();
            relocated[0] = target;
            Arrays.sort(relocated);
            int expected = 1 + TABLE.count(relocated);
            assertEquals(expected, est.count(out, 0), out.toString());
            assertEquals(Scores.Kind.RACE, Scores.kind(est.evaluate(out)), "the leaf stays in the band");
            assertEquals(LINEAR.evaluate(out), RACE.evaluate(out), "without the estimate: the linear value");
            // the step onto the relocation cell, when legal, lowers the estimate by exactly one
            int step = sternhalma.engine.Move.of(behind, target);
            if (Arrays.stream(out.legalMoves()).anyMatch(m -> m == step)) {
                out.make(step);
                assertEquals(expected - 1, est.count(out, 0));
                out.unmake();
            }
            checked++;
        }
        assertTrue(checked > 100, checked + " positions with a piece moved behind the region");
    }

    @Test
    void theRegistryValidatesTheOutsideWeight() {
        assertFalse(((RaceEvaluator) Evaluators.byName("racelin")).estimatesOutside());
        assertTrue(((RaceEvaluator) Evaluators.byName("racelin", java.util.Map.of("wout", 1))).estimatesOutside());
        assertFalse(((RaceEvaluator) Evaluators.byName("racelin", java.util.Map.of("wout", 0))).estimatesOutside());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Evaluators.byName("racelin", java.util.Map.of("wout", 2)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Evaluators.byName("racelin1", java.util.Map.of("wx", 1)));
        AlphaBetaPlayer p = PlayerSpec.parse("ab:eval=racelin,wout=1,nodes=100").create();
        assertTrue(p.name().contains("racelin{wout=1}"), p.name());
    }

    @Test
    void theRegistryOffersRacelinForBothTablesAndTheContractCoversThem() {
        assertTrue(Evaluators.names().contains("racelin"));
        assertTrue(Evaluators.names().contains("racelin1"));
        assertFalse(Evaluators.names().contains("racelin-r1"), "the R2 fingerprint stays untouched");
        RaceEvaluator r2 = (RaceEvaluator) Evaluators.byName("racelin");
        assertEquals(2, ((RaceTable) r2.table()).minRow());
        assertEquals(LINEAR.evaluate(Position.initial(RULES)), r2.evaluate(Position.initial(RULES)));
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith(Strategies.RACELIN_SPEC + ",")));
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith("ab:eval=racelin1,")));
        assertEquals(1, PlayerSpec.parse(Strategies.RACELIN_SPEC).race(), "the contract spec plays races too");
        assertTrue(PlayerSpec.parse(Strategies.RACELIN_SPEC).options().futilityOn());
        assertEquals("ab:eval=linear,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1",
                Strategies.BEST_R1_SPEC, "the round-1 BEST keeps its text under its own name");
        // the integration of round 2: the race evaluator over the bestGain evaluator linear-r3 (E07), registered as
        // racelin1-r3 and reachable for any base through the prefix racelin1:<name>
        assertTrue(Evaluators.names().contains("racelin1-r3"));
        RaceEvaluator r3 = (RaceEvaluator) Evaluators.byName("racelin1-r3");
        assertEquals(1, ((RaceTable) r3.table()).minRow());
        assertEquals(LinearEvaluator.GainMode.BOTH, ((LinearEvaluator) r3.base()).gainMode());
        assertEquals(Evaluators.byName("linear-r3").evaluate(Position.initial(RULES)),
                r3.evaluate(Position.initial(RULES)));
        RaceEvaluator prefixed = (RaceEvaluator) Evaluators.byName("racelin1:linear-r3", java.util.Map.of("wout", 1));
        assertEquals(LinearEvaluator.GainMode.BOTH, ((LinearEvaluator) prefixed.base()).gainMode());
        assertTrue(prefixed.estimatesOutside());
        assertEquals(2, ((RaceTable) ((RaceEvaluator) Evaluators.byName("racelin:linear-r1")).table()).minRow());
        assertThrows(IllegalArgumentException.class, () -> Evaluators.byName("racelin1:no-such-evaluator"));
        assertEquals("racelin1-r3", PlayerSpec.parse(Strategies.BEST_SPEC).evaluator());
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith("ab:eval=racelin1-r3,")));
        // racelin1 reads the rows>=1 table (committed with Git LFS; built into the cache when only a pointer exists)
        RaceEvaluator r1 = (RaceEvaluator) Evaluators.byName("racelin1");
        RaceCounts rows1 = r1.table();
        assertEquals(1, ((RaceTable) rows1).minRow());
        Position deep = deepPassed(new Random(3), 0, 2);
        assertEquals(Scores.Kind.RACE, Scores.kind(r1.evaluate(deep)));
        // a configuration with a piece on row 1 is covered by racelin1 only
        Random random = new Random(4);
        Position edge = deepPassed(random, 0, 1);
        while (TABLE.covers(edge.pieces(0)) && TABLE.covers(Rows.toPlayer0(1, edge.pieces(1)))) {
            edge = deepPassed(random, 0, 1);
        }
        assertEquals(LINEAR.evaluate(edge), r2.evaluate(edge));
        assertEquals(Scores.Kind.RACE, Scores.kind(r1.evaluate(edge)));
    }
}
