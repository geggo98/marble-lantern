package sternhalma.search;

/**
 * The options of {@link Mcts} (spec keys of {@link MctsSpec} in brackets). The defaults are the ones the block-2g
 * measurements chose (docs/training.md, "As implemented (block 2g)").
 *
 * @param sims             simulations per move, at least 1 ({@code sims}); the strength control
 * @param batch            leaves evaluated in one forward pass, 1 to {@link #MAX_BATCH} ({@code batch}); with virtual
 *                         loss, so a larger batch changes the search and costs strength at a small budget (docs/training.md: 100
 *                         simulations at batch 16 play about 325 Elo below batch 1); the CPU forward pass is no faster per
 *                         position in a batch, so the default is 1 and larger batches are for a device that gains from them
 * @param cpuct            the exploration constant c of {@code Q + c * P * sqrt(N_parent) / (1 + N)} ({@code cpuct})
 * @param priorTemperature the priors are the softmax of the move logits divided by this ({@code ptemp}); 1 is the
 *                         plain softmax, a larger value flattens the priors
 * @param noise            weight of the Dirichlet noise mixed into the root priors, 0 off ({@code noise})
 * @param dirichlet        concentration of that noise ({@code alpha})
 * @param temperature      move choice: 0 the most visited move, otherwise sampling proportional to
 *                         {@code visits^(1/temperature)} ({@code temp})
 * @param fpu              first-play reduction: an unvisited edge counts as the mean value of its node minus this
 *                         ({@code fpu})
 * @param race             0 off, 1 or 2: race play by {@link RaceMove} in passed positions instead of a search, as the
 *                         {@code race} key of {@link PlayerSpec} ({@code race})
 * @param raceRows         region of the race table of {@code race}: 1 or 2 ({@code racerows})
 * @param s1               whether a passed leaf with an S1 verdict ({@link RaceAdjudication}) is an exact +-1 leaf
 *                         ({@code s1})
 * @param win1             whether a move that wins at once is recognised when its node is expanded and then played
 *                         in every simulation of that node ({@code win1})
 * @param reuse            whether the subtree of the played line is kept for the next move ({@code reuse})
 * @param millis           time budget per move in milliseconds, checked between batches; {@code Long.MAX_VALUE} none
 *                         ({@code ms}). A time budget makes the result depend on machine load; {@code sims} stays an
 *                         upper bound
 */
public record MctsOptions(int sims, int batch, double cpuct, double priorTemperature, double noise, double dirichlet,
        double temperature, double fpu, int race, int raceRows, boolean s1, boolean win1, boolean reuse,
        long millis) {

    public static final int MAX_BATCH = 64;
    public static final int DEFAULT_SIMS = 100;
    public static final int DEFAULT_BATCH = 1;
    public static final double DEFAULT_CPUCT = 1.0;
    public static final double DEFAULT_DIRICHLET = 0.3;

    public MctsOptions {
        if (sims < 1) {
            throw new IllegalArgumentException("sims must be at least 1, got " + sims);
        }
        if (batch < 1 || batch > MAX_BATCH) {
            throw new IllegalArgumentException("batch must be 1 to " + MAX_BATCH + ", got " + batch);
        }
        if (!(cpuct >= 0) || Double.isInfinite(cpuct)) {
            throw new IllegalArgumentException("cpuct must be finite and not negative, got " + cpuct);
        }
        if (!(priorTemperature > 0) || Double.isInfinite(priorTemperature)) {
            throw new IllegalArgumentException("ptemp must be positive and finite, got " + priorTemperature);
        }
        if (!(noise >= 0 && noise <= 1)) {
            throw new IllegalArgumentException("noise must be in [0, 1], got " + noise);
        }
        if (!(dirichlet > 0) || Double.isInfinite(dirichlet)) {
            throw new IllegalArgumentException("alpha must be positive and finite, got " + dirichlet);
        }
        if (!(temperature >= 0) || Double.isInfinite(temperature)) {
            throw new IllegalArgumentException("temp must be finite and not negative, got " + temperature);
        }
        if (Double.isNaN(fpu) || Double.isInfinite(fpu)) {
            throw new IllegalArgumentException("fpu must be finite, got " + fpu);
        }
        if (race < 0 || race > 2) {
            throw new IllegalArgumentException("race must be 0, 1 or 2, got " + race);
        }
        if (raceRows != 1 && raceRows != 2) {
            throw new IllegalArgumentException("racerows must be 1 or 2, got " + raceRows);
        }
        if (millis < 1) {
            throw new IllegalArgumentException("ms must be positive, got " + millis);
        }
    }

    /** The defaults: 100 simulations, batch 1, c = 1, plain softmax priors, no noise, most visited move, S1 leaves, win-in-1. */
    public static MctsOptions defaults() {
        return new MctsOptions(DEFAULT_SIMS, DEFAULT_BATCH, DEFAULT_CPUCT, 1.0, 0, DEFAULT_DIRICHLET, 0, 0, 1, 2, true,
                true, false, Long.MAX_VALUE);
    }

    public MctsOptions withSims(int newSims) {
        return new MctsOptions(newSims, batch, cpuct, priorTemperature, noise, dirichlet, temperature, fpu, race,
                raceRows, s1, win1, reuse, millis);
    }

    public MctsOptions withBatch(int newBatch) {
        return new MctsOptions(sims, newBatch, cpuct, priorTemperature, noise, dirichlet, temperature, fpu, race,
                raceRows, s1, win1, reuse, millis);
    }

    public MctsOptions withCpuct(double newCpuct) {
        return new MctsOptions(sims, batch, newCpuct, priorTemperature, noise, dirichlet, temperature, fpu, race,
                raceRows, s1, win1, reuse, millis);
    }

    public MctsOptions withNoise(double newNoise) {
        return new MctsOptions(sims, batch, cpuct, priorTemperature, newNoise, dirichlet, temperature, fpu, race,
                raceRows, s1, win1, reuse, millis);
    }

    public MctsOptions withTemperature(double newTemperature) {
        return new MctsOptions(sims, batch, cpuct, priorTemperature, noise, dirichlet, newTemperature, fpu, race,
                raceRows, s1, win1, reuse, millis);
    }

    public MctsOptions withPriorTemperature(double newTemperature) {
        return new MctsOptions(sims, batch, cpuct, newTemperature, noise, dirichlet, temperature, fpu, race,
                raceRows, s1, win1, reuse, millis);
    }

    public MctsOptions withRace(int newRace) {
        return new MctsOptions(sims, batch, cpuct, priorTemperature, noise, dirichlet, temperature, fpu, newRace,
                raceRows, s1, win1, reuse, millis);
    }

    public MctsOptions withS1(boolean newS1) {
        return new MctsOptions(sims, batch, cpuct, priorTemperature, noise, dirichlet, temperature, fpu, race,
                raceRows, newS1, win1, reuse, millis);
    }

    public MctsOptions withWin1(boolean newWin1) {
        return new MctsOptions(sims, batch, cpuct, priorTemperature, noise, dirichlet, temperature, fpu, race,
                raceRows, s1, newWin1, reuse, millis);
    }

    public MctsOptions withReuse(boolean newReuse) {
        return new MctsOptions(sims, batch, cpuct, priorTemperature, noise, dirichlet, temperature, fpu, race,
                raceRows, s1, win1, newReuse, millis);
    }

    public MctsOptions withFpu(double newFpu) {
        return new MctsOptions(sims, batch, cpuct, priorTemperature, noise, dirichlet, temperature, newFpu, race,
                raceRows, s1, win1, reuse, millis);
    }

    public MctsOptions withMillis(long newMillis) {
        return new MctsOptions(sims, batch, cpuct, priorTemperature, noise, dirichlet, temperature, fpu, race,
                raceRows, s1, win1, reuse, newMillis);
    }
}
