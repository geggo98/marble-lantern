#include "cuda_backend.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <climits>
#include <string>
#include <utility>

#include "gather.hpp"
#include "priors.hpp"

namespace sh {
namespace {

std::unexpected<nn::Error> fail(std::string message) { return std::unexpected(nn::Error{std::move(message)}); }

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) { return std::chrono::duration<double, std::milli>(Clock::now() - t0).count(); }

cuda::Variant device_variant(nn::ConvVariant v) { return v == nn::ConvVariant::Tiled ? cuda::Variant::Tiled : cuda::Variant::Naive; }

}  // namespace

std::string_view cuda_backend_name() { return cuda::build_kind() == "cuda" ? "cuda" : "cuda-stub"; }

PackedWeights pack_weights(const nn::Parameters& p) {
    const nn::NetworkConfig& cfg = p.config();
    PackedWeights packed;
    packed.shape = cuda::Shape{cfg.channels, cfg.blocks, cfg.players, cfg.value_hidden, cfg.input_planes()};
    cuda::Weights& w = packed.weights;
    w.gather = std::span<const int>(nn::gather_table());
    const auto conv = [&](const std::string& prefix) { return cuda::ConvWeights{p.data(prefix + ".weight"), p.data(prefix + ".bias")}; };
    w.conv_in = conv("conv_in");
    for (int i = 0; i < cfg.blocks; ++i) {
        const std::string block = "block" + std::to_string(i);
        w.blocks.push_back({conv(block + ".conv1"), conv(block + ".conv2")});
    }
    w.policy_weight = p.data("head_policy.weight");
    w.policy_bias = p.data("head_policy.bias");
    w.dense1_weight = p.data("head_value.dense1.weight");
    w.dense1_bias = p.data("head_value.dense1.bias");
    w.dense2_weight = p.data("head_value.dense2.weight");
    w.dense2_bias = p.data("head_value.dense2.bias");
    return packed;
}

std::expected<CudaBackend, nn::Error> CudaBackend::create(const nn::Parameters& parameters, const CudaOptions& options, const NetFactory& factory) {
    if (options.max_batch < 1) return fail("max_batch must be >= 1, got " + std::to_string(options.max_batch));
    if (options.variant == nn::ConvVariant::Simdgroup) return fail("the simdgroup kernel is not available on CUDA (TornadoVM refuses it too)");
    const nn::NetworkConfig& cfg = parameters.config();
    if (static_cast<long long>(options.max_batch) * nn::kRows * cfg.channels > INT_MAX) {
        return fail("max_batch " + std::to_string(options.max_batch) + " is too large for " + std::to_string(cfg.channels) + " channels");
    }
    const PackedWeights packed = pack_weights(parameters);
    cuda::Options device;
    device.max_bucket = options.max_batch;
    device.memory = options.memory;
    device.instrumented = options.instrumented;
    std::string error;
    std::unique_ptr<cuda::CudaNet> net = factory ? factory(packed.shape, packed.weights, device, error) : cuda::CudaNet::create(packed.shape, packed.weights, device, error);
    if (net == nullptr) return fail(error.empty() ? "the device layer returned no net" : error);
    CudaBackend backend;
    backend.config_ = cfg;
    backend.options_ = options;
    backend.priors_ = nn::has_residual_priors(cfg.planes);
    if (backend.priors_) {
        backend.beta_ = parameters.data(nn::kPriorBeta)[0];
        backend.k_ = parameters.data(nn::kPriorK)[0];
    }
    backend.net_ = std::move(net);
    return backend;
}

std::expected<void, nn::Error> CudaBackend::forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) {
    if (batch < 1) return fail("batch must be >= 1, got " + std::to_string(batch));
    const auto b = static_cast<std::size_t>(batch);
    const auto in_size = static_cast<std::size_t>(config_.input_size());
    const auto planes = static_cast<std::size_t>(config_.input_planes());
    const auto players = static_cast<std::size_t>(config_.players);
    const auto policy = static_cast<std::size_t>(nn::kPolicySize);
    const auto need = [](const char* name, std::size_t have, std::size_t want) -> std::expected<void, nn::Error> {
        if (have < want) return fail(std::string(name) + " needs " + std::to_string(want) + " floats, has " + std::to_string(have));
        return {};
    };
    if (auto r = need("input", input.size(), b * in_size); !r) return r;
    if (auto r = need("logits", logits.size(), b * policy); !r) return r;
    if (auto r = need("values", values.size(), b * players); !r) return r;
    // Every position is checked before anything runs, as the reference refuses a dirty padding row too.
    for (std::size_t pos = 0; pos < b; ++pos) {
        const std::span<const float> pad = input.subspan(pos * in_size + static_cast<std::size_t>(nn::kPadding) * planes, planes);
        for (std::size_t plane = 0; plane < planes; ++plane) {
            if (pad[plane] != 0) return fail("padding row of input " + std::to_string(pos) + " is not zero in plane " + std::to_string(plane));
        }
    }
    kernel_log_.clear();
    for (const nn::Chunk& chunk : nn::chunks_of(batch, options_.max_batch)) {
        const auto first = static_cast<std::size_t>(chunk.first);
        const auto n = static_cast<std::size_t>(chunk.n);
        const auto in = input.subspan(first * in_size, n * in_size);
        const auto lo = logits.subspan(first * policy, n * policy);
        const auto va = values.subspan(first * players, n * players);
        cuda::Call call;
        call.n = chunk.n;
        call.bucket = chunk.bucket;
        call.variant = device_variant(options_.variant.value_or(nn::variant_for(chunk.bucket, config_.channels, false)));
        call.apply_tanh = !priors_;
        cuda::CallTimes t;
        if (std::string error = net_->forward_raw(in, call, lo, va, t); !error.empty()) return fail(std::move(error));
        times_.copy_in_ms += t.copy_in_ms;
        times_.h2d_ms += t.h2d_ms;
        times_.kernel_ms += t.kernel_ms;
        times_.d2h_ms += t.d2h_ms;
        times_.copy_out_ms += t.copy_out_ms;
        ++times_.calls;
        for (cuda::KernelTime& k : t.kernels) {  // summed by name over the chunks
            const auto it = std::ranges::find_if(kernel_log_, [&](const cuda::KernelTime& e) { return e.name == k.name; });
            if (it != kernel_log_.end()) {
                it->ms += k.ms;
            } else {
                kernel_log_.push_back(std::move(k));
            }
        }
        if (priors_) {
            const auto t0 = Clock::now();
            nn::apply_host_priors(beta_, k_, in, config_.input_planes(), config_.players, chunk.n, lo, va);
            times_.priors_ms += ms_since(t0);
        }
    }
    return {};
}

}  // namespace sh
