package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.SearchResult;
import sternhalma.selfplay.Adjudication.Firing;
import sternhalma.selfplay.Adjudication.Layer;

/**
 * Layer 2 against a regression set built from the control games of the E05 acceptance run (BEST_SPEC at 20000
 * nodes, seed 1005, every game played to its end; {@link RegressionSetExport}): 35 games in which a looser margin
 * (400 on one own move) labels the side that then did not win, the one game with a wrong label of the shipped rule
 * (500 on two own moves; the game went to the move limit; it is also one of the 35), and 30 games in which the shipped rule fired correctly.
 * The live tracker, fed with the stored scores, must label exactly as recorded: never the side that did not win,
 * except the one known move-limit game.
 */
class MarginRegressionTest {

    /** The known error of the shipped rule in 993 firings: labelled player 1, the game ended at the move limit. */
    static final int KNOWN_LIMIT_GAME = 15288;

    record Game(GameRecord record, double[] finalValues) {
    }

    static List<Game> load() throws IOException {
        List<Game> games = new ArrayList<>();
        try (InputStream in = MarginRegressionTest.class.getResourceAsStream("/e05-margin-regression.txt")) {
            assertNotNull(in, "resource e05-margin-regression.txt");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] t = line.split(" ");
                int n = Integer.parseInt(t[3]);
                assertEquals(4 + 2 * n, t.length, "game " + t[0]);
                int[] moves = new int[n];
                int[] scores = new int[n];
                for (int i = 0; i < n; i++) {
                    moves[i] = Integer.parseInt(t[4 + 2 * i]);
                    scores[i] = Integer.parseInt(t[5 + 2 * i]);
                }
                double[] fv = {Double.parseDouble(t[1]), Double.parseDouble(t[2])};
                games.add(new Game(new GameRecord(Integer.parseInt(t[0]), 0, moves, scores, Layer.NONE, n, fv, true,
                        n, fv, null, null, null, 0, 0, 0, 0, 0, null), fv));
            }
        }
        return games;
    }

    /** The live layer-2 tracker over a stored game; also checks that the moves replay legally. */
    static Firing live(GameRecord g, int margin, int moves) {
        Adjudication.Tracker t = new Adjudication(new Adjudication.Config(false, margin, moves, 0, 4), 2, null, null)
                .tracker();
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < g.moves().length; i++) {
            int m = g.moves()[i];
            assertTrue(java.util.Arrays.stream(pos.legalMoves()).anyMatch(x -> x == m), "game " + g.id() + " ply " + i);
            int mover = pos.sideToMove();
            pos.make(m);
            int s = g.scores()[i];
            if (pos.outcome() == null) {
                Firing f = t.afterMove(pos, mover, s == GameRecord.NO_SCORE ? null : new SearchResult(m, s, 1, 1, true));
                if (f != null) {
                    return f;
                }
            }
        }
        assertNotNull(pos.outcome(), "the stored game is complete");
        return null;
    }

    @Test
    void theShippedMarginNeverLabelsTheSideThatDidNotWin() throws IOException {
        List<Game> games = load();
        assertEquals(65, games.size(), "35 near misses (the known limit game among them) and 30 correct firings");
        int fired = 0;
        int nearMisses = 0;
        for (Game g : games) {
            Adjudication.Config shipped = Adjudication.Config.DEFAULT;
            Firing f = live(g.record(), shipped.margin(), shipped.marginMoves());
            assertEquals(Report.marginFiring(g.record(), 500, 2), f, "live and offline layer 2 agree");
            Firing loose = live(g.record(), 400, 1);
            if (loose != null && g.finalValues()[loose.winner()] != 1.0) {
                nearMisses++;
            }
            if (f == null) {
                continue;
            }
            fired++;
            if (g.record().id() == KNOWN_LIMIT_GAME) {
                assertEquals(1, f.winner());
                assertTrue(g.finalValues()[1] < 1.0 && g.finalValues()[0] < 1.0, "the move-limit game");
            } else {
                assertEquals(1.0, g.finalValues()[f.winner()], "game " + g.record().id() + ": label " + f.winner());
            }
        }
        assertEquals(31, fired, "30 correct firings and the known limit game");
        // the set is sharp: the looser rule mislabels 35 of these games, so a margin lowered to 400 fails this test
        assertEquals(35, nearMisses);
    }
}
