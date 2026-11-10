package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.search.Mcts;
import sternhalma.search.MctsResult;
import sternhalma.search.RaceAdjudication;
import sternhalma.selfplay.Adjudication.Layer;
import sternhalma.tablebase.RaceTable;

/**
 * The self-play games of the loop: policy targets are the root visit shares of an independent search, value labels are
 * the result or the S1 verdict seen by the mover (the second mover included), random opening plies and race play carry
 * no policy weight, and a game is a function of settings, network and id alone.
 */
class LoopGameTest {

    private static final Parameters NET = Parameters.init(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), 11);

    private static LoopGame.Settings settings(long seed, double noise, int temperaturePlies, Adjudication.Config adjudication) {
        return new LoopGame.Settings(seed, 8, 1, 1.0, noise, 0.3, 1.0, temperaturePlies, 4, 10, 1, adjudication);
    }

    private static LoopGame game(LoopGame.Settings s) {
        return new LoopGame(s, Networks.fastest(NET));
    }

    private static Position replay(GameRecord r, int plies) {
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < plies; i++) {
            pos.make(r.moves()[i]);
        }
        return pos;
    }

    // ---- policy targets ----

    @Test
    void policyTargetsAreTheVisitSharesOfAnIndependentSearch() {
        // no noise and no sampling: a search is a function of its position, so it can be repeated
        LoopGame.Settings s = settings(5, 0.0, 0, Adjudication.Config.PRODUCTION);
        int compared = 0;
        int oneHot = 0;
        for (int id = 0; id < 3; id++) {
            GameRecord r = game(s).play(id);
            GameRecord.Samples samples = r.samples();
            int k = 0;
            for (int i = 0; i < samples.count(); i++) {
                int n = samples.moveCounts()[i];
                double sum = 0;
                for (int m = 0; m < n; m++) {
                    float t = samples.moveTarget()[k + m];
                    assertTrue(t >= 0 && t <= 1, "a share");
                    sum += t;
                }
                assertEquals(1.0, sum, 1e-5, "targets sum to 1, sample " + i);
                if ((samples.flags()[i] & 1) != 0) {
                    Position pos = replay(r, i);
                    MctsResult independent = new Mcts(Networks.fastest(NET), s.options(), 99).search(pos);
                    assertEquals(independent.sims(), 8);
                    assertEquals(n, independent.visits().length);
                    for (int m = 0; m < n; m++) {
                        assertEquals((double) independent.visits()[m] / 8, samples.moveTarget()[k + m], 1e-6,
                                "game " + id + ", sample " + i + ", move " + m);
                    }
                    // the move that was played is the most visited one (temperature 0)
                    int most = 0;
                    for (int m = 1; m < n; m++) {
                        most = independent.visits()[m] > independent.visits()[most] ? m : most;
                    }
                    assertEquals(independent.visits()[most], independent.visits()[samples.played()[i]]);
                    compared++;
                } else {
                    oneHot++;
                    assertEquals(1f, samples.moveTarget()[k + samples.played()[i]], 0f, "the played move alone");
                }
                k += n;
            }
        }
        assertTrue(compared > 100, "searched samples compared with an independent search: " + compared);
        assertTrue(oneHot > 5, "samples without a search exist (opening, race, single move): " + oneHot);
    }

    @Test
    void theFlagsAndWeightsOfSearchedOpeningAndRacePositions() {
        // adjudication off: the games are played to the end, through the race play of the passed positions
        LoopGame.Settings s = settings(6, 0.25, 10, Adjudication.Config.OFF);
        int searched = 0;
        int race = 0;
        int single = 0;
        for (int id = 0; id < 4; id++) {
            GameRecord r = game(s).play(id);
            GameRecord.Samples samples = r.samples();
            assertEquals(r.moves().length, samples.count(), "a game that is played out has a sample per ply");
            int k = 0;
            for (int i = 0; i < samples.count(); i++) {
                int n = samples.moveCounts()[i];
                byte flags = samples.flags()[i];
                if (i < r.openingPlies()) {
                    assertEquals(4, flags & 7, "random opening ply: bit 2 only");
                    assertEquals(0f, samples.policyWeight()[i]);
                    assertEquals(GameRecord.NO_SCORE, r.scores()[i]);
                } else if (r.scores()[i] == GameRecord.NO_SCORE) {
                    race++;
                    assertEquals(0, flags & 1, "race play: no search");
                    assertEquals(0f, samples.policyWeight()[i], "race play: no policy weight");
                } else if (n == 1) {
                    single++;
                    assertEquals(0, flags & 1, "a single legal move is not searched");
                    assertEquals(1f, samples.policyWeight()[i], "its target is exact and its gradient zero");
                } else {
                    searched++;
                    assertEquals(1, flags & 1, "a searched position has the full-search flag");
                    assertEquals(1f, samples.policyWeight()[i]);
                }
                assertEquals(0, flags & 2, "no adjudication: bit 1 is not set");
                assertEquals(1f, samples.valueWeight()[i], "every position has a value target");
                k += n;
            }
        }
        assertTrue(searched > 100 && race > 5, "searched " + searched + ", race " + race + ", single " + single);
    }

    @Test
    void aPositionWithASingleLegalMoveIsNotSearchedAndKeepsPolicyWeightOne() {
        // a real one, set up: a lone piece of player 0 in the corner cell 0, blocked by two pieces of player 1
        Position pos = Position.empty(RuleSet.defaults(2)).put(0, 0).put(1, 1).put(1, 3);
        assertEquals(1, pos.legalMoves().length, "the position has a single legal move");
        LoopGame.Settings s = new LoopGame.Settings(3, 8, 1, 1.0, 0.25, 0.3, 1.0, 10, 4, 10, 0, Adjudication.Config.OFF);
        GameRecord.Samples samples = game(s).play(0, pos).samples();
        assertEquals(1, samples.moveCounts()[0]);
        assertEquals(0, samples.flags()[0] & 1, "a single legal move is not searched");
        assertEquals(0, samples.flags()[0] & 4, "and it is no random opening ply");
        assertEquals(1f, samples.policyWeight()[0], "its target is exact and its gradient zero: weight 1");
        assertEquals(1f, samples.moveTarget()[0]);
        assertEquals(1f, samples.valueWeight()[0]);
        // the next position has many moves and is searched
        assertTrue(samples.moveCounts()[1] > 1);
        assertEquals(1, samples.flags()[1] & 1);
        assertEquals(1f, samples.policyWeight()[1]);
    }

    /** No random opening, so that the game depends on the seed through the search and the sampling alone. */
    private static LoopGame.Settings noOpening(long seed, double noise, int temperaturePlies) {
        return new LoopGame.Settings(seed, 8, 1, 1.0, noise, 0.3, 1.0, temperaturePlies, 0, 0, 1,
                Adjudication.Config.PRODUCTION);
    }

    @Test
    void theMoveIsSampledFromTheVisitsInTheFirstPliesAndTheMostVisitedAfterwards() {
        // temperature plies 0 and no noise: nothing is random, so the seed cannot matter
        GameRecord a = game(noOpening(1, 0.0, 0)).play(3);
        GameRecord b = game(noOpening(2, 0.0, 0)).play(3);
        assertTrue(a.moves().length > 40);
        assertArrayEquals(a.moves(), b.moves(), "the most visited move every time: the same game for any seed");
        // sampling in the first 30 plies: the seed matters, the same seed repeats
        GameRecord s1 = game(noOpening(1, 0.0, 30)).play(3);
        GameRecord s1Again = game(noOpening(1, 0.0, 30)).play(3);
        GameRecord s2 = game(noOpening(2, 0.0, 30)).play(3);
        assertArrayEquals(s1.moves(), s1Again.moves());
        assertFalse(Arrays.equals(s1.moves(), s2.moves()), "sampled moves follow the seed");
        // the first moves are sampled: they differ from the most visited game within the sampled plies
        assertFalse(Arrays.equals(Arrays.copyOf(s1.moves(), 30), Arrays.copyOf(a.moves(), 30)));
        // plies after the sampling are the most visited move of their position: the game of a late-sampling seed
        // agrees with a search-only continuation of its own position
        int late = 30;
        Position pos = replay(s1, late);
        MctsResult search = new Mcts(Networks.fastest(NET), noOpening(1, 0.0, 30).options(), 5).search(pos);
        assertEquals(s1.moves()[late], search.move(), "after the sampled plies the most visited move is played");
    }

    @Test
    void theSearchNoiseAndTheMoveSamplingAreSeededFromTheGameIdToo() {
        // nothing else differs between the games of one generation: no opening, same network, same settings
        Set<String> identical = new HashSet<>();
        Set<String> noisy = new HashSet<>();
        Set<String> sampled = new HashSet<>();
        for (int id = 0; id < 6; id++) {
            identical.add(Arrays.toString(game(noOpening(7, 0.0, 0)).play(id).moves()));
            noisy.add(Arrays.toString(game(noOpening(7, 0.25, 0)).play(id).moves()));
            sampled.add(Arrays.toString(game(noOpening(7, 0.0, 30)).play(id).moves()));
        }
        assertEquals(1, identical.size(), "without noise and sampling the id changes nothing: the comparison below is meaningful");
        assertTrue(noisy.size() >= 3, "the Dirichlet noise of game i is drawn from its own seed: " + noisy.size() + " different games");
        assertTrue(sampled.size() >= 3, "the sampling of game i is drawn from its own seed: " + sampled.size() + " different games");
    }

    @Test
    void sampleDrawsProportionalToVisitsToThePowerOneOverTemperature() {
        int[] visits = {0, 6, 3, 1};
        SplittableRandom rnd = new SplittableRandom(42);
        int draws = 200_000;
        for (double temperature : new double[] {1.0, 2.0, 0.5}) {
            int[] count = new int[4];
            for (int i = 0; i < draws; i++) {
                count[LoopGame.sample(visits, temperature, rnd.nextDouble())]++;
            }
            double[] w = new double[4];
            double sum = 0;
            for (int i = 1; i < 4; i++) {
                w[i] = Math.pow(visits[i], 1 / temperature);
                sum += w[i];
            }
            assertEquals(0, count[0], "a move without visits is never drawn");
            for (int i = 1; i < 4; i++) {
                assertEquals(w[i] / sum, (double) count[i] / draws, 0.005, "T = " + temperature + ", move " + i);
            }
        }
        // the edges of u
        assertEquals(1, LoopGame.sample(visits, 1.0, 0.0));
        assertEquals(3, LoopGame.sample(visits, 1.0, Math.nextDown(1.0)));
        assertEquals(1, LoopGame.sample(new int[] {0, 5, 0}, 1.0, 0.99));
    }

    // ---- value labels ----

    @Test
    void valueTargetsAreTheS1VerdictOrTheResultForTheMoverIncludingTheSecondMover() {
        RaceAdjudication independent = new RaceAdjudication(RaceTable.rows2());
        int s1Games = 0;
        int playedOut = 0;
        int secondMoverSamples = 0;
        for (Adjudication.Config adjudication : List.of(Adjudication.Config.PRODUCTION, Adjudication.Config.OFF)) {
            LoopGame.Settings s = settings(9, 0.25, 12, adjudication);
            for (int id = 0; id < 6; id++) {
                GameRecord r = game(s).play(id);
                GameRecord.Samples samples = r.samples();
                double[] label;
                if (r.label() == Layer.S1) {
                    s1Games++;
                    // the verdict of a second S1 run on the position at the cut
                    RaceAdjudication.Verdict v = independent.adjudicate(replay(r, r.labelPly()));
                    assertTrue(v != null, "S1 judges the position of the cut");
                    label = v.winner() == 0 ? new double[] {1, -1} : new double[] {-1, 1};
                    assertEquals(r.labelPly(), samples.count(), "samples exist for the plies before the cut");
                } else {
                    assertEquals(Layer.NONE, r.label());
                    assertTrue(r.playedOut());
                    playedOut++;
                    label = r.finalValues();
                    assertArrayEquals(label, r.labelValues());
                }
                for (int i = 0; i < samples.count(); i++) {
                    int mover = replay(r, i).sideToMove();
                    assertEquals(label[mover], samples.value()[2 * i], 0, "game " + id + ", ply " + i + ": the mover's value");
                    assertEquals(label[1 - mover], samples.value()[2 * i + 1], 0, "and the opponent's");
                    assertEquals(r.label() == Layer.S1 ? 2 : 0, samples.flags()[i] & 2, "bit 1 marks the adjudicated games");
                    secondMoverSamples += mover == 1 ? 1 : 0;
                    assertEquals(1f, samples.valueWeight()[i]);
                }
            }
        }
        assertTrue(s1Games >= 3, "adjudicated games: " + s1Games);
        assertTrue(playedOut >= 3, "games played out: " + playedOut);
        assertTrue(secondMoverSamples > 200, "second-mover samples: " + secondMoverSamples);
    }

    @Test
    void noSearchScoreEntersALabel() {
        // the stored score is the root value in thousandths: far from the race band of the alpha-beta scores
        LoopGame.Settings s = settings(12, 0.25, 12, Adjudication.Config.PRODUCTION);
        for (int id = 0; id < 4; id++) {
            GameRecord r = game(s).play(id);
            for (int score : r.scores()) {
                assertTrue(score == GameRecord.NO_SCORE || Math.abs(score) <= 1000, "score " + score);
            }
            assertEquals(null, r.margin(), "the margin layer is off");
            assertEquals(null, r.eval(), "the evaluation layer is off");
            assertEquals(0, r.raceScoresIgnored());
            assertTrue(r.label() == Layer.S1 || r.label() == Layer.NONE, "only S1 or the result labels a position");
        }
    }

    // ---- determinism ----

    private static void assertSameGame(GameRecord a, GameRecord b) {
        assertArrayEquals(a.moves(), b.moves());
        assertArrayEquals(a.scores(), b.scores());
        assertEquals(a.label(), b.label());
        assertEquals(a.labelPly(), b.labelPly());
        assertArrayEquals(a.samples().cells(), b.samples().cells());
        assertArrayEquals(a.samples().moveTarget(), b.samples().moveTarget());
        assertArrayEquals(a.samples().value(), b.samples().value());
        assertArrayEquals(a.samples().flags(), b.samples().flags());
        assertArrayEquals(a.samples().policyWeight(), b.samples().policyWeight());
    }

    @Test
    void aGameIsAFunctionOfSettingsNetworkAndIdNotOfOrderInstanceOrThread() throws Exception {
        LoopGame.Settings s = settings(21, 0.25, 14, Adjudication.Config.PRODUCTION);
        List<GameRecord> forward = new ArrayList<>();
        LoopGame one = game(s);
        for (int id = 0; id < 5; id++) {
            forward.add(one.play(id));
        }
        // other order, a fresh instance for every game
        for (int id = 4; id >= 0; id--) {
            assertSameGame(forward.get(id), game(s).play(id));
        }
        // other threads, each with a forward pass of its own, any interleaving
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            List<Future<GameRecord>> futures = new ArrayList<>();
            for (int id = 0; id < 5; id++) {
                int i = id;
                futures.add(pool.submit(() -> {
                    ForwardPass own = Networks.fastest(NET);
                    return new LoopGame(s, own).play(i);
                }));
            }
            for (int id = 0; id < 5; id++) {
                assertSameGame(forward.get(id), futures.get(id).get());
            }
        }
        // the games differ from each other and from those of another seed
        assertFalse(Arrays.equals(forward.get(0).moves(), forward.get(1).moves()));
        assertFalse(Arrays.equals(forward.get(0).moves(), game(settings(22, 0.25, 14, Adjudication.Config.PRODUCTION)).play(0).moves()));
    }

    @Test
    void theSettingsTextNamesEveryOptionThatChangesAGame() {
        LoopGame.Settings base = settings(1, 0.25, 14, Adjudication.Config.PRODUCTION);
        String d = base.describe();
        assertTrue(d.contains("seed=1") && d.contains("sims=8") && d.contains("noise=0.25") && d.contains("temp-plies=14")
                && d.contains("race=1") && d.contains("s1=1"), d);
        assertNotEquals(d, settings(2, 0.25, 14, Adjudication.Config.PRODUCTION).describe());
        assertNotEquals(d, settings(1, 0.3, 14, Adjudication.Config.PRODUCTION).describe());
        assertNotEquals(d, settings(1, 0.25, 15, Adjudication.Config.PRODUCTION).describe());
        assertNotEquals(d, settings(1, 0.25, 14, Adjudication.Config.OFF).describe());
        assertNotEquals(d, new LoopGame.Settings(1, 9, 1, 1.0, 0.25, 0.3, 1.0, 14, 4, 10, 1, Adjudication.Config.PRODUCTION).describe());
    }
}
