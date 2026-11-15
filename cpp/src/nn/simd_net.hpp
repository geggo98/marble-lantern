// The CPU SIMD forward pass (Java: VectorNetwork). Weights are repacked once into tiles of 2L output channels (L = float
// lanes), the convolution works on 4 cells at a time with 8 accumulators, channels are padded with zero weights to a multiple
// of 2L, and forward() allocates nothing. One instance is not thread-safe (its scratch buffers are members): one per thread.
//
// Two variants of the same source (src/nn/simd_net.cpp, built twice):
//   Fma    simd_net.cpp, built with -ffp-contract=fast: `acc + w * x` becomes a fused multiply-add, as in Java's fma.
//   NoFma  simd_net_nofma.cpp includes the same source with -ffp-contract=off and the namespace simd_nofma.
// scripts/check-fma.sh proves in the object code that the first has fused instructions in the inner loop and the second none.
#pragma once

#include <cstdint>
#include <expected>
#include <memory>
#include <span>

#include "config.hpp"
#include "parameters.hpp"

// Float lanes of one vector: from the target, overridable with -DSH_SIMD_LANES=N (4, 8 or 16). Printed by the benchmark header
// like the Java species.
#ifndef SH_SIMD_LANES
#if defined(__AVX512F__)
#define SH_SIMD_LANES 16
#elif defined(__AVX2__)
#define SH_SIMD_LANES 8
#else
#define SH_SIMD_LANES 4  // NEON, SSE2 and the generic fallback
#endif
#endif

namespace sh::nn {

inline constexpr int kSimdLanes = SH_SIMD_LANES;

// What both variants implement. forward() has the contract of ReferenceNet::forward.
class SimdEngine {
public:
    virtual ~SimdEngine() = default;
    virtual std::expected<void, Error> forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) = 0;
};

namespace simd_fma {
std::unique_ptr<SimdEngine> make_engine(const Parameters& parameters);
}  // namespace simd_fma
namespace simd_nofma {
std::unique_ptr<SimdEngine> make_engine(const Parameters& parameters);
}  // namespace simd_nofma

enum class SimdVariant : std::uint8_t { Fma, NoFma };

class SimdNet {
public:
    // Packs the parameters; the net keeps no reference to them.
    explicit SimdNet(const Parameters& parameters, SimdVariant variant = SimdVariant::Fma)
        : config_(parameters.config()),
          variant_(variant),
          engine_(variant == SimdVariant::Fma ? simd_fma::make_engine(parameters) : simd_nofma::make_engine(parameters)) {}

    const NetworkConfig& config() const { return config_; }
    SimdVariant variant() const { return variant_; }
    static constexpr int lanes() { return kSimdLanes; }

    // input: batch x [122][planes]; logits: batch x 242; values: batch x players (after the tanh). Fails, and writes nothing,
    // on a wrong span size, batch < 1 or a dirty padding row in any position. Allocates nothing on success.
    std::expected<void, Error> forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) {
        return engine_->forward(input, batch, logits, values);
    }

private:
    NetworkConfig config_;
    SimdVariant variant_;
    std::unique_ptr<SimdEngine> engine_;
};

}  // namespace sh::nn
