#include "priors.hpp"

#include "config.hpp"

namespace sh::nn {

int value_sign(int relative_player) { return relative_player == 0 ? 1 : relative_player == 1 ? -1 : 0; }

float value_base(std::span<const float> input, int stride, int relative_player) {
    // Encoder.index(stride, 0, EVAL) = 0 * stride + EVAL
    (void)stride;
    return static_cast<float>(value_sign(relative_player)) * input[static_cast<std::size_t>(kEval)];
}

void add_policy_prior(float beta, std::span<const float> input, int stride, std::span<float> policy) {
    const float scale = beta * static_cast<float>(kDistScale);
    for (int c = 0; c < kCells; ++c) {
        const float term = scale * input[static_cast<std::size_t>(c * stride + kDistOwn)];
        policy[static_cast<std::size_t>(c * kPolicyChannels + kFromChannel)] += term;
        policy[static_cast<std::size_t>(c * kPolicyChannels + kToChannel)] -= term;
    }
}

void add_value_prior(float k, std::span<const float> input, int stride, std::span<float> values) {
    for (std::size_t i = 0; i < values.size(); ++i) {
        values[i] += k * value_base(input, stride, static_cast<int>(i));
    }
}

}  // namespace sh::nn

#include <cmath>

namespace sh::nn {

void apply_host_priors(float beta, float k, std::span<const float> input, int stride, int players, int n, std::span<float> logits,
                       std::span<float> values) {
    const auto size = static_cast<std::size_t>(kRows) * static_cast<std::size_t>(stride);
    const auto p = static_cast<std::size_t>(players);
    for (std::size_t b = 0; b < static_cast<std::size_t>(n); ++b) {
        const std::span<const float> in = input.subspan(b * size, size);
        add_policy_prior(beta, in, stride, logits.subspan(b * static_cast<std::size_t>(kPolicySize), static_cast<std::size_t>(kPolicySize)));
        const std::span<float> v = values.subspan(b * p, p);
        for (std::size_t o = 0; o < p; ++o) {
            float pre = v[o];
            pre += k * value_base(in, stride, static_cast<int>(o));
            v[o] = static_cast<float>(std::tanh(static_cast<double>(pre)));
        }
    }
}

}  // namespace sh::nn
