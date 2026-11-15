// The scalar reference forward pass (Java: ReferenceOps, Network). Every dot product accumulates in double in ascending
// index order and is rounded to float once per output element; ReLU is java_max; the residual priors of classic1 are added
// as in Java. The logits are bit-identical to the Java reference, the values within 1 ulp (std::tanh against Math.tanh).
#pragma once

#include <expected>
#include <span>

#include "config.hpp"
#include "parameters.hpp"

namespace sh::nn {

// ReferenceOps, exposed for tests. All buffers are row-major [rows][channels].
// hex_conv refuses an input whose padding row (row 121) is not zero (a NaN counts as not zero).
std::expected<void, Error> hex_conv(std::span<const float> in, int in_channels, std::span<const float> weight, std::span<const float> bias,
                                    int out_channels, std::span<float> out);
std::expected<void, Error> pointwise(std::span<const float> in, int rows, int in_channels, std::span<const float> weight,
                                     std::span<const float> bias, int out_channels, std::span<float> out);

class ReferenceNet {
public:
    // The parameters must outlive the net.
    explicit ReferenceNet(const Parameters& parameters) : parameters_(&parameters) {}

    const NetworkConfig& config() const { return parameters_->config(); }

    // input: batch x [122][planes]; logits: batch x 242; values: batch x players (after the tanh).
    // Fails (and writes nothing useful) on a wrong span size, batch < 1 or a dirty padding row.
    std::expected<void, Error> forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) const;

private:
    const Parameters* parameters_;
};

}  // namespace sh::nn
