// C++23 adapter between Parameters and the CudaNet of src/gpu/cuda (WP12). It does everything that the device layer must not
// know about the project: it packs the tensors of a Parameters object into the spans of cuda::Weights, applies the bucket rule
// and the variant rule of TornadoNetwork (naive while bucket * channels <= 128, else tiled: TornadoVM refuses simdgroup on
// CUDA too), splits large batches into chunks, refuses a dirty padding row like the reference, and runs the host priors.
// The device side is either cuda_net.cu (real device code, built by scripts/build-cuda.sh) or the stub (no CUDA): in the stub
// build create() fails with "no CUDA device in this build" and everything else of the adapter still runs and is tested.
#pragma once

#include <cstdint>
#include <expected>
#include <functional>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "buckets.hpp"
#include "config.hpp"
#include "cuda_net.hpp"
#include "parameters.hpp"

namespace sh {

// Name of the backend this build contains: "cuda" with device code, "cuda-stub" without.
std::string_view cuda_backend_name();

// The tensors of a Parameters object laid out for CudaNet: spans into the Parameters (and into the static gather table), so
// the Parameters must outlive every use. This is the "argument packing" that cuda_adapter_test checks.
struct PackedWeights {
    cuda::Shape shape;
    cuda::Weights weights;
};
PackedWeights pack_weights(const nn::Parameters& parameters);

struct CudaOptions {
    int max_batch = nn::kDefaultMaxBatch;       // positions per execution at most; larger batches are split
    std::optional<nn::ConvVariant> variant;     // empty = the rule per bucket; Simdgroup is refused
    cuda::HostMemory memory = cuda::HostMemory::Pageable;
    bool instrumented = false;                  // per-kernel events; the call is not slower for it, but its times are labelled as profiled
};

// Totals over the calls since the last reset_timings(), in milliseconds: the CPU time of the staging copies and of the host
// priors, the device time (cudaEvent) of the three stages.
struct CudaPhaseTimes {
    std::int64_t calls = 0;  // chunks, i.e. executions
    double copy_in_ms = 0, h2d_ms = 0, kernel_ms = 0, d2h_ms = 0, copy_out_ms = 0, priors_ms = 0;
};

// Makes the device net. The default is cuda::CudaNet::create; the tests pass a factory that returns an emulation on the CPU.
using NetFactory = std::function<std::unique_ptr<cuda::CudaNet>(const cuda::Shape&, const cuda::Weights&, const cuda::Options&, std::string&)>;

class CudaBackend {
public:
    // Fails if the options are out of range, Simdgroup is asked for, or the device layer fails (no device, no memory, ...).
    // The Parameters are read in create() only.
    static std::expected<CudaBackend, nn::Error> create(const nn::Parameters& parameters, const CudaOptions& options = {},
                                                        const NetFactory& factory = {});

    const nn::NetworkConfig& config() const { return config_; }
    const CudaOptions& options() const { return options_; }
    int plans_built() const { return static_cast<int>(net_->setup().size()); }  // (bucket, variant) pairs in use so far
    double init_ms() const { return net_->init_ms(); }
    const std::vector<cuda::BucketSetup>& setup() const { return net_->setup(); }
    const CudaPhaseTimes& timings() const { return times_; }
    void reset_timings() { times_ = CudaPhaseTimes{}; }
    const std::vector<cuda::KernelTime>& kernel_times() const { return kernel_log_; }  // last forward(), instrumented mode only

    // input: batch x [122][planes]; logits: batch x 242; values: batch x players (after the tanh). Fails on a wrong span size,
    // batch < 1, a dirty padding row (like the reference) or a failed CUDA call.
    std::expected<void, nn::Error> forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values);

private:
    CudaBackend() = default;
    nn::NetworkConfig config_;
    CudaOptions options_;
    bool priors_ = false;
    float beta_ = 0, k_ = 0;
    std::unique_ptr<cuda::CudaNet> net_;
    CudaPhaseTimes times_;
    std::vector<cuda::KernelTime> kernel_log_;
};

}  // namespace sh
