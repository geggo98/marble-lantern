package sternhalma.selfplay;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.TriangleRows;
import sternhalma.nn.LearningRateSchedule;
import sternhalma.search.PlayerSpec;

/**
 * What a training-loop run is, fixed in {@code run.json} and hashed into every checkpoint and candidate (docs/training.md,
 * "As implemented (block 2h)"). Everything that changes a result is here; the cadences (checkpoints, progress lines,
 * proxies), the thread counts, the strength estimate and the number of generations are not: another value gives the
 * same networks and games.
 *
 * @param initSha256       SHA-256 of the network file of generation 0
 * @param seed             run seed: seeds the self-play of every generation, the batches and the gate openings
 * @param games            self-play games per generation
 * @param shardGames       games per sample shard
 * @param sims             simulations per self-play move
 * @param mctsBatch        leaves per forward pass
 * @param cpuct            PUCT constant
 * @param noise            weight of the root Dirichlet noise
 * @param alpha            concentration of the noise
 * @param temperature      move sampling temperature in the first plies
 * @param temperaturePlies plies of the game with sampling
 * @param openingMin       random opening plies, minimum
 * @param openingMax       random opening plies, maximum
 * @param race             race play (0 off, 1, 2)
 * @param s1               S1 adjudication on
 * @param lr               learning rate of the generations (constant after the warm-up)
 * @param warmup           warm-up steps of every generation
 * @param batch            training batch
 * @param microbatch       training microbatch
 * @param reuse            R: steps = ceil(R * new training samples / batch)
 * @param window           W: samples of the replay window
 * @param augment          mirror augmentation
 * @param valueCoefficient c_v
 * @param holdout          share of the games held out for the proxies: every round(1 / holdout)-th game id
 * @param teacherSha256    fingerprint of the teacher shards in the replay buffer as generation 0, or ""
 * @param teacherFiles     their number
 * @param gatePairs        pairs of the match candidate against best network
 * @param pairShard        pairs per gate and strength shard
 * @param gateOpening      random plies of a gate opening
 * @param baselineRung     node limit of the fixed rung of the non-regression clause
 * @param baselinePairs    pairs against that rung (0: no clause)
 * @param baselineSeed     seed of those openings (the same for every network, so they are comparable)
 * @param baselineSigmas   the clause allows a drop of this many standard errors
 * @param promoteZ        the gate promotes only if {@code score - promoteZ * se > 0.5} (one-sided; 0 is the old "score > 0.5"). {@code run.json}
 *                         names it only for a value other than 0, so the hash of a run from before the option is unchanged
 * @param ladderSpec       the ladder's base spec
 * @param trainer          the trainer of the candidates: {@code cpu} (the reference trainer, the default) or {@code gpu} (the
 *                         device trainer of module nn-tornado). Part of the hash: the GPU's float sums are not those of the
 *                         CPU's double sums, so the two give different networks (each is deterministic). {@code run.json}
 *                         names it only for {@code gpu}, so the hash of every CPU run is what it was before the option
 * @param lrFinal          the rate falls with a cosine from {@code lr} to this over the planned steps of every generation (0:
 *                         constant after the warm-up, the default). {@code run.json} names it only when set, so the hash of
 *                         every run from before the option is unchanged
 * @param rows             the board size of every game of the run (self-play, gate, baseline, strength). {@code FIVE}
 *                         needs {@code race=0} and no S1 (the race table is for 10 pieces) and an initial network
 *                         whose {@code triangle_rows} lists 5. {@code run.json} names it only for {@code FIVE}, so the
 *                         hash of every 4-row run is unchanged
 */
record LoopConfig(String initSha256, long seed, int games, int shardGames, int sims, int mctsBatch, double cpuct,
        double noise, double alpha, double temperature, int temperaturePlies, int openingMin, int openingMax, int race,
        boolean s1, double lr, int warmup, int batch, int microbatch, double reuse, int window, boolean augment,
        double valueCoefficient, double holdout, String teacherSha256, int teacherFiles, int gatePairs, int pairShard,
        int gateOpening, int baselineRung, int baselinePairs, long baselineSeed, double baselineSigmas,
        String ladderSpec, String trainer, double promoteZ, double lrFinal, TriangleRows rows) {

    static final String FORMAT = "sternhalma-loop";
    /** Mixed into the held-out split, so it is not the same function as any other hash of a game id. */
    static final long SPLIT_SALT = 0x3C6EF372FE94F82BL;

    LoopConfig {
        if (games < 1 || shardGames < 1 || batch < 1 || microbatch < 1 || window < 1 || gatePairs < 2 || pairShard < 1
                || baselinePairs < 0 || baselinePairs == 1 || gateOpening < 0 || warmup < 1) {
            throw new IllegalArgumentException("a count option is out of range");
        }
        if (!(reuse > 0) || !(lr > 0) || !(holdout > 0 && holdout < 1) || !(baselineSigmas >= 0) || !(promoteZ >= 0)
                || !(lrFinal >= 0 && lrFinal <= lr)) {
            throw new IllegalArgumentException(
                    "reuse and lr must be positive, holdout in (0, 1), sigmas >= 0, promoteZ >= 0, 0 <= lrFinal <= lr");
        }
        StrengthEstimate.requireDistinctRungs(List.of(baselineRung));
        if (!trainer.equals("cpu") && !trainer.equals("gpu")) {
            throw new IllegalArgumentException("--trainer must be cpu or gpu, got " + trainer);
        }
        java.util.Objects.requireNonNull(rows, "rows");
        if (rows == TriangleRows.FIVE && (race != 0 || s1)) {
            throw new IllegalArgumentException("--rows 5 needs --race 0 and --s1 0: race play and S1 use the race table"
                    + " of 10 pieces");
        }
        if (PlayerSpec.parse(ladderSpec).rows() != rows) {
            throw new IllegalArgumentException("the ladder spec plays rows=" + PlayerSpec.parse(ladderSpec).rows().rows()
                    + ", the run rows=" + rows.rows() + ": " + ladderSpec);
        }
        new LoopGame.Settings(0, sims, mctsBatch, cpuct, noise, alpha, temperature, temperaturePlies, openingMin,
                openingMax, race, adjudication(), rows); // validates
    }

    /** A 4-row run (every run before the 5-row board). */
    LoopConfig(String initSha256, long seed, int games, int shardGames, int sims, int mctsBatch, double cpuct,
            double noise, double alpha, double temperature, int temperaturePlies, int openingMin, int openingMax, int race,
            boolean s1, double lr, int warmup, int batch, int microbatch, double reuse, int window, boolean augment,
            double valueCoefficient, double holdout, String teacherSha256, int teacherFiles, int gatePairs, int pairShard,
            int gateOpening, int baselineRung, int baselinePairs, long baselineSeed, double baselineSigmas,
            String ladderSpec, String trainer, double promoteZ, double lrFinal) {
        this(initSha256, seed, games, shardGames, sims, mctsBatch, cpuct, noise, alpha, temperature, temperaturePlies,
                openingMin, openingMax, race, s1, lr, warmup, batch, microbatch, reuse, window, augment, valueCoefficient,
                holdout, teacherSha256, teacherFiles, gatePairs, pairShard, gateOpening, baselineRung, baselinePairs,
                baselineSeed, baselineSigmas, ladderSpec, trainer, promoteZ, lrFinal, TriangleRows.FOUR);
    }

    /** The rules of every game of the run: the two-player defaults of the board size (move limit 200 or 250). */
    RuleSet rules() {
        return RuleSet.defaults(2, rows);
    }

    Adjudication.Config adjudication() {
        return s1 ? Adjudication.Config.PRODUCTION : Adjudication.Config.OFF;
    }

    /** The seed of the self-play of generation {@code generation}. */
    long selfPlaySeed(int generation) {
        return seed * 1_000_003L + generation;
    }

    /** The seed of the gate openings of generation {@code generation}. */
    long gateSeed(int generation) {
        return seed * 7_919L + 104_729L * generation + 1;
    }

    LoopGame.Settings settings(int generation) {
        return new LoopGame.Settings(selfPlaySeed(generation), sims, mctsBatch, cpuct, noise, alpha, temperature,
                temperaturePlies, openingMin, openingMax, race, adjudication(), rows);
    }

    LearningRateSchedule schedule() {
        // generation 0 is not trained here: its warm-up and length only have to be valid
        return new LearningRateSchedule(lr, 100, 1000, 0.1, warmup, List.of());
    }

    /**
     * The schedule of a generation of {@code plannedSteps} steps: constant after the warm-up, or with {@code lrFinal} set a
     * cosine from {@code lr} to {@code lrFinal} over all the planned steps (a generation too short for it stays constant).
     */
    LearningRateSchedule schedule(long plannedSteps) {
        if (lrFinal == 0 || plannedSteps <= warmup + 1L) {
            return schedule();
        }
        return new LearningRateSchedule(lr, 100, 1000, 0.1, warmup, List.of(), plannedSteps, lrFinal);
    }

    /** The player text of an evaluation player (gate, baseline, strength): no noise, the most visited move. */
    String evalPlayer() {
        return "mcts:sims=" + sims + ",batch=" + mctsBatch + ",cpuct=" + cpuct + ",noise=0,temp=0,race=" + race;
    }

    /** Planned steps of a generation with {@code newSamples} new training samples. */
    long plannedSteps(long newSamples) {
        return Math.max(1, (long) Math.ceil(reuse * newSamples / batch));
    }

    String json() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("format", FORMAT);
        f.put("version", 1);
        f.put("players", 2);
        f.put("init_sha256", initSha256);
        f.put("seed", seed);
        f.put("games", games);
        f.put("shard_games", shardGames);
        f.put("sims", sims);
        f.put("mcts_batch", mctsBatch);
        f.put("cpuct", cpuct);
        f.put("noise", noise);
        f.put("alpha", alpha);
        f.put("temperature", temperature);
        f.put("temperature_plies", temperaturePlies);
        f.put("opening_min", openingMin);
        f.put("opening_max", openingMax);
        f.put("race", race);
        f.put("s1", s1);
        f.put("optimizer", "adamw");
        f.put("lr", lr);
        f.put("warmup", warmup);
        f.put("batch", batch);
        f.put("microbatch", microbatch);
        f.put("reuse", reuse);
        f.put("window", window);
        f.put("augment", augment);
        f.put("value_coefficient", valueCoefficient);
        f.put("holdout", holdout);
        f.put("holdout_salt", SPLIT_SALT);
        f.put("teacher_sha256", teacherSha256);
        f.put("teacher_files", teacherFiles);
        f.put("gate_pairs", gatePairs);
        f.put("pair_shard", pairShard);
        f.put("gate_opening", gateOpening);
        f.put("baseline_rung", baselineRung);
        f.put("baseline_pairs", baselinePairs);
        f.put("baseline_seed", baselineSeed);
        f.put("baseline_sigmas", baselineSigmas);
        f.put("ladder_spec", ladderSpec);
        if (trainer.equals("gpu")) {
            f.put("trainer", trainer);
        }
        if (promoteZ != 0) {
            f.put("promote_z", promoteZ);
        }
        if (lrFinal != 0) {
            f.put("lr_final", lrFinal);
        }
        if (rows != TriangleRows.FOUR) {
            f.put("rows", rows.rows());
        }
        return RunMonitor.json(f);
    }

    String sha256() {
        return Gen0.sha256Hex(json().getBytes(StandardCharsets.UTF_8));
    }

    /** The configuration that {@link #json()} wrote (a parsed {@code run.json}). */
    static LoopConfig fromJson(Map<String, String> m) {
        if (m == null || !FORMAT.equals(m.get("format")) || !"1".equals(m.get("version"))) {
            throw new IllegalArgumentException("not a " + FORMAT + " version 1 configuration");
        }
        return new LoopConfig(m.get("init_sha256"), Long.parseLong(m.get("seed")), Integer.parseInt(m.get("games")),
                Integer.parseInt(m.get("shard_games")), Integer.parseInt(m.get("sims")),
                Integer.parseInt(m.get("mcts_batch")), Double.parseDouble(m.get("cpuct")),
                Double.parseDouble(m.get("noise")), Double.parseDouble(m.get("alpha")),
                Double.parseDouble(m.get("temperature")), Integer.parseInt(m.get("temperature_plies")),
                Integer.parseInt(m.get("opening_min")), Integer.parseInt(m.get("opening_max")),
                Integer.parseInt(m.get("race")), Boolean.parseBoolean(m.get("s1")), Double.parseDouble(m.get("lr")),
                Integer.parseInt(m.get("warmup")), Integer.parseInt(m.get("batch")), Integer.parseInt(m.get("microbatch")),
                Double.parseDouble(m.get("reuse")), Integer.parseInt(m.get("window")),
                Boolean.parseBoolean(m.get("augment")), Double.parseDouble(m.get("value_coefficient")),
                Double.parseDouble(m.get("holdout")), m.get("teacher_sha256"), Integer.parseInt(m.get("teacher_files")),
                Integer.parseInt(m.get("gate_pairs")), Integer.parseInt(m.get("pair_shard")),
                Integer.parseInt(m.get("gate_opening")), Integer.parseInt(m.get("baseline_rung")),
                Integer.parseInt(m.get("baseline_pairs")), Long.parseLong(m.get("baseline_seed")),
                Double.parseDouble(m.get("baseline_sigmas")), m.get("ladder_spec"), m.getOrDefault("trainer", "cpu"),
                m.containsKey("promote_z") ? Double.parseDouble(m.get("promote_z")) : 0,
                m.containsKey("lr_final") ? Double.parseDouble(m.get("lr_final")) : 0,
                SelfPlay.rows(m.getOrDefault("rows", "4")));
    }

    /**
     * The defaults of the options that the config takes; {@code lr} 3e-4 (docs/training.md, block 2h). With
     * {@code --rows 5} the defaults of {@code --race} and {@code --s1} are 0 and the ladder spec is
     * {@code BROWSER_ROWS5_SPEC} ({@code BEST_SPEC} uses race play, which the engine refuses on 5 rows).
     */
    static LoopConfig of(Map<String, String> o, String initSha256, String teacherSha256, int teacherFiles) {
        TriangleRows rows = SelfPlay.rows(o.getOrDefault("rows", "4"));
        boolean four = rows == TriangleRows.FOUR;
        return new LoopConfig(initSha256, lng(o, "seed", 1), integer(o, "games", 600), integer(o, "shard-games", 25),
                integer(o, "sims", 100), integer(o, "mcts-batch", 1), dbl(o, "cpuct", 1.0), dbl(o, "noise", 0.25),
                dbl(o, "alpha", 0.3), dbl(o, "temp", 1.0), integer(o, "temp-plies", 14), integer(o, "opening-min", 4),
                integer(o, "opening-max", 10), integer(o, "race", four ? 1 : 0), flag(o, "s1", four),
                dbl(o, "lr", 3e-4),
                integer(o, "warmup", 100), integer(o, "batch", 256), integer(o, "microbatch", 32), dbl(o, "reuse", 4),
                integer(o, "window", 500_000), flag(o, "augment", true), dbl(o, "value-coefficient", 1.0),
                dbl(o, "holdout", 0.1), teacherSha256, teacherFiles, integer(o, "gate-pairs", 100),
                integer(o, "pair-shard", 10), integer(o, "gate-opening", 4), integer(o, "baseline-rung", 1600),
                integer(o, "baseline-pairs", 50), lng(o, "baseline-seed", 4001), dbl(o, "baseline-sigmas", 2.0),
                o.getOrDefault("ladder-spec", StrengthEstimate.defaultBase(sternhalma.engine.RuleSet.JumpRule.ADJACENT,
                        rows)), o.getOrDefault("trainer", "cpu"), dbl(o, "promote-z", 1.28), dbl(o, "lr-final", 0), rows);
    }

    static long lng(Map<String, String> o, String key, long def) {
        return o.containsKey(key) ? Long.parseLong(o.get(key)) : def;
    }

    static int integer(Map<String, String> o, String key, int def) {
        return o.containsKey(key) ? Integer.parseInt(o.get(key)) : def;
    }

    static double dbl(Map<String, String> o, String key, double def) {
        return o.containsKey(key) ? Double.parseDouble(o.get(key)) : def;
    }

    static boolean flag(Map<String, String> o, String key, boolean def) {
        String v = o.get(key);
        if (v == null) {
            return def;
        }
        return switch (v) {
            case "1", "on", "true" -> true;
            case "0", "off", "false" -> false;
            default -> throw new IllegalArgumentException("--" + key + " must be 1 or 0, got " + v);
        };
    }
}
