// Port of VectorNetwork. Vectors are compiler vector extensions (clang and gcc map them to NEON, AVX2 or AVX-512).
// All loads and stores go through std::span sub-ranges, std::copy_n and std::bit_cast, so the file needs no unsafe region:
// -Wunsafe-buffer-usage accepts it (spike 1). The size of every sub-range is checked where the span is cut.
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

Vec load(std::span<const float> s) {
    Lane a{};
    const auto head = s.first<kL>();
    std::copy_n(head.begin(), kL, a.begin());
    return std::bit_cast<Vec>(a);
}

void store(std::span<float> d, Vec v) {
    const Lane a = std::bit_cast<Lane>(v);
    const auto head = d.first<kL>();
    std::copy_n(a.begin(), kL, head.begin());
}

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

// Dot product of two ranges of the same length, a multiple of L.
float dot(std::span<const float> a, std::span<const float> b) {
    Vec sum{};
    for (std::size_t i = 0; i < a.size(); i += kL) sum = sum + load(a.subspan(i)) * load(b.subspan(i));
    return reduce_add(sum);
}

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
        if (has_residual_priors(config_.planes)) {
            prior_beta_ = p.data(kPriorBeta)[0];
            prior_k_ = p.data(kPriorK)[0];
        }
    }

    std::expected<void, Error> forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) override {
        if (batch < 1) return fail("batch must be >= 1, got " + std::to_string(batch));
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

        for (std::size_t v = 0; v < stride_; v += kL) {
            Vec sum{};
            for (std::size_t cell = 0; cell < kCellCount; ++cell) sum = sum + load(std::span<const float>(trunk).subspan(cell * stride_ + v));
            store(std::span<float>(pooled_).subspan(v), sum / splat(static_cast<float>(kCells)));
        }
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

    // Stores one tile of a cell: adds the residual if there is one (the span is empty if not), applies ReLU.
    static void store_tile(std::span<float> out, std::span<const float> residual, std::size_t index, Vec a0, Vec a1) {
        if (!residual.empty()) {
            a0 = a0 + load(residual.subspan(index));
            a1 = a1 + load(residual.subspan(index + kL));
        }
        store(out.subspan(index), relu(a0));
        store(out.subspan(index + kL), relu(a1));
    }

    // out = ReLU(bias + conv(in) [+ residual]) for the 121 cell rows; tail lanes become 0. `in` has rows of in_stride floats, of
    // which the first in_channels take part; its padding row must be zero. weight is packed by pack_conv.
    void conv(std::span<const float> in, std::size_t in_stride, std::size_t in_channels, std::span<const float> weight, std::span<const float> bias,
              std::span<float> out, std::span<const float> residual) const {
        const GatherTable& gather = gather_table();
        const std::size_t width = kStencilSize * in_channels;
        for (std::size_t tile = 0; tile < stride_ / kTile; ++tile) {
            const std::size_t o = tile * kTile;
            const std::size_t tile_base = tile * width * kTile;
            const Vec bias0 = load(bias.subspan(o));
            const Vec bias1 = load(bias.subspan(o + kL));
            std::size_t cell = 0;
            for (; cell + kCellBlock <= kCellCount; cell += kCellBlock) {
                Vec a00 = bias0, a01 = bias1, a10 = bias0, a11 = bias1, a20 = bias0, a21 = bias1, a30 = bias0, a31 = bias1;
                for (std::size_t k = 0; k < kStencilSize; ++k) {
                    // One bounds check per range and stencil entry; the channel loop indexes inside the checked ranges.
                    const auto x0 = in.subspan(static_cast<std::size_t>(gather[(cell + 0) * kStencilSize + k]) * in_stride, in_channels);
                    const auto x1 = in.subspan(static_cast<std::size_t>(gather[(cell + 1) * kStencilSize + k]) * in_stride, in_channels);
                    const auto x2 = in.subspan(static_cast<std::size_t>(gather[(cell + 2) * kStencilSize + k]) * in_stride, in_channels);
                    const auto x3 = in.subspan(static_cast<std::size_t>(gather[(cell + 3) * kStencilSize + k]) * in_stride, in_channels);
                    const auto wk = weight.subspan(tile_base + k * in_channels * kTile, in_channels * kTile);
                    for (std::size_t ch = 0; ch < in_channels; ++ch) {
                        const auto wc = wk.subspan(ch * kTile, kTile);
                        const Vec w0 = load(wc);
                        const Vec w1 = load(wc.subspan(kL));
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
                store_tile(out, residual, (cell + 0) * stride_ + o, a00, a01);
                store_tile(out, residual, (cell + 1) * stride_ + o, a10, a11);
                store_tile(out, residual, (cell + 2) * stride_ + o, a20, a21);
                store_tile(out, residual, (cell + 3) * stride_ + o, a30, a31);
            }
            for (; cell < kCellCount; ++cell) {  // 121 = 4 * 30 + 1: the last cell
                Vec a0 = bias0, a1 = bias1;
                for (std::size_t k = 0; k < kStencilSize; ++k) {
                    const auto x = in.subspan(static_cast<std::size_t>(gather[cell * kStencilSize + k]) * in_stride, in_channels);
                    const auto wk = weight.subspan(tile_base + k * in_channels * kTile, in_channels * kTile);
                    for (std::size_t ch = 0; ch < in_channels; ++ch) {
                        const auto wc = wk.subspan(ch * kTile, kTile);
                        const Vec v = splat(x[ch]);
                        a0 = a0 + load(wc) * v;
                        a1 = a1 + load(wc.subspan(kL)) * v;
                    }
                }
                store_tile(out, residual, cell * stride_ + o, a0, a1);
            }
        }
        // Row 121 of `out` is not written: nothing ever writes it, so it keeps the 0 of the allocation.
    }

    NetworkConfig config_;
    std::size_t stride_;         // row stride S of the internal activations
    std::size_t hidden_stride_;  // length of the internal hidden vector
    std::vector<float> conv_in_weight_, conv_in_bias_;
    std::vector<std::vector<float>> block_weight_, block_bias_;
    std::vector<float> policy_weight_, policy_bias_, dense1_weight_, dense1_bias_, dense2_weight_, dense2_bias_;
    float prior_beta_ = 0;
    float prior_k_ = 0;
    std::vector<float> x_, t_, u_, pooled_, hidden_;
};

}  // namespace

std::unique_ptr<SimdEngine> make_engine(const Parameters& parameters) { return std::make_unique<Engine>(parameters); }

}  // namespace sh::nn::SH_SIMD_NS
