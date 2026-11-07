package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.AlphaBeta;
import sternhalma.search.RaceAdjudication;
import sternhalma.search.Scores;
import sternhalma.search.SearchResult;
import sternhalma.selfplay.Adjudication.Config;
import sternhalma.selfplay.Adjudication.Firing;
import sternhalma.selfplay.Adjudication.Layer;
import sternhalma.tablebase.RaceTable;

/** The three layers and the label policy, on constructed score sequences and positions. */
class AdjudicationTest {

    private static final RuleSet RULES = RuleSet.defaults(2);
    private static final Config MARGIN_ONLY = new Config(false, 500, 2, 0, 4);

    /** A contact position after a few plies (not passed, not over). */
    private static Position contact() {
        Position pos = Position.initial(RULES);
        for (int i = 0; i < 6; i++) {
            pos.make(pos.legalMoves()[0]);
        }
        return pos;
    }

    /** Passed: player 0 has nine pieces in its goal and one on p0, player 1 likewise with p1. */
    static Position nineAndOne(int p0, int p1, int stm) {
        Position pos = Position.empty(RULES);
        Layout layout = Layout.of(2);
        int free0 = Geometry.id(-2, 5);
        int free1 = Geometry.id(2, -5);
        for (int c : Geometry.cornerCells(layout.goalCorner(0))) {
            if (c != free0) {
                pos.put(0, c);
            }
        }
        for (int c : Geometry.cornerCells(layout.goalCorner(1))) {
            if (c != free1) {
                pos.put(1, c);
            }
        }
        pos.put(0, p0).put(1, p1).setSideToMove(stm);
        return pos;
    }

    private static SearchResult score(int s) {
        return new SearchResult(0, s, 4, 1000, true);
    }

    @Test
    void refusesOtherPlayerCounts() {
        assertThrows(IllegalArgumentException.class, () -> new Adjudication(Config.DEFAULT, 3, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Adjudication(MARGIN_ONLY, 3, null, null));
        // with every layer off nothing is adjudicated, so three players are allowed
        new Adjudication(Config.OFF, 3, null, null);
        assertThrows(IllegalArgumentException.class, () -> new Adjudication(new Config(false, 0, 2, 0.98, 4), 2,
                null, null), "layer 3 needs an evaluator");
    }

    @Test
    void marginNeedsConsecutiveOwnMovesOfTheSameSign() {
        Adjudication.Tracker t = new Adjudication(MARGIN_ONLY, 2, null, null).tracker();
        Position pos = contact();
        // the opponent's scores between the two own moves do not break the streak
        assertNull(t.afterMove(pos, 0, score(600)));
        assertNull(t.afterMove(pos, 1, score(-600)));
        Firing f = t.afterMove(pos, 0, score(500));
        assertNotNull(f, "second own move at the margin");
        assertEquals(Layer.MARGIN, f.layer());
        assertEquals(0, f.winner());
        assertEquals(pos.ply(), f.ply());
        assertNull(t.afterMove(pos, 0, score(900)), "a layer fires once per game");

        Adjudication.Tracker loser = new Adjudication(MARGIN_ONLY, 2, null, null).tracker();
        assertNull(loser.afterMove(pos, 1, score(-700)));
        Firing l = loser.afterMove(pos, 1, score(-501));
        assertNotNull(l);
        assertEquals(0, l.winner(), "a losing score labels the opponent");

        Adjudication.Tracker reset = new Adjudication(MARGIN_ONLY, 2, null, null).tracker();
        assertNull(reset.afterMove(pos, 0, score(600)));
        assertNull(reset.afterMove(pos, 0, score(499)), "below the margin resets");
        assertNull(reset.afterMove(pos, 0, score(600)));
        assertNull(reset.afterMove(pos, 0, score(-600)), "a sign change resets");
        assertNull(reset.afterMove(pos, 0, null), "a move without a search (race play) resets");
        assertNull(reset.afterMove(pos, 0, score(600)));
        assertNotNull(reset.afterMove(pos, 0, score(600)));
    }

    @Test
    void mateScoresCountAndRaceBandScoresAreIgnored() {
        Position pos = contact();
        Adjudication.Tracker mate = new Adjudication(MARGIN_ONLY, 2, null, null).tracker();
        assertNull(mate.afterMove(pos, 1, score(AlphaBeta.MATE - 7)));
        assertNotNull(mate.afterMove(pos, 1, score(AlphaBeta.MATE - 5)));

        Adjudication.Tracker race = new Adjudication(MARGIN_ONLY, 2, null, null).tracker();
        int r = Scores.RACE_BASE + 200 * 3;
        assertEquals(Scores.Kind.RACE, Scores.kind(r));
        for (int i = 0; i < 5; i++) {
            assertNull(race.afterMove(pos, 0, score(r)), "a race-band score never labels");
            assertNull(race.afterMove(pos, 1, score(-r)));
        }
        assertEquals(10, race.raceScoresIgnored());
        // a race-band score between two margin scores breaks the streak
        assertNull(race.afterMove(pos, 0, score(600)));
        assertNull(race.afterMove(pos, 0, score(Scores.RACE_BASE)));
        assertNull(race.afterMove(pos, 0, score(600)));
        assertNotNull(race.afterMove(pos, 0, score(600)));
    }

    @Test
    void layerTwoNeverLabelsAPassedPosition() {
        // a passed position where a race evaluator would report +5000 and more: neither that nor an ordinary
        // margin score may label it; only S1 may
        Position passed = nineAndOne(Geometry.id(-2, 3), Geometry.id(0, 0), 1);
        assertTrue(RaceAdjudication.passed(passed));
        Adjudication.Tracker t = new Adjudication(MARGIN_ONLY, 2, null, null).tracker();
        for (int i = 0; i < 4; i++) {
            assertNull(t.afterMove(passed, 0, score(Scores.RACE_BASE + 200)));
        }
        for (int i = 0; i < 4; i++) {
            assertNull(t.afterMove(passed, 0, score(900)), "ordinary margin in a passed position");
        }
        assertNull(t.margin());
        assertEquals(4, t.raceScoresIgnored());
    }

    @Test
    void s1FiresOnAWonRaceWithTheVerdictOfRaceAdjudication() {
        Adjudication a = new Adjudication(Config.DEFAULT, 2, RaceTable.rows2(), null);
        Position won = nineAndOne(Geometry.id(-2, 4), Geometry.id(0, 0), 1);
        Adjudication.Tracker t = a.tracker();
        Firing f = t.beforeMove(won);
        assertNotNull(f);
        assertEquals(Layer.S1, f.layer());
        RaceAdjudication.Verdict v = new RaceAdjudication(RaceTable.rows2()).adjudicate(won);
        assertEquals(v.winner(), f.winner());
        assertEquals(v.endPly(), f.endPly());
        assertEquals(0, f.ply());
        assertNull(t.beforeMove(won), "fires once");
        // contact positions are never S1-adjudicated
        assertNull(a.tracker().beforeMove(contact()));
        Adjudication off = new Adjudication(MARGIN_ONLY, 2, null, null);
        assertNull(off.tracker().beforeMove(won), "S1 off");
    }

    @Test
    void evalLayerNeedsConsecutivePliesForTheSameSideAndOrdinaryValues() {
        int[] value = {0};
        Adjudication a = new Adjudication(new Config(false, 0, 2, 0.98, 3), 2, null, p -> value[0]);
        Adjudication.Tracker t = a.tracker();
        Position p0 = contact(); // player 0 to move
        Position p1 = p0.copy();
        p1.make(p1.legalMoves()[0]); // player 1 to move
        value[0] = 400; // p = 0.982 for the side to move
        assertNull(t.beforeMove(p0));
        value[0] = -400; // the side to move (1) is lost: the same winner (0)
        assertNull(t.beforeMove(p1));
        value[0] = 400;
        Firing f = t.beforeMove(p0);
        assertNotNull(f);
        assertEquals(0, f.winner());

        Adjudication.Tracker broken = a.tracker();
        value[0] = 400;
        assertNull(broken.beforeMove(p0));
        value[0] = 300; // p = 0.953, below the threshold
        assertNull(broken.beforeMove(p1));
        value[0] = 400;
        assertNull(broken.beforeMove(p0));
        value[0] = Scores.RACE_BASE + 1; // race band: no information
        assertNull(broken.beforeMove(p1));
        assertNull(broken.beforeMove(p0));
        assertNull(broken.eval());
    }
}
