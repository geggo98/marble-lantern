// Deterministic networks and inputs for the CUDA tests (cuda_adapter_test.cpp on the Mac, cuda_test.cpp on the instance): no
// golden file needed, only the SplittableRandom port with fixed seeds. Weights follow the u5 bound sqrt(6 / (7 C)), inputs are
// in [0, 1) with a zero padding row.
#pragma once

#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <cstdint>
#include <iostream>
#include <utility>
#include <vector>

#include "config.hpp"
#include "parameters.hpp"
#include "safetensors.hpp"
#include "splittable_random.hpp"

namespace sh::testdata {

inline float unit(sh::SplittableRandom& r) { return static_cast<float>(static_cast<double>(r.next_long() >> 40) / static_cast<double>(1 << 24)); }

inline std::vector<float> uniform(std::size_t n, float bound, sh::SplittableRandom& r) {
    std::vector<float> v(n);
    for (float& x : v) x = (2.0f * unit(r) - 1.0f) * bound;
    return v;
}

// Tensors of a config with the u5 bound sqrt(6 / (7 C)), in the order of parameter_shapes.
inline std::vector<nn::Tensor> random_tensors(const nn::NetworkConfig& config, std::uint64_t seed) {
    sh::SplittableRandom rng(seed);
    const auto bound = static_cast<float>(std::sqrt(6.0 / (7.0 * config.channels)));
    std::vector<nn::Tensor> tensors;
    for (const auto& [name, shape] : nn::parameter_shapes(config)) {
        nn::Tensor t;
        t.name = name;
        t.shape = shape;
        t.data = uniform(static_cast<std::size_t>(*nn::element_count(shape)), bound, rng);
        tensors.push_back(std::move(t));
    }
    return tensors;
}

inline nn::Parameters make_params(const nn::NetworkConfig& config, std::uint64_t seed) {
    auto p = nn::Parameters::from_tensors(config, random_tensors(config, seed));
    if (!p) {
        std::cerr << "make_params: " << p.error().message << '\n';
        std::abort();
    }
    return std::move(*p);
}

// n positions of [122][planes], values in [0, 1), the padding row zero.
inline std::vector<float> make_inputs(const nn::NetworkConfig& config, int n, std::uint64_t seed) {
    sh::SplittableRandom rng(seed);
    const auto size = static_cast<std::size_t>(config.input_size());
    std::vector<float> in(static_cast<std::size_t>(n) * size);
    for (float& x : in) x = unit(rng);
    for (int pos = 0; pos < n; ++pos) {
        for (int plane = 0; plane < config.input_planes(); ++plane) {
            in[static_cast<std::size_t>(pos) * size + static_cast<std::size_t>(nn::kPadding * config.input_planes() + plane)] = 0.0f;
        }
    }
    return in;
}

}  // namespace sh::testdata
