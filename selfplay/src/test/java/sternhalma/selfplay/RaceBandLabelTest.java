package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.RaceAdjudication;
import sternhalma.search.Scores;
import sternhalma.selfplay.Adjudication.Layer;

/**
 * The label policy with the race evaluator of round-2 experiment E08 in play: games of {@code eval=racelin} put
 * race-band scores into the records, the margin layer ignores every one of them, and each margin label rests on two
 * ordinary (or mate) scores in a contact position. The margin layer is on here on purpose (it is off in production).
 */
class RaceBandLabelTest {

    /**
     * Without race play, so that the search itself runs in passed positions and reports race-band scores; with the
     * estimate for pieces behind the region ({@code wout=1}), so that every passed leaf has a race value.
     */
    private static final String SPEC =
            "ab:eval=racelin,wout=1,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=30,"
                    + "fut2=1,nodes=300,tt=12";

    @Test
    void marginLabelsNeverRestOnRaceBandScores() {
        Adjudication.Config margin = new Adjudication.Config(false, 500, 2, 0, 4);
        SelfPlayGame game = new SelfPlayGame(new SelfPlayGame.Settings(SPEC, 1006, margin, 1.0,
                OpeningSampler.Mode.UNIFORM, 4, 6, 30, 1, 0.05, false, true));
        int raceScores = 0;
        int raceSeen = 0;
        int raceIgnored = 0;
        int marginLabels = 0;
        List<Integer> plies = new ArrayList<>();
        for (int id = 0; id < 12; id++) {
            GameRecord g = game.play(id);
            assertTrue(g.playedOut(), "control: every game is played to its end");
            raceIgnored += g.raceScoresIgnored();
            for (int i = 0; i < g.scores().length; i++) {
                int s = g.scores()[i];
                if (s != GameRecord.NO_SCORE && Scores.kind(s) == Scores.Kind.RACE) {
                    raceScores++;
                    // layer 2 sees a score when the move does not end the game and the layer has not fired yet
                    boolean seen = i + 1 < g.finalPly() && (g.margin() == null || i + 1 < g.margin().ply());
                    raceSeen += seen ? 1 : 0;
                }
            }
            if (g.margin() == null) {
                continue;
            }
            marginLabels++;
            assertEquals(Layer.MARGIN, g.label());
            int ply = g.margin().ply();
            plies.add(ply);
            Position at = Report.replay(g, ply);
            assertFalse(RaceAdjudication.passed(at), "a margin label in a passed position at ply " + ply);
            // the label rests on the labelled player's last two own moves, both with ordinary or mate scores
            int mover = Report.replay(g, ply - 1).sideToMove();
            int expectedWinner = Integer.signum(g.scores()[ply - 1]) > 0 ? mover : 1 - mover;
            assertEquals(expectedWinner, g.margin().winner());
            for (int i : new int[] {ply - 1, ply - 3}) {
                int s = g.scores()[i];
                assertNotEquals(GameRecord.NO_SCORE, s, "a searched move backs the label");
                assertNotEquals(Scores.Kind.RACE, Scores.kind(s), "a race-band score backs the label at ply " + ply);
                assertTrue(Math.abs(s) >= 500, "score " + s + " below the margin backs the label");
                assertEquals(expectedWinner, Integer.signum(s) > 0 ? mover : 1 - mover, "same side on both moves");
            }
        }
        System.out.println("RaceBandLabelTest: " + raceScores + " race-band scores in 12 games, " + raceIgnored
                + " ignored by layer 2, " + marginLabels + " margin labels at plies " + plies);
        assertTrue(raceScores > 20, "the race evaluator must have reported band scores: " + raceScores);
        assertEquals(raceSeen, raceIgnored, "layer 2 ignored every race-band score it saw");
        assertTrue(raceIgnored > 10, raceIgnored + " ignored");
    }

    @Test
    void theReportCountsLabelsThatWouldRestOnRaceBandScores() {
        Position pos = Position.initial(RuleSet.defaults(2));
        int[] moves = new int[6];
        int[] scores = new int[6];
        for (int i = 0; i < 6; i++) {
            moves[i] = pos.legalMoves()[0];
            scores[i] = GameRecord.NO_SCORE;
            pos.make(moves[i]);
        }
        // a (fabricated) margin firing after move 5, player 1's move, with the two own-move scores at indices 5 and 3
        scores[5] = -600;
        scores[3] = Scores.RACE_BASE + 200;
        GameRecord bad = record(moves, scores, new Adjudication.Firing(Layer.MARGIN, 6, 0, -1));
        assertEquals(1, Report.raceBackedMarginLabels(List.of(bad)));
        scores[3] = -600;
        GameRecord good = record(moves, scores, new Adjudication.Firing(Layer.MARGIN, 6, 0, -1));
        assertEquals(0, Report.raceBackedMarginLabels(List.of(good)));
        GameRecord none = record(moves, scores, null);
        assertEquals(1, Report.raceBackedMarginLabels(List.of(none, bad, good)));
    }

    private static GameRecord record(int[] moves, int[] scores, Adjudication.Firing margin) {
        return new GameRecord(0, 4, moves.clone(), scores.clone(), margin == null ? Layer.NONE : Layer.MARGIN,
                margin == null ? moves.length : margin.ply(), new double[] {1, -1}, true, moves.length,
                new double[] {1, -1}, null, margin, null, 0, 0, 0, 0, 0, null);
    }
}
