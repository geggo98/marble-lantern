// Port of VectorNetwork. Vectors are compiler vector extensions (clang and gcc map them to NEON, AVX2 or AVX-512).
// This file is an unsafe zone (scripts/check-rules.sh). The checked parts (setup, the cut of every range, the size checks of one
// convolution call) use std::span; the hot loops (convolution, dot product, pooling) work on raw pointers into ranges that were
// checked once before the loop. Reason (README, "x86 code generation of the SIMD path"): with span access the libstdc++ assertions
// of -D_GLIBCXX_ASSERTIONS sat in the inner loop (7 compare-and-jump pairs and spilled loop variables per iteration), and the
// load through std::array and std::copy_n went through a stack temporary that GCC 12 split into two 32-byte stores followed by one
// 64-byte reload (a store-forwarding stall) at 16 lanes.
//
// Layout, as in Java: with L lanes and T = 2 L, every internal activation has a row stride S = C rounded up to a multiple
// of T, the value head's hidden vector has length H rounded up to a multiple of L. The extra channels have zero weights and
// zero biases, so they stay 0 after every layer. Row 121 of every internal buffer is never written and stays 0.
#include "simd_net.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <string>
#include <utility>
#include <vector>

#include "gather.hpp"
#include "java_compat.hpp"
#include "priors.hpp"

// -Wunsafe-buffer-usage regions exist only in clang; gcc would warn about the pragma.
#if defined(__clang__)
#define SH_UNSAFE_BEGIN _Pragma("clang unsafe_buffer_usage begin")
#define SH_UNSAFE_END _Pragma("clang unsafe_buffer_usage end")
#else
#define SH_UNSAFE_BEGIN
#define SH_UNSAFE_END
#endif

#ifndef SH_SIMD_NS
#define SH_SIMD_NS simd_fma
#endif

namespace sh::nn::SH_SIMD_NS {
namespace {

constexpr std::size_t kL = SH_SIMD_LANES;
constexpr std::size_t kTile = 2 * kL;       // output channels per convolution tile
constexpr std::size_t kCellBlock = 4;       // cells per pass of the main loop
constexpr std::size_t kStencilSize = static_cast<std::size_t>(kStencil);
constexpr std::size_t kCellCount = static_cast<std::size_t>(kCells);

using Vec = float __attribute__((vector_size(4 * SH_SIMD_LANES)));
using Lane = std::array<float, kL>;
static_assert(sizeof(Vec) == sizeof(Lane));

SH_UNSAFE_BEGIN
// One unaligned vector load or store of L floats at p; the caller has checked the range. __builtin_memcpy of a vector is one
// vmovups; it needs no temporary on the stack (the std::array round trip it replaces did at 16 lanes).
inline Vec load(const float* p) {
    Vec v;
    __builtin_memcpy(&v, p, sizeof v);
    return v;
}

inline void store(float* p, Vec v) { __builtin_memcpy(p, &v, sizeof v); }
SH_UNSAFE_END

// Every lane x. x - 0 keeps -0 and NaN, unlike 0 + x.
Vec splat(float x) { return x - Vec{}; }

// Lane-wise Math.max(v, 0): NaN stays NaN, -0 becomes +0 (v < 0 is false for both; the final + 0 turns -0 into +0).
Vec relu(Vec v) {
    const Vec zero{};
    const Vec r = (v < zero) ? zero : v;
    return r + zero;
}

float reduce_add(Vec v) {
    const Lane a = std::bit_cast<Lane>(v);
    float sum = a[0];
    for (std::size_t i = 1; i < kL; ++i) sum += a[i];
    return sum;
}

SH_UNSAFE_BEGIN
// The sum of a[i] * b[i] over n floats (n a multiple of L), lane-wise first, then the lanes in order, as in Java.
float dot_raw(const float* a, const float* b, std::size_t n) {
    Vec sum{};
    for (std::size_t i = 0; i < n; i += kL) sum = sum + load(a + i) * load(b + i);
    return reduce_add(sum);
}
SH_UNSAFE_END

// Dot product of two ranges of the same length, a multiple of L. The range of b is cut to the length of a once, outside the loop.
float dot(std::span<const float> a, std::span<const float> b) { return dot_raw(a.data(), b.first(a.size()).data(), a.size()); }

std::size_t round_up(std::size_t n, std::size_t multiple) { return (n + multiple - 1) / multiple * multiple; }

// packed[(tile * width + j) * T + lane] = weight[(tile * T + lane) * width + j]; zeros past out_channels.
std::vector<float> pack_conv(std::span<const float> weight, std::size_t in_channels, std::size_t out_channels, std::size_t stride) {
    const std::size_t width = kStencilSize * in_channels;
    const std::size_t tiles = stride / kTile;
    std::vector<float> packed(tiles * width * kTile, 0.0f);
    for (std::size_t tile = 0; tile < tiles; ++tile) {
        for (std::size_t j = 0; j < width; ++j) {
            for (std::size_t lane = 0; lane < kTile; ++lane) {
                const std::size_t o = tile * kTile + lane;
                if (o < out_channels) packed[(tile * width + j) * kTile + lane] = weight[o * width + j];
            }
        }
    }
    return packed;
}

// One row of `length` floats copied into a zero-padded row of `stride` floats.
std::vector<float> pad_rows(std::span<const float> src, std::size_t rows, std::size_t length, std::size_t stride) {
    std::vector<float> out(rows * stride, 0.0f);
    const std::span<float> dst(out);
    for (std::size_t r = 0; r < rows; ++r) {
        // an element loop, not std::copy_n: GCC 12 reports a bound of (size_t)-4 for the memcpy of copy_n here (-Wstringop-overflow, false positive)
        const std::span<const float> from = src.subspan(r * length, length);
        const std::span<float> to = dst.subspan(r * stride, length);
        for (std::size_t i = 0; i < length; ++i) to[i] = from[i];
    }
    return out;
}

std::vector<float> pad_vector(std::span<const float> src, std::size_t stride) { return pad_rows(src, 1, src.size(), stride); }

std::unexpected<Error> fail(std::string message) { return std::unexpected(Error{std::move(message)}); }

class Engine final : public SimdEngine {
public:
    explicit Engine(const Parameters& p)
        : config_(p.config()),
          stride_(round_up(static_cast<std::size_t>(config_.channels), kTile)),
          hidden_stride_(round_up(static_cast<std::size_t>(config_.value_hidden), kL)),
          x_(static_cast<std::size_t>(kRows) * stride_, 0.0f),
          t_(x_.size(), 0.0f),
          u_(x_.size(), 0.0f),
          pooled_(stride_, 0.0f),
          hidden_(hidden_stride_, 0.0f) {
        const auto c = static_cast<std::size_t>(config_.channels);
        const auto h = static_cast<std::size_t>(config_.value_hidden);
        const auto players = static_cast<std::size_t>(config_.players);
        const auto planes = static_cast<std::size_t>(config_.input_planes());
        conv_in_weight_ = pack_conv(p.data("conv_in.weight"), planes, c, stride_);
        conv_in_bias_ = pad_vector(p.data("conv_in.bias"), stride_);
        for (int i = 0; i < config_.blocks; ++i) {
            for (int j = 1; j <= 2; ++j) {
                const std::string conv = "block" + std::to_string(i) + ".conv" + std::to_string(j);
                block_weight_.push_back(pack_conv(p.data(conv + ".weight"), c, c, stride_));
                block_bias_.push_back(pad_vector(p.data(conv + ".bias"), stride_));
            }
        }
        policy_weight_ = pad_rows(p.data("head_policy.weight"), static_cast<std::size_t>(kPolicyChannels), c, stride_);
        policy_bias_.assign(p.data("head_policy.bias").begin(), p.data("head_policy.bias").end());
        dense1_weight_ = pad_rows(p.data("head_value.dense1.weight"), h, c, stride_);
        dense1_bias_.assign(p.data("head_value.dense1.bias").begin(), p.data("head_value.dense1.bias").end());
        dense2_weight_ = pad_rows(p.data("head_value.dense2.weight"), players, h, hidden_stride_);
        dense2_bias_.assign(p.data("head_value.dense2.bias").begin(), p.data("head_value.dense2.bias").end());
        // conv_raw indexes the input rows with the gather entries without a check: prove once that every entry is a row.
        gather_ok_ = std::ranges::all_of(gather_table(), [](int row) { return row >= 0 && row < kRows; });
        if (has_residual_priors(config_.planes)) {
            prior_beta_ = p.data(kPriorBeta)[0];
            prior_k_ = p.data(kPriorK)[0];
        }
    }

    std::expected<void, Error> forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) override {
        if (batch < 1) return fail("batch must be >= 1, got " + std::to_string(batch));
        if (!gather_ok_) return fail("gather table has an entry outside the rows");
        const auto b = static_cast<std::size_t>(batch);
        const auto planes = static_cast<std::size_t>(config_.input_planes());
        const auto in_size = static_cast<std::size_t>(config_.input_size());
        const auto players = static_cast<std::size_t>(config_.players);
        if (input.size() < b * in_size) return fail("input needs " + std::to_string(b * in_size) + " floats, has " + std::to_string(input.size()));
        if (logits.size() < b * static_cast<std::size_t>(kPolicySize))
            return fail("logits needs " + std::to_string(b * static_cast<std::size_t>(kPolicySize)) + " floats, has " + std::to_string(logits.size()));
        if (values.size() < b * players) return fail("values needs " + std::to_string(b * players) + " floats, has " + std::to_string(values.size()));
        // Every position is checked before anything is written (a NaN is not zero).
        for (std::size_t pos = 0; pos < b; ++pos) {
            const std::size_t pad = pos * in_size + static_cast<std::size_t>(kPadding) * planes;
            for (std::size_t plane = 0; plane < planes; ++plane) {
                if (input[pad + plane] != 0) return fail("padding row of input " + std::to_string(pos) + " is not zero in plane " + std::to_string(plane));
            }
        }
        for (std::size_t pos = 0; pos < b; ++pos) {
            forward_one(input.subspan(pos * in_size, in_size), logits.subspan(pos * static_cast<std::size_t>(kPolicySize), static_cast<std::size_t>(kPolicySize)),
                        values.subspan(pos * players, players));
        }
        return {};
    }

private:
    void forward_one(std::span<const float> input, std::span<float> policy, std::span<float> values) {
        const auto c = static_cast<std::size_t>(config_.channels);
        const auto planes = static_cast<std::size_t>(config_.input_planes());
        std::span<float> trunk = x_;
        std::span<float> mid = t_;
        std::span<float> next = u_;
        conv(input, planes, planes, conv_in_weight_, conv_in_bias_, trunk, {});
        for (std::size_t i = 0; i < static_cast<std::size_t>(config_.blocks); ++i) {
            conv(trunk, stride_, c, block_weight_[2 * i], block_bias_[2 * i], mid, {});
            conv(mid, stride_, c, block_weight_[2 * i + 1], block_bias_[2 * i + 1], next, trunk);
            std::swap(trunk, next);
        }

        for (std::size_t cell = 0; cell < kCellCount; ++cell) {
            const std::span<const float> row = std::span<const float>(trunk).subspan(cell * stride_, stride_);
            for (std::size_t o = 0; o < static_cast<std::size_t>(kPolicyChannels); ++o) {
                policy[cell * static_cast<std::size_t>(kPolicyChannels) + o] =
                    policy_bias_[o] + dot(row, std::span<const float>(policy_weight_).subspan(o * stride_, stride_));
            }
        }
        const bool priors = has_residual_priors(config_.planes);
        if (priors) add_policy_prior(prior_beta_, input, config_.input_planes(), policy);

        pool(trunk.first(kCellCount * stride_), std::span<float>(pooled_).first(stride_));
        // hidden_[value_hidden .. hidden_stride_) is never written and stays 0.
        for (std::size_t o = 0; o < static_cast<std::size_t>(config_.value_hidden); ++o) {
            hidden_[o] = sh::java_max(dense1_bias_[o] + dot(pooled_, std::span<const float>(dense1_weight_).subspan(o * stride_, stride_)), 0.0f);
        }
        const auto players = static_cast<std::size_t>(config_.players);
        for (std::size_t o = 0; o < players; ++o) {
            values[o] = dense2_bias_[o] + dot(hidden_, std::span<const float>(dense2_weight_).subspan(o * hidden_stride_, hidden_stride_));
        }
        if (priors) add_value_prior(prior_k_, input, config_.input_planes(), values);
        for (std::size_t o = 0; o < players; ++o) values[o] = static_cast<float>(std::tanh(static_cast<double>(values[o])));
    }

    // Mean over the 121 cell rows, lane-wise: pooled[v] = (sum of trunk[cell][v] over the cells in order) / 121.
    void pool(std::span<const float> trunk, std::span<float> pooled) const {
        SH_UNSAFE_BEGIN
        const float* t = trunk.data();
        float* out = pooled.data();
        for (std::size_t v = 0; v < stride_; v += kL) {
            Vec sum{};
            for (std::size_t cell = 0; cell < kCellCount; ++cell) sum = sum + load(t + cell * stride_ + v);
            store(out + v, sum / splat(static_cast<float>(kCells)));
        }
        SH_UNSAFE_END
    }

    // out = ReLU(bias + conv(in) [+ residual]) for the 121 cell rows; tail lanes become 0. `in` has rows of in_stride floats, of
    // which the first in_channels take part; its padding row must be zero. weight is packed by pack_conv. The span is empty if
    // there is no residual. Row 121 of `out` is not written: nothing ever writes it, so it keeps the 0 of the allocation.
    //
    // Every range is cut to its size here, once per call (a checked cut: the libstdc++ assertion of the hardened build, or
    // std::span::first itself); the loops in conv_raw then work on pointers. The gather entries were checked in the constructor.
    void conv(std::span<const float> in, std::size_t in_stride, std::size_t in_channels, std::span<const float> weight, std::span<const float> bias,
              std::span<float> out, std::span<const float> residual) const {
        const std::size_t width = kStencilSize * in_channels;
        const std::size_t tiles = stride_ / kTile;
        const std::size_t rows = static_cast<std::size_t>(kRows);
        conv_raw(in.first(rows * in_stride).data(), in_stride, in_channels, weight.first(tiles * width * kTile).data(), bias.first(stride_).data(),
                 out.first(kCellCount * stride_).data(), residual.empty() ? nullptr : residual.first(kCellCount * stride_).data());
    }

    SH_UNSAFE_BEGIN
    // Stores one tile of a cell: adds the residual if there is one (nullptr if not), applies ReLU.
    static void store_tile(float* out, const float* residual, Vec a0, Vec a1) {
        if (residual != nullptr) {
            a0 = a0 + load(residual);
            a1 = a1 + load(residual + kL);
        }
        store(out, relu(a0));
        store(out + kL, relu(a1));
    }

    // The loops of conv() on pointers. Ranges (checked by conv): in rows * in_stride floats, weight tiles * width * T, bias S,
    // out and residual 121 * S; every gather entry is below kRows (constructor). The order of the additions per accumulator is
    // the one of Java's VectorNetwork: stencil entry k, then channel ch, one multiply-add each.
    void conv_raw(const float* in, std::size_t in_stride, std::size_t in_channels, const float* weight, const float* bias, float* out,
                  const float* residual) const {
        const int* gather = gather_table().data();
        const std::size_t width = kStencilSize * in_channels;
        for (std::size_t tile = 0; tile < stride_ / kTile; ++tile) {
            const std::size_t o = tile * kTile;
            const float* const tile_weight = weight + tile * width * kTile;
            const Vec bias0 = load(bias + o);
            const Vec bias1 = load(bias + o + kL);
            std::size_t cell = 0;
            for (; cell + kCellBlock <= kCellCount; cell += kCellBlock) {
                Vec a00 = bias0, a01 = bias1, a10 = bias0, a11 = bias1, a20 = bias0, a21 = bias1, a30 = bias0, a31 = bias1;
                const int* g = gather + cell * kStencilSize;
                for (std::size_t k = 0; k < kStencilSize; ++k) {
                    const float* x0 = in + static_cast<std::size_t>(g[0 * kStencilSize + k]) * in_stride;
                    const float* x1 = in + static_cast<std::size_t>(g[1 * kStencilSize + k]) * in_stride;
                    const float* x2 = in + static_cast<std::size_t>(g[2 * kStencilSize + k]) * in_stride;
                    const float* x3 = in + static_cast<std::size_t>(g[3 * kStencilSize + k]) * in_stride;
                    const float* wc = tile_weight + k * in_channels * kTile;
                    for (std::size_t ch = 0; ch < in_channels; ++ch, wc += kTile) {
                        const Vec w0 = load(wc);
                        const Vec w1 = load(wc + kL);
                        const Vec v0 = splat(x0[ch]);
                        const Vec v1 = splat(x1[ch]);
                        const Vec v2 = splat(x2[ch]);
                        const Vec v3 = splat(x3[ch]);
                        a00 = a00 + w0 * v0;
                        a01 = a01 + w1 * v0;
                        a10 = a10 + w0 * v1;
                        a11 = a11 + w1 * v1;
                        a20 = a20 + w0 * v2;
                        a21 = a21 + w1 * v2;
                        a30 = a30 + w0 * v3;
                        a31 = a31 + w1 * v3;
                    }
                }
                const std::size_t i0 = cell * stride_ + o;
                store_tile(out + i0 + 0 * stride_, residual ? residual + i0 + 0 * stride_ : nullptr, a00, a01);
                store_tile(out + i0 + 1 * stride_, residual ? residual + i0 + 1 * stride_ : nullptr, a10, a11);
                store_tile(out + i0 + 2 * stride_, residual ? residual + i0 + 2 * stride_ : nullptr, a20, a21);
                store_tile(out + i0 + 3 * stride_, residual ? residual + i0 + 3 * stride_ : nullptr, a30, a31);
            }
            for (; cell < kCellCount; ++cell) {  // 121 = 4 * 30 + 1: the last cell
                Vec a0 = bias0, a1 = bias1;
                for (std::size_t k = 0; k < kStencilSize; ++k) {
                    const float* x = in + static_cast<std::size_t>(gather[cell * kStencilSize + k]) * in_stride;
                    const float* wc = tile_weight + k * in_channels * kTile;
                    for (std::size_t ch = 0; ch < in_channels; ++ch, wc += kTile) {
                        const Vec v = splat(x[ch]);
                        a0 = a0 + load(wc) * v;
                        a1 = a1 + load(wc + kL) * v;
                    }
                }
                const std::size_t i0 = cell * stride_ + o;
                store_tile(out + i0, residual ? residual + i0 : nullptr, a0, a1);
            }
        }
    }
    SH_UNSAFE_END

    NetworkConfig config_;
    std::size_t stride_;         // row stride S of the internal activations
    std::size_t hidden_stride_;  // length of the internal hidden vector
    std::vector<float> conv_in_weight_, conv_in_bias_;
    std::vector<std::vector<float>> block_weight_, block_bias_;
    std::vector<float> policy_weight_, policy_bias_, dense1_weight_, dense1_bias_, dense2_weight_, dense2_bias_;
    bool gather_ok_ = false;
    float prior_beta_ = 0;
    float prior_k_ = 0;
    std::vector<float> x_, t_, u_, pooled_, hidden_;
};

}  // namespace

std::unique_ptr<SimdEngine> make_engine(const Parameters& parameters) { return std::make_unique<Engine>(parameters); }

}  // namespace sh::nn::SH_SIMD_NS
