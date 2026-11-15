// A CPU emulation of the CUDA execution model, to run the REAL kernels of src/gpu/cuda/kernels.cuh without a GPU.
//   - a block is run by one std::thread per CUDA thread, __syncthreads() is a std::barrier of the block, __shared__ is a static
//     array (blocks run one after the other, so one copy is enough);
//   - kernels without a barrier run their threads one after the other on the calling thread;
//   - device buffers are exact-size std::vector<float> (the callers' own), so AddressSanitizer sees an out-of-bounds access of a
//     kernel, which no GPU would report (docs/cpp-comparison-plan.md, "Unsafe zones": device code has no bounds checks);
//   - EmuNet implements cuda::CudaNet on top of it, with the launch order of kernels::run_forward, so that the adapter
//     (src/nn/cuda_backend.cpp) can be tested without a device.
// What it does not emulate: warp scheduling, memory ordering across blocks, fused multiply-add (the build is -ffp-contract=off),
// the CUDA runtime. Only cuda_adapter_test.cpp includes this file (the kernels are defined in the header, once per program).
#pragma once

#include <algorithm>
#include <barrier>
#include <cmath>
#include <cstddef>
#include <map>
#include <memory>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "cuda_net.hpp"

namespace sh::emu {

struct Dim3 {
    unsigned x = 0, y = 0, z = 0;
};

inline thread_local Dim3 tid;    // threadIdx of the running emulated thread
inline thread_local Dim3 bid;    // blockIdx
inline thread_local Dim3 bdim;   // blockDim
inline std::barrier<>* block_barrier = nullptr;  // of the block that runs now (threads of one block only)

inline void sync() { block_barrier->arrive_and_wait(); }

}  // namespace sh::emu

// The CUDA keywords of kernels.cuh, as plain C++.
#define __global__
#define __device__
#define __shared__ static
#define __syncthreads() ::sh::emu::sync()
#define threadIdx (::sh::emu::tid)
#define blockIdx (::sh::emu::bid)
#define blockDim (::sh::emu::bdim)

#include "kernels.cuh"

namespace sh::emu {

// Runs `body` once per thread of every block of the grid. uses_barrier: the kernel calls __syncthreads (then the threads of a
// block run concurrently, else one after the other).
template <class Body>
void launch(const cuda::kernels::LaunchDims& d, bool uses_barrier, const Body& body) {
    const auto gx = static_cast<unsigned>(d.grid_x);
    const auto gy = static_cast<unsigned>(d.grid_y);
    const auto bx = static_cast<unsigned>(d.block_x);
    const auto by = static_cast<unsigned>(d.block_y);
    for (unsigned y = 0; y < gy; ++y) {
        for (unsigned x = 0; x < gx; ++x) {
            const Dim3 block{x, y, 0};
            const Dim3 dims{bx, by, 1};
            if (!uses_barrier) {
                bid = block;
                bdim = dims;
                for (unsigned ty = 0; ty < by; ++ty) {
                    for (unsigned tx = 0; tx < bx; ++tx) {
                        tid = Dim3{tx, ty, 0};
                        body();
                    }
                }
                continue;
            }
            std::barrier<> barrier(static_cast<std::ptrdiff_t>(bx * by));
            block_barrier = &barrier;
            std::vector<std::thread> threads;
            threads.reserve(bx * by);
            for (unsigned ty = 0; ty < by; ++ty) {
                for (unsigned tx = 0; tx < bx; ++tx) {
                    threads.emplace_back([&body, block, dims, tx, ty] {
                        bid = block;
                        bdim = dims;
                        tid = Dim3{tx, ty, 0};
                        body();
                    });
                }
            }
            for (std::thread& t : threads) t.join();
            block_barrier = nullptr;
        }
    }
}

namespace k = sh::cuda::kernels;

// The launcher of kernels::run_forward that runs the kernels on the CPU. `labels` records the launch order.
struct EmuLauncher {
    const int* gather = nullptr;
    std::vector<std::string> labels;

    void conv(k::ConvKind kind, const float* in, const k::ConvLayer& layer, const float* res, float* out, const k::ConvArgs& a,
              const std::string& label) {
        labels.push_back(label);
        if (kind == k::ConvKind::Naive) {
            launch(k::naive_dims(a.out_channels, a.batch), false, [&] { k::hexconv_naive(in, gather, layer.weight, layer.bias, res, out, a); });
        } else {
            launch(k::tiled_dims(a.out_channels, a.batch), true, [&] { k::hexconv_tiled(in, gather, layer.weight, layer.bias, res, out, a); });
        }
    }
    void policy(const float* trunk, const float* weight, const float* bias, float* out, const k::HeadArgs& a, const std::string& label) {
        labels.push_back(label);
        launch(k::policy_dims(a.batch), false, [&] { k::head_policy(trunk, weight, bias, out, a); });
    }
    void value(const float* trunk, const float* w1, const float* b1, const float* w2, const float* b2, float* out, const k::HeadArgs& a,
               const std::string& label) {
        labels.push_back(label);
        launch(k::value_dims(a.batch), true, [&] { k::head_value(trunk, w1, b1, w2, b2, out, a); });
    }
};

// cuda::CudaNet on the CPU: the same bucket plans, the same launch order, device buffers as exact-size vectors.
class EmuNet final : public cuda::CudaNet {
public:
    static std::unique_ptr<cuda::CudaNet> create(const cuda::Shape& shape, const cuda::Weights& weights, const cuda::Options& options, std::string& error,
                                                 EmuNet** observer = nullptr) {
        if (weights.blocks.size() != static_cast<std::size_t>(shape.blocks) || weights.gather.size() != 121 * 7) {
            error = "emulated net: weights do not match the shape";
            return nullptr;
        }
        auto net = std::make_unique<EmuNet>();
        net->shape_ = shape;
        net->options_ = options;
        net->gather_.assign(weights.gather.begin(), weights.gather.end());
        net->zero_.assign(1, 0.0f);
        k::NetView& v = net->view_;
        v.gather = net->gather_.data();
        v.no_residual = net->zero_.data();
        const auto conv = [&](const cuda::ConvWeights& c) {
            return k::ConvLayer{net->keep(c.weight), net->keep(c.bias)};
        };
        v.conv_in = conv(weights.conv_in);
        for (const auto& block : weights.blocks) v.blocks.push_back(k::BlockLayers{conv(block[0]), conv(block[1])});
        v.policy_weight = net->keep(weights.policy_weight);
        v.policy_bias = net->keep(weights.policy_bias);
        v.dense1_weight = net->keep(weights.dense1_weight);
        v.dense1_bias = net->keep(weights.dense1_bias);
        v.dense2_weight = net->keep(weights.dense2_weight);
        v.dense2_bias = net->keep(weights.dense2_bias);
        if (observer != nullptr) *observer = net.get();
        return net;
    }

    const cuda::Shape& shape() const override { return shape_; }
    const cuda::Options& options() const override { return options_; }
    double init_ms() const override { return 0; }
    const std::vector<cuda::BucketSetup>& setup() const override { return setup_; }

    std::vector<cuda::Call> calls;                  // every forward_raw call, in order
    std::vector<std::vector<std::string>> launches;  // the launch order of each call

    std::string forward_raw(std::span<const float> input, const cuda::Call& call, std::span<float> logits, std::span<float> values,
                            cuda::CallTimes& times) override {
        times = cuda::CallTimes{};
        calls.push_back(call);
        if (call.n < 1 || call.bucket < call.n || call.bucket > options_.max_bucket) return "n and bucket out of range";
        const auto n = static_cast<std::size_t>(call.n);
        const auto bucket = static_cast<std::size_t>(call.bucket);
        const auto planes = static_cast<std::size_t>(shape_.input_planes);
        const auto channels = static_cast<std::size_t>(shape_.channels);
        const auto players = static_cast<std::size_t>(shape_.players);
        if (input.size() < n * k::kRows * planes || logits.size() < n * k::kPolicySize || values.size() < n * players) return "span too small";
        Plan& p = plan(call.bucket, call.variant, bucket * k::kRows * planes, bucket * k::kRows * channels, bucket * (k::kPolicySize + players));
        std::copy_n(input.begin(), n * k::kRows * planes, p.input.begin());  // slots n.. of the bucket keep what they held
        k::PassShape pass;
        pass.batch = call.bucket;
        pass.input_planes = shape_.input_planes;
        pass.channels = shape_.channels;
        pass.hidden = shape_.hidden;
        pass.players = shape_.players;
        pass.apply_tanh = call.apply_tanh;
        pass.conv = call.variant == cuda::Variant::Tiled ? k::ConvKind::Tiled : k::ConvKind::Naive;
        k::Work work;
        work.input = p.input.data();
        work.x = p.x.data();
        work.t = p.t.data();
        work.u = p.u.data();
        work.out = p.out.data();
        EmuLauncher launcher;
        launcher.gather = view_.gather;
        k::run_forward(launcher, view_, pass, work);
        if (options_.instrumented) {
            for (const std::string& name : launcher.labels) times.kernels.push_back(cuda::KernelTime{name, 0.0});
        }
        launches.push_back(launcher.labels);
        std::copy_n(p.out.begin(), n * k::kPolicySize, logits.begin());
        std::copy_n(p.out.begin() + static_cast<std::ptrdiff_t>(bucket * k::kPolicySize), n * players, values.begin());
        return {};
    }

private:
    struct Plan {
        std::vector<float> input, x, t, u, out;
    };

    // Plans start filled with NaN, so that an element that a kernel does not write shows up as NaN in the comparison. The
    // real device layer starts with zeros; the NaN is the stricter test.
    Plan& plan(int bucket, cuda::Variant variant, std::size_t input_floats, std::size_t activation, std::size_t out_floats) {
        const auto key = std::make_pair(bucket, static_cast<int>(variant));
        auto it = plans_.find(key);
        if (it == plans_.end()) {
            const float nan = std::nanf("");
            Plan p;
            p.input.assign(input_floats, 0.0f);  // the padding rows and unused slots must read as zero
            p.x.assign(activation, nan);
            p.t.assign(activation, nan);
            p.u.assign(activation, nan);
            p.out.assign(out_floats, nan);
            it = plans_.emplace(key, std::move(p)).first;
            setup_.push_back(cuda::BucketSetup{bucket, variant, 0.0});
        }
        return it->second;
    }

    const float* keep(std::span<const float> data) {
        owned_.emplace_back(data.begin(), data.end());
        return owned_.back().data();
    }

    cuda::Shape shape_;
    cuda::Options options_;
    std::vector<int> gather_;
    std::vector<float> zero_;
    std::vector<std::vector<float>> owned_;  // one exact-size vector per tensor
    k::NetView view_;
    std::map<std::pair<int, int>, Plan> plans_;
    std::vector<cuda::BucketSetup> setup_;
};

}  // namespace sh::emu
