package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import sternhalma.engine.Position;

/**
 * Tests of {@link Optimizer} (docs/training.md, "Optimiser", "Tests of Part A", "Optimiser and training").
 *
 * <p>The arithmetic tests use the one-channel config {@code (1, 0, 2, 1)} and four elements: a weight and a bias
 * that have gradients, and a weight and a bias that have gradient 0 and a non-zero value. The expected numbers were
 * computed once, outside the code, by a double-precision script that rounds m, v, u and theta to float after each
 * step, exactly as the documented formulas say.
 */
class OptimizerTest {

    private static final NetworkConfig ONE = new NetworkConfig(1, 0, 2, 1);
    private static final double LR = 0.1;
    private static final double EPS = 2e-7; // two float ulps near 1

    private static final String W = "head_policy.weight";
    private static final String B = "head_policy.bias";

    /** All parameters 0 except: W[0] = 1, W[1] = 2, B[0] = 1, B[1] = 2. */
    private static Parameters hand() {
        Parameters p = Parameters.uniform(ONE, 1, 1f);
        for (var t : p.tensors().values()) {
            Arrays.fill(t.data(), 0f);
        }
        p.get(W).data()[0] = 1f;
        p.get(W).data()[1] = 2f;
        p.get(B).data()[0] = 1f;
        p.get(B).data()[1] = 2f;
        return p;
    }

    /** Gradient g on W[0] and B[0]; 0 on W[1] and B[1] and everywhere else. */
    private static ReferenceTrainer.Gradients handGradients(double g) {
        ReferenceTrainer.Gradients gradients = new ReferenceTrainer.Gradients(ONE);
        gradients.get(W)[0] = g;
        gradients.get(B)[0] = g;
        return gradients;
    }

    private static Optimizer.Settings adamw() {
        return new Optimizer.Settings(Optimizer.Kind.ADAMW, 0.9, 0.999, 1e-8, 0.1, 0.9, 0);
    }

    private static Optimizer.Settings sgd() {
        return new Optimizer.Settings(Optimizer.Kind.SGD, 0.9, 0.999, 1e-8, 0.01, 0.9, 0.01);
    }

    // ------------------------------------------------------------------ arithmetic

    /**
     * AdamW, two steps (gradients 0.5, then -0.25), against hand-computed values. Catches: bias correction from
     * t = 0 (the first step would divide by 0), no bias correction, decay on biases (B[0] would differ, B[1] would
     * move), decay that does not scale with lr, no decay on weights with gradient 0, m and v mixed up.
     */
    @Test
    void adamwTwoStepsEqualHandComputedValues() {
        Parameters p = hand();
        Optimizer o = new Optimizer(p, adamw());
        o.step(handGradients(0.5), LR);
        assertEquals(0.8899999856948853, p.get(W).data()[0], EPS, "W[0] after step 1");
        assertEquals(1.9800000190734863, p.get(W).data()[1], EPS, "W[1] after step 1");
        assertEquals(0.8999999761581421, p.get(B).data()[0], EPS, "B[0] after step 1");
        assertEquals(2.0f, p.get(B).data()[1], "B[1] has no gradient and is a bias: unchanged");
        o.step(handGradients(-0.25), LR);
        assertEquals(0.8544662594795227, p.get(W).data()[0], EPS, "W[0] after step 2");
        assertEquals(1.9602000713348389, p.get(W).data()[1], EPS, "W[1] after step 2");
        assertEquals(0.8733662962913513, p.get(B).data()[0], EPS, "B[0] after step 2");
        assertEquals(2.0f, p.get(B).data()[1]);
    }

    @Test
    void adamwStateHoldsTheMomentsAfterEachStep() {
        Parameters p = hand();
        Optimizer o = new Optimizer(p, adamw());
        o.step(handGradients(0.5), LR);
        assertEquals(0.05f, o.m().get(W)[0], 1e-9f);
        assertEquals(0.00025f, o.v().get(W)[0], 1e-9f);
        assertEquals(0f, o.m().get(W)[1]);
        assertEquals(0f, o.v().get(W)[1]);
        assertEquals(1, o.steps());
        o.step(handGradients(-0.25), LR);
        assertEquals(0.9 * 0.05 + 0.1 * -0.25, o.m().get(W)[0], 1e-8);
        assertEquals(0.999 * 0.00025 + 0.001 * 0.0625, o.v().get(W)[0], 1e-9);
        assertEquals(2, o.steps());
    }

    /**
     * SGD with momentum and L2, two steps. Catches: momentum applied before adding the gradient (u = mu (u + g)),
     * L2 on biases, L2 added to the update instead of to g, a velocity that is reset each step, theta - u instead
     * of theta - lr u.
     */
    @Test
    void sgdTwoStepsEqualHandComputedValues() {
        Parameters p = hand();
        Optimizer o = new Optimizer(p, sgd());
        o.step(handGradients(0.5), LR);
        assertEquals(0.9490000009536743, p.get(W).data()[0], EPS, "W[0] after step 1");
        assertEquals(1.9980000257492065, p.get(W).data()[1], EPS, "W[1]: only L2 acts on it");
        assertEquals(0.949999988079071, p.get(B).data()[0], EPS, "B[0] after step 1");
        assertEquals(2.0f, p.get(B).data()[1], "a bias with gradient 0 gets no L2");
        o.step(handGradients(-0.25), LR);
        assertEquals(0.9271510243415833, p.get(W).data()[0], EPS, "W[0] after step 2");
        assertEquals(1.9942020177841187, p.get(W).data()[1], EPS, "W[1] after step 2");
        assertEquals(0.9300000071525574, p.get(B).data()[0], EPS, "B[0] after step 2");
        assertEquals(2.0f, p.get(B).data()[1]);
        assertEquals(0.21848998963832855, o.u().get(W)[0], 1e-7, "velocity of W[0] after step 2");
        assertEquals(0.20000000298023224, o.u().get(B)[0], 1e-7, "velocity of B[0] after step 2");
    }

    /**
     * eps is added to the root: with eps = 0.5, g = 0.5 and step 1, the update is lr * 0.5 / (0.5 + 0.5) = 0.5 lr.
     * Under the root it would be lr * 0.5 / sqrt(0.25 + 0.5) = 0.577 lr. (With the default eps = 1e-8 the two are
     * the same to float precision, so the hand-computed test cannot tell them apart.)
     */
    @Test
    void epsilonIsAddedToTheRootNotUnderIt() {
        Parameters p = hand();
        Optimizer o = new Optimizer(p, new Optimizer.Settings(Optimizer.Kind.ADAMW, 0.9, 0.999, 0.5, 0, 0.9, 0));
        o.step(handGradients(0.5), 1.0);
        assertEquals(1.0 - 0.5, p.get(B).data()[0], 1e-6);
    }

    /** The AdamW decay of a weight with gradient 0 is exactly the factor 1 - lr wd, as docs/training.md demands. */
    @Test
    void adamwShrinksAWeightWithoutGradientByExactlyOneMinusLrWd() {
        Parameters p = hand();
        Optimizer o = new Optimizer(p, new Optimizer.Settings(Optimizer.Kind.ADAMW, 0.9, 0.999, 1e-8, 0.25, 0.9, 0));
        o.step(new ReferenceTrainer.Gradients(ONE), 0.5);
        assertEquals(2.0f * (1 - 0.5f * 0.25f), p.get(W).data()[1], 0.0);
        assertEquals(2.0f, p.get(B).data()[1], 0.0);
        o.step(new ReferenceTrainer.Gradients(ONE), 0.5);
        assertEquals(2.0f * 0.875f * 0.875f, p.get(W).data()[1], 0.0);
    }

    @Test
    void theOptimiserLeavesTheGradientsAlone() {
        Parameters p = hand();
        ReferenceTrainer.Gradients g = handGradients(0.5);
        new Optimizer(p, adamw()).step(g, LR);
        assertEquals(0.5, g.get(W)[0], 0.0);
        assertEquals(0.5, g.get(B)[0], 0.0);
    }

    // ------------------------------------------------------------------ guards

    /** A NaN or infinite gradient must stop the run, whatever the optimiser. */
    @Test
    void aNonFiniteGradientRaisesInsteadOfPoisoningTheParameters() {
        for (Optimizer.Settings settings : List.of(adamw(), sgd())) {
            for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300}) {
                Optimizer o = new Optimizer(hand(), settings);
                ReferenceTrainer.Gradients g = handGradients(0.5);
                g.get(W)[0] = bad;
                assertThrows(IllegalStateException.class, () -> o.step(g, LR), settings.kind() + " " + bad);
            }
        }
    }

    @Test
    void rejectsBadSettingsAndRates() {
        assertThrows(IllegalArgumentException.class,
                () -> new Optimizer.Settings(Optimizer.Kind.ADAMW, 1.0, 0.999, 1e-8, 0.01, 0.9, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Optimizer.Settings(Optimizer.Kind.ADAMW, 0.9, -0.1, 1e-8, 0.01, 0.9, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Optimizer.Settings(Optimizer.Kind.ADAMW, 0.9, 0.999, 0, 0.01, 0.9, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Optimizer.Settings(Optimizer.Kind.SGD, 0.9, 0.999, 1e-8, -1, 0.9, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Optimizer.Settings(Optimizer.Kind.SGD, 0.9, 0.999, 1e-8, 0.01, 1.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Optimizer.Settings(Optimizer.Kind.SGD, 0.9, 0.999, 1e-8, 0.01, 0.9, Double.NaN));
        Optimizer o = new Optimizer(hand(), adamw());
        ReferenceTrainer.Gradients zero = new ReferenceTrainer.Gradients(ONE);
        assertThrows(IllegalArgumentException.class, () -> o.step(zero, -1e-3));
        assertThrows(IllegalArgumentException.class, () -> o.step(zero, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> o.step(zero, Double.POSITIVE_INFINITY));
        assertEquals(0, o.steps(), "a rejected step does not count");
    }

    @Test
    void defaultsAreTheDocumentedOnes() {
        Optimizer.Settings a = Optimizer.Settings.adamw();
        assertEquals(Optimizer.Kind.ADAMW, a.kind());
        assertEquals(0.9, a.beta1(), 0);
        assertEquals(0.999, a.beta2(), 0);
        assertEquals(1e-8, a.eps(), 0);
        assertEquals(0.01, a.weightDecay(), 0);
        assertEquals(0.0, a.l2(), 0);
        Optimizer.Settings s = Optimizer.Settings.sgd();
        assertEquals(Optimizer.Kind.SGD, s.kind());
        assertEquals(0.9, s.momentum(), 0);
        assertEquals(1e-4, s.l2(), 0);
    }

    @Test
    void stateAccessorsMatchTheKind() {
        Optimizer a = new Optimizer(hand(), adamw());
        assertThrows(IllegalStateException.class, a::u);
        Optimizer s = new Optimizer(hand(), sgd());
        assertThrows(IllegalStateException.class, s::m);
        assertThrows(IllegalStateException.class, s::v);
        assertEquals(List.copyOf(Parameters.shapes(ONE).keySet()), List.copyOf(a.m().keySet()));
        assertTrue(a.m().values().stream().allMatch(x -> x.length > 0));
        for (float x : a.m().get("conv_in.weight")) {
            assertEquals(0f, x);
        }
    }

    @Test
    void restoreRejectsStateOfTheWrongShapeOrKind() {
        Optimizer a = new Optimizer(hand(), adamw());
        Parameters p = hand();
        assertThrows(IllegalArgumentException.class, () -> Optimizer.restore(p, adamw(), 3, a.m(), null, null),
                "AdamW needs v");
        assertThrows(IllegalArgumentException.class, () -> Optimizer.restore(p, adamw(), 3, a.m(), a.v(), a.m()),
                "AdamW has no u");
        assertThrows(IllegalArgumentException.class, () -> Optimizer.restore(p, adamw(), -1, a.m(), a.v(), null));
        LinkedHashMap<String, float[]> shortM = new LinkedHashMap<>(a.m());
        shortM.put(W, new float[1]);
        assertThrows(IllegalArgumentException.class, () -> Optimizer.restore(p, adamw(), 3, shortM, a.v(), null));
        Optimizer r = Optimizer.restore(p, adamw(), 3, a.m(), a.v(), null);
        assertEquals(3, r.steps());
        Optimizer s = new Optimizer(hand(), sgd());
        assertThrows(IllegalArgumentException.class, () -> Optimizer.restore(p, sgd(), 3, null, null, null));
        assertArrayEquals(s.u().get(W), Optimizer.restore(p, sgd(), 1, null, null, s.u()).u().get(W));
    }

    // ------------------------------------------------------------------ first-order prediction

    /**
     * One SGD step lowers the loss by the predicted amount (docs/training.md, "Optimiser and training"): on a fixed
     * batch of 16 encoded positions, lr is chosen so that lr |g|^2 is 1e-3 of the loss; the loss after the step must
     * be lower, and the decrease must be within 10 % of lr |g|^2. A gradient with the wrong sign fails the first
     * check, a gradient with a wrong factor (2 g) the second. |g|^2 is taken over the float-rounded gradient, as the
     * optimiser reads it.
     */
    @Test
    void oneSgdStepLowersTheLossByTheFirstOrderPrediction(TestReporter reporter) {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters p = Parameters.init(config, 11);
        List<Position> positions = TestBatches.pick(Playouts.positions(2, 6, 60, 31), 16);
        TrainingBatch batch = TestBatches.fromPositions(2, positions, 5);
        ReferenceTrainer trainer = new ReferenceTrainer(p, 1.0);
        ReferenceTrainer.Result before = trainer.accumulate(batch, 4, 1);
        double g2 = 0;
        for (double[] g : trainer.gradients().tensors().values()) {
            for (double x : g) {
                float f = (float) x;
                g2 += (double) f * f;
            }
        }
        double lr = 1e-3 * before.loss() / g2;
        new Optimizer(p, new Optimizer.Settings(Optimizer.Kind.SGD, 0.9, 0.999, 1e-8, 0.01, 0.9, 0)).step(
                trainer.gradients(), lr);
        trainer.zeroGradients();
        ReferenceTrainer.Result after = trainer.accumulate(batch, 4, 1);
        double decrease = before.loss() - after.loss();
        double predicted = lr * g2;
        reporter.publishEntry("first-order", String.format(Locale.ROOT,
                "loss %.6f -> %.6f, decrease %.3e, predicted %.3e, ratio %.4f", before.loss(), after.loss(),
                decrease, predicted, decrease / predicted));
        assertTrue(decrease > 0, "the loss must go down");
        assertEquals(1.0, decrease / predicted, 0.10);
    }
}
