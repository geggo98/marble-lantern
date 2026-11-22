package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.TriangleRows;
import sternhalma.nn.Encoder;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.TrainingBatch;
import sternhalma.search.PlayerSpec;
import sternhalma.search.Strategies;

/**
 * The training tools on the 5-row board (plan "neural networks for the 5-row board", sections 2.3 and 3): self-play
 * settings and their defaults, sample shards, the replay buffer, the loop and match configuration. A 5-row shard
 * trains a network that lists 5 rows and is refused by name everywhere else.
 */
class FiveRowsDataTest {

    private static Map<String, String> opts(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void shardRulesRoundTripFiveAndDefaultToFour() {
        for (int players : new int[] {2, 3}) {
            RuleSet five = RuleSet.defaults(players, TriangleRows.FIVE).withJumpRule(RuleSet.JumpRule.LONG);
            assertEquals(five, GameShard.rules(Map.of("players", "" + players, "rules", five.toString())));
            RuleSet four = RuleSet.defaults(players);
            // a rules text written before the switch has no triangleRows key
            assertEquals(TriangleRows.FOUR,
                    GameShard.rules(Map.of("players", "" + players, "rules", four.toString())).triangleRows());
            assertEquals(four, GameShard.rules(Map.of("players", "" + players)));
        }
    }

    @Test
    void bestSpecDeclinesFiveRows() {
        // the owner asked for BEST_SPEC as the teacher; its race play and race evaluator know 10 pieces only
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PlayerSpec.parse(Strategies.BEST_SPEC + ",rows=5"));
        assertTrue(e.getMessage().contains("rows=5"), e.getMessage());
        assertEquals(TriangleRows.FIVE, PlayerSpec.parse(Strategies.BROWSER_ROWS5_SPEC).rows());
        assertEquals(0, PlayerSpec.parse(Strategies.BROWSER_ROWS5_SPEC).race());
    }

    @Test
    void rowsFiveSettingsTakeTheRows5SpecsAndSwitchS1Off() {
        SelfPlayGame.Settings s = SelfPlay.settings(opts("seed", "1", "rows", "5"));
        assertEquals(Strategies.BROWSER_ROWS5_SPEC + ",nodes=5000,tt=18", s.spec());
        assertEquals(TriangleRows.FIVE, s.rules().triangleRows());
        assertEquals(250, s.rules().moveLimit());
        assertFalse(s.adjudication().s1());
        assertTrue(s.describe().endsWith(";rows=5"), s.describe());

        SelfPlayGame.Settings three = SelfPlay.settings(opts("seed", "1", "rows", "5", "players", "3"));
        assertEquals(Strategies.BROWSER_3P_ROWS5_SPEC, three.spec());
        assertEquals(375, three.rules().moveLimit());
        SelfPlayGame.Settings longHop = SelfPlay.settings(opts("seed", "1", "rows", "5", "jump", "long", "nodes", "700"));
        assertTrue(longHop.spec().startsWith(Strategies.BROWSER_ROWS5_LONG_SPEC + ",nodes=700"), longHop.spec());
        // a given spec gets rows=5
        assertTrue(SelfPlay.settings(opts("seed", "1", "rows", "5", "spec", "ab:eval=linear-r3,nodes=50")).spec()
                .contains(",rows=5"));

        assertThrows(IllegalArgumentException.class, () -> SelfPlay.settings(opts("seed", "1", "rows", "5", "s1", "1")));
        assertThrows(IllegalArgumentException.class, () -> SelfPlay.settings(opts("seed", "1", "rows", "6")));
        // a spec of the other size is refused when the game is set up
        SelfPlayGame.Settings mismatch = SelfPlay.settings(opts("seed", "1", "rows", "5", "spec",
                "ab:eval=linear-r3,nodes=50,rows=4"));
        assertThrows(IllegalArgumentException.class, () -> new SelfPlayGame(mismatch));
    }

    @Test
    void fourRowSettingsAreUnchanged() {
        SelfPlayGame.Settings s = SelfPlay.settings(opts("seed", "1", "spec", "ab:eval=linear-r3,nodes=50"));
        assertEquals(TriangleRows.FOUR, s.rules().triangleRows());
        assertFalse(s.describe().contains("rows"), s.describe());
        assertTrue(s.adjudication().s1());
    }

    private static List<Path> shards(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().matches("samples-.*\\.safetensors")).sorted().toList();
        }
    }

    /** Two teacher games on 5 rows through the command line, as the runbook starts them. */
    private static Path fiveRowShards(Path dir) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SelfPlay.run(new String[] {"run", "--out", dir.toString(), "--rows", "5", "--games", "2",
                "--shard", "2", "--seed", "7", "--nodes", "300", "--teacher-depth", "2", "--teacher-alpha",
                "0.7,0.3,127", "--progress", "100"}, System.out, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    void fiveRowShardsTrainAFiveRowBufferWhoseBatchesAreTheEncoderOutput(@TempDir Path dir) throws Exception {
        fiveRowShards(dir);
        List<Path> files = shards(dir);
        assertEquals(1, files.size());
        GameShard.Read read = GameShard.read(files.get(0));
        assertEquals(TriangleRows.FIVE, GameShard.rules(read.metadata()).triangleRows());
        for (short limit : read.contents().tensor("move_limit").i16()) {
            assertEquals(250, limit);
        }
        ReplayBuffer buffer = new ReplayBuffer(2, Integer.MAX_VALUE, false, PlaneSet.CLASSIC1,
                EnumSet.of(TriangleRows.FIVE));
        ReplayBuffer.Loaded loaded = buffer.addGeneration(0, files);
        assertEquals(List.of(), loaded.skipped());

        // replay the games: sample k of the buffer is position k of the games, in order
        RuleSet rules = RuleSet.defaults(2, TriangleRows.FIVE);
        int k = 0;
        for (GameRecord g : read.games()) {
            Position p = Position.initial(rules);
            for (int ply = 0; ply < g.labelPly(); ply++, k++) {
                TrainingBatch b = buffer.batchOf(new int[] {k}, new boolean[] {false});
                float[] expected = new float[PlaneSet.CLASSIC1.inputSize()];
                Encoder.encode(PlaneSet.CLASSIC1, p, expected, 0);
                assertArrayEquals(expected, b.input(), "game " + g.id() + ", ply " + ply);
                p.make(g.moves()[ply]);
            }
        }
        assertEquals(buffer.size(), k, "every sample checked");
        assertTrue(k > 100, "two full 5-row games: " + k);
    }

    @Test
    void aFiveRowShardIsAWrongConfigurationForAFourRowOrMixedBuffer(@TempDir Path dir) throws Exception {
        List<Path> files = shards(fiveRowShards(dir));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ReplayBuffer(2, 100, false, PlaneSet.CLASSIC1).addGeneration(0, files));
        assertTrue(e.getMessage().contains("5-row"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ReplayBuffer.mixed(100, false, PlaneSet.CLASSIC1V, ReplayBuffer.Strata.EQUAL).addGeneration(0, files));
        assertTrue(Files.exists(files.get(0)), "a wrong configuration renames nothing");
        // and a 4-row shard for a 5-row buffer
        Path four = SyntheticShards.write(dir, "four.safetensors", 2, 7, 0, 2, 3, 5);
        assertThrows(IllegalArgumentException.class, () -> new ReplayBuffer(2, 100, false, PlaneSet.CLASSIC1,
                Set.of(TriangleRows.FIVE)).addGeneration(1, List.of(four)));
    }

    @Test
    void gen0ConfigOfFiveRowsNamesItsSizeAndTheNetworkListsIt() {
        Gen0.Config five = new Gen0.Config("tiny", 1, 10, 4, 2, 1e-3, 2, 1, true, 0.1, "x", 1, "classic1", "cpu",
                "equal", "5");
        assertEquals(Set.of(TriangleRows.FIVE), five.network().rows());
        assertTrue(five.json().contains("\"rows\":5"), five.json());
        Gen0.Config four = new Gen0.Config("tiny", 1, 10, 4, 2, 1e-3, 2, 1, true, 0.1, "x", 1, "classic1", "cpu",
                "equal");
        assertFalse(four.json().contains("\"rows\""), four.json());
        assertEquals(Set.of(TriangleRows.FOUR), four.network().rows());
        assertThrows(IllegalArgumentException.class, () -> new Gen0.Config("tiny", 1, 10, 4, 2, 1e-3, 2, 1, true, 0.1,
                "x", 1, "classic1v", "cpu", "equal", "5"));
        assertEquals(Strategies.BROWSER_ROWS5_SPEC, Gen0.cadence(opts("rows", "5")).ladderBase());
        assertEquals(Strategies.BEST_SPEC, Gen0.cadence(opts()).ladderBase());
    }

    @Test
    void loopConfigOfFiveRowsSwitchesRaceAndS1OffAndTakesTheRows5Ladder() {
        String init = "a".repeat(64);
        LoopConfig five = LoopConfig.of(opts("rows", "5"), init, "", 0);
        assertEquals(TriangleRows.FIVE, five.rows());
        assertEquals(0, five.race());
        assertFalse(five.s1());
        assertEquals(Strategies.BROWSER_ROWS5_SPEC, five.ladderSpec());
        assertEquals(250, five.rules().moveLimit());
        assertTrue(five.json().contains("\"rows\":5"), five.json());
        assertEquals(five, LoopConfig.fromJson(RunMonitor.parse(five.json())));
        assertTrue(five.settings(1).describe().endsWith(";rows=5"));
        assertTrue(five.evalPlayer().endsWith("race=0"), five.evalPlayer());

        LoopConfig four = LoopConfig.of(opts(), init, "", 0);
        assertFalse(four.json().contains("\"rows\""), four.json());
        assertEquals(TriangleRows.FOUR, LoopConfig.fromJson(RunMonitor.parse(four.json())).rows());

        assertThrows(IllegalArgumentException.class, () -> LoopConfig.of(opts("rows", "5", "race", "1"), init, "", 0));
        assertThrows(IllegalArgumentException.class, () -> LoopConfig.of(opts("rows", "5", "s1", "1"), init, "", 0));
        assertThrows(IllegalArgumentException.class,
                () -> LoopConfig.of(opts("rows", "5", "ladder-spec", Strategies.BROWSER_LONG_SPEC.replace(",jump=long", "")),
                        init, "", 0), "a 4-row ladder in a 5-row run");
    }

    @Test
    void aLoopGameOnFiveRowsWritesFiveRowSamples() {
        NetworkConfig cfg = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1).withRows(EnumSet.of(TriangleRows.FIVE));
        LoopGame.Settings s = new LoopGame.Settings(3, 2, 1, 1.0, 0.25, 0.3, 1.0, 10, 4, 10, 0, Adjudication.Config.OFF,
                TriangleRows.FIVE);
        GameRecord r = new LoopGame(s, Networks.fastest(Parameters.init(cfg, 5))).play(1);
        assertTrue(r.moves().length > 0 && r.moves().length <= 250, "plies " + r.moves().length);
        // the moves are legal on 5 rows (replay throws otherwise) and the sample cells hold 15 pieces per side
        Position p = Position.initial(RuleSet.defaults(2, TriangleRows.FIVE));
        for (int m : r.moves()) {
            assertTrue(Arrays.stream(p.legalMoves()).anyMatch(x -> x == m));
            p.make(m);
        }
        byte[] cells = r.samples().cells();
        int pieces = 0;
        for (int c = 0; c < 121; c++) {
            pieces += cells[c] != 0 ? 1 : 0;
        }
        assertEquals(30, pieces);
        assertThrows(IllegalArgumentException.class, () -> new LoopGame.Settings(3, 2, 1, 1.0, 0.25, 0.3, 1.0, 10, 4, 10,
                0, Adjudication.Config.PRODUCTION, TriangleRows.FIVE), "no S1 on 5 rows");
    }

    /**
     * One generation of the loop on 5 rows end to end: self-play, training, gate, baseline and strength all on the
     * 5-row board, with the 5-row teacher shards as generation 0 of the window; a 4-row init network is refused.
     */
    @Test
    void aLoopRunOnFiveRowsPlaysAndTrainsOnFiveRows(@TempDir Path dir) throws Exception {
        Path teacher = fiveRowShards(dir.resolve("teacher"));
        Path init = dir.resolve("init.safetensors");
        Parameters.init(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1).withRows(EnumSet.of(TriangleRows.FIVE)), 7)
                .save(init);
        List<String> o = LoopFixture.options("--generations", "1", "--rows", "5", "--teacher", teacher.toString(),
                "--init", init.toString());
        LoopFixture.Result r = LoopFixture.loop(dir.resolve("run"), LoopFixture.NEVER, o);
        assertEquals(0, r.code(), r.err());
        Path gen1 = dir.resolve("run").resolve("gen-0001");
        assertTrue(Files.exists(gen1.resolve("decision.json")), r.err());
        List<Path> shards = shards(gen1.resolve("samples"));
        assertFalse(shards.isEmpty(), "self-play shards of generation 1 in " + gen1);
        GameShard.Read read = GameShard.read(shards.get(0));
        assertEquals(TriangleRows.FIVE, GameShard.rules(read.metadata()).triangleRows());
        assertTrue(Files.readString(dir.resolve("run").resolve("run.json")).contains("\"rows\":5"));

        Path four = dir.resolve("four.safetensors");
        Parameters.init(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), 7).save(four);
        LoopFixture.Result refused = LoopFixture.loop(dir.resolve("run4"), LoopFixture.NEVER,
                LoopFixture.options("--generations", "1", "--rows", "5", "--init", four.toString()));
        assertEquals(1, refused.code());
        assertTrue(refused.err().contains("triangle_rows=4"), refused.err());
    }

    @Test
    void mctsMatchOnFiveRowsPlaysTheFiveRowLadderInItsOwnFile(@TempDir Path dir) throws Exception {
        Path net = dir.resolve("five.safetensors");
        Parameters.init(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1).withRows(EnumSet.of(TriangleRows.FIVE)), 3)
                .save(net);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MctsMatch.run(new String[] {"--out", dir.resolve("m").toString(), "--net", net.toString(),
                "--subject", "mcts:sims=2,batch=1,race=0", "--vs", "ab:1", "--pairs", "2", "--seed", "4", "--threads",
                "2", "--rows", "5", "--progress-seconds", "100"}, System.out,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        // the 4-row network is refused on 5 rows by the evaluator (the match fails, it does not play wrong inputs)
        Path four = dir.resolve("four.safetensors");
        Parameters.init(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), 3).save(four);
        int refused = MctsMatch.run(new String[] {"--out", dir.resolve("n").toString(), "--net", four.toString(),
                "--subject", "mcts:sims=2,batch=1,race=0", "--vs", "ab:1", "--pairs", "2", "--seed", "4", "--threads",
                "1", "--rows", "5", "--progress-seconds", "100"}, System.out, System.err);
        assertEquals(2, refused, "a usage error before any game");
        // a 4-row ladder spec in 5-row games is a usage error
        assertEquals(2, MctsMatch.run(new String[] {"--out", dir.resolve("o").toString(), "--net", net.toString(),
                "--subject", "mcts:sims=2,race=0", "--vs", "ab:1", "--rows", "5", "--ladder-spec", "ab:eval=distance"},
                System.out, System.err));
    }

    @Test
    void strengthEstimateOnTheRows5LadderPlaysFiveRowGames() {
        NetworkConfig cfg = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1).withRows(EnumSet.of(TriangleRows.FIVE));
        List<StrengthEstimate.Rung> r = StrengthEstimate.estimate(Networks.fastest(Parameters.init(cfg, 9)),
                Strategies.BROWSER_ROWS5_SPEC, List.of(1), 2, 1, 1, () -> false, n -> { });
        assertEquals(1, r.size());
        assertEquals(4, r.get(0).wins() + r.get(0).draws() + r.get(0).losses());
    }
}
