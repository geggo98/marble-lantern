package sternhalma.selfplay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.HexGrid;
import sternhalma.nn.Orientation;
import sternhalma.selfplay.SampleFile.Tensor;

/**
 * Sample files built from random playouts of any player count, in the layout of {@link GameShard} (the E05 tool
 * writes two players only). Besides the file, the tests keep the original {@link Position} of every sample, so that
 * they can compare the expansion with {@code Encoder.encode}.
 */
final class SyntheticSamples {

    /** One sample with the position it came from. The target is as written to the file (not normalised). */
    record Sample(Position position, byte[] cells, int[] from, int[] to, float[] target, float[] value, float wp,
            float wv) {
    }

    private SyntheticSamples() {
    }

    /**
     * About {@code count} samples from seeded random playouts, positions with at least one legal move, cycling
     * through the movers. Weights cycle through (1, 1), (0, 1), (0.5, 2) and (2, 0.5); raw targets are positive and
     * sum to about 3, every fourth one is 0 (one stays positive).
     */
    static List<Sample> samples(int players, long seed, int count) {
        Random random = new Random(seed);
        float[] policyWeights = {1f, 0f, 0.5f, 2f};
        float[] valueWeights = {1f, 1f, 2f, 0.5f};
        List<Sample> result = new ArrayList<>();
        while (result.size() < count) {
            Position position = Position.initial(RuleSet.defaults(players));
            for (int ply = 0; ply < 120 && result.size() < count && position.outcome() == null; ply++) {
                int[] moves = position.legalMoves();
                if (moves.length == 0) {
                    break;
                }
                Orientation o = Orientation.of(position);
                byte[] cells = new byte[Geometry.CELLS];
                for (int c = 0; c < Geometry.CELLS; c++) {
                    int owner = position.owner(c);
                    cells[o.toCanonical(c)] = (byte) (owner < 0 ? 0 : 1 + o.relativePlayer(owner));
                }
                int[] from = new int[moves.length];
                int[] to = new int[moves.length];
                float[] target = new float[moves.length];
                for (int m = 0; m < moves.length; m++) {
                    int cm = o.toCanonicalMove(moves[m]);
                    from[m] = Move.from(cm);
                    to[m] = Move.to(cm);
                    target[m] = m % 4 == 3 ? 0f : 0.1f + 3f * random.nextFloat() / moves.length;
                }
                target[0] = 1f;
                float[] value = new float[players];
                for (int i = 0; i < players; i++) {
                    value[i] = 2 * random.nextFloat() - 1;
                }
                int k = result.size() % 4;
                result.add(new Sample(position.copy(), cells, from, to, target, value, policyWeights[k],
                        valueWeights[k]));
                position.make(moves[random.nextInt(moves.length)]);
            }
        }
        return result;
    }

    static Map<String, Tensor> tensors(int players, List<Sample> samples) {
        int n = samples.size();
        int moves = samples.stream().mapToInt(s -> s.from().length).sum();
        byte[] cells = new byte[n * HexGrid.CELLS];
        short[] ply = new short[n];
        short[] limit = new short[n];
        int[] offsets = new int[n + 1];
        byte[] from = new byte[moves];
        byte[] to = new byte[moves];
        float[] target = new float[moves];
        float[] value = new float[n * players];
        float[] wp = new float[n];
        float[] wv = new float[n];
        int at = 0;
        for (int s = 0; s < n; s++) {
            Sample sample = samples.get(s);
            System.arraycopy(sample.cells(), 0, cells, s * HexGrid.CELLS, HexGrid.CELLS);
            ply[s] = (short) sample.position().ply();
            limit[s] = (short) sample.position().rules().moveLimit();
            for (int m = 0; m < sample.from().length; m++) {
                from[at] = (byte) sample.from()[m];
                to[at] = (byte) sample.to()[m];
                target[at] = sample.target()[m];
                at++;
            }
            offsets[s + 1] = at;
            System.arraycopy(sample.value(), 0, value, s * players, players);
            wp[s] = sample.wp();
            wv[s] = sample.wv();
        }
        Map<String, Tensor> t = new LinkedHashMap<>();
        t.put("cells", Tensor.u8(cells, n, HexGrid.CELLS));
        t.put("ply", Tensor.i16(ply, n));
        t.put("move_limit", Tensor.i16(limit, n));
        t.put("move_offsets", Tensor.i32(offsets, n + 1));
        t.put("move_from", Tensor.u8(from, moves));
        t.put("move_to", Tensor.u8(to, moves));
        t.put("move_target", Tensor.f32(target, moves));
        t.put("value_target", Tensor.f32(value, n, players));
        t.put("policy_weight", Tensor.f32(wp, n));
        t.put("value_weight", Tensor.f32(wv, n));
        return t;
    }

    static Map<String, String> metadata(int players) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("format", GameShard.FORMAT);
        m.put("format_version", Integer.toString(GameShard.FORMAT_VERSION));
        m.put("players", Integer.toString(players));
        return m;
    }

    static Path write(Path dir, String name, int players, List<Sample> samples) throws IOException {
        Path file = dir.resolve(name);
        SampleFile.write(file, tensors(players, samples), metadata(players));
        return file;
    }
}
