package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.selfplay.MixedTrainingTest.Result;
import sternhalma.tornado.GpuTrainers;

/** The mixed-variant training of {@link MixedTrainingTest} with the Tornado trainer (task gpuTest; needs the SDK and a device). */
class MixedTrainingGpuTest {

    @TempDir
    Path dir;

    @BeforeEach
    void requireDevice() {
        String why = GpuTrainers.unavailableReason();
        if (Boolean.getBoolean("sternhalma.tornado.flags") && why != null && !why.contains("no device")) {
            throw new AssertionError("the build passed the TornadoVM flags, but the device trainer is not usable: " + why);
        }
        assumeTrue(why == null, "no usable device: " + why);
    }

    @Test
    void aTinySharedNetOverfitsAMixedFourVariantDatasetOnTheTornadoTrainer() throws Exception {
        MixedTrainingTest.overfit(dir, true);
    }

    @Test
    void gen0WithTheGpuTrainerTrainsOnMixedDataAndWritesTheSameKindOfRun() throws Exception {
        Path data = MixedTrainingTest.dataDir(dir, "gpu-data");
        Path run = dir.resolve("gpu-run");
        Result r = MixedTrainingTest.gen0(MixedTrainingTest.run(MixedTrainingTest.options(data, run, "--trainer",
                "gpu")));
        assertFalse(r.err().contains("training on the CPU instead"), r.err());
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.readString(run.resolve("run.json")).contains("\"trainer\":\"gpu\""));
        assertTrue(Files.readString(run.resolve("metrics.jsonl")).contains("\"variant\":\"3p-long\""));
        assertFalse(r.err().contains("Exception"), r.err());
    }
}
