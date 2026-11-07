package sternhalma.tornado;

import sternhalma.nn.HexGrid;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.WorkerGrid2D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * TornadoVM kernels of the hex convolution of docs/network.md ("Gather table", "Layers"): a gather of each cell
 * and its 6 neighbours, a matrix multiplication with the weight, the bias, optionally the residual add, optionally
 * ReLU.
 *
 * <p>The three kernels ({@link #naive}, {@link #tiled}, {@link #simdgroup}) compute, for a batch of
 * {@code batch} activations [122][inChannels] (channels last, row 121 the zero padding row), for every position
 * b, cell c &lt; 121 and output channel o:
 *
 * <pre>
 * v = bias[o] + sum over k in 0..6, ch of weight[o][k * inChannels + ch] * in[b][gather[c][k]][ch]
 * if residual != 0: v += res[b][c][o]
 * if relu != 0:     v = max(v, 0)     (NaN stays NaN, as in ReferenceOps.relu)
 * out[b][c][o] = v,      out[b][121][o] = 0
 * </pre>
 *
 * Every element of {@code out} is written, the padding row included, so the output buffer needs no clearing.
 * The kernels read row 121 of {@code in} through the gather table and therefore rely on it being zero, as every
 * layer guarantees. {@code res} is read only if {@code residual != 0}; it may be any array otherwise. {@code in},
 * {@code res} and {@code out} must not overlap. Sums run in float, so results agree with
 * {@link sternhalma.nn.ReferenceOps} only within the tolerance of docs/network.md. {@link #naive} and
 * {@link #tiled} add the products of an output in ascending column order and the bias last; the order inside
 * the SIMD-group matrix unit is not documented. On the Metal device (2026-11-07) all three gave bit-identical
 * results, but nothing guarantees that.
 *
 * <p>The gather table is {@link HexGrid#gatherTable()} uploaded as an {@link IntArray}. The kernels index it
 * without bounds checks, as the device does not check bounds (docs/tornado.md, "Pitfalls").
 *
 * <p>Which kernel is fastest depends on the batch (docs/tornado.md, "Convolution kernels"): {@link #naive} at
 * batch 1, {@link #simdgroup} or {@link #tiled} from batch 64 on. {@link #naive} also runs as plain Java when
 * called directly; the other two need the work-group grid of a device.
 */
public final class KernelsHexConv {

    /** Rows of one activation: 121 cells and the padding row. */
    static final int ROWS = HexGrid.ROWS;
    /** Gather entries per cell. */
    static final int STENCIL = HexGrid.STENCIL;

    /** Output rows (batch * 122 rows flattened) per work group of {@link #tiled}. */
    public static final int TILE_M = 64;
    /** Output channels per work group of {@link #tiled}. */
    public static final int TILE_N = 32;
    /** Gathered inputs per step of the k loop of {@link #tiled}. */
    public static final int TILE_K = 32;
    /** Rows per work item of {@link #tiled}. */
    static final int MICRO_M = 4;
    /** Output channels per work item of {@link #tiled}. */
    static final int MICRO_N = 4;
    /** Work-group width of {@link #tiled} (output channel direction). */
    public static final int GROUP_X = TILE_N / MICRO_N;
    /** Work-group height of {@link #tiled} (row direction). */
    public static final int GROUP_Y = TILE_M / MICRO_M;
    /** Work items per work group of {@link #tiled}. */
    static final int GROUP_SIZE = GROUP_X * GROUP_Y;
    /** Row stride of the local A tile [TILE_K][TILE_M + 1]; the extra column avoids bank conflicts on stores. */
    static final int A_STRIDE = TILE_M + 1;
    /** Row stride of the local weight tile [TILE_K][TILE_N + 1]. */
    static final int W_STRIDE = TILE_N + 1;

    /** Rows and output channels per work group of {@link #simdgroup}. */
    public static final int SG_TILE = 32;
    /** Gathered inputs per step of the k loop of {@link #simdgroup}. */
    public static final int SG_TILE_K = 32;
    /** Work items per work group of {@link #simdgroup}: 4 SIMD groups of 32, arranged 2 x 2 over the tile. */
    public static final int SG_GROUP_SIZE = 128;

    private KernelsHexConv() {
    }

    /**
     * The straightforward kernel: one work item per output element (row of the batch, output channel); the inner
     * sum runs over k, then ch, in ascending order, and adds the bias at the end.
     *
     * @param in          [batch][122][inChannels]
     * @param gather      the gather table, [121][7]
     * @param weight      [outChannels][7 * inChannels]
     * @param bias        [outChannels]
     * @param res         [batch][122][outChannels] if {@code residual != 0}
     * @param out         [batch][122][outChannels], written completely
     * @param residual    1 to add {@code res}, 0 not to
     * @param relu        1 to apply ReLU, 0 not to
     */
    public static void naive(FloatArray in, IntArray gather, FloatArray weight, FloatArray bias, FloatArray res,
            FloatArray out, int batch, int inChannels, int outChannels, int residual, int relu) {
        int width = STENCIL * inChannels;
        for (@Parallel int r = 0; r < batch * ROWS; r++) {
            for (@Parallel int o = 0; o < outChannels; o++) {
                int cell = r % ROWS;
                float v = 0f;
                if (cell < HexGrid.CELLS) {
                    int positionBase = (r - cell) * inChannels;
                    float sum = 0f;
                    for (int k = 0; k < STENCIL; k++) {
                        int rowBase = positionBase + gather.get(cell * STENCIL + k) * inChannels;
                        int weightBase = o * width + k * inChannels;
                        for (int ch = 0; ch < inChannels; ch++) {
                            sum += weight.get(weightBase + ch) * in.get(rowBase + ch);
                        }
                    }
                    v = sum + bias.get(o);
                    if (residual != 0) {
                        v += res.get(r * outChannels + o);
                    }
                    if (relu != 0) {
                        v = DeviceMath.relu(v);
                    }
                }
                out.set(r * outChannels + o, v);
            }
        }
    }

    /**
     * The tiled kernel, the same contract as {@link #naive}. It treats the convolution as the matrix product
     * {@code out[m][o] = sum_j A[m][j] * weight[o][j]} with m a row of the batch (batch * 122 rows) and A the
     * gathered input (7 * inChannels columns). A work group of {@link #GROUP_X} x {@link #GROUP_Y} items computes
     * a tile of {@link #TILE_M} rows x {@link #TILE_N} channels. Per step of {@link #TILE_K} columns it gathers its
     * slice of A and of the weight into local memory, then every item accumulates {@link #MICRO_M} x
     * {@link #MICRO_N} outputs in registers; the items of a tile are interleaved (row {@code ty + GROUP_Y * i},
     * channel {@code tx + GROUP_X * j}) so that the local reads of a SIMD group do not collide in a bank. Rows,
     * channels and columns beyond the shape are masked, so no size needs to be a multiple of a tile.
     *
     * <p>Launch it with {@link #tiledGrid(int, int)}.
     */
    public static void tiled(KernelContext context, FloatArray in, IntArray gather, FloatArray weight,
            FloatArray bias, FloatArray res, FloatArray out, int batch, int inChannels, int outChannels,
            int residual, int relu) {
        float[] aTile = context.allocateFloatLocalArray(TILE_K * A_STRIDE);
        float[] wTile = context.allocateFloatLocalArray(TILE_K * W_STRIDE);
        int tx = context.localIdx;
        int ty = context.localIdy;
        int n0 = context.groupIdx * TILE_N;
        int m0 = context.groupIdy * TILE_M;
        int item = ty * GROUP_X + tx;
        int rows = batch * ROWS;
        int width = STENCIL * inChannels;

        float c00 = 0f;
        float c01 = 0f;
        float c02 = 0f;
        float c03 = 0f;
        float c10 = 0f;
        float c11 = 0f;
        float c12 = 0f;
        float c13 = 0f;
        float c20 = 0f;
        float c21 = 0f;
        float c22 = 0f;
        float c23 = 0f;
        float c30 = 0f;
        float c31 = 0f;
        float c32 = 0f;
        float c33 = 0f;

        for (int k0 = 0; k0 < width; k0 += TILE_K) {
            // Gather the A slice [TILE_M][TILE_K]; consecutive items read consecutive columns (channels) of a row.
            for (int i = item; i < TILE_M * TILE_K; i += GROUP_SIZE) {
                int kk = i % TILE_K;
                int mm = i / TILE_K;
                int m = m0 + mm;
                int j = k0 + kk;
                float a = 0f;
                if (m < rows && j < width) {
                    int cell = m % ROWS;
                    if (cell < HexGrid.CELLS) {
                        int k = j / inChannels;
                        int ch = j - k * inChannels;
                        a = in.get((m - cell + gather.get(cell * STENCIL + k)) * inChannels + ch);
                    }
                }
                aTile[kk * A_STRIDE + mm] = a;
            }
            // The weight slice [TILE_N][TILE_K]; consecutive items read consecutive columns of a weight row.
            for (int i = item; i < TILE_N * TILE_K; i += GROUP_SIZE) {
                int kk = i % TILE_K;
                int nn = i / TILE_K;
                int n = n0 + nn;
                int j = k0 + kk;
                float w = 0f;
                if (n < outChannels && j < width) {
                    w = weight.get(n * width + j);
                }
                wTile[kk * W_STRIDE + nn] = w;
            }
            context.localBarrier();

            for (int kk = 0; kk < TILE_K; kk++) {
                int a = kk * A_STRIDE + ty;
                float a0 = aTile[a];
                float a1 = aTile[a + GROUP_Y];
                float a2 = aTile[a + 2 * GROUP_Y];
                float a3 = aTile[a + 3 * GROUP_Y];
                int w = kk * W_STRIDE + tx;
                float w0 = wTile[w];
                float w1 = wTile[w + GROUP_X];
                float w2 = wTile[w + 2 * GROUP_X];
                float w3 = wTile[w + 3 * GROUP_X];
                c00 += a0 * w0;
                c01 += a0 * w1;
                c02 += a0 * w2;
                c03 += a0 * w3;
                c10 += a1 * w0;
                c11 += a1 * w1;
                c12 += a1 * w2;
                c13 += a1 * w3;
                c20 += a2 * w0;
                c21 += a2 * w1;
                c22 += a2 * w2;
                c23 += a2 * w3;
                c30 += a3 * w0;
                c31 += a3 * w1;
                c32 += a3 * w2;
                c33 += a3 * w3;
            }
            context.localBarrier();
        }

        int m = m0 + ty;
        store(c00, c01, c02, c03, m, n0 + tx, rows, bias, res, out, outChannels, residual, relu);
        store(c10, c11, c12, c13, m + GROUP_Y, n0 + tx, rows, bias, res, out, outChannels, residual, relu);
        store(c20, c21, c22, c23, m + 2 * GROUP_Y, n0 + tx, rows, bias, res, out, outChannels, residual, relu);
        store(c30, c31, c32, c33, m + 3 * GROUP_Y, n0 + tx, rows, bias, res, out, outChannels, residual, relu);
    }

    /** Stores the four channels {@code n, n + GROUP_X, ...} of row m of the tiled kernel's micro tile. */
    private static void store(float s0, float s1, float s2, float s3, int m, int n, int rows, FloatArray bias,
            FloatArray res, FloatArray out, int outChannels, int residual, int relu) {
        if (m < rows) {
            boolean padding = m % ROWS == HexGrid.PADDING;
            storeOne(s0, m, n, padding, bias, res, out, outChannels, residual, relu);
            storeOne(s1, m, n + GROUP_X, padding, bias, res, out, outChannels, residual, relu);
            storeOne(s2, m, n + 2 * GROUP_X, padding, bias, res, out, outChannels, residual, relu);
            storeOne(s3, m, n + 3 * GROUP_X, padding, bias, res, out, outChannels, residual, relu);
        }
    }

    private static void storeOne(float sum, int m, int n, boolean padding, FloatArray bias, FloatArray res,
            FloatArray out, int outChannels, int residual, int relu) {
        if (n < outChannels) {
            float v = 0f;
            if (!padding) {
                v = sum + bias.get(n);
                if (residual != 0) {
                    v += res.get(m * outChannels + n);
                }
                if (relu != 0) {
                    v = DeviceMath.relu(v);
                }
            }
            out.set(m * outChannels + n, v);
        }
    }

    /**
     * The kernel on the Metal SIMD-group matrix units, the same contract as {@link #naive}. The product
     * {@code out[m][o] = sum_j A[m][j] * weight[o][j]} is split into tiles of {@link #SG_TILE} rows x
     * {@link #SG_TILE} channels per work group of {@link #SG_GROUP_SIZE} items (4 SIMD groups of 32). Per step of
     * {@link #SG_TILE_K} columns the group gathers its slice of A ([m][j]) and of the weight ([o][j]) into local
     * memory, row-major and contiguous, so that consecutive items write consecutive words. Each SIMD group then
     * multiplies 2 x 2 blocks of 8 x 8 ({@code simdgroupMatrixMultiplyAccumulate}), reading the weight blocks
     * transposed. At the end each SIMD group stores its blocks to a local tile, and the items apply bias,
     * residual and ReLU and write the masked tile to {@code out}.
     *
     * <p>Launch it with {@link #simdgroupGrid(int, int)}. Only the Metal backend implements the SIMD-group matrix
     * operations.
     */
    public static void simdgroup(KernelContext context, FloatArray in, IntArray gather, FloatArray weight,
            FloatArray bias, FloatArray res, FloatArray out, int batch, int inChannels, int outChannels,
            int residual, int relu) {
        float[] aTile = context.allocateFloatLocalArray(SG_TILE * SG_TILE_K);
        float[] wTile = context.allocateFloatLocalArray(SG_TILE * SG_TILE_K);
        float[] cTile = context.allocateFloatLocalArray(SG_TILE * SG_TILE);
        int rows = batch * ROWS;
        int width = STENCIL * inChannels;
        int groupsN = (outChannels + SG_TILE - 1) / SG_TILE;
        int group = context.groupIdx;
        int m0 = (group / groupsN) * SG_TILE;
        int n0 = (group % groupsN) * SG_TILE;
        int item = context.localIdx;
        int simd = item / 32;
        // SIMD group (sm, sn) owns rows 16 sm .. 16 sm + 15 and channels 16 sn .. 16 sn + 15 of the tile.
        int sm = simd / 2;
        int sn = simd % 2;

        var c00 = context.simdgroupMatrixZero();
        var c01 = context.simdgroupMatrixZero();
        var c10 = context.simdgroupMatrixZero();
        var c11 = context.simdgroupMatrixZero();

        for (int k0 = 0; k0 < width; k0 += SG_TILE_K) {
            for (int i = item; i < SG_TILE * SG_TILE_K; i += SG_GROUP_SIZE) {
                int kk = i % SG_TILE_K;
                int m = m0 + i / SG_TILE_K;
                int j = k0 + kk;
                float a = 0f;
                if (m < rows && j < width) {
                    int cell = m % ROWS;
                    if (cell < HexGrid.CELLS) {
                        int k = j / inChannels;
                        int ch = j - k * inChannels;
                        a = in.get((m - cell + gather.get(cell * STENCIL + k)) * inChannels + ch);
                    }
                }
                aTile[i] = a;
            }
            for (int i = item; i < SG_TILE * SG_TILE_K; i += SG_GROUP_SIZE) {
                int n = n0 + i / SG_TILE_K;
                int j = k0 + i % SG_TILE_K;
                float w = 0f;
                if (n < outChannels && j < width) {
                    w = weight.get(n * width + j);
                }
                wTile[i] = w;
            }
            context.localBarrier();

            for (int k8 = 0; k8 < SG_TILE_K; k8 += 8) {
                var a0 = context.simdgroupMatrixLoad(aTile, (sm * 16) * SG_TILE_K + k8, SG_TILE_K);
                var a1 = context.simdgroupMatrixLoad(aTile, (sm * 16 + 8) * SG_TILE_K + k8, SG_TILE_K);
                var b0 = context.simdgroupMatrixLoadTransposed(wTile, (sn * 16) * SG_TILE_K + k8, SG_TILE_K);
                var b1 = context.simdgroupMatrixLoadTransposed(wTile, (sn * 16 + 8) * SG_TILE_K + k8, SG_TILE_K);
                c00 = context.simdgroupMatrixMultiplyAccumulate(a0, b0, c00);
                c01 = context.simdgroupMatrixMultiplyAccumulate(a0, b1, c01);
                c10 = context.simdgroupMatrixMultiplyAccumulate(a1, b0, c10);
                c11 = context.simdgroupMatrixMultiplyAccumulate(a1, b1, c11);
            }
            context.localBarrier();
        }

        context.simdgroupMatrixStore(c00, cTile, (sm * 16) * SG_TILE + sn * 16, SG_TILE);
        context.simdgroupMatrixStore(c01, cTile, (sm * 16) * SG_TILE + sn * 16 + 8, SG_TILE);
        context.simdgroupMatrixStore(c10, cTile, (sm * 16 + 8) * SG_TILE + sn * 16, SG_TILE);
        context.simdgroupMatrixStore(c11, cTile, (sm * 16 + 8) * SG_TILE + sn * 16 + 8, SG_TILE);
        context.localBarrier();

        for (int i = item; i < SG_TILE * SG_TILE; i += SG_GROUP_SIZE) {
            int m = m0 + i / SG_TILE;
            int n = n0 + i % SG_TILE;
            if (m < rows) {
                storeOne(cTile[i], m, n, m % ROWS == HexGrid.PADDING, bias, res, out, outChannels, residual, relu);
            }
        }
    }

    /**
     * The launch grid of {@link #simdgroup}: one work group of {@link #SG_GROUP_SIZE} items per tile of
     * {@link #SG_TILE} rows x {@link #SG_TILE} channels, rounded up, in one dimension.
     */
    public static WorkerGrid simdgroupGrid(int batch, int outChannels) {
        int groupsN = (outChannels + SG_TILE - 1) / SG_TILE;
        int groupsM = (batch * ROWS + SG_TILE - 1) / SG_TILE;
        WorkerGrid grid = new WorkerGrid1D(groupsN * groupsM * SG_GROUP_SIZE);
        grid.setLocalWork(SG_GROUP_SIZE, 1, 1);
        return grid;
    }

    /**
     * The launch grid of {@link #tiled}: one work group of {@link #GROUP_X} x {@link #GROUP_Y} items per tile of
     * {@link #TILE_N} output channels x {@link #TILE_M} rows, rounded up.
     */
    public static WorkerGrid tiledGrid(int batch, int outChannels) {
        int groupsX = (outChannels + TILE_N - 1) / TILE_N;
        int groupsY = (batch * ROWS + TILE_M - 1) / TILE_M;
        WorkerGrid grid = new WorkerGrid2D(groupsX * GROUP_X, groupsY * GROUP_Y);
        grid.setLocalWork(GROUP_X, GROUP_Y, 1);
        return grid;
    }

    /** Multiply-adds of one call: {@code batch * 121 * outChannels * 7 * inChannels}. */
    public static long multiplyAdds(int batch, int inChannels, int outChannels) {
        return (long) batch * HexGrid.CELLS * outChannels * STENCIL * inChannels;
    }
}
