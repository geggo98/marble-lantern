package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Encoder;
import sternhalma.nn.Orientation;
import sternhalma.search.AlphaBeta;
import sternhalma.search.Evaluators;
import sternhalma.search.PlayerSpec;
import sternhalma.search.RaceAdjudication;
import sternhalma.search.Scores;
import sternhalma.search.Strategies;
import sternhalma.selfplay.Adjudication.Layer;

/** Teacher samples for generation 0 (round-2 plan E10): targets, band rules, expansion, files and resume. */
class TeacherTest {

    static final String SPEC = Strategies.BEST_R1_SPEC + ",nodes=300,tt=12";
    /** Futility, race play and the race band, as BEST_SPEC has them, with the committed rows&gt;=2 table. */
    static final String RACE_SPEC = Strategies.BEST_R1_SPEC.replace("eval=linear", "eval=racelin")
            + ",fut=1,futk=30,fut2=1,race=1,nodes=300,tt=12";
    static final Teacher.Config DEPTH2 = new Teacher.Config(2, 0.06, 0.7, 0.3, 100);

    static SelfPlayGame.Settings settings(String spec, Teacher.Config teacher, Adjudication.Config adj,
            double control) {
        return new SelfPlayGame.Settings(spec, 1010, adj, control, OpeningSampler.Mode.UNIFORM, 4, 10, 30, 1, 0.05,
                true, true, teacher);
    }

    // ---- the formulas

    @Test
    void softmaxSumsToOneAndFollowsTheScoresWithPositiveTemperature() {
        double[] p = Teacher.softmax(new int[] {100, 0, -100, 100}, 0.1);
        double sum = 0;
        for (double x : p) {
            sum += x;
            assertTrue(x > 0);
        }
        assertEquals(1.0, sum, 1e-12);
        assertEquals(p[0], p[3], 1e-15, "equal scores, equal probability");
        assertTrue(p[0] > p[1] && p[1] > p[2], "a higher score gets more probability");
        // exp(10) : 1 : exp(-10) up to the normaliser
        assertEquals(Math.exp(10), p[0] / p[1], 1e-6 * Math.exp(10));
        assertEquals(Math.exp(-10), p[2] / p[1], 1e-12);
    }

    @Test
    void softmaxSurvivesMateAndRaceScores() {
        double[] p = Teacher.softmax(new int[] {AlphaBeta.MATE - 3, 40, -(AlphaBeta.MATE - 2), 5200}, 0.1);
        double sum = 0;
        for (double x : p) {
            sum += x;
            assertFalse(Double.isNaN(x));
        }
        assertEquals(1.0, sum, 1e-12);
        assertEquals(1.0, p[0], 1e-12, "the mate takes everything");
        assertEquals(1, Teacher.softmax(new int[] {7}, 0.1).length);
        assertEquals(1.0, Teacher.softmax(new int[] {7}, 0.1)[0], 0);
    }

    @Test
    void defaultTemperaturesAndAlphaSchedule() {
        assertEquals(0.1, Teacher.Config.defaultTau(3));
        assertEquals(0.06, Teacher.Config.defaultTau(2));
        assertThrows(IllegalArgumentException.class, () -> Teacher.Config.defaultTau(4));
        Teacher.Config c = new Teacher.Config(3, 0.1, 0.7, 0.3, 100);
        assertEquals(0.7, c.alpha(0), 1e-12);
        assertEquals(0.5, c.alpha(50), 1e-12);
        assertEquals(0.3, c.alpha(100), 1e-12);
        assertEquals(0.3, c.alpha(180), 1e-12, "constant after the ramp");
        assertThrows(IllegalArgumentException.class, () -> new Teacher.Config(3, -0.1, 0.7, 0.3, 100));
        assertThrows(IllegalArgumentException.class, () -> new Teacher.Config(3, 0, 0.7, 0.3, 100));
        assertThrows(IllegalArgumentException.class, () -> new Teacher.Config(0, 0.1, 0.7, 0.3, 100));
        assertThrows(IllegalArgumentException.class, () -> new Teacher.Config(3, 0.1, 1.2, 0.3, 100));
        assertTrue(c.describe().contains("depth=3") && c.describe().contains("tau=0.1"), c.describe());
    }

    @Test
    void theBlendFollowsTheFormulaForOrdinaryAndMateScores() {
        Teacher.Config c = new Teacher.Config(3, 0.1, 0.7, 0.3, 100);
        // z' = (1 - a) z + a (2 sigma(score / 75) - 1) for the mover, the negative for the opponent
        double t = 2 / (1 + Math.exp(-150.0 / 75)) - 1;
        double[] v = c.value(new double[] {1, -1}, 50, 150, false);
        double a = 0.5;
        assertEquals((1 - a) * 1 + a * t, v[0], 1e-12);
        assertEquals((1 - a) * -1 + a * -t, v[1], 1e-12);
        assertEquals(-v[0], v[1], 1e-12, "zero sum stays zero sum");
        double[] lost = c.value(new double[] {-1, 1}, 50, 150, false);
        assertEquals((1 - a) * -1 + a * t, lost[0], 1e-12, "the game result and the search can disagree");
        double[] mate = c.value(new double[] {-1, 1}, 0, AlphaBeta.MATE - 5, false);
        assertEquals(0.3 * -1 + 0.7 * 1, mate[0], 1e-9, "a mate score is a certain win in the teacher part");
        double[] mated = c.value(new double[] {1, -1}, 100, -(AlphaBeta.MATE - 5), false);
        assertEquals(0.7 * 1 + 0.3 * -1, mated[0], 1e-9);
    }

    @Test
    void raceBandScoresAndPassedPositionsGiveTheGameResultOnly() {
        Teacher.Config c = new Teacher.Config(3, 0.1, 0.7, 0.3, 100);
        for (int score : new int[] {Scores.RACE_BASE, Scores.RACE_BASE + 1400, -(Scores.RACE_BASE + 200),
                AlphaBeta.MATE - 1001, -(AlphaBeta.MATE - 1001)}) {
            if (Scores.kind(score) != Scores.Kind.RACE) {
                continue;
            }
            assertArrayEquals(new double[] {-1, 1}, c.value(new double[] {-1, 1}, 30, score, false),
                    "race band score " + score);
            assertArrayEquals(new double[] {0, 0}, c.value(new double[] {0, 0}, 30, score, false));
        }
        // a passed position takes its value from S1 or the result, whatever the search said
        assertArrayEquals(new double[] {1, -1}, c.value(new double[] {1, -1}, 30, -400, true));
        assertTrue(Teacher.usesScore(Scores.RACE_BASE - 1) && Teacher.usesScore(-(Scores.RACE_BASE - 1)));
        assertFalse(Teacher.usesScore(Scores.RACE_BASE) || Teacher.usesScore(-Scores.RACE_BASE));
        assertTrue(Teacher.usesScore(AlphaBeta.MATE - 7) && Teacher.usesScore(-(AlphaBeta.MATE - 7)));
    }

    @Test
    void theTeacherSearchIsExactAndSettingsDescribeItself() {
        Teacher teacher = new Teacher(DEPTH2, PlayerSpec.parse(RACE_SPEC));
        assertEquals(0, teacher.options().lmr());
        assertFalse(teacher.options().futilityOn());
        String d = teacher.searchDescription();
        assertTrue(d.contains("fut=0") && d.contains("lmr=0") && d.contains("qs=0") && d.contains("depth=2"), d);
        assertTrue(d.contains("window=full") && d.contains("asp=0"), d);
        // the file settings do not change for runs without teacher
        String off = settings(SPEC, null, Adjudication.Config.OFF, 0).describe();
        assertFalse(off.contains("teacher"), off);
        assertTrue(settings(SPEC, DEPTH2, Adjudication.Config.OFF, 0).describe().contains("teacher=" + DEPTH2.describe()));
    }

    // ---- samples

    /** A sample's slice of the file tensors. */
    private static float[] slice(float[] a, int from, int len) {
        return java.util.Arrays.copyOfRange(a, from, from + len);
    }

    @Test
    void policyTargetsSumToOneRespectTheLegalMaskAndEqualTheSoftmaxOfTheExactScores() {
        SelfPlayGame game = new SelfPlayGame(settings(SPEC, DEPTH2, Adjudication.Config.DEFAULT, 0));
        PlayerSpec spec = PlayerSpec.parse(SPEC);
        AlphaBeta oracle = new AlphaBeta(Evaluators.byName(spec.evaluator(), spec.weights()), 16, true,
                spec.options().exact());
        for (int id = 0; id < 3; id++) {
            GameRecord g = game.play(id);
            GameRecord.Samples s = g.samples();
            Position pos = Position.initial(RuleSet.defaults(2));
            int k = 0;
            for (int i = 0; i < s.count(); i++) {
                int[] legal = pos.legalMoves();
                assertEquals(legal.length, s.moveCounts()[i], "one target per legal move, none for anything else");
                Orientation o = Orientation.of(pos);
                double sum = 0;
                for (int j = 0; j < legal.length; j++) {
                    assertEquals(legal[j], o.fromCanonicalMove(Move.of(s.moveFrom()[k + j] & 0xFF,
                            s.moveTo()[k + j] & 0xFF)), "legal move " + j);
                    assertTrue(s.moveTarget()[k + j] >= 0f);
                    sum += s.moveTarget()[k + j];
                }
                assertEquals(1.0, sum, 1e-5, "sample " + i);
                int[] scores = oracle.rootScores(pos, 2);
                if ((s.flags()[i] & 1) != 0) {
                    double[] expected = Teacher.softmax(scores, 0.06);
                    for (int j = 0; j < legal.length; j++) {
                        assertEquals(expected[j], s.moveTarget()[k + j], 1e-6, "sample " + i + " move " + j);
                    }
                }
                k += legal.length;
                pos.make(g.moves()[i]);
            }
        }
    }

    @Test
    void flagsWeightsAndExpansionFollowTheFormat() {
        SelfPlayGame game = new SelfPlayGame(settings(SPEC, DEPTH2, Adjudication.Config.DEFAULT, 0));
        float[] planes = new float[Encoder.INPUT_SIZE];
        for (int id = 0; id < 3; id++) {
            GameRecord g = game.play(id);
            GameRecord.Samples s = g.samples();
            assertNotNull(s);
            Position pos = Position.initial(RuleSet.defaults(2));
            for (int i = 0; i < s.count(); i++) {
                boolean opening = i < g.openingPlies();
                int flags = s.flags()[i];
                assertEquals(opening ? 4 : 0, flags & 4, "bit 2 marks the random opening plies");
                assertEquals(g.label() != Layer.NONE ? 2 : 0, flags & 2, "bit 1 marks classical adjudication");
                assertEquals(1, flags & 1, "bit 0: the policy target comes from the full search (no race band here)");
                assertEquals(opening ? 0f : 1f, s.policyWeight()[i], "wp = 0 for random plies");
                assertEquals(1f, s.valueWeight()[i]);
                // expansion equals Encoder.encode of the position, for either mover
                Encoder.encode(pos, planes, 0);
                for (int c = 0; c < Geometry.CELLS; c++) {
                    int cell = s.cells()[i * Geometry.CELLS + c];
                    for (int plane : new int[] {Encoder.OWN, Encoder.NEXT, Encoder.THIRD}) {
                        assertEquals(planes[Encoder.index(c, plane)], cell == 1 + plane ? 1f : 0f,
                                "game " + id + " sample " + i + " cell " + c + " plane " + plane);
                    }
                    assertEquals(planes[Encoder.index(c, Encoder.PROGRESS)], (float) Math.min(s.ply()[i], 200) / 200);
                }
                int played = s.played()[i];
                assertEquals(g.moves()[i], pos.legalMoves()[played]);
                // the value target is the blend of the label and the teacher, from the mover
                double z = g.labelValues()[pos.sideToMove()];
                double v = s.value()[2 * i];
                assertTrue(v >= -1 && v <= 1);
                assertEquals(-v, s.value()[2 * i + 1], 1e-6);
                assertTrue(Math.abs(v - z) <= DEPTH2.alpha(s.ply()[i]) * 2 + 1e-6);
                pos.make(g.moves()[i]);
            }
        }
    }

    @Test
    void noSampleCarriesAValueDerivedFromARaceBandScore() {
        // S1 off and race play on: games go on through passed positions, whose racelin leaves are in the race band
        SelfPlayGame.Settings st = settings(RACE_SPEC, DEPTH2, Adjudication.Config.OFF, 0);
        SelfPlayGame game = new SelfPlayGame(st);
        PlayerSpec spec = PlayerSpec.parse(RACE_SPEC);
        AlphaBeta oracle = new AlphaBeta(Evaluators.byName(spec.evaluator(), spec.weights()), 16, true,
                spec.options().exact());
        int raceBest = 0;
        int passed = 0;
        int blended = 0;
        for (int id = 0; id < 6; id++) {
            GameRecord g = game.play(id);
            GameRecord.Samples s = g.samples();
            Position pos = Position.initial(RuleSet.defaults(2));
            for (int i = 0; i < s.count(); i++) {
                int[] scores = oracle.rootScores(pos, 2);
                int best = Integer.MIN_VALUE;
                for (int x : scores) {
                    best = Math.max(best, x);
                }
                double z = g.labelValues()[pos.sideToMove()];
                boolean isPassed = RaceAdjudication.passed(pos);
                Scores.Kind kind = Scores.kind(best);
                if (kind == Scores.Kind.RACE || isPassed) {
                    assertEquals((float) z, s.value()[2 * i], 0f, "game " + id + " ply " + i + " best " + best);
                    assertEquals((float) -z, s.value()[2 * i + 1], 0f);
                    raceBest += kind == Scores.Kind.RACE ? 1 : 0;
                    passed += isPassed ? 1 : 0;
                } else {
                    double a = DEPTH2.alpha(pos.ply());
                    double t = 2 / (1 + Math.exp(-best / 75.0)) - 1;
                    assertEquals((1 - a) * z + a * t, s.value()[2 * i], 1e-5, "game " + id + " ply " + i);
                    blended++;
                }
                if (kind == Scores.Kind.RACE) {
                    assertEquals(0f, s.policyWeight()[i], "no policy label from race-band scores");
                    assertEquals(0, s.flags()[i] & 1);
                }
                pos.make(g.moves()[i]);
            }
        }
        assertTrue(raceBest > 0, "the games reach race-band root scores");
        assertTrue(passed > 0, "the games reach passed positions");
        assertTrue(blended > 100, "and ordinary positions are blended");
    }

    @Test
    void teacherModeDoesNotChangeTheGames() {
        SelfPlayGame plain = new SelfPlayGame(settings(SPEC, null, Adjudication.Config.DEFAULT, 0.5));
        SelfPlayGame taught = new SelfPlayGame(settings(SPEC, DEPTH2, Adjudication.Config.DEFAULT, 0.5));
        for (int id = 0; id < 4; id++) {
            GameRecord a = plain.play(id);
            GameRecord b = taught.play(id);
            assertArrayEquals(a.moves(), b.moves(), "game " + id);
            assertArrayEquals(a.scores(), b.scores());
            assertEquals(a.label(), b.label());
            assertEquals(a.nodes(), b.nodes(), "labels come from the teacher computation only");
        }
        assertTrue(taught.teacher().samples() > 100);
        assertTrue(taught.teacher().cpuNanos() > 0 && taught.teacher().nodes() > 0);
    }

    @Test
    void policyTargetsWithoutTeacherStayTheBootstrapTargets() {
        GameRecord g = new SelfPlayGame(settings(SPEC, null, Adjudication.Config.DEFAULT, 0)).play(0);
        for (int i = 0; i < g.samples().count(); i++) {
            assertEquals(0, g.samples().flags()[i] & 1, "flag bit 0 is never set without the teacher");
        }
    }

    // ---- files, command line, resume

    private static Map<String, String> metadata(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            Path f = s.filter(x -> x.toString().endsWith(".safetensors")).sorted().findFirst().orElseThrow();
            return GameShard.read(f).metadata();
        }
    }

    @Test
    void aTinyRunWritesAndReadsATeacherFileAndRecordsTheSearchOptions(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(log, true, StandardCharsets.UTF_8);
        int code = SelfPlay.run(new String[] {"run", "--out", tmp.toString(), "--spec", SPEC, "--games", "2",
                "--seed", "1010", "--shard", "2", "--control", "none", "--teacher-depth", "2", "--progress", "0"},
                new PrintStream(new ByteArrayOutputStream()), err);
        assertEquals(0, code, log.toString(StandardCharsets.UTF_8));
        Map<String, String> m = metadata(tmp);
        assertEquals("sternhalma-samples", m.get("format"));
        assertEquals("1", m.get("format_version"));
        assertTrue(m.get("teacher_search").contains("fut=0") && m.get("teacher_search").contains("lmr=0")
                && m.get("teacher_search").contains("depth=2"), m.get("teacher_search"));
        assertTrue(m.get("teacher_labels").contains("tau=0.06"), m.get("teacher_labels"));
        assertEquals("2", m.get("teacher_depth"));
        try (Stream<Path> s = Files.list(tmp)) {
            Path f = s.filter(x -> x.toString().endsWith(".safetensors")).findFirst().orElseThrow();
            SampleFile.Contents c = GameShard.read(f).contents();
            byte[] flags = c.tensor("flags").u8();
            float[] wp = c.tensor("policy_weight").f32();
            assertTrue(flags.length > 50);
            int full = 0;
            for (int i = 0; i < flags.length; i++) {
                full += flags[i] & 1;
                if ((flags[i] & 4) != 0) {
                    assertEquals(0f, wp[i], "random opening plies carry no policy weight");
                }
            }
            assertEquals(flags.length, full, "every sample of this run has a full-search policy");
            // the tensors are the ones ReplayBuffer reads
            int[] offsets = c.tensor("move_offsets").i32();
            float[] target = c.tensor("move_target").f32();
            assertEquals(flags.length + 1, offsets.length);
            assertEquals(offsets[flags.length], target.length);
            for (int i = 0; i < flags.length; i++) {
                assertEquals(1.0, sum(slice(target, offsets[i], offsets[i + 1] - offsets[i])), 1e-5);
            }
        }
        // a second call finds the shard complete
        log.reset();
        assertEquals(0, SelfPlay.run(new String[] {"run", "--out", tmp.toString(), "--spec", SPEC, "--games", "2",
                "--seed", "1010", "--shard", "2", "--control", "none", "--teacher-depth", "2", "--progress", "0"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(log, true, StandardCharsets.UTF_8)));
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("nothing more to do"), log.toString());
    }

    private static double sum(float[] a) {
        double s = 0;
        for (float x : a) {
            s += x;
        }
        return s;
    }

    @Test
    void commandLineOptionsBuildTheTeacherAndRefuseNonsense() {
        Map<String, String> opts = new java.util.HashMap<>(Map.of("spec", SPEC, "seed", "1010"));
        assertEquals(null, SelfPlay.settings(opts).teacher());
        opts.put("teacher-depth", "3");
        Teacher.Config c = SelfPlay.settings(opts).teacher();
        assertEquals(3, c.depth());
        assertEquals(0.1, c.tau());
        opts.put("teacher-depth", "2");
        assertEquals(0.06, SelfPlay.settings(opts).teacher().tau());
        opts.put("teacher-tau", "0.2");
        assertEquals(0.2, SelfPlay.settings(opts).teacher().tau());
        opts.put("teacher-tau", "-0.1");
        assertThrows(IllegalArgumentException.class, () -> SelfPlay.settings(opts));
        opts.put("teacher-tau", "0.1");
        opts.put("teacher-alpha", "0.6,0.2,80");
        Teacher.Config d = SelfPlay.settings(opts).teacher();
        assertEquals(0.6, d.alphaStart());
        assertEquals(0.2, d.alphaEnd());
        assertEquals(80, d.alphaPlies());
        opts.put("teacher-depth", "4");
        opts.remove("teacher-tau");
        assertThrows(IllegalArgumentException.class, () -> SelfPlay.settings(opts), "no default tau at depth 4");
    }

    private static List<Path> shards(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".safetensors")).sorted().toList();
        }
    }

    @Test
    void abortedTeacherRunsResumeToIdenticalTargets(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(log, true, StandardCharsets.UTF_8);
        SelfPlayGame.Settings st = settings(SPEC, DEPTH2, Adjudication.Config.DEFAULT, 0);
        Path whole = tmp.resolve("whole");
        SelfPlay.GamesJob a = new SelfPlay.GamesJob(whole, st, 8, 2, 2, err);
        a.progressSeconds = 0;
        assertEquals(0, a.run());
        Path split = tmp.resolve("split");
        SelfPlay.GamesJob first = new SelfPlay.GamesJob(split, st, 8, 2, 2, err);
        first.progressSeconds = 0;
        first.stopAfter = 3;
        assertEquals(Job.EXIT_ABORTED, first.run());
        int partial = shards(split).size();
        assertTrue(partial >= 1 && partial < 4, "aborted with " + partial + " of 4 shards");
        // a leftover of a killed run is cleaned up
        Files.writeString(split.resolve("samples-s1010-00003.safetensors.tmp.1"), "half");
        log.reset();
        SelfPlay.GamesJob second = new SelfPlay.GamesJob(split, st, 8, 2, 2, err);
        second.progressSeconds = 0;
        assertEquals(0, second.run());
        String text = log.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains(partial + " complete (skip)") && text.contains("1 cleanup"), text);
        List<Path> x = shards(whole);
        List<Path> y = shards(split);
        assertEquals(x.size(), y.size());
        for (int i = 0; i < x.size(); i++) {
            SampleFile.Contents p = GameShard.read(x.get(i)).contents();
            SampleFile.Contents q = GameShard.read(y.get(i)).contents();
            assertArrayEquals(p.tensor("move_target").f32(), q.tensor("move_target").f32(), "shard " + i);
            assertArrayEquals(p.tensor("value_target").f32(), q.tensor("value_target").f32());
            assertArrayEquals(p.tensor("flags").u8(), q.tensor("flags").u8());
        }
        // a run with another teacher setting is refused
        SelfPlayGame.Settings other = settings(SPEC, new Teacher.Config(2, 0.07, 0.7, 0.3, 100),
                Adjudication.Config.DEFAULT, 0);
        SelfPlay.GamesJob foreign = new SelfPlay.GamesJob(split, other, 8, 2, 2, err);
        foreign.progressSeconds = 0;
        assertEquals(Job.EXIT_ERROR, foreign.run());
    }

    @Test
    void theCheckToolDrawsPositionsResumesAndPrintsTheMeasures(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        String[] args = {"teacher-check", "--out", tmp.toString(), "--seed", "1010", "--spec", SPEC, "--positions", "4",
                "--depths", "2", "--ref-nodes", "2000", "--cost-games", "1", "--progress", "0"};
        assertEquals(0, SelfPlay.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(log, true, StandardCharsets.UTF_8)), log.toString(StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("policy NLL of the reference move") && text.contains("value log loss")
                && text.contains("core-hours"), text);
        // positions are replayed from the stored moves
        try (Stream<Path> s = Files.list(tmp)) {
            Path f = s.filter(x -> x.getFileName().toString().startsWith("positions-")).findFirst().orElseThrow();
            for (String line : Files.readAllLines(f)) {
                if (line.startsWith("#") || line.equals("-")) {
                    continue;
                }
                TeacherCheck.Item it = TeacherCheck.Item.parse(line);
                assertEquals(line, it.format());
                assertTrue(it.ply() >= 10 && it.ply() <= 90);
                assertEquals(it.ply(), it.position().ply());
                assertTrue(java.util.Arrays.stream(it.position().legalMoves()).anyMatch(m -> m == it.played()));
            }
        }
        // a second call finds the positions complete
        log.reset();
        assertEquals(0, SelfPlay.run(args, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(log, true, StandardCharsets.UTF_8)));
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("nothing more to do"));
        assertEquals(0.0, TeacherCheck.logLoss(1, 1), 1e-4);
        assertEquals(Math.log(2), TeacherCheck.logLoss(1, 0), 1e-12);
        assertEquals(Math.log(2), TeacherCheck.logLoss(0, 0), 1e-12, "a draw against an even prediction");
    }
}
