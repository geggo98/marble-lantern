// MSL kernels of the Metal backend, compiled at run time from this source (newLibraryWithSource:options:error:).
// Port of the TornadoVM kernels of nn-tornado (KernelsHexConv.naive, OpsKernels.policyHead and valueHead, DeviceMath).
// WP11a: naive convolution and the two heads; WP11b adds tiled and simdgroup.
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
