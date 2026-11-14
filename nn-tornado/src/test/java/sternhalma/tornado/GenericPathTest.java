package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.ReferenceTrainer;
import sternhalma.nn.TrainingBatch;
import sternhalma.tornado.KernelsHexConvLayer.Variant;
import sternhalma.tornado.TrainTesting.Worst;
import uk.ac.manchester.tornado.api.common.TornadoDevice;

/**
 * The backend-independent kernels (docs/tornado.md, "Backends"): the generic {@code KernelContext} weight
 * gradient {@link KernelsConvBackward#weightPartialTiled} and the TILED convolution run on the device that is
 * there, forced, and are compared with the CPU reference trainer with the tolerances of docs/training.md,
 * "Verification against Part A". On Metal this runs the path that CUDA and OpenCL devices take, next to the
 * SIMD-group path; on another backend the SIMD-group variant is skipped, because it cannot run there.
 */
class GenericPathTest {

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    @Test
    void backendNamesParseAndPtxIsCuda() {
        assertEquals(DeviceBackend.METAL, DeviceBackend.parse("Metal"));
        assertEquals(DeviceBackend.CUDA, DeviceBackend.parse("cuda"));
        assertEquals(DeviceBackend.CUDA, DeviceBackend.parse(" PTX "));
        assertEquals(DeviceBackend.OPENCL, DeviceBackend.parse("opencl"));
        assertThrows(IllegalArgumentException.class, () -> DeviceBackend.parse("vulkan"));
        assertTrue(DeviceBackend.METAL.hasSimdgroupMatrix());
        assertFalse(DeviceBackend.CUDA.hasSimdgroupMatrix());
        assertFalse(DeviceBackend.OPENCL.hasSimdgroupMatrix());
    }

    @Test
    void theSelectedDeviceIsWithinTheKernelLimits() {
        TornadoDevice device = TornadoSupport.defaultDevice();
        DeviceBackend.requireLimits(device);
    }

    @Test
    void forcingTheGenericPathReplacesSimdgroupAndNothingElse() {
        TornadoDevice device = TornadoSupport.defaultDevice();
        String old = System.getProperty(DeviceBackend.FORCE_GENERIC_PROPERTY);
        try {
            System.setProperty(DeviceBackend.FORCE_GENERIC_PROPERTY, "true");
            assertEquals(Variant.NAIVE, TornadoNetwork.variantFor(4, 32, device));
            assertEquals(Variant.TILED, TornadoNetwork.variantFor(8, 32, device));
            assertEquals(Variant.TILED, TornadoNetwork.variantFor(1, 1024, device));
            System.clearProperty(DeviceBackend.FORCE_GENERIC_PROPERTY);
            Variant large = TornadoNetwork.variantFor(8, 32, device);
            assertEquals(DeviceBackend.of(device).hasSimdgroupMatrix() ? Variant.SIMDGROUP : Variant.TILED, large);
        } finally {
            if (old == null) {
                System.clearProperty(DeviceBackend.FORCE_GENERIC_PROPERTY);
            } else {
                System.setProperty(DeviceBackend.FORCE_GENERIC_PROPERTY, old);
            }
        }
    }

    @Test
    void aForcedSimdgroupVariantNeverBecomesAnotherOneSilently() {
        TornadoDevice device = TornadoSupport.defaultDevice();
        if (DeviceBackend.of(device).hasSimdgroupMatrix()) {
            DeviceBackend.requireSimdgroup(device);
            return;
        }
        assertThrows(IllegalStateException.class, () -> DeviceBackend.requireSimdgroup(device));
        NetworkConfig config = NetworkConfig.tiny(2);
        assertThrows(IllegalStateException.class, () -> new TornadoNetwork(Parameters.init(config, 1), device, 4,
                Variant.SIMDGROUP, false));
    }

    /**
     * The convolution weight and bias gradients of the generic kernel (and of the NAIVE and, where it exists, the
     * SIMD-group kernel) against the reference trainer: shapes with odd channel counts and a batch whose 122-row
     * activations fill no 32-row tile evenly, and a batch above the 64 samples of one split-K chunk.
     */
    @Test
    void convolutionGradientsOfEveryVariantMatchTheReference() {
        NetworkConfig odd = new NetworkConfig(5, 2, 3, 7, PlaneSet.CLASSIC1);
        NetworkConfig small = NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1);
        NetworkConfig tiny = NetworkConfig.tiny(2);
        Object[][] cases = {
                {"odd-classic1", odd, TrainTesting.classicPrior(Parameters.uniform(odd, 18, TrainTesting.initBound(odd))),
                        TrainTesting.batch(3, PlaneSet.CLASSIC1, 19, 10), 1.25},
                {"small2-classic1", small,
                        TrainTesting.classicPrior(Parameters.uniform(small, 15, TrainTesting.initBound(small))),
                        TrainTesting.batch(2, PlaneSet.CLASSIC1, 16, 7), 1.0},
                {"tiny2-plain-130", tiny, Parameters.uniform(tiny, 20, TrainTesting.initBound(tiny)),
                        TrainTesting.batch(2, PlaneSet.PLAIN, 130, 22), 1.0}};
        TornadoDevice device = TornadoSupport.defaultDevice();
        List<Variant> variants = new ArrayList<>(List.of(Variant.NAIVE, Variant.TILED));
        if (DeviceBackend.of(device).hasSimdgroupMatrix()) {
            variants.add(Variant.SIMDGROUP);
        }
        for (Object[] c : cases) {
            String name = (String) c[0];
            Parameters parameters = (Parameters) c[2];
            TrainingBatch batch = (TrainingBatch) c[3];
            double cv = (Double) c[4];
            ReferenceTrainer reference = new ReferenceTrainer(parameters, cv);
            reference.accumulate(batch, batch.size(), 1);
            for (Variant variant : variants) {
                try (TornadoTrainer trainer = new TornadoTrainer(parameters, batch.size(), batch.moveCount() + 7, cv,
                        device, variant, false, TornadoTrainer.ALL_STAGES)) {
                    trainer.run(batch);
                    double worstOverall = 0;
                    for (String tensor : TornadoTrainerTest.convolutionTensors((NetworkConfig) c[1])) {
                        double[] ref = reference.gradients().get(tensor);
                        float[] gpu = trainer.gradient(tensor);
                        assertEquals(ref.length, gpu.length, tensor);
                        double scale = TrainTesting.maxAbs(ref);
                        assertTrue(scale > 0, name + ": the reference gradient of " + tensor + " is 0");
                        Worst worst = new Worst(tensor);
                        TrainTesting.gradientAgrees(worst, name + " " + variant, ref, 0, gpu, 0, ref.length, scale);
                        worstOverall = Math.max(worstOverall, worst.fraction);
                    }
                    System.out.printf("GenericPath %s %s on %s: convolution gradients, largest error / bound %.4f%n",
                            name, variant, TornadoSupport.status().backend(), worstOverall);
                }
            }
        }
    }
}
