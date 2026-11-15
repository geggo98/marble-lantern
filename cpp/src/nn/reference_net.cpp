#include "reference_net.hpp"

#include <cmath>
#include <vector>

#include "gather.hpp"
#include "java_compat.hpp"
#include "priors.hpp"

namespace sh::nn {
namespace {

std::unexpected<Error> fail(std::string message) { return std::unexpected(Error{std::move(message)}); }

std::expected<void, Error> require_length(const char* name, std::size_t have, std::size_t need) {
    if (have < need) return fail(std::string(name) + " needs " + std::to_string(need) + " floats, has " + std::to_string(have));
    return {};
}

void relu(std::span<float> a) {
    for (float& v : a) v = sh::java_max(v, 0.0f);  // keeps NaN, turns -0 into +0
}

void add(std::span<float> a, std::span<const float> b) {
    for (std::size_t i = 0; i < a.size(); ++i) a[i] += b[i];
}

void mean_over_cells(std::span<const float> in, int channels, std::span<float> out) {
    const auto ch = static_cast<std::size_t>(channels);
    for (std::size_t c = 0; c < ch; ++c) {
        double sum = 0;
        for (std::size_t cell = 0; cell < static_cast<std::size_t>(kCells); ++cell) sum += static_cast<double>(in[cell * ch + c]);
        out[c] = static_cast<float>(sum / kCells);
    }
}

void tanh_in_place(std::span<float> a) {
    for (float& v : a) v = static_cast<float>(std::tanh(static_cast<double>(v)));
}

}  // namespace

std::expected<void, Error> hex_conv(std::span<const float> in, int in_channels, std::span<const float> weight, std::span<const float> bias,
                                    int out_channels, std::span<float> out) {
    const auto cin = static_cast<std::size_t>(in_channels);
    const auto cout = static_cast<std::size_t>(out_channels);
    const std::size_t width = static_cast<std::size_t>(kStencil) * cin;
    if (auto r = require_length("in", in.size(), static_cast<std::size_t>(kRows) * cin); !r) return r;
    if (auto r = require_length("weight", weight.size(), cout * width); !r) return r;
    if (auto r = require_length("bias", bias.size(), cout); !r) return r;
    if (auto r = require_length("out", out.size(), static_cast<std::size_t>(kRows) * cout); !r) return r;
    for (std::size_t ch = 0; ch < cin; ++ch) {
        const float pad = in[static_cast<std::size_t>(kPadding) * cin + ch];
        if (pad != 0) return fail("padding row of the input is not zero in channel " + std::to_string(ch) + ": " + std::to_string(pad));
    }
    const GatherTable& table = gather_table();
    for (std::size_t c = 0; c < static_cast<std::size_t>(kCells); ++c) {
        for (std::size_t o = 0; o < cout; ++o) {
            double sum = static_cast<double>(bias[o]);
            for (std::size_t k = 0; k < static_cast<std::size_t>(kStencil); ++k) {
                const std::size_t row = static_cast<std::size_t>(table[c * kStencil + k]);
                const std::span<const float> w = weight.subspan(o * width + k * cin, cin);
                const std::span<const float> x = in.subspan(row * cin, cin);
                for (std::size_t ch = 0; ch < cin; ++ch) sum += static_cast<double>(w[ch]) * static_cast<double>(x[ch]);
            }
            out[c * cout + o] = static_cast<float>(sum);
        }
    }
    for (std::size_t o = 0; o < cout; ++o) out[static_cast<std::size_t>(kPadding) * cout + o] = 0.0f;
    return {};
}

std::expected<void, Error> pointwise(std::span<const float> in, int rows, int in_channels, std::span<const float> weight,
                                     std::span<const float> bias, int out_channels, std::span<float> out) {
    const auto n = static_cast<std::size_t>(rows);
    const auto cin = static_cast<std::size_t>(in_channels);
    const auto cout = static_cast<std::size_t>(out_channels);
    if (auto r = require_length("in", in.size(), n * cin); !r) return r;
    if (auto r = require_length("weight", weight.size(), cout * cin); !r) return r;
    if (auto r = require_length("bias", bias.size(), cout); !r) return r;
    if (auto r = require_length("out", out.size(), n * cout); !r) return r;
    for (std::size_t row = 0; row < n; ++row) {
        for (std::size_t o = 0; o < cout; ++o) {
            double sum = static_cast<double>(bias[o]);
            for (std::size_t i = 0; i < cin; ++i) sum += static_cast<double>(weight[o * cin + i]) * static_cast<double>(in[row * cin + i]);
            out[row * cout + o] = static_cast<float>(sum);
        }
    }
    return {};
}

std::expected<void, Error> ReferenceNet::forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) const {
    const NetworkConfig& cfg = config();
    if (batch < 1) return fail("batch must be >= 1, got " + std::to_string(batch));
    const auto b = static_cast<std::size_t>(batch);
    const auto in_size = static_cast<std::size_t>(cfg.input_size());
    const auto players = static_cast<std::size_t>(cfg.players);
    if (auto r = require_length("input", input.size(), b * in_size); !r) return r;
    if (auto r = require_length("logits", logits.size(), b * static_cast<std::size_t>(kPolicySize)); !r) return r;
    if (auto r = require_length("values", values.size(), b * players); !r) return r;

    const Parameters& p = *parameters_;
    const int c = cfg.channels;
    const int h = cfg.value_hidden;
    const auto activation = static_cast<std::size_t>(kRows) * static_cast<std::size_t>(c);
    const bool priors = has_residual_priors(cfg.planes);
    std::vector<float> x(activation);
    std::vector<float> t(activation);
    std::vector<float> u(activation);
    std::vector<float> pooled(static_cast<std::size_t>(c));
    std::vector<float> hidden(static_cast<std::size_t>(h));

    for (std::size_t pos = 0; pos < b; ++pos) {
        const std::span<const float> in = input.subspan(pos * in_size, in_size);
        const std::span<float> policy = logits.subspan(pos * static_cast<std::size_t>(kPolicySize), static_cast<std::size_t>(kPolicySize));
        const std::span<float> value = values.subspan(pos * players, players);

        // 1. conv_in, bias, ReLU. Row 121 is written as 0 and ReLU keeps it 0.
        if (auto r = hex_conv(in, cfg.input_planes(), p.data("conv_in.weight"), p.data("conv_in.bias"), c, x); !r) return r;
        relu(x);
        // 2. and 3. residual blocks: x = ReLU(conv2(ReLU(conv1(x))) + x).
        for (int i = 0; i < cfg.blocks; ++i) {
            const std::string block = "block" + std::to_string(i);
            if (auto r = hex_conv(x, c, p.data(block + ".conv1.weight"), p.data(block + ".conv1.bias"), c, t); !r) return r;
            relu(t);
            if (auto r = hex_conv(t, c, p.data(block + ".conv2.weight"), p.data(block + ".conv2.bias"), c, u); !r) return r;
            add(u, x);
            relu(u);
            x.swap(u);
        }
        // 4. policy head: 1x1 convolution on the 121 cell rows, no activation.
        if (auto r = pointwise(x, kCells, c, p.data("head_policy.weight"), p.data("head_policy.bias"), kPolicyChannels, policy); !r) return r;
        if (priors) add_policy_prior(p.data(kPriorBeta)[0], in, cfg.input_planes(), policy);
        // 5. to 7. value head: mean over cells, dense + ReLU, dense (+ prior) + tanh.
        mean_over_cells(x, c, pooled);
        if (auto r = pointwise(pooled, 1, c, p.data("head_value.dense1.weight"), p.data("head_value.dense1.bias"), h, hidden); !r) return r;
        relu(hidden);
        if (auto r = pointwise(hidden, 1, h, p.data("head_value.dense2.weight"), p.data("head_value.dense2.bias"), cfg.players, value); !r) return r;
        if (priors) add_value_prior(p.data(kPriorK)[0], in, cfg.input_planes(), value);
        tanh_in_place(value);
    }
    return {};
}

}  // namespace sh::nn
