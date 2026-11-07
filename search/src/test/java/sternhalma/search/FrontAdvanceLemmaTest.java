package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.tablebase.Rows;

/**
 * The front-advance lemma of {@link RaceAdjudication}: with every W piece at least three rows beyond Lo's front F,
 * no legal Lo move raises the front above F + 1. Tested on random positions and on adversarial ones (W pieces exactly
 * on rows F + 3 and F + 4, Lo pieces packed on rows F - 1 and F so that jumps are available), with every legal Lo
 * move enumerated by the engine. The test also shows that it has teeth: with a gap of two rows the bound fails.
 */
class FrontAdvanceLemmaTest {

    private static final RuleSet RULES = RuleSet.defaults(2);

    /** Cells the player may stand on, by the given row in W's orientation. */
    static List<Integer> cellsOnRow(int player, int w, int row) {
        Layout layout = Layout.of(2);
        List<Integer> cells = new ArrayList<>();
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (layout.allowed(player, c) && Rows.row(w, c) == row) {
                cells.add(c);
            }
        }
        return cells;
    }

    /**
     * A position with W to move or Lo to move: Lo pieces on rows {@code <= front} (the front occupied), W pieces on
     * rows {@code >= front + gap}; {@code n} pieces each when possible. Returns null if it cannot be built.
     */
    static Position build(Random random, int w, int front, int gap, boolean adversarial, int n) {
        int lo = 1 - w;
        Layout layout = Layout.of(2);
        List<Integer> loCandidates = new ArrayList<>();
        List<Integer> wCandidates = new ArrayList<>();
        for (int c = 0; c < Geometry.CELLS; c++) {
            int row = Rows.row(w, c);
            if (layout.allowed(lo, c) && row <= front && (!adversarial || row >= front - 1)) {
                loCandidates.add(c);
            }
            if (layout.allowed(w, c) && row >= front + gap && (!adversarial || row <= front + gap + 1)) {
                wCandidates.add(c);
            }
        }
        if (loCandidates.size() < n || wCandidates.size() < n) {
            return null;
        }
        Position pos = Position.empty(RULES);
        java.util.Collections.shuffle(loCandidates, random);
        java.util.Collections.shuffle(wCandidates, random);
        // the front must be occupied
        List<Integer> onFront = cellsOnRow(lo, w, front);
        if (onFront.isEmpty()) {
            return null;
        }
        int frontCell = onFront.get(random.nextInt(onFront.size()));
        pos.put(lo, frontCell);
        int placed = 1;
        for (int c : loCandidates) {
            if (placed == n) {
                break;
            }
            if (c != frontCell) {
                pos.put(lo, c);
                placed++;
            }
        }
        for (int i = 0; i < n; i++) {
            pos.put(w, wCandidates.get(i));
        }
        pos.setSideToMove(lo);
        return pos;
    }

    /** The largest W-row of any Lo piece. */
    static int front(Position pos, int w) {
        int f = Integer.MIN_VALUE;
        for (int c : pos.pieces(1 - w)) {
            f = Math.max(f, Rows.row(w, c));
        }
        return f;
    }

    /** The largest front any legal Lo move produces, from the engine's move list. */
    static int maxFrontAfterAnyMove(Position pos, int w) {
        int best = Integer.MIN_VALUE;
        for (int m : pos.legalMoves()) {
            pos.make(m);
            best = Math.max(best, front(pos, w));
            pos.unmake();
        }
        return best;
    }

    private record Count(long positions, long moves, long violations) {
    }

    private static Count run(long seed, int perThread, boolean adversarial, int gap) {
        Random random = new Random(seed);
        long positions = 0;
        long moves = 0;
        long violations = 0;
        while (positions < perThread) {
            int w = random.nextInt(2);
            int front = random.nextInt(-4, 5);
            int n = adversarial ? 10 : 1 + random.nextInt(10);
            Position pos = build(random, w, front, gap, adversarial, n);
            if (pos == null || front(pos, w) != front) {
                continue;
            }
            positions++;
            int[] legal = pos.legalMoves();
            moves += legal.length;
            for (int m : legal) {
                pos.make(m);
                if (front(pos, w) > front + 1) {
                    violations++;
                }
                pos.unmake();
            }
        }
        return new Count(positions, moves, violations);
    }

    @Test
    void noLegalLoMoveRaisesTheFrontByMoreThanOneWhenWIsThreeRowsAway() throws Exception {
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        int perThread = 1_050_000 / threads + 1;
        List<Future<Count>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                long seed = 100 + t;
                boolean adversarial = t % 2 == 1;
                futures.add(pool.submit(() -> run(seed, perThread, adversarial, 3)));
            }
            long positions = 0;
            long moves = 0;
            long violations = 0;
            for (Future<Count> f : futures) {
                Count c = f.get();
                positions += c.positions();
                moves += c.moves();
                violations += c.violations();
            }
            assertTrue(positions >= 1_000_000, positions + " positions");
            assertTrue(moves > 10 * positions, moves + " moves");
            assertEquals(0, violations, "front-advance violations in " + moves + " moves of " + positions + " positions");
        }
    }

    @Test
    void theLemmaFailsWithAGapOfTwoRowsSoTheTestHasTeeth() {
        Count c = run(999, 20_000, true, 2);
        assertTrue(c.violations() > 0, "with W only two rows away, Lo can jump over a W piece: expected violations");
    }

    @Test
    void aConstructedChainOverOwnAndThenOverAWPieceShowsWhyTheGapIsThree() {
        // W = player 0 (rows = r). Lo = player 1. Lo pieces on (0,-1) [row -1] and (0,0) [row 0, the front F = 0].
        // W piece on (0,2) [row 2 = F + 2]: Lo jumps (0,-1) over (0,0) to (0,1), then over (0,2) to (0,3): front 3.
        Position pos = Position.empty(RULES);
        pos.put(1, Geometry.id(0, -1));
        pos.put(1, Geometry.id(0, 0));
        pos.put(0, Geometry.id(0, 2));
        pos.setSideToMove(1);
        assertEquals(0, front(pos, 0));
        assertEquals(3, maxFrontAfterAnyMove(pos, 0), "a gap of two rows lets Lo jump over W");
        // with the W piece on row 3 instead, the chain ends on row 1
        Position safe = Position.empty(RULES);
        safe.put(1, Geometry.id(0, -1));
        safe.put(1, Geometry.id(0, 0));
        safe.put(0, Geometry.id(-1, 3));
        safe.setSideToMove(1);
        assertEquals(1, maxFrontAfterAnyMove(safe, 0));
        boolean sawChain = false;
        for (int m : safe.legalMoves()) {
            sawChain |= Move.from(m) == Geometry.id(0, -1) && Move.to(m) == Geometry.id(0, 1);
        }
        assertTrue(sawChain, "the jump over the own piece to row F + 1 exists");
    }
}
