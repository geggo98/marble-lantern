package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.tablebase.RaceTable;

/**
 * The exhaustive adversary of the plan: from the first position where S1 fires in each of {@value #GAMES} self-play
 * games, the predicted loser plays every legal move at every ply (transposition set on its configuration) while the
 * winner plays its plan; for plans with more than {@value #EXHAUSTIVE_LO_MOVES} loser moves four complete lines are
 * attacked instead (the recorded game, a front-maximiser, the chaser and a random loser). Every leaf must be a win
 * of the predicted winner on or before the predicted ply, and no line may contain a loss, an illegal plan move or a
 * front-bound violation. The games are played by BEST at a reduced node budget so that the test stays fast; the
 * positions are of the same kind as in the measurement runs.
 */
class ExhaustiveAdversaryTest {

    static final int GAMES = 220;
    static final int EXHAUSTIVE_LO_MOVES = 4;
    static final long CAP = 10_000_000L;
    /** The round-1 BEST, as in E03: a searcher that plays the race itself, so every verdict is attacked by a search. */
    static final String SPEC = Strategies.BEST_R1_SPEC + ",nodes=1500,tt=14";

    private record Attack(int game, int loMoves, RaceAdversary.Result exhaustive, List<RaceAdversary.Result> lines) {
    }

    @Test
    void everyLineAgainstEveryVerdictEndsInThepredictedWin() throws Exception {
        RuleSet rules = RuleSet.defaults(2);
        PlayerSpec spec = PlayerSpec.parse(SPEC);
        RaceTable table = RaceTable.rows2();
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        List<Future<Attack>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int g = 0; g < GAMES; g++) {
                int index = g;
                futures.add(pool.submit(() -> {
                    RaceReplay.Game game = RaceReplay.play(index, spec, rules, RaceReplay.opening(rules, 1200, index),
                            new RaceAdjudication(table));
                    assertNull(game.violation(), "game " + index);
                    assertTrue(game.labelCorrect() || game.verdict() != null, "game " + index);
                    if (game.verdict() == null) {
                        return new Attack(index, -1, null, List.of());
                    }
                    RaceAdjudication.Verdict v = game.verdict();
                    Position fire = game.firePosition(rules);
                    boolean wToMove = fire.sideToMove() == v.winner();
                    int loMoves = wToMove ? v.winnerMoves() - 1 : v.winnerMoves();
                    List<RaceAdversary.Result> lines = new ArrayList<>();
                    lines.add(RaceAdversary.recorded(fire, v, game.moves(), RaceAdversary.frontMaximiser(v.winner())));
                    lines.add(RaceAdversary.line(fire, v, RaceAdversary.frontMaximiser(v.winner())));
                    lines.add(RaceAdversary.line(fire, v, RaceAdversary.chaser(v)));
                    lines.add(RaceAdversary.line(fire, v, RaceAdversary.random(index)));
                    lines.add(RaceAdversary.line(fire, v, PlayerSpec.parse(Strategies.BEST_SPEC + ",nodes=2000,tt=14")
                            .create()));
                    RaceAdversary.Result exhaustive = loMoves <= EXHAUSTIVE_LO_MOVES
                            ? RaceAdversary.exhaustive(fire, v, CAP) : null;
                    return new Attack(index, loMoves, exhaustive, lines);
                }));
            }
            int verdicts = 0;
            int exhaustive = 0;
            int capped = 0;
            long nodes = 0;
            long leaves = 0;
            int longPlans = 0;
            int[] loMovesHistogram = new int[16];
            for (Future<Attack> f : futures) {
                Attack a = f.get();
                if (a.loMoves() < 0) {
                    continue;
                }
                verdicts++;
                loMovesHistogram[Math.min(15, a.loMoves())]++;
                for (RaceAdversary.Result line : a.lines()) {
                    assertNull(line.failure(), "game " + a.game() + ": " + line.failure());
                    assertEquals(1, line.leaves());
                }
                if (a.exhaustive() != null) {
                    exhaustive++;
                    nodes += a.exhaustive().nodes();
                    leaves += a.exhaustive().leaves();
                    if (a.exhaustive().capped()) {
                        capped++;
                    } else {
                        assertNull(a.exhaustive().failure(), "game " + a.game() + ": " + a.exhaustive().failure());
                        assertTrue(a.exhaustive().leaves() > 0);
                    }
                    assertNull(a.exhaustive().failure(), "game " + a.game() + ": " + a.exhaustive().failure());
                } else {
                    longPlans++;
                }
            }
            System.out.printf(Locale.ROOT, "adversary: %d verdicts in %d games; exhaustive on %d (%d capped at %d nodes), "
                    + "%d nodes, %d leaves, all wins; %d longer plans checked along 5 lines each; loser moves %s%n",
                    verdicts, GAMES, exhaustive, capped, CAP, nodes, leaves, longPlans,
                    java.util.Arrays.toString(loMovesHistogram));
            assertTrue(verdicts >= 200, verdicts + " verdicts");
            assertTrue(exhaustive >= 100, exhaustive + " exhaustive attacks");
            assertTrue(capped <= exhaustive / 10, capped + " attacks hit the node cap");
        }
    }
}
