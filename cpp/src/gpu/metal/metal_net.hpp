// MetalNet: the Metal forward pass behind a plain C++ interface (pimpl, no Objective-C type). Port of TornadoNetwork on Metal.
// WP11a: the naive convolution, both heads, buckets, host priors, memory mode "copy" (private device buffers, explicit blit
// copies of the input to the device and of the head buffer back, as TornadoVM does on Metal). WP11b: the tiled and simdgroup
// convolutions, the variant rule, the "shared" memory mode (one shared buffer for the input and one for the output, no blits),
// the phase timing and the instrumented mode (one command buffer per kernel).
#pragma once

#include <cstdint>
#include <expected>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

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
    int max_batch = nn::kDefaultMaxBatch;          // positions per execution at most; larger batches are split
    // The kernel of every convolution; empty = the rule of TornadoNetwork.variantFor per bucket: Naive while
    // bucket * channels <= 128, else Simdgroup.
    std::optional<nn::ConvVariant> variant;
    MemoryMode memory = MemoryMode::Copy;
    // Instrumented mode: one command buffer per kernel (and per blit), each timed by the GPU clock (kernel_times()). It
    // slows the call, so its wall time is not a result; the plain mode is the one that is compared.
    bool instrumented = false;
};

// Totals over the calls since the last reset_timings(), in milliseconds. CPU clock: copy_in (host to the input buffer or its
// staging buffer), commit_wait (commit of the command buffer(s) until they have completed: the blits, the kernels and the
// waiting), copy_out (output buffer to the caller's spans), priors (host priors). gpu is the sum of GPUEndTime - GPUStartTime
// of the command buffers (the time the GPU worked, blits included, queueing excluded).
struct PhaseTimes {
    std::int64_t calls = 0;  // forward() chunks, i.e. executions
    double copy_in_ms = 0, commit_wait_ms = 0, gpu_ms = 0, copy_out_ms = 0, priors_ms = 0;
};

// One kernel or blit of the last forward() call in instrumented mode (summed over chunks), by GPU clock.
struct KernelTime {
    std::string name;  // "blit_in", "conv_in", "block0.conv1", ..., "policy_head", "value_head", "blit_out"
    double gpu_ms = 0;
};

// What the first call of a bucket cost: pipeline creation (three pipelines of the bucket) and buffer allocation, in milliseconds.
struct BucketSetup {
    int bucket = 0;
    nn::ConvVariant variant = nn::ConvVariant::Naive;
    double pipeline_ms = 0, buffer_ms = 0;
};

// Milliseconds of the one newLibraryWithSource of the process (compiles kernels.metal; 0 if the library failed).
double library_compile_ms();

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
    const PhaseTimes& timings() const;
    void reset_timings();
    const std::vector<KernelTime>& kernel_times() const;  // last forward(), instrumented mode only (else empty)
    const std::vector<BucketSetup>& setup() const;        // one entry per bucket built, in order

    // input: batch x [122][planes]; logits: batch x 242; values: batch x players (after the tanh). Fails on a wrong span
    // size, batch < 1, a dirty padding row (like the reference) or a failed command buffer.
    std::expected<void, nn::Error> forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values);

private:
    struct Impl;
    explicit MetalNet(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

// ---- single kernels, for the tests (shared-storage buffers, one command buffer per call) ----

// One convolution kernel (KernelsHexConv.naive, tiled or simdgroup) over [batch][122][in_channels] -> [batch][122][out_channels].
// `out` is uploaded first, so a NaN-filled span shows every element that the kernel did not write. `residual` is read only if
// use_residual. Returns the GPU time of the command buffer in milliseconds (for the kernel table).
std::expected<double, nn::Error> run_conv(nn::ConvVariant variant, std::span<const float> in, std::span<const float> weight, std::span<const float> bias,
                                          std::span<const float> residual, std::span<float> out, int batch, int in_channels,
                                          int out_channels, bool use_residual, bool relu);

// Test seam: from now on a buffer above this many bytes is refused like a device that returns nil (the call must fail with
// an error and never write through a null pointer). The default is no limit.
void set_buffer_limit_for_test(std::size_t bytes);

// run_conv with the naive kernel (WP11a).
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
