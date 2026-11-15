// Device code and the thin host layer of the CUDA backend (nvcc -std=c++20 -ccbin g++-12; built by build-cuda.sh, never by
// CMake). Includes only cuda_net.hpp, kernels.cuh and standard and CUDA headers, never a project header.
//
// Unsafe zone (docs/cpp-comparison-plan.md): cudaMalloc, cudaHostAlloc and raw device pointers. Containment: the RAII classes
// DeviceBuffer, PinnedBuffer, Stream and Event below own every resource (deleted copy), host memory is handed out as
// std::span, and the kernels (kernels.cuh) are the only code that indexes a device pointer.
//
// This file could not be compiled or run where it was written (no nvcc, no GPU); scripts/ptx-check.sh checks syntax and types
// with clang, and tests/cuda_adapter_test.cpp runs the same kernels and launch order on the CPU. The first real run is the
// CUDA gate of the AWS run (tests/cuda_test.cpp).
#include <cuda_runtime.h>

#include <algorithm>
#include <chrono>
#include <climits>
#include <cstddef>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "cuda_net.hpp"
#include "kernels.cuh"

namespace sh::cuda {
namespace {

namespace k = sh::cuda::kernels;

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t0) { return std::chrono::duration<double, std::milli>(Clock::now() - t0).count(); }

// "" for cudaSuccess, else "<what>: <CUDA error text> (<code>)".
std::string check(cudaError_t e, const std::string& what) {
    if (e == cudaSuccess) return {};
    return what + ": " + cudaGetErrorString(e) + " (" + std::to_string(static_cast<int>(e)) + ")";
}

// ---- RAII for every CUDA resource ----

template <class T>
class DeviceBuffer {
public:
    DeviceBuffer() = default;
    // Allocates count elements (at least one) and zeroes them; on an error the buffer stays empty and `error` is set.
    static DeviceBuffer zeroed(std::size_t count, std::string& error) {
        DeviceBuffer b;
        const std::size_t bytes = std::max<std::size_t>(count, 1) * sizeof(T);
        void* p = nullptr;
        error = check(cudaMalloc(&p, bytes), "cudaMalloc " + std::to_string(bytes) + " bytes");
        if (!error.empty()) return b;
        b.ptr_ = static_cast<T*>(p);
        b.count_ = count;
        error = check(cudaMemset(p, 0, bytes), "cudaMemset");
        return b;
    }
    DeviceBuffer(const DeviceBuffer&) = delete;
    DeviceBuffer& operator=(const DeviceBuffer&) = delete;
    DeviceBuffer(DeviceBuffer&& o) noexcept : ptr_(std::exchange(o.ptr_, nullptr)), count_(std::exchange(o.count_, 0)) {}
    DeviceBuffer& operator=(DeviceBuffer&& o) noexcept {
        if (this != &o) {
            release();
            ptr_ = std::exchange(o.ptr_, nullptr);
            count_ = std::exchange(o.count_, 0);
        }
        return *this;
    }
    ~DeviceBuffer() { release(); }
    T* get() const { return ptr_; }
    std::size_t count() const { return count_; }

private:
    void release() {
        if (ptr_ != nullptr) cudaFree(ptr_);
        ptr_ = nullptr;
        count_ = 0;
    }
    T* ptr_ = nullptr;
    std::size_t count_ = 0;
};

// Page-locked host memory, handed out as a span.
class PinnedBuffer {
public:
    PinnedBuffer() = default;
    static PinnedBuffer make(std::size_t count, std::string& error) {
        PinnedBuffer b;
        void* p = nullptr;
        error = check(cudaHostAlloc(&p, std::max<std::size_t>(count, 1) * sizeof(float), cudaHostAllocDefault),
                      "cudaHostAlloc " + std::to_string(count * sizeof(float)) + " bytes");
        if (!error.empty()) return b;
        b.ptr_ = static_cast<float*>(p);
        b.count_ = count;
        return b;
    }
    PinnedBuffer(const PinnedBuffer&) = delete;
    PinnedBuffer& operator=(const PinnedBuffer&) = delete;
    PinnedBuffer(PinnedBuffer&& o) noexcept : ptr_(std::exchange(o.ptr_, nullptr)), count_(std::exchange(o.count_, 0)) {}
    PinnedBuffer& operator=(PinnedBuffer&& o) noexcept {
        if (this != &o) {
            release();
            ptr_ = std::exchange(o.ptr_, nullptr);
            count_ = std::exchange(o.count_, 0);
        }
        return *this;
    }
    ~PinnedBuffer() { release(); }
    std::span<float> span() const { return std::span<float>(ptr_, count_); }

private:
    void release() {
        if (ptr_ != nullptr) cudaFreeHost(ptr_);
        ptr_ = nullptr;
        count_ = 0;
    }
    float* ptr_ = nullptr;
    std::size_t count_ = 0;
};

class Stream {
public:
    Stream() = default;
    std::string create() { return check(cudaStreamCreate(&s_), "cudaStreamCreate"); }
    Stream(const Stream&) = delete;
    Stream& operator=(const Stream&) = delete;
    ~Stream() {
        if (s_ != nullptr) cudaStreamDestroy(s_);
    }
    cudaStream_t get() const { return s_; }

private:
    cudaStream_t s_ = nullptr;
};

class Event {
public:
    Event() = default;
    std::string create() { return check(cudaEventCreate(&e_), "cudaEventCreate"); }
    Event(const Event&) = delete;
    Event& operator=(const Event&) = delete;
    Event(Event&& o) noexcept : e_(std::exchange(o.e_, nullptr)) {}
    Event& operator=(Event&& o) noexcept {
        if (this != &o) {
            release();
            e_ = std::exchange(o.e_, nullptr);
        }
        return *this;
    }
    ~Event() { release(); }
    cudaEvent_t get() const { return e_; }

private:
    void release() {
        if (e_ != nullptr) cudaEventDestroy(e_);
        e_ = nullptr;
    }
    cudaEvent_t e_ = nullptr;
};

dim3 grid_of(const k::LaunchDims& d) { return dim3(static_cast<unsigned>(d.grid_x), static_cast<unsigned>(d.grid_y)); }
dim3 block_of(const k::LaunchDims& d) { return dim3(static_cast<unsigned>(d.block_x), static_cast<unsigned>(d.block_y)); }

}  // namespace

// ---- the net ----

namespace {

// The device and host buffers of one bucket (and one convolution kernel).
struct Plan {
    DeviceBuffer<float> input, x, t, u, out;
    PinnedBuffer in_pinned, out_pinned;           // pinned mode
    std::vector<float> in_pageable, out_pageable;  // pageable mode
    std::span<float> in_stage, out_stage;          // the host side of the two copies, whichever mode
};

// Everything one net owns.
struct State {
    Shape shape;
    Options options;
    double init_ms = 0;
    Stream stream;
    DeviceBuffer<int> gather;
    DeviceBuffer<float> no_residual;
    std::vector<DeviceBuffer<float>> weights;  // owns every uploaded tensor
    k::NetView view;
    std::map<std::pair<int, int>, std::unique_ptr<Plan>> plans;  // (bucket, variant)
    std::vector<BucketSetup> setup;
    std::vector<Event> events;

    // Copies a host span to a new device buffer (synchronously, from pageable memory) and returns its pointer.
    const float* upload(std::span<const float> data, std::string& error) {
        DeviceBuffer<float> b = DeviceBuffer<float>::zeroed(data.size(), error);
        if (!error.empty()) return nullptr;
        error = check(cudaMemcpy(b.get(), data.data(), data.size_bytes(), cudaMemcpyHostToDevice), "cudaMemcpy of a weight tensor");
        if (!error.empty()) return nullptr;
        const float* p = b.get();
        weights.push_back(std::move(b));
        return p;
    }

    std::string ensure_events(std::size_t count) {
        while (events.size() < count) {
            Event e;
            if (std::string err = e.create(); !err.empty()) return err;
            events.push_back(std::move(e));
        }
        return {};
    }

    Plan* plan(int bucket, Variant variant, std::string& error) {
        const auto key = std::make_pair(bucket, static_cast<int>(variant));
        if (const auto it = plans.find(key); it != plans.end()) return it->second.get();
        const auto t0 = Clock::now();
        auto p = std::make_unique<Plan>();
        const auto b = static_cast<std::size_t>(bucket);
        const std::size_t input_floats = b * k::kRows * static_cast<std::size_t>(shape.input_planes);
        const std::size_t activation = b * k::kRows * static_cast<std::size_t>(shape.channels);
        const std::size_t out_floats = b * static_cast<std::size_t>(k::kPolicySize + shape.players);
        p->input = DeviceBuffer<float>::zeroed(input_floats, error);
        if (!error.empty()) return nullptr;
        for (DeviceBuffer<float>* buffer : {&p->x, &p->t, &p->u}) {
            *buffer = DeviceBuffer<float>::zeroed(activation, error);
            if (!error.empty()) return nullptr;
        }
        p->out = DeviceBuffer<float>::zeroed(out_floats, error);
        if (!error.empty()) return nullptr;
        if (options.memory == HostMemory::Pinned) {
            p->in_pinned = PinnedBuffer::make(input_floats, error);
            if (!error.empty()) return nullptr;
            p->out_pinned = PinnedBuffer::make(out_floats, error);
            if (!error.empty()) return nullptr;
            p->in_stage = p->in_pinned.span();
            p->out_stage = p->out_pinned.span();
        } else {
            p->in_pageable.assign(input_floats, 0.0f);
            p->out_pageable.assign(out_floats, 0.0f);
            p->in_stage = std::span<float>(p->in_pageable);
            p->out_stage = std::span<float>(p->out_pageable);
        }
        setup.push_back(BucketSetup{bucket, variant, ms_since(t0)});
        return plans.emplace(key, std::move(p)).first->second.get();
    }
};

// Starts the kernels of kernels.cuh on the stream; in instrumented mode records an event after each one. After the first
// failure it does nothing more and keeps the error.
struct DeviceLauncher {
    cudaStream_t stream;
    const int* gather;
    bool instrumented;
    std::vector<Event>& events;
    std::size_t next_event;  // index of the next event to record
    std::vector<std::string> names;
    std::string error;

    void mark(const std::string& label) {
        error = check(cudaEventRecord(events[next_event].get(), stream), "cudaEventRecord after " + label);
        ++next_event;
        names.push_back(label);
    }
    void launched(const std::string& label) {
        error = check(cudaGetLastError(), "kernel launch " + label);
        if (error.empty() && instrumented) mark(label);
    }

    void conv(k::ConvKind kind, const float* in, const k::ConvLayer& layer, const float* res, float* out, const k::ConvArgs& a,
              const std::string& label) {
        if (!error.empty()) return;
        if (kind == k::ConvKind::Naive) {
            const k::LaunchDims d = k::naive_dims(a.out_channels, a.batch);
            k::hexconv_naive<<<grid_of(d), block_of(d), 0, stream>>>(in, gather, layer.weight, layer.bias, res, out, a);
        } else {
            const k::LaunchDims d = k::tiled_dims(a.out_channels, a.batch);
            k::hexconv_tiled<<<grid_of(d), block_of(d), 0, stream>>>(in, gather, layer.weight, layer.bias, res, out, a);
        }
        launched(label);
    }
    void policy(const float* trunk, const float* weight, const float* bias, float* out, const k::HeadArgs& a, const std::string& label) {
        if (!error.empty()) return;
        const k::LaunchDims d = k::policy_dims(a.batch);
        k::head_policy<<<grid_of(d), block_of(d), 0, stream>>>(trunk, weight, bias, out, a);
        launched(label);
    }
    void value(const float* trunk, const float* w1, const float* b1, const float* w2, const float* b2, float* out, const k::HeadArgs& a,
               const std::string& label) {
        if (!error.empty()) return;
        const k::LaunchDims d = k::value_dims(a.batch);
        k::head_value<<<grid_of(d), block_of(d), 0, stream>>>(trunk, w1, b1, w2, b2, out, a);
        launched(label);
    }
};

std::string check_sizes(const Shape& s, const Weights& w) {
    const auto c = static_cast<std::size_t>(s.channels);
    const auto planes = static_cast<std::size_t>(s.input_planes);
    const auto h = static_cast<std::size_t>(s.hidden);
    const auto p = static_cast<std::size_t>(s.players);
    const auto wrong = [](const char* what, std::size_t have, std::size_t want) {
        return have == want ? std::string() : std::string(what) + " has " + std::to_string(have) + " floats, expected " + std::to_string(want);
    };
    std::string e;
    const auto first = [&e](std::string next) {
        if (e.empty()) e = std::move(next);
    };
    first(wrong("gather", w.gather.size(), static_cast<std::size_t>(k::kCells) * k::kStencil));
    first(wrong("conv_in.weight", w.conv_in.weight.size(), c * k::kStencil * planes));
    first(wrong("conv_in.bias", w.conv_in.bias.size(), c));
    first(w.blocks.size() == static_cast<std::size_t>(s.blocks) ? std::string() : "wrong number of blocks");
    for (const auto& block : w.blocks) {
        for (const ConvWeights& conv : block) {
            first(wrong("block weight", conv.weight.size(), c * k::kStencil * c));
            first(wrong("block bias", conv.bias.size(), c));
        }
    }
    first(wrong("policy weight", w.policy_weight.size(), 2 * c));
    first(wrong("policy bias", w.policy_bias.size(), 2));
    first(wrong("dense1 weight", w.dense1_weight.size(), h * c));
    first(wrong("dense1 bias", w.dense1_bias.size(), h));
    first(wrong("dense2 weight", w.dense2_weight.size(), p * h));
    first(wrong("dense2 bias", w.dense2_bias.size(), p));
    return e;
}

std::string forward_impl(State& m, std::span<const float> input, const Call& call, std::span<float> logits, std::span<float> values,
                         CallTimes& times) {
    const Shape& s = m.shape;
    times = CallTimes{};
    if (call.n < 1 || call.bucket < call.n || call.bucket > m.options.max_bucket) {
        return "n and bucket must satisfy 1 <= n <= bucket <= " + std::to_string(m.options.max_bucket) + ", got n=" + std::to_string(call.n) +
               " bucket=" + std::to_string(call.bucket);
    }
    const auto n = static_cast<std::size_t>(call.n);
    const auto bucket = static_cast<std::size_t>(call.bucket);
    const std::size_t in_floats = n * k::kRows * static_cast<std::size_t>(s.input_planes);
    const std::size_t logit_floats = n * k::kPolicySize;
    const std::size_t value_floats = n * static_cast<std::size_t>(s.players);
    if (input.size() < in_floats) return "input needs " + std::to_string(in_floats) + " floats, has " + std::to_string(input.size());
    if (logits.size() < logit_floats) return "logits need " + std::to_string(logit_floats) + " floats, have " + std::to_string(logits.size());
    if (values.size() < value_floats) return "values need " + std::to_string(value_floats) + " floats, have " + std::to_string(values.size());

    std::string error;
    Plan* plan = m.plan(call.bucket, call.variant, error);
    if (plan == nullptr) return error;
    const cudaStream_t stream = m.stream.get();
    const std::size_t kernel_count = 1 + 2 * static_cast<std::size_t>(s.blocks) + 2;
    // events: 0 start, 1 after the copy in, then one per kernel (instrumented) or one in all, last the end of the copy out
    if (error = m.ensure_events(2 + kernel_count + 1); !error.empty()) return error;

    auto t = Clock::now();
    std::copy_n(input.begin(), in_floats, plan->in_stage.begin());
    times.copy_in_ms = ms_since(t);

    if (error = check(cudaEventRecord(m.events[0].get(), stream), "cudaEventRecord"); !error.empty()) return error;
    if (error = check(cudaMemcpyAsync(plan->input.get(), plan->in_stage.data(), in_floats * sizeof(float), cudaMemcpyHostToDevice, stream),
                      "cudaMemcpyAsync host to device");
        !error.empty()) {
        return error;
    }
    if (error = check(cudaEventRecord(m.events[1].get(), stream), "cudaEventRecord"); !error.empty()) return error;

    DeviceLauncher launcher{stream, m.view.gather, m.options.instrumented, m.events, 2, {}, {}};
    k::PassShape pass;
    pass.batch = call.bucket;
    pass.input_planes = s.input_planes;
    pass.channels = s.channels;
    pass.hidden = s.hidden;
    pass.players = s.players;
    pass.apply_tanh = call.apply_tanh;
    pass.conv = call.variant == Variant::Tiled ? k::ConvKind::Tiled : k::ConvKind::Naive;
    k::Work work;
    work.input = plan->input.get();
    work.x = plan->x.get();
    work.t = plan->t.get();
    work.u = plan->u.get();
    work.out = plan->out.get();
    k::run_forward(launcher, m.view, pass, work);
    if (!launcher.error.empty()) return launcher.error;
    if (!m.options.instrumented) {
        launcher.mark("all kernels");
        if (!launcher.error.empty()) return launcher.error;
    }
    const std::size_t kernels_end = launcher.next_event - 1;

    const std::size_t out_floats = bucket * static_cast<std::size_t>(k::kPolicySize + s.players);
    if (error = check(cudaMemcpyAsync(plan->out_stage.data(), plan->out.get(), out_floats * sizeof(float), cudaMemcpyDeviceToHost, stream),
                      "cudaMemcpyAsync device to host");
        !error.empty()) {
        return error;
    }
    const std::size_t end = launcher.next_event;
    if (error = check(cudaEventRecord(m.events[end].get(), stream), "cudaEventRecord"); !error.empty()) return error;
    if (error = check(cudaEventSynchronize(m.events[end].get()), "cudaEventSynchronize (a kernel or a copy failed)"); !error.empty()) return error;
    if (error = check(cudaGetLastError(), "after the forward pass"); !error.empty()) return error;

    const auto elapsed = [&](std::size_t from, std::size_t to, double& ms, const char* what) {
        float f = 0;
        const std::string e = check(cudaEventElapsedTime(&f, m.events[from].get(), m.events[to].get()), what);
        ms = static_cast<double>(f);
        return e;
    };
    if (error = elapsed(0, 1, times.h2d_ms, "cudaEventElapsedTime h2d"); !error.empty()) return error;
    if (error = elapsed(1, kernels_end, times.kernel_ms, "cudaEventElapsedTime kernels"); !error.empty()) return error;
    if (error = elapsed(kernels_end, end, times.d2h_ms, "cudaEventElapsedTime d2h"); !error.empty()) return error;
    if (m.options.instrumented) {
        std::size_t previous = 1;
        for (std::size_t i = 0; i < launcher.names.size(); ++i) {
            KernelTime kt;
            kt.name = launcher.names[i];
            if (error = elapsed(previous, 2 + i, kt.ms, "cudaEventElapsedTime kernel"); !error.empty()) return error;
            previous = 2 + i;
            times.kernels.push_back(std::move(kt));
        }
    }

    t = Clock::now();
    std::copy_n(plan->out_stage.begin(), logit_floats, logits.begin());
    std::copy_n(plan->out_stage.begin() + static_cast<std::ptrdiff_t>(bucket * k::kPolicySize), value_floats, values.begin());
    times.copy_out_ms = ms_since(t);
    return {};
}

class DeviceNet final : public CudaNet {
public:
    State m;
    const Shape& shape() const override { return m.shape; }
    const Options& options() const override { return m.options; }
    double init_ms() const override { return m.init_ms; }
    const std::vector<BucketSetup>& setup() const override { return m.setup; }
    std::string forward_raw(std::span<const float> input, const Call& call, std::span<float> logits, std::span<float> values,
                            CallTimes& times) override {
        return forward_impl(m, input, call, logits, values, times);
    }
};

}  // namespace

std::string_view build_kind() { return "cuda"; }

int device_count() {
    int n = 0;
    if (cudaGetDeviceCount(&n) != cudaSuccess) {
        cudaGetLastError();  // clear the sticky error of a missing driver
        return 0;
    }
    return n;
}

std::string device_name() {
    if (device_count() < 1) return {};
    cudaDeviceProp prop{};
    if (cudaGetDeviceProperties(&prop, 0) != cudaSuccess) return {};
    return std::string(prop.name);
}

std::unique_ptr<CudaNet> CudaNet::create(const Shape& shape, const Weights& weights, const Options& options, std::string& error) {
    error.clear();
    if (shape.channels < 1 || shape.channels > k::kMaxWidth || shape.hidden < 1 || shape.hidden > k::kMaxWidth || shape.players < 1 ||
        shape.blocks < 0 || shape.input_planes < 1 || options.max_bucket < 1) {
        error = "shape out of range: channels and hidden must be in [1, 1024], players and input planes at least 1, blocks at least 0";
        return nullptr;
    }
    const long long widest = std::max(shape.channels, shape.input_planes);
    if (static_cast<long long>(options.max_bucket) * k::kRows * widest > INT_MAX) {
        error = "max_bucket " + std::to_string(options.max_bucket) + " is too large for " + std::to_string(widest) + " channels";
        return nullptr;
    }
    if (error = check_sizes(shape, weights); !error.empty()) return nullptr;
    if (device_count() < 1) {
        error = "no CUDA device found";
        return nullptr;
    }
    const auto t0 = Clock::now();
    if (error = check(cudaSetDevice(0), "cudaSetDevice"); !error.empty()) return nullptr;
    if (error = check(cudaFree(nullptr), "context creation"); !error.empty()) return nullptr;  // creates the context, loads the module
    auto net = std::make_unique<DeviceNet>();
    State* impl = &net->m;
    impl->shape = shape;
    impl->options = options;
    if (error = impl->stream.create(); !error.empty()) return nullptr;
    impl->gather = DeviceBuffer<int>::zeroed(weights.gather.size(), error);
    if (!error.empty()) return nullptr;
    if (error = check(cudaMemcpy(impl->gather.get(), weights.gather.data(), weights.gather.size_bytes(), cudaMemcpyHostToDevice), "cudaMemcpy of the gather table");
        !error.empty()) {
        return nullptr;
    }
    k::NetView& view = impl->view;
    view.gather = impl->gather.get();
    const std::vector<float> zero(1, 0.0f);
    view.no_residual = impl->upload(zero, error);
    if (!error.empty()) return nullptr;
    const auto layer = [&](const ConvWeights& conv) {
        k::ConvLayer l;
        l.weight = impl->upload(conv.weight, error);
        if (error.empty()) l.bias = impl->upload(conv.bias, error);
        return l;
    };
    view.conv_in = layer(weights.conv_in);
    if (!error.empty()) return nullptr;
    for (const auto& block : weights.blocks) {
        k::BlockLayers b;
        b.conv1 = layer(block[0]);
        if (error.empty()) b.conv2 = layer(block[1]);
        if (!error.empty()) return nullptr;
        view.blocks.push_back(b);
    }
    view.policy_weight = impl->upload(weights.policy_weight, error);
    if (error.empty()) view.policy_bias = impl->upload(weights.policy_bias, error);
    if (error.empty()) view.dense1_weight = impl->upload(weights.dense1_weight, error);
    if (error.empty()) view.dense1_bias = impl->upload(weights.dense1_bias, error);
    if (error.empty()) view.dense2_weight = impl->upload(weights.dense2_weight, error);
    if (error.empty()) view.dense2_bias = impl->upload(weights.dense2_bias, error);
    if (!error.empty()) return nullptr;
    impl->init_ms = ms_since(t0);
    return net;
}

}  // namespace sh::cuda
