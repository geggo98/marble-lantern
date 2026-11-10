package sternhalma.selfplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ETA of a generation and of the whole run of the training loop (docs/training.md, "As implemented (block
 * 2h)"). A phase has a cost in seconds per unit (a game, a training step, a pair); the remaining time is the
 * remaining units of the running phase at its live rate, plus the later phases of the generation, plus the further
 * generations, each at the cost that earlier phases measured.
 *
 * <p>Only work done counts. The live rate of a phase is the rate of the items that this process did (the progress
 * lines of the jobs count nothing skipped or resumed), and the history is made of {@code phase} lines of the metrics
 * file, which a process writes with the units it did itself. So the estimate after a restart is not computed from
 * the units that an earlier process finished. The history is display only; a phase without any measurement makes the
 * estimate a lower bound (marked with {@code +}).
 */
final class Eta {

    /** The phases of a generation, in order. */
    static final List<String> PHASES = List.of("selfplay", "train", "gate", "baseline", "strength");

    /** How many of the latest observations of a phase make its cost. */
    private static final int KEEP = 3;

    private final Map<String, ArrayDeque<double[]>> observations = new HashMap<>(); // phase -> {units, seconds}

    /** A phase of this process or an earlier one: {@code units} items took {@code seconds}. */
    void observe(String phase, double units, double seconds) {
        if (!PHASES.contains(phase)) {
            throw new IllegalArgumentException("unknown phase " + phase);
        }
        if (!(units > 0) || !(seconds >= 0)) {
            return; // nothing was done: no information about the cost
        }
        ArrayDeque<double[]> q = observations.computeIfAbsent(phase, k -> new ArrayDeque<>());
        q.addLast(new double[] {units, seconds});
        while (q.size() > KEEP) {
            q.removeFirst();
        }
    }

    /** Reads the {@code phase} lines of a metrics file (a missing file or a torn line is no history). */
    static Eta fromMetrics(Path metrics) {
        Eta eta = new Eta();
        if (!Files.isRegularFile(metrics)) {
            return eta;
        }
        try {
            for (String line : Files.readAllLines(metrics, StandardCharsets.UTF_8)) {
                Map<String, String> m = RunMonitor.parse(line);
                if (m != null && "phase".equals(m.get("event")) && m.containsKey("phase") && m.containsKey("units")
                        && m.containsKey("wall_ns") && PHASES.contains(m.get("phase"))) {
                    eta.observe(m.get("phase"), Double.parseDouble(m.get("units")),
                            Double.parseDouble(m.get("wall_ns")) / 1e9);
                }
            }
        } catch (IOException | RuntimeException e) {
            // the history is display only
        }
        return eta;
    }

    /** The units of the newest observation of a phase, 0 if there is none. */
    double lastUnits(String phase) {
        ArrayDeque<double[]> q = observations.get(phase);
        return q == null || q.isEmpty() ? 0 : q.peekLast()[0];
    }

    /** Seconds per unit of a phase, NaN if it was never measured. */
    double secondsPerUnit(String phase) {
        ArrayDeque<double[]> q = observations.get(phase);
        if (q == null || q.isEmpty()) {
            return Double.NaN;
        }
        double units = 0;
        double seconds = 0;
        for (double[] o : q) {
            units += o[0];
            seconds += o[1];
        }
        return seconds / units;
    }

    /**
     * The estimate.
     *
     * @param generationSeconds seconds to the end of the generation
     * @param runSeconds        seconds to the end of the run
     * @param exact             false if a phase that is still to come has never been measured (a lower bound)
     */
    record Estimate(double generationSeconds, double runSeconds, boolean exact, boolean optionalPending) {

        Estimate(double generationSeconds, double runSeconds, boolean exact) {
            this(generationSeconds, runSeconds, exact, false);
        }

        String text(int generationsAfter) {
            String plus = exact ? "" : "+";
            return String.format(Locale.ROOT, "gen ETA %s%s, run ETA %s%s (%d more generation%s)%s",
                    Job.human(generationSeconds), plus, Job.human(runSeconds), plus, generationsAfter,
                    generationsAfter == 1 ? "" : "s", optionalPending ? PENDING_NOTE : "");
        }
    }

    /**
     * Said while the gate has not shown yet that the candidate can be promoted: only a candidate with a gate score
     * above 0.5 gets a baseline match and, as the new best network, a strength record.
     */
    static final String PENDING_NOTE = "; this generation without baseline and strength until the gate score is above 0.5";

    /**
     * @param phase            the running phase
     * @param remainingUnits   its remaining units
     * @param liveRate         units per second of this process in the running phase, 0 if none yet (then the history
     *                         cost is used)
     * @param unitsPerGeneration the units of every phase in one generation (a phase that does not occur: 0 or
     *                         missing; strength counts its share per generation)
     * @param generationsAfter generations that follow the running one
     */
    Estimate estimate(String phase, double remainingUnits, double liveRate, Map<String, Double> unitsPerGeneration,
            int generationsAfter) {
        return estimate(phase, remainingUnits, liveRate, unitsPerGeneration, unitsPerGeneration, generationsAfter, false);
    }

    /**
     * The estimate with different units for the running generation and for the generations that follow. The baseline
     * and strength phases happen only for a candidate that is promoted, which the running generation shows at its
     * gate: until then the caller leaves them out of {@code unitsThisGeneration} and sets {@code optionalPending}, and
     * the estimate is a lower bound (marked with {@code +} and said in the text). The further generations are
     * counted with all their phases, as if each candidate were promoted: for them the estimate is pessimistic, not
     * optimistic.
     *
     * @param unitsThisGeneration     the units of every phase of the running generation
     * @param unitsFurtherGenerations the units of every phase of a generation that follows
     * @param optionalPending         whether baseline and strength of the running generation are left out because the
     *                                gate has not decided yet
     */
    Estimate estimate(String phase, double remainingUnits, double liveRate, Map<String, Double> unitsThisGeneration,
            Map<String, Double> unitsFurtherGenerations, int generationsAfter, boolean optionalPending) {
        boolean exact = !optionalPending;
        double current;
        if (liveRate > 0) {
            current = remainingUnits / liveRate;
        } else if (!Double.isNaN(secondsPerUnit(phase))) {
            current = remainingUnits * secondsPerUnit(phase);
        } else {
            current = 0;
            exact = false;
        }
        double later = 0;
        double whole = 0;
        int index = PHASES.indexOf(phase);
        for (int i = 0; i < PHASES.size(); i++) {
            double thisUnits = unitsThisGeneration.getOrDefault(PHASES.get(i), 0.0);
            double furtherUnits = unitsFurtherGenerations.getOrDefault(PHASES.get(i), 0.0);
            if (thisUnits <= 0 && furtherUnits <= 0) {
                continue;
            }
            double spu = secondsPerUnit(PHASES.get(i));
            if (Double.isNaN(spu)) {
                if (i != index || liveRate <= 0) {
                    exact = false;
                }
                // the running phase of the first generation has a live rate: it prices the further generations
                spu = i == index && liveRate > 0 ? 1 / liveRate : 0;
            }
            whole += furtherUnits * spu;
            if (i > index) {
                later += thisUnits * spu;
            }
        }
        double generation = current + later;
        return new Estimate(generation, generation + generationsAfter * whole, exact, optionalPending);
    }
}
