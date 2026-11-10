package sternhalma.tornado;

import sternhalma.nn.HexGrid;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * TornadoVM kernels of the hex convolution backward pass (docs/training.md, Part B kernels 6 to 9, and "Hex
 * convolution" of Part A). No kernel uses an atomic add: every output element has one writer.
 *
 * <ul>
 * <li>The input gradient is the <em>forward</em> hex convolution kernel of {@link KernelsHexConv} applied to dz
 * with the permuted weight W' that {@link #permuteWeight} builds (the gather form of the document); the trainer
 * adds that task with {@code KernelsHexConvLayer.addTask}.</li>
 * <li>The weight and bias gradient is a split-K sum: {@link #weightPartial} gives every chunk of rows its own
 * partial sums, {@link #weightReduce} adds the chunks in ascending order.</li>
 * <li>{@link #maskGradient} applies the ReLU rule {@code dz = dOut [out > 0]} with the stored activation.</li>
 * </ul>
 */
public final class KernelsConvBackward {

    /** Rows of one activation: 121 cells and the padding row. */
    static final int ROWS = HexGrid.ROWS;
    /** Cells of the board. */
    static final int CELLS = HexGrid.CELLS;
    /** Gather entries per cell. */
    static final int STENCIL = HexGrid.STENCIL;

    /** Output channels and columns per work group of {@link #weightPartialSimdgroup}. */
    public static final int WG_TILE = 32;
    /** Rows per step of the row loop of {@link #weightPartialSimdgroup}. */
    public static final int WG_TILE_R = 32;
    /** Work items per work group of {@link #weightPartialSimdgroup}: 4 SIMD groups of 32. */
    public static final int WG_GROUP_SIZE = 128;

    private KernelsConvBackward() {
    }

    /**
     * The ReLU backward rule in place: {@code grad[i] = act[i] > 0 ? grad[i] : 0}, with the stored output of the
     * ReLU. NaN in {@code act} gives 0, as in {@code ReferenceBackward.reluBackward}. The padding rows of both
     * arrays are 0, so they stay 0.
     */
    public static void maskGradient(FloatArray grad, FloatArray act, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            float g = grad.get(i);
            grad.set(i, act.get(i) > 0f ? g : 0f);
        }
    }

    /**
     * {@link #maskGradient} into another array: {@code out[i] = act[i] > 0 ? in[i] : 0}. {@code out} must not be
     * {@code in}: the first mask of a backward pass must not destroy dx_B.
     */
    public static void maskInto(FloatArray in, FloatArray act, FloatArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            out.set(i, act.get(i) > 0f ? in.get(i) : 0f);
        }
    }

    /**
     * Kernel 6: the permuted and transposed weight of docs/training.md, "Hex convolution":
     * {@code wPrime[ch][k' * outChannels + o] = w[o][pi(k') * inChannels + ch]} with
     * {@code pi = (0, 2, 1, 4, 3, 6, 5)}. The forward convolution of dz with {@code wPrime} (zero bias, channels
     * swapped) is the input gradient of the convolution with the weight {@code w}.
     *
     * @param w      [outChannels][7 * inChannels]
     * @param wPrime [inChannels][7 * outChannels], written
     */
    public static void permuteWeight(FloatArray w, FloatArray wPrime, int inChannels, int outChannels) {
        int width = STENCIL * outChannels;
        for (@Parallel int i = 0; i < inChannels * width; i++) {
            int ch = i / width;
            int rest = i - ch * width;
            int kPrime = rest / outChannels;
            int o = rest - kPrime * outChannels;
            int k = 0;
            if (kPrime > 0) {
                k = ((kPrime - 1) ^ 1) + 1;
            }
            wPrime.set(i, w.get(o * STENCIL * inChannels + k * inChannels + ch));
        }
    }

    /**
     * Kernel 7: split-K partial sums of the weight and bias gradient. The {@code rows} (batch times 122) are cut
     * into {@code splits} consecutive chunks of {@code ceil(rows / splits)}; work item {@code (chunk, o, j)} sums
     * over the cell rows of its chunk {@code dz[r][o] * in[gather(cell, k)][ch]} for {@code j = k * inChannels + ch},
     * and {@code dz[r][o]} for the bias column {@code j = 7 * inChannels}. Padding rows are skipped. Within an
     * item the rows are added in ascending order.
     *
     * @param dz      [rows][outChannels], the gradient of the pre-activation
     * @param in      [batch][122][inChannels], the input of the convolution
     * @param partial [splits][outChannels][7 * inChannels + 1], written completely
     */
    public static void weightPartial(FloatArray dz, FloatArray in, IntArray gather, FloatArray partial, int rows,
            int inChannels, int outChannels, int splits) {
        int width = STENCIL * inChannels + 1;
        int per = (rows + splits - 1) / splits;
        for (@Parallel int i = 0; i < splits * outChannels * width; i++) {
            int chunk = i / (outChannels * width);
            int rest = i - chunk * outChannels * width;
            int o = rest / width;
            int j = rest - o * width;
            int k = j / inChannels;
            int ch = j - k * inChannels;
            int last = (chunk + 1) * per;
            if (last > rows) {
                last = rows;
            }
            float sum = 0f;
            for (int r = chunk * per; r < last; r++) {
                int cell = r % ROWS;
                if (cell < CELLS) {
                    float g = dz.get(r * outChannels + o);
                    if (k < STENCIL) {
                        int source = r - cell + gather.get(cell * STENCIL + k);
                        sum += g * in.get(source * inChannels + ch);
                    } else {
                        sum += g;
                    }
                }
            }
            partial.set(i, sum);
        }
    }

    /**
     * Kernel 7 on the Metal SIMD-group matrix units: the same partial sums as {@link #weightPartial} for the
     * weight columns and the bias column. The product
     * {@code partial[o][j] = sum over rows r of dz[r][o] A[r][j]} with {@code A} the gathered input is computed per
     * work group for a tile of {@link #WG_TILE} output channels x {@link #WG_TILE} columns of one chunk: per step of
     * {@link #WG_TILE_R} rows the group gathers its slice of dz and of {@code A} into local memory, and each SIMD
     * group multiplies 2 x 2 blocks of 8 x 8 (the dz block is read transposed). Rows run in ascending order per
     * chunk, as in {@link #weightPartial}; the order inside the matrix unit is not documented. The groups of the
     * first column tile also add the bias column: every item sums the dz values it loaded, four items per channel,
     * and the four sums are added in a fixed order.
     *
     * <p>Launch it with {@link #weightGrid}.
     */
    public static void weightPartialSimdgroup(KernelContext context, FloatArray dz, FloatArray in, IntArray gather,
            FloatArray partial, int rows, int inChannels, int outChannels, int splits) {
        float[] dzTile = context.allocateFloatLocalArray(WG_TILE_R * WG_TILE);
        float[] aTile = context.allocateFloatLocalArray(WG_TILE_R * WG_TILE);
        float[] cTile = context.allocateFloatLocalArray(WG_TILE * WG_TILE);
        float[] biasTile = context.allocateFloatLocalArray(WG_GROUP_SIZE);
        int columns = STENCIL * inChannels;
        int width = columns + 1;
        int groupsJ = (columns + WG_TILE - 1) / WG_TILE;
        int groupsO = (outChannels + WG_TILE - 1) / WG_TILE;
        int group = context.groupIdx;
        int chunk = group / (groupsO * groupsJ);
        int rest = group - chunk * groupsO * groupsJ;
        int o0 = (rest / groupsJ) * WG_TILE;
        int j0 = (rest % groupsJ) * WG_TILE;
        int per = (rows + splits - 1) / splits;
        int first = chunk * per;
        int last = first + per;
        if (last > rows) {
            last = rows;
        }
        int item = context.localIdx;
        int simd = item / 32;
        // SIMD group (so, sj) owns channels 16 so .. 16 so + 15 and columns 16 sj .. 16 sj + 15 of the tile.
        int so = simd / 2;
        int sj = simd % 2;

        var c00 = context.simdgroupMatrixZero();
        var c01 = context.simdgroupMatrixZero();
        var c10 = context.simdgroupMatrixZero();
        var c11 = context.simdgroupMatrixZero();
        float bias = 0f;

        for (int r0 = first; r0 < last; r0 += WG_TILE_R) {
            for (int i = item; i < WG_TILE_R * WG_TILE; i += WG_GROUP_SIZE) {
                int rr = i / WG_TILE;
                int col = i - rr * WG_TILE;
                int r = r0 + rr;
                float g = 0f;
                float a = 0f;
                if (r < last) {
                    int cell = r % ROWS;
                    if (cell < CELLS) {
                        if (o0 + col < outChannels) {
                            g = dz.get(r * outChannels + o0 + col);
                        }
                        int j = j0 + col;
                        if (j < columns) {
                            int k = j / inChannels;
                            int ch = j - k * inChannels;
                            a = in.get((r - cell + gather.get(cell * STENCIL + k)) * inChannels + ch);
                        }
                    }
                }
                dzTile[i] = g;
                aTile[i] = a;
                bias += g;
            }
            context.localBarrier();

            for (int r8 = 0; r8 < WG_TILE_R; r8 += 8) {
                var d0 = context.simdgroupMatrixLoadTransposed(dzTile, r8 * WG_TILE + so * 16, WG_TILE);
                var d1 = context.simdgroupMatrixLoadTransposed(dzTile, r8 * WG_TILE + so * 16 + 8, WG_TILE);
                var b0 = context.simdgroupMatrixLoad(aTile, r8 * WG_TILE + sj * 16, WG_TILE);
                var b1 = context.simdgroupMatrixLoad(aTile, r8 * WG_TILE + sj * 16 + 8, WG_TILE);
                c00 = context.simdgroupMatrixMultiplyAccumulate(d0, b0, c00);
                c01 = context.simdgroupMatrixMultiplyAccumulate(d0, b1, c01);
                c10 = context.simdgroupMatrixMultiplyAccumulate(d1, b0, c10);
                c11 = context.simdgroupMatrixMultiplyAccumulate(d1, b1, c11);
            }
            context.localBarrier();
        }

        biasTile[item] = bias;
        context.simdgroupMatrixStore(c00, cTile, (so * 16) * WG_TILE + sj * 16, WG_TILE);
        context.simdgroupMatrixStore(c01, cTile, (so * 16) * WG_TILE + sj * 16 + 8, WG_TILE);
        context.simdgroupMatrixStore(c10, cTile, (so * 16 + 8) * WG_TILE + sj * 16, WG_TILE);
        context.simdgroupMatrixStore(c11, cTile, (so * 16 + 8) * WG_TILE + sj * 16 + 8, WG_TILE);
        context.localBarrier();

        for (int i = item; i < WG_TILE * WG_TILE; i += WG_GROUP_SIZE) {
            int o = o0 + i / WG_TILE;
            int j = j0 + i % WG_TILE;
            if (o < outChannels && j < columns) {
                partial.set((chunk * outChannels + o) * width + j, cTile[i]);
            }
        }
        // The bias column: item c < 32 adds the sums of the items c, c + 32, c + 64 and c + 96, which loaded channel c.
        if (j0 == 0 && item < WG_TILE && o0 + item < outChannels) {
            float sum = biasTile[item] + biasTile[item + 32] + biasTile[item + 64] + biasTile[item + 96];
            partial.set((chunk * outChannels + o0 + item) * width + columns, sum);
        }
    }

    /**
     * The launch grid of {@link #weightPartialSimdgroup}: one work group of {@link #WG_GROUP_SIZE} items per
     * (chunk, tile of channels, tile of columns), in one dimension.
     */
    public static WorkerGrid weightGrid(int inChannels, int outChannels, int splits) {
        int groupsJ = (STENCIL * inChannels + WG_TILE - 1) / WG_TILE;
        int groupsO = (outChannels + WG_TILE - 1) / WG_TILE;
        WorkerGrid grid = new WorkerGrid1D(splits * groupsO * groupsJ * WG_GROUP_SIZE);
        grid.setLocalWork(WG_GROUP_SIZE, 1, 1);
        return grid;
    }

    /**
     * Kernel 9: adds the chunks of {@link #weightPartial} in ascending order into {@code dW} [outChannels][7
     * inChannels] and {@code db} [outChannels]. With {@code control[0] > 0.5} the sums are added to the gradients
     * that are there (the next microbatch of a batch), otherwise they replace them.
     */
    public static void weightReduce(FloatArray partial, FloatArray dW, FloatArray db, FloatArray control,
            int inChannels, int outChannels, int splits) {
        int width = STENCIL * inChannels + 1;
        for (@Parallel int i = 0; i < outChannels * width; i++) {
            float sum = 0f;
            for (int chunk = 0; chunk < splits; chunk++) {
                sum += partial.get(chunk * outChannels * width + i);
            }
            int o = i / width;
            int j = i - o * width;
            if (j < STENCIL * inChannels) {
                int at = o * STENCIL * inChannels + j;
                dW.set(at, KernelsTrain.accumulate(control, dW.get(at), sum));
            } else {
                db.set(o, KernelsTrain.accumulate(control, db.get(o), sum));
            }
        }
    }
}
