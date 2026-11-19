package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.nn.Orientation;
import sternhalma.search.Strategies;
import sternhalma.selfplay.Adjudication.Layer;

/** The four variants (2 or 3 players, adjacent or long jump) of the self-play tool: shards, labels, CLI, resume. */
class SelfPlayVariantsTest {

    /** A variant: players, jump rule and a cheap mover spec (the games must finish in seconds). */
    record Variant(int players, JumpRule jump, String spec) {
        SelfPlayGame.Settings settings(long seed) {
            Adjudication.Config adj = players == 3 ? Adjudication.Config.OFF
                    : jump == JumpRule.LONG ? new Adjudication.Config(false, 500, 2, 0, 4)
                            : Adjudication.Config.PRODUCTION;
            Teacher.Config teacher = players == 2 && jump == JumpRule.LONG ? new Teacher.Config(2, 0.06, 0.5, 0.2, 100)
                    : null;
            return new SelfPlayGame.Settings(spec, seed, adj, 0.1, OpeningSampler.Mode.UNIFORM, 4, 10, 30, 1, 0.05,
                    true, true, teacher, players, jump);
        }

        String label() {
            return players + "p " + jump;
        }
    }

    static final List<Variant> VARIANTS = List.of(
            new Variant(2, JumpRule.ADJACENT, Strategies.BEST_R1_SPEC + ",nodes=200,tt=14"),
            new Variant(2, JumpRule.LONG, Strategies.BROWSER_LONG_SPEC + ",nodes=200,tt=14"),
            new Variant(3, JumpRule.ADJACENT, "ab3:nodes=200,tt=14"),
            new Variant(3, JumpRule.LONG, "ab3:nodes=200,tt=14,jump=long"));

    private static GameShard.Read shard(Path dir, Variant v, int games) throws IOException {
        SelfPlayGame.Settings s = v.settings(1006);
        SelfPlayGame game = new SelfPlayGame(s);
        List<GameRecord> records = new ArrayList<>();
        for (int id = 0; id < games; id++) {
            records.add(game.play(id));
        }
        Path file = dir.resolve(v.players() + "p-" + v.jump() + ".safetensors");
        GameShard.write(file, s, 0, 0, records);
        return GameShard.read(file);
    }

    @Test
    void shardsOfEveryVariantReadBackWithTheirRulesLegalMovesAndTargets(@TempDir Path dir) throws Exception {
        for (Variant v : VARIANTS) {
            GameShard.Read r = shard(dir, v, 3);
            String name = v.label();
            RuleSet rules = GameShard.rules(r.metadata());
            assertEquals(v.settings(1006).rules(), rules, name + " rules");
            assertEquals(Integer.toString(v.players()), r.metadata().get("players"), name);
            assertTrue(r.metadata().get("rules").contains("jumpRule=" + v.jump()), name + ": " + r.metadata().get("rules"));
            assertEquals(3, r.games().size(), name);

            SampleFile.Contents c = r.contents();
            int n = c.tensor("ply").i16().length;
            assertTrue(n > 0, name + " has samples");
            assertArrayEquals(new long[] {n, v.players()}, c.tensor("value_target").shape(), name);
            assertArrayEquals(new long[] {3, v.players()}, c.tensor("game_values").shape(), name);
            int[] sampleGame = c.tensor("game").i32();
            int[] offsets = c.tensor("move_offsets").i32();
            byte[] from = c.tensor("move_from").u8();
            byte[] to = c.tensor("move_to").u8();
            float[] target = c.tensor("move_target").f32();
            float[] value = c.tensor("value_target").f32();
            short[] ply = c.tensor("ply").i16();
            short[] limit = c.tensor("move_limit").i16();

            int s = 0;
            for (int g = 0; g < r.games().size(); g++) {
                GameRecord game = r.games().get(g);
                // one sample per ply before the label ply
                int count = 0;
                for (int x : sampleGame) {
                    count += x == g ? 1 : 0;
                }
                assertEquals(game.labelPly(), count, name + " game " + g + " sample count");
                Position pos = Position.initial(rules);
                for (int k = 0; k < count; k++, s++) {
                    assertEquals(g, sampleGame[s], name);
                    assertEquals(k, ply[s], name + " ply of sample " + s);
                    assertEquals(rules.moveLimit(), limit[s], name + " move limit");
                    Orientation o = Orientation.of(pos);
                    int[] legal = pos.legalMoves();
                    assertEquals(legal.length, offsets[s + 1] - offsets[s], name + " legal move count at " + s);
                    double sum = 0;
                    boolean playedFound = false;
                    for (int i = 0; i < legal.length; i++) {
                        int cm = o.toCanonicalMove(legal[i]);
                        assertEquals(Move.from(cm), from[offsets[s] + i] & 0xFF, name + " from " + s + "/" + i);
                        assertEquals(Move.to(cm), to[offsets[s] + i] & 0xFF, name + " to " + s + "/" + i);
                        sum += target[offsets[s] + i];
                        playedFound |= legal[i] == game.moves()[k];
                    }
                    assertTrue(playedFound, name + " the played move is legal at sample " + s);
                    assertEquals(1.0, sum, 1e-4, name + " policy target of sample " + s + " sums to 1");
                    // value target: the mover first; z of relative player i is the label of absolute player i's seat
                    boolean teacher = v.players() == 2 && v.jump() == JumpRule.LONG;
                    double rowSum = 0;
                    for (int i = 0; i < v.players(); i++) {
                        float z = value[s * v.players() + i];
                        rowSum += z;
                        if (!teacher) {
                            // no teacher: exactly the label of the seat that relative player i sits in
                            assertEquals((float) game.labelValues()[o.absolutePlayer(i)], z, 0f, name + " value " + s);
                        }
                    }
                    assertEquals(0.0, rowSum, 1e-5, name + " value target of sample " + s + " sums to 0");
                    pos.make(game.moves()[k]);
                }
                if (v.players() == 3) {
                    // the label is Outcome.values of the finished game: no adjudication, one value per seat
                    for (int k = count; k < game.moves().length; k++) {
                        pos.make(game.moves()[k]);
                    }
                    Outcome out = pos.outcome() != null ? pos.outcome() : pos.noMovesOutcome();
                    assertTrue(game.playedOut(), name + " game " + g + " is played out");
                    assertEquals(Layer.NONE, game.label(), name);
                    assertArrayEquals(out.values(), game.labelValues(), 1e-6, name + " label = Outcome.values");
                    assertEquals(3, game.labelValues().length, name);
                }
            }
            assertEquals(n, s, name + " every sample belongs to a game");
        }
    }

    @Test
    void longHopShardsReplayOnlyUnderTheLongRule(@TempDir Path dir) throws Exception {
        // the recorded moves of a long-hop game are not all legal under the adjacent rule: reading the shard with the
        // wrong rules must be detectable (here: some move of 3 games is illegal under adjacent)
        Variant v = VARIANTS.get(1);
        GameShard.Read r = shard(dir, v, 3);
        RuleSet adjacent = RuleSet.defaults(2);
        boolean someIllegal = false;
        for (GameRecord g : r.games()) {
            Position pos = Position.initial(adjacent);
            try {
                for (int m : g.moves()) {
                    if (Arrays.stream(pos.legalMoves()).noneMatch(x -> x == m)) {
                        someIllegal = true;
                        break;
                    }
                    pos.make(m);
                }
            } catch (RuntimeException e) {
                someIllegal = true;
            }
        }
        assertTrue(someIllegal, "no long hop occurred in 3 games; the variant would not be tested");
        assertEquals(JumpRule.LONG, GameShard.rules(r.metadata()).jumpRule());
    }

    @Test
    void rulesOfOldShardsWithoutAJumpRuleAreAdjacent() {
        // a shard written before RuleSet had a jump rule: players and rules, no jumpRule
        Map<String, String> old = new HashMap<>();
        old.put("players", "2");
        old.put("rules", "RuleSet[players=2, moveLimit=200, leaveGoal=FORBIDDEN, blockadeRule=OPPONENT_FILLS, "
                + "suicideMoves=ILLEGAL, blockingStates=NONE, repetition=MOVE_LIMIT, drawHandling=SCORE_BY_DISTANCE, "
                + "ranking=FIRST_WINNER_ONLY]");
        assertEquals(RuleSet.defaults(2), GameShard.rules(old));
        assertEquals(RuleSet.defaults(2), GameShard.rules(Map.of("players", "2")));
        assertEquals(RuleSet.defaults(3), GameShard.rules(Map.of("players", "3")));
        RuleSet custom = RuleSet.defaults(3).withJumpRule(JumpRule.LONG).withMoveLimit(123);
        assertEquals(custom, GameShard.rules(Map.of("players", "3", "rules", custom.toString())));
        assertThrows(IllegalArgumentException.class,
                () -> GameShard.rules(Map.of("players", "2", "rules", RuleSet.defaults(3).toString())));
    }

    private static Map<String, String> opts(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void commandLineDefaultsPerVariant() {
        SelfPlayGame.Settings adjacent = SelfPlay.settings(opts("spec", "ab:nodes=1000", "seed", "1"));
        assertEquals(2, adjacent.players());
        assertEquals(JumpRule.ADJACENT, adjacent.jumpRule());
        assertEquals(Adjudication.Config.PRODUCTION, adjacent.adjudication());
        assertEquals(null, adjacent.teacher());
        // the description of an existing run does not change: resume of old data sets still matches
        assertFalse(adjacent.describe().contains("players"), adjacent.describe());
        assertFalse(adjacent.describe().contains("jump"), adjacent.describe());

        SelfPlayGame.Settings long2 = SelfPlay.settings(opts("jump", "long", "seed", "1"));
        assertEquals(JumpRule.LONG, long2.jumpRule());
        assertTrue(long2.spec().contains("jump=long") && long2.spec().contains("nodes=5000"), long2.spec());
        assertFalse(long2.adjudication().s1());
        assertEquals(500, long2.adjudication().margin());
        assertNotNull(long2.teacher());
        assertEquals(2, long2.teacher().depth());
        assertEquals(0.5, long2.teacher().alphaStart());
        assertTrue(long2.describe().endsWith(";jump=long"), long2.describe());

        SelfPlayGame.Settings three = SelfPlay.settings(opts("players", "3", "seed", "1"));
        assertEquals(Strategies.BROWSER_3P_SPEC, three.spec());
        assertFalse(three.adjudication().any());
        assertEquals(null, three.teacher());
        assertEquals(0.05, three.epsilon());
        SelfPlayGame.Settings threeLong = SelfPlay.settings(opts("players", "3", "jump", "long", "nodes", "1234",
                "seed", "1"));
        assertEquals("ab3:eval=linear-r2,nodes=1234,tt=16,jump=long", threeLong.spec());
        assertEquals(3, threeLong.players());
        assertTrue(three.describe().endsWith(";players=3"), three.describe());
        assertTrue(threeLong.describe().endsWith(";players=3;jump=long"), threeLong.describe());
    }

    @Test
    void commandLineRefusesWhatTheVariantCannotDo() {
        // S1 and the race table count single jumps
        assertThrows(IllegalArgumentException.class, () -> SelfPlay.settings(opts("jump", "long", "s1", "1", "seed", "1")));
        // no adjudication and no teacher for three seats
        assertThrows(IllegalArgumentException.class,
                () -> SelfPlay.settings(opts("players", "3", "margin", "500", "seed", "1")));
        assertThrows(IllegalArgumentException.class,
                () -> SelfPlay.settings(opts("players", "3", "teacher-depth", "2", "seed", "1")));
        assertThrows(IllegalArgumentException.class, () -> SelfPlay.settings(opts("players", "4", "spec", "x", "seed", "1")));
        assertThrows(IllegalArgumentException.class, () -> SelfPlay.settings(opts("jump", "far", "seed", "1")));
        // a spec of the other jump rule than the settings
        assertThrows(IllegalArgumentException.class, () -> new SelfPlayGame(new SelfPlayGame.Settings(
                Strategies.BEST_R1_SPEC + ",nodes=100", 1, Adjudication.Config.OFF, 0, OpeningSampler.Mode.UNIFORM, 4,
                10, 30, 1, 0.05, true, true, null, 2, JumpRule.LONG)));
    }

    private static String sha(Path f) throws Exception {
        return SelfPlayVariantsGoldenTest.digest(f);
    }

    @Test
    void aRunOfEveryVariantResumes(@TempDir Path dir) throws Exception {
        List<List<String>> runs = List.of(
                List.of("--spec", Strategies.BEST_R1_SPEC + ",nodes=100"),
                List.of("--jump", "long", "--nodes", "100"),
                List.of("--players", "3", "--nodes", "100"),
                List.of("--players", "3", "--jump", "long", "--nodes", "100"));
        int v = 0;
        for (List<String> extra : runs) {
            Path out = dir.resolve("run" + v++);
            List<String> args = new ArrayList<>(List.of("run", "--out", out.toString(), "--games", "4", "--seed", "7",
                    "--shard", "2", "--progress", "0"));
            args.addAll(extra);
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            PrintStream err = new PrintStream(log, true, StandardCharsets.UTF_8);
            assertEquals(0, SelfPlay.run(args.toArray(String[]::new), System.out, err), log.toString());
            Path[] shards;
            try (var s = Files.list(out)) {
                shards = s.filter(f -> f.getFileName().toString().endsWith(".safetensors")).sorted().toArray(Path[]::new);
            }
            assertEquals(2, shards.length, extra.toString());
            String first = sha(shards[0]);
            String second = sha(shards[1]);
            // a second run has nothing to do and leaves the shards alone
            assertEquals(0, SelfPlay.run(args.toArray(String[]::new), System.out, err), log.toString());
            assertEquals(first, sha(shards[0]));
            // the last shard is lost: the next run plays exactly it again, with the same content
            Files.delete(shards[1]);
            assertEquals(0, SelfPlay.run(args.toArray(String[]::new), System.out, err), log.toString());
            assertEquals(first, sha(shards[0]), extra + " first shard kept");
            assertEquals(second, sha(shards[1]), extra + " lost shard replayed identically");
            // the same directory with other variant settings is refused, not mixed
            List<String> other = new ArrayList<>(args);
            other.addAll(List.of("--epsilon", "0.1"));
            assertTrue(SelfPlay.run(other.toArray(String[]::new), System.out, err) != 0, "other settings must not resume");
        }
    }

    @Test
    void pilotPrintsTheFiguresOfEveryVariant() throws Exception {
        for (Variant v : VARIANTS) {
            VariantPilot.Stats st = VariantPilot.play(v.settings(5), 3, 2);
            assertEquals(3, st.games());
            assertTrue(st.meanSamples() > 0, v.label());
            assertTrue(st.line().startsWith(v.label().toLowerCase(java.util.Locale.ROOT)), st.line());
            assertTrue(st.line().contains("move limit") && st.line().contains("samples/game"), st.line());
            // plies per game: at least the opening; samples are the positions before the label
            for (int i = 0; i < 3; i++) {
                assertTrue(st.plies()[i] >= 4 && st.samples()[i] <= st.plies()[i], v.label());
            }
        }
    }

    @Test
    void pythonStandardLibraryReadsAThreePlayerLongShard(@TempDir Path dir) throws Exception {
        Variant v = VARIANTS.get(3);
        GameShard.Read r = shard(dir, v, 2);
        Path f = dir.resolve("3p-LONG.safetensors");
        String script = String.join("\n",
                "import json, struct, sys",
                "b = open(sys.argv[1], 'rb').read()",
                "n = struct.unpack('<Q', b[:8])[0]",
                "h = json.loads(b[8:8 + n])",
                "d = b[8 + n:]",
                "t = h['value_target']",
                "lo, hi = t['data_offsets']",
                "v = struct.unpack('<%df' % (t['shape'][0] * t['shape'][1]), d[lo:hi])",
                "k = t['shape'][1]",
                "m = h['__metadata__']",
                "print(m['players'], k, 'jumpRule=LONG' in m['rules'], max(abs(sum(v[i:i + k])) for i in range(0, len(v), k)) < 1e-5)");
        Path py = dir.resolve("read.py");
        Files.writeString(py, script);
        Process p;
        try {
            p = new ProcessBuilder("python3", "-I", py.toString(), f.toString()).redirectErrorStream(true).start();
        } catch (IOException e) {
            assumeTrue(false, "python3 not available: " + e.getMessage());
            return;
        }
        assumeTrue(p.waitFor(60, TimeUnit.SECONDS), "python3 did not finish");
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.exitValue(), out);
        assertEquals("3 3 True True", out);
        assertEquals(2, r.games().size());
    }
}
