package sternhalma.nn;

import java.util.List;

/**
 * The learning rate as a pure function of the run configuration, the generation and the step, so that a resumed
 * run uses the same rates (docs/training.md, "Learning-rate schedule"). The step is the 0-based index of the step
 * that is about to run within its generation.
 *
 * <ul>
 *   <li><b>Generation 0</b> (supervised, fixed data): linear warm-up over {@code generationZeroWarmup} steps,
 *       the rate of step s is {@code lr (s + 1) / warmup}, so that the first step already moves and the last
 *       warm-up step has the full rate; then cosine decay from lr at step {@code warmup} to
 *       {@code finalFraction * lr} at the last planned step, {@code generationZeroSteps - 1}. Later steps keep
 *       the end value. Milestones do not apply.
 *   <li><b>Self-play generations:</b> the same kind of warm-up over {@code selfPlayWarmup} steps, then constant.
 *       A milestone {@code (g, r)} replaces lr by r from generation g on. Optionally (a sweep option, off by default)
 *       a cosine decay instead of the constant: from the base rate at step {@code selfPlayWarmup} to
 *       {@code selfPlayFinalLr} at step {@code selfPlayDecaySteps - 1}, the end value after that.
 * </ul>
 *
 * @param lr                   base rate
 * @param generationZeroWarmup warm-up steps of generation 0, &gt;= 1
 * @param generationZeroSteps  planned steps of generation 0, more than {@code generationZeroWarmup + 1}
 * @param finalFraction        rate at the end of the cosine as a fraction of lr, in [0, 1]
 * @param selfPlayWarmup       warm-up steps of later generations, &gt;= 1
 * @param milestones           ascending generations &gt;= 1, each with the rate that holds from there on
 * @param selfPlayDecaySteps   steps of the cosine decay of the self-play generations (0: constant, the default), else more than
 *                             {@code selfPlayWarmup + 1}
 * @param selfPlayFinalLr      rate at the end of that decay, &gt;= 0 and finite (unused for 0 decay steps)
 */
public record LearningRateSchedule(double lr, int generationZeroWarmup, long generationZeroSteps,
        double finalFraction, int selfPlayWarmup, List<Milestone> milestones, long selfPlayDecaySteps, double selfPlayFinalLr) {

    /** From {@code generation} on the base rate is {@code lr}. */
    public record Milestone(int generation, double lr) {
        public Milestone {
            if (generation < 1) {
                throw new IllegalArgumentException("milestone generation must be >= 1, got " + generation);
            }
            if (!(lr > 0) || !Double.isFinite(lr)) {
                throw new IllegalArgumentException("milestone lr must be positive and finite, got " + lr);
            }
        }
    }

    /** The schedule of the loop: constant after the warm-up. */
    public LearningRateSchedule(double lr, int generationZeroWarmup, long generationZeroSteps, double finalFraction,
            int selfPlayWarmup, List<Milestone> milestones) {
        this(lr, generationZeroWarmup, generationZeroSteps, finalFraction, selfPlayWarmup, milestones, 0, 0);
    }

    public LearningRateSchedule {
        if (!(lr > 0) || !Double.isFinite(lr)) {
            throw new IllegalArgumentException("lr must be positive and finite, got " + lr);
        }
        if (generationZeroWarmup < 1 || selfPlayWarmup < 1) {
            throw new IllegalArgumentException("warm-up must be >= 1 step");
        }
        if (generationZeroSteps <= generationZeroWarmup + 1L) {
            throw new IllegalArgumentException("generationZeroSteps (" + generationZeroSteps
                    + ") must exceed the warm-up (" + generationZeroWarmup + ") by at least 2");
        }
        if (!(finalFraction >= 0 && finalFraction <= 1)) {
            throw new IllegalArgumentException("finalFraction must be in [0, 1], got " + finalFraction);
        }
        if (selfPlayDecaySteps < 0 || selfPlayDecaySteps > 0 && selfPlayDecaySteps <= selfPlayWarmup + 1L) {
            throw new IllegalArgumentException("selfPlayDecaySteps must be 0 or exceed the warm-up (" + selfPlayWarmup
                    + ") by at least 2, got " + selfPlayDecaySteps);
        }
        if (!(selfPlayFinalLr >= 0) || !Double.isFinite(selfPlayFinalLr)) {
            throw new IllegalArgumentException("selfPlayFinalLr must be >= 0 and finite, got " + selfPlayFinalLr);
        }
        milestones = List.copyOf(milestones);
        int previous = 0;
        for (Milestone m : milestones) {
            if (m.generation() <= previous) {
                throw new IllegalArgumentException("milestone generations must be ascending and distinct");
            }
            previous = m.generation();
        }
    }

    /** The documented defaults: warm-up 200 and 100, cosine to 0.1 lr. */
    public static LearningRateSchedule of(double lr, long generationZeroSteps, List<Milestone> milestones) {
        return new LearningRateSchedule(lr, 200, generationZeroSteps, 0.1, 100, milestones);
    }

    /** The rate of step {@code step} (0-based, within its generation) of generation {@code generation}. */
    public double rate(int generation, long step) {
        if (generation < 0 || step < 0) {
            throw new IllegalArgumentException("generation and step must be >= 0, got " + generation + ", " + step);
        }
        if (generation == 0) {
            if (step < generationZeroWarmup) {
                return lr * (step + 1) / generationZeroWarmup;
            }
            double progress = Math.min(1.0, (double) (step - generationZeroWarmup)
                    / (generationZeroSteps - 1 - generationZeroWarmup));
            return lr * (finalFraction + (1 - finalFraction) * 0.5 * (1 + StrictMath.cos(Math.PI * progress)));
        }
        double base = lr;
        for (Milestone m : milestones) {
            if (m.generation() <= generation) {
                base = m.lr();
            }
        }
        if (step < selfPlayWarmup) {
            return base * (step + 1) / selfPlayWarmup;
        }
        if (selfPlayDecaySteps == 0) {
            return base;
        }
        double progress = Math.min(1.0, (double) (step - selfPlayWarmup) / (selfPlayDecaySteps - 1 - selfPlayWarmup));
        return selfPlayFinalLr + (base - selfPlayFinalLr) * 0.5 * (1 + StrictMath.cos(Math.PI * progress));
    }
}
