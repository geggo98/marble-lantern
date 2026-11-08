package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Tests of the validated layout of {@link TrainingBatch} (docs/training.md, "Batch layout"). */
class TrainingBatchTest {

    /** Mutable arrays of a valid batch with two samples: 2 moves and 1 move. */
    private static final class Arrays2 {
        int size = 2;
        int players = 2;
        float[] input = new float[2 * Encoder.INPUT_SIZE];
        int[] offsets = {0, 2, 3};
        int[] from = {1, 2, 3};
        int[] to = {4, 5, 6};
        float[] target = {0.25f, 0.75f, 1f};
        float[] z = {0.5f, -0.5f, 1f, -1f};
        float[] wp = {1f, 1f};
        float[] wv = {1f, 1f};
        double[] entropy = {ReferenceLoss.entropy(new float[] {0.25f, 0.75f}, 0, 2), 0.0};

        TrainingBatch build() {
            return new TrainingBatch(size, players, input, offsets, from, to, target, z, wp, wv, entropy);
        }
    }

    private static void rejects(String why, Consumer<Arrays2> damage) {
        Arrays2 a = new Arrays2();
        damage.accept(a);
        assertThrows(IllegalArgumentException.class, a::build, why);
    }

    @Test
    void acceptsAValidBatch() {
        TrainingBatch batch = new Arrays2().build();
        assertEquals(2, batch.size());
        assertEquals(3, batch.moveCount());
    }

    @Test
    void rejectsEveryBrokenRule() {
        rejects("size 0", a -> a.size = 0);
        rejects("three-and-a-half players", a -> a.players = 4);
        rejects("short input", a -> a.input = new float[Encoder.INPUT_SIZE]);
        rejects("short offsets", a -> a.offsets = new int[] {0, 2});
        rejects("first offset not 0", a -> a.offsets = new int[] {1, 2, 3});
        rejects("decreasing offsets", a -> a.offsets = new int[] {0, 4, 3});
        rejects("moveFrom shorter than M", a -> a.from = new int[] {1, 2});
        rejects("target length", a -> a.target = new float[] {0.25f, 0.75f});
        rejects("value target length", a -> a.z = new float[] {0f, 0f, 0f});
        rejects("weight length", a -> a.wp = new float[] {1f});
        rejects("from cell 121", a -> a.from[1] = 121);
        rejects("negative to cell", a -> a.to[0] = -1);
        rejects("negative target", a -> {
            a.target[0] = -0.25f;
            a.target[1] = 1.25f;
        });
        rejects("NaN target", a -> a.target[0] = Float.NaN);
        rejects("targets sum to 1.5", a -> a.target[1] = 1.25f);
        rejects("targets not normalised", a -> a.target = new float[] {1f, 1f, 1f});
        rejects("weighted sample with zero targets", a -> {
            a.target[2] = 0f;
            a.entropy[1] = 0;
        });
        rejects("weighted sample without moves", a -> {
            a.offsets = new int[] {0, 3, 3};
            a.target = new float[] {0.25f, 0.5f, 0.25f};
            a.entropy = new double[] {ReferenceLoss.entropy(a.target, 0, 3), 0.0};
        });
        rejects("same (from, to) twice", a -> {
            a.from[1] = 1;
            a.to[1] = 4;
        });
        rejects("value target above 1", a -> a.z[0] = 1.5f);
        rejects("NaN value target", a -> a.z[0] = Float.NaN);
        rejects("negative policy weight", a -> a.wp[0] = -1f);
        rejects("infinite value weight", a -> a.wv[0] = Float.POSITIVE_INFINITY);
        rejects("padding row of the input", a -> a.input[HexGrid.CELLS * Encoder.PLANES + 3] = 1f);
        rejects("NaN in the input", a -> a.input[5] = Float.NaN);
        rejects("wrong entropy", a -> a.entropy[0] += 0.1);
    }

    /** The same pair in two different samples is fine; a zero-weight sample may have zero targets. */
    @Test
    void acceptsWhatTheRulesAllow() {
        Arrays2 a = new Arrays2();
        a.from[2] = 1;
        a.to[2] = 4; // the same pair as move 0, but in sample 1
        a.build();
        Arrays2 b = new Arrays2();
        b.wp[1] = 0f;
        b.target[2] = 0f;
        b.entropy[1] = 0;
        b.build();
        Arrays2 c = new Arrays2();
        c.wp[1] = 0f;
        c.offsets = new int[] {0, 3, 3};
        c.target = new float[] {0.25f, 0.5f, 0.25f};
        c.entropy = new double[] {ReferenceLoss.entropy(c.target, 0, 3), 0.0};
        c.build(); // a sample without legal moves and policy weight 0
    }

    @Test
    void copiesItsArrays() {
        Arrays2 a = new Arrays2();
        TrainingBatch batch = a.build();
        a.target[0] = 99f;
        a.from[0] = 120;
        a.input[0] = 5f;
        assertEquals(0.25f, batch.moveTarget()[0]);
        assertEquals(1, batch.moveFrom()[0]);
        assertEquals(0f, batch.input()[0]);
    }

    @Test
    void builderNormalisesAndComputesTheEntropy() {
        TrainingBatch.Builder builder = new TrainingBatch.Builder(2);
        builder.add(new float[Encoder.INPUT_SIZE], new int[] {1, 2, 3}, new int[] {4, 5, 6}, new double[] {2, 6, 0},
                new float[] {1f, -1f}, 1f, 1f);
        builder.add(new float[Encoder.INPUT_SIZE], new int[0], new int[0], new double[0], new float[] {0f, 0f}, 0f,
                1f);
        TrainingBatch batch = builder.build();
        assertArrayEquals(new float[] {0.25f, 0.75f, 0f}, batch.moveTarget());
        assertArrayEquals(new int[] {0, 3, 3}, batch.moveOffsets());
        double h = -(0.25 * Math.log(0.25) + 0.75 * Math.log(0.75));
        assertEquals(h, batch.targetEntropy()[0], 1e-7);
        assertEquals(0.0, batch.targetEntropy()[1]);

        TrainingBatch.Builder bad = new TrainingBatch.Builder(2);
        assertThrows(IllegalArgumentException.class, () -> bad.add(new float[Encoder.INPUT_SIZE - 1], new int[] {1},
                new int[] {2}, new double[] {1}, new float[] {0f, 0f}, 1f, 1f), "short input");
        assertThrows(IllegalArgumentException.class, () -> bad.add(new float[Encoder.INPUT_SIZE], new int[] {1},
                new int[] {2}, new double[] {1}, new float[] {0f, 0f, 0f}, 1f, 1f), "three value targets, two players");
        assertThrows(IllegalArgumentException.class, () -> bad.add(new float[Encoder.INPUT_SIZE], new int[] {1},
                new int[] {2}, new double[] {0}, new float[] {0f, 0f}, 1f, 1f), "zero targets, positive weight");
        assertThrows(IllegalArgumentException.class, () -> bad.add(new float[Encoder.INPUT_SIZE], new int[] {1, 2},
                new int[] {2}, new double[] {1}, new float[] {0f, 0f}, 1f, 1f), "from and to differ in length");
    }
}
