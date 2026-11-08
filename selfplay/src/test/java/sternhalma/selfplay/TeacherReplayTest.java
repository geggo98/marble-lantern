package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.ReferenceTrainer;
import sternhalma.nn.TrainingBatch;

/** A teacher shard written by the tool, loaded by the replay buffer and trained on for one step (E10). */
class TeacherReplayTest {

    @Test
    void aTeacherShardLoadsIntoTheReplayBufferAndOneTrainingStepHasAFiniteLoss(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        int code = SelfPlay.run(new String[] {"run", "--out", dir.toString(), "--spec",
                TeacherTest.SPEC.replace("nodes=300", "nodes=100"), "--games", "4", "--seed", "1010", "--shard", "2",
                "--control", "none", "--s1", "0", "--teacher-depth", "2", "--progress", "0"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(log, true, StandardCharsets.UTF_8));
        assertEquals(0, code, log.toString(StandardCharsets.UTF_8));
        ReplayBuffer buffer = new ReplayBuffer(2, 1_000_000, true);
        ReplayBuffer.Loaded loaded = buffer.addGeneration(0, dir);
        assertEquals(2, loaded.files());
        assertTrue(loaded.skipped().isEmpty() && loaded.quarantined().isEmpty(), loaded.toString());
        assertTrue(buffer.size() > 100, "samples: " + buffer.size());
        TrainingBatch batch = buffer.batch(42, 0, 0, 32);
        int weighted = 0;
        for (int i = 0; i < batch.size(); i++) {
            double sum = 0;
            for (int m = batch.moveOffsets()[i]; m < batch.moveOffsets()[i + 1]; m++) {
                sum += batch.moveTarget()[m];
            }
            assertEquals(1.0, sum, 1e-4, "teacher target of sample " + i);
            weighted += batch.policyWeight()[i] > 0 ? 1 : 0;
        }
        assertTrue(weighted > 16, "most samples carry a policy weight");
        ReferenceTrainer trainer = new ReferenceTrainer(Parameters.init(NetworkConfig.tiny(2), 1), 1.0);
        ReferenceTrainer.Result r = trainer.accumulate(batch, 8, 2);
        assertTrue(Double.isFinite(r.loss()) && r.loss() > 0 && Double.isFinite(r.policyLoss())
                && Double.isFinite(r.valueLoss()), "loss " + r.loss());
    }
}
