package sternhalma.selfplay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import sternhalma.selfplay.SampleFile.Tensor;
import sternhalma.selfplay.SyntheticSamples.Sample;

/**
 * Teacher-like shards from random playouts: {@link SyntheticSamples} plus the game tensors ({@code game},
 * {@code game_id}, {@code game_values}) and the {@code seed} metadata that the held-out split and the winner
 * prediction need. Samples are cut into games of {@code perGame} samples; game {@code g} has the label values
 * {@code (1, -1)} for even g, {@code (-1, 1)} for odd g and {@code (0, 0)} for every seventh.
 */
final class SyntheticShards {

    private SyntheticShards() {
    }

    /** The label values of game {@code id} (absolute players). */
    static float[] values(int id) {
        if (id % 7 == 0) {
            return new float[] {0, 0};
        }
        return id % 2 == 0 ? new float[] {1, -1} : new float[] {-1, 1};
    }

    static Path write(Path dir, String name, long shardSeed, int firstGameId, int games, int perGame, long sampleSeed)
            throws IOException {
        return write(dir, name, 2, shardSeed, firstGameId, games, perGame, sampleSeed);
    }

    /** As above for {@code players} players; {@code game_values} is [games, players] (the labels of the two-player case, padded with 0). */
    static Path write(Path dir, String name, int players, long shardSeed, int firstGameId, int games, int perGame,
            long sampleSeed) throws IOException {
        List<Sample> samples = SyntheticSamples.samples(players, sampleSeed, games * perGame);
        Map<String, Tensor> tensors = SyntheticSamples.tensors(players, samples);
        int n = samples.size();
        int[] game = new int[n];
        for (int s = 0; s < n; s++) {
            game[s] = s / perGame;
        }
        int[] id = new int[games];
        float[] values = new float[games * players];
        for (int g = 0; g < games; g++) {
            id[g] = firstGameId + g;
            values[players * g] = values(id[g])[0];
            values[players * g + 1] = values(id[g])[1];
        }
        tensors.put("game", Tensor.i32(game, n));
        tensors.put("game_id", Tensor.i32(id, games));
        tensors.put("game_values", Tensor.f32(values, games, players));
        Map<String, String> metadata = SyntheticSamples.metadata(players);
        metadata.put("seed", Long.toString(shardSeed));
        Path file = dir.resolve(name);
        SampleFile.write(file, tensors, metadata);
        return file;
    }
}
