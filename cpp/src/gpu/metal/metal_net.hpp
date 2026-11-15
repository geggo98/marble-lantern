// MetalNet: the Metal forward pass behind a plain C++ interface (pimpl, no Objective-C type). Port of TornadoNetwork on Metal.
// WP11a: the naive convolution, both heads, buckets, host priors, memory mode "copy" (private device buffers, explicit blit
// copies of the input to the device and of the head buffer back, as TornadoVM does on Metal). WP11b adds the other kernels,
// the shared memory mode and the phase timing.
#pragma once

#include <expected>
#include <memory>
#include <span>
#include <string>
#include <string_view>

#include "buckets.hpp"
#include "config.hpp"
#include "parameters.hpp"

namespace sh::metal {

// True if a Metal device is available on this machine.
bool device_available();

// Name of the default Metal device ("" without one).
std::string device_name();

// The MSL source of kernels.metal, embedded at build time (cmake/EmbedText.cmake).
std::string_view kernel_source();

enum class MemoryMode : std::uint8_t { Copy, Shared };

struct Options {
    int max_batch = nn::kDefaultMaxBatch;               // positions per execution at most; larger batches are split
    nn::ConvVariant variant = nn::ConvVariant::Naive;   // the kernel of every convolution (WP11a: Naive only)
    MemoryMode memory = MemoryMode::Copy;               // WP11a: Copy only
};

// The whole forward pass on the GPU, like TornadoNetwork: one command buffer per call (per chunk of max_batch positions), one
// pipeline set and buffer set per power-of-two bucket built on first use, the residual priors of classic1 on the host.
// Not thread-safe. The Parameters are read in create() only (as TornadoNetwork reads them in its constructor).
class MetalNet {
public:
    static std::expected<MetalNet, nn::Error> create(const nn::Parameters& parameters, const Options& options = {});
    MetalNet(MetalNet&&) noexcept;
    MetalNet& operator=(MetalNet&&) noexcept;
    ~MetalNet();

    const nn::NetworkConfig& config() const;
    const Options& options() const;
    int plans_built() const;  // buckets in use so far

    // input: batch x [122][planes]; logits: batch x 242; values: batch x players (after the tanh). Fails on a wrong span
    // size, batch < 1, a dirty padding row (like the reference) or a failed command buffer.
    std::expected<void, nn::Error> forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values);

private:
    struct Impl;
    explicit MetalNet(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

// ---- single kernels, for the tests (shared-storage buffers, one command buffer per call) ----

// KernelsHexConv.naive over [batch][122][in_channels] -> [batch][122][out_channels]. `out` is uploaded first, so a NaN-filled
// span shows every element that the kernel did not write. `residual` is read only if use_residual.
std::expected<void, nn::Error> run_conv_naive(std::span<const float> in, std::span<const float> weight, std::span<const float> bias,
                                              std::span<const float> residual, std::span<float> out, int batch, int in_channels,
                                              int out_channels, bool use_residual, bool relu);

// OpsKernels.policyHead and valueHead on a trunk [batch][122][channels]; logits [batch][242] and values [batch][players] are
// uploaded first (NaN-filled spans show unwritten elements). apply_tanh as in the Java kernel.
struct HeadWeights {
    std::span<const float> policy_weight, policy_bias;      // [2][C], [2]
    std::span<const float> dense1_weight, dense1_bias;      // [H][C], [H]
    std::span<const float> dense2_weight, dense2_bias;      // [P][H], [P]
};
std::expected<void, nn::Error> run_heads(std::span<const float> trunk, const HeadWeights& weights, int batch, int channels, int hidden,
                                         int players, bool apply_tanh, std::span<float> logits, std::span<float> values);

}  // namespace sh::metal
