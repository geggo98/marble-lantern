package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.nn.BatchSampler;
import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.TrainingBatch;
import sternhalma.selfplay.ReplayBuffer.Strata;

/**
 * The mixed-variant replay buffer: variants, strata, value rows and weights, mirroring, determinism. Expected values
 * come from the synthetic samples that wrote the files (their values and weights) and from the constant planes 13
 * ({@code long}) and 14 ({@code three}) of the batch input, not from the buffer's own bookkeeping.
 */
class MixedReplayBufferTest {

    private static final PlaneSet PLANES = PlaneSet.CLASSIC1V;
    /** Samples per variant, deliberately unequal: 20, 60, 100, 200. */
    private static final int[] GAMES = {2, 6, 10, 20};
    private static final int PER_GAME = 10;

    @TempDir
    Path dir;

    private static RuleSet rules(Variant v) {
        return RuleSet.defaults(v.players()).withMoveLimit(300).withJumpRule(v.jumpRule());
    }

    private List<Path> files(Variant... variants) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Variant v : variants) {
            files.add(SyntheticShards.write(dir, "samples-" + v.label() + ".safetensors", rules(v), 11 + v.ordinal(),
                    0, GAMES[v.ordinal()], PER_GAME, 100 + v.ordinal()));
        }
        return files;
    }

    private ReplayBuffer buffer(Strata strata, boolean augment, Variant... variants) throws IOException {
        ReplayBuffer b = ReplayBuffer.mixed(Integer.MAX_VALUE, augment, PLANES, strata);
        b.addGeneration(0, files(variants));
        return b;
    }

    private ReplayBuffer all(Strata strata, boolean augment) throws IOException {
        return buffer(strata, augment, Variant.values());
    }

    /** The variant a batch row has, read from its constant planes 13 and 14. */
    private static Variant variantOfRow(TrainingBatch b, int row) {
        int stride = PLANES.planes();
        int base = row * PLANES.inputSize();
        boolean three = b.input()[base + Encoder.THREE_PLAYERS] == 1f;
        boolean longJump = b.input()[base + Encoder.LONG] == 1f;
        assertEquals(three ? 1f : 0f, b.input()[base + (HexGrid.CELLS - 1) * stride + Encoder.THREE_PLAYERS],
                "plane 14 is constant over the cells");
        return Variant.of(three ? 3 : 2, longJump ? JumpRule.LONG : JumpRule.ADJACENT);
    }

    private static Map<Variant, Integer> counts(TrainingBatch b) {
        Map<Variant, Integer> counts = new EnumMap<>(Variant.class);
        for (int i = 0; i < b.size(); i++) {
            counts.merge(variantOfRow(b, i), 1, Integer::sum);
        }
        return counts;
    }

    // ---- loading ----

    @Test
    void anAllVariantsBufferKnowsEveryVariantWithItsSamples() throws IOException {
        ReplayBuffer b = all(Strata.EQUAL, false);
        assertEquals(List.of(Variant.values()), b.variants());
        assertEquals(3, b.players(), "the batches have the width of the shared value head");
        for (Variant v : Variant.values()) {
            assertEquals(GAMES[v.ordinal()] * PER_GAME, b.indicesOf(v).length, v.label());
            for (int i : b.indicesOf(v)) {
                assertEquals(v, b.variantOf(i));
            }
        }
        assertEquals(380, b.size());
    }

    @Test
    void aMixedBufferRejectsAPlainPlaneSetAndAStrictBufferRejectsAnotherPlayerCount() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> ReplayBuffer.mixed(10, false, PlaneSet.CLASSIC1, Strata.EQUAL));
        ReplayBuffer strict = new ReplayBuffer(3, 100, false, PLANES);
        assertThrows(IllegalArgumentException.class, () -> strict.addGeneration(0, files(Variant.TWO_ADJACENT)),
                "a strict 3-player buffer still refuses a 2-player file");
    }

    @Test
    void theRulesOfEachFileDecideTheReachPlanes() throws IOException {
        // the same synthetic samples under ADJACENT and LONG: row planes 9/10 (reach) differ, plane 13 differs
        ReplayBuffer adj = buffer(Strata.NATURAL, false, Variant.TWO_ADJACENT);
        ReplayBuffer lng = buffer(Strata.NATURAL, false, Variant.TWO_LONG);
        TrainingBatch a = adj.batchOf(new int[] {5}, new boolean[] {false});
        TrainingBatch l = lng.batchOf(new int[] {5}, new boolean[] {false});
        assertEquals(0f, a.input()[Encoder.LONG]);
        assertEquals(1f, l.input()[Encoder.LONG]);
        assertEquals(0f, a.input()[Encoder.THREE_PLAYERS]);
    }

    // ---- value rows and weights ----

    @Test
    void twoPlayerRowsGetADummyTargetAndWeight1Point5ThreePlayerRowsAreUntouched() throws IOException {
        List<SyntheticSamples.Sample> two = SyntheticSamples.samples(rules(Variant.TWO_ADJACENT), 100, 20);
        List<SyntheticSamples.Sample> three = SyntheticSamples.samples(rules(Variant.THREE_LONG), 103, 30);
        ReplayBuffer b = ReplayBuffer.mixed(Integer.MAX_VALUE, false, PLANES, Strata.NATURAL);
        b.addGeneration(0, List.of(
                SyntheticSamples.write(dir, "samples-a.safetensors", rules(Variant.TWO_ADJACENT), two),
                SyntheticSamples.write(dir, "samples-b.safetensors", rules(Variant.THREE_LONG), three)));
        int[] indices = new int[two.size() + three.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        TrainingBatch batch = b.batchOf(indices, new boolean[indices.length]);
        assertEquals(3, batch.players());
        for (int i = 0; i < two.size(); i++) {
            SyntheticSamples.Sample s = two.get(i);
            assertArrayEquals(new float[] {s.value()[0], s.value()[1], 0f},
                    Arrays.copyOfRange(batch.valueTarget(), 3 * i, 3 * i + 3), "2p value row " + i);
            assertEquals(1.5f * s.wv(), batch.valueWeight()[i], 1e-6, "2p value weight " + i);
            assertEquals(s.wp(), batch.policyWeight()[i], "the policy weight is not scaled");
        }
        for (int i = 0; i < three.size(); i++) {
            int row = two.size() + i;
            SyntheticSamples.Sample s = three.get(i);
            assertArrayEquals(s.value(), Arrays.copyOfRange(batch.valueTarget(), 3 * row, 3 * row + 3),
                    "3p value row " + i);
            assertEquals(s.wv(), batch.valueWeight()[row], "3p value weight " + i);
        }
    }

    // ---- stratification ----

    @Test
    void equalStrataGiveEachPresentVariantAQuarterOfEveryBatchWhateverTheDataSizes() throws IOException {
        ReplayBuffer b = all(Strata.EQUAL, false);
        for (long step = 0; step < 20; step++) {
            Map<Variant, Integer> c = counts(b.batch(7, 0, step, 256));
            for (Variant v : Variant.values()) {
                assertEquals(64, c.get(v), v.label() + " at step " + step + ": " + c);
            }
        }
    }

    @Test
    void equalStrataOverThreeVariantsAndAnIndivisibleBatchSizeRotateTheRemainder() throws IOException {
        ReplayBuffer b = buffer(Strata.EQUAL, false, Variant.TWO_ADJACENT, Variant.TWO_LONG, Variant.THREE_LONG);
        Map<Variant, Integer> total = new EnumMap<>(Variant.class);
        for (long step = 0; step < 30; step++) {
            Map<Variant, Integer> c = counts(b.batch(3, 0, step, 10));
            for (Variant v : List.of(Variant.TWO_ADJACENT, Variant.TWO_LONG, Variant.THREE_LONG)) {
                assertTrue(c.get(v) == 3 || c.get(v) == 4, v.label() + ": " + c);
                total.merge(v, c.get(v), Integer::sum);
            }
            assertFalse(c.containsKey(Variant.THREE_ADJACENT));
        }
        // 30 batches of 10 = 300 rows, exactly 100 per variant because the remainder rotates with the step
        total.values().forEach(n -> assertEquals(100, n, total.toString()));
    }

    @Test
    void naturalStrataFollowTheDataSharesAndUseTheOldDraw() throws IOException {
        ReplayBuffer b = all(Strata.NATURAL, false);
        Map<Variant, Integer> total = new EnumMap<>(Variant.class);
        for (long step = 0; step < 40; step++) {
            counts(b.batch(7, 0, step, 256)).forEach((v, n) -> total.merge(v, n, Integer::sum));
        }
        // shares 20/60/100/200 of 380; 10240 draws, standard deviation <= 50
        int rows = 40 * 256;
        int[] data = {20, 60, 100, 200};
        for (Variant v : Variant.values()) {
            assertEquals(rows * data[v.ordinal()] / 380.0, total.get(v), 200, v.label() + " " + total);
        }
        // the draw is BatchSampler's: the first row of step 3 is the sample BatchSampler picks
        int expected = BatchSampler.indices(7, 0, 3, b.size(), 256)[0];
        TrainingBatch single = b.batchOf(new int[] {expected}, new boolean[] {false});
        assertArrayEquals(single.input(), Arrays.copyOf(b.batch(7, 0, 3, 256).input(), PLANES.inputSize()));
    }

    @Test
    void aBufferWithOneVariantDrawsLikeNaturalWhateverTheStrata() throws IOException {
        ReplayBuffer equal = buffer(Strata.EQUAL, false, Variant.THREE_ADJACENT);
        ReplayBuffer natural = buffer(Strata.NATURAL, false, Variant.THREE_ADJACENT);
        assertArrayEquals(natural.batch(9, 1, 2, 16).input(), equal.batch(9, 1, 2, 16).input());
    }

    // ---- determinism ----

    @Test
    void theBatchIsAPureFunctionOfSeedGenerationAndStep() throws IOException {
        ReplayBuffer b = all(Strata.EQUAL, true);
        ReplayBuffer again = all(Strata.EQUAL, true);
        TrainingBatch x = b.batch(42, 2, 5, 64);
        TrainingBatch y = again.batch(42, 2, 5, 64);
        assertArrayEquals(x.input(), y.input());
        assertArrayEquals(x.valueTarget(), y.valueTarget());
        assertArrayEquals(x.valueWeight(), y.valueWeight());
        assertArrayEquals(x.moveTarget(), y.moveTarget());
        assertNotEquals(Arrays.hashCode(x.input()), Arrays.hashCode(b.batch(43, 2, 5, 64).input()), "another seed");
        assertNotEquals(Arrays.hashCode(x.input()), Arrays.hashCode(b.batch(42, 2, 6, 64).input()), "another step");
        assertNotEquals(Arrays.hashCode(x.input()), Arrays.hashCode(b.batch(42, 3, 5, 64).input()),
                "another generation");
    }

    @Test
    void aGoldenBatchOfTheMixedBufferStaysTheSameAcrossRuns() throws Exception {
        // taken from the first run of this implementation: it pins the draw order and the value rows, so a change
        // to the stratified draw, the mirror coins or the padding is a visible decision
        ReplayBuffer b = all(Strata.EQUAL, true);
        TrainingBatch batch = b.batch(42, 0, 0, 16);
        assertEquals(GOLDEN_ROWS, rowSignature(batch));
    }

    private static final String GOLDEN_ROWS = "0:0.75:0.0:36 1:0.75:0.0:54 2:1.0:-0.07703614:53 3:0.5:-0.5730938:84 0:1.5:0.0:39 1:1.5:0.0:57 2:1.0:0.9232936:51 3:1.0:0.8085979:74 0:0.75:0.0:42 1:1.5:0.0:26 2:1.0:-0.3481437:34 3:0.5:0.6539283:40 0:3.0:0.0:25 1:0.75:0.0:59 2:1.0:0.32202077:46 3:2.0:-0.42232358:41";

    private static String rowSignature(TrainingBatch b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.size(); i++) {
            sb.append(variantOfRow(b, i).ordinal()).append(':').append(b.valueWeight()[i]).append(':')
                    .append(b.valueTarget()[3 * i + 2]).append(':').append(b.moveOffsets()[i + 1]
                            - b.moveOffsets()[i]).append(' ');
        }
        return sb.toString().strip();
    }

    // ---- mirroring ----

    @Test
    void mirroringAppliesToTwoPlayerRowsOnly() throws IOException {
        ReplayBuffer augmented = all(Strata.EQUAL, true);
        ReplayBuffer plain = all(Strata.EQUAL, false);
        int three = augmented.indicesOf(Variant.THREE_ADJACENT)[0];
        assertThrows(IllegalArgumentException.class, () -> augmented.batchOf(new int[] {three}, new boolean[] {true}));
        // the same seed draws the same samples in both buffers (the mirror coins have their own stream), so a row
        // differs between them exactly when it was mirrored
        int mirrored = 0;
        int twoPlayerRows = 0;
        int size = PLANES.inputSize();
        for (long step = 0; step < 30; step++) {
            TrainingBatch x = augmented.batch(5, 0, step, 64);
            TrainingBatch y = plain.batch(5, 0, step, 64);
            for (int i = 0; i < x.size(); i++) {
                boolean same = Arrays.equals(Arrays.copyOfRange(x.input(), i * size, (i + 1) * size),
                        Arrays.copyOfRange(y.input(), i * size, (i + 1) * size));
                if (variantOfRow(x, i).players() == 2) {
                    twoPlayerRows++;
                    mirrored += same ? 0 : 1;
                } else {
                    assertTrue(same, "a three-player row is never mirrored");
                }
            }
        }
        // symmetric positions (the start position) look the same mirrored, so the share is below 1/2
        assertTrue(mirrored > 0.2 * twoPlayerRows && mirrored < 0.6 * twoPlayerRows,
                mirrored + " of " + twoPlayerRows);
    }

    // ---- winners ----

    @Test
    void theWinnerOfAThreePlayerSampleIsTheRelativePlayerWithTheTopGameValue() throws IOException {
        ReplayBuffer b = buffer(Strata.NATURAL, false, Variant.THREE_ADJACENT);
        // SyntheticShards: game g has the values (1, -1, 0) for even ids, (-1, 1, 0) for odd ids, all 0 for id % 7 == 0
        // The mover of ply p is p mod 3, so relative player i is absolute (p + i) mod 3.
        int checked = 0;
        for (int i = 0; i < b.size(); i++) {
            int game = i / PER_GAME;
            float[] values = SyntheticShards.values(game);
            int w = b.winner(i);
            if (game % 7 == 0) {
                assertEquals(-1, w, "a shared top value has no winner");
            } else {
                int absolute = game % 2 == 0 ? 0 : 1;
                int ply = plyOf(b, i);
                assertEquals(((absolute - ply) % 3 + 3) % 3, w, "sample " + i + " ply " + ply + " " + values[0]);
                checked++;
            }
        }
        assertTrue(checked > 20);
    }

    private static int plyOf(ReplayBuffer b, int index) {
        // the ply is not exposed; read it from the progress plane: progress = min(ply, limit) / limit, limit 300
        TrainingBatch t = b.batchOf(new int[] {index}, new boolean[] {false});
        return Math.round(t.input()[Encoder.PROGRESS] * 300);
    }

    @Test
    void theTwoPlayerResultKeepsItsMeaningInAMixedBuffer() throws IOException {
        ReplayBuffer b = buffer(Strata.NATURAL, false, Variant.TWO_ADJACENT, Variant.THREE_ADJACENT);
        int two = b.indicesOf(Variant.TWO_ADJACENT)[1];
        int game = two / PER_GAME;
        int ply = plyOf(b, two);
        int mover = ply % 2;
        float[] v = SyntheticShards.values(game);
        int expected = (int) Math.signum(v[mover] - v[1 - mover]);
        assertEquals(expected, b.result(two));
        assertEquals(0, b.result(b.indicesOf(Variant.THREE_ADJACENT)[0]), "no two-player result for three players");
    }

    @Test
    void aGameFilterKeepsTheVariantIndexInStep() throws IOException {
        ReplayBuffer b = ReplayBuffer.mixed(Integer.MAX_VALUE, false, PLANES, Strata.EQUAL);
        b.addGeneration(0, files(Variant.values()), (m, id) -> id % 2 == 0);
        for (Variant v : Variant.values()) {
            assertEquals(GAMES[v.ordinal()] / 2 * PER_GAME, b.indicesOf(v).length, v.label());
        }
        assertEquals(List.of(Variant.values()), b.variants());
    }
}
