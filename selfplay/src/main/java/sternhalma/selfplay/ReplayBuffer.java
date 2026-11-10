package sternhalma.selfplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import sternhalma.nn.BatchSampler;
import sternhalma.nn.BatchSource;
import sternhalma.nn.HexGrid;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.ReferenceLoss;
import sternhalma.nn.SampleExpander;
import sternhalma.nn.TrainingBatch;
import sternhalma.selfplay.SampleFile.Contents;

/**
 * The replay buffer of docs/training.md, "Replay buffer": the samples of the last generations in the compact form
 * of the sample files (canonical cells as bytes, moves as bytes), and the builder that turns drawn samples into a
 * {@link TrainingBatch}.
 *
 * <p><b>Window.</b> Generations are added in increasing order. After each addition, while the buffer holds more than
 * {@code window} samples and more than one generation, the oldest whole generation is dropped. A single generation
 * is never dropped, however large, so the newest data is always trainable.
 *
 * <p><b>Files.</b> A file is read with {@link SampleFile} and validated completely (format, shapes, offsets, cell
 * range, targets, weights); a file that fails is skipped. {@link #addGeneration(int, Path)} follows the job
 * conventions: it renames a failed file to {@code <name>.corrupt}, which the caller counts as CLEANUP. A file of
 * another player count is not corrupt but a wrong configuration and raises {@link IllegalArgumentException}. Move
 * targets are normalised to sum 1 at load time, as {@link TrainingBatch.Builder} does.
 *
 * <p><b>Batches.</b> {@link #batch} draws with replacement and with the seeded sampler of {@link BatchSampler}, so
 * the same run seed, generation and step give the same batch. With two players and {@code augment}, each drawn
 * sample is mirrored with probability 1/2 (docs/training.md, "Symmetric augmentation"); three players are never
 * mirrored. Samples with policy weight 0 keep it; the weights come from the file.
 *
 * <p>Not thread-safe: one thread adds generations and builds batches.
 */
public final class ReplayBuffer implements BatchSource {

    /** Mixed into the run seed so the mirror coins are independent of the indices. */
    private static final long MIRROR_SALT = 0x5DEECE66DL * 0x9E3779B97F4A7C15L;

    /** The outcome of loading one generation. */
    public record Loaded(int generation, int files, int samples, List<Path> skipped, List<Path> quarantined) {
    }

    /** The samples of one file, validated, in compact form. */
    private record Block(int generation, int size, byte[] cells, short[] ply, short[] moveLimit, int[] moveOffsets,
            byte[] moveFrom, byte[] moveTo, float[] moveTarget, float[] value, float[] policyWeight,
            float[] valueWeight, double[] entropy,
            byte[] result) {
    }

    private final int players;
    private final int window;
    private final boolean augment;
    private final PlaneSet planes;
    private final List<Block> blocks = new ArrayList<>();
    private int[] starts = {0};
    private int lastGeneration = -1;

    /**
     * @param players the player count of the files this buffer accepts, 2 or 3
     * @param window  W, the number of samples above which the oldest generation is dropped, &gt;= 1
     * @param augment whether to mirror drawn samples (has an effect for two players only)
     */
    public ReplayBuffer(int players, int window, boolean augment) {
        this(players, window, augment, PlaneSet.PLAIN);
    }

    /**
     * @param planes the input planes of the batches; the classical planes are recomputed from the cells of each drawn
     *               sample at batch build time, never stored
     */
    public ReplayBuffer(int players, int window, boolean augment, PlaneSet planes) {
        if (players != 2 && players != 3) {
            throw new IllegalArgumentException("players must be 2 or 3, got " + players);
        }
        if (window < 1) {
            throw new IllegalArgumentException("window must be >= 1, got " + window);
        }
        this.players = players;
        this.window = window;
        this.augment = augment && players == 2;
        this.planes = java.util.Objects.requireNonNull(planes, "planes");
    }

    /** Which games of a file a buffer takes (see {@link #addGeneration(int, List, GameFilter)}). */
    @FunctionalInterface
    public interface GameFilter {

        /**
         * @param metadata the metadata of the file ({@code seed}, ...)
         * @param gameId   the value of the tensor {@code game_id} of the game
         */
        boolean accepts(Map<String, String> metadata, int gameId);
    }

    @Override
    public int players() {
        return players;
    }

    /**
     * The result of the game of sample {@code index}, seen by the player to move there: +1 if the game label says
     * that the mover wins, -1 if it loses, 0 for a draw or when the file had no game tensors or the buffer is not
     * for two players.
     *
     * @throws IllegalArgumentException if the index is outside the buffer
     */
    public int result(int index) {
        if (index < 0 || index >= size()) {
            throw new IllegalArgumentException("index " + index + " outside 0 to " + (size() - 1));
        }
        int b = Arrays.binarySearch(starts, 0, blocks.size() + 1, index);
        b = b >= 0 ? b : -b - 2;
        Block block = blocks.get(b);
        return block.result == null ? 0 : block.result[index - starts[b]];
    }

    /** Samples in the buffer. */
    public int size() {
        return starts[blocks.size()];
    }

    /** The generations in the buffer, oldest first. */
    public List<Integer> generations() {
        List<Integer> result = new ArrayList<>();
        for (Block b : blocks) {
            if (result.isEmpty() || result.get(result.size() - 1) != b.generation) {
                result.add(b.generation);
            }
        }
        return result;
    }

    /** Samples of one generation, 0 if it is not in the buffer. */
    public int size(int generation) {
        int n = 0;
        for (Block b : blocks) {
            if (b.generation == generation) {
                n += b.size;
            }
        }
        return n;
    }

    /**
     * Loads the files {@code samples-*.safetensors} of a directory in name order as one generation; a file that
     * fails validation is renamed to {@code *.corrupt} and reported in {@link Loaded#quarantined()},
     * after the whole call succeeded (a file of another player count throws before any rename). If no file loads, the
     * generation number stays free for a retry.
     */
    public Loaded addGeneration(int generation, Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(directory)) {
            files = s.filter(f -> f.getFileName().toString().matches("samples-.*\\.safetensors")).sorted().toList();
        }
        return add(generation, files, true, null);
    }

    /** Loads the given files, in the given order, as one generation; files that fail are skipped and left alone. */
    public Loaded addGeneration(int generation, List<Path> files) throws IOException {
        return add(generation, files, false, null);
    }

    /**
     * Like {@link #addGeneration(int, List)}, but takes only the samples of the games that the filter accepts, and
     * keeps their results for {@link #result}. Needs the game tensors of the shard layout; a file without them is a
     * wrong configuration (IllegalArgumentException), not corrupt. Files are never renamed.
     */
    public Loaded addGeneration(int generation, List<Path> files, GameFilter filter) throws IOException {
        return add(generation, files, false, Objects.requireNonNull(filter, "filter"));
    }

    private Loaded add(int generation, List<Path> files, boolean quarantine, GameFilter filter) throws IOException {
        if (generation <= lastGeneration) {
            throw new IllegalArgumentException("generation " + generation + " is not after generation "
                    + lastGeneration);
        }
        List<Block> loaded = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        List<Path> quarantined = new ArrayList<>();
        int samples = 0;
        int read = 0;
        for (Path file : files) {
            Block block;
            try {
                Contents contents = SampleFile.read(file);
                block = parse(generation, contents);
                block = withGames(block, contents, filter);
            } catch (IOException e) {
                skipped.add(file);
                continue;
            }
            if (block != null && block.size > 0) {
                loaded.add(block);
                samples += block.size;
            }
            read++;
        }
        // Renames come last, so a wrong-players file (thrown above) leaves every file as it was.
        if (quarantine) {
            for (Path file : skipped) {
                Path target = file.resolveSibling(file.getFileName() + ".corrupt");
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                quarantined.add(target);
            }
        }
        // A generation of which no file loaded is not consumed: after a repair the same number can be retried.
        if (read > 0) {
            lastGeneration = generation;
        }
        blocks.addAll(loaded);
        while (generations().size() > 1 && size(blocks) > window) {
            int oldest = blocks.get(0).generation;
            blocks.removeIf(b -> b.generation == oldest);
        }
        starts = new int[blocks.size() + 1];
        for (int i = 0; i < blocks.size(); i++) {
            starts[i + 1] = starts[i] + blocks.get(i).size;
        }
        return new Loaded(generation, read, samples, skipped, quarantined);
    }

    private static int size(List<Block> blocks) {
        int n = 0;
        for (Block b : blocks) {
            n += b.size;
        }
        return n;
    }

    /**
     * The batch of step {@code step}: {@link BatchSampler#indices} over the whole buffer, and for two players with
     * augmentation one mirror coin per drawn sample from a second seeded stream.
     *
     * @param generation the training generation (as for {@link BatchSampler}), not a data generation
     * @throws IllegalStateException    if the buffer is empty
     * @throws IllegalArgumentException if batchSize &lt; 1, or generation or step is negative
     */
    public TrainingBatch batch(long runSeed, int generation, long step, int batchSize) {
        if (size() == 0) {
            throw new IllegalStateException("the replay buffer is empty");
        }
        int[] indices = BatchSampler.indices(runSeed, generation, step, size(), batchSize);
        boolean[] mirror = new boolean[batchSize];
        if (augment) {
            SplittableRandom coins = new SplittableRandom(BatchSampler.mix(runSeed ^ MIRROR_SALT, generation, step));
            for (int i = 0; i < batchSize; i++) {
                mirror[i] = coins.nextBoolean();
            }
        }
        return batchOf(indices, mirror);
    }

    /**
     * The batch of the samples with the given buffer indices (0 is the oldest sample), in that order, each mirrored
     * where {@code mirror[i]} says so.
     *
     * @throws IllegalArgumentException if an index is outside the buffer, the arrays differ in length, or a mirror
     *                                  is requested for three players
     */
    public TrainingBatch batchOf(int[] indices, boolean[] mirror) {
        int n = indices.length;
        if (n == 0 || mirror.length != n) {
            throw new IllegalArgumentException("need at least one index and one mirror flag per index");
        }
        int[] blockOf = new int[n];
        int[] offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            if (indices[i] < 0 || indices[i] >= size()) {
                throw new IllegalArgumentException("index " + indices[i] + " outside 0 to " + (size() - 1));
            }
            int b = Arrays.binarySearch(starts, 0, blocks.size() + 1, indices[i]);
            blockOf[i] = b >= 0 ? b : -b - 2;
            Block block = blocks.get(blockOf[i]);
            int s = indices[i] - starts[blockOf[i]];
            offsets[i + 1] = offsets[i] + block.moveOffsets[s + 1] - block.moveOffsets[s];
        }
        int inputSize = planes.inputSize();
        float[] input = new float[n * inputSize];
        int[] from = new int[offsets[n]];
        int[] to = new int[offsets[n]];
        float[] target = new float[offsets[n]];
        float[] value = new float[n * players];
        float[] wp = new float[n];
        float[] wv = new float[n];
        double[] entropy = new double[n];
        for (int i = 0; i < n; i++) {
            Block b = blocks.get(blockOf[i]);
            int s = indices[i] - starts[blockOf[i]];
            SampleExpander.expand(planes, players, b.cells, s * HexGrid.CELLS, b.ply[s], b.moveLimit[s], mirror[i], input,
                    i * inputSize);
            int first = b.moveOffsets[s];
            int count = b.moveOffsets[s + 1] - first;
            for (int m = 0; m < count; m++) {
                int f = b.moveFrom[first + m] & 0xFF;
                int t = b.moveTo[first + m] & 0xFF;
                from[offsets[i] + m] = mirror[i] ? SampleExpander.mirrorCell(f) : f;
                to[offsets[i] + m] = mirror[i] ? SampleExpander.mirrorCell(t) : t;
            }
            System.arraycopy(b.moveTarget, first, target, offsets[i], count);
            System.arraycopy(b.value, s * players, value, i * players, players);
            wp[i] = b.policyWeight[s];
            wv[i] = b.valueWeight[s];
            entropy[i] = b.entropy[s];
        }
        return new TrainingBatch(n, players, input, offsets, from, to, target, value, wp, wv, entropy);
    }

    // ---- loading and validation ----

    private Block parse(int generation, Contents c) throws IOException {
        Map<String, String> meta = c.metadata();
        if (!GameShard.FORMAT.equals(meta.get("format"))
                || !Integer.toString(GameShard.FORMAT_VERSION).equals(meta.get("format_version"))) {
            throw new IOException("not a " + GameShard.FORMAT + " v" + GameShard.FORMAT_VERSION + " file");
        }
        String declared = meta.get("players");
        if (declared == null || !declared.matches("[0-9]+")) {
            throw new IOException("metadata players is " + declared);
        }
        if (Integer.parseInt(declared) != players) {
            throw new IllegalArgumentException("file has " + declared + " players, the buffer " + players);
        }
        try {
            return validated(generation, c);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            throw new IOException("invalid sample file: " + e.getMessage(), e);
        }
    }

    private Block validated(int generation, Contents c) throws IOException {
        short[] ply = c.tensor("ply").i16();
        int n = ply.length;
        byte[] cells = c.tensor("cells").u8();
        short[] limit = c.tensor("move_limit").i16();
        int[] offsets = c.tensor("move_offsets").i32();
        byte[] from = c.tensor("move_from").u8();
        byte[] to = c.tensor("move_to").u8();
        float[] target = c.tensor("move_target").f32();
        float[] value = c.tensor("value_target").f32();
        float[] wp = c.tensor("policy_weight").f32();
        float[] wv = c.tensor("value_weight").f32();
        int moves = from.length;
        int[] seen = new int[HexGrid.CELLS * HexGrid.CELLS]; // sample + 1 of the last sample that used a pair
        if (cells.length != (long) n * HexGrid.CELLS || limit.length != n || offsets.length != n + 1
                || to.length != moves || target.length != moves || value.length != (long) n * players
                || wp.length != n || wv.length != n) {
            throw new IOException("tensor lengths do not fit " + n + " samples");
        }
        if (offsets[0] != 0 || offsets[n] != moves) {
            throw new IOException("move_offsets do not span the " + moves + " moves");
        }
        for (int s = 0; s < n; s++) {
            if (ply[s] < 0 || limit[s] < 1) {
                throw new IOException("sample " + s + ": ply " + ply[s] + ", move_limit " + limit[s]);
            }
            for (int i = 0; i < HexGrid.CELLS; i++) {
                if ((cells[s * HexGrid.CELLS + i] & 0xFF) > players) {
                    throw new IOException("sample " + s + ": cell byte " + (cells[s * HexGrid.CELLS + i] & 0xFF));
                }
            }
            for (int i = 0; i < players; i++) {
                float v = value[s * players + i];
                if (!(v >= -1 && v <= 1)) {
                    throw new IOException("sample " + s + ": value target " + v);
                }
            }
            if (!(wp[s] >= 0) || !Float.isFinite(wp[s]) || !(wv[s] >= 0) || !Float.isFinite(wv[s])) {
                throw new IOException("sample " + s + ": weights " + wp[s] + ", " + wv[s]);
            }
            int first = offsets[s];
            int end = offsets[s + 1];
            if (end < first || end > moves) {
                throw new IOException("sample " + s + ": move_offsets " + first + " to " + end);
            }
            double sum = 0;
            for (int m = first; m < end; m++) {
                int f = from[m] & 0xFF;
                int t = to[m] & 0xFF;
                if (f >= HexGrid.CELLS || t >= HexGrid.CELLS) {
                    throw new IOException("sample " + s + ": move " + f + " to " + t);
                }
                if (!(target[m] >= 0) || !Float.isFinite(target[m])) {
                    throw new IOException("sample " + s + ": move target " + target[m]);
                }
                int pair = f * HexGrid.CELLS + t;
                if (seen[pair] == s + 1) {
                    throw new IOException("sample " + s + " lists the move " + f + " to " + t + " twice");
                }
                seen[pair] = s + 1;
                sum += target[m];
            }
            if (wp[s] > 0 && (first == end || !(sum > 0) || !Double.isFinite(sum))) {
                throw new IOException("sample " + s + " has a policy weight but no usable targets");
            }
        }
        // normalise to sum 1 (a sample without positive targets keeps zeros, its weight is 0)
        float[] normalised = new float[moves];
        double[] entropy = new double[n];
        for (int s = 0; s < n; s++) {
            int first = offsets[s];
            int end = offsets[s + 1];
            double sum = 0;
            for (int m = first; m < end; m++) {
                sum += target[m];
            }
            for (int m = first; m < end; m++) {
                normalised[m] = sum > 0 ? (float) (target[m] / sum) : 0f;
            }
            entropy[s] = ReferenceLoss.entropy(normalised, first, end);
        }
        return new Block(generation, n, cells, ply, limit, offsets, from, to, normalised, value, wp, wv, entropy, null);
    }

    /**
     * Applies the game filter and attaches the game results. Needs the tensors {@code game} (game index of each
     * sample), {@code game_id} and {@code game_values} (label values, absolute players) of the E05 shard layout.
     *
     * @return the block restricted to the accepted games (null if the filter takes none of them), with
     *         {@link Block#result}
     * @throws IllegalArgumentException if a filter is given and a game tensor is missing
     * @throws IOException              if the game tensors do not fit the samples
     */
    private Block withGames(Block block, Contents c, GameFilter filter) throws IOException {
        boolean present = c.tensors().containsKey("game") && c.tensors().containsKey("game_id")
                && c.tensors().containsKey("game_values");
        if (!present) {
            if (filter != null) {
                throw new IllegalArgumentException("a game filter needs the tensors game, game_id and game_values");
            }
            return block;
        }
        int[] game = c.tensor("game").i32();
        int[] id = c.tensor("game_id").i32();
        float[] gameValues = c.tensor("game_values").f32();
        int games = id.length;
        if (game.length != block.size || gameValues.length != (long) players * games) {
            throw new IOException("game tensors do not fit " + block.size + " samples and " + games + " games");
        }
        byte[] result = new byte[block.size];
        boolean[] keep = new boolean[block.size];
        boolean[] accepted = new boolean[games];
        for (int g = 0; g < games; g++) {
            accepted[g] = filter == null || filter.accepts(c.metadata(), id[g]);
        }
        int kept = 0;
        for (int s = 0; s < block.size; s++) {
            if (game[s] < 0 || game[s] >= games) {
                throw new IOException("sample " + s + ": game index " + game[s]);
            }
            keep[s] = accepted[game[s]];
            kept += keep[s] ? 1 : 0;
            if (players == 2) {
                // two players alternate from player 0, so the mover of ply p is p mod 2
                int mover = block.ply[s] & 1;
                double diff = (double) gameValues[2 * game[s] + mover] - gameValues[2 * game[s] + 1 - mover];
                result[s] = (byte) Math.signum(diff);
            }
        }
        if (players != 2) {
            result = null;
        }
        Block withResult = new Block(block.generation, block.size, block.cells, block.ply, block.moveLimit,
                block.moveOffsets, block.moveFrom, block.moveTo, block.moveTarget, block.value, block.policyWeight,
                block.valueWeight, block.entropy, result);
        if (kept == block.size) {
            return withResult;
        }
        return kept == 0 ? null : subset(withResult, keep, kept);
    }

    /** The samples of {@code b} with {@code keep[s]}, in order. */
    private static Block subset(Block b, boolean[] keep, int kept) {
        int pl = b.value.length / b.size;
        byte[] cells = new byte[kept * HexGrid.CELLS];
        short[] ply = new short[kept];
        short[] limit = new short[kept];
        int[] offsets = new int[kept + 1];
        float[] value = new float[kept * pl];
        float[] wp = new float[kept];
        float[] wv = new float[kept];
        double[] entropy = new double[kept];
        byte[] result = b.result == null ? null : new byte[kept];
        int moves = 0;
        for (int s = 0; s < b.size; s++) {
            if (keep[s]) {
                moves += b.moveOffsets[s + 1] - b.moveOffsets[s];
            }
        }
        byte[] from = new byte[moves];
        byte[] to = new byte[moves];
        float[] target = new float[moves];
        int k = 0;
        int m = 0;
        for (int s = 0; s < b.size; s++) {
            if (!keep[s]) {
                continue;
            }
            System.arraycopy(b.cells, s * HexGrid.CELLS, cells, k * HexGrid.CELLS, HexGrid.CELLS);
            ply[k] = b.ply[s];
            limit[k] = b.moveLimit[s];
            int first = b.moveOffsets[s];
            int count = b.moveOffsets[s + 1] - first;
            System.arraycopy(b.moveFrom, first, from, m, count);
            System.arraycopy(b.moveTo, first, to, m, count);
            System.arraycopy(b.moveTarget, first, target, m, count);
            m += count;
            offsets[k + 1] = m;
            System.arraycopy(b.value, s * pl, value, k * pl, pl);
            wp[k] = b.policyWeight[s];
            wv[k] = b.valueWeight[s];
            entropy[k] = b.entropy[s];
            if (result != null) {
                result[k] = b.result[s];
            }
            k++;
        }
        return new Block(b.generation, kept, cells, ply, limit, offsets, from, to, target, value, wp, wv, entropy,
                result);
    }

    @Override
    public String toString() {
        return "ReplayBuffer[players=" + players + ", window=" + window + ", samples=" + size() + ", generations="
                + generations() + "]";
    }
}
