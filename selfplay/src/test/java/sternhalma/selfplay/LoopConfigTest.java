package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sternhalma.nn.LearningRateSchedule;
import sternhalma.search.MctsOptions;
import sternhalma.search.MctsPlayer;
import sternhalma.search.MctsSpec;

/** The configuration of a loop run: what is hashed, the derived numbers, the evaluation players and the held-out split. */
class LoopConfigTest {

    private static final String INIT = "a".repeat(64);

    private static LoopConfig config(String... keyValues) {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            o.put(keyValues[i], keyValues[i + 1]);
        }
        return LoopConfig.of(o, INIT, "", 0);
    }

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        LoopConfig c = config();
        assertEquals(600, c.games());
        assertEquals(100, c.sims());
        assertEquals(1, c.mctsBatch());
        assertEquals(1.0, c.cpuct());
        assertEquals(0.25, c.noise());
        assertEquals(0.3, c.alpha());
        assertEquals(1.0, c.temperature());
        assertEquals(14, c.temperaturePlies());
        assertEquals(3e-4, c.lr());
        assertEquals(100, c.warmup());
        assertEquals(256, c.batch());
        assertEquals(4.0, c.reuse());
        assertEquals(500_000, c.window());
        assertEquals(100, c.gatePairs());
        assertEquals(10, c.pairShard());
        assertEquals(4, c.gateOpening());
        assertEquals(1600, c.baselineRung());
        assertEquals(50, c.baselinePairs());
        assertEquals(2.0, c.baselineSigmas());
        assertEquals(Adjudication.Config.PRODUCTION, c.adjudication(), "S1 only: the margin layer is off");
    }

    @Test
    void theJsonRoundTripsAndTheHashFollowsEveryOptionThatChangesAResult() {
        LoopConfig base = config();
        assertEquals(base, LoopConfig.fromJson(RunMonitor.parse(base.json())));
        assertEquals(base.sha256(), LoopConfig.fromJson(RunMonitor.parse(base.json())).sha256());
        Set<String> hashes = new HashSet<>();
        hashes.add(base.sha256());
        String[][] changes = {{"seed", "2"}, {"games", "601"}, {"shard-games", "30"}, {"sims", "101"}, {"mcts-batch", "2"},
            {"cpuct", "1.5"}, {"noise", "0.3"}, {"alpha", "0.2"}, {"temp", "0.9"}, {"temp-plies", "15"}, {"opening-min", "3"},
            {"opening-max", "11"}, {"race", "2"}, {"s1", "0"}, {"lr", "0.001"}, {"warmup", "50"}, {"batch", "128"},
            {"microbatch", "16"}, {"reuse", "3"}, {"window", "400000"}, {"augment", "0"}, {"value-coefficient", "0.5"},
            {"holdout", "0.2"}, {"gate-pairs", "60"}, {"pair-shard", "5"}, {"gate-opening", "6"}, {"baseline-rung", "400"},
            {"baseline-pairs", "40"}, {"baseline-seed", "9"}, {"baseline-sigmas", "1"}, {"ladder-spec", "ab:eval=distance"}};
        for (String[] change : changes) {
            LoopConfig changed = config(change[0], change[1]);
            assertNotEquals(base.sha256(), changed.sha256(), change[0] + " is part of the configuration");
            assertTrue(hashes.add(changed.sha256()), change[0] + " gives a hash that another option gave already");
            assertEquals(changed, LoopConfig.fromJson(RunMonitor.parse(changed.json())), change[0]);
        }
        assertNotEquals(base.sha256(), LoopConfig.of(new HashMap<>(), "b".repeat(64), "", 0).sha256(), "the init network");
        assertNotEquals(base.sha256(), LoopConfig.of(new HashMap<>(), INIT, "c".repeat(64), 5).sha256(), "the teacher data");
    }

    @Test
    void cadencesThreadsAndTheNumberOfGenerationsAreNotPartOfTheConfiguration() {
        LoopConfig base = config();
        for (String key : List.of("threads", "train-threads", "checkpoint-steps", "checkpoint-seconds", "keep",
                "progress-seconds", "eval-samples", "proxy-every", "proxy-seconds", "strength-every", "ladder",
                "strength-pairs", "strength-seed", "generations")) {
            assertEquals(base.sha256(), config(key, "7").sha256(), key);
        }
    }

    @Test
    void theStepsOfAGenerationAreTheCeilingOfReuseTimesNewSamplesOverTheBatch() {
        LoopConfig c = config(); // R = 4, batch 256
        assertEquals(157, c.plannedSteps(10_000)); // 156.25
        assertEquals(100, c.plannedSteps(6_400)); // exactly 100
        assertEquals(101, c.plannedSteps(6_401));
        assertEquals(1, c.plannedSteps(1));
        assertEquals(1, c.plannedSteps(0), "never fewer than one step");
        assertEquals(30, config("reuse", "1.5", "batch", "100").plannedSteps(2_000));
    }

    @Test
    void theSeedsOfTheGenerationsDifferAndAreFixedByTheRunSeed() {
        LoopConfig c = config();
        Set<Long> seeds = new HashSet<>();
        for (int g = 1; g <= 50; g++) {
            assertTrue(seeds.add(c.selfPlaySeed(g)));
            assertTrue(seeds.add(c.gateSeed(g)));
        }
        assertNotEquals(c.selfPlaySeed(1), config("seed", "2").selfPlaySeed(1));
        assertNotEquals(c.gateSeed(1), config("seed", "2").gateSeed(1));
        assertEquals(c.selfPlaySeed(3), config("threads", "1").selfPlaySeed(3));
    }

    @Test
    void theScheduleWarmsUpForTheConfiguredStepsAndThenStaysConstant() {
        LearningRateSchedule s = config("lr", "0.001", "warmup", "10").schedule();
        assertEquals(0.0001, s.rate(1, 0), 1e-12);
        assertEquals(0.001, s.rate(1, 9), 1e-12);
        assertEquals(0.001, s.rate(1, 10), 1e-12);
        assertEquals(0.001, s.rate(7, 5000), 1e-12, "constant, no decay");
    }

    @Test
    void theEvaluationPlayersPlayWithoutNoiseAndTheMostVisitedMove() {
        LoopConfig c = config("sims", "40", "cpuct", "1.5", "noise", "0.4", "temp", "2");
        MctsOptions e = MctsSpec.parse(c.evalPlayer()).options();
        assertEquals(0, e.noise(), "no Dirichlet noise in the gate, the baseline and the strength games");
        assertEquals(0, e.temperature(), "the most visited move");
        assertEquals(40, e.sims(), "the same search budget as the self-play");
        assertEquals(1.5, e.cpuct());
        assertEquals(1, e.race(), "race play as in the self-play");
        // and the self-play does use them
        MctsOptions s = c.settings(3).options();
        assertEquals(0.4, s.noise());
        assertEquals(40, s.sims());
        assertEquals(0, s.temperature(), "the sampling is done by the loop from the visits, not by the search");
    }

    @Test
    void optionsOutOfRangeAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> config("games", "0"));
        assertThrows(IllegalArgumentException.class, () -> config("holdout", "0"));
        assertThrows(IllegalArgumentException.class, () -> config("holdout", "1"));
        assertThrows(IllegalArgumentException.class, () -> config("baseline-pairs", "1"));
        assertThrows(IllegalArgumentException.class, () -> config("baseline-rung", "50"), "rungs 2 to 99 repeat rung 1");
        assertThrows(IllegalArgumentException.class, () -> config("reuse", "0"));
        assertThrows(IllegalArgumentException.class, () -> config("temp", "0"));
        assertThrows(IllegalArgumentException.class, () -> config("s1", "maybe"));
        assertEquals(0, config("baseline-pairs", "0").baselinePairs(), "0 switches the clause off");
        assertThrows(IllegalArgumentException.class, () -> LoopConfig.fromJson(RunMonitor.parse("{\"format\":\"x\"}")));
    }

    // ---- the held-out split ----

    private static Map<String, String> shard(long seed) {
        return Map.of("seed", Long.toString(seed));
    }

    @Test
    void exactlyOneGameInPeriodIsHeldOutWhateverTheFewGames() {
        for (long seed : List.of(1L, 2L, 3L, 1_000_004L, -5L)) {
            for (double fraction : List.of(0.5, 0.25, 0.1)) {
                int period = (int) Math.round(1 / fraction);
                int held = 0;
                for (int id = 0; id < 10 * period; id++) {
                    held += Loop.heldOut(shard(seed), id, fraction, 10 * period) ? 1 : 0;
                }
                assertEquals(10, held, "seed " + seed + ", fraction " + fraction);
                int first = 0;
                for (int id = 0; id < period; id++) {
                    first += Loop.heldOut(shard(seed), id, fraction, period) ? 1 : 0;
                }
                assertEquals(1, first, "even the first " + period + " games hold exactly one out");
            }
        }
    }

    @Test
    void aGenerationOfFewGamesHoldsOutAtLeastOneAndKeepsAtLeastOneToTrainOn() {
        // review finding: with 4 games and --holdout 0.1 the offset was out of range for 5 of 6 seeds: no held-out game
        for (long seed = -20; seed <= 200; seed++) {
            for (double fraction : List.of(0.5, 0.25, 0.1, 0.01)) {
                for (int games : new int[] {2, 3, 4, 5, 9, 10, 11, 25}) {
                    int held = 0;
                    for (int id = 0; id < games; id++) {
                        held += Loop.heldOut(shard(seed), id, fraction, games) ? 1 : 0;
                    }
                    int period = (int) Math.min(games, Math.max(2, Math.round(1 / fraction)));
                    assertTrue(held >= 1 && held <= games - 1, "seed " + seed + ", fraction " + fraction + ", games " + games
                            + ": " + held + " held out");
                    assertTrue(held == games / period || held == (games + period - 1) / period,
                            "every period-th game (one when there are fewer games than the period): " + held);
                }
            }
            int four = 0;
            for (int id = 0; id < 4; id++) {
                four += Loop.heldOut(shard(seed), id, 0.1, 4) ? 1 : 0;
            }
            assertEquals(1, four, "4 games at 0.1: exactly one, seed " + seed);
        }
        // the seed picks which one
        Set<Integer> picked = new HashSet<>();
        for (long seed = 1; seed <= 60; seed++) {
            for (int id = 0; id < 4; id++) {
                if (Loop.heldOut(shard(seed), id, 0.1, 4)) {
                    picked.add(id);
                }
            }
        }
        assertEquals(Set.of(0, 1, 2, 3), picked);
        // one game cannot be split
        assertFalse(Loop.heldOut(shard(1), 0, 0.1, 1));
    }

    @Test
    void theHeldOutGamesOfTwoSeedsDiffer() {
        Set<Integer> offsets = new HashSet<>();
        for (long seed = 1; seed <= 40; seed++) {
            for (int id = 0; id < 10; id++) {
                if (Loop.heldOut(shard(seed), id, 0.1, 10)) {
                    offsets.add(id);
                }
            }
        }
        assertTrue(offsets.size() >= 6, "the offset is a function of the shard seed: " + offsets);
    }

    @Test
    void aGameIsHeldOutOrTrainedNeverBoth() {
        // the two buffers use the filter and its negation; the property is the function's, checked on a range
        for (int id = 0; id < 100; id++) {
            boolean held = Loop.heldOut(shard(7), id, 0.1, 100);
            assertEquals(held, Loop.heldOut(shard(7), id, 0.1, 100), "a function of seed, id and games alone");
        }
    }

    @Test
    void theMctsPlayerOfAnEvaluationSpecIsBuiltFromTheSpecText() {
        // NetPlayers builds the players of the gate, the baseline and the strength record from this text
        LoopConfig c = config("sims", "3");
        var parameters = sternhalma.nn.Parameters.init(sternhalma.nn.NetworkConfig.tiny(2), 1);
        var factory = NetPlayers.factory(c.evalPlayer(), parameters);
        MctsPlayer p = (MctsPlayer) factory.apply(5);
        assertEquals(0, p.mcts().options().noise());
        assertEquals(3, p.mcts().options().sims());
    }
}
