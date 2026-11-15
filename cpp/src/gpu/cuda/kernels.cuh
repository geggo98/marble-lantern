// CUDA kernels of the forward pass (naive and tiled convolution, policy and value heads), their launch geometry and the order
// of the launches of one forward pass. Port of the TornadoVM kernels of nn-tornado (KernelsHexConv.naive and tiled,
// OpsKernels.policyHead and valueHead, DeviceMath) and of src/gpu/metal/kernels.metal, without simdgroup (TornadoVM refuses it
// on CUDA too).
//
// Unsafe zone (docs/cpp-comparison-plan.md): raw device pointers and indexing are the only way to write a kernel. The whole
// file sits in a marked region; nothing else of the project may use raw pointers like this.
//
// Included by cuda_net.cu (nvcc) and, with the emulation macros of tests/cuda_emu.hpp, by cuda_adapter_test.cpp, which runs
// the same code on the CPU against the reference (threads emulate a block, std::barrier emulates __syncthreads, buffers are
// exact-size vectors so that AddressSanitizer sees an out-of-bounds access). Includes only standard headers.
//
// Kernel names never equal a CUDA math function name (TornadoVM pitfall 15).
#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#if defined(__clang__)
#define SH_UNSAFE_BEGIN _Pragma("clang unsafe_buffer_usage begin")
#define SH_UNSAFE_END _Pragma("clang unsafe_buffer_usage end")
#else
#define SH_UNSAFE_BEGIN
#define SH_UNSAFE_END
#endif

SH_UNSAFE_BEGIN

namespace sh::cuda::kernels {

inline constexpr int kRows = 122;        // HexGrid.ROWS
inline constexpr int kCells = 121;       // HexGrid.CELLS, also the index of the padding row
inline constexpr int kStencil = 7;       // HexGrid.STENCIL
inline constexpr int kPolicySize = 242;
inline constexpr int kValueGroup = 256;  // OpsKernels.VALUE_GROUP
inline constexpr int kMaxWidth = 1024;   // OpsKernels.MAX_WIDTH

inline constexpr int kTileM = 64;   // KernelsHexConv.TILE_M
inline constexpr int kTileN = 32;   // TILE_N
inline constexpr int kTileK = 32;   // TILE_K
inline constexpr int kGroupX = 8;   // GROUP_X = TILE_N / MICRO_N
inline constexpr int kGroupY = 16;  // GROUP_Y = TILE_M / MICRO_M
inline constexpr int kGroupSize = kGroupX * kGroupY;
inline constexpr int kAStride = kTileM + 1;  // one extra column against bank conflicts
inline constexpr int kWStride = kTileN + 1;

inline constexpr int kNaiveBlockX = 32;  // naive: blocks of up to 32 channels x 8 rows
inline constexpr int kNaiveBlockY = 8;
inline constexpr int kPolicyBlock = 64;

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

// ---- launch geometry (plain numbers, shared by the host layer and the emulation) ----

struct LaunchDims {
    int grid_x = 1, grid_y = 1;
    int block_x = 1, block_y = 1;
};

constexpr int ceil_div(int a, int b) { return (a + b - 1) / b; }

constexpr LaunchDims naive_dims(int out_channels, int batch) {
    const int bx = out_channels < kNaiveBlockX ? out_channels : kNaiveBlockX;
    return {ceil_div(out_channels, bx), ceil_div(batch * kRows, kNaiveBlockY), bx, kNaiveBlockY};
}
// KernelsHexConv.tiledGrid: blocks of 8 x 16, one block per 64 rows x 32 channels.
constexpr LaunchDims tiled_dims(int out_channels, int batch) {
    return {ceil_div(out_channels, kTileN), ceil_div(batch * kRows, kTileM), kGroupX, kGroupY};
}
constexpr LaunchDims policy_dims(int batch) { return {ceil_div(kPolicySize, kPolicyBlock), batch, kPolicyBlock, 1}; }
constexpr LaunchDims value_dims(int batch) { return {batch, 1, kValueGroup, 1}; }

// ---- device code ----

// DeviceMath.relu: keeps NaN (fmaxf would drop it).
__device__ inline float relu_keep_nan(float v) { return v < 0.0f ? 0.0f : v; }

// DeviceMath.tanh: exactly +-1 above 8.5, NaN stays NaN (the comparisons fail for NaN, which reaches tanhf).
__device__ inline float tanh_saturating(float x) { return x > 8.5f ? 1.0f : (x < -8.5f ? -1.0f : tanhf(x)); }

// KernelsHexConv.storeOne: bias, optional residual, optional ReLU; the padding row is written as 0; n beyond the shape is masked.
__device__ inline void store_one(float sum, int m, int n, bool padding, const float* bias, const float* res, float* out, const ConvArgs& a) {
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
__global__ void hexconv_naive(const float* in, const int* gather, const float* weight, const float* bias, const float* res, float* out,
                              ConvArgs a) {
    const int o = static_cast<int>(blockIdx.x * blockDim.x + threadIdx.x);
    const int r = static_cast<int>(blockIdx.y * blockDim.y + threadIdx.y);
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

// KernelsHexConv.tiled: block 8 x 16, tile 64 rows x 32 channels, steps of 32 columns; the items are interleaved (row
// ty + 16 i, channel tx + 8 j) in a 4 x 4 register tile. Every thread reaches every barrier (no early return).
__global__ void hexconv_tiled(const float* in, const int* gather, const float* weight, const float* bias, const float* res, float* out,
                              ConvArgs a) {
    __shared__ float a_tile[kTileK * kAStride];
    __shared__ float w_tile[kTileK * kWStride];
    const int tx = static_cast<int>(threadIdx.x);
    const int ty = static_cast<int>(threadIdx.y);
    const int n0 = static_cast<int>(blockIdx.x) * kTileN;
    const int m0 = static_cast<int>(blockIdx.y) * kTileM;
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
        __syncthreads();
        for (int kk = 0; kk < kTileK; kk++) {
            float av[4];
            float wv[4];
            for (int i = 0; i < 4; i++) av[i] = a_tile[kk * kAStride + ty + kGroupY * i];
            for (int j = 0; j < 4; j++) wv[j] = w_tile[kk * kWStride + tx + kGroupX * j];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) c[i][j] += av[i] * wv[j];
            }
        }
        __syncthreads();
    }
    for (int i = 0; i < 4; i++) {
        const int m = m0 + ty + kGroupY * i;
        if (m < rows) {
            const bool padding = m % kRows == kCells;
            for (int j = 0; j < 4; j++) store_one(c[i][j], m, n0 + tx + kGroupX * j, padding, bias, res, out, a);
        }
    }
}

// OpsKernels.policyHead: one thread per (position, logit); the padding row of the trunk is not read.
__global__ void head_policy(const float* trunk, const float* weight, const float* bias, float* out, HeadArgs a) {
    const int j = static_cast<int>(blockIdx.x * blockDim.x + threadIdx.x);
    const int b = static_cast<int>(blockIdx.y);
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

// OpsKernels.valueHead: one block of 256 per position. Mean over the 121 cells with partial sums (parts = 256 / C threads share
// a channel when C < 256), dense C -> H with ReLU, dense H -> P with tanh (only where apply_tanh != 0). Needs C and H <= 1024.
__global__ void head_value(const float* trunk, const float* w1, const float* b1, const float* w2, const float* b2, float* out, HeadArgs a) {
    __shared__ float partial[kValueGroup];
    __shared__ float pooled[kMaxWidth];
    __shared__ float act[kMaxWidth];
    const int t = static_cast<int>(threadIdx.x);
    const int b = static_cast<int>(blockIdx.x);
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
            pooled[ch] = sum / static_cast<float>(kCells);
        } else {
            partial[slot] = sum;
        }
    }
    __syncthreads();
    if (parts > 1) {
        for (int ch = t; ch < channels; ch += kValueGroup) {
            float sum = 0.0f;
            for (int part = 0; part < parts; part++) {
                sum += partial[part * channels + ch];
            }
            pooled[ch] = sum / static_cast<float>(kCells);
        }
    }
    __syncthreads();

    // 2. dense C -> H, bias, ReLU
    for (int j = t; j < a.hidden; j += kValueGroup) {
        float sum = b1[j];
        for (int ch = 0; ch < channels; ch++) {
            sum += w1[j * channels + ch] * pooled[ch];
        }
        act[j] = relu_keep_nan(sum);
    }
    __syncthreads();

    // 3. dense H -> P, bias, tanh
    for (int p = t; p < a.players; p += kValueGroup) {
        float sum = b2[p];
        for (int j = 0; j < a.hidden; j++) {
            sum += w2[p * a.hidden + j] * act[j];
        }
        out[a.out_offset + b * a.players + p] = a.apply_tanh != 0 ? tanh_saturating(sum) : sum;
    }
}

// ---- the order of the launches of one forward pass (TornadoNetwork's task graph) ----

enum class ConvKind : std::uint8_t { Naive, Tiled };

struct ConvLayer {
    const float* weight = nullptr;  // [out][7 * in]
    const float* bias = nullptr;    // [out]
};
struct BlockLayers {
    ConvLayer conv1, conv2;
};

// Device pointers of one network, uploaded once.
struct NetView {
    const int* gather = nullptr;         // [121][7]
    const float* no_residual = nullptr;  // read by the convolutions without residual instead of a residual; never read
    ConvLayer conv_in;
    std::vector<BlockLayers> blocks;
    const float *policy_weight = nullptr, *policy_bias = nullptr;
    const float *dense1_weight = nullptr, *dense1_bias = nullptr;
    const float *dense2_weight = nullptr, *dense2_bias = nullptr;
};

// The buffers of one plan (one bucket): input [batch][122][planes]; x, t, u [batch][122][channels]; out holds the logits
// [batch][242] and then the head values [batch][players].
struct Work {
    const float* input = nullptr;
    float *x = nullptr, *t = nullptr, *u = nullptr;
    float* out = nullptr;
};

struct PassShape {
    int batch = 0;  // the bucket
    int input_planes = 0;
    int channels = 0;
    int hidden = 0;
    int players = 0;
    bool apply_tanh = false;
    ConvKind conv = ConvKind::Naive;
};

// Runs the forward pass through a launcher with the members
//   conv(ConvKind, const float* in, const ConvLayer&, const float* res, float* out, const ConvArgs&, const std::string& label)
//   policy(const float* trunk, const float* weight, const float* bias, float* out, const HeadArgs&, const std::string& label)
//   value(const float* trunk, w1, b1, w2, b2, float* out, const HeadArgs&, const std::string& label)
// The host layer's launcher starts the kernels above; the emulation's runs them on the CPU. The launcher stops doing work
// after its first failure (it keeps the error itself). Returns the buffer that holds the trunk after the last block.
template <class Launcher>
const float* run_forward(Launcher& launcher, const NetView& net, const PassShape& s, const Work& w) {
    const int c = s.channels;
    launcher.conv(s.conv, w.input, net.conv_in, net.no_residual, w.x, ConvArgs{s.batch, s.input_planes, c, 0, 1}, "conv_in");
    float* x = w.x;  // x is the block input; conv1 writes t, conv2 reads t and adds x into u; then u is the next x
    float* t = w.t;
    float* u = w.u;
    for (std::size_t i = 0; i < net.blocks.size(); ++i) {
        const std::string name = "block" + std::to_string(i);
        launcher.conv(s.conv, x, net.blocks[i].conv1, net.no_residual, t, ConvArgs{s.batch, c, c, 0, 1}, name + ".conv1");
        launcher.conv(s.conv, t, net.blocks[i].conv2, x, u, ConvArgs{s.batch, c, c, 1, 1}, name + ".conv2");
        std::swap(x, u);
    }
    const HeadArgs heads{s.batch, c, s.hidden, s.players, s.apply_tanh ? 1 : 0, s.batch * kPolicySize};
    launcher.policy(x, net.policy_weight, net.policy_bias, w.out, heads, "policy_head");
    launcher.value(x, net.dense1_weight, net.dense1_bias, net.dense2_weight, net.dense2_bias, w.out, heads, "value_head");
    return x;
}

}  // namespace sh::cuda::kernels

SH_UNSAFE_END
