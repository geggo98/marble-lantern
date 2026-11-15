// MSL kernels of the Metal backend, compiled at run time from this source (newLibraryWithSource:options:error:).
// Port of the TornadoVM kernels of nn-tornado (KernelsHexConv.naive, OpsKernels.policyHead and valueHead, DeviceMath).
// WP11a: naive convolution and the two heads; WP11b: tiled and simdgroup convolution.
// The device has no bounds checks (docs/cpp-comparison-plan.md, "Unsafe zones"): every shape is tested with NaN-filled outputs.
#include <metal_stdlib>
using namespace metal;

constant int kRows = 122;      // HexGrid.ROWS
constant int kCells = 121;     // HexGrid.CELLS, also the index of the padding row
constant int kStencil = 7;     // HexGrid.STENCIL
constant int kPolicySize = 242;
constant int kValueGroup = 256;  // OpsKernels.VALUE_GROUP
constant int kMaxWidth = 1024;   // OpsKernels.MAX_WIDTH

// Mirrored by the host structs in metal_net.mm (five and six ints).
struct ConvArgs {
    int batch;
    int in_channels;
    int out_channels;
    int residual;
    int relu;
};

struct HeadArgs {
    int batch;
    int channels;
    int hidden;
    int players;
    int apply_tanh;
    int out_offset;
};

// DeviceMath.relu: keeps NaN (fmax would drop it).
static inline float relu_keep_nan(float v) { return v < 0.0f ? 0.0f : v; }

// DeviceMath.tanh: exactly +-1 above 8.5, NaN stays NaN (the comparisons fail for NaN, which reaches tanh).
static inline float tanh_saturating(float x) { return x > 8.5f ? 1.0f : (x < -8.5f ? -1.0f : tanh(x)); }

constexpr constant int kTileM = 64;   // KernelsHexConv.TILE_M
constexpr constant int kTileN = 32;   // TILE_N
constexpr constant int kTileK = 32;   // TILE_K
constexpr constant int kGroupX = 8;   // GROUP_X = TILE_N / MICRO_N
constexpr constant int kGroupY = 16;  // GROUP_Y = TILE_M / MICRO_M
constexpr constant int kGroupSize = kGroupX * kGroupY;
constexpr constant int kAStride = kTileM + 1;  // one extra column against bank conflicts
constexpr constant int kWStride = kTileN + 1;
constexpr constant int kSgTile = 32;       // SG_TILE
constexpr constant int kSgTileK = 32;      // SG_TILE_K
constexpr constant int kSgGroupSize = 128; // SG_GROUP_SIZE

// KernelsHexConv.storeOne: bias, optional residual, optional ReLU; the padding row is written as 0; n beyond the shape is masked.
static inline void store_one(float sum, int m, int n, bool padding, device const float* bias, device const float* res, device float* out,
                             constant ConvArgs& a) {
    if (n < a.out_channels) {
        float v = 0.0f;
        if (!padding) {
            v = sum + bias[n];
            if (a.residual != 0) v += res[m * a.out_channels + n];
            if (a.relu != 0) v = relu_keep_nan(v);
        }
        out[m * a.out_channels + n] = v;
    }
}

// KernelsHexConv.naive: one thread per (row of the batch, output channel); sum over k, then ch, ascending; bias last;
// optional residual; optional ReLU; the padding row is written as 0.
kernel void conv_naive(device const float* in [[buffer(0)]], device const int* gather [[buffer(1)]],
                       device const float* weight [[buffer(2)]], device const float* bias [[buffer(3)]],
                       device const float* res [[buffer(4)]], device float* out [[buffer(5)]],
                       constant ConvArgs& a [[buffer(6)]], uint2 gid [[thread_position_in_grid]]) {
    const int o = int(gid.x);
    const int r = int(gid.y);
    if (o >= a.out_channels || r >= a.batch * kRows) return;
    const int width = kStencil * a.in_channels;
    const int cell = r % kRows;
    float v = 0.0f;
    if (cell < kCells) {
        const int position_base = (r - cell) * a.in_channels;
        float sum = 0.0f;
        for (int k = 0; k < kStencil; k++) {
            const int row_base = position_base + gather[cell * kStencil + k] * a.in_channels;
            const int weight_base = o * width + k * a.in_channels;
            for (int ch = 0; ch < a.in_channels; ch++) {
                sum += weight[weight_base + ch] * in[row_base + ch];
            }
        }
        v = sum + bias[o];
        if (a.residual != 0) v += res[r * a.out_channels + o];
        if (a.relu != 0) v = relu_keep_nan(v);
    }
    out[r * a.out_channels + o] = v;
}

// OpsKernels.policyHead: one thread per (position, logit); the padding row of the trunk is not read.
kernel void policy_head(device const float* trunk [[buffer(0)]], device const float* weight [[buffer(1)]],
                        device const float* bias [[buffer(2)]], device float* out [[buffer(3)]],
                        constant HeadArgs& a [[buffer(4)]], uint2 gid [[thread_position_in_grid]]) {
    const int j = int(gid.x);
    const int b = int(gid.y);
    if (j >= kPolicySize || b >= a.batch) return;
    const int cell = j / 2;
    const int k = j - cell * 2;
    const int in = b * kRows * a.channels + cell * a.channels;
    const int w = k * a.channels;
    float sum = bias[k];
    for (int ch = 0; ch < a.channels; ch++) {
        sum += weight[w + ch] * trunk[in + ch];
    }
    out[b * kPolicySize + j] = sum;
}

// OpsKernels.valueHead: one thread group of 256 per position. Mean over the 121 cells with partial sums (parts = 256 / C
// threads share a channel when C < 256), dense C -> H with ReLU, dense H -> P with tanh (only where apply_tanh != 0).
kernel void value_head(device const float* trunk [[buffer(0)]], device const float* w1 [[buffer(1)]],
                       device const float* b1 [[buffer(2)]], device const float* w2 [[buffer(3)]],
                       device const float* b2 [[buffer(4)]], device float* out [[buffer(5)]],
                       constant HeadArgs& a [[buffer(6)]], uint thread_in_group [[thread_position_in_threadgroup]],
                       uint group [[threadgroup_position_in_grid]]) {
    threadgroup float partial[kValueGroup];
    threadgroup float pooled[kMaxWidth];
    threadgroup float act[kMaxWidth];
    const int t = int(thread_in_group);
    const int b = int(group);
    const int channels = a.channels;
    const int base = b * kRows * channels;

    // 1. mean over the cells
    const int parts = channels >= kValueGroup ? 1 : kValueGroup / channels;
    for (int slot = t; slot < parts * channels; slot += kValueGroup) {
        const int ch = slot % channels;
        const int part = slot / channels;
        float sum = 0.0f;
        for (int cell = part; cell < kCells; cell += parts) {
            sum += trunk[base + cell * channels + ch];
        }
        if (parts == 1) {
            pooled[ch] = sum / float(kCells);
        } else {
            partial[slot] = sum;
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (parts > 1) {
        for (int ch = t; ch < channels; ch += kValueGroup) {
            float sum = 0.0f;
            for (int part = 0; part < parts; part++) {
                sum += partial[part * channels + ch];
            }
            pooled[ch] = sum / float(kCells);
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    // 2. dense C -> H, bias, ReLU
    for (int j = t; j < a.hidden; j += kValueGroup) {
        float sum = b1[j];
        for (int ch = 0; ch < channels; ch++) {
            sum += w1[j * channels + ch] * pooled[ch];
        }
        act[j] = relu_keep_nan(sum);
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    // 3. dense H -> P, bias, tanh
    for (int p = t; p < a.players; p += kValueGroup) {
        float sum = b2[p];
        for (int j = 0; j < a.hidden; j++) {
            sum += w2[p * a.hidden + j] * act[j];
        }
        out[a.out_offset + b * a.players + p] = a.apply_tanh != 0 ? tanh_saturating(sum) : sum;
    }
}

// KernelsHexConv.tiled: thread group 8 x 16, tile 64 rows x 32 channels, steps of 32 columns; the items are interleaved (row
// ty + 16 i, channel tx + 8 j) in a 4 x 4 register tile. Every thread reaches every barrier (no early return).
kernel void conv_tiled(device const float* in [[buffer(0)]], device const int* gather [[buffer(1)]],
                       device const float* weight [[buffer(2)]], device const float* bias [[buffer(3)]],
                       device const float* res [[buffer(4)]], device float* out [[buffer(5)]],
                       constant ConvArgs& a [[buffer(6)]], uint2 local_id [[thread_position_in_threadgroup]],
                       uint2 group_id [[threadgroup_position_in_grid]]) {
    threadgroup float a_tile[kTileK * kAStride];
    threadgroup float w_tile[kTileK * kWStride];
    const int tx = int(local_id.x);
    const int ty = int(local_id.y);
    const int n0 = int(group_id.x) * kTileN;
    const int m0 = int(group_id.y) * kTileM;
    const int item = ty * kGroupX + tx;
    const int rows = a.batch * kRows;
    const int width = kStencil * a.in_channels;
    float c[4][4] = {{0.0f, 0.0f, 0.0f, 0.0f}, {0.0f, 0.0f, 0.0f, 0.0f}, {0.0f, 0.0f, 0.0f, 0.0f}, {0.0f, 0.0f, 0.0f, 0.0f}};

    for (int k0 = 0; k0 < width; k0 += kTileK) {
        for (int i = item; i < kTileM * kTileK; i += kGroupSize) {
            const int kk = i % kTileK;
            const int mm = i / kTileK;
            const int m = m0 + mm;
            const int j = k0 + kk;
            float v = 0.0f;
            if (m < rows && j < width) {
                const int cell = m % kRows;
                if (cell < kCells) {
                    const int k = j / a.in_channels;
                    const int ch = j - k * a.in_channels;
                    v = in[(m - cell + gather[cell * kStencil + k]) * a.in_channels + ch];
                }
            }
            a_tile[kk * kAStride + mm] = v;
        }
        for (int i = item; i < kTileN * kTileK; i += kGroupSize) {
            const int kk = i % kTileK;
            const int nn = i / kTileK;
            const int n = n0 + nn;
            const int j = k0 + kk;
            float w = 0.0f;
            if (n < a.out_channels && j < width) w = weight[n * width + j];
            w_tile[kk * kWStride + nn] = w;
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
        for (int kk = 0; kk < kTileK; kk++) {
            float av[4];
            float wv[4];
            for (int i = 0; i < 4; i++) av[i] = a_tile[kk * kAStride + ty + kGroupY * i];
            for (int j = 0; j < 4; j++) wv[j] = w_tile[kk * kWStride + tx + kGroupX * j];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) c[i][j] += av[i] * wv[j];
            }
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
    }
    for (int i = 0; i < 4; i++) {
        const int m = m0 + ty + kGroupY * i;
        if (m < rows) {
            const bool padding = m % kRows == kCells;
            for (int j = 0; j < 4; j++) store_one(c[i][j], m, n0 + tx + kGroupX * j, padding, bias, res, out, a);
        }
    }
}

// KernelsHexConv.simdgroup: 4 SIMD groups of 32 per thread group, a 32 x 32 x 32 tile, 2 x 2 blocks of 8 x 8 per SIMD group
// (rows 16 sm .., channels 16 sn ..) with simdgroup_multiply_accumulate; W is loaded transposed. The result goes through a
// threadgroup tile, then bias, residual and ReLU. One-dimensional grid: group = m_tile * groups_n + n_tile.
kernel void conv_simdgroup(device const float* in [[buffer(0)]], device const int* gather [[buffer(1)]],
                           device const float* weight [[buffer(2)]], device const float* bias [[buffer(3)]],
                           device const float* res [[buffer(4)]], device float* out [[buffer(5)]],
                           constant ConvArgs& a [[buffer(6)]], uint item_id [[thread_index_in_threadgroup]],
                           uint group_id [[threadgroup_position_in_grid]]) {
    threadgroup float a_tile[kSgTile * kSgTileK];
    threadgroup float w_tile[kSgTile * kSgTileK];
    threadgroup float c_tile[kSgTile * kSgTile];
    const int rows = a.batch * kRows;
    const int width = kStencil * a.in_channels;
    const int groups_n = (a.out_channels + kSgTile - 1) / kSgTile;
    const int group = int(group_id);
    const int m0 = (group / groups_n) * kSgTile;
    const int n0 = (group % groups_n) * kSgTile;
    const int item = int(item_id);
    const int simd = item / 32;
    const int sm = simd / 2;
    const int sn = simd % 2;

    simdgroup_float8x8 c00 = make_filled_simdgroup_matrix<float, 8, 8>(0.0f);
    simdgroup_float8x8 c01 = make_filled_simdgroup_matrix<float, 8, 8>(0.0f);
    simdgroup_float8x8 c10 = make_filled_simdgroup_matrix<float, 8, 8>(0.0f);
    simdgroup_float8x8 c11 = make_filled_simdgroup_matrix<float, 8, 8>(0.0f);

    for (int k0 = 0; k0 < width; k0 += kSgTileK) {
        for (int i = item; i < kSgTile * kSgTileK; i += kSgGroupSize) {
            const int kk = i % kSgTileK;
            const int m = m0 + i / kSgTileK;
            const int j = k0 + kk;
            float v = 0.0f;
            if (m < rows && j < width) {
                const int cell = m % kRows;
                if (cell < kCells) {
                    const int k = j / a.in_channels;
                    const int ch = j - k * a.in_channels;
                    v = in[(m - cell + gather[cell * kStencil + k]) * a.in_channels + ch];
                }
            }
            a_tile[i] = v;
        }
        for (int i = item; i < kSgTile * kSgTileK; i += kSgGroupSize) {
            const int n = n0 + i / kSgTileK;
            const int j = k0 + i % kSgTileK;
            float w = 0.0f;
            if (n < a.out_channels && j < width) w = weight[n * width + j];
            w_tile[i] = w;
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
        for (int k8 = 0; k8 < kSgTileK; k8 += 8) {
            simdgroup_float8x8 a0, a1, b0, b1;
            simdgroup_load(a0, a_tile + (sm * 16) * kSgTileK + k8, kSgTileK);
            simdgroup_load(a1, a_tile + (sm * 16 + 8) * kSgTileK + k8, kSgTileK);
            simdgroup_load(b0, w_tile + (sn * 16) * kSgTileK + k8, kSgTileK, ulong2(0, 0), true);
            simdgroup_load(b1, w_tile + (sn * 16 + 8) * kSgTileK + k8, kSgTileK, ulong2(0, 0), true);
            simdgroup_multiply_accumulate(c00, a0, b0, c00);
            simdgroup_multiply_accumulate(c01, a0, b1, c01);
            simdgroup_multiply_accumulate(c10, a1, b0, c10);
            simdgroup_multiply_accumulate(c11, a1, b1, c11);
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
    }
    simdgroup_store(c00, c_tile + (sm * 16) * kSgTile + sn * 16, kSgTile);
    simdgroup_store(c01, c_tile + (sm * 16) * kSgTile + sn * 16 + 8, kSgTile);
    simdgroup_store(c10, c_tile + (sm * 16 + 8) * kSgTile + sn * 16, kSgTile);
    simdgroup_store(c11, c_tile + (sm * 16 + 8) * kSgTile + sn * 16 + 8, kSgTile);
    threadgroup_barrier(mem_flags::mem_threadgroup);
    for (int i = item; i < kSgTile * kSgTile; i += kSgGroupSize) {
        const int m = m0 + i / kSgTile;
        const int n = n0 + i % kSgTile;
        if (m < rows) store_one(c_tile[i], m, n, m % kRows == kCells, bias, res, out, a);
    }
}
