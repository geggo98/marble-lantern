package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.Orientation;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.PlayerSpec;
import sternhalma.search.RaceAdjudication;
import sternhalma.search.Scores;
import sternhalma.search.Strategies;
import sternhalma.selfplay.Adjudication.Layer;

/** Games, searcher reuse, control games, labels and samples of the self-play tool. */
class SelfPlayGameTest {

    static final String SPEC = Strategies.BEST_SPEC + ",nodes=1000,tt=16";

    static SelfPlayGame.Settings settings(String spec, double control, boolean reuse, Adjudication.Config adj) {
        return new SelfPlayGame.Settings(spec, 1006, adj, control, OpeningSampler.Mode.UNIFORM, 4, 10, 30, 1, 0.05,
                true, reuse);
    }

    /** Plain self-play with fresh players per game through PlayerSpec.create(), independent of the tool. */
    private static int[] freshGame(String spec, int[] opening) {
        Position pos = Position.initial(RuleSet.defaults(2));
        List<Integer> moves = new ArrayList<>();
        for (int m : opening) {
            pos.make(m);
            moves.add(m);
        }
        PlayerSpec p = PlayerSpec.parse(spec);
        AlphaBetaPlayer[] players = {p.create(), p.create()};
        while (pos.outcome() == null && pos.legalMoves().length > 0) {
            int m = players[pos.sideToMove()].choose(pos);
            moves.add(m);
            pos.make(m);
        }
        return moves.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void reusedSearchersPlayExactlyLikeFreshPlayers() {
        for (String spec : List.of(SPEC, SPEC + ",race=1")) {
            SelfPlayGame reused = new SelfPlayGame(settings(spec, 1, true, Adjudication.Config.OFF));
            SelfPlayGame fresh = new SelfPlayGame(settings(spec, 1, false, Adjudication.Config.OFF));
            OpeningSampler openings = new OpeningSampler(OpeningSampler.Mode.UNIFORM, 4, 10, 30, 1, null);
            // several games in a row on the same instance, so the tables of earlier games would leak into later ones;
            // game 0 twice in a row, so that a table not cleared would hold every position of the next game
            for (int id : new int[] {0, 0, 1, 2, 3, 4}) {
                GameRecord a = reused.play(id);
                GameRecord b = fresh.play(id);
                assertArrayEquals(b.moves(), a.moves(), spec + " game " + id);
                assertArrayEquals(b.scores(), a.scores(), spec + " game " + id + " scores");
                assertArrayEquals(freshGame(spec, openings.sample(RuleSet.defaults(2), 1006, id)), a.moves(),
                        spec + " game " + id + " against plain self-play");
                assertEquals(Layer.NONE, a.label());
                assertTrue(a.playedOut());
            }
        }
    }

    @Test
    void productionGamesArePrefixesOfControlGamesWithTheSameLabelAndSamples() {
        SelfPlayGame control = new SelfPlayGame(settings(SPEC, 1, true, Adjudication.Config.DEFAULT));
        SelfPlayGame production = new SelfPlayGame(settings(SPEC, 0, true, Adjudication.Config.DEFAULT));
        int adjudicated = 0;
        for (int id = 0; id < 8; id++) {
            GameRecord c = control.play(id);
            GameRecord p = production.play(id);
            assertTrue(c.playedOut());
            assertEquals(c.label(), p.label());
            assertEquals(c.labelPly(), p.labelPly());
            assertArrayEquals(c.labelValues(), p.labelValues());
            assertArrayEquals(Arrays.copyOf(c.moves(), p.moves().length), p.moves(), "prefix");
            assertArrayEquals(c.samples().cells(), p.samples().cells());
            assertArrayEquals(c.samples().value(), p.samples().value());
            if (p.label() != Layer.NONE) {
                adjudicated++;
                assertFalse(p.playedOut());
                assertEquals(p.labelPly(), p.moves().length, "a production game stops at the cut");
                assertTrue(c.finalPly() > c.labelPly());
                assertEquals(c.finalPly() - c.labelPly(), c.pliesSaved());
            }
        }
        assertTrue(adjudicated >= 6, "BEST games at 1000 nodes are nearly all adjudicated, got " + adjudicated);
    }

    @Test
    void labelsFollowThePolicy() {
        SelfPlayGame game = new SelfPlayGame(settings(SPEC, 1, true, Adjudication.Config.DEFAULT));
        for (int id = 0; id < 12; id++) {
            GameRecord g = game.play(id);
            switch (g.label()) {
                case S1 -> {
                    // a verdict is a game value: the label is the verdict, whatever the play-out did
                    assertArrayEquals(SelfPlayGame.win(g.s1().winner()), g.labelValues());
                    assertEquals(g.s1().ply(), g.labelPly());
                    assertTrue(RaceAdjudication.passed(Report.replay(g, g.labelPly())));
                }
                case MARGIN -> {
                    assertArrayEquals(SelfPlayGame.win(g.margin().winner()), g.labelValues());
                    Position at = Report.replay(g, g.labelPly());
                    assertFalse(RaceAdjudication.passed(at), "only S1 labels passed positions");
                    // the two last own scores of the mover that triggered it are ordinary or mate and beyond 500
                    int last = g.labelPly() - 1;
                    for (int i : new int[] {last, last - 2}) {
                        int s = g.scores()[i];
                        assertTrue(s != GameRecord.NO_SCORE && Math.abs(s) >= 500
                                && Scores.kind(s) != Scores.Kind.RACE, "score " + s + " at ply " + i);
                    }
                    if (g.s1() != null) {
                        assertTrue(g.s1().ply() > g.margin().ply(), "the first firing sets the label");
                    }
                }
                case NONE -> assertArrayEquals(g.finalValues(), g.labelValues());
                default -> throw new AssertionError("layer 3 is off");
            }
            // the offline recomputation of layer 2 from the stored scores agrees with the live layer
            assertEquals(g.margin(), Report.marginFiring(g, 500, 2));
        }
    }

    @Test
    void samplesExpandToTheEncoderInputAndCarryTheTargets() {
        SelfPlayGame game = new SelfPlayGame(settings(SPEC, 0, true, Adjudication.Config.DEFAULT));
        float[] planes = new float[Encoder.INPUT_SIZE];
        for (int id = 0; id < 3; id++) {
            GameRecord g = game.play(id);
            GameRecord.Samples s = g.samples();
            assertNotNull(s);
            assertEquals(g.labelPly(), s.count(), "one sample per position before the cut");
            Position pos = Position.initial(RuleSet.defaults(2));
            int k = 0;
            for (int i = 0; i < s.count(); i++) {
                Encoder.encode(pos, planes, 0);
                Orientation o = Orientation.of(pos);
                for (int c = 0; c < Geometry.CELLS; c++) {
                    int cell = s.cells()[i * Geometry.CELLS + c];
                    for (int plane : new int[] {Encoder.OWN, Encoder.NEXT, Encoder.THIRD}) {
                        assertEquals(planes[Encoder.index(c, plane)], cell == 1 + plane ? 1f : 0f,
                                "game " + id + " sample " + i + " cell " + c + " plane " + plane);
                    }
                    float progress = (float) Math.min(s.ply()[i], 200) / 200;
                    assertEquals(planes[Encoder.index(c, Encoder.PROGRESS)], progress);
                }
                assertEquals(HexGrid.CELLS, Geometry.CELLS);
                int[] legal = pos.legalMoves();
                assertEquals(legal.length, s.moveCounts()[i]);
                double sum = 0;
                for (int j = 0; j < legal.length; j++) {
                    int board = o.fromCanonicalMove(Move.of(s.moveFrom()[k + j] & 0xFF, s.moveTo()[k + j] & 0xFF));
                    assertEquals(legal[j], board, "legal move " + j);
                    sum += s.moveTarget()[k + j];
                }
                assertEquals(1.0, sum, 1e-5, "policy target sums to 1");
                int played = s.played()[i];
                assertEquals(g.moves()[i], legal[played], "played index");
                assertTrue(s.moveTarget()[k + played] > 0.95f);
                k += legal.length;
                boolean opening = i < g.openingPlies();
                assertEquals(opening ? 0f : 1f, s.policyWeight()[i]);
                assertEquals((opening ? 4 : 0) | (g.label() != Layer.NONE ? 2 : 0), s.flags()[i]);
                // value target: relative players, from the label
                assertEquals((float) g.labelValues()[pos.sideToMove()], s.value()[2 * i]);
                assertEquals((float) g.labelValues()[1 - pos.sideToMove()], s.value()[2 * i + 1]);
                pos.make(g.moves()[i]);
            }
        }
    }

    @Test
    void controlDrawIsDeterministicAndHasTheRequestedShare() {
        int n = 20000;
        int hits = 0;
        for (int id = 0; id < n; id++) {
            boolean c = SelfPlayGame.isControl(1005, id, 0.1);
            assertEquals(c, SelfPlayGame.isControl(1005, id, 0.1));
            hits += c ? 1 : 0;
        }
        assertEquals(0.1, (double) hits / n, 0.01);
        assertTrue(SelfPlayGame.isControl(1005, 7, 1.0));
        assertFalse(SelfPlayGame.isControl(1005, 7, 0.0));
    }

    @Test
    void ttFollowsTheNodeBudgetUnlessTheSpecSetsIt() {
        assertEquals("ab:nodes=1000,tt=16", SelfPlay.effectiveSpec("ab:nodes=1000", "auto"));
        assertEquals("ab:nodes=5000,tt=18", SelfPlay.effectiveSpec("ab:nodes=5000", "auto"));
        assertEquals("ab:nodes=20000,tt=18", SelfPlay.effectiveSpec("ab:nodes=20000", "auto"));
        assertEquals("ab:nodes=100,tt=14", SelfPlay.effectiveSpec("ab:nodes=100", "auto"));
        assertEquals("ab:nodes=1000,tt=12", SelfPlay.effectiveSpec("ab:nodes=1000,tt=12", "auto"));
        assertEquals("ab:tt=12,nodes=1000", SelfPlay.effectiveSpec("ab:tt=12,nodes=1000", "auto"));
        assertEquals("ab:nodes=1000", SelfPlay.effectiveSpec("ab:nodes=1000", "spec"));
    }
}
